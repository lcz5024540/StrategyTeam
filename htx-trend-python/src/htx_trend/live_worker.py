"""Explicitly authorised HTX live trend worker.

It shares the same model generation as observation, but policy selection and
all account/exchange checks are delegated to :class:`HtxTrendEntryExecutor`.
Creating this class does not grant trade authority; the executor does.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from os import environ
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal
from hashlib import sha256
from typing import Any
import logging
from importlib.metadata import PackageNotFoundError, version

from .diagnostics import CandidateDiagnosis, RejectCode, heartbeat_detail
from .execution_service import HtxTrendEntryExecutor
from .market_data import HtxMarketData, period_seconds
from .models import TrendSide
from .java_entry_contract import JavaEntryConfig, explain_candidates
from .policy import StrategyPolicy, PolicySelection, select_policy, explain_policy
from .strategy_context import MarketContext, MarketRegime
from .strategy_platform import ExecutionMode, StrategyWorker, WorkerHealth
from .mysql_store import HtxTrendMySqlRepository, RuntimeInstance
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact
from .strategy_decision import HtxStrategyDecisionEngine, LEGACY_STRATEGY_VERSION
from .timeframes import TimeframeProfile
from .strategy_profiles import profile_entry_config, profile_for_version, profile_strategy
from .research.regime import RegimeStatus, profile_regime


DEFAULT_LIVE_CONTRACTS = ("BTC-USDT", "ETH-USDT")

# Broad observation universe, never the implicit LIVE pool.
DEFAULT_TREND_CONTRACTS = (
    "BTC-USDT", "ETH-USDT", "SOL-USDT", "XRP-USDT", "BNB-USDT", "DOGE-USDT", "ADA-USDT",
    "TRX-USDT", "LINK-USDT", "AVAX-USDT", "SUI-USDT", "HYPE-USDT", "HBAR-USDT", "PONS-USDT",
    "BCH-USDT", "LTC-USDT", "DOT-USDT", "UNI-USDT", "AAVE-USDT", "NEAR-USDT", "ETC-USDT",
    "XLM-USDT", "FIL-USDT", "ARB-USDT", "OP-USDT", "ATOM-USDT", "ICP-USDT", "APT-USDT",
    "PEPE-USDT", "SHIB-USDT", "WIF-USDT", "BONK-USDT", "FLOKI-USDT", "TAO-USDT", "WLD-USDT",
    "TIA-USDT", "JUP-USDT", "ENA-USDT", "ONDO-USDT", "ALGO-USDT", "OKB-USDT", "DASH-USDT",
    "ZEC-USDT", "XAUT-USDT", "PAXG-USDT", "PEOPLE-USDT", "ORDI-USDT", "BOME-USDT", "RAY-USDT",
    "CHZ-USDT", "ETHFI-USDT", "ACE-USDT",
)


@dataclass(frozen=True, slots=True)
class LiveTrendWorkerConfig:
    contracts: tuple[str, ...]
    enabled: bool = False
    observe_only: bool = True
    entry_enabled: bool = True
    entry_period: str = "15min"
    trend_period: str = "60min"
    timeframe_profile: TimeframeProfile = TimeframeProfile.INTRADAY_15M
    kline_size: int = 160
    max_concurrency: int = 6
    # The live worker deliberately shares the Java ensemble defaults.  Python
    # research models may evolve independently but cannot alter this path.
    java_entry: JavaEntryConfig = JavaEntryConfig.from_env()
    # Mirrors HtxTrendProperties: scan is per completed bar, then entries are
    # attempted only in this post-close window. A cached candidate can carry
    # into a later scheduler tick without recomputing a different signal.
    entry_window_start_seconds_after_close: int = 15
    entry_window_duration_seconds: int = 45
    signal_freshness_seconds: int = 600
    candle_close_grace_seconds: int = 10
    market_anchor_contracts: tuple[str, ...] = ("BTC-USDT", "ETH-USDT")
    market_anchor_periods: tuple[str, ...] = ("15min", "30min", "60min", "4hour")
    market_anchor_kline_size: int = 80
    market_anchor_fast_ema_period: int = 8
    market_anchor_slow_ema_period: int = 21
    market_anchor_min_aligned_periods_per_symbol: int = 2
    market_anchor_min_aligned_signals: int = 4
    market_anchor_min_aligned_symbols: int = 2
    market_anchor_max_opposite_signals: int = 1
    market_breadth_lookback_seconds: int = 35 * 60
    market_breadth_min_symbols: int = 20
    market_breadth_threshold: Decimal = Decimal("0.70")
    market_breadth_opposite_max: Decimal = Decimal("0.15")
    adaptive_sizing_enabled: bool = True
    market_anchor_enabled: bool = True
    max_active_positions: int = 5
    # Java's multi-contract path is intentionally opt-in.  Its legacy path is
    # not implemented by the Python worker, so allowing this worker to start
    # with the flag off would make it trade a different strategy.
    combination_entry_enabled: bool = False
    # The legacy strategy can be promoted to the independently managed 4h
    # lifecycle only through an explicit profile-level deployment switch.  It
    # is never inferred from HTX_TREND_ENTRY_ENABLED.
    allow_legacy_swing_live: bool = False

    @classmethod
    def from_env(cls) -> "LiveTrendWorkerConfig":
        """Load the Java ``htx.trend`` live-scan configuration exactly.

        Python must use the same HTX_TREND_* source of truth as Java.  In
        particular, do not retain the former COINSTRATEGY_CONTRACTS-only
        override: it could silently scan a different universe.
        """
        def integer(name: str, default: int) -> int:
            return int(environ.get(name, str(default)))

        def decimal(name: str, default: Decimal) -> Decimal:
            return Decimal(environ.get(name, str(default)))

        def flag(name: str, default: bool) -> bool:
            return environ.get(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}

        def values(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
            raw = environ.get(name, ",".join(default))
            return tuple(dict.fromkeys(item.strip().upper() for item in raw.split(",") if item.strip()))

        defaults = cls(())
        config = cls(
            contracts=values("HTX_TREND_CONTRACTS", DEFAULT_LIVE_CONTRACTS),
            enabled=flag("HTX_TREND_ENABLED", defaults.enabled),
            observe_only=flag("HTX_TREND_OBSERVE_ONLY", defaults.observe_only),
            entry_enabled=flag("HTX_TREND_ENTRY_ENABLED", defaults.entry_enabled),
            entry_period=environ.get("HTX_TREND_PERIOD", defaults.entry_period),
            trend_period=environ.get("HTX_TREND_TREND_PERIOD", defaults.trend_period),
            kline_size=integer("HTX_TREND_KLINE_SIZE", defaults.kline_size),
            max_concurrency=integer("HTX_TREND_CANDIDATE_SCAN_CONCURRENCY", defaults.max_concurrency),
            java_entry=JavaEntryConfig.from_env(),
            entry_window_start_seconds_after_close=integer("HTX_TREND_ENTRY_WINDOW_START_SECONDS_AFTER_CLOSE", defaults.entry_window_start_seconds_after_close),
            entry_window_duration_seconds=integer("HTX_TREND_ENTRY_WINDOW_DURATION_SECONDS", defaults.entry_window_duration_seconds),
            signal_freshness_seconds=integer("HTX_TREND_MAX_ENTRY_SIGNAL_AGE_SECONDS", defaults.signal_freshness_seconds),
            candle_close_grace_seconds=integer("HTX_TREND_CANDLE_CLOSE_GRACE_SECONDS", defaults.candle_close_grace_seconds),
            market_anchor_contracts=values("HTX_TREND_MARKET_ANCHOR_CONTRACTS", defaults.market_anchor_contracts),
            market_anchor_periods=values("HTX_TREND_MARKET_ANCHOR_PERIODS", defaults.market_anchor_periods),
            market_anchor_kline_size=integer("HTX_TREND_MARKET_ANCHOR_KLINE_SIZE", defaults.market_anchor_kline_size),
            market_anchor_fast_ema_period=integer("HTX_TREND_MARKET_ANCHOR_FAST_EMA_PERIOD", defaults.market_anchor_fast_ema_period),
            market_anchor_slow_ema_period=integer("HTX_TREND_MARKET_ANCHOR_SLOW_EMA_PERIOD", defaults.market_anchor_slow_ema_period),
            market_anchor_min_aligned_periods_per_symbol=integer("HTX_TREND_MARKET_ANCHOR_MIN_ALIGNED_PERIODS_PER_SYMBOL", defaults.market_anchor_min_aligned_periods_per_symbol),
            market_anchor_min_aligned_signals=integer("HTX_TREND_MARKET_ANCHOR_MIN_ALIGNED_SIGNALS", defaults.market_anchor_min_aligned_signals),
            market_anchor_min_aligned_symbols=integer("HTX_TREND_MARKET_ANCHOR_MIN_ALIGNED_SYMBOLS", defaults.market_anchor_min_aligned_symbols),
            market_anchor_max_opposite_signals=integer("HTX_TREND_MARKET_ANCHOR_MAX_OPPOSITE_SIGNALS", defaults.market_anchor_max_opposite_signals),
            market_breadth_lookback_seconds=integer("HTX_TREND_MARKET_BREADTH_LOOKBACK_MINUTES", 35) * 60,
            market_breadth_min_symbols=integer("HTX_TREND_MARKET_BREADTH_MIN_SYMBOLS", defaults.market_breadth_min_symbols),
            market_breadth_threshold=decimal("HTX_TREND_MARKET_BREADTH_THRESHOLD", defaults.market_breadth_threshold),
            market_breadth_opposite_max=decimal("HTX_TREND_MARKET_BREADTH_OPPOSITE_MAX", defaults.market_breadth_opposite_max),
            adaptive_sizing_enabled=flag("HTX_TREND_ADAPTIVE_SIZING_ENABLED", defaults.adaptive_sizing_enabled),
            market_anchor_enabled=flag("HTX_TREND_MARKET_ANCHOR_ENABLED", defaults.market_anchor_enabled),
            max_active_positions=integer("HTX_TREND_MAX_ACTIVE_POSITIONS", defaults.max_active_positions),
            combination_entry_enabled=flag("HTX_TREND_COMBINATION_ENTRY_ENABLED", defaults.combination_entry_enabled),
            allow_legacy_swing_live=flag("HTX_TREND_ALLOW_LEGACY_4H_LIVE", defaults.allow_legacy_swing_live),
        )
        profile_name = environ.get("HTX_TREND_TIMEFRAME_PROFILE", "").strip().upper()
        profile = TimeframeProfile(profile_name) if profile_name else TimeframeProfile.for_period(config.entry_period)
        profile.validate(config.entry_period, config.trend_period)
        return replace(config, timeframe_profile=profile)

    @classmethod
    def profiles_from_env(cls, *, strategy_version: str = LEGACY_STRATEGY_VERSION) -> tuple["LiveTrendWorkerConfig", ...]:
        """Build independent profile runtimes while retaining legacy single-period configuration."""
        def flag(name: str, default: bool) -> bool:
            return environ.get(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}

        raw = environ.get("HTX_TREND_PROFILES", "").strip()
        if not raw:
            version_profile = profile_for_version(strategy_version)
            if version_profile is None:
                return (cls.from_env(),)
            contract = version_profile.contract
            base = cls.from_env()
            live = flag(f"HTX_TREND_{version_profile.value}_LIVE_ENABLED", False)
            return (replace(base, entry_period=contract.entry_period, trend_period=contract.trend_period,
                            timeframe_profile=version_profile, entry_enabled=base.entry_enabled and live,
                            java_entry=profile_entry_config(version_profile),
                            allow_legacy_swing_live=False),)
        saved = {name: environ.get(name) for name in (
            "HTX_TREND_PERIOD", "HTX_TREND_TREND_PERIOD", "HTX_TREND_TIMEFRAME_PROFILE")}
        try:
            environ["HTX_TREND_PERIOD"] = "15min"
            environ["HTX_TREND_TREND_PERIOD"] = "60min"
            environ["HTX_TREND_TIMEFRAME_PROFILE"] = TimeframeProfile.INTRADAY_15M.value
            base = cls.from_env()
        finally:
            for name, value in saved.items():
                if value is None: environ.pop(name, None)
                else: environ[name] = value
        profiles = tuple(dict.fromkeys(
            TimeframeProfile(item.strip().upper()) for item in raw.split(",") if item.strip()))
        if not profiles:
            raise ValueError("HTX_TREND_PROFILES must contain at least one profile")
        version_profile = profile_for_version(strategy_version)
        if version_profile is not None and profiles != (version_profile,):
            raise ValueError(
                f"{strategy_version} must run only {version_profile.value}; configure a separate runtime instance"
            )
        output = []
        for profile in profiles:
            contract = profile.contract
            default_live = base.entry_enabled if profile is TimeframeProfile.INTRADAY_15M else False
            live = environ.get(f"HTX_TREND_{profile.value}_LIVE_ENABLED", str(default_live)).strip().lower() in {
                "1", "true", "yes", "on"}
            output.append(replace(base, entry_period=contract.entry_period, trend_period=contract.trend_period,
                                  timeframe_profile=profile, entry_enabled=base.entry_enabled and live,
                                  java_entry=(profile_entry_config(profile) if version_profile is not None
                                              else base.java_entry),
                                  allow_legacy_swing_live=(profile is TimeframeProfile.SWING_4H
                                                           and flag("HTX_TREND_ALLOW_LEGACY_4H_LIVE", False))))
        return tuple(output)


class _EvidenceUnavailable(RuntimeError):
    pass


class _ContextUnavailable(RuntimeError):
    def __init__(self, windows, missing_periods):
        self.flags = {"required_bars": 121, "missing_periods": list(missing_periods),
                      "closed_bars": {p: len(windows.get(p, ())) for p in missing_periods}}
        super().__init__("required closed-bar profile context unavailable")


@dataclass(frozen=True, slots=True)
class _Scan:
    contract: str
    entry: Any
    trend: Any
    signal: Any
    candidates: tuple[Any, ...]
    book: Any
    contract_spec: Any
    funding: Any
    decision_hash: str | None = None
    diagnosis: CandidateDiagnosis | None = None
    regime_snapshot: Any | None = None


class LiveTrendWorker(StrategyWorker):
    """Real execution path; only a LIVE instance with authorised executor may use it."""

    def __init__(self, instance: RuntimeInstance, repository: HtxTrendMySqlRepository, market: HtxMarketData,
                 executor: HtxTrendEntryExecutor, config: LiveTrendWorkerConfig,
                 trend_config: JavaEntryConfig | None = None, *, position_manager=None, writer_guard=None,
                 admission: StrategyAdmissionArtifact | None = None) -> None:
        if instance.execution_mode != ExecutionMode.LIVE:
            raise ValueError("LiveTrendWorker requires a LIVE strategy instance")
        if not config.enabled:
            raise ValueError("LiveTrendWorker requires HTX_TREND_ENABLED=true")
        if not config.contracts:
            raise ValueError("live trend worker requires at least one contract")
        if not config.combination_entry_enabled:
            raise ValueError("LiveTrendWorker requires HTX_TREND_COMBINATION_ENTRY_ENABLED=true; Java legacy entry is not implemented in Python")
        config.timeframe_profile.validate(config.entry_period, config.trend_period)
        if (config.timeframe_profile is TimeframeProfile.SWING_4H and not config.observe_only
                and config.entry_enabled and instance.strategy_name == LEGACY_STRATEGY_VERSION
                and not config.allow_legacy_swing_live):
            raise ValueError("SWING_4H legacy live entry requires HTX_TREND_ALLOW_LEGACY_4H_LIVE=true")
        self._instance, self._repository, self._market, self._executor = instance, repository, market, executor
        self._config, self._trend_config, self._stopped = config, trend_config or config.java_entry, False
        self._diagnosis_rows: dict[tuple[str, int], dict[str, Any]] = {}
        self._raw_candidates = self._policy_selected = 0
        self._market_regime_source = "UNCLASSIFIED"
        try:
            self._package_version = version("coinstrategy-htx-trend")
        except PackageNotFoundError:
            self._package_version = None
        self._admission = admission
        self._last_scanned_close_epoch: int | None = None
        self._pending: dict[str, tuple[PolicySelection, _Scan, int]] = {}
        self._last_context = MarketContext.neutral()
        self._position_manager, self._writer_guard = position_manager, writer_guard

    def runtime_key(self):
        return self._config.timeframe_profile.value

    def export_runtime(self):
        from .runtime_checkpoint import fingerprint
        return dict(config=fingerprint((self._config,self._trend_config,self._instance.strategy_name)),
            last_scanned=self._last_scanned_close_epoch, pending=self._pending, context=self._last_context,
            context_at=getattr(self,'_context_at',None), diagnosis=self._diagnosis_rows,
            failures=getattr(self,'_last_scan_failures',()), coverage=getattr(self,'_last_scan_coverage',0),
            raw=self._raw_candidates, selected=self._policy_selected, regime_source=self._market_regime_source)

    def restore_runtime(self, state):
        from .runtime_checkpoint import fingerprint
        self._last_scanned_close_epoch=state['last_scanned']
        self._last_context=state['context']
        self._context_at=state['context_at']
        self._pending=state['pending']
        if state['config'] != fingerprint((self._config,self._trend_config,self._instance.strategy_name)):
            # Configuration changes do not resurrect a completed bar or execute
            # an old candidate under a different policy.
            self._pending={}
        self._diagnosis_rows=state['diagnosis']
        self._last_scan_failures=state['failures']
        self._last_scan_coverage=state['coverage']
        self._raw_candidates,self._policy_selected=state['raw'],state['selected']
        self._market_regime_source=state['regime_source']
        self._recovering=True

    def _checkpoint(self):
        save=getattr(self,'_save_runtime',None)
        if save is not None:
            save()

    def _recovery_context(self, now_epoch):
        if not hasattr(self,'_save_runtime') or not getattr(self,'_recovering',True):
            return self._last_context
        at=getattr(self,'_context_at',None)
        if at is None or now_epoch-at > period_seconds(self._config.entry_period):
            breadth=(self._repository.latest_breadth_stats(
                datetime.fromtimestamp(now_epoch-self._config.market_breadth_lookback_seconds,UTC))
                if self._config.adaptive_sizing_enabled else None)
            anchor=(self._market_anchor_regime(now_epoch,strict=True)
                    if self._config.market_anchor_enabled else MarketRegime.MIXED)
            self._last_context=self.market_context_from_breadth(total_count=breadth.total_count if breadth else 0,
                long_context_count=breadth.long_context_count if breadth else 0,
                short_context_count=breadth.short_context_count if breadth else 0,
                anchor_regime=anchor,config=self._config)
            self._context_at=now_epoch
        self._recovering=False
        return self._last_context

    def stop(self) -> None:
        self._stopped = True

    def prepare_management(self):
        self._writer_guard()
        recovered = self._executor.reconcile_pending()
        now_epoch = int(datetime.now(UTC).timestamp())
        context_error=None
        try:
            management_context=self._recovery_context(now_epoch)
        except Exception as error:
            management_context=None
            context_error='recovery context unavailable:'+type(error).__name__
        management_failures = self._position_manager.manage(now_epoch, management_context)
        if context_error:
            management_failures.append(context_error)
        if any(status.startswith('ERROR:') or status in ('UNKNOWN','INTENT') for status in recovered.values()):
            management_failures.append('pending order recovery incomplete; new risk blocked')
        self._prepared_management=(recovered,management_failures)
        return management_failures

    def tick(self) -> WorkerHealth:
        if self._stopped:
            return WorkerHealth("STOPPED", "worker has been stopped")
        if self._writer_guard is None or self._position_manager is None:
            raise RuntimeError("LIVE worker requires ownership and position management")
        prepared=getattr(self,'_prepared_management',None)
        if prepared is None:
            self.prepare_management()
            prepared=self._prepared_management
        recovered,management_failures=prepared
        self._prepared_management=None
        now_epoch = int(datetime.now(UTC).timestamp())
        if management_failures or getattr(self,'_entry_recovery_blocked',False):
            return WorkerHealth('DEGRADED','; '.join(management_failures) or 'another profile recovery incomplete')
        policies = tuple(StrategyPolicy(value.policy_code, value.policy_version, value.primary_model,
                                        value.confirmation_models, value.base_notional_usdt, value.max_notional_usdt,
                                        value.score_threshold, value.box_window_bars)
                         for value in self._repository.enabled_strategy_policies())
        java_config = replace(self._trend_config, box_window_bars=(policies[0].box_window_bars if policies else self._trend_config.box_window_bars))

        # Java marks a completed entry bar before scanning. Repeated scheduler
        # ticks reuse its cached candidates; they must not refetch 52 contracts
        # and then submit a later, different reading of the same bar.
        stable_close_epoch = self._stable_candle_close_epoch(now_epoch)
        fresh_scan = stable_close_epoch != self._last_scanned_close_epoch
        scans: list[_Scan] = []
        failures: list[str] = list(getattr(self, "_last_scan_failures", ()))
        if fresh_scan:
            self._diagnosis_rows = {}
            self._raw_candidates = self._policy_selected = 0
            # This is intentionally read *before* writing this scan's signals,
            # matching Java's marketBreadthContext() ordering.
            breadth = (self._repository.latest_breadth_stats(
                datetime.fromtimestamp(now_epoch - self._config.market_breadth_lookback_seconds, UTC))
                if self._config.adaptive_sizing_enabled else None)
            scans, failures = self._scan_all(now_epoch, java_config)
            self._last_scan_failures = tuple(failures)
            self._last_scan_coverage = len(scans)
            self._last_scanned_close_epoch = stable_close_epoch
            anchor_regime = self._market_anchor_regime(now_epoch) if self._config.market_anchor_enabled else MarketRegime.MIXED
            context = self.market_context_from_breadth(
                total_count=breadth.total_count if breadth else 0,
                long_context_count=breadth.long_context_count if breadth else 0,
                short_context_count=breadth.short_context_count if breadth else 0,
                anchor_regime=anchor_regime,
                config=self._config,
            )
            self._last_context = context
            self._context_at = now_epoch
            self._market_regime_source = ("BREADTH" if breadth and breadth.total_count >= max(1, self._config.market_breadth_min_symbols)
                                          else "UNCLASSIFIED")
            for scan in scans:
                factor_id = self._persist_observation(scan, context)
                chosen = select_policy(scan.candidates, policies, context)
                diagnosis = scan.diagnosis or explain_candidates(scan.entry.candles, scan.signal, java_config, self._config.entry_period)
                policy_diagnoses = explain_policy(scan.candidates, policies)
                self._raw_candidates += len(scan.candidates)
                self._policy_selected += len(chosen)
                code, stage = diagnosis.code, diagnosis.stage
                flags = dict(diagnosis.flags)
                if not code and not chosen:
                    stage = "POLICY"
                    code = (RejectCode.NO_ENABLED_POLICY if not policies else
                            RejectCode.POLICY_SCORE_BELOW_THRESHOLD if any(
                                item.code == RejectCode.POLICY_SCORE_BELOW_THRESHOLD for item in policy_diagnoses)
                            else RejectCode.POLICY_NOT_MATCHED)
                    rejected = next((item for item in policy_diagnoses if item.code == code), None)
                    if rejected:
                        flags["policy"] = {"model": rejected.model, "confirmations": rejected.confirmations,
                                           "score": str(rejected.score),
                                           "thresholds": [str(value) for value in rejected.thresholds[:4]]}
                self._diagnose(scan, stage=stage, code=code, selected=len(chosen), flags=flags)
                if chosen:
                    shadow = self._config.observe_only or not self._config.entry_enabled
                    initial_gate, initial_reason = _initial_candidate_gate(
                        chosen[0].candidate,
                        now_epoch=now_epoch,
                        entry_period_seconds=period_seconds(self._config.entry_period),
                        signal_freshness_seconds=self._config.signal_freshness_seconds,
                        shadow=shadow,
                    )
                    if initial_gate == "EXPIRED":
                        # A worker can start after a higher-timeframe candle's
                        # entry window and freshness deadline have passed. Do
                        # not label that stale candidate PRE_FLIGHT: it never
                        # reached account/exchange preflight and must be
                        # auditable as expired at admission time.
                        self._diagnose(scan, stage="QUEUE", code=RejectCode.EXPIRED,
                                       selected=0, flags={"reason": "candidate expired before preflight"})
                        self._record_candidate(chosen[0], scan, factor_id, context, shadow=shadow,
                                               gate_result=initial_gate, gate_reason=initial_reason)
                        self._pending.pop(scan.contract, None)
                        continue
                    self._pending[scan.contract] = (chosen[0], scan, factor_id)
                    self._record_candidate(chosen[0], scan, factor_id, context, shadow=shadow,
                                           gate_result=initial_gate, gate_reason=initial_reason)
                else:
                    self._pending.pop(scan.contract, None)
        else:
            context = self._last_context

        for policy, scan, _ in self._pending.values():
            if not self._candidate_fresh(policy.candidate, now_epoch):
                self._diagnose(scan, stage="QUEUE", code=RejectCode.EXPIRED)
        self._expire_pending(now_epoch)
        self._checkpoint()
        ranked = sorted(self._pending.values(), key=lambda item: (item[0].effective_score, item[0].base_score,
                                                                    item[0].candidate.expected_r), reverse=True)
        within_window = self._entry_window_open(now_epoch, stable_close_epoch)
        carryover = any(self._candidate_fresh(item[0].candidate, now_epoch) for item in ranked)
        accepted = 0
        attempted = 0
        execution_block_reason = ("trend observe-only enabled" if self._config.observe_only
                                  else "new trend entries disabled" if not self._config.entry_enabled else None)
        entry_attempt_allowed = ranked and (within_window or carryover)
        if not entry_attempt_allowed:
            for _, scan, _ in ranked:
                self._diagnose(scan, stage="QUEUE", code=RejectCode.OUT_OF_WINDOW)
        elif not self._config.observe_only and not self._config.entry_enabled:
            for _, scan, _ in ranked:
                self._diagnose(scan, stage="QUEUE", code=RejectCode.ENTRY_DISABLED)
                self._pending.pop(scan.contract, None)
        if entry_attempt_allowed and self._config.observe_only:
            active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
            # Java enters openIfEligible in observe-only mode when capacity is
            # available. It then removes every observed candidate, preventing
            # a later flag flip from submitting an old bar as a live order.
            if self._remaining_slots(len(ranked), active) > 0:
                for _, scan, _ in ranked:
                    self._diagnose(scan, stage="QUEUE", code=RejectCode.OBSERVE_ONLY_DISCARDED)
                    self._pending.pop(scan.contract, None)
            else:
                for _, scan, _ in ranked:
                    self._diagnose(scan, stage="QUEUE", code=RejectCode.CAPACITY_FULL)
        elif entry_attempt_allowed and execution_block_reason is None:
            active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
            slots = self._remaining_slots(len(ranked), active)
            for _, scan, _ in ranked[slots:]:
                self._diagnose(scan, stage="QUEUE", code=(RejectCode.CAPACITY_FULL if slots == 0
                                                         else RejectCode.EVALUATION_QUEUED))
            for policy, scan, factor_id in ranked[:slots]:
                if getattr(self,'_drain_requested',lambda:False)():
                    execution_block_reason='DRAINING'
                    break
                # Durable consumed-candidate boundary precedes any order intent.
                # If submission is interrupted, the DB owns recovery of its
                # original client id; the scan queue must never submit it again.
                self._pending.pop(scan.contract, None)
                self._checkpoint()
                candidate = policy.candidate
                run_id = f"trend-entry:{scan.contract}:{self._config.entry_period}:{candidate.bar_time}"
                gate = self._executor.submit(run_id=run_id, candidate=candidate, period=self._config.entry_period,
                    atr=scan.signal.indicators.atr, ema_fast=scan.signal.indicators.ema_fast,
                    ema_slow=scan.signal.indicators.ema_slow, adx=scan.signal.indicators.adx,
                    recent_candles=scan.entry.candles, book=scan.book, contract=scan.contract_spec, funding=scan.funding,
                    market_context=context, factor_snapshot_id=factor_id,
                    timeframe_profile=self._config.timeframe_profile.value, now_epoch=now_epoch,
                    evidence_refs=scan.entry.evidence_refs + scan.trend.evidence_refs)
                self._repository.mark_candidate_execution_gate(run_id, scan.contract, candidate.model.value,
                    "PASSED" if gate.accepted else gate.gate.stage, gate.reason)
                execution_code = None if gate.accepted else (
                    RejectCode.ACCOUNT_PREFLIGHT_FAILED.value
                    if gate.gate.stage == "ACCOUNT_PREFLIGHT" else gate.gate.stage)
                self._diagnose(scan, stage="EXEC", code=execution_code)
                accepted += int(gate.accepted)
                attempted += 1
                # Java removes a candidate after its gate/order evaluation;
                # a new completed candle is required for another attempt.
                self._pending.pop(scan.contract, None)
        detail = (f"scan={'fresh' if fresh_scan else 'cached'} contracts={len(scans) if fresh_scan else 0}/{len(self._config.contracts)} "
                  f"coverage={getattr(self, '_last_scan_coverage', len(scans))}/{len(self._config.contracts)} "
                  f"pending={len(self._pending)} entriesAttempted={attempted} entriesAccepted={accepted} recovered={len(recovered)} "
                  f"entryWindow={'OPEN' if within_window else 'CLOSED'} anchor={context.anchor_regime.value}")
        if execution_block_reason:
            detail += f" entrySkipped={execution_block_reason}"
        if failures:
            detail += " failures=" + ",".join(failures[:8])
        rejections = {row["contract_code"]: row["reject_code"] for row in self._diagnosis_rows.values() if row["reject_code"]}
        detail = heartbeat_detail(detail, period=self._config.entry_period, raw_candidates=self._raw_candidates,
                                  policy_selected=self._policy_selected, rejections=rejections)
        for row in self._diagnosis_rows.values():
            try:
                self._repository.record_entry_diagnosis(**row)
            except Exception as error:
                # Diagnostics must not interrupt an otherwise valid trading tick.
                logging.getLogger(__name__).warning("entry diagnosis unavailable: %s", type(error).__name__)
        active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
        return WorkerHealth("DEGRADED" if failures else "HEALTHY", detail, len(ranked), active)

    def _record_candidate(self, policy: PolicySelection, scan: _Scan, factor_id: int,
                          context: MarketContext, *, shadow: bool, gate_result: str, gate_reason: str) -> None:
        candidate = policy.candidate
        run_id = f"trend-entry:{scan.contract}:{self._config.entry_period}:{candidate.bar_time}"
        self._repository.record_candidate(contract_code=scan.contract, period=self._config.entry_period,
            bar_time=candidate.bar_time, direction=candidate.side.value,
            regime=candidate.market_regime or context.regime.value,
            score=candidate.score, close_price=candidate.entry_price,
            factors={"ema_fast": scan.signal.indicators.ema_fast, "ema_medium": scan.signal.indicators.ema_medium,
                     "ema_slow": scan.signal.indicators.ema_slow, "atr": scan.signal.indicators.atr,
                     "atr_pct": scan.signal.indicators.atr_pct, "adx": scan.signal.indicators.adx,
                     "roc": scan.signal.indicators.roc}, reason="; ".join(candidate.evidence), model=candidate.model.value,
            run_id=run_id, factor_snapshot_id=factor_id, invalidation_price=candidate.invalidation_price,
            target_low=candidate.target_low, target_high=candidate.target_high, expected_r=candidate.expected_r,
            shadow=shadow, confirmation_models=policy.confirmation_models, policy_version=policy.policy.policy_version,
            gate_result=gate_result, gate_reason=gate_reason,
            sizing_multiplier=policy.sizing_multiplier, planned_notional_usdt=policy.planned_notional_usdt,
            timeframe_profile=self._config.timeframe_profile.value,
            strategy_version=self._instance.strategy_name,
            strategy_spec_hash=(profile_strategy(self._config.timeframe_profile).spec_hash
                                if profile_for_version(self._instance.strategy_name) is not None else None))

    def _diagnose(self, scan: _Scan | None, *, stage: str | None, code: str | None,
                  selected: int | None = None, flags: dict | None = None,
                  contract: str | None = None, bar_time: int | None = None) -> None:
        if scan is not None:
            contract = scan.contract
            bar_time = scan.signal.bar_time
            if bar_time is None:
                bar_time = max((c.open_time for c in scan.entry.candles), default=None)
        if bar_time is None:
            bar_time = (self._last_scanned_close_epoch or 0) - period_seconds(self._config.entry_period)
        key = (contract, bar_time)
        previous = self._diagnosis_rows.get(key)
        if previous and previous["reject_code"] and previous["reject_code"] not in (
                RejectCode.CAPACITY_FULL, RejectCode.EVALUATION_QUEUED) and not (stage == "EXEC" and code is None):
            return  # Preserve terminal rejection; a queue observation is not a final decision.
        signal = scan.signal if scan else None
        self._diagnosis_rows[key] = dict(
            instance_key=self._instance.instance_key,
            run_id=f"trend-entry:{contract}:{self._config.entry_period}:{bar_time}",
            contract_code=contract, period=self._config.entry_period, bar_time=bar_time,
            timeframe_profile=self._config.timeframe_profile.value,
            signal_side=signal.side.value if signal else None, signal_score=signal.score if signal else None,
            score_gap=signal.score_gap if signal else None, regime=signal.regime if signal else None,
            market_regime_source=self._market_regime_source if scan else "UNCLASSIFIED",
            stage=stage, reject_code=code, raw_candidate_count=len(scan.candidates) if scan else 0,
            policy_selected_count=selected if selected is not None else previous["policy_selected_count"] if previous else int(scan is not None),
            flags=flags if flags is not None else previous["flags"] if previous else {},
            decision_hash=scan.decision_hash if scan else None, package_version=self._package_version)

    def _remaining_slots(self, candidate_count: int, active_position_count: int) -> int:
        """Literal Java combination-mode capacity semantics.

        ``maxActivePositions=0`` is unlimited only in the combined-candidate
        mode.  It cannot be converted to Python's ordinary ``max(0, ...)``
        calculation without suppressing every qualified candidate.
        """
        if self._config.combination_entry_enabled and self._config.max_active_positions == 0:
            return max(0, candidate_count)
        return max(0, self._config.max_active_positions - active_position_count)

    @staticmethod
    def market_context_from_breadth(*, total_count: int, long_context_count: int, short_context_count: int,
                                    anchor_regime: MarketRegime,
                                    config: LiveTrendWorkerConfig) -> MarketContext:
        """Literal Java breadth classification from persisted latest signals."""
        total = max(0, total_count)
        long_count, short_count = max(0, long_context_count), max(0, short_context_count)
        long_ratio = Decimal(long_count) / Decimal(total) if total else Decimal("0")
        short_ratio = Decimal(short_count) / Decimal(total) if total else Decimal("0")
        if total < max(1, config.market_breadth_min_symbols):
            regime, side, ratio = MarketRegime.MIXED, TrendSide.FLAT, Decimal("0")
        elif long_ratio >= config.market_breadth_threshold and short_ratio <= config.market_breadth_opposite_max:
            regime, side, ratio = MarketRegime.BROAD_UP, TrendSide.LONG, long_ratio
        elif short_ratio >= config.market_breadth_threshold and long_ratio <= config.market_breadth_opposite_max:
            regime, side, ratio = MarketRegime.BROAD_DOWN, TrendSide.SHORT, short_ratio
        else:
            regime, side, ratio = MarketRegime.MIXED, TrendSide.FLAT, Decimal("0")
        anchor_side = (TrendSide.LONG if anchor_regime is MarketRegime.BROAD_UP else
                       TrendSide.SHORT if anchor_regime is MarketRegime.BROAD_DOWN else TrendSide.FLAT)
        return MarketContext(regime=regime, breadth_side=side, breadth_ratio=ratio,
                             btc_anchor=anchor_side, eth_anchor=anchor_side,
                             anchor_regime=anchor_regime)

    def _stable_candle_close_epoch(self, now_epoch: int) -> int:
        period = period_seconds(self._config.entry_period)
        stable_now = now_epoch - max(0, self._config.candle_close_grace_seconds)
        return stable_now - stable_now % period

    def _entry_window_open(self, now_epoch: int, close_epoch: int) -> bool:
        start = close_epoch + self._config.entry_window_start_seconds_after_close
        return start <= now_epoch <= start + self._config.entry_window_duration_seconds

    def _candidate_fresh(self, candidate, now_epoch: int) -> bool:
        return now_epoch <= candidate.bar_time + period_seconds(self._config.entry_period) + self._config.signal_freshness_seconds

    def _expire_pending(self, now_epoch: int) -> None:
        self._pending = {contract: item for contract, item in self._pending.items()
                         if self._candidate_fresh(item[0].candidate, now_epoch)}

    def _market_anchor_regime(self, now_epoch: int, *, strict=False) -> MarketRegime:
        """Exact Java BTC/ETH multi-timeframe anchor classification."""
        long_signals = short_signals = long_symbols = short_symbols = 0
        for contract in self._config.market_anchor_contracts:
            symbol_long = symbol_short = 0
            for period in self._config.market_anchor_periods:
                try:
                    window = self._market.kline_window(contract, period, self._config.market_anchor_kline_size,
                                                        now_epoch=now_epoch)
                    side = self._market_anchor_period_side(window.candles)
                except Exception:
                    if strict:
                        raise
                    side = TrendSide.FLAT
                if side is TrendSide.LONG:
                    long_signals += 1; symbol_long += 1
                elif side is TrendSide.SHORT:
                    short_signals += 1; symbol_short += 1
            if symbol_long >= self._config.market_anchor_min_aligned_periods_per_symbol and symbol_long > symbol_short:
                long_symbols += 1
            elif symbol_short >= self._config.market_anchor_min_aligned_periods_per_symbol and symbol_short > symbol_long:
                short_symbols += 1
        if (long_signals >= self._config.market_anchor_min_aligned_signals
                and long_symbols >= self._config.market_anchor_min_aligned_symbols
                and short_signals <= self._config.market_anchor_max_opposite_signals):
            return MarketRegime.BROAD_UP
        if (short_signals >= self._config.market_anchor_min_aligned_signals
                and short_symbols >= self._config.market_anchor_min_aligned_symbols
                and long_signals <= self._config.market_anchor_max_opposite_signals):
            return MarketRegime.BROAD_DOWN
        return MarketRegime.MIXED

    def _market_anchor_period_side(self, candles) -> TrendSide:
        if len(candles) < self._config.market_anchor_slow_ema_period + 2:
            return TrendSide.FLAT
        closes = [candle.close for candle in candles]
        from .indicators import ema
        latest, previous = closes[-1], closes[-2]
        fast = ema(closes, self._config.market_anchor_fast_ema_period)
        slow = ema(closes, self._config.market_anchor_slow_ema_period)
        if latest > previous and fast > slow and latest >= fast:
            return TrendSide.LONG
        if latest < previous and fast < slow and latest <= fast:
            return TrendSide.SHORT
        return TrendSide.FLAT

    def _scan_all(self, now_epoch: int, java_config: JavaEntryConfig) -> tuple[list[_Scan], list[str]]:
        scans, failures = [], []
        contracts = tuple(dict.fromkeys(self._config.contracts))
        with ThreadPoolExecutor(max_workers=max(1, self._config.max_concurrency), thread_name_prefix="htx-trend-live") as pool:
            futures = {pool.submit(self._scan_one, contract, now_epoch, java_config): contract for contract in contracts}
            for future in as_completed(futures):
                contract = futures[future]
                try:
                    scans.append(future.result())
                except Exception as error:
                    failures.append(f"{contract}:{'CONTEXT_UNAVAILABLE' if isinstance(error, _ContextUnavailable) else 'RuntimeError' if isinstance(error, _EvidenceUnavailable) else type(error).__name__}")
                    code = (RejectCode.CONTEXT_UNAVAILABLE if isinstance(error, _ContextUnavailable) else
                            RejectCode.EVIDENCE_UNAVAILABLE if isinstance(error, _EvidenceUnavailable)
                            else RejectCode.SCAN_FAILED)
                    self._diagnose(None, contract=contract, bar_time=self._stable_candle_close_epoch(now_epoch)
                                   - period_seconds(self._config.entry_period), stage="SCAN", code=code,
                                   flags=error.flags if isinstance(error, _ContextUnavailable) else None)
        return scans, failures

    def _scan_one(self, contract: str, now_epoch: int, java_config: JavaEntryConfig) -> _Scan:
        entry = self._market.kline_window(contract, self._config.entry_period, self._config.kline_size, now_epoch=now_epoch)
        trend = self._market.kline_window(contract, self._config.trend_period, self._config.kline_size, now_epoch=now_epoch)
        if entry.evidence_status == "FAILED" or trend.evidence_status == "FAILED":
            raise _EvidenceUnavailable("evidence persistence unavailable; new risk is paused")
        book = self._market.order_book(contract)
        regime = None
        evidence_refs = entry.evidence_refs + trend.evidence_refs
        version_profile = profile_for_version(self._instance.strategy_name or LEGACY_STRATEGY_VERSION)
        if version_profile is not None:
            windows = {entry.period: entry.candles, trend.period: trend.candles}
            for period in version_profile.contract.confirmation_periods:
                if period in windows:
                    continue
                window = self._market.kline_window(contract, period, self._config.kline_size, now_epoch=now_epoch)
                if window.evidence_status == "FAILED":
                    raise _EvidenceUnavailable("profile confirmation evidence persistence unavailable")
                windows[period] = window.candles
                evidence_refs += window.evidence_refs
            regime = profile_regime(
                version_profile, windows, as_of_epoch=now_epoch,
                spread_rate=book.top.spread_rate,
                executable_depth_contracts=min(
                    book.executable_contracts("LONG"), book.executable_contracts("SHORT")),
            )
            if regime.status is RegimeStatus.UNKNOWN:
                raise _ContextUnavailable(windows, regime.missing_periods)
        decision = HtxStrategyDecisionEngine(
            strategy_version=self._instance.strategy_name or LEGACY_STRATEGY_VERSION,
            config=java_config, admission=self._admission, evidence_store=self._market.evidence_store,
        ).decide(contract_code=contract, period=self._config.entry_period, candles=entry.candles,
                 surface=DecisionSurface.RANKING, evidence_refs=evidence_refs)
        try:
            funding = self._market.funding_rate(contract)
        except Exception:
            funding = None
        return _Scan(contract, entry, trend, decision.signal, decision.candidates,
                     book, self._market.contract_spec(contract), funding,
                     decision.decision_hash, decision.diagnosis, regime)

    def _persist_observation(self, scan: _Scan, context: MarketContext) -> int:
        factor = scan.signal.indicators
        if factor is None or scan.signal.bar_time is None:
            return self._repository.record_factor_snapshot(snapshot_key=sha256(f"empty:{scan.contract}".encode()).hexdigest(),
                contract_code=scan.contract, period=self._config.entry_period, bar_time=0, market_regime=context.regime.value, factors={})
        factors = {name: getattr(factor, name) for name in ("ema_fast", "ema_medium", "ema_slow", "atr", "atr_pct", "adx", "roc", "vwap")}
        if scan.regime_snapshot is not None:
            factors["regime_snapshot_hash"] = scan.regime_snapshot.snapshot_hash
            factors["regime_trend"] = scan.regime_snapshot.trend.value
            factors["regime_volatility"] = scan.regime_snapshot.volatility.value
            factors["regime_liquidity"] = scan.regime_snapshot.liquidity.value
        snapshot_key = sha256(
            f"python-live:{self._instance.instance_key}:{self._config.timeframe_profile.value}:"
            f"{scan.contract}:{scan.signal.bar_time}:{factors}".encode()).hexdigest()
        factor_id = self._repository.record_factor_snapshot(snapshot_key=snapshot_key, contract_code=scan.contract,
            period=self._config.entry_period, bar_time=scan.signal.bar_time, market_regime=context.regime.value, factors=factors)
        self._repository.record_signal(contract_code=scan.contract, period=self._config.entry_period, bar_time=scan.signal.bar_time,
            signal=scan.signal.side.value, close_price=scan.signal.close_price, ema_fast=factor.ema_fast, ema_slow=factor.ema_slow,
            breakout_high=scan.signal.breakout_high or Decimal("0"), breakout_low=scan.signal.breakout_low or Decimal("0"),
            atr=factor.atr, atr_pct=factor.atr_pct, adx=factor.adx, spread_rate=scan.book.top.spread_rate,
            funding_rate=scan.funding.rate if scan.funding else None, reason=scan.signal.reason)
        return factor_id


def _initial_candidate_gate(candidate, *, now_epoch: int, entry_period_seconds: int,
                            signal_freshness_seconds: int, shadow: bool) -> tuple[str, str]:
    """Classify a newly selected candidate before it enters the pending queue.

    A process may start after a higher-timeframe candle's freshness deadline.
    Such a candidate is an observation fact, not a live preflight attempt.
    """
    deadline = candidate.bar_time + entry_period_seconds + signal_freshness_seconds
    if now_epoch > deadline:
        return "EXPIRED", "candidate expired before live account/exchange preflight"
    if shadow:
        return "SHADOW_ONLY", "profile live entry disabled"
    return "PRE_FLIGHT", "live account/exchange preflight pending"


class MultiTimeframeLiveTrendWorker(StrategyWorker):
    """Run independent timeframe lifecycles behind one account writer lock."""

    def __init__(self, workers: tuple[LiveTrendWorker, ...]) -> None:
        if not workers:
            raise ValueError("multi-timeframe worker requires at least one profile")
        profiles = [worker._config.timeframe_profile for worker in workers]
        if len(set(profiles)) != len(profiles):
            raise ValueError("duplicate timeframe profile")
        self._workers = workers

    def stop(self) -> None:
        for worker in self._workers:
            worker.stop()

    def tick(self) -> WorkerHealth:
        outcomes = [(worker._config.timeframe_profile, worker.tick()) for worker in self._workers]
        status = ("DEGRADED" if any(outcome.status == "DEGRADED" for _, outcome in outcomes)
                  else "STOPPED" if all(outcome.status == "STOPPED" for _, outcome in outcomes)
                  else "HEALTHY")
        # MySQL heartbeat detail is capped at 1024 characters. Preserve a
        # bounded slice from every profile instead of letting the first one
        # hide the second profile's state.
        detail = " | ".join(f"{profile.value}[{outcome.detail[:440]}]" for profile, outcome in outcomes)
        return WorkerHealth(status, detail, sum(outcome.candidate_count for _, outcome in outcomes),
                            max(outcome.active_position_count for _, outcome in outcomes))
