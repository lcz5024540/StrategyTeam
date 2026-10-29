"""Composable Python trend runtime: scan -> gate -> paper order -> ledger.

It intentionally has no path to ``HtxV5Client.place_order``.  This is the
paper/shadow runtime used to compare Python decisions with Java before P6.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Sequence
import copy
import json
import os
from pathlib import Path

from .execution import ExecutionConfig, ExecutionFacts, GateResult, PriorTrade, evaluate_gate
from .exits import ExitInput, ExitAction, decide
from .continuation import (BASELINE, CONFIRM, LINKED, ContinuationConfig, advance_confirmation,
                           decide_continuation, reentry_gate)
from .ledger import TrendLedger
from .maker_execution import (
    MakerExecutionConfig, MakerTimeoutAction,
    evaluate_maker_lifecycle, maker_limit_price, maker_reference_price,
    p_shadow_taker_as_dict, p_shadow_taker_evidence,
)
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
                 admission: StrategyAdmissionArtifact | None = None,
                 maker_config: MakerExecutionConfig = MakerExecutionConfig(),
                 evidence_store=None, compact=None, evidence_context=None,
                 continuation_config: ContinuationConfig = ContinuationConfig()) -> None:
        self._ledger, self._exchange = ledger, exchange
        self._account_id, self._mirror_account_id = account_id, mirror_account_id
        self._decision = HtxStrategyDecisionEngine(strategy_version=strategy_version, config=entry_config,
                                                   admission=admission)
        self._execution_config = execution_config
        self._maker_config = maker_config
        self._evidence_store = evidence_store
        self._compact = compact
        self._evidence_context = evidence_context or {}
        self.evidence_error = None
        continuation_config.require_15m(strategy_version == "HTX_TREND_15M_V2")
        self._continuation = continuation_config

    def _version_ref(self):
        from .research.store import digest
        manifest_path = Path(__file__).resolve().parents[2] / 'MANIFEST.json'
        manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
        config = {'entry': asdict(self._decision.config), 'execution': asdict(self._execution_config),
                  'maker': asdict(self._maker_config), 'continuation': asdict(self._continuation),
                  'paper_worker': getattr(self, '_paper_worker_config', None)}
        return self._evidence_store.put('compact_strategy_version', {
            **self._evidence_context, 'strategy': self._decision.strategy_version,
            'source_commit': manifest.get('source_commit') or os.getenv('HTX_PYTHON_SOURCE_COMMIT') or 'UNPACKAGED',
            'package_version': manifest.get('version', 'UNPACKAGED'),
            'package_sha256': os.getenv('HTX_PYTHON_PACKAGE_SHA256') or None,
            'config_fingerprint': digest(config), 'config': config})

    def scan(self, *, run_id: str, entry_window: KlineWindow, trend_window: KlineWindow, now_epoch: int,
             at: datetime, book: TopOfBook, contract: ContractSpec, funding: FundingRate | None,
             prior_trade: PriorTrade | None = None, market_context: MarketContext | None = None,
             execution_facts: ExecutionFacts | None = None,
             claimed_structure_keys: frozenset[str] = frozenset(),
             execution_candle: Candle | None = None, previous_exit: dict | None = None) -> ScanOutcome:
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
        if self._continuation.version == LINKED:
            gates = tuple(reentry_gate(item, entry_window.candles[-1], book, previous_exit) or gate
                          for item, gate in zip(output, gates))
        evidence = {}
        if self._compact is not None:
            refs = (*self._compact.retain_input(entry_window), *self._compact.retain_input(trend_window), self._version_ref())
            ref = self._evidence_store.put('compact_entry_decision', {
                **self._evidence_context, 'run_id': run_id, 'at': at, 'now_epoch': now_epoch,
                'contract_code': entry_window.contract_code, 'period': entry_window.period,
                'signal': signal, 'candidates': output, 'gates': gates, 'route': route,
                'decision_hash': decision.decision_hash, 'book': book, 'contract': contract,
                'funding_quote': funding, 'funding_paid': None, 'funding_paid_status': 'UNKNOWN',
                'execution_facts': execution_facts, 'prior_trade': prior_trade, 'market_context': context,
                'claimed_structure_keys': sorted(claimed_structure_keys), 'execution_candle': execution_candle,
                'previous_exit': previous_exit, 'continuation': asdict(self._continuation),
                'entry_window': entry_window.evidence_refs, 'trend_window': trend_window.evidence_refs,
            }, refs=refs)
            evidence = {'evidence_ref': ref, 'evidence_status': 'COMPLETE'}
        self._ledger.decision(self._account_id, run_id, "ENTRY_SCAN", {
            **evidence,
            "signal": signal.reason, "side": signal.side.value,
            "candidates": [item.model.value for item in output], "gates": [item.reason for item in gates],
            "primary_model": route.primary.model.value if route.primary else None, "route": route.reason,
            "strategy_version": decision.strategy_version, "decision_hash": decision.decision_hash,
            **({"continuation_version": self._continuation.version} if self._continuation.version != BASELINE else {}),
        })
        selected = next((item for item in route.ranked if gates[output.index(item)].allowed), None)
        if selected is None:
            return ScanOutcome(run_id, output, gates, None, None)
        gate = gates[output.index(selected)]
        assert gate.price_plan is not None and gate.size_plan is not None
        if self._maker_config.enabled:
            return self._submit_maker_entry(
                run_id=run_id, selected=selected, gate=gate, book=book,
                entry_window=entry_window, contract=contract, at=at,
                execution_candle=execution_candle, output=output, gates=gates)
        order_type = "LIMIT" if gate.price_plan.style.value == "PASSIVE_LIMIT" else "MARKET"
        request = PaperOrder(self._account_id, entry_window.contract_code, selected.side.value,
                             "BUY" if selected.side.value == "LONG" else "SELL", gate.size_plan.contracts,
                             order_type, at, f"paper-{run_id}-{selected.model.value}",
                             gate.price_plan.desired_entry if order_type == "LIMIT" else None, strategy_run_id=run_id,
                             signal_px=selected.entry_price, signal_ts=datetime.fromtimestamp(selected.bar_time, UTC), mid=book.mid)
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
                "trailing_armed_at": None, "trailing_arm_reason": None, "max_favorable_r": "0",
                **({"continuation_version": self._continuation.version} if self._continuation.version != BASELINE else {})})
            if self._mirror_account_id:
                self._mirror(position.position_id, order.order_id, entry_window.contract_code, selected.side.value,
                             gate.size_plan.contracts, at, run_id, fill_candle, contract.contract_size, mid=book.mid)
        return ScanOutcome(run_id, output, gates, selected, order.order_id)

    def _submit_maker_entry(self, *, run_id: str, selected: ModelCandidate, gate: GateResult, book: TopOfBook,
                            entry_window: KlineWindow, contract: ContractSpec, at: datetime,
                            execution_candle: Candle | None, output: tuple[ModelCandidate, ...],
                            gates: tuple[GateResult, ...]) -> ScanOutcome:
        """PAPER maker path: LIMIT @ best same-side price, freeze-table lifecycle, no taker fallback."""
        cfg = self._maker_config
        reference = maker_reference_price(selected.side, book)
        if reference is None or reference <= Decimal("0"):
            self._ledger.decision(self._account_id, run_id, "MAKER_MISSING_BOOK", {
                "candidate": selected.model.value, "side": selected.side.value,
                "reason": "missing same-side book price; maker reference unavailable"})
            return ScanOutcome(run_id, output, gates, selected, None)
        limit_price = maker_limit_price(selected.side, reference, cfg.offset_bps)
        fill_candle = execution_candle or entry_window.candles[-1]
        lifecycle = evaluate_maker_lifecycle(
            side=selected.side, quantity=gate.size_plan.contracts, placed_price=limit_price,
            candles=(fill_candle,), max_wait_bars=cfg.max_wait_bars,
            max_amends=cfg.max_amends, amend_threshold_bps=cfg.amend_threshold_bps,
            partial_accept=cfg.partial_accept, timeout_action=cfg.timeout_action)
        # P-SHADOW-TAKER: record maker_live vs taker_shadow cost pair for every signal.
        taker_shadow_price = book.ask if selected.side is TrendSide.LONG else book.bid
        p_evidence = p_shadow_taker_evidence(
            run_id=run_id, contract_code=entry_window.contract_code, side=selected.side,
            signal_price=selected.entry_price, maker_price=limit_price, taker_price=taker_shadow_price,
            maker_fee_rate=cfg.maker_fee_rate, taker_fee_rate=self._execution_config.fee_rate,
            fill_status=lifecycle.final_status,
            notes=(f"offset_bps={cfg.offset_bps}; wait_bars={cfg.max_wait_bars}; "
                   f"max_amends={cfg.max_amends}; timeout_action={cfg.timeout_action.value}; "
                   f"lifecycle_reason={lifecycle.reason}"))
        self._ledger.decision(self._account_id, run_id, "P_SHADOW_TAKER", p_shadow_taker_as_dict(p_evidence))
        if self._evidence_store is not None:
            self._evidence_store.put("p_shadow_taker", p_shadow_taker_as_dict(p_evidence))
        if lifecycle.final_status not in ("FILLED", "PARTIAL_TAKER", "TAKER"):
            decision_kind = "MAKER_CANCELLED"
            if lifecycle.final_status in ("PARTIAL_CANCELLED",):
                decision_kind = "MAKER_PARTIAL_CANCELLED"
            elif lifecycle.final_status in ("OPEN",):
                decision_kind = "MAKER_EXPIRED"
            self._ledger.decision(self._account_id, run_id, decision_kind, {
                "candidate": selected.model.value, "side": selected.side.value,
                "limit_price": str(limit_price), "filled_quantity": lifecycle.filled_quantity,
                "remaining_quantity": lifecycle.remaining_quantity,
                "bars_held": lifecycle.bars_held, "amends_done": lifecycle.amends_done,
                "final_status": lifecycle.final_status, "reason": lifecycle.reason})
            return ScanOutcome(run_id, output, gates, selected, None)
        # Fill the accepted portion at the limit price against a deterministic candle.
        request = PaperOrder(self._account_id, entry_window.contract_code, selected.side.value,
                             "BUY" if selected.side.value == "LONG" else "SELL", lifecycle.filled_quantity,
                             "LIMIT", at, f"paper-{run_id}-{selected.model.value}",
                             limit_price, strategy_run_id=run_id,
                             signal_px=selected.entry_price, signal_ts=datetime.fromtimestamp(selected.bar_time, UTC), mid=book.mid)
        fill_price = lifecycle.average_price or limit_price
        synthetic_candle = Candle(
            fill_candle.open_time, fill_price, fill_price, fill_price, fill_price,
            volume=fill_candle.volume)
        order = self._exchange.submit(request, synthetic_candle)
        self._ledger.decision(self._account_id, run_id, "ENTRY_ORDER", {"candidate": selected.model.value,
                              "order_id": order.order_id, "status": order.status, "gate": gate.reason,
                              "maker_lifecycle_status": lifecycle.final_status})
        if order.status == "FILLED":
            position = self._exchange.open_position(self._account_id, entry_window.contract_code, selected.side.value,
                                                    lifecycle.filled_quantity, fill_price, at, run_id)
            self._ledger.save_position_context(position.position_id, {"model": selected.model.value,
                "structure_key": selected.structure_key, "confirmation_models": [item.value for item in selected.confirmation_models],
                "initial_stop": str(selected.invalidation_price), "invalidation": str(selected.invalidation_price),
                "structure_target": str(selected.target_low), "contract_size": str(contract.contract_size),
                "entry_fee": str(fill_price * contract.contract_size * Decimal(lifecycle.filled_quantity) * Decimal("0.0004")),
                "highest": str(fill_price), "lowest": str(fill_price), "protection": None,
                "trailing_armed_at": None, "trailing_arm_reason": None, "max_favorable_r": "0",
                **({"continuation_version": self._continuation.version} if self._continuation.version != BASELINE else {})})
            if self._mirror_account_id:
                self._mirror(position.position_id, order.order_id, entry_window.contract_code, selected.side.value,
                             lifecycle.filled_quantity, at, run_id, synthetic_candle, contract.contract_size, mid=book.mid)
        return ScanOutcome(run_id, output, gates, selected, order.order_id)

    def _mirror(self, parent_position_id: str, parent_order_id: str, contract_code: str, side: str, quantity: int,
                at: datetime, run_id: str, candle, contract_size: Decimal, *, mid: Decimal | None = None) -> None:
        request = PaperOrder(self._mirror_account_id, contract_code, side, "BUY" if side == "LONG" else "SELL", quantity,
                             "MARKET", at, f"paper-mirror-{run_id}", parent_order_id=parent_order_id, strategy_run_id=run_id, mid=mid)
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
               flat_streak: int = 0, protection_breach_bars: int = 0,
               evidence_windows: tuple[KlineWindow, ...] = (), evaluation_candle: Candle | None = None, market_snapshot: dict | None = None) -> object:
        position = self._ledger.position(position_id)
        context = self._ledger.position_context(position_id)
        context_before = copy.deepcopy(context)
        self.evidence_error = None
        if position.status != "OPEN":
            raise ValueError("only open paper positions can be managed")
        side = TrendSide(position.side)
        model = ModelCode(context["model"])
        contract_size = Decimal(context["contract_size"])
        gross = (reference_price - position.entry_price) * contract_size * position.quantity if side.value == "LONG" else (position.entry_price - reference_price) * contract_size * position.quantity
        estimated_close_fee = reference_price * contract_size * Decimal(position.quantity) * Decimal("0.0004")
        exit_input = ExitInput(model, side, position.quantity, contract_size, position.entry_price,
            Decimal(context["initial_stop"]), Decimal(context["invalidation"]), Decimal(context["structure_target"]),
            reference_price, Decimal(context["protection"]) if context.get("protection") else None, highest_price, lowest_price,
            atr, signal_side, ema_fast, ema_slow, major_timeframe_opposition, market_context_opposes,
            gross - Decimal(context.get("entry_fee", "0")) - estimated_close_fee, Decimal(context.get("entry_fee", "0")),
            estimated_close_fee, datetime.fromisoformat(context["trailing_armed_at"]) if context.get("trailing_armed_at") else None,
            context.get("trailing_arm_reason"), Decimal(context.get("max_favorable_r", "0")), at, flat_streak=flat_streak,
            protection_breach_bars=protection_breach_bars)
        version = context.get('continuation_version', BASELINE)
        if version not in (BASELINE, CONFIRM, LINKED):
            raise ValueError('unknown position continuation version')
        candidate_policy = version in (CONFIRM, LINKED)
        if candidate_policy:
            if self._decision.strategy_version != 'HTX_TREND_15M_V2':
                raise ValueError('candidate position policy requires 15m')
            count, last_bar = advance_confirmation(exit_input, evaluation_candle, context)
            decision = decide_continuation(exit_input, count)
            if (evaluation_candle is not None and context.get('continuation_last_bar') is not None
                    and evaluation_candle.open_time <= int(context['continuation_last_bar'])):
                from dataclasses import replace
                return replace(decision, action=ExitAction.HOLD, target_remaining_contracts=None,
                               reason='continuation-duplicate-bar')
            context.update(continuation_breach_count=count, continuation_last_bar=last_bar)
        else:
            decision = decide(exit_input)
        if decision.action is ExitAction.FULL_CLOSE and evaluation_candle is not None:
            context['complete_exit'] = dict(position_id=position_id, side=position.side, reason=decision.reason,
                closed_at=at.isoformat(), protection=str(decision.protection_price) if decision.protection_price else None,
                bar_time=evaluation_candle.open_time, bar_high=str(evaluation_candle.high),
                bar_low=str(evaluation_candle.low), evidence_status='RECORDED')
        context.update({"highest": str(highest_price), "lowest": str(lowest_price),
                        "protection": str(decision.protection_price) if decision.protection_price else None,
                        "trailing_armed_at": decision.trailing_armed_at.isoformat() if decision.trailing_armed_at else None,
                        "trailing_arm_reason": decision.trailing_arm_reason, "max_favorable_r": str(decision.max_favorable_r)})
        self._ledger.save_position_context(position_id, context)
        evidence = {}
        if self._compact is not None:
            try:
                refs = tuple(ref for window in evidence_windows for ref in self._compact.retain_input(window))
                ref = self._evidence_store.put('compact_exit_decision', {
                    **self._evidence_context, 'run_id': run_id, 'at': at, 'position_id': position_id,
                    'position_before': position, 'context_before': context_before, 'input': exit_input,
                    'decision': decision, 'context_after': context, 'funding_paid': None,
                    'evaluation_candle': evaluation_candle, 'continuation_version': version,
                    'market_snapshot': market_snapshot,
                    'funding_paid_status': 'UNKNOWN'}, refs=(*refs, self._version_ref()))
                evidence = {'evidence_ref': ref, 'evidence_status': 'COMPLETE'}
            except Exception as error:
                self.evidence_error = type(error).__name__
                evidence = {'evidence_status': 'FAILED', 'evidence_error': self.evidence_error}
        self._ledger.decision(position.account_id, run_id, "EXIT_DECISION", {**evidence, "phase": decision.phase.value,
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
