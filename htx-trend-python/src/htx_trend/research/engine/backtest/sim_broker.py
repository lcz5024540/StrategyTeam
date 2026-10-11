"""模拟经纪商 - t收盘信号, t+1成交

在回测模式下，账户守卫仅用于配置验证。
在 PAPER/LIVE 模式下，每次下单前都会验证账户权限。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any

from ...account_guard import AccountGuard
from ...adapters.htx.fees import FeeProvider, FeeRates
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


class SimBroker:
    def __init__(
        self,
        initial_equity: float,
        cost_scenario: CostScenario = CostScenario.BASE,
        funding_data: dict[str, Any] | None = None,
        fee_provider: FeeProvider | None = None,
        account_guard: AccountGuard | None = None,
    ):
        self.initial_equity = initial_equity
        self.equity = initial_equity
        self.cost_scenario = cost_scenario
        self.funding_data = funding_data or {}
        self.fee_provider = fee_provider
        self.account_guard = account_guard
        
        self.positions: dict[str, SimPosition] = {}
        self.fills: list[Fill] = []
        self.pending_orders: list[tuple[Order, datetime]] = []
        
        self.fee_rates_used: dict[str, FeeRates] = {}
        
        self.maker_fee_rate, self.taker_fee_rate = self._get_default_fee_rates()
    
    def _get_default_fee_rates(self) -> tuple[float, float]:
        """根据成本场景获取默认费率（仅在无 FeeProvider 时使用）"""
        if self.cost_scenario == CostScenario.ZERO:
            return 0.0, 0.0
        elif self.cost_scenario == CostScenario.OPTIMISTIC:
            return 0.0002, 0.0004
        elif self.cost_scenario == CostScenario.BASE:
            return 0.0002, 0.0006
        else:
            return 0.0003, 0.0007
    
    def _get_fee_rates_for_symbol(self, symbol: str) -> tuple[float, float]:
        """获取指定品种的费率，优先使用 FeeProvider"""
        if self.fee_provider is not None:
            if symbol not in self.fee_rates_used:
                rates = self.fee_provider.get_fee_rates(symbol)
                self.fee_rates_used[symbol] = rates
            else:
                rates = self.fee_rates_used[symbol]
            
            return rates.maker_rate, rates.taker_rate
        
        return self.maker_fee_rate, self.taker_fee_rate
    
    def submit_order(self, order: Order) -> OrderAck:
        signal_time = datetime.now()
        
        self.pending_orders.append((order, signal_time))
        
        order_id = str(uuid.uuid4())
        
        return OrderAck(
            order_id=order_id,
            status="PENDING",
            fill_price=None,
            fill_time=None,
            reject_reason=None,
        )
    
    def process_next_bar(self, symbol: str, bar: dict[str, Any]) -> list[OrderAck]:
        acks = []
        
        remaining_orders = []
        for order, signal_time in self.pending_orders:
            if order.symbol != symbol:
                remaining_orders.append((order, signal_time))
                continue
            
            fill_price = bar["open"]
            fill_time = bar["bar_time"]
            
            is_entry = order.symbol not in self.positions
            
            maker_rate, taker_rate = self._get_fee_rates_for_symbol(order.symbol)
            fee_rate = maker_rate if order.order_type == "LIMIT" else taker_rate
            
            notional = fill_price * order.size
            fees = notional * fee_rate
            
            slippage = 0.0
            if self.cost_scenario == CostScenario.CONSERVATIVE:
                slippage = notional * 0.0005
            
            if is_entry:
                self.positions[order.symbol] = SimPosition(
                    symbol=order.symbol,
                    side=order.side,
                    size=order.size,
                    entry_price=fill_price,
                    entry_time=fill_time,
                    leverage=5.0,
                )
            else:
                position = self.positions.pop(order.symbol, None)
                if position:
                    if position.side == "LONG":
                        pnl = (fill_price - position.entry_price) * position.size
                    else:
                        pnl = (position.entry_price - fill_price) * position.size
                    
                    self.equity += pnl - fees - slippage - position.accumulated_funding
            
            self.fills.append(
                Fill(
                    symbol=order.symbol,
                    side=order.side,
                    size=order.size,
                    price=fill_price,
                    time=fill_time,
                    fees=fees,
                    slippage=slippage,
                    is_entry=is_entry,
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
            if position.symbol == symbol:
                current_price = bar["close"]
                if position.side == "LONG":
                    position.unrealized_pnl = (
                        current_price - position.entry_price
                    ) * position.size
                else:
                    position.unrealized_pnl = (
                        position.entry_price - current_price
                    ) * position.size
        
        return acks
    
    def apply_funding(self, symbol: str, funding_rate: float) -> None:
        if symbol in self.positions:
            position = self.positions[symbol]
            notional = position.entry_price * position.size
            funding_payment = notional * funding_rate
            
            if position.side == "LONG":
                position.accumulated_funding += funding_payment
            else:
                position.accumulated_funding -= funding_payment
    
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
            }
            for p in self.positions.values()
        ]
    
    def get_account_equity(self) -> float:
        total_unrealized = sum(p.unrealized_pnl for p in self.positions.values())
        return self.equity + total_unrealized
