"""数据拼接器 - 合并 HTX 和 Binance 数据并检查偏差

1d 不直接拼接原生日线：HTX 1d 开在 16:00 UTC，Binance 1d 开在 00:00 UTC。
规范 1d 一律由已对齐的 4h 按可配置 daily_cut（UTC00 默认 / UTC16）重采样
（daily_resample v1.0.0）。原生 HTX 1d 只保存，不进入信号。各源分开重采样，
禁止把 Binance 与 HTX 的 4h 混进同一根日线。
"""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from ..features.daily_resample import (
    DEFAULT_DAILY_CUT,
    daily_resample_manifest_fields,
    detect_daily_open_hour,
    resample_4h_to_daily,
)
from ..ops.logging import LogComponent, LogLevel, log_event


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


class DataStitcher:
    def __init__(self, close_deviation_threshold: float):
        if close_deviation_threshold is None:
            raise ValueError("close_deviation_threshold is required")
        self.close_deviation_threshold = close_deviation_threshold

    def stitch_klines(
        self, htx_df: pd.DataFrame, binance_df: pd.DataFrame
    ) -> tuple[pd.DataFrame, dict]:
        if htx_df.empty:
            log_event(
                LogComponent.DATA,
                "HTX data empty, using Binance only",
                level=LogLevel.WARNING,
            )
            return binance_df, {
                "htx_bars": 0,
                "binance_bars": len(binance_df),
                "binance_backfill_bars": len(binance_df),
                "overlap_deviation": overlap_deviation_distribution(
                    pd.Series(dtype=float), self.close_deviation_threshold
                ),
            }

        if binance_df.empty:
            return htx_df, {
                "htx_bars": len(htx_df),
                "binance_bars": 0,
                "binance_backfill_bars": 0,
                "overlap_deviation": overlap_deviation_distribution(
                    pd.Series(dtype=float), self.close_deviation_threshold
                ),
            }

        htx_df = htx_df.copy()
        binance_df = binance_df.copy()

        htx_df["bar_time"] = pd.to_datetime(htx_df["bar_time"], utc=True)
        binance_df["bar_time"] = pd.to_datetime(binance_df["bar_time"], utc=True)

        htx_start = htx_df["bar_time"].min()
        binance_backfill = binance_df[binance_df["bar_time"] < htx_start].copy()

        htx_df["non_comparable"] = False
        htx_df["external"] = False

        overlap = pd.merge(
            htx_df[["bar_time", "close"]],
            binance_df[["bar_time", "close"]],
            on="bar_time",
            suffixes=("_htx", "_binance"),
        )

        non_comparable_bar_times: list = []
        deviation = pd.Series(dtype=float)
        if not overlap.empty:
            overlap["deviation"] = abs(
                overlap["close_htx"] - overlap["close_binance"]
            ) / overlap["close_htx"]
            deviation = overlap["deviation"]
            non_comparable = overlap[overlap["deviation"] > self.close_deviation_threshold]
            if not non_comparable.empty:
                non_comparable_bar_times = non_comparable["bar_time"].tolist()
                log_event(
                    LogComponent.DATA,
                    f"Found {len(non_comparable)} bars with close price deviation "
                    f">{self.close_deviation_threshold*100:.1f}%",
                    level=LogLevel.WARNING,
                    non_comparable_count=len(non_comparable),
                    max_deviation=float(overlap["deviation"].max()),
                )
                htx_df.loc[
                    htx_df["bar_time"].isin(non_comparable_bar_times), "non_comparable"
                ] = True

        if not binance_backfill.empty:
            binance_backfill["source"] = "binance"
            binance_backfill["is_backfill"] = True
            binance_backfill["external"] = True
            binance_backfill["non_comparable"] = False

        htx_df["is_backfill"] = False
        combined = pd.concat([binance_backfill, htx_df], ignore_index=True)
        combined = combined.sort_values("bar_time").reset_index(drop=True)

        stats = {
            "htx_bars": len(htx_df),
            "binance_backfill_bars": len(binance_backfill),
            "overlap_bars": len(overlap),
            "non_comparable_bars": len(non_comparable_bar_times),
            "total_bars": len(combined),
            "overlap_deviation": overlap_deviation_distribution(
                deviation, self.close_deviation_threshold
            ),
        }
        log_event(LogComponent.DATA, "Data stitching complete", **{
            k: v for k, v in stats.items() if k != "overlap_deviation"
        })
        return combined, stats

    def stitch_daily_from_4h(
        self,
        htx_4h: pd.DataFrame,
        binance_4h: pd.DataFrame,
        htx_1d: pd.DataFrame | None = None,
        daily_cut: str = DEFAULT_DAILY_CUT,
        now=None,
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

        # Resample each source separately, then stitch. Never mix 4h sources
        # inside one daily bar (mixed days are marked if a caller concatenates).
        htx_daily = resample_4h_to_daily(tagged_htx, daily_cut=daily_cut, now=now)
        bn_daily = resample_4h_to_daily(tagged_bn, daily_cut=daily_cut, now=now)
        combined, stats = self.stitch_klines(htx_daily.signal_bars, bn_daily.signal_bars)
        stats.update(daily_resample_manifest_fields(
            daily_cut=daily_cut,
            htx_detected_daily_open_hour=detected_hour,
            extra={
                "htx_resample": htx_daily.stats,
                "binance_resample": bn_daily.stats,
            },
        ))
        return combined, stats
