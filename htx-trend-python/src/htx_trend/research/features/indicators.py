"""技术指标计算 - ATR, EMA, Donchian, ADX"""

from __future__ import annotations

import numpy as np
import pandas as pd


class FeatureCalculator:
    VERSION = "1.1.0"  # Updated: Wilder smoothing + index preservation
    
    @staticmethod
    def _wilder_smooth(series: pd.Series, period: int) -> pd.Series:
        """Wilder's smoothing (RMA - Running Moving Average)
        
        RMA[i] = (RMA[i-1] * (period - 1) + value[i]) / period
        
        This is equivalent to EWM with alpha = 1/period.
        Preserves the original series index.
        """
        return series.ewm(alpha=1.0/period, adjust=False).mean()
    
    @staticmethod
    def atr(df: pd.DataFrame, period: int = 14, method: str = "wilder") -> pd.Series:
        """Average True Range
        
        Args:
            df: DataFrame with high, low, close columns
            period: ATR period
            method: Smoothing method - "wilder" (default) or "ema"
        
        Returns:
            pd.Series with same index as input df
        """
        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        tr1 = high - low
        tr2 = abs(high - close.shift(1))
        tr3 = abs(low - close.shift(1))
        
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        if method == "wilder":
            atr = FeatureCalculator._wilder_smooth(tr, period)
        elif method == "ema":
            atr = tr.ewm(span=period, adjust=False).mean()
        else:
            raise ValueError(f"Unknown ATR method: {method}")
        
        # Index is preserved automatically by pandas operations
        return atr
    
    @staticmethod
    def ema(series: pd.Series, period: int) -> pd.Series:
        return series.ewm(span=period, adjust=False).mean()
    
    @staticmethod
    def donchian_high(series: pd.Series, period: int) -> pd.Series:
        """Donchian high channel - max of prior N bars (excludes current bar)
        
        Breakout condition: close[t] > donchian_high[t]
        Where donchian_high[t] = max(high[t-N:t]) (prior N bars, excluding bar t)
        """
        return series.shift(1).rolling(window=period).max()
    
    @staticmethod
    def donchian_low(series: pd.Series, period: int) -> pd.Series:
        """Donchian low channel - min of prior N bars (excludes current bar)
        
        Breakout condition: close[t] < donchian_low[t]
        Where donchian_low[t] = min(low[t-N:t]) (prior N bars, excluding bar t)
        """
        return series.shift(1).rolling(window=period).min()

    @staticmethod
    def donchian_high_excluding_bad_ticks(
        series: pd.Series, period: int, bad_mask: pd.Series
    ) -> pd.Series:
        return lift_valid_indicator(
            FeatureCalculator.donchian_high(series.loc[~bad_mask.astype(bool)], period),
            series.index,
        )

    @staticmethod
    def donchian_low_excluding_bad_ticks(
        series: pd.Series, period: int, bad_mask: pd.Series
    ) -> pd.Series:
        return lift_valid_indicator(
            FeatureCalculator.donchian_low(series.loc[~bad_mask.astype(bool)], period),
            series.index,
        )
    
    @staticmethod
    def adx(df: pd.DataFrame, period: int = 14, method: str = "wilder") -> pd.Series:
        """Average Directional Index (ADX)
        
        Args:
            df: DataFrame with high, low, close columns
            period: ADX period
            method: Smoothing method - "wilder" (default, traditional) or "ema"
        
        Returns:
            pd.Series with same index as input df
        """
        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        # Directional movement
        high_diff = high.diff()
        low_diff = -low.diff()
        
        # Keep as Series to preserve index
        plus_dm = pd.Series(
            np.where((high_diff > low_diff) & (high_diff > 0), high_diff, 0),
            index=df.index,
        )
        minus_dm = pd.Series(
            np.where((low_diff > high_diff) & (low_diff > 0), low_diff, 0),
            index=df.index,
        )
        
        # True Range
        tr1 = high - low
        tr2 = abs(high - close.shift(1))
        tr3 = abs(low - close.shift(1))
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        # Smoothing
        if method == "wilder":
            smoothed_tr = FeatureCalculator._wilder_smooth(tr, period)
            smoothed_plus_dm = FeatureCalculator._wilder_smooth(plus_dm, period)
            smoothed_minus_dm = FeatureCalculator._wilder_smooth(minus_dm, period)
        elif method == "ema":
            smoothed_tr = tr.ewm(span=period, adjust=False).mean()
            smoothed_plus_dm = plus_dm.ewm(span=period, adjust=False).mean()
            smoothed_minus_dm = minus_dm.ewm(span=period, adjust=False).mean()
        else:
            raise ValueError(f"Unknown ADX method: {method}")
        
        # Directional indicators
        plus_di = 100 * smoothed_plus_dm / smoothed_tr
        minus_di = 100 * smoothed_minus_dm / smoothed_tr
        
        # DX
        dx = 100 * abs(plus_di - minus_di) / (plus_di + minus_di)
        
        # ADX (smooth DX)
        if method == "wilder":
            adx = FeatureCalculator._wilder_smooth(dx, period)
        else:
            adx = dx.ewm(span=period, adjust=False).mean()
        
        # Index is preserved throughout
        return adx
    
    @staticmethod
    def percentile_rank(series: pd.Series, window: int = 90) -> pd.Series:
        def rank_pct(x):
            if len(x) < 2:
                return np.nan
            return (x < x.iloc[-1]).sum() / (len(x) - 1)
        
        return series.rolling(window=window).apply(rank_pct, raw=False)


