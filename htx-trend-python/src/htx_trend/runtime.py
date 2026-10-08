"""Composable Python trend runtime: scan -> gate -> paper order -> ledger.

It intentionally has no path to ``HtxV5Client.place_order``.  This is the
paper/shadow runtime used to compare Python decisions with Java before P6.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime
from decimal import Decimal
from typing import Sequence

from .execution import ExecutionConfig, GateResult, PriorTrade, evaluate_gate
from .ledger import TrendLedger
from .market_data import ContractSpec, FundingRate, KlineWindow, TopOfBook
from .models import ModelCandidate, TrendConfig, candidates, evaluate_signal
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
                 trend_config: TrendConfig = TrendConfig(), execution_config: ExecutionConfig = ExecutionConfig(),
                 mirror_account_id: str | None = None) -> None:
        self._ledger, self._exchange = ledger, exchange
        self._account_id, self._mirror_account_id = account_id, mirror_account_id
        self._trend_config, self._execution_config = trend_config, execution_config

    def scan(self, *, run_id: str, entry_window: KlineWindow, trend_window: KlineWindow, now_epoch: int,
             at: datetime, book: TopOfBook, contract: ContractSpec, funding: FundingRate | None,
             prior_trade: PriorTrade | None = None) -> ScanOutcome:
        signal = evaluate_signal(entry_window.candles, trend_window.candles, self._trend_config)
        output = candidates(entry_window.candles, signal, self._trend_config)
        gates = tuple(evaluate_gate(item, period=entry_window.period, now_epoch=now_epoch,
                                    atr=signal.indicators.atr if signal.indicators else Decimal("0"), order_book=book,
                                    contract=contract, funding=funding, recent_candles=entry_window.candles,
                                    prior_trade=prior_trade, config=self._execution_config) for item in output)
        self._ledger.decision(self._account_id, run_id, "ENTRY_SCAN", {
            "signal": signal.reason, "side": signal.side.value,
            "candidates": [item.model.value for item in output], "gates": [item.reason for item in gates],
        })
        selected = next((item for item, gate in zip(output, gates) if gate.allowed), None)
        if selected is None:
            return ScanOutcome(run_id, output, gates, None, None)
        gate = next(item for item in gates if item.allowed)
        assert gate.price_plan is not None and gate.size_plan is not None
        order_type = "LIMIT" if gate.price_plan.style.value == "PASSIVE_LIMIT" else "MARKET"
        request = PaperOrder(self._account_id, entry_window.contract_code, selected.side.value,
                             "BUY" if selected.side.value == "LONG" else "SELL", gate.size_plan.contracts,
                             order_type, at, f"paper-{run_id}-{selected.model.value}",
                             gate.price_plan.desired_entry if order_type == "LIMIT" else None, strategy_run_id=run_id)
        order = self._exchange.submit(request, entry_window.candles[-1])
        self._ledger.decision(self._account_id, run_id, "ENTRY_ORDER", {"candidate": selected.model.value,
                              "order_id": order.order_id, "status": order.status, "gate": gate.reason})
        if order.status == "FILLED":
            position = self._exchange.open_position(self._account_id, entry_window.contract_code, selected.side.value,
                                                    gate.size_plan.contracts, order.limit_price or entry_window.candles[-1].close,
                                                    at, run_id)
            if self._mirror_account_id:
                self._mirror(position.position_id, order.order_id, entry_window.contract_code, selected.side.value,
                             gate.size_plan.contracts, at, run_id, entry_window.candles[-1])
        return ScanOutcome(run_id, output, gates, selected, order.order_id)

    def _mirror(self, parent_position_id: str, parent_order_id: str, contract_code: str, side: str, quantity: int,
                at: datetime, run_id: str, candle) -> None:
        request = PaperOrder(self._mirror_account_id, contract_code, side, "BUY" if side == "LONG" else "SELL", quantity,
                             "MARKET", at, f"paper-mirror-{run_id}", parent_order_id=parent_order_id, strategy_run_id=run_id)
        order = self._exchange.submit(request, candle)
        self._ledger.decision(self._mirror_account_id, run_id, "MIRROR_ENTRY", {"parent_order_id": parent_order_id,
                              "order_id": order.order_id, "status": order.status})
        if order.status == "FILLED":
            self._exchange.open_position(self._mirror_account_id, contract_code, side, quantity, candle.close, at, run_id,
                                         parent_position_id)
