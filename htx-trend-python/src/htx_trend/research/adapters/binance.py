"""Binance USDT-M historical klines via data.binance.vision.

The fapi.binance.com REST API is geo-blocked (HTTP 451) on the research
server. Monthly (then current-month daily) futures/um archives are used
instead. Spot paths are rejected.
"""

from __future__ import annotations

import atexit
import hashlib
import io
import math
import zipfile
from datetime import UTC, datetime, timedelta
from typing import Iterable

import httpx

from .htx.market_data import FundingRate, Kline, datetime_from_epoch, require_research_timestamp

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
        self._closed = False
        self._assert_not_spot(self.base_url)
        if self._owns_client:
            atexit.register(self.close)

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

    def download_funding_history(
        self,
        symbol: str,
        start_date: datetime,
        end_date: datetime,
    ) -> list[FundingRate]:
        if symbol not in ALLOWED_SYMBOLS:
            raise ValueError(f"Unsupported Binance vision symbol: {symbol}")
        start = _as_utc(start_date)
        end = _as_utc(end_date)
        rates: list[FundingRate] = []
        for year, month in _iter_months(start, end):
            try:
                archive = self._download_monthly_funding(symbol, year, month)
            except httpx.HTTPStatusError as exc:
                if exc.response is not None and exc.response.status_code == 404:
                    continue
                raise
            rates.extend(self._parse_funding_archive(archive, symbol))

        unique: dict[datetime, FundingRate] = {}
        for item in rates:
            if start <= item.funding_time <= end:
                unique[item.funding_time] = item
        return [unique[key] for key in sorted(unique)]

    def _download_monthly(self, symbol: str, interval: str, year: int, month: int) -> bytes:
        name = f"{symbol}-{interval}-{year:04d}-{month:02d}.zip"
        url = f"{self.base_url}/monthly/klines/{symbol}/{interval}/{name}"
        checksum_url = f"{url}.CHECKSUM"
        return self._get_and_verify(url, checksum_url)

    def _download_monthly_funding(self, symbol: str, year: int, month: int) -> bytes:
        name = f"{symbol}-fundingRate-{year:04d}-{month:02d}.zip"
        url = f"{self.base_url}/monthly/fundingRate/{symbol}/{name}"
        checksum_url = f"{url}.CHECKSUM"
        return self._get_and_verify(url, checksum_url)

    def _parse_funding_archive(self, payload: bytes, symbol: str) -> list[FundingRate]:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            names = [n for n in archive.namelist() if n.endswith(".csv")]
            if not names:
                raise ValueError("Vision funding archive contains no CSV")
            raw = archive.read(names[0]).decode("utf-8")
        columns: list[str] | None = None
        rows: list[tuple[datetime, float, float | None]] = []
        for line in raw.splitlines():
            if not line.strip():
                continue
            parts = [item.strip() for item in line.split(",")]
            if not parts or not parts[0]:
                continue
            if _is_csv_header(parts[0]):
                columns = [item.lower() for item in parts]
                continue
            timestamp, rate, interval_hours = _funding_fields(parts, columns)
            funding_time = require_research_timestamp(
                datetime_from_epoch(_floor_epoch_seconds(timestamp), field="calc_time"),
                field="calc_time",
            )
            rows.append((funding_time, rate, interval_hours))
        rows.sort(key=lambda item: item[0])
        _assert_funding_intervals(rows)
        return [
            FundingRate(
                symbol=symbol,
                funding_time=funding_time,
                funding_rate=rate,
                realized_rate=rate,
                source="binance_external",
            )
            for funding_time, rate, _interval in rows
        ]

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
            if not line.strip():
                continue
            parts = line.split(",")
            if len(parts) < 6 or _is_csv_header(parts[0]):
                continue
            klines.append(
                Kline(
                    bar_time=require_research_timestamp(
                        datetime_from_epoch(
                            _floor_epoch_seconds(float(parts[0])),
                            field="open_time",
                        ),
                        field="open_time",
                    ),
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
        if getattr(self, "_closed", False):
            return
        self._closed = True
        if self._owns_client:
            try:
                self.client.close()
            except Exception:
                pass

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


def _is_csv_header(first: str) -> bool:
    try:
        float(first.strip())
    except ValueError:
        return True
    return False


def _floor_epoch_seconds(value: float) -> float:
    if value > 10**12:
        value = value / 1000.0
    return math.floor(value)


def _funding_fields(
    parts: list[str],
    columns: list[str] | None,
) -> tuple[float, float, float | None]:
    if columns:
        index = {name: i for i, name in enumerate(columns)}
        timestamp = float(parts[index.get("calc_time", 0)])
        if "last_funding_rate" in index:
            rate = float(parts[index["last_funding_rate"]])
        else:
            rate = float(parts[-1])
        interval = None
        if "funding_interval_hours" in index:
            interval = float(parts[index["funding_interval_hours"]])
        return timestamp, rate, interval
    return float(parts[0]), float(parts[-1]), None


def _assert_funding_intervals(rows: list[tuple[datetime, float, float | None]]) -> None:
    for previous, current in zip(rows, rows[1:]):
        interval = current[2] if current[2] is not None else previous[2]
        if interval is None:
            continue
        actual = (current[0] - previous[0]).total_seconds()
        expected = float(interval) * 3600.0
        if abs(actual - expected) > 1.0:
            raise ValueError(
                f"funding_interval_hours={interval} but gap is {actual:.0f}s"
            )


def _parse_checksum(text: str) -> str:
    line = next((item.strip() for item in text.splitlines() if item.strip()), "")
    if not line:
        raise BinanceChecksumError("Empty CHECKSUM file")
    if line.upper().startswith("SHA256") and "=" in line:
        return line.split("=", 1)[1].strip()
    return line.split()[0]
