"""Funding as-of: a bar may only use settlements at or before its close."""

from __future__ import annotations

import pandas as pd

from typing import Any

from ..data.immutable_storage import _parse_interval_seconds


def attach_funding_as_of(
    bars: pd.DataFrame,
    funding: pd.DataFrame,
    interval: str,
    *,
    allow_missing_funding: bool = False,
) -> pd.DataFrame:
    """Attach the latest funding_rate with funding_time <= bar close.

    Future settlements after the bar close are never used.
    Bars before the first settlement get funding_rate=0 and pre_first_funding=True.
    """
    if bars is None or bars.empty:
        return bars.copy() if bars is not None else pd.DataFrame()

    out = bars.copy()
    out["bar_time"] = pd.to_datetime(out["bar_time"], utc=True)
    interval_seconds = _parse_interval_seconds(interval)
    out["_bar_close"] = out["bar_time"] + pd.Timedelta(seconds=interval_seconds)

    if funding is None or funding.empty:
        if not allow_missing_funding:
            raise ValueError(
                "funding history is empty; collect funding or set "
                "allow_missing_funding=true"
            )
        out["funding_rate"] = 0.0
        out["pre_first_funding"] = True
        out["funding_missing"] = True
        return out.drop(columns=["_bar_close"])

    fund = funding.copy()
    time_col = "funding_time" if "funding_time" in fund.columns else "bar_time"
    fund[time_col] = pd.to_datetime(fund[time_col], utc=True)
    if fund["funding_rate"].isna().any():
        raise ValueError("funding history contains NaN funding_rate")
    fund = fund.sort_values(time_col)
    out = out.sort_values("_bar_close")

    merged = pd.merge_asof(
        out,
        fund[[time_col, "funding_rate"]].rename(
            columns={time_col: "_funding_time", "funding_rate": "funding_rate"}
        ),
        left_on="_bar_close",
        right_on="_funding_time",
        direction="backward",
        allow_exact_matches=True,
    )
    if "_funding_time" in merged.columns:
        future = merged["_funding_time"] > merged["_bar_close"]
        if future.any():
            raise AssertionError("Funding as-of leaked a settlement after bar close")
        merged["pre_first_funding"] = merged["_funding_time"].isna()
        merged = merged.drop(columns=["_funding_time"])
    else:
        merged["pre_first_funding"] = merged["funding_rate"].isna()
    merged.loc[merged["pre_first_funding"] == True, "funding_rate"] = 0.0
    if merged["funding_rate"].isna().any():
        raise ValueError("funding_rate contains NaN after as-of attach")
    merged["funding_missing"] = False
    return merged.drop(columns=["_bar_close"]).reset_index(drop=True)


def _utc_ts(value) -> pd.Timestamp:
    stamp = pd.Timestamp(value)
    if stamp.tzinfo is None:
        return stamp.tz_localize("UTC")
    return stamp.tz_convert("UTC")


def bar_close_time(bar_open, interval: str) -> pd.Timestamp:
    return _utc_ts(bar_open) + pd.Timedelta(seconds=_parse_interval_seconds(interval))


def settlements_in_bar(
    funding: pd.DataFrame,
    bar_open,
    bar_close,
) -> pd.DataFrame:
    """Return history rows whose own settlement timestamp is in (bar_open, bar_close].

    Times come only from the frozen funding records. No 8h (or any) schedule is assumed.
    """
    columns = ["funding_time", "funding_rate"]
    if funding is None or funding.empty:
        return pd.DataFrame(columns=columns)
    fund = funding.copy()
    time_col = "funding_time" if "funding_time" in fund.columns else "bar_time"
    times = pd.to_datetime(fund[time_col], utc=True)
    open_ts = _utc_ts(bar_open)
    close_ts = _utc_ts(bar_close)
    mask = (times > open_ts) & (times <= close_ts)
    rows = fund.loc[mask].copy()
    if rows.empty:
        return pd.DataFrame(columns=columns)
    rows["funding_time"] = pd.to_datetime(rows[time_col], utc=True)
    return rows[columns].sort_values("funding_time").reset_index(drop=True)


def apply_bar_settlements(
    broker: Any,
    symbol: str,
    bar: dict,
    interval: str,
    funding: pd.DataFrame,
) -> None:
    """Charge each settlement in this bar if a position is open after open fills."""
    bar_open = bar["bar_time"]
    bar_close = bar_close_time(bar_open, interval)
    mark = float(bar["close"])
    for item in settlements_in_bar(funding, bar_open, bar_close).itertuples(index=False):
        broker.apply_funding(
            symbol,
            float(item.funding_rate),
            mark_price=mark,
            settlement_time=item.funding_time,
        )
