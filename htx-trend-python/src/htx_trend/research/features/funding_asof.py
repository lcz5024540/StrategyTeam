"""Funding as-of: a bar may only use settlements at or before its close."""

from __future__ import annotations

import pandas as pd

from ..data.immutable_storage import _parse_interval_seconds


def attach_funding_as_of(
    bars: pd.DataFrame,
    funding: pd.DataFrame,
    interval: str,
) -> pd.DataFrame:
    """Attach the latest funding_rate with funding_time <= bar close.

    Future settlements after the bar close are never used.
    """
    if bars is None or bars.empty:
        return bars.copy() if bars is not None else pd.DataFrame()

    out = bars.copy()
    out["bar_time"] = pd.to_datetime(out["bar_time"], utc=True)
    interval_seconds = _parse_interval_seconds(interval)
    out["_bar_close"] = out["bar_time"] + pd.Timedelta(seconds=interval_seconds)

    if funding is None or funding.empty:
        out["funding_rate"] = pd.NA
        return out.drop(columns=["_bar_close"])

    fund = funding.copy()
    time_col = "funding_time" if "funding_time" in fund.columns else "bar_time"
    fund[time_col] = pd.to_datetime(fund[time_col], utc=True)
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
        merged = merged.drop(columns=["_funding_time"])
    return merged.drop(columns=["_bar_close"]).reset_index(drop=True)
