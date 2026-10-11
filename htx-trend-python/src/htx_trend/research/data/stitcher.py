"""数据拼接器 - 在可配置 stitch point 处切换 Binance → HTX

规则：
- stitch point 之前只用 Binance；从 stitch point 起只用 HTX。
- 默认 BTC/ETH = 2020-11-01 00:00 UTC。
- non_comparable 只标在拼接点前后各 N 根（默认 30），以及含拼接点的日线。
- 重叠期 HTX vs Binance 偏差写入质量审计，不因此丢信号 bar。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

import numpy as np
import pandas as pd

from ..features.daily_resample import (
    DEFAULT_DAILY_CUT,
    daily_open_for,
    daily_resample_manifest_fields,
    detect_daily_open_hour,
    resample_4h_to_daily,
)
from ..ops.logging import LogComponent, LogLevel, log_event
from .quality import (
    MANUAL_REVIEW_THRESHOLD,
    STITCH_WINDOW_BARS,
    overlap_audit_rows,
    stitch_point_for,
)


def overlap_deviation_distribution(
    deviations: pd.Series, threshold: float
) -> dict[str, Any]:
    if deviations is None or len(deviations) == 0:
        return {
            "count": 0,
            "median": None,
            "p95": None,
            "max": None,
            "bars_over_threshold": 0,
            "threshold": threshold,
        }
    values = deviations.astype(float)
    return {
        "count": int(len(values)),
        "median": float(values.median()),
        "p95": float(np.percentile(values, 95)),
        "max": float(values.max()),
        "bars_over_threshold": int((values > threshold).sum()),
        "threshold": threshold,
    }


def _as_utc_index(df: pd.DataFrame) -> pd.DataFrame:
    work = df.copy()
    work["bar_time"] = pd.to_datetime(work["bar_time"], utc=True)
    return work


def mark_stitch_window(
    combined: pd.DataFrame,
    stitch_point: datetime,
    window_bars: int = STITCH_WINDOW_BARS,
) -> pd.DataFrame:
    work = combined.copy()
    if "non_comparable" not in work.columns:
        work["non_comparable"] = False
    if work.empty:
        return work
    work = work.sort_values("bar_time").reset_index(drop=True)
    stitch = pd.Timestamp(stitch_point)
    if stitch.tzinfo is None:
        stitch = stitch.tz_localize("UTC")
    else:
        stitch = stitch.tz_convert("UTC")
    at_or_after = work.index[work["bar_time"] >= stitch]
    if len(at_or_after) == 0:
        pos = len(work) - 1
    else:
        pos = int(at_or_after[0])
    start = max(0, pos - int(window_bars))
    end = min(len(work) - 1, pos + int(window_bars))
    work.loc[start:end, "non_comparable"] = True
    return work


def mark_stitch_day(
    daily: pd.DataFrame,
    stitch_point: datetime,
    daily_cut: str = DEFAULT_DAILY_CUT,
) -> pd.DataFrame:
    work = daily.copy()
    if work.empty:
        return work
    if "non_comparable" not in work.columns:
        work["non_comparable"] = False
    stitch_open = daily_open_for(stitch_point, daily_cut)
    times = pd.to_datetime(work["bar_time"], utc=True)
    work.loc[times == pd.Timestamp(stitch_open), "non_comparable"] = True
    return work


class DataStitcher:
    def __init__(
        self,
        close_deviation_threshold: float,
        manual_review_threshold: float = MANUAL_REVIEW_THRESHOLD,
        window_bars: int = STITCH_WINDOW_BARS,
    ):
        if close_deviation_threshold is None:
            raise ValueError("close_deviation_threshold is required")
        self.close_deviation_threshold = close_deviation_threshold
        self.manual_review_threshold = manual_review_threshold
        self.window_bars = window_bars

    def stitch_klines(
        self,
        htx_df: pd.DataFrame,
        binance_df: pd.DataFrame,
        stitch_point: datetime | None = None,
        symbol: str = "",
        window_bars: int | None = None,
        mark_daily_stitch_day: bool = False,
        daily_cut: str = DEFAULT_DAILY_CUT,
    ) -> tuple[pd.DataFrame, dict]:
        stitch = stitch_point_for(symbol, override=stitch_point)
        window = self.window_bars if window_bars is None else int(window_bars)

        if htx_df.empty and binance_df.empty:
            return pd.DataFrame(), {
                "htx_bars": 0,
                "binance_bars": 0,
                "binance_backfill_bars": 0,
                "stitch_point": stitch.isoformat(),
                "non_comparable_window_bars": window,
                "overlap_audit": [],
                "overlap_deviation": overlap_deviation_distribution(
                    pd.Series(dtype=float), self.close_deviation_threshold
                ),
            }

        htx = _as_utc_index(htx_df) if not htx_df.empty else pd.DataFrame()
        bn = _as_utc_index(binance_df) if not binance_df.empty else pd.DataFrame()
        stitch_ts = pd.Timestamp(stitch)

        overlap = pd.DataFrame()
        deviation = pd.Series(dtype=float)
        if not htx.empty and not bn.empty:
            overlap = pd.merge(
                htx[["bar_time", "close"]],
                bn[["bar_time", "close"]],
                on="bar_time",
                suffixes=("_htx", "_binance"),
            )
            if not overlap.empty:
                overlap["deviation"] = abs(
                    overlap["close_htx"] - overlap["close_binance"]
                ) / overlap["close_htx"]
                deviation = overlap["deviation"]
                over = overlap[overlap["deviation"] > self.close_deviation_threshold]
                if not over.empty:
                    log_event(
                        LogComponent.DATA,
                        f"Overlap deviations >{self.close_deviation_threshold*100:.1f}% "
                        f"recorded in audit (not dropped): {len(over)}",
                        level=LogLevel.WARNING,
                        max_deviation=float(overlap["deviation"].max()),
                    )

        audit = overlap_audit_rows(
            overlap,
            symbol,
            audit_threshold=self.close_deviation_threshold,
            review_threshold=self.manual_review_threshold,
        )

        before = bn[bn["bar_time"] < stitch_ts].copy() if not bn.empty else pd.DataFrame()
        after = htx[htx["bar_time"] >= stitch_ts].copy() if not htx.empty else pd.DataFrame()

        if not before.empty:
            before["source"] = "binance"
            before["is_backfill"] = True
            before["external"] = True
        if not after.empty:
            if "source" not in after.columns:
                after["source"] = "htx"
            after["is_backfill"] = False
            after["external"] = False

        if before.empty and after.empty:
            combined = pd.DataFrame()
        else:
            combined = pd.concat([before, after], ignore_index=True)
        if combined.empty:
            stats = {
                "htx_bars": len(after),
                "binance_bars": len(bn),
                "binance_backfill_bars": len(before),
                "overlap_bars": len(overlap),
                "non_comparable_bars": 0,
                "total_bars": 0,
                "stitch_point": stitch.isoformat(),
                "non_comparable_window_bars": window,
                "overlap_audit": audit,
                "manual_review_flags": sum(1 for row in audit if row["flag_manual_review"]),
                "overlap_deviation": overlap_deviation_distribution(
                    deviation, self.close_deviation_threshold
                ),
            }
            return combined, stats

        combined["non_comparable"] = False
        if "bad_tick" not in combined.columns:
            combined["bad_tick"] = False
        combined = mark_stitch_window(combined, stitch, window)
        if mark_daily_stitch_day:
            combined = mark_stitch_day(combined, stitch, daily_cut)
        combined = combined.sort_values("bar_time").reset_index(drop=True)

        stats = {
            "htx_bars": len(after),
            "binance_bars": len(bn),
            "binance_backfill_bars": len(before),
            "overlap_bars": len(overlap),
            "non_comparable_bars": int(combined["non_comparable"].sum()),
            "total_bars": len(combined),
            "stitch_point": stitch.isoformat(),
            "non_comparable_window_bars": window,
            "overlap_audit": audit,
            "manual_review_flags": sum(1 for row in audit if row["flag_manual_review"]),
            "overlap_deviation": overlap_deviation_distribution(
                deviation, self.close_deviation_threshold
            ),
        }
        log_event(LogComponent.DATA, "Data stitching complete", **{
            k: v for k, v in stats.items() if k not in {"overlap_deviation", "overlap_audit"}
        })
        return combined, stats

    def stitch_daily_from_4h(
        self,
        htx_4h: pd.DataFrame,
        binance_4h: pd.DataFrame,
        htx_1d: pd.DataFrame | None = None,
        daily_cut: str = DEFAULT_DAILY_CUT,
        now=None,
        stitch_point: datetime | None = None,
        symbol: str = "",
        window_bars: int | None = None,
    ) -> tuple[pd.DataFrame, dict]:
        """Canonical 1d from 4h. Raw HTX 1d is stored but not used for signals."""
        detected_hour = None
        if htx_1d is not None and not htx_1d.empty:
            detected_hour = detect_daily_open_hour(htx_1d["bar_time"])

        tagged_htx = htx_4h.copy()
        if not tagged_htx.empty and "source" not in tagged_htx.columns:
            tagged_htx["source"] = "htx"
        tagged_bn = binance_4h.copy()
        if not tagged_bn.empty and "source" not in tagged_bn.columns:
            tagged_bn["source"] = "binance"

        htx_daily = resample_4h_to_daily(tagged_htx, daily_cut=daily_cut, now=now)
        bn_daily = resample_4h_to_daily(tagged_bn, daily_cut=daily_cut, now=now)
        combined, stats = self.stitch_klines(
            htx_daily.signal_bars,
            bn_daily.signal_bars,
            stitch_point=stitch_point,
            symbol=symbol,
            window_bars=window_bars,
            mark_daily_stitch_day=True,
            daily_cut=daily_cut,
        )
        stats.update(daily_resample_manifest_fields(
            daily_cut=daily_cut,
            htx_detected_daily_open_hour=detected_hour,
            extra={
                "htx_resample": htx_daily.stats,
                "binance_resample": bn_daily.stats,
            },
        ))
        return combined, stats
