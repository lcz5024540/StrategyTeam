"""Java-compatible live entry state machine, kept detached from worker startup.

This module is deliberately inert until an operator supplies an explicit
``LiveAuthority``.  It gives Python the same intent -> reservation -> exchange
acceptance -> reconciliation ordering as Java without allowing an observer
deployment to acquire execution rights.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal
from os import environ
from hashlib import sha256
from typing import Any, Callable
import time
import uuid

from .account_facts import HtxAccountFactsAdapter
from .execution import ExecutionConfig, GateResult, PriorTrade, evaluate_gate
from .htx_v5 import SwapOrderRequest
from .live import HtxLiveExecutor
from .market_data import ContractSpec, FundingRate, OrderBook
from .models import ModelCandidate, TrendSide
from .policy import PolicySelection, StrategyPolicy, select_policy
from .mysql_store import (HtxTrendMySqlRepository, LedgerEvent, PositionIntent,
                          RuntimeInstance, TradeAccount, TrendOrderIntent)
from .strategy_context import (CandidateTrendState, EntryStateConfig, FibonacciQuality, MarketContext,
                               evaluate_candidate_state, evaluate_counter_market)


@dataclass(frozen=True, slots=True)
class EntryExecutionConfig:
    leverage: int = 5
    entry_order_type: str = "market"
    margin_safety_ratio: Decimal = Decimal("1.20")
    # HtxTrendProperties defaults. These values remain configurable at worker
    # construction, but Python must not silently use a second sizing policy.
    strong_quality_multiplier: Decimal = Decimal("1.15")
    breadth_aligned_multiplier: Decimal = Decimal("1.50")
    breadth_counter_multiplier: Decimal = Decimal("0.50")
    combination_mixed_market_multiplier: Decimal = Decimal("0.50")
    min_adaptive_multiplier: Decimal = Decimal("0.25")
    max_adaptive_multiplier: Decimal = Decimal("1.80")
    max_active_positions: int = 1

    @classmethod
    def from_env(cls) -> "EntryExecutionConfig":
        def dec(name: str, default: Decimal) -> Decimal:
            return Decimal(environ.get(name, str(default)))
        return cls(leverage=int(environ.get("HTX_TREND_LEVERAGE", str(cls.leverage))),
                   entry_order_type="market",
                   margin_safety_ratio=dec("HTX_CONTRACT_MARGIN_SAFETY_RATIO", cls.margin_safety_ratio),
                   strong_quality_multiplier=dec("HTX_TREND_STRONG_QUALITY_POSITION_MULTIPLIER", cls.strong_quality_multiplier),
                   breadth_aligned_multiplier=dec("HTX_TREND_MARKET_BREADTH_ALIGNED_POSITION_MULTIPLIER", cls.breadth_aligned_multiplier),
                   breadth_counter_multiplier=dec("HTX_TREND_MARKET_BREADTH_COUNTER_POSITION_MULTIPLIER", cls.breadth_counter_multiplier),
                   combination_mixed_market_multiplier=dec("HTX_TREND_COMBINATION_MIXED_MARKET_POSITION_MULTIPLIER", cls.combination_mixed_market_multiplier),
                   min_adaptive_multiplier=dec("HTX_TREND_MIN_ADAPTIVE_POSITION_MULTIPLIER", cls.min_adaptive_multiplier),
                   max_adaptive_multiplier=dec("HTX_TREND_MAX_ADAPTIVE_POSITION_MULTIPLIER", cls.max_adaptive_multiplier),
                   max_active_positions=int(environ.get("HTX_TREND_MAX_ACTIVE_POSITIONS", str(cls.max_active_positions))))


@dataclass(frozen=True, slots=True)
class EntrySubmission:
    accepted: bool
    gate: GateResult
    position_id: int | None
    client_order_id: str | None
    exchange_order_id: int | None
    reason: str


@dataclass(frozen=True, slots=True)
class ConfirmedEntryFill:
    position_id: int
    position_key: str
    run_id: str | None
    contract_code: str
    side: TrendSide
    filled_contracts: int
    fill_price: Decimal
    contract_size: Decimal
    model: str
    factor_snapshot_id: int | None


@dataclass(frozen=True, slots=True)
class ConfirmedReductionFill:
    position_id: int
    position_key: str
    run_id: str | None
    contract_code: str
    side: TrendSide
    filled_contracts: int
    model: str
    factor_snapshot_id: int | None
    full_close: bool


class HtxTrendEntryExecutor:
    """One-account trend entry executor with no implicit credentials or DDL."""

    def __init__(self, *, repository: HtxTrendMySqlRepository, instance: RuntimeInstance, account: TradeAccount,
                 facts: HtxAccountFactsAdapter, executor: HtxLiveExecutor,
                 execution: ExecutionConfig = ExecutionConfig(), config: EntryExecutionConfig = EntryExecutionConfig(),
                 on_confirmed_entry: Callable[[ConfirmedEntryFill], None] | None = None,
                 on_confirmed_reduction: Callable[[ConfirmedReductionFill], None] | None = None) -> None:
        if instance.account_id != account.account_id:
            raise ValueError("strategy instance and execution account differ")
        self._repository, self._instance, self._account = repository, instance, account
        self._facts, self._executor, self._execution, self._config = facts, executor, execution, config
        self._on_confirmed_entry, self._on_confirmed_reduction = on_confirmed_entry, on_confirmed_reduction

    def submit(self, *, run_id: str, candidate: ModelCandidate, period: str, atr: Decimal,
               ema_fast: Decimal, ema_slow: Decimal, adx: Decimal, recent_candles, book: OrderBook, contract: ContractSpec,
               funding: FundingRate | None, market_context: MarketContext,
               prior_trade=None, claimed_structure_keys: frozenset[str] = frozenset(),
               factor_snapshot_id: int | None = None, timeframe_profile: str = "INTRADAY_15M",
               now_epoch: int | None = None) -> EntrySubmission:
        # Java loads the latest completed trade before its cooldown gate.  Live
        # Python used to leave this as None, which silently disabled cooldown.
        if prior_trade is None:
            latest_done = getattr(self._repository, "latest_done_trade", lambda **_: None)(
                account_id=self._account.account_id, contract_code=contract.contract_code)
            prior_trade = _prior_trade(latest_done)
        # Java resolves only the enabled strategy-policy ledger here. Account
        # allocation/reservation rows belong to Python's platform ledger and
        # must not become an unmirrored admission gate.
        policies = tuple(StrategyPolicy(value.policy_code, value.policy_version, value.primary_model,
                                        value.confirmation_models, value.base_notional_usdt, value.max_notional_usdt,
                                        value.score_threshold, value.box_window_bars)
                         for value in self._repository.enabled_strategy_policies())
        selections = select_policy((candidate,), policies, market_context)
        if not selections:
            gate = GateResult(False, "MODEL_POLICY", "no enabled Java-compatible policy matches candidate model, confirmations and score")
            return EntrySubmission(False, gate, None, None, None, gate.reason)
        policy = selections[0]
        momentum_quality = min(Decimal("100"), (candidate.score + adx) / Decimal("2"))
        confirmations = candidate.policy_confirmations or ""
        counter = evaluate_counter_market(side=candidate.side, context=market_context, model_score=candidate.score,
                                          expected_r=candidate.expected_r, momentum_score=momentum_quality, adx=adx,
                                          ema_fast=ema_fast, ema_slow=ema_slow, confirmations=confirmations,
                                          config=EntryStateConfig(counter_enabled=self._execution.counter_market_strong_candidate_enabled))
        state = evaluate_candidate_state(candidate.score, candidate.expected_r, momentum_quality, candidate.side,
                                         ema_fast, ema_slow, adx, market_context,
                                         counter_market_qualified=counter.qualified)
        adaptive_multiplier = self._sizing_multiplier(candidate, state, market_context)
        # Same sequence as Java: policy score multiplier first, then candidate/market sizing.
        total_multiplier = policy.sizing_multiplier * adaptive_multiplier
        planned_notional = min(policy.policy.base_notional_usdt * total_multiplier, policy.policy.max_notional_usdt)
        effective = replace(self._execution,
                            target_notional=planned_notional, max_notional=planned_notional,
                            margin_safety_ratio=self._config.margin_safety_ratio,
                            require_execution_facts=True,
                            java_policy_notional_sizing=True)
        snapshot = self._facts.snapshot(contract.contract_code,
                                        candidate.side, book, active_position_count=self._repository.active_position_count(account_id=self._account.account_id),
                                        max_active_positions=self._config.max_active_positions,
                                        reserved_notional_usdt=Decimal("0"), risk_capacity_usdt=Decimal("0"))
        quality = FibonacciQuality.from_candles(recent_candles, candidate.side, atr, ema_fast)
        gate = evaluate_gate(candidate, period=period, now_epoch=int(time.time()) if now_epoch is None else now_epoch, atr=atr, order_book=book.top,
                             contract=contract, funding=funding, recent_candles=recent_candles, prior_trade=prior_trade,
                             market_context=market_context, candidate_state=state, fibonacci_quality=quality,
                             execution_facts=snapshot.facts, claimed_structure_keys=claimed_structure_keys,
                             momentum_quality=momentum_quality, ema_fast=ema_fast, ema_slow=ema_slow, adx=adx,
                             config=effective)
        if not gate.allowed:
            return EntrySubmission(False, gate, None, None, None, gate.reason)
        assert gate.price_plan is not None and gate.size_plan is not None
        try:
            self._executor.set_leverage(contract.contract_code, "long" if candidate.side is TrendSide.LONG else "short",
                                        self._config.leverage)
        except Exception as error:
            leverage_gate = GateResult(False, "MARGIN_RISK", f"leverage switch failed: {type(error).__name__}",
                                      gate.price_plan, gate.size_plan)
            return EntrySubmission(False, leverage_gate, None, None, None, leverage_gate.reason)
        structure_key = _entry_cycle_key(contract.contract_code, candidate.side, candidate.model, run_id,
                                         candidate.source_breakout_time)
        structure_claimed = False
        if structure_key is not None:
            claim = getattr(self._repository, "claim_entry_cycle", None)
            if claim is None or not claim(structure_key=structure_key, contract_code=contract.contract_code,
                                          timeframe=period, direction=candidate.side.value,
                                          primary_model=candidate.model.value,
                                          breakout_bar_time=candidate.source_breakout_time,
                                          source_run_id=run_id):
                structure_gate = GateResult(False, "COOLDOWN_STRUCTURE", "STRUCTURE_ALREADY_CONSUMED", gate.price_plan, gate.size_plan)
                return EntrySubmission(False, structure_gate, None, None, None, structure_gate.reason)
            structure_claimed = True
        position_key = f"python-trend-{uuid.uuid4()}"
        client_order_id = _numeric_client_order_id()
        reservation_key = sha256(f"{self._instance.instance_key}:{position_key}".encode()).hexdigest()[:160]
        position_id: int | None = None
        try:
            fee_rate = snapshot.facts.actual_taker_fee_rate
            estimated_entry_fee = gate.size_plan.notional * fee_rate if fee_rate is not None else None
            estimated_close_fee = gate.size_plan.notional * fee_rate if fee_rate is not None else None
            gross_target = ((candidate.target_low - gate.price_plan.desired_entry) if candidate.side is TrendSide.LONG
                            else (gate.price_plan.desired_entry - candidate.target_low)) * Decimal(gate.size_plan.contracts) * contract.contract_size
            expected_net_after_fees = (gross_target - (estimated_entry_fee or Decimal("0")) - (estimated_close_fee or Decimal("0")))
            sizing_reason = (f"policy={policy.policy.policy_code}@{policy.policy.policy_version}; baseScore={policy.base_score}; "
                             f"policyMultiplier={policy.sizing_multiplier}; adaptiveMultiplier={adaptive_multiplier}; "
                             f"plannedNotional={planned_notional}")
            position_id = self._repository.record_position_intent(PositionIntent(
                position_key, run_id, contract.contract_code, candidate.side.value, contract.contract_size,
                gate.size_plan.contracts, self._config.leverage, self._account, candidate.model.value, factor_snapshot_id,
                "; ".join(candidate.evidence), gate.reason, candidate.invalidation_price, candidate.target_low,
                candidate.target_high, candidate.expected_r, timeframe_profile, market_context.regime.value,
                self._instance.strategy_family, self._instance.strategy_name, None, policy.confirmation_models,
                policy.policy.policy_version, "; ".join(candidate.evidence), total_multiplier, sizing_reason, fee_rate,
                estimated_entry_fee, estimated_close_fee, expected_net_after_fees))
            if structure_claimed:
                self._repository.attach_entry_cycle_intent(structure_key=structure_key, source_run_id=run_id,
                                                          position_id=position_id)
            request = _order_request(contract.contract_code, candidate.side, client_order_id, gate.size_plan.contracts,
                                     self._config.entry_order_type)
            self._repository.record_order_intent(TrendOrderIntent(position_id, position_key, run_id, contract.contract_code,
                "ENTRY", candidate.side.value, request.side.upper(), request.position_side.upper(), False, client_order_id,
                gate.size_plan.contracts, self._account, candidate.model.value, factor_snapshot_id,
                f"entry: {gate.reason}", timeframe_profile, self._instance.strategy_family, self._instance.strategy_name,
                total_multiplier, sizing_reason, gate.reason, fee_rate, estimated_entry_fee, estimated_close_fee))
            # V5 returns ids under slightly different keys depending on API version.
            response = self._executor.place(request)
            exchange_id = _order_id(response)
            if exchange_id is None:
                raise RuntimeError("HTX accepted response lacks order id")
            self._repository.mark_order_accepted(position_id=position_id, leg="ENTRY", client_order_id=client_order_id,
                                                 exchange_order_id=exchange_id, exchange_client_order_id=_client_id(response),
                                                 message="entry accepted by exchange")
            self._repository.mark_position_status(position_id=position_id, status="ENTRY_SENT", message="entry sent")
            return EntrySubmission(True, gate, position_id, client_order_id, exchange_id, "entry accepted")
        except Exception as error:
            if position_id is not None:
                self._repository.mark_order_rejected(position_id=position_id, leg="ENTRY", client_order_id=client_order_id,
                                                     message=f"entry rejected: {type(error).__name__}")
                self._repository.mark_position_status(position_id=position_id, status="REJECTED", message=f"entry rejected: {type(error).__name__}")
            if structure_claimed:
                self._repository.abandon_entry_cycle(structure_key=structure_key,
                                                     reason=f"entry failure: {type(error).__name__}")
            return EntrySubmission(False, gate, position_id, client_order_id, None, f"entry rejected: {type(error).__name__}")

    def reconcile(self, *, client_order_id: str, contract_code: str, position_id: int, leg: str,
                  factor_snapshot_id: int | None, model: str, contract_size: Decimal,
                  position_key: str | None = None, run_id: str | None = None, side: str | None = None) -> str:
        """Reconcile a persisted intent using the exact Java V5 status semantics.

        A restart only queries by client id.  It never creates another order,
        and a close releases risk only after an exchange-confirmed full fill.
        """
        order = self._executor.order(contract_code, client_order_id=client_order_id)
        status = _terminal_status(order)
        filled = _decimal(order, "trade_volume") or Decimal("0")
        price = _decimal(order, "trade_avg_price")
        fee = _decimal(order, "fee") or Decimal("0")
        exchange_id = _order_id(order)
        exchange_state = _exchange_state(order)
        self._repository.reconcile_order(position_id=position_id, leg=leg, client_order_id=client_order_id, status=status,
            exchange_order_id=exchange_id, exchange_client_order_id=_client_id(order), exchange_state=exchange_state,
            filled_contracts=filled, average_price=price, fee=fee, fee_currency=str(order.get("fee_currency") or "USDT"),
            realized_profit=_decimal(order, "real_profit"), message="exchange reconciliation")
        if status == "FILLED" and filled > 0 and leg == "ENTRY":
            self._repository.mark_position_status(position_id=position_id, status="ACTIVE", message="entry filled")
            mark_active = getattr(self._repository, "mark_entry_cycle_active", None)
            if mark_active is not None:
                mark_active(position_id=position_id)
            self._repository.record_ledger_event(LedgerEvent(f"python-open-fill:{position_id}:{client_order_id}", "OPEN_FILL",
                self._account, self._instance.strategy_family, self._instance.strategy_name, model, run_id, factor_snapshot_id,
                contract_code, position_id, None, client_order_id, side, execution_price_usdt=price, entry_price_usdt=price,
                filled_contracts=filled, contract_size=contract_size,
                amount_usdt=(price * filled * contract_size if price else None), fee_usdt=fee, reason="exchange confirmed entry fill"))
            if self._on_confirmed_entry is not None and price is not None and position_key:
                try:
                    self._on_confirmed_entry(ConfirmedEntryFill(position_id, position_key, run_id, contract_code,
                        TrendSide(side) if side else TrendSide.LONG, int(filled), price, contract_size, model, factor_snapshot_id))
                except Exception:
                    # A follower must never reverse a primary exchange-confirmed fill.
                    pass
        elif leg == "ENTRY" and status in {"CANCELLED", "REJECTED"} and filled == 0:
            self._repository.mark_position_status(position_id=position_id, status="REJECTED", message=f"entry terminal without fill state={exchange_state}")
            if position_key:
                self._repository.release_risk(_reservation_key(self._instance, position_key), "confirmed entry terminal without fill")
        elif status == "FILLED" and filled > 0 and leg in {"CLOSE", "PARTIAL_CLOSE"}:
            if leg == "CLOSE":
                self._repository.mark_position_status(position_id=position_id, status="DONE", message="close filled")
                mark_terminal = getattr(self._repository, "mark_entry_cycle_terminal", None)
                if mark_terminal is not None:
                    mark_terminal(position_id=position_id, reason="exchange confirmed full close")
            else:
                self._repository.mark_position_status(position_id=position_id, status="ACTIVE", message="partial close filled")
            realized = _decimal(order, "real_profit")
            self._repository.record_ledger_event(LedgerEvent(f"python-close-fill:{position_id}:{client_order_id}", "CLOSE_FILL",
                self._account, self._instance.strategy_family, self._instance.strategy_name, model, run_id, factor_snapshot_id,
                contract_code, position_id, None, client_order_id, side, execution_price_usdt=price, close_price_usdt=price,
                filled_contracts=filled, contract_size=contract_size, amount_usdt=(price * filled * contract_size if price else None),
                fee_usdt=fee, gross_pnl_usdt=realized, net_pnl_after_fee=(realized - fee if realized is not None else None),
                reason=f"exchange confirmed {leg.lower()} fill"))
            if self._on_confirmed_reduction is not None and position_key:
                try:
                    self._on_confirmed_reduction(ConfirmedReductionFill(position_id, position_key, run_id, contract_code,
                        TrendSide(side) if side else TrendSide.LONG, int(filled), model, factor_snapshot_id, leg == "CLOSE"))
                except Exception:
                    # A follower failure is recorded in its own intent path and cannot undo primary fill.
                    pass
        return status

    def reconcile_pending(self) -> dict[str, str]:
        """Restart-safe reconciliation for persisted intents; no order is re-sent."""
        result: dict[str, str] = {}
        for item in self._repository.pending_orders(account_id=self._account.account_id):
            client_order_id = str(item["client_order_id"])
            result[client_order_id] = self.reconcile(client_order_id=client_order_id, contract_code=item["contract_code"],
                position_id=int(item["position_id"]), leg=str(item["leg"]), factor_snapshot_id=item.get("factor_snapshot_id"),
                model=str(item["model_name"]), contract_size=Decimal(str(item["contract_size"])),
                position_key=str(item["position_key"]), run_id=item.get("run_id"), side=item.get("trend_side"))
        return result

    def _sizing_multiplier(self, candidate: ModelCandidate, state: CandidateTrendState, context: MarketContext) -> Decimal:
        """Literal port of Java ``sizingContext`` for combination candidates."""
        multiplier = self._config.strong_quality_multiplier if state.strong else Decimal("1")
        if context.breadth_aligned(candidate.side):
            multiplier *= self._config.breadth_aligned_multiplier
        elif context.breadth_opposed(candidate.side):
            multiplier *= self._config.breadth_counter_multiplier
        else:
            # Every candidate reaching this executor is a Java combination
            # candidate; Java reduces mixed-market combinations rather than
            # allowing Python's former neutral 1.0 multiplier.
            multiplier *= self._config.combination_mixed_market_multiplier
        return min(self._config.max_adaptive_multiplier,
                   max(self._config.min_adaptive_multiplier, multiplier))


def _entry_cycle_key(contract_code: str, side: TrendSide, model: str | object, run_id: str,
                     breakout_bar_time: int | None) -> str | None:
    """Match Java: only Fibonacci owns a persistent entry-cycle key."""
    model_value = getattr(model, "value", str(model))
    if model_value != "FIB_PULLBACK_CONTINUATION" or breakout_bar_time is None:
        return None
    return f"{contract_code}:{side.value}:{model_value}:ENTRY_CYCLE:{run_id}"


def _prior_trade(row: dict[str, Any] | None):
    if row is None:
        return None
    value = row.get("updated_at")
    if isinstance(value, datetime):
        closed_at = int(value.replace(tzinfo=value.tzinfo or UTC).timestamp())
    else:
        try:
            closed_at = int(datetime.fromisoformat(str(value)).replace(tzinfo=UTC).timestamp())
        except (TypeError, ValueError):
            return None
    realized = Decimal(str(row.get("realized_profit") or "0"))
    entry_fee = Decimal(str(row.get("entry_fee") or "0"))
    close_fee = Decimal(str(row.get("close_fee") or "0"))
    return PriorTrade(str(row.get("contract_code") or ""), closed_at, realized - entry_fee - close_fee, row.get("message"))


def _order_request(contract: str, side: TrendSide, client_order_id: str, volume: int, type_: str) -> SwapOrderRequest:
    long = side is TrendSide.LONG
    return SwapOrderRequest(contract, "long" if long else "short", "buy" if long else "sell", type_, client_order_id, str(volume))


def _numeric_client_order_id() -> str:
    return str((int(datetime.now(UTC).timestamp() * 1_000_000) + uuid.uuid4().int % 1_000_000) % 9_000_000_000_000_000_000)


def _decimal(row: dict[str, Any], *names: str) -> Decimal | None:
    for name in names:
        if row.get(name) is not None:
            return Decimal(str(row[name]))
    return None


def _order_id(row: dict[str, Any]) -> int | None:
    value = row.get("order_id") or row.get("orderId")
    return int(value) if value is not None else None


def _client_id(row: dict[str, Any]) -> int | None:
    value = row.get("client_order_id") or row.get("clientOrderId")
    try:
        return int(value) if value is not None else None
    except ValueError:
        return None


def _reservation_key(instance: RuntimeInstance, position_key: str) -> str:
    return sha256(f"{instance.instance_key}:{position_key}".encode()).hexdigest()[:160]


def _exchange_state(row: dict[str, Any]) -> str:
    # Java maps V5 ``state`` first, then accepts legacy numeric status.  A few
    # HTX compatibility envelopes expose the textual V5 state in ``status``.
    state = str(row.get("state") or row.get("order_state") or "").lower()
    if state:
        return state
    legacy = str(row.get("status") or row.get("order_status") or "").lower()
    if legacy in {"new", "partially_filled", "filled", "partially_canceled", "canceled", "cancelled", "rejected"}:
        return legacy
    return {"6": "filled", "5": "canceled", "7": "rejected"}.get(legacy, "new")


def _terminal_status(row: dict[str, Any]) -> str:
    state = _exchange_state(row)
    filled = _decimal(row, "trade_volume") or Decimal("0")
    if state == "filled":
        return "FILLED"
    if state in {"partially_filled", "partial-filled"}:
        return "PARTIALLY_FILLED"
    if state in {"partially_canceled", "canceled", "cancelled"}:
        return "PARTIALLY_FILLED" if filled > 0 else "CANCELLED"
    if state == "rejected":
        return "PARTIALLY_FILLED" if filled > 0 else "REJECTED"
    return "ACCEPTED"
