"""Versioned daily-bar construction from aligned 4h bars.

HTX native 1d opens at 16:00 UTC; Binance 1d opens at 00:00 UTC; 4h is
aligned. Canonical 1d used for signals is resampled from 4h.

Rules (daily_resample v1.0.0):
1. A day needs exactly 6 closed 4h bars. Incomplete days are marked,
   excluded from signals, and never fill-forwarded. The current
   unfinished day never enters frozen data.
2. A day that mixes Binance and HTX 4h bars is non_comparable; volumes
   are not summed.
3. daily_cut is configurable: UTC00 (default) or UTC16.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

import pandas as pd

DailyCut = Literal["UTC00", "UTC16"]

DAILY_CUTS: dict[str, int] = {"UTC00": 0, "UTC16": 16}
DEFAULT_DAILY_CUT: DailyCut = "UTC00"
COMPLETE_BARS_PER_DAY = 6
SOURCE_INTERVAL_SECONDS = 4 * 3600

DAILY_RESAMPLE_FEATURE: dict[str, Any] = {
    "name": "daily_resample",
    "version": "1.0.0",
    "method": "ohlcv_from_4h",
    "source_interval": "4hour",
    "complete_bars_per_day": COMPLETE_BARS_PER_DAY,
}

BINANCE_DAILY_OPEN_HOUR = 0
HTX_NATIVE_DAILY_OPEN_HOUR = 16


def normalize_daily_cut(daily_cut: str | None) -> str:
    cut = DEFAULT_DAILY_CUT if daily_cut is None else str(daily_cut)
    if cut not in DAILY_CUTS:
        raise ValueError(f"Unknown daily_cut {cut!r}. Must be one of {sorted(DAILY_CUTS)}")
    return cut


def detect_daily_open_hour(bar_times: pd.Series) -> int:
    hours = pd.to_datetime(bar_times, utc=True).dt.hour
    if hours.empty:
        raise ValueError("Cannot detect daily open hour from empty bar_times")
    return int(hours.mode().iloc[0])


def daily_open_for(ts: datetime | pd.Timestamp, daily_cut: str) -> pd.Timestamp:
    cut = normalize_daily_cut(daily_cut)
    open_hour = DAILY_CUTS[cut]
    value = pd.Timestamp(ts)
    if value.tzinfo is None:
        value = value.tz_localize("UTC")
    else:
        value = value.tz_convert("UTC")
    candidate = value.normalize() + pd.Timedelta(hours=open_hour)
    if value < candidate:
        candidate -= pd.Timedelta(days=1)
    return candidate


def _as_utc(value: datetime | pd.Timestamp) -> datetime:
    if isinstance(value, pd.Timestamp):
        value = value.to_pydatetime()
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _is_closed_4h(bar_time: datetime | pd.Timestamp, now: datetime) -> bool:
    close = _as_utc(bar_time) + timedelta(seconds=SOURCE_INTERVAL_SECONDS)
    return close <= _as_utc(now)


@dataclass
class DailyResampleResult:
    signal_bars: pd.DataFrame
    excluded: pd.DataFrame
    stats: dict[str, Any] = field(default_factory=dict)


def resample_4h_to_daily(
    df_4h: pd.DataFrame,
    daily_cut: str = DEFAULT_DAILY_CUT,
    now: datetime | None = None,
) -> DailyResampleResult:
    """Resample closed 4h bars to daily with explicit cut and completeness rules."""
    cut = normalize_daily_cut(daily_cut)
    check_now = now or datetime.now(timezone.utc)
    empty_cols = [
        "bar_time",
        "open",
        "high",
        "low",
        "close",
        "volume",
        "source",
        "daily_cut",
        "daily_resample_version",
        "incomplete",
        "mixed_source",
        "non_comparable",
        "unfinished",
    ]
    empty = pd.DataFrame(columns=empty_cols)

    if df_4h is None or df_4h.empty:
        return DailyResampleResult(empty.copy(), empty.copy(), _stats(cut, 0, 0, 0, 0))

    work = df_4h.copy()
    work["bar_time"] = pd.to_datetime(work["bar_time"], utc=True)
    work = work.sort_values("bar_time")
    work = work[work["bar_time"].map(lambda ts: _is_closed_4h(ts, check_now))]
    if work.empty:
        return DailyResampleResult(empty.copy(), empty.copy(), _stats(cut, 0, 0, 0, 0))

    work["daily_open"] = work["bar_time"].map(lambda ts: daily_open_for(ts, cut))
    if "source" not in work.columns:
        work["source"] = "htx"

    records: list[dict[str, Any]] = []
    excluded_records: list[dict[str, Any]] = []
    incomplete = 0
    mixed = 0
    unfinished = 0

    for daily_open, group in work.groupby("daily_open", sort=True):
        daily_close = pd.Timestamp(daily_open) + pd.Timedelta(days=1)
        is_unfinished = _as_utc(daily_close) > _as_utc(check_now)
        n_bars = len(group)
        sources = {str(item) for item in group["source"].tolist()}
        is_mixed = len(sources) > 1
        is_incomplete = n_bars != COMPLETE_BARS_PER_DAY

        base = {
            "bar_time": pd.Timestamp(daily_open),
            "open": float(group["open"].iloc[0]),
            "high": float(group["high"].max()),
            "low": float(group["low"].min()),
            "close": float(group["close"].iloc[-1]),
            "source": "mixed" if is_mixed else next(iter(sources)),
            "daily_cut": cut,
            "daily_resample_version": DAILY_RESAMPLE_FEATURE["version"],
            "incomplete": is_incomplete,
            "mixed_source": is_mixed,
            "non_comparable": is_mixed,
            "unfinished": is_unfinished,
        }
        if "amount" in group.columns and not is_mixed:
            base["amount"] = float(group["amount"].sum())
        elif "amount" in group.columns:
            base["amount"] = float("nan")

        if is_mixed:
            mixed += 1
            base["volume"] = float("nan")
        else:
            base["volume"] = float(group["volume"].sum())

        if is_unfinished:
            unfinished += 1
            excluded_records.append(base)
            continue
        if is_incomplete:
            incomplete += 1
            excluded_records.append(base)
            continue
        records.append(base)

    signal = pd.DataFrame(records) if records else empty.copy()
    excluded = pd.DataFrame(excluded_records) if excluded_records else empty.copy()
    stats = _stats(cut, len(signal), incomplete, mixed, unfinished)
    return DailyResampleResult(signal.reset_index(drop=True), excluded.reset_index(drop=True), stats)


def resample_4h_to_daily_utc00(
    df_4h: pd.DataFrame, now: datetime | None = None
) -> pd.DataFrame:
    return resample_4h_to_daily(df_4h, daily_cut="UTC00", now=now).signal_bars


def daily_resample_manifest_fields(
    *,
    daily_cut: str = DEFAULT_DAILY_CUT,
    htx_detected_daily_open_hour: int | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    cut = normalize_daily_cut(daily_cut)
    payload = {
        "daily_cut": cut,
        "daily_resample": {**DAILY_RESAMPLE_FEATURE, "daily_cut": cut},
        "htx_detected_daily_open_hour": htx_detected_daily_open_hour,
        "htx_native_1d_used_for_signals": False,
    }
    if extra:
        payload.update(extra)
    return payload


def _stats(cut: str, signal: int, incomplete: int, mixed: int, unfinished: int) -> dict[str, Any]:
    return {
        "daily_cut": cut,
        "daily_resample": {**DAILY_RESAMPLE_FEATURE, "daily_cut": cut},
        "signal_days": signal,
        "incomplete_days": incomplete,
        "mixed_source_days": mixed,
        "unfinished_days_excluded": unfinished,
        "fill_forward": False,
    }
