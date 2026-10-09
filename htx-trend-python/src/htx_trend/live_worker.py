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

from .execution_service import HtxTrendEntryExecutor
from .market_data import HtxMarketData, period_seconds
from .models import TrendSide
from .java_entry_contract import JavaEntryConfig, candidates as java_combination_candidates, evaluate as evaluate_java_ensemble
from .policy import StrategyPolicy, PolicySelection, select_policy
from .strategy_context import MarketContext, MarketRegime
from .strategy_platform import ExecutionMode, StrategyWorker, WorkerHealth
from .mysql_store import HtxTrendMySqlRepository, RuntimeInstance


@dataclass(frozen=True, slots=True)
class LiveTrendWorkerConfig:
    contracts: tuple[str, ...]
    enabled: bool = False
    observe_only: bool = True
    entry_enabled: bool = True
    entry_period: str = "15min"
    trend_period: str = "60min"
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
    max_active_positions: int = 1
    # Java's multi-contract path is intentionally opt-in.  Its legacy path is
    # not implemented by the Python worker, so allowing this worker to start
    # with the flag off would make it trade a different strategy.
    combination_entry_enabled: bool = False

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
        default_contracts = ("BTC-USDT", "ETH-USDT", "SOL-USDT", "XRP-USDT", "BNB-USDT", "DOGE-USDT", "ADA-USDT",
                             "TRX-USDT", "LINK-USDT", "AVAX-USDT", "SUI-USDT", "HYPE-USDT", "HBAR-USDT", "PONS-USDT",
                             "BCH-USDT", "LTC-USDT", "DOT-USDT", "UNI-USDT", "AAVE-USDT", "NEAR-USDT", "ETC-USDT",
                             "XLM-USDT", "FIL-USDT", "ARB-USDT", "OP-USDT", "ATOM-USDT", "ICP-USDT", "APT-USDT",
                             "PEPE-USDT", "SHIB-USDT", "WIF-USDT", "BONK-USDT", "FLOKI-USDT", "TAO-USDT", "WLD-USDT",
                             "TIA-USDT", "JUP-USDT", "ENA-USDT", "ONDO-USDT", "ALGO-USDT", "OKB-USDT", "DASH-USDT",
                             "ZEC-USDT", "XAUT-USDT", "PAXG-USDT", "PEOPLE-USDT", "ORDI-USDT", "BOME-USDT", "RAY-USDT",
                             "CHZ-USDT", "ETHFI-USDT", "ACE-USDT")
        return cls(
            contracts=values("HTX_TREND_CONTRACTS", default_contracts),
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
        )


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


