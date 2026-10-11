"""模拟经纪商 - t收盘信号, t+1成交

仓位 size 一律为合约张数。名义价值 / 手续费 / 盈亏 / 资金费均使用
notional = contracts * contract_size * price。
入场为市价开盘（taker）；止损成交价不利滑点。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Any

from ...account_guard import AccountGuard
from ...adapters.htx.fees import FeeProvider, FeeRates
from ..contract_value import coin_notional, require_contract_size
from ..interfaces import Broker, Order, OrderAck


class CostScenario(str, Enum):
    ZERO = "zero"
    OPTIMISTIC = "optimistic"
    BASE = "base"
    CONSERVATIVE = "conservative"


@dataclass
class SimPosition:
    symbol: str
    side: str
    size: float
    entry_price: float
    entry_time: datetime
    leverage: float
    contract_size: float
    unrealized_pnl: float = 0.0
    accumulated_funding: float = 0.0


@dataclass
class Fill:
    symbol: str
    side: str
    size: float
    price: float
    time: datetime
    fees: float
    slippage: float
    is_entry: bool
    contract_size: float = 1.0
    notional: float = 0.0
    funding: float = 0.0
    order_id: str | None = None
    client_order_id: str | None = None
    position_id: str | None = None


class SimBroker:
    def __init__(
        self,
        initial_equity: float,
        cost_scenario: CostScenario = CostScenario.BASE,
        funding_data: dict[str, Any] | None = None,
        fee_provider: FeeProvider | None = None,
        account_guard: AccountGuard | None = None,
        contract_sizes: dict[str, float] | None = None,
    ):
        if not contract_sizes:
            raise ValueError("contract_sizes is required; refuse to price 1 contract as 1 coin")
        self.contract_sizes = {
            symbol: require_contract_size(size, symbol)
            for symbol, size in contract_sizes.items()
        }
        self.initial_equity = initial_equity
        self.equity = initial_equity
        self.cost_scenario = cost_scenario
        self.funding_data = funding_data or {}
        self.fee_provider = fee_provider
        self.account_guard = account_guard

        self.positions: dict[str, SimPosition] = {}
        self.fills: list[Fill] = []
        self.funding_events: list[dict[str, Any]] = []
        self.pending_orders: list[tuple[Order, datetime]] = []

        self.fee_rates_used: dict[str, FeeRates] = {}

        self.maker_fee_rate, self.taker_fee_rate = self._get_default_fee_rates()

    def contract_size_for(self, symbol: str) -> float:
        if symbol not in self.contract_sizes:
            raise ValueError(f"contract_size missing for {symbol}")
        return self.contract_sizes[symbol]

    def _get_default_fee_rates(self) -> tuple[float, float]:
        if self.cost_scenario == CostScenario.ZERO:
            return 0.0, 0.0
        if self.cost_scenario == CostScenario.OPTIMISTIC:
            return 0.0002, 0.0004
        if self.cost_scenario == CostScenario.BASE:
            return 0.0002, 0.0006
        return 0.0003, 0.0007

    def _get_fee_rates_for_symbol(self, symbol: str) -> tuple[float, float]:
        if self.fee_provider is not None:
            if symbol not in self.fee_rates_used:
                rates = self.fee_provider.get_fee_rates(symbol)
                self.fee_rates_used[symbol] = rates
            else:
                rates = self.fee_rates_used[symbol]
            return rates.maker_rate, rates.taker_rate
        return self.maker_fee_rate, self.taker_fee_rate

    def submit_order(self, order: Order) -> OrderAck:
        self.pending_orders.append((order, datetime.now()))
        return OrderAck(
            order_id=str(uuid.uuid4()),
            status="PENDING",
            fill_price=None,
            fill_time=None,
            reject_reason=None,
        )

    def process_next_bar(
        self,
        symbol: str,
        bar: dict[str, Any],
        *,
        allow_entry_fills: bool = True,
        allow_exit_fills: bool = True,
    ) -> list[OrderAck]:
        acks = []
        remaining_orders = []
        for order, signal_time in self.pending_orders:
            if order.symbol != symbol:
                remaining_orders.append((order, signal_time))
                continue
            is_exit = bool(order.reduce_only or order.is_stop)
            if is_exit and not allow_exit_fills:
                remaining_orders.append((order, signal_time))
                continue
            if (not is_exit) and not allow_entry_fills:
                remaining_orders.append((order, signal_time))
                continue

            contract_size = self.contract_size_for(order.symbol)
            fill_price = float(bar["open"])
            fill_time = bar["bar_time"]
            if order.is_stop and order.stop_price is not None:
                fill_price = _adverse_stop_fill(
                    order.side, order.stop_price, bar["open"], order.stop_slippage or 0.0
                )
            elif order.order_type == "MARKET" and self.cost_scenario != CostScenario.ZERO:
                slip = order.stop_slippage or 0.0
                fill_price = _adverse_entry_fill(order.side, fill_price, slip)

            is_entry = order.symbol not in self.positions
            maker_rate, taker_rate = self._get_fee_rates_for_symbol(order.symbol)
            fee_rate = taker_rate if order.order_type == "MARKET" else maker_rate

            notional = coin_notional(fill_price, order.size, contract_size, order.symbol)
            fees = notional * fee_rate
            slippage_cost = notional * 0.0005 if self.cost_scenario == CostScenario.CONSERVATIVE else 0.0

            funding = 0.0
            if is_entry:
                self.positions[order.symbol] = SimPosition(
                    symbol=order.symbol,
                    side=order.side,
                    size=order.size,
                    entry_price=fill_price,
                    entry_time=fill_time,
                    leverage=5.0,
                    contract_size=contract_size,
                )
                self.equity -= fees + slippage_cost
            else:
                position = self.positions.pop(order.symbol, None)
                if position:
                    funding = position.accumulated_funding
                    direction = 1.0 if position.side == "LONG" else -1.0
                    pnl = coin_notional(
                        fill_price - position.entry_price,
                        position.size,
                        position.contract_size,
                        position.symbol,
                    ) * direction
                    self.equity += pnl - fees - slippage_cost - funding

            self.fills.append(
                Fill(
                    symbol=order.symbol,
                    side=order.side,
                    size=order.size,
                    price=fill_price,
                    time=fill_time,
                    fees=fees,
                    slippage=slippage_cost,
                    is_entry=is_entry,
                    contract_size=contract_size,
                    notional=notional,
                    funding=funding,
                    order_id=getattr(order, "order_id", None),
                    client_order_id=order.client_order_id,
                    position_id=order.client_order_id,
                )
            )
            acks.append(
                OrderAck(
                    order_id=str(uuid.uuid4()),
                    status="FILLED",
                    fill_price=fill_price,
                    fill_time=fill_time,
                    reject_reason=None,
                )
            )

        self.pending_orders = remaining_orders

        for position in self.positions.values():
            if position.symbol != symbol:
                continue
            current_price = float(bar["close"])
            direction = 1.0 if position.side == "LONG" else -1.0
            position.unrealized_pnl = coin_notional(
                current_price - position.entry_price,
                position.size,
                position.contract_size,
                position.symbol,
            ) * direction
        return acks

    def apply_funding(
        self,
        symbol: str,
        funding_rate: float,
        mark_price: float | None = None,
        settlement_time: Any = None,
    ) -> None:
        if symbol not in self.positions:
            return
        position = self.positions[symbol]
        price = float(mark_price) if mark_price is not None else position.entry_price
        notional = coin_notional(price, position.size, position.contract_size, symbol)
        payment = notional * funding_rate
        if position.side == "LONG":
            position.accumulated_funding += payment
        else:
            position.accumulated_funding -= payment
        self.funding_events.append(
            {
                "symbol": symbol,
                "funding_rate": float(funding_rate),
                "payment": payment,
                "notional": notional,
                "mark_price": price,
                "settlement_time": settlement_time,
            }
        )

    def get_positions(self) -> list[dict[str, Any]]:
        return [
            {
                "symbol": p.symbol,
                "side": p.side,
                "size": p.size,
                "entry_price": p.entry_price,
                "entry_time": p.entry_time,
                "leverage": p.leverage,
                "unrealized_pnl": p.unrealized_pnl,
                "accumulated_funding": p.accumulated_funding,
                "contract_size": p.contract_size,
            }
            for p in self.positions.values()
        ]

    def get_account_equity(self) -> float:
        return self.equity + sum(
            p.unrealized_pnl - float(p.accumulated_funding or 0.0)
            for p in self.positions.values()
        )

    def close(self) -> None:
        if getattr(self, "_closed", False):
            return
        self._closed = True
        closer = getattr(self.fee_provider, "close", None)
        if callable(closer):
            closer()


def _adverse_stop_fill(
    close_side: str, stop_price: float, next_open: float, slippage: float
) -> float:
    if close_side == "SHORT":
        return min(stop_price, next_open) - slippage
    return max(stop_price, next_open) + slippage


def _adverse_entry_fill(side: str, open_price: float, slippage: float) -> float:
    if side == "LONG":
        return open_price + slippage
    return open_price - slippage
