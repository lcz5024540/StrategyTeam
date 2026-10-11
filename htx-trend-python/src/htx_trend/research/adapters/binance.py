"""Binance USDT-M 公开市场数据客户端 - 仅用于历史数据回填"""

from __future__ import annotations

import time
from datetime import UTC, datetime

import httpx

from .htx.market_data import Kline


class BinanceMarketDataClient:
    def __init__(self, base_url: str = "https://fapi.binance.com"):
        self.base_url = base_url.rstrip("/")
        self.client = httpx.Client(timeout=httpx.Timeout(15.0, connect=5.0))
    
    def fetch_klines(
        self,
        symbol: str,
        interval: str,
        start_time: int | None = None,
        end_time: int | None = None,
        limit: int = 1500,
        max_retries: int = 3,
    ) -> list[Kline]:
        for attempt in range(max_retries):
            try:
                params = {
                    "symbol": symbol,
                    "interval": interval,
                    "limit": limit,
                }
                
                if start_time:
                    params["startTime"] = start_time
                if end_time:
                    params["endTime"] = end_time
                
                response = self.client.get(
                    f"{self.base_url}/fapi/v1/klines", params=params
                )
                response.raise_for_status()
                
                raw_data = response.json()
                first_seen = datetime.now(UTC)
                
                klines = []
                for item in raw_data:
                    klines.append(
                        Kline(
                            bar_time=datetime.fromtimestamp(item[0] / 1000, UTC),
                            open=float(item[1]),
                            high=float(item[2]),
                            low=float(item[3]),
                            close=float(item[4]),
                            volume=float(item[5]),
                            amount=float(item[7]),
                            source="binance",
                            first_seen_at=first_seen,
                        )
                    )
                
                return klines
            
            except Exception as e:
                if attempt == max_retries - 1:
                    raise
                time.sleep(2 ** attempt)
        
        return []
    
    def close(self) -> None:
        self.client.close()
    
    def __enter__(self) -> BinanceMarketDataClient:
        return self
    
    def __exit__(self, *_: object) -> None:
        self.close()
