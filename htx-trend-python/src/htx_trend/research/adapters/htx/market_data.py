"""HTX 公开市场数据客户端 - 复用现有 HtxV5Client"""

from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from ....htx_v5 import HtxV5Client


@dataclass(frozen=True)
class Kline:
    bar_time: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float
    amount: float
    source: str = "htx"
    first_seen_at: datetime | None = None


@dataclass(frozen=True)
class FundingRate:
    symbol: str
    funding_time: datetime
    funding_rate: float
    realized_rate: float | None = None


@dataclass(frozen=True)
class ContractSpec:
    contract_code: str
    symbol: str
    contract_size: float
    price_tick: float
    min_order_size: int
    create_date: int


class HtxMarketDataClient:
    def __init__(self, base_url: str = "https://api.hbdm.com"):
        self.client = HtxV5Client(base_url=base_url)
    
    def fetch_klines(
        self,
        contract_code: str,
        period: str,
        size: int = 2000,
        max_retries: int = 3,
    ) -> list[Kline]:
        for attempt in range(max_retries):
            try:
                raw_data = self.client.history_kline(contract_code, period, size)
                first_seen = datetime.now(UTC)
                
                klines = []
                for item in raw_data:
                    klines.append(
                        Kline(
                            bar_time=datetime.fromtimestamp(item["id"], UTC),
                            open=float(item["open"]),
                            high=float(item["high"]),
                            low=float(item["low"]),
                            close=float(item["close"]),
                            volume=float(item["vol"]),
                            amount=float(item["amount"]),
                            source="htx",
                            first_seen_at=first_seen,
                        )
                    )
                
                return sorted(klines, key=lambda k: k.bar_time)
            
            except Exception as e:
                if attempt == max_retries - 1:
                    raise
                time.sleep(2 ** attempt)
        
        return []
    
    def fetch_funding_history(
        self, contract_code: str, page_size: int = 50
    ) -> list[FundingRate]:
        try:
            raw_data = self.client.funding_history(contract_code, page_size)
            
            rates = []
            for item in raw_data:
                rates.append(
                    FundingRate(
                        symbol=item["symbol"],
                        funding_time=datetime.fromtimestamp(
                            item["funding_time"] / 1000, UTC
                        ),
                        funding_rate=float(item["funding_rate"]),
                        realized_rate=float(item.get("realized_rate", item["funding_rate"])),
                    )
                )
            
            return sorted(rates, key=lambda r: r.funding_time)
        
        except Exception:
            return []
    
    def fetch_contract_info(self, contract_code: str) -> ContractSpec | None:
        try:
            raw_data = self.client.contract_info(contract_code)
            
            if not raw_data:
                return None
            
            item = raw_data[0]
            return ContractSpec(
                contract_code=item["contract_code"],
                symbol=item["symbol"],
                contract_size=float(item["contract_size"]),
                price_tick=float(item["price_tick"]),
                min_order_size=int(item.get("min_order_size", 1)),
                create_date=int(item["create_date"]),
            )
        
        except Exception:
            return None
    
    def close(self) -> None:
        self.client.close()
    
    def __enter__(self) -> HtxMarketDataClient:
        return self
    
    def __exit__(self, *_: object) -> None:
        self.close()
