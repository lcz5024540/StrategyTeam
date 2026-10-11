"""数据拼接器 - 合并 HTX 和 Binance 数据并检查偏差"""

from __future__ import annotations

import pandas as pd

from ..ops.logging import LogComponent, LogLevel, log_event


class DataStitcher:
    def __init__(self, close_deviation_threshold: float = 0.003):
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
            return binance_df, {"htx_bars": 0, "binance_bars": len(binance_df)}
        
        if binance_df.empty:
            return htx_df, {"htx_bars": len(htx_df), "binance_bars": 0}
        
        htx_df = htx_df.copy()
        binance_df = binance_df.copy()
        
        htx_df["bar_time"] = pd.to_datetime(htx_df["bar_time"])
        binance_df["bar_time"] = pd.to_datetime(binance_df["bar_time"])
        
        htx_start = htx_df["bar_time"].min()
        binance_backfill = binance_df[binance_df["bar_time"] < htx_start].copy()
        
        overlap = pd.merge(
            htx_df[["bar_time", "close"]],
            binance_df[["bar_time", "close"]],
            on="bar_time",
            suffixes=("_htx", "_binance"),
        )
        
        if not overlap.empty:
            overlap["deviation"] = abs(
                overlap["close_htx"] - overlap["close_binance"]
            ) / overlap["close_htx"]
            
            non_comparable = overlap[
                overlap["deviation"] > self.close_deviation_threshold
            ]
            
            if not non_comparable.empty:
                log_event(
                    LogComponent.DATA,
                    f"Found {len(non_comparable)} bars with close price deviation >{self.close_deviation_threshold*100:.1f}%",
                    level=LogLevel.WARNING,
                    non_comparable_count=len(non_comparable),
                    max_deviation=float(overlap["deviation"].max()),
                )
                
                for _, row in non_comparable.iterrows():
                    log_event(
                        LogComponent.DATA,
                        f"Non-comparable bar flagged",
                        level=LogLevel.WARNING,
                        bar_time=str(row["bar_time"]),
                        htx_close=float(row["close_htx"]),
                        binance_close=float(row["close_binance"]),
                        deviation_pct=float(row["deviation"] * 100),
                    )
        
        if not binance_backfill.empty:
            binance_backfill["source"] = "binance"
            binance_backfill["is_backfill"] = True
        
        htx_df["is_backfill"] = False
        
        combined = pd.concat([binance_backfill, htx_df], ignore_index=True)
        combined = combined.sort_values("bar_time").reset_index(drop=True)
        
        stats = {
            "htx_bars": len(htx_df),
            "binance_backfill_bars": len(binance_backfill),
            "overlap_bars": len(overlap),
            "non_comparable_bars": len(non_comparable) if not overlap.empty else 0,
            "total_bars": len(combined),
        }
        
        log_event(LogComponent.DATA, "Data stitching complete", **stats)
        
        return combined, stats
