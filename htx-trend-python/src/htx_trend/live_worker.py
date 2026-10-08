"""Explicitly authorised HTX live trend worker.

It shares the same model generation as observation, but policy selection and
all account/exchange checks are delegated to :class:`HtxTrendEntryExecutor`.
Creating this class does not grant trade authority; the executor does.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from hashlib import sha256
from typing import Any

from .execution_service import HtxTrendEntryExecutor
from .market_data import HtxMarketData
from .models import TrendConfig, TrendSide, evaluate_signal, market_breadth, model_candidates
from .policy import StrategyPolicy, select_policy
from .strategy_context import MarketContext, MarketRegime
from .strategy_platform import ExecutionMode, StrategyWorker, WorkerHealth
from .mysql_store import HtxTrendMySqlRepository, RuntimeInstance


@dataclass(frozen=True, slots=True)
class LiveTrendWorkerConfig:
    contracts: tuple[str, ...]
    entry_period: str = "15min"
    trend_period: str = "60min"
    kline_size: int = 160
    max_concurrency: int = 6


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
                 trend_config: TrendConfig = TrendConfig()) -> None:
        if instance.execution_mode != ExecutionMode.LIVE:
            raise ValueError("LiveTrendWorker requires a LIVE strategy instance")
        if not config.contracts:
            raise ValueError("live trend worker requires at least one contract")
        self._instance, self._repository, self._market, self._executor = instance, repository, market, executor
        self._config, self._trend_config, self._stopped = config, trend_config, False

    def stop(self) -> None:
        self._stopped = True

    def tick(self) -> WorkerHealth:
        if self._stopped:
            return WorkerHealth("STOPPED", "worker has been stopped")
        recovered = self._executor.reconcile_pending()
        now_epoch = int(datetime.now(UTC).timestamp())
        scans, failures = self._scan_all(now_epoch)
        breadth = market_breadth(item.signal for item in scans)
        signal_by_contract = {item.contract: item.signal.side for item in scans}
        context = MarketContext(
            regime=(MarketRegime.BROAD_UP if breadth.side is TrendSide.LONG else MarketRegime.BROAD_DOWN
                    if breadth.side is TrendSide.SHORT else MarketRegime.MIXED),
            breadth_side=breadth.side, breadth_ratio=breadth.ratio,
            btc_anchor=signal_by_contract.get("BTC-USDT", TrendSide.FLAT),
            eth_anchor=signal_by_contract.get("ETH-USDT", TrendSide.FLAT),
        )
        policies = tuple(StrategyPolicy(value.policy_code, value.policy_version, value.primary_model,
                                        value.confirmation_models, value.base_notional_usdt, value.max_notional_usdt,
                                        value.score_threshold, value.box_window_bars)
                         for value in self._repository.enabled_strategy_policies())
        selected: list[tuple[Any, Any, int]] = []
        for scan in scans:
            factor_id = self._persist_observation(scan, context)
            chosen = select_policy(scan.candidates, policies, context)
            if chosen:
                selected.append((chosen[0], scan, factor_id))
        accepted = 0
        for policy, scan, factor_id in sorted(selected, key=lambda item: item[0].effective_score, reverse=True):
            candidate = policy.candidate
            run_id = f"trend-entry:{scan.contract}:{self._config.entry_period}:{candidate.bar_time}"
            self._repository.record_candidate(contract_code=scan.contract, period=self._config.entry_period,
                bar_time=candidate.bar_time, direction=candidate.side.value, regime=context.regime.value,
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
        detail = f"scanned={len(scans)}/{len(self._config.contracts)} policyCandidates={len(selected)} entriesAccepted={accepted} recovered={len(recovered)}"
        if failures:
            detail += " failures=" + ",".join(failures[:8])
        active = self._repository.active_position_count(account_id=self._instance.account_id or -1)
        return WorkerHealth("DEGRADED" if failures else "HEALTHY", detail, len(selected), active)

    def _scan_all(self, now_epoch: int) -> tuple[list[_Scan], list[str]]:
        scans, failures = [], []
        contracts = tuple(dict.fromkeys(self._config.contracts))
        with ThreadPoolExecutor(max_workers=max(1, self._config.max_concurrency), thread_name_prefix="htx-trend-live") as pool:
            futures = {pool.submit(self._scan_one, contract, now_epoch): contract for contract in contracts}
            for future in as_completed(futures):
                contract = futures[future]
                try:
                    scans.append(future.result())
                except Exception as error:
                    failures.append(f"{contract}:{type(error).__name__}")
        return scans, failures

    def _scan_one(self, contract: str, now_epoch: int) -> _Scan:
        entry = self._market.kline_window(contract, self._config.entry_period, self._config.kline_size, now_epoch=now_epoch)
        trend = self._market.kline_window(contract, self._config.trend_period, self._config.kline_size, now_epoch=now_epoch)
        signal = evaluate_signal(entry.candles, trend.candles, self._trend_config)
        try:
            funding = self._market.funding_rate(contract)
        except Exception:
            funding = None
        return _Scan(contract, entry, trend, signal, model_candidates(entry.candles, trend.candles, self._trend_config),
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
