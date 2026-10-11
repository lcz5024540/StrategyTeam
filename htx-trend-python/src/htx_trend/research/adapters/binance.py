"""Binance USDT-M historical klines via data.binance.vision.

The fapi.binance.com REST API is geo-blocked (HTTP 451) on the research
server. Monthly (then current-month daily) futures/um archives are used
instead. Spot paths are rejected.
"""

from __future__ import annotations

import hashlib
import io
import zipfile
from datetime import UTC, datetime, timedelta
from typing import Iterable

import httpx

from .htx.market_data import Kline

VISION_BASE = "https://data.binance.vision/data/futures/um"
ALLOWED_SYMBOLS = frozenset({"BTCUSDT", "ETHUSDT"})
ALLOWED_INTERVALS = frozenset({"4h", "1d"})
FORBIDDEN_PATH_MARKERS = ("/data/spot", "/spot/monthly/", "/spot/daily/")


class BinanceChecksumError(ValueError):
    """Raised when a vision archive does not match its CHECKSUM file."""


class BinanceVisionClient:
    def __init__(self, base_url: str = VISION_BASE, client: httpx.Client | None = None):
        self.base_url = base_url.rstrip("/")
        self._owns_client = client is None
        self.client = client or httpx.Client(timeout=httpx.Timeout(60.0, connect=10.0))
        self._assert_not_spot(self.base_url)

    def _assert_not_spot(self, url: str) -> None:
        lowered = url.lower()
        for marker in FORBIDDEN_PATH_MARKERS:
            if marker in lowered:
                raise ValueError(f"Spot kline archives are forbidden: {url}")

    def download_klines(
        self,
        symbol: str,
        interval: str,
        start_date: datetime,
        end_date: datetime,
    ) -> list[Kline]:
        if symbol not in ALLOWED_SYMBOLS:
            raise ValueError(f"Unsupported Binance vision symbol: {symbol}")
        if interval not in ALLOWED_INTERVALS:
            raise ValueError(f"Unsupported Binance vision interval: {interval}")

        start = _as_utc(start_date)
        end = _as_utc(end_date)
        now = datetime.now(UTC)
        klines: list[Kline] = []
        first_seen = datetime.now(UTC)

        for year, month in _iter_months(start, end):
            month_start = datetime(year, month, 1, tzinfo=UTC)
            if (year, month) < (now.year, now.month):
                archive = self._download_monthly(symbol, interval, year, month)
                klines.extend(self._parse_archive(archive, first_seen))
            else:
                day_cursor = max(start, month_start)
                day_end = min(end, now)
                while day_cursor.date() <= day_end.date():
                    archive = self._download_daily(
                        symbol, interval, day_cursor.year, day_cursor.month, day_cursor.day
                    )
                    if archive is not None:
                        klines.extend(self._parse_archive(archive, first_seen))
                    day_cursor += timedelta(days=1)

        unique: dict[datetime, Kline] = {}
        for item in klines:
            if start <= item.bar_time <= end:
                unique[item.bar_time] = item
        return [unique[key] for key in sorted(unique)]

    def _download_monthly(self, symbol: str, interval: str, year: int, month: int) -> bytes:
        name = f"{symbol}-{interval}-{year:04d}-{month:02d}.zip"
        url = f"{self.base_url}/monthly/klines/{symbol}/{interval}/{name}"
        checksum_url = f"{url}.CHECKSUM"
        return self._get_and_verify(url, checksum_url)

    def _download_daily(
        self, symbol: str, interval: str, year: int, month: int, day: int
    ) -> bytes | None:
        name = f"{symbol}-{interval}-{year:04d}-{month:02d}-{day:02d}.zip"
        url = f"{self.base_url}/daily/klines/{symbol}/{interval}/{name}"
        checksum_url = f"{url}.CHECKSUM"
        try:
            return self._get_and_verify(url, checksum_url)
        except httpx.HTTPStatusError as exc:
            if exc.response is not None and exc.response.status_code == 404:
                return None
            raise

    def _get_and_verify(self, url: str, checksum_url: str) -> bytes:
        self._assert_not_spot(url)
        self._assert_not_spot(checksum_url)
        zip_resp = self.client.get(url)
        zip_resp.raise_for_status()
        checksum_resp = self.client.get(checksum_url)
        checksum_resp.raise_for_status()
        expected = _parse_checksum(checksum_resp.text)
        actual = hashlib.sha256(zip_resp.content).hexdigest()
        if actual.lower() != expected.lower():
            raise BinanceChecksumError(
                f"CHECKSUM mismatch for {url}: expected {expected}, got {actual}"
            )
        return zip_resp.content

    def _parse_archive(self, payload: bytes, first_seen: datetime) -> list[Kline]:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            names = [n for n in archive.namelist() if n.endswith(".csv")]
            if not names:
                raise ValueError("Vision archive contains no CSV")
            raw = archive.read(names[0]).decode("utf-8")
        klines: list[Kline] = []
        for line in raw.splitlines():
            if not line.strip() or line.lower().startswith("open_time"):
                continue
            parts = line.split(",")
            if len(parts) < 6:
                continue
            klines.append(
                Kline(
                    bar_time=datetime.fromtimestamp(int(parts[0]) / 1000, UTC),
                    open=float(parts[1]),
                    high=float(parts[2]),
                    low=float(parts[3]),
                    close=float(parts[4]),
                    volume=float(parts[5]),
                    amount=float(parts[7]) if len(parts) > 7 else 0.0,
                    source="binance",
                    first_seen_at=first_seen,
                )
            )
        return klines

    def close(self) -> None:
        if self._owns_client:
            self.client.close()

    def __enter__(self) -> "BinanceVisionClient":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


# Historical name kept so existing imports keep working; implementation is vision-only.
BinanceMarketDataClient = BinanceVisionClient


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def _iter_months(start: datetime, end: datetime) -> Iterable[tuple[int, int]]:
    year, month = start.year, start.month
    while (year, month) <= (end.year, end.month):
        yield year, month
        month += 1
        if month == 13:
            month = 1
            year += 1


def _parse_checksum(text: str) -> str:
    line = next((item.strip() for item in text.splitlines() if item.strip()), "")
    if not line:
        raise BinanceChecksumError("Empty CHECKSUM file")
    if line.upper().startswith("SHA256") and "=" in line:
        return line.split("=", 1)[1].strip()
    return line.split()[0]
