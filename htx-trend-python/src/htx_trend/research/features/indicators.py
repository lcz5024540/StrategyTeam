"""技术指标计算 - ATR, EMA, Donchian, ADX"""

from __future__ import annotations

import numpy as np
import pandas as pd


class FeatureCalculator:
    VERSION = "1.0.0"
    
    @staticmethod
    def atr(df: pd.DataFrame, period: int = 14) -> pd.Series:
        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        tr1 = high - low
        tr2 = abs(high - close.shift(1))
        tr3 = abs(low - close.shift(1))
        
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        atr = tr.ewm(span=period, adjust=False).mean()
        
        return atr
    
    @staticmethod
    def ema(series: pd.Series, period: int) -> pd.Series:
        return series.ewm(span=period, adjust=False).mean()
    
    @staticmethod
    def donchian_high(series: pd.Series, period: int) -> pd.Series:
        return series.rolling(window=period).max()
    
    @staticmethod
    def donchian_low(series: pd.Series, period: int) -> pd.Series:
        return series.rolling(window=period).min()
    
    @staticmethod
    def adx(df: pd.DataFrame, period: int = 14) -> pd.Series:
        high = df["high"]
        low = df["low"]
        close = df["close"]
        
        high_diff = high.diff()
        low_diff = -low.diff()
        
        plus_dm = np.where((high_diff > low_diff) & (high_diff > 0), high_diff, 0)
        minus_dm = np.where((low_diff > high_diff) & (low_diff > 0), low_diff, 0)
        
        tr1 = high - low
        tr2 = abs(high - close.shift(1))
        tr3 = abs(low - close.shift(1))
        tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
        
        atr = tr.ewm(span=period, adjust=False).mean()
        
        plus_di = 100 * pd.Series(plus_dm).ewm(span=period, adjust=False).mean() / atr
        minus_di = 100 * pd.Series(minus_dm).ewm(span=period, adjust=False).mean() / atr
        
        dx = 100 * abs(plus_di - minus_di) / (plus_di + minus_di)
        
        adx = dx.ewm(span=period, adjust=False).mean()
        
        return adx
    
    @staticmethod
    def percentile_rank(series: pd.Series, window: int = 90) -> pd.Series:
        def rank_pct(x):
            if len(x) < 2:
                return np.nan
            return (x < x.iloc[-1]).sum() / (len(x) - 1)
        
        return series.rolling(window=window).apply(rank_pct, raw=False)


def compute_all_features(df: pd.DataFrame) -> pd.DataFrame:
    calc = FeatureCalculator()
    
    df = df.copy()
    
    df["atr14"] = calc.atr(df, 14)
    df["atr20"] = calc.atr(df, 20)
    df["atr_pct"] = df["atr14"] / df["close"]
    df["atr_pct_rank_90"] = calc.percentile_rank(df["atr_pct"], 90)
    
    df["ema20"] = calc.ema(df["close"], 20)
    df["ema60"] = calc.ema(df["close"], 60)
    df["ema120"] = calc.ema(df["close"], 120)
    
    ema20_above_60 = df["ema20"] > df["ema60"]
    ema60_above_120 = df["ema60"] > df["ema120"]
    ema20_below_60 = df["ema20"] < df["ema60"]
    ema60_below_120 = df["ema60"] < df["ema120"]
    
    df["ema_align"] = 0
    df.loc[ema20_above_60 & ema60_above_120, "ema_align"] = 1
    df.loc[ema20_below_60 & ema60_below_120, "ema_align"] = -1
    
    df["donchian_high_42"] = calc.donchian_high(df["high"], 42)
    df["donchian_low_42"] = calc.donchian_low(df["low"], 42)
    df["donchian_high_84"] = calc.donchian_high(df["high"], 84)
    df["donchian_low_84"] = calc.donchian_low(df["low"], 84)
    df["donchian_high_126"] = calc.donchian_high(df["high"], 126)
    df["donchian_low_126"] = calc.donchian_low(df["low"], 126)
    df["donchian_high_168"] = calc.donchian_high(df["high"], 168)
    df["donchian_low_168"] = calc.donchian_low(df["low"], 168)
    
    df["adx14"] = calc.adx(df, 14)
    
    df["feature_version"] = FeatureCalculator.VERSION
    
    return df
