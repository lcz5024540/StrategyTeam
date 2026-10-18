"""市场状态(regime)标签计算"""

from __future__ import annotations

import pandas as pd


class RegimeLabeler:
    def __init__(
        self,
        adx_trend_threshold: float = 25.0,
        adx_range_threshold: float = 20.0,
        vol_high_percentile: float = 0.8,
        vol_low_percentile: float = 0.2,
    ):
        self.adx_trend_threshold = adx_trend_threshold
        self.adx_range_threshold = adx_range_threshold
        self.vol_high_percentile = vol_high_percentile
        self.vol_low_percentile = vol_low_percentile
    
    def label_trend_state(self, df: pd.DataFrame) -> pd.Series:
        adx = df["adx14"]
        ema_align = df["ema_align"]
        
        trend_state = pd.Series("TRANSITION", index=df.index)
        
        trend_up = (adx >= self.adx_trend_threshold) & (ema_align == 1)
        trend_down = (adx >= self.adx_trend_threshold) & (ema_align == -1)
        range_market = adx < self.adx_range_threshold
        
        trend_state[trend_up] = "TREND_UP"
        trend_state[trend_down] = "TREND_DOWN"
        trend_state[range_market] = "RANGE"
        
        return trend_state
    
    def label_vol_state(self, df: pd.DataFrame) -> pd.Series:
        atr_rank = df["atr_pct_rank_90"]
        
        vol_state = pd.Series("MID", index=df.index)
        
        vol_state[atr_rank >= self.vol_high_percentile] = "HIGH"
        vol_state[atr_rank <= self.vol_low_percentile] = "LOW"
        
        return vol_state
    
    def label_regime(self, df: pd.DataFrame) -> pd.DataFrame:
        df = df.copy()
        
        df["trend_state"] = self.label_trend_state(df)
        df["vol_state"] = self.label_vol_state(df)
        df["regime"] = df["trend_state"] + "_" + df["vol_state"]
        
        return df


def compute_regime_labels(df: pd.DataFrame) -> pd.DataFrame:
    labeler = RegimeLabeler()
    return labeler.label_regime(df)