class LiveTrendWorker(StrategyWorker):
    """Real execution path; only a LIVE instance with authorised executor may use it."""

    def __init__(self, instance: RuntimeInstance, repository: HtxTrendMySqlRepository, market: HtxMarketData,
                 executor: HtxTrendEntryExecutor, config: LiveTrendWorkerConfig,
                 trend_config: JavaEntryConfig | None = None, *, position_manager=None, writer_guard=None) -> None:
        if instance.execution_mode != ExecutionMode.LIVE:
            raise ValueError("LiveTrendWorker requires a LIVE strategy instance")
        if not config.enabled:
            raise ValueError("LiveTrendWorker requires HTX_TREND_ENABLED=true")
        if not config.contracts:
            raise ValueError("live trend worker requires at least one contract")
        if not config.combination_entry_enabled:
            raise ValueError("LiveTrendWorker requires HTX_TREND_COMBINATION_ENTRY_ENABLED=true; Java legacy entry is not implemented in Python")
        self._instance, self._repository, self._market, self._executor = instance, repository, market, executor
        self._config, self._trend_config, self._stopped = config, trend_config or config.java_entry, False
        self._last_scanned_close_epoch: int | None = None
        self._pending: dict[str, tuple[PolicySelection, _Scan, int]] = {}
        self._last_context = MarketContext.neutral()
        self._position_manager, self._writer_guard = position_manager, writer_guard

    def stop(self) -> None:
        self._stopped = True

    def tick(self) -> WorkerHealth:
        if self._stopped:
            return WorkerHealth("STOPPED", "worker has been stopped")
        if self._writer_guard is None or self._position_manager is None:
            raise RuntimeError("LIVE worker requires ownership and position management")
        self._writer_guard()
        recovered = self._executor.reconcile_pending()
        now_epoch = int(datetime.now(UTC).timestamp())
        management_failures = self._position_manager.manage(now_epoch, self._last_context)
        if management_failures:
            return WorkerHealth("DEGRADED", "; ".join(management_failures))
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
        failures: list[str] = []
        if fresh_scan:
            # This is intentionally read *before* writing this scan's signals,
            # matching Java's marketBreadthContext() ordering.
            breadth = (self._repository.latest_breadth_stats(
                datetime.fromtimestamp(now_epoch - self._config.market_breadth_lookback_seconds, UTC))
                if self._config.adaptive_sizing_enabled else None)
            scans, failures = self._scan_all(now_epoch, java_config)
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
            for scan in scans:
                factor_id = self._persist_observation(scan, context)
                chosen = select_policy(scan.candidates, policies, context)
                if chosen:
                    self._pending[scan.contract] = (chosen[0], scan, factor_id)
                else:
                    self._pending.pop(scan.contract, None)
        else:
            context = self._last_context

        self._expire_pending(now_epoch)
        ranked = sorted(self._pending.values(), key=lambda item: (item[0].effective_score, item[0].base_score,
                                                                    item[0].candidate.expected_r), reverse=True)
        within_window = self._entry_window_open(now_epoch, stable_close_epoch)
        carryover = any(self._candidate_fresh(item[0].candidate, now_epoch) for item in ranked)
        accepted = 0
        attempted = 0
        execution_block_reason = ("trend observe-only enabled" if self._config.observe_only
                                  else "new trend entries disabled" if not self._config.entry_enabled else None)
        entry_attempt_allowed = ranked and (within_window or carryover)
        if entry_attempt_allowed and self._config.observe_only:
            active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
            # Java enters openIfEligible in observe-only mode when capacity is
            # available. It then removes every observed candidate, preventing
            # a later flag flip from submitting an old bar as a live order.
            if self._remaining_slots(len(ranked), active) > 0:
                for _, scan, _ in ranked:
                    self._pending.pop(scan.contract, None)
        elif entry_attempt_allowed and execution_block_reason is None:
            active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
            slots = self._remaining_slots(len(ranked), active)
            for policy, scan, factor_id in ranked[:slots]:
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
                    shadow=False, confirmation_models=policy.confirmation_models, policy_version=policy.policy.policy_version,
                    gate_result="PRE_FLIGHT", gate_reason="live account/exchange preflight pending",
                    sizing_multiplier=policy.sizing_multiplier, planned_notional_usdt=policy.planned_notional_usdt)
                gate = self._executor.submit(run_id=run_id, candidate=candidate, period=self._config.entry_period,
                    atr=scan.signal.indicators.atr, ema_fast=scan.signal.indicators.ema_fast,
                    ema_slow=scan.signal.indicators.ema_slow, adx=scan.signal.indicators.adx,
                    recent_candles=scan.entry.candles, book=scan.book, contract=scan.contract_spec, funding=scan.funding,
                    market_context=context, factor_snapshot_id=factor_id, now_epoch=now_epoch)
                self._repository.mark_candidate_execution_gate(run_id, scan.contract, candidate.model.value,
                    "PASSED" if gate.accepted else gate.gate.stage, gate.reason)
                accepted += int(gate.accepted)
                attempted += 1
                # Java removes a candidate after its gate/order evaluation;
                # a new completed candle is required for another attempt.
                self._pending.pop(scan.contract, None)
        detail = (f"scan={'fresh' if fresh_scan else 'cached'} contracts={len(scans) if fresh_scan else 0}/{len(self._config.contracts)} "
                  f"pending={len(self._pending)} entriesAttempted={attempted} entriesAccepted={accepted} recovered={len(recovered)} "
                  f"entryWindow={'OPEN' if within_window else 'CLOSED'} anchor={context.anchor_regime.value}")
        if execution_block_reason:
            detail += f" entrySkipped={execution_block_reason}"
        if failures:
            detail += " failures=" + ",".join(failures[:8])
        active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
        return WorkerHealth("DEGRADED" if failures else "HEALTHY", detail, len(ranked), active)

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

    def _market_anchor_regime(self, now_epoch: int) -> MarketRegime:
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
                    failures.append(f"{contract}:{type(error).__name__}")
        return scans, failures

    def _scan_one(self, contract: str, now_epoch: int, java_config: JavaEntryConfig) -> _Scan:
        entry = self._market.kline_window(contract, self._config.entry_period, self._config.kline_size, now_epoch=now_epoch)
        trend = self._market.kline_window(contract, self._config.trend_period, self._config.kline_size, now_epoch=now_epoch)
        # Do not use the separate research scorer here.  Java executes an
        # ensemble signal followed by its combination calculator.
        signal = evaluate_java_ensemble(entry.candles, java_config)
        try:
            funding = self._market.funding_rate(contract)
        except Exception:
            funding = None
        return _Scan(contract, entry, trend, signal, java_combination_candidates(entry.candles, signal, java_config, self._config.entry_period),
                     self._market.order_book(contract), self._market.contract_spec(contract), funding)

    def _persist_observation(self, scan: _Scan, context: MarketContext) -> int:
        factor = scan.signal.indicators
        if factor is None or scan.signal.bar_time is None:
            return self._repository.record_factor_snapshot(snapshot_key=sha256(f"empty:{scan.contract}".encode()).hexdigest(),
                contract_code=scan.contract, period=self._config.entry_period, bar_time=0, market_regime=context.regime.value, factors={})
        factors = {name: getattr(factor, name) for name in ("ema_fast", "ema_medium", "ema_slow", "atr", "atr_pct", "adx", "roc", "vwap")}
        snapshot_key = sha256(f"python-live:{self._instance.instance_key}:{scan.contract}:{scan.signal.bar_time}:{factors}".encode()).hexdigest()
        factor_id = self._repository.record_factor_snapshot(snapshot_key=snapshot_key, contract_code=scan.contract,
            period=self._config.entry_period, bar_time=scan.signal.bar_time, market_regime=context.regime.value, factors=factors)
        self._repository.record_signal(contract_code=scan.contract, period=self._config.entry_period, bar_time=scan.signal.bar_time,
            signal=scan.signal.side.value, close_price=scan.signal.close_price, ema_fast=factor.ema_fast, ema_slow=factor.ema_slow,
            breakout_high=scan.signal.breakout_high or Decimal("0"), breakout_low=scan.signal.breakout_low or Decimal("0"),
            atr=factor.atr, atr_pct=factor.atr_pct, adx=factor.adx, spread_rate=scan.book.top.spread_rate,
            funding_rate=scan.funding.rate if scan.funding else None, reason=scan.signal.reason)
        return factor_id
