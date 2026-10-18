"""核心接口定义 - Clock, Feed, Broker"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal, Protocol

import pandas as pd


class Clock(Protocol):
    def now(self) -> datetime:
        ...


class Feed(Protocol):
    def closed_bars(self, symbol: str, timeframe: str, n: int) -> pd.DataFrame:
        ...


@dataclass
class Order:
    symbol: str
    side: Literal["LONG", "SHORT"]
    size: float
    order_type: Literal["MARKET", "LIMIT"]
    price: float | None = None
    client_order_id: str | None = None
    reduce_only: bool = False
    is_stop: bool = False
    stop_price: float | None = None
    stop_slippage: float | None = None


@dataclass
class OrderAck:
    order_id: str
    status: Literal["FILLED", "REJECTED", "PENDING"]
    fill_price: float | None = None
    fill_time: datetime | None = None
    reject_reason: str | None = None


class Broker(Protocol):
    def submit_order(self, order: Order) -> OrderAck:
        ...
    
    def get_positions(self) -> list[dict[str, Any]]:
        ...
    
    def get_account_equity(self) -> float:
        ...
