"""Deterministic paper exchange used for replay and shadow validation."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
import uuid

from .ledger import Account, Fill, Order, Position, TrendLedger
from .market_data import Candle


@dataclass(frozen=True, slots=True)
class PaperOrder:
    account_id: str
    contract_code: str
    position_side: str  # LONG/SHORT
    side: str           # BUY/SELL
    quantity: int
    order_type: str     # MARKET/LIMIT
    created_at: datetime
    client_order_id: str
    limit_price: Decimal | None = None
    position_id: str | None = None
    parent_order_id: str | None = None
    strategy_run_id: str | None = None


class PaperExchange:
    def __init__(self, ledger: TrendLedger, *, fee_rate: Decimal = Decimal("0.0004")) -> None:
        self._ledger, self._fee_rate = ledger, fee_rate

    def submit(self, request: PaperOrder, candle: Candle) -> Order:
        order = Order(str(uuid.uuid4()), request.account_id, request.position_id, request.contract_code, request.side,
                      request.position_side, request.order_type, request.quantity, request.client_order_id, "OPEN",
                      request.created_at, request.limit_price, request.parent_order_id, request.strategy_run_id)
        self._ledger.record_order(order)
        fill_price = self._fill_price(request, candle)
        if fill_price is None:
            return order
        fill = Fill(str(uuid.uuid4()), order.order_id, request.account_id, request.quantity, fill_price,
                    fill_price * Decimal(request.quantity) * self._fee_rate, request.created_at)
        self._ledger.record_fill(fill)
        return Order(**{**order.__dict__, "status": "FILLED", "filled_at": request.created_at})

    def open_position(self, account_id: str, contract_code: str, side: str, quantity: int, entry_price: Decimal,
                      when: datetime, run_id: str, parent_position_id: str | None = None) -> Position:
        position = Position(str(uuid.uuid4()), account_id, contract_code, side, quantity, entry_price, Decimal("0"), "OPEN",
                            when, None, parent_position_id, run_id)
        self._ledger.open_position(position)
        return position

    @staticmethod
    def _fill_price(request: PaperOrder, candle: Candle) -> Decimal | None:
        if request.order_type == "MARKET":
            return candle.close
        if request.limit_price is not None and candle.low <= request.limit_price <= candle.high:
            return request.limit_price
        return None
