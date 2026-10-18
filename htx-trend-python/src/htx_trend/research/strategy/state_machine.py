"""策略状态机 - FLAT → ENTRY_PENDING → OPEN → EXIT_PENDING → FLAT"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Literal


class StrategyState(str, Enum):
    FLAT = "FLAT"
    ENTRY_PENDING = "ENTRY_PENDING"
    OPEN = "OPEN"
    EXIT_PENDING = "EXIT_PENDING"


@dataclass
class Position:
    symbol: str
    side: Literal["LONG", "SHORT"]
    size: float
    entry_price: float
    entry_time: datetime
    stop_loss: float
    leverage: float
    state: StrategyState = StrategyState.OPEN
    exit_price: float | None = None
    exit_time: datetime | None = None
    exit_reason: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


class StateMachine:
    def __init__(self):
        self.positions: dict[str, Position] = {}
        self.state: dict[str, StrategyState] = {}
    
    def get_state(self, symbol: str) -> StrategyState:
        return self.state.get(symbol, StrategyState.FLAT)
    
    def get_position(self, symbol: str) -> Position | None:
        return self.positions.get(symbol)
    
    def signal_entry(
        self,
        symbol: str,
        side: Literal["LONG", "SHORT"],
        size: float,
        entry_price: float,
        entry_time: datetime,
        stop_loss: float,
        leverage: float,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        if self.get_state(symbol) != StrategyState.FLAT:
            return
        
        self.state[symbol] = StrategyState.ENTRY_PENDING
        
        position = Position(
            symbol=symbol,
            side=side,
            size=size,
            entry_price=entry_price,
            entry_time=entry_time,
            stop_loss=stop_loss,
            leverage=leverage,
            state=StrategyState.ENTRY_PENDING,
            metadata=metadata or {},
        )
        
        self.positions[symbol] = position
    
    def confirm_entry(self, symbol: str, actual_entry_price: float | None = None) -> None:
        position = self.get_position(symbol)
        if position is None or position.state != StrategyState.ENTRY_PENDING:
            return
        
        if actual_entry_price is not None:
            position.entry_price = actual_entry_price
        
        position.state = StrategyState.OPEN
        self.state[symbol] = StrategyState.OPEN
    
    def signal_exit(self, symbol: str, exit_reason: str) -> None:
        position = self.get_position(symbol)
        if position is None or position.state != StrategyState.OPEN:
            return
        
        position.state = StrategyState.EXIT_PENDING
        position.exit_reason = exit_reason
        self.state[symbol] = StrategyState.EXIT_PENDING
    
    def confirm_exit(
        self, symbol: str, exit_price: float, exit_time: datetime
    ) -> Position | None:
        position = self.get_position(symbol)
        if position is None or position.state != StrategyState.EXIT_PENDING:
            return None
        
        position.exit_price = exit_price
        position.exit_time = exit_time
        
        self.state[symbol] = StrategyState.FLAT
        
        closed_position = self.positions.pop(symbol)
        
        return closed_position
    
    def get_open_positions(self) -> list[Position]:
        return [
            p
            for p in self.positions.values()
            if p.state in {StrategyState.OPEN, StrategyState.EXIT_PENDING}
        ]
