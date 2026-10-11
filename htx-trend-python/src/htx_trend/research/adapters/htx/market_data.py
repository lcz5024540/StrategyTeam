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


KLINE_PERIOD_SECONDS = {
    "1min": 60,
    "5min": 300,
    "15min": 900,
    "30min": 1800,
    "60min": 3600,
    "4hour": 4 * 3600,
    "1day": 24 * 3600,
    "1mon": 30 * 24 * 3600,
}

# HTX rejects from/to windows spanning more than 2000 bars
# (400d of 4h fails; inclusive endpoints can count as 2001).
SAFE_KLINE_BARS = 1990
DEFAULT_EMPTY_PAGES_TO_STOP = 2


def kline_window_seconds(period: str, bars: int = SAFE_KLINE_BARS) -> int:
    if period not in KLINE_PERIOD_SECONDS:
        raise ValueError(f"Unsupported HTX kline period: {period}")
    if bars <= 0 or bars > 2000:
        raise ValueError("kline window bars must be in (0, 2000]")
    return int(bars) * KLINE_PERIOD_SECONDS[period]


def parse_create_date(create_date: int | None) -> datetime | None:
    if create_date is None:
        return None
    value = int(create_date)
    if value <= 0:
        return None
    if value >= 10**12:
        return datetime.fromtimestamp(value / 1000, UTC)
    if value >= 10**9:
        return datetime.fromtimestamp(value, UTC)
    text = f"{value:08d}"
    if len(text) == 8:
        return datetime(int(text[:4]), int(text[4:6]), int(text[6:8]), tzinfo=UTC)
    return None


class HtxMarketDataClient:
    MAX_KLINE_SIZE = 2000
    SAFE_KLINE_BARS = SAFE_KLINE_BARS

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
            err_code = getattr(exc, "err_code", None) or "unknown"
            err_msg = getattr(exc, "err_msg", None) or str(exc)
            raise HtxAPIError(
                f"HTX API error: {err_code} - {err_msg}",
                err_code=str(err_code),
                err_msg=str(err_msg),
            ) from exc

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
        page_size: int = SAFE_KLINE_BARS,
        from_timestamp: int | None = None,
        to_timestamp: int | None = None,
        empty_pages_to_stop: int = DEFAULT_EMPTY_PAGES_TO_STOP,
        listed_from: datetime | None = None,
        page_sleep: float = 0.2,
    ) -> list[Kline]:
        """向后分页。每个 from/to 窗口 <= 1990 根，避免 HTX invalid from to。"""
        if period not in KLINE_PERIOD_SECONDS:
            raise ValueError(f"Unsupported HTX kline period: {period}")
        page_size = min(int(page_size), SAFE_KLINE_BARS)
        period_sec = KLINE_PERIOD_SECONDS[period]
        start_ts = from_timestamp
        end_ts = to_timestamp
        if start_ts is None and start_time is not None:
            start_ts = int(start_time.timestamp())
        if end_ts is None and end_time is not None:
            end_ts = int(end_time.timestamp())
        if end_ts is None:
            end_ts = int(datetime.now(UTC).timestamp())

        listing_ts: int | None = None
        if listed_from is not None:
            listing_ts = int(listed_from.timestamp())
        else:
            spec = self.fetch_contract_info(contract_code)
            parsed = parse_create_date(getattr(spec, "create_date", None) if spec else None)
            if parsed is not None:
                listing_ts = int(parsed.timestamp())
        if listing_ts is not None:
            start_ts = listing_ts if start_ts is None else max(int(start_ts), listing_ts)

        all_klines: list[Kline] = []
        cursor_end = int(end_ts)
        empty_streak = 0
        for page in range(max_pages):
            if listing_ts is not None and cursor_end <= listing_ts:
                break
            window = page_size * period_sec
            cursor_start = cursor_end - window
            if start_ts is not None:
                cursor_start = max(int(start_ts), cursor_start)
            if cursor_start >= cursor_end:
                break
            batch = self.fetch_klines(
                contract_code,
                period,
                from_timestamp=cursor_start,
                to_timestamp=cursor_end,
            )
            if not batch:
                empty_streak += 1
                if empty_streak >= max(1, int(empty_pages_to_stop)):
                    break
                cursor_end = cursor_start
                if page_sleep and page + 1 < max_pages:
                    time.sleep(page_sleep)
                continue
            empty_streak = 0
            all_klines.extend(batch)
            earliest_ts = int(min(k.bar_time for k in batch).timestamp())
            if start_ts is not None and earliest_ts <= int(start_ts):
                break
            cursor_end = cursor_start
            if page_sleep and page + 1 < max_pages:
                time.sleep(page_sleep)

        seen: set[datetime] = set()
        unique_klines = []
        for k in sorted(all_klines, key=lambda x: x.bar_time):
            if start_ts is not None and int(k.bar_time.timestamp()) < int(start_ts):
                continue
            if int(k.bar_time.timestamp()) > int(end_ts):
                continue
            if k.bar_time not in seen:
                seen.add(k.bar_time)
                unique_klines.append(k)
        return unique_klines
    
    def fetch_funding_history(
        self,
        contract_code: str,
        page_size: int = 50,
        page_index: int = 1,
    ) -> list[FundingRate]:
        """获取单页资金费率历史"""
        try:
            raw_data = self.client.funding_history(
                contract_code, page_size=page_size, page_index=page_index
            )

            rates = []
            for item in raw_data:
                funding_time = item["funding_time"]
                if isinstance(funding_time, (int, float)) and funding_time > 10**12:
                    funding_time = funding_time / 1000
                rates.append(
                    FundingRate(
                        symbol=item["symbol"],
                        funding_time=datetime.fromtimestamp(float(funding_time), UTC),
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
        empty_pages_to_stop: int = DEFAULT_EMPTY_PAGES_TO_STOP,
        page_sleep: float = 0.5,
    ) -> list[FundingRate]:
        """按页向后取资金费率；连续空页停止，边界去重。"""
        all_rates: list[FundingRate] = []
        empty_streak = 0

        for page in range(max_pages):
            batch = self.fetch_funding_history(
                contract_code, page_size=page_size, page_index=page + 1
            )
            if start_time:
                batch = [row for row in batch if row.funding_time >= start_time]
            if end_time:
                batch = [row for row in batch if row.funding_time <= end_time]
            if not batch:
                empty_streak += 1
                if empty_streak >= max(1, int(empty_pages_to_stop)):
                    break
                if page_sleep and page + 1 < max_pages:
                    time.sleep(page_sleep)
                continue
            empty_streak = 0
            all_rates.extend(batch)
            if page_sleep and page + 1 < max_pages:
                time.sleep(page_sleep)

        seen: set[datetime] = set()
        unique_rates = []
        for row in sorted(all_rates, key=lambda item: item.funding_time):
            if row.funding_time not in seen:
                seen.add(row.funding_time)
                unique_rates.append(row)
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
