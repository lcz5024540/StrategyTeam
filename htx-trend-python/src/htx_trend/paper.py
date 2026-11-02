"""Deterministic paper exchange used for replay and shadow validation."""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal, ROUND_DOWN
import uuid

from .order_px_fields import order_px_fields
from .ledger import Account, Fill, Order, Position, TrendLedger
from .market_data import Candle
from .research.lifecycle import ExecutionAssumptions


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

    signal_px: Decimal | None = None
    signal_ts: datetime | None = None
    mid: Decimal | None = None
    last: Decimal | None = None


class PaperExchange:
    def __init__(self, ledger: TrendLedger, *, fee_rate: Decimal = Decimal("0.0004")) -> None:
        self._ledger, self._fee_rate = ledger, fee_rate

    @property
    def fee_rate(self) -> Decimal:
        return self._fee_rate

    def submit(self, request: PaperOrder, candle: Candle) -> Order:
        order = Order(str(uuid.uuid4()), request.account_id, request.position_id, request.contract_code, request.side,
                      request.position_side, request.order_type, request.quantity, request.client_order_id, "OPEN",
                      request.created_at, request.limit_price, request.parent_order_id, request.strategy_run_id,
                      signal_px=request.signal_px, signal_ts=request.signal_ts, order_ts=datetime.now(UTC),
                      **order_px_fields(request.order_type, limit=request.limit_price, mid=request.mid, last=request.last))
        self._ledger.record_order(order)
        fill_price = self._fill_price(request, candle)
        if fill_price is None:
            return order
        fill = Fill(str(uuid.uuid4()), order.order_id, request.account_id, request.quantity, fill_price,
                    fill_price * Decimal(request.quantity) * self._fee_rate, request.created_at)
        self._ledger.record_fill(fill)
        return replace(order, status="FILLED", filled_at=request.created_at)

    def submit_next_event(self, request: PaperOrder, next_candle: Candle, *,
                          assumptions: ExecutionAssumptions = ExecutionAssumptions()) -> Order:
        """Use the same next-event, cost and participation rules as lifecycle research."""
        order = Order(str(uuid.uuid4()), request.account_id, request.position_id, request.contract_code, request.side,
                      request.position_side, request.order_type, request.quantity, request.client_order_id, "OPEN",
                      request.created_at, request.limit_price, request.parent_order_id, request.strategy_run_id,
                      signal_px=request.signal_px, signal_ts=request.signal_ts, order_ts=datetime.now(UTC),
                      **order_px_fields(request.order_type, limit=request.limit_price, mid=request.mid, last=request.last))
        self._ledger.record_order(order)
        fill_price = self._next_event_fill_price(request, next_candle, assumptions)
        if fill_price is None:
            self._ledger.update_order_status(order.order_id, "EXPIRED")
            return replace(order, status="EXPIRED")
        volume = next_candle.volume or Decimal("0")
        capacity = int((volume * assumptions.max_volume_participation).to_integral_value(rounding=ROUND_DOWN))
        quantity = min(request.quantity, capacity)
        if quantity < 1:
            self._ledger.update_order_status(order.order_id, "MISSED")
            return replace(order, status="MISSED")
        fee = fill_price * Decimal(quantity) * assumptions.fee_rate
        fill = Fill(str(uuid.uuid4()), order.order_id, request.account_id, quantity, fill_price, fee,
                    datetime.fromtimestamp(next_candle.open_time, UTC))
        status = "FILLED" if quantity == request.quantity else "PARTIAL"
        self._ledger.record_fill(fill, order_status=status)
        return replace(order, status=status, filled_at=fill.filled_at)

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

    @staticmethod
    def _next_event_fill_price(request: PaperOrder, candle: Candle,
                               assumptions: ExecutionAssumptions) -> Decimal | None:
        if request.order_type == "MARKET":
            multiplier = (Decimal("1") + assumptions.slippage_rate
                          if request.side == "BUY" else Decimal("1") - assumptions.slippage_rate)
            return candle.open * multiplier
        if request.limit_price is not None and candle.low <= request.limit_price <= candle.high:
            return request.limit_price
        return None

    def reduce_position(self, position: Position, *, quantity: int, exit_price: Decimal, contract_size: Decimal,
                        when: datetime, run_id: str, parent_order_id: str | None = None) -> Order:
        if quantity < 1 or quantity > position.quantity:
            raise ValueError("paper reduce quantity is outside current position")
        side = "SELL" if position.side == "LONG" else "BUY"
        request = PaperOrder(position.account_id, position.contract_code, position.side, side, quantity, "MARKET", when,
                             f"paper-close-{run_id}-{position.position_id}-{quantity}", position_id=position.position_id,
                             parent_order_id=parent_order_id, strategy_run_id=run_id)
        candle = Candle(int(when.timestamp()), exit_price, exit_price, exit_price, exit_price)
        order = self.submit(request, candle)
        gross = (exit_price - position.entry_price) * contract_size * quantity if position.side == "LONG" else (position.entry_price - exit_price) * contract_size * quantity
        fee = exit_price * Decimal(quantity) * self._fee_rate
        accumulated = position.realized_pnl + gross - fee
        self._ledger.reduce_position(position.position_id, position.quantity - quantity, accumulated, when)
        return order
