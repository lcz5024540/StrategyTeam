"""Composable Python trend runtime: scan -> gate -> paper order -> ledger.

It intentionally has no path to ``HtxV5Client.place_order``.  This is the
paper/shadow runtime used to compare Python decisions with Java before P6.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime
from decimal import Decimal
from typing import Sequence

from .execution import ExecutionConfig, ExecutionFacts, GateResult, PriorTrade, evaluate_gate
from .exits import ExitInput, ExitAction, decide
from .ledger import TrendLedger
from .market_data import Candle, ContractSpec, FundingRate, KlineWindow, TopOfBook
from .java_entry_contract import JavaEntryConfig
from .models import ModelCandidate, ModelCode, TrendSide, route_candidates
from .strategy_context import FibonacciQuality, MarketContext
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact
from .strategy_decision import HtxStrategyDecisionEngine, LEGACY_STRATEGY_VERSION
from .paper import PaperExchange, PaperOrder


@dataclass(frozen=True, slots=True)
class ScanOutcome:
    run_id: str
    candidates: tuple[ModelCandidate, ...]
    gates: tuple[GateResult, ...]
    selected: ModelCandidate | None
    paper_order_id: str | None


class PaperTrendRuntime:
    def __init__(self, ledger: TrendLedger, exchange: PaperExchange, *, account_id: str,
                 entry_config: JavaEntryConfig = JavaEntryConfig(), execution_config: ExecutionConfig = ExecutionConfig(),
                 mirror_account_id: str | None = None, strategy_version: str = LEGACY_STRATEGY_VERSION,
                 admission: StrategyAdmissionArtifact | None = None) -> None:
        self._ledger, self._exchange = ledger, exchange
        self._account_id, self._mirror_account_id = account_id, mirror_account_id
        self._decision = HtxStrategyDecisionEngine(strategy_version=strategy_version, config=entry_config,
                                                   admission=admission)
        self._execution_config = execution_config

    def scan(self, *, run_id: str, entry_window: KlineWindow, trend_window: KlineWindow, now_epoch: int,
             at: datetime, book: TopOfBook, contract: ContractSpec, funding: FundingRate | None,
             prior_trade: PriorTrade | None = None, market_context: MarketContext | None = None,
             execution_facts: ExecutionFacts | None = None,
             claimed_structure_keys: frozenset[str] = frozenset(),
             execution_candle: Candle | None = None) -> ScanOutcome:
        decision = self._decision.decide(contract_code=entry_window.contract_code, period=entry_window.period,
                                         candles=entry_window.candles, surface=DecisionSurface.PAPER)
        signal = decision.signal
        context = market_context or MarketContext.neutral()
        output = decision.candidates
        route = route_candidates(output, market_context=context)
        gates = tuple(evaluate_gate(item, period=entry_window.period, now_epoch=now_epoch,
                                    atr=signal.indicators.atr if signal.indicators else Decimal("0"), order_book=book,
                                    contract=contract, funding=funding, recent_candles=entry_window.candles,
                                    prior_trade=prior_trade, market_context=context, execution_facts=execution_facts,
                                    claimed_structure_keys=claimed_structure_keys,
                                    fibonacci_quality=(FibonacciQuality.from_candles(entry_window.candles, item.side,
                                        signal.indicators.atr, signal.indicators.ema_fast) if item.model is ModelCode.FIB_PULLBACK_CONTINUATION
                                                       and signal.indicators is not None else None),
                                    config=self._execution_config) for item in output)
        self._ledger.decision(self._account_id, run_id, "ENTRY_SCAN", {
            "signal": signal.reason, "side": signal.side.value,
            "candidates": [item.model.value for item in output], "gates": [item.reason for item in gates],
            "primary_model": route.primary.model.value if route.primary else None, "route": route.reason,
            "strategy_version": decision.strategy_version, "decision_hash": decision.decision_hash,
        })
        selected = next((item for item in route.ranked if gates[output.index(item)].allowed), None)
        if selected is None:
            return ScanOutcome(run_id, output, gates, None, None)
        gate = gates[output.index(selected)]
        assert gate.price_plan is not None and gate.size_plan is not None
        order_type = "LIMIT" if gate.price_plan.style.value == "PASSIVE_LIMIT" else "MARKET"
        request = PaperOrder(self._account_id, entry_window.contract_code, selected.side.value,
                             "BUY" if selected.side.value == "LONG" else "SELL", gate.size_plan.contracts,
                             order_type, at, f"paper-{run_id}-{selected.model.value}",
                             gate.price_plan.desired_entry if order_type == "LIMIT" else None, strategy_run_id=run_id)
        fill_candle = execution_candle or entry_window.candles[-1]
        order = self._exchange.submit(request, fill_candle)
        self._ledger.decision(self._account_id, run_id, "ENTRY_ORDER", {"candidate": selected.model.value,
                              "order_id": order.order_id, "status": order.status, "gate": gate.reason})
        if order.status == "FILLED":
            entry_price = order.limit_price or fill_candle.close
            position = self._exchange.open_position(self._account_id, entry_window.contract_code, selected.side.value,
                                                    gate.size_plan.contracts, entry_price, at, run_id)
            self._ledger.save_position_context(position.position_id, {"model": selected.model.value,
                "structure_key": selected.structure_key, "confirmation_models": [item.value for item in selected.confirmation_models],
                "initial_stop": str(selected.invalidation_price), "invalidation": str(selected.invalidation_price),
                "structure_target": str(selected.target_low), "contract_size": str(contract.contract_size),
                "entry_fee": str(entry_price * contract.contract_size * Decimal(gate.size_plan.contracts) * Decimal("0.0004")),
                "highest": str(entry_price), "lowest": str(entry_price), "protection": None,
                "trailing_armed_at": None, "trailing_arm_reason": None, "max_favorable_r": "0"})
            if self._mirror_account_id:
                self._mirror(position.position_id, order.order_id, entry_window.contract_code, selected.side.value,
                             gate.size_plan.contracts, at, run_id, fill_candle, contract.contract_size)
        return ScanOutcome(run_id, output, gates, selected, order.order_id)

    def _mirror(self, parent_position_id: str, parent_order_id: str, contract_code: str, side: str, quantity: int,
                at: datetime, run_id: str, candle, contract_size: Decimal) -> None:
        request = PaperOrder(self._mirror_account_id, contract_code, side, "BUY" if side == "LONG" else "SELL", quantity,
                             "MARKET", at, f"paper-mirror-{run_id}", parent_order_id=parent_order_id, strategy_run_id=run_id)
        order = self._exchange.submit(request, candle)
        self._ledger.decision(self._mirror_account_id, run_id, "MIRROR_ENTRY", {"parent_order_id": parent_order_id,
                              "order_id": order.order_id, "status": order.status})
        if order.status == "FILLED":
            position = self._exchange.open_position(self._mirror_account_id, contract_code, side, quantity, candle.close, at, run_id,
                                                    parent_position_id)
            self._ledger.save_position_context(position.position_id, {"model": "MIRROR", "contract_size": str(contract_size),
                "highest": str(candle.close), "lowest": str(candle.close), "parent_position_id": parent_position_id})

    def manage(self, *, run_id: str, position_id: str, reference_price: Decimal, highest_price: Decimal,
               lowest_price: Decimal, atr: Decimal, signal_side, ema_fast: Decimal | None, ema_slow: Decimal | None,
               major_timeframe_opposition: int, market_context_opposes: bool, at: datetime,
               flat_streak: int = 0, protection_breach_bars: int = 0) -> object:
        position = self._ledger.position(position_id)
        context = self._ledger.position_context(position_id)
        if position.status != "OPEN":
            raise ValueError("only open paper positions can be managed")
        side = TrendSide(position.side)
        model = ModelCode(context["model"])
        contract_size = Decimal(context["contract_size"])
        gross = (reference_price - position.entry_price) * contract_size * position.quantity if side.value == "LONG" else (position.entry_price - reference_price) * contract_size * position.quantity
        estimated_close_fee = reference_price * contract_size * Decimal(position.quantity) * Decimal("0.0004")
        decision = decide(ExitInput(model, side, position.quantity, contract_size, position.entry_price,
            Decimal(context["initial_stop"]), Decimal(context["invalidation"]), Decimal(context["structure_target"]),
            reference_price, Decimal(context["protection"]) if context.get("protection") else None, highest_price, lowest_price,
            atr, signal_side, ema_fast, ema_slow, major_timeframe_opposition, market_context_opposes,
            gross - Decimal(context.get("entry_fee", "0")) - estimated_close_fee, Decimal(context.get("entry_fee", "0")),
            estimated_close_fee, datetime.fromisoformat(context["trailing_armed_at"]) if context.get("trailing_armed_at") else None,
            context.get("trailing_arm_reason"), Decimal(context.get("max_favorable_r", "0")), at, flat_streak=flat_streak,
            protection_breach_bars=protection_breach_bars))
        context.update({"highest": str(highest_price), "lowest": str(lowest_price),
                        "protection": str(decision.protection_price) if decision.protection_price else None,
                        "trailing_armed_at": decision.trailing_armed_at.isoformat() if decision.trailing_armed_at else None,
                        "trailing_arm_reason": decision.trailing_arm_reason, "max_favorable_r": str(decision.max_favorable_r)})
        self._ledger.save_position_context(position_id, context)
        self._ledger.decision(position.account_id, run_id, "EXIT_DECISION", {"phase": decision.phase.value,
                              "action": decision.action.value, "reason": decision.reason, "evidence": decision.evidence}, position_id=position_id)
        if decision.action in (ExitAction.FULL_CLOSE, ExitAction.PARTIAL_CLOSE):
            quantity = position.quantity if decision.action is ExitAction.FULL_CLOSE else position.quantity - (decision.target_remaining_contracts or 0)
            if quantity > 0:
                close = self._exchange.reduce_position(position, quantity=quantity, exit_price=reference_price,
                                                       contract_size=contract_size, when=at, run_id=run_id)
                self._mirror_reduction(position_id, quantity, reference_price, at, run_id, close.order_id, contract_size)
        return decision

    def _mirror_reduction(self, primary_position_id: str, quantity: int, price: Decimal, at: datetime, run_id: str,
                          parent_order_id: str, contract_size: Decimal) -> None:
        for mirror in self._ledger.mirrored_positions(primary_position_id):
            mirror_quantity = min(quantity, mirror.quantity)
            if mirror_quantity:
                self._exchange.reduce_position(mirror, quantity=mirror_quantity, exit_price=price, contract_size=contract_size,
                                               when=at, run_id=run_id, parent_order_id=parent_order_id)
