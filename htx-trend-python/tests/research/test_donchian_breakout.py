"""测试 Donchian 通道排除当前 K 线并正确检测突破"""

import pandas as pd
import pytest

from htx_trend.research.features.indicators import FeatureCalculator


def test_donchian_excludes_current_bar():
    """Donchian 通道必须排除当前 K 线，只使用前 N 根"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=10, freq="4h"),
        "high": [100, 101, 102, 103, 104, 105, 106, 107, 108, 150],  # 最后一根暴涨
        "low": [90, 91, 92, 93, 94, 95, 96, 97, 98, 99],
        "close": [95, 96, 97, 98, 99, 100, 101, 102, 103, 149],
    })
    
    period = 5
    
    don_high = FeatureCalculator.donchian_high(df["high"], period)
    don_low = FeatureCalculator.donchian_low(df["low"], period)
    
    # 索引 9 的 Donchian 高点应该是索引 4-8 的最大值（不包括索引 9）
    # 索引 4-8 的 high: [104, 105, 106, 107, 108]
    assert don_high.iloc[9] == 108, f"Expected 108 (prior 5 bars max), got {don_high.iloc[9]}"
    
    # 如果没有 shift(1)，它会错误地包含当前 K 线，返回 150
    assert don_high.iloc[9] != 150, "Current bar (150) should NOT be included"
    
    # 索引 9 的 Donchian 低点应该是索引 4-8 的最小值
    # 索引 4-8 的 low: [94, 95, 96, 97, 98]
    assert don_low.iloc[9] == 94, f"Expected 94 (prior 5 bars min), got {don_low.iloc[9]}"


def test_donchian_breakout_long_detected():
    """测试多头突破：close > prior N bars max(high)"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=10, freq="4h"),
        "high": [100, 101, 102, 103, 104, 105, 106, 107, 108, 110],
        "low": [90, 91, 92, 93, 94, 95, 96, 97, 98, 100],
        "close": [95, 96, 97, 98, 99, 100, 101, 102, 103, 109],  # 最后一根突破
    })
    
    period = 5
    
    don_high = FeatureCalculator.donchian_high(df["high"], period)
    
    # 索引 9: don_high = max(high[4:9]) = max(104, 105, 106, 107, 108) = 108
    assert don_high.iloc[9] == 108
    
    # 突破检测：close[9] = 109 > don_high[9] = 108 → 多头突破
    breakout_long = df["close"].iloc[9] > don_high.iloc[9]
    assert breakout_long, "Should detect long breakout when close > prior N bars high"


def test_donchian_breakout_short_detected():
    """测试空头突破：close < prior N bars min(low)"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=10, freq="4h"),
        "high": [110, 109, 108, 107, 106, 105, 104, 103, 102, 100],
        "low": [100, 99, 98, 97, 96, 95, 94, 93, 92, 88],
        "close": [105, 104, 103, 102, 101, 100, 99, 98, 97, 89],  # 最后一根突破
    })
    
    period = 5
    
    don_low = FeatureCalculator.donchian_low(df["low"], period)
    
    # 索引 9: don_low = min(low[4:9]) = min(96, 95, 94, 93, 92) = 92
    assert don_low.iloc[9] == 92
    
    # 突破检测：close[9] = 89 < don_low[9] = 92 → 空头突破
    breakout_short = df["close"].iloc[9] < don_low.iloc[9]
    assert breakout_short, "Should detect short breakout when close < prior N bars low"


def test_donchian_no_breakout_when_within_channel():
    """测试当价格在通道内时不触发突破"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=10, freq="4h"),
        "high": [100, 101, 102, 103, 104, 105, 106, 107, 108, 107],
        "low": [90, 91, 92, 93, 94, 95, 96, 97, 98, 97],
        "close": [95, 96, 97, 98, 99, 100, 101, 102, 103, 102],  # 在通道内
    })
    
    period = 5
    
    don_high = FeatureCalculator.donchian_high(df["high"], period)
    don_low = FeatureCalculator.donchian_low(df["low"], period)
    
    # 索引 9: 
    # don_high = max(high[4:9]) = 108
    # don_low = min(low[4:9]) = 94
    # close[9] = 102
    
    assert don_high.iloc[9] == 108
    assert don_low.iloc[9] == 94
    
    # 没有突破
    assert not (df["close"].iloc[9] > don_high.iloc[9]), "Should not break high"
    assert not (df["close"].iloc[9] < don_low.iloc[9]), "Should not break low"


def test_donchian_first_period_bars_are_nan():
    """测试前 period 根 K 线的 Donchian 值为 NaN（数据不足）"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=10, freq="4h"),
        "high": list(range(100, 110)),
        "low": list(range(90, 100)),
        "close": list(range(95, 105)),
    })
    
    period = 5
    
    don_high = FeatureCalculator.donchian_high(df["high"], period)
    don_low = FeatureCalculator.donchian_low(df["low"], period)
    
    # 前 period 根（索引 0 到 period-1）+ 额外 shift(1) 应该是 NaN
    # shift(1) 使得前 period+1 根为 NaN
    assert pd.isna(don_high.iloc[0:period]).all()
    assert pd.isna(don_low.iloc[0:period]).all()
    
    # 索引 period 开始应该有值
    assert not pd.isna(don_high.iloc[period])
    assert not pd.isna(don_low.iloc[period])
