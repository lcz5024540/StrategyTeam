"""HTX 公开市场数据客户端 - 复用现有 HtxV5Client

官方文档:
- https://huobiapi.github.io/docs/spot/v1/en/#get-klines-candles
- 永续: /linear-swap-ex/market/history/kline
  参数: contract_code, period, size (max 2000) 或 from/to（互斥）
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx

from ....htx_v5 import HtxApiError, HtxV5Client


class HtxAPIError(Exception):
    """HTX API 错误（含 err-code / err-msg）"""

    def __init__(
        self,
        message: str,
        err_code: str | None = None,
        err_msg: str | None = None,
    ):
        super().__init__(message)
        self.err_code = err_code
        self.err_msg = err_msg


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
    MAX_KLINE_SIZE = 2000

    def __init__(self, base_url: str = "https://api.hbdm.com"):
        self.client = HtxV5Client(base_url=base_url)

    def _request_kline_envelope(
        self,
        contract_code: str,
        period: str,
        size: int | None = None,
        from_timestamp: int | None = None,
        to_timestamp: int | None = None,
    ) -> dict[str, Any]:
        """请求 K 线信封。size 与 from/to 互斥；size 上限 2000。"""
        capped = None if size is None else min(int(size), self.MAX_KLINE_SIZE)
        try:
            if from_timestamp is not None and to_timestamp is not None:
                envelope = self.client.history_kline_envelope(
                    contract_code,
                    period,
                    start=int(from_timestamp),
                    end=int(to_timestamp),
                )
            else:
                envelope = self.client.history_kline_envelope(
                    contract_code,
                    period,
                    capped if capped is not None else self.MAX_KLINE_SIZE,
                )
        except httpx.HTTPError as exc:
            raise HtxAPIError(f"Network error: {exc}") from exc
        except HtxApiError as exc:
            raise HtxAPIError(str(exc), err_code="unknown", err_msg=str(exc)) from exc

        if envelope.get("status") != "ok":
            err_code = str(envelope.get("err-code") or envelope.get("err_code") or "unknown")
            err_msg = str(envelope.get("err-msg") or envelope.get("err_msg") or "no message")
            raise HtxAPIError(
                f"HTX API error: {err_code} - {err_msg}",
                err_code=err_code,
                err_msg=err_msg,
            )
        return envelope

    def _parse_klines(self, raw_data: list[dict[str, Any]]) -> list[Kline]:
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
                    amount=float(item.get("amount", 0)),
                    source="htx",
                    first_seen_at=first_seen,
                )
            )
        return sorted(klines, key=lambda k: k.bar_time)

    def fetch_klines(
        self,
        contract_code: str,
        period: str,
        size: int = 2000,
        max_retries: int = 3,
        from_timestamp: int | None = None,
        to_timestamp: int | None = None,
    ) -> list[Kline]:
        """获取单批 K 线，支持官方 from/to 参数。"""
        last_error: Exception | None = None
        for attempt in range(max_retries):
            try:
                envelope = self._request_kline_envelope(
                    contract_code,
                    period,
                    size=size,
                    from_timestamp=from_timestamp,
                    to_timestamp=to_timestamp,
                )
                raw_data = envelope.get("data") or []
                if not isinstance(raw_data, list):
                    raise HtxAPIError("HTX response data is not a list")
                return self._parse_klines(raw_data)
            except HtxAPIError:
                raise
            except Exception as exc:
                last_error = exc
                if attempt == max_retries - 1:
                    raise HtxAPIError(f"Network error: {exc}") from exc
                time.sleep(2 ** attempt)
        if last_error:
            raise HtxAPIError(f"Network error: {last_error}") from last_error
        return []

    def fetch_klines_paginated(
        self,
        contract_code: str,
        period: str,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
        max_pages: int = 10,
        page_size: int = 2000,
        from_timestamp: int | None = None,
        to_timestamp: int | None = None,
    ) -> list[Kline]:
        """分页抓取 K 线，向官方接口传递 from/to。"""
        page_size = min(int(page_size), self.MAX_KLINE_SIZE)
        start_ts = from_timestamp
        end_ts = to_timestamp
        if start_ts is None and start_time is not None:
            start_ts = int(start_time.timestamp())
        if end_ts is None and end_time is not None:
            end_ts = int(end_time.timestamp())

        if start_ts is not None and end_ts is not None:
            envelope = self._request_kline_envelope(
                contract_code,
                period,
                from_timestamp=start_ts,
                to_timestamp=end_ts,
            )
            raw_data = envelope.get("data") or []
            if not isinstance(raw_data, list):
                raise HtxAPIError("HTX response data is not a list")
            return self._parse_klines(raw_data)

        all_klines: list[Kline] = []
        for _page in range(max_pages):
            batch = self.fetch_klines(contract_code, period, size=page_size)
            if not batch:
                break
            if start_time:
                batch = [k for k in batch if k.bar_time >= start_time]
            if end_time:
                batch = [k for k in batch if k.bar_time <= end_time]
            if not batch:
                break
            all_klines.extend(batch)
            if start_time and batch[0].bar_time <= start_time:
                break
            if len(batch) < page_size:
                break
            time.sleep(0.5)

        seen: set[datetime] = set()
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