def lift_valid_indicator(values: pd.Series, full_index: pd.Index) -> pd.Series:
    """Map a valid-bar-only series back onto the full index without introducing NaNs."""
    return values.reindex(full_index).ffill()


def valid_feature_frame(df: pd.DataFrame) -> pd.DataFrame:
    if "bad_tick" not in df.columns:
        return df
    mask = df["bad_tick"].astype(bool)
    if not mask.any():
        return df
    return df.loc[~mask]


def compute_all_features(df: pd.DataFrame) -> pd.DataFrame:
    calc = FeatureCalculator()
    
    df = df.copy()
    source = valid_feature_frame(df)

    def lift(values: pd.Series) -> pd.Series:
        if source is df:
            return values
        return lift_valid_indicator(values, df.index)
    
    df["atr14"] = lift(calc.atr(source, 14))
    df["atr20"] = lift(calc.atr(source, 20))
    df["atr_pct"] = df["atr14"] / df["close"]
    df["atr_pct_rank_90"] = calc.percentile_rank(df["atr_pct"], 90)
    
    df["ema20"] = lift(calc.ema(source["close"], 20))
    df["ema60"] = lift(calc.ema(source["close"], 60))
    df["ema120"] = lift(calc.ema(source["close"], 120))
    
    ema20_above_60 = df["ema20"] > df["ema60"]
    ema60_above_120 = df["ema60"] > df["ema120"]
    ema20_below_60 = df["ema20"] < df["ema60"]
    ema60_below_120 = df["ema60"] < df["ema120"]
    
    df["ema_align"] = 0
    df.loc[ema20_above_60 & ema60_above_120, "ema_align"] = 1
    df.loc[ema20_below_60 & ema60_below_120, "ema_align"] = -1
    
    df["donchian_high_42"] = lift(calc.donchian_high(source["high"], 42))
    df["donchian_low_42"] = lift(calc.donchian_low(source["low"], 42))
    df["donchian_high_84"] = lift(calc.donchian_high(source["high"], 84))
    df["donchian_low_84"] = lift(calc.donchian_low(source["low"], 84))
    df["donchian_high_126"] = lift(calc.donchian_high(source["high"], 126))
    df["donchian_low_126"] = lift(calc.donchian_low(source["low"], 126))
    df["donchian_high_168"] = lift(calc.donchian_high(source["high"], 168))
    df["donchian_low_168"] = lift(calc.donchian_low(source["low"], 168))
    
    df["adx14"] = lift(calc.adx(source, 14))
    
    df["feature_version"] = FeatureCalculator.VERSION
    
    return df
