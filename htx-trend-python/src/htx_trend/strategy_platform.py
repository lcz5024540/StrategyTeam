"""Independently deployable strategy workers and their shared hard-risk boundary.

Workers do not call each other and do not share mutable strategy state.  They
may share an account only through :class:`CapitalRiskCoordinator`, which reads
the operator-owned allocation table before a worker is allowed to create an
entry intent.  The first production-capable implementation is the read-only
trend observer; carry workers deliberately remain disabled placeholders.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum
from hashlib import sha256
from typing import Protocol, Sequence
import json
import uuid

from .market_data import HtxMarketData
from .java_entry_contract import JavaEntryConfig
from .models import TrendSide, market_breadth, route_candidates
from .policy import StrategyPolicy, select_policy
from .mysql_store import HtxTrendMySqlRepository, RuntimeInstance
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact
from .strategy_decision import HtxStrategyDecisionEngine, LEGACY_STRATEGY_VERSION
from .strategy_context import MarketContext, MarketRegime
from .timeframes import TimeframeProfile


class ExecutionMode(StrEnum):
    OBSERVE = "OBSERVE"
    PAPER = "PAPER"
    LIVE = "LIVE"


@dataclass(frozen=True, slots=True)
class WorkerHealth:
    status: str
    detail: str
    candidate_count: int = 0
    active_position_count: int = 0


class StrategyWorker(Protocol):
    def tick(self) -> WorkerHealth: ...
    def stop(self) -> None: ...


@dataclass(frozen=True, slots=True)
class Admission:
    allowed: bool
    reason: str


class CapitalRiskCoordinator:
    """Small shared boundary; no strategy can bypass a missing allocation."""
    def __init__(self, repository: HtxTrendMySqlRepository) -> None:
        self._repository = repository

    def admit(self, instance: RuntimeInstance, requested_notional: Decimal) -> Admission:
        if instance.account_id is None:
            return Admission(False, "strategy instance has no account")
        allocation = self._repository.capital_allocation(account_id=instance.account_id,
                                                         strategy_family=instance.strategy_family,
                                                         strategy_name=instance.strategy_name)
        if allocation is None:
            return Admission(False, "no enabled capital allocation")
        if requested_notional > allocation.max_notional_usdt:
            return Admission(False, "requested notional exceeds strategy allocation")
        limit = self._repository.account_risk_limit(instance.account_id)
        if limit is None:
            return Admission(False, "no enabled account-level risk limit")
        reserved = self._repository.active_risk_reservation_totals(instance.account_id)
        if reserved.notional_usdt + requested_notional > limit.max_notional_usdt:
            return Admission(False, "account-level notional capacity exhausted")
        if reserved.position_count >= limit.max_active_positions:
            return Admission(False, "account-level position capacity exhausted")
        return Admission(True, "allocation and account capacity available")


class DisabledStrategyWorker:
    """Safe placeholder until an arbitrage execution contract and replay exist."""
    def __init__(self, instance: RuntimeInstance, repository: HtxTrendMySqlRepository) -> None:
        self._instance, self._repository = instance, repository

    def tick(self) -> WorkerHealth:
        return WorkerHealth("DISABLED", f"{self._instance.strategy_family}/{self._instance.strategy_name} has no approved worker")

    def stop(self) -> None:
        return None


@dataclass(frozen=True, slots=True)
class TrendObserverConfig:
    contracts: tuple[str, ...]
    entry_period: str = "15min"
    trend_period: str = "60min"
    kline_size: int = 160
    max_concurrency: int = 6


@dataclass(frozen=True, slots=True)
class _TrendObservation:
    contract: str
    signal: object
    model_candidates: tuple[object, ...]
    funding_rate: Decimal | None
    spread_rate: Decimal | None
    decision_hash: str


class TrendObservationWorker:
    """Writes production-compatible observation evidence without creating orders.

    This is deliberately an OBSERVE-only worker.  PAPER and LIVE require
    separate execution adapters and are rejected here so deploying this worker
    cannot silently switch the current Java live trader.
    """
    def __init__(self, instance: RuntimeInstance, repository: HtxTrendMySqlRepository, market: HtxMarketData,
                 config: TrendObserverConfig, entry_config: JavaEntryConfig = JavaEntryConfig(),
                 admission: StrategyAdmissionArtifact | None = None) -> None:
        if instance.execution_mode != ExecutionMode.OBSERVE:
            raise ValueError("TrendObservationWorker only supports OBSERVE; paper/live need an approved execution adapter")
        if not config.contracts:
            raise ValueError("trend observer requires at least one contract")
        self._instance, self._repository, self._market = instance, repository, market
        self._config, self._stopped = config, False
        self._decision = HtxStrategyDecisionEngine(strategy_version=instance.strategy_name or LEGACY_STRATEGY_VERSION,
                                                   config=entry_config, admission=admission,
                                                   evidence_store=market.evidence_store)

    def tick(self) -> WorkerHealth:
        if self._stopped:
            return WorkerHealth("STOPPED", "worker has been stopped")
        run_id, now_epoch = f"python-observe-{uuid.uuid4()}", int(datetime.now(UTC).timestamp())
        results: list[_TrendObservation] = []
        failures: list[str] = []
        contracts = tuple(dict.fromkeys(self._config.contracts))
        with ThreadPoolExecutor(max_workers=max(1, self._config.max_concurrency), thread_name_prefix="htx-trend-observe") as pool:
            futures = {pool.submit(self._observe_one, contract, now_epoch): contract for contract in contracts}
            for future in as_completed(futures):
                contract = futures[future]
                try:
                    results.append(future.result())
                except Exception as error:  # a bad contract must not stop peers
                    failures.append(f"{contract}:{type(error).__name__}")
        breadth = market_breadth(result.signal for result in results)
        anchors = {result.contract: result.signal.side for result in results}
        context = MarketContext(
            regime=(MarketRegime.BROAD_UP if breadth.side.value == "LONG" else MarketRegime.BROAD_DOWN
                    if breadth.side.value == "SHORT" else MarketRegime.MIXED),
            breadth_side=breadth.side, breadth_ratio=breadth.ratio,
            btc_anchor=anchors.get("BTC-USDT", TrendSide.FLAT),
            eth_anchor=anchors.get("ETH-USDT", TrendSide.FLAT),
        )
        candidate_count = 0
        for result in results:
            candidate_count += self._persist(run_id, result, context)
        detail = f"observed={len(results)}/{len(contracts)} candidates={candidate_count}"
        if failures:
            detail += " failures=" + ",".join(failures[:8])
        return WorkerHealth("DEGRADED" if failures else "HEALTHY", detail, candidate_count, 0)

    def stop(self) -> None:
        self._stopped = True

    def _observe_one(self, contract: str, now_epoch: int) -> _TrendObservation:
        entry = self._market.kline_window(contract, self._config.entry_period, self._config.kline_size, now_epoch=now_epoch)
        decision = self._decision.decide(contract_code=contract, period=self._config.entry_period,
                                         candles=entry.candles, surface=DecisionSurface.OBSERVE,
                                         evidence_refs=entry.evidence_refs)
        book = self._market.top_of_book(contract)
        try:
            funding = self._market.funding_rate(contract).rate
        except Exception:
            funding = None
        return _TrendObservation(contract, decision.signal, decision.candidates, funding, book.spread_rate,
                                 decision.decision_hash)

    def _persist(self, run_id: str, result: _TrendObservation, context: MarketContext) -> int:
        signal = result.signal
        factors = signal.indicators
        if factors is None or signal.bar_time is None:
            return 0
        values = {name: getattr(factors, name) for name in (
            "ema_fast", "ema_medium", "ema_slow", "atr", "atr_pct", "adx", "roc", "vwap", "rsi", "macd", "macd_signal", "macd_histogram",
        )}
        self._repository.record_signal(contract_code=result.contract, period=self._config.entry_period, bar_time=signal.bar_time,
                                       signal=signal.side.value, close_price=signal.close_price, ema_fast=factors.ema_fast,
                                       ema_slow=factors.ema_slow, breakout_high=signal.breakout_high or Decimal("0"),
                                       breakout_low=signal.breakout_low or Decimal("0"), atr=factors.atr, atr_pct=factors.atr_pct,
                                       adx=factors.adx, spread_rate=result.spread_rate, funding_rate=result.funding_rate, reason=signal.reason)
        snapshot_key = sha256(json.dumps({"instance": self._instance.instance_key, "contract": result.contract,
            "period": self._config.entry_period, "bar": signal.bar_time, "factors": values}, default=str, sort_keys=True).encode()).hexdigest()
        factor_id = self._repository.record_factor_snapshot(snapshot_key=snapshot_key, contract_code=result.contract,
            period=self._config.entry_period, bar_time=signal.bar_time, market_regime=signal.regime,
            factors={**values, "strategy_decision_hash": result.decision_hash})
        route = route_candidates(result.model_candidates, market_context=context)
        policies = tuple(StrategyPolicy(value.policy_code, value.policy_version, value.primary_model, value.confirmation_models,
                                        value.base_notional_usdt, value.max_notional_usdt, value.score_threshold, value.box_window_bars)
                         for value in self._repository.enabled_strategy_policies())
        selections = select_policy(route.ranked, policies, context)
        selected_by_candidate = {item.candidate: item for item in selections}
        for item in route.ranked:
            selection = selected_by_candidate.get(item)
            gate_result = "POLICY_PASSED" if selection else "POLICY_NOT_MATCHED"
            gate_reason = (f"policy={selection.policy.policy_code}@{selection.policy.policy_version}; "
                           f"baseScore={selection.base_score}; effectiveScore={selection.effective_score}; "
                           f"plannedNotional={selection.planned_notional_usdt}" if selection
                           else "no enabled Java-compatible policy matches model, confirmation evidence and score")
            self._repository.record_candidate(contract_code=result.contract, period=self._config.entry_period, bar_time=item.bar_time,
                direction=item.side.value, regime=context.regime.value, score=item.score, close_price=item.entry_price,
                factors={**values, "structure_key": item.structure_key,
                         "confirmation_models": [peer.value for peer in item.confirmation_models],
                         "conflict_evidence": list(item.conflict_evidence), "route": route.reason},
                reason="; ".join(item.evidence), model=item.model.value, run_id=run_id,
                factor_snapshot_id=factor_id, invalidation_price=item.invalidation_price, target_low=item.target_low,
                target_high=item.target_high, expected_r=item.expected_r, shadow=True,
                confirmation_models=selection.confirmation_models if selection else None,
                policy_version=selection.policy.policy_version if selection else None,
                gate_result=gate_result, gate_reason=gate_reason,
                sizing_multiplier=selection.sizing_multiplier if selection else None,
                planned_notional_usdt=selection.planned_notional_usdt if selection else None,
                timeframe_profile=(TimeframeProfile.for_period(self._config.entry_period).value
                                   if self._config.entry_period in {"15min", "4hour"} else None))
            self._repository.mark_candidate_execution_gate(run_id, result.contract, item.model.value, "OBSERVE_ONLY",
                                                            f"{gate_reason}; real account, book, fee, margin and order preflight not entered")
        return len(result.model_candidates)


class StrategySupervisor:
    """One process may host several safe workers, while systemd can isolate each one."""
    def __init__(self, repository: HtxTrendMySqlRepository, workers: dict[str, StrategyWorker]) -> None:
        self._repository, self._workers = repository, workers

    def tick_all(self) -> dict[str, WorkerHealth]:
        results: dict[str, WorkerHealth] = {}
        for instance_key, worker in self._workers.items():
            try:
                health = worker.tick()
            except Exception as error:
                health = WorkerHealth("FAILED", f"{type(error).__name__}: {error}")
            results[instance_key] = health
            self._repository.heartbeat(instance_key=instance_key, status=health.status, detail=health.detail,
                                       candidate_count=health.candidate_count, active_position_count=health.active_position_count)
        return results

    def stop(self) -> None:
        for worker in self._workers.values():
            worker.stop()
