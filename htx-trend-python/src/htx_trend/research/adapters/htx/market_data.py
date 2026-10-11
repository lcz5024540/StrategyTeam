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
        """获取单批 K 线数据"""
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
    
    def fetch_klines_paginated(
        self,
        contract_code: str,
        period: str,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
        max_pages: int = 10,
        page_size: int = 2000,
    ) -> list[Kline]:
        """分页获取 K 线数据，获取最大可用历史深度
        
        Args:
            contract_code: 合约代码
            period: 周期
            start_time: 起始时间（可选）
            end_time: 结束时间（可选）
            max_pages: 最大页数
            page_size: 每页大小
        
        Returns:
            所有 K 线数据（按时间升序）
        """
        all_klines: list[Kline] = []
        current_end = end_time or datetime.now(UTC)
        
        for page in range(max_pages):
            # 获取一批数据
            batch = self.fetch_klines(contract_code, period, size=page_size)
            
            if not batch:
                break
            
            # 筛选时间范围
            if start_time:
                batch = [k for k in batch if k.bar_time >= start_time]
            
            if end_time:
                batch = [k for k in batch if k.bar_time <= end_time]
            
            if not batch:
                break
            
            all_klines.extend(batch)
            
            # 检查是否已达到起始时间
            if start_time and batch[0].bar_time <= start_time:
                break
            
            # 更新结束时间，获取更早的数据
            current_end = batch[0].bar_time
            
            # 如果返回的数据量 < page_size，说明已经没有更多历史数据
            if len(batch) < page_size:
                break
            
            # 限速
            time.sleep(0.5)
        
        # 去重并排序
        seen = set()
        unique_klines = []
        for k in sorted(all_klines, key=lambda x: x.bar_time):
            if k.bar_time not in seen:
                seen.add(k.bar_time)
                unique_klines.append(k)
        
        return unique_klines
    
    def fetch_funding_history(
        self, contract_code: str, page_size: int = 50
    ) -> list[FundingRate]:
        """获取单页资金费率历史"""
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
    
    def fetch_funding_history_paginated(
        self,
        contract_code: str,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
        max_pages: int = 20,
        page_size: int = 50,
    ) -> list[FundingRate]:
        """分页获取资金费率历史，获取最大可用深度
        
        Args:
            contract_code: 合约代码
            start_time: 起始时间（可选）
            end_time: 结束时间（可选）
            max_pages: 最大页数
            page_size: 每页大小
        
        Returns:
            所有资金费率记录（按时间升序）
        """
        all_rates: list[FundingRate] = []
        
        for page in range(max_pages):
            batch = self.fetch_funding_history(contract_code, page_size)
            
            if not batch:
                break
            
            # 筛选时间范围
            if start_time:
                batch = [r for r in batch if r.funding_time >= start_time]
            
            if end_time:
                batch = [r for r in batch if r.funding_time <= end_time]
            
            if not batch:
                break
            
            all_rates.extend(batch)
            
            # 检查是否已达到起始时间
            if start_time and batch[-1].funding_time <= start_time:
                break
            
            # 如果返回的数据量 < page_size，说明已经没有更多历史数据
            if len(batch) < page_size:
                break
            
            # 限速
            time.sleep(0.5)
        
        # 去重并排序
        seen = set()
        unique_rates = []
        for r in sorted(all_rates, key=lambda x: x.funding_time):
            if r.funding_time not in seen:
                seen.add(r.funding_time)
                unique_rates.append(r)
        
        return unique_rates
    
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
