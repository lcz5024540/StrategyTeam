"""测试 ATR/ADX 索引保留和 Wilder 平滑方法"""

import pandas as pd
import pytest

from htx_trend.research.features.indicators import FeatureCalculator


def test_atr_preserves_custom_index():
    """ATR 必须保留 DataFrame 的自定义索引（非默认整数）"""
    custom_index = pd.date_range("2024-01-01", periods=20, freq="4h")
    
    df = pd.DataFrame({
        "high": [100 + i for i in range(20)],
        "low": [90 + i for i in range(20)],
        "close": [95 + i for i in range(20)],
    }, index=custom_index)
    
    atr = FeatureCalculator.atr(df, period=14, method="wilder")
    
    # 索引必须保持一致
    assert atr.index.equals(df.index), "ATR must preserve input DataFrame index"
    
    # 长度一致
    assert len(atr) == len(df)


def test_adx_preserves_custom_index():
    """ADX 必须保留 DataFrame 的自定义索引"""
    custom_index = pd.date_range("2024-01-01", periods=30, freq="1h")
    
    df = pd.DataFrame({
        "high": [100 + i * 0.5 for i in range(30)],
        "low": [90 + i * 0.5 for i in range(30)],
        "close": [95 + i * 0.5 for i in range(30)],
    }, index=custom_index)
    
    adx = FeatureCalculator.adx(df, period=14, method="wilder")
    
    # 索引必须保持一致
    assert adx.index.equals(df.index), "ADX must preserve input DataFrame index"
    
    # 长度一致
    assert len(adx) == len(df)


def test_atr_wilder_vs_ema_different_results():
    """ATR Wilder 方法和 EMA 方法应产生不同结果"""
    df = pd.DataFrame({
        "high": [100, 102, 105, 103, 107, 110, 108, 112, 115, 113] * 3,
        "low": [90, 92, 95, 93, 97, 100, 98, 102, 105, 103] * 3,
        "close": [95, 97, 100, 98, 102, 105, 103, 107, 110, 108] * 3,
    })
    
    atr_wilder = FeatureCalculator.atr(df, period=5, method="wilder")
    atr_ema = FeatureCalculator.atr(df, period=5, method="ema")
    
    # Wilder 和 EMA 平滑结果不同（alpha=1/5 vs span=5）
    assert not atr_wilder.equals(atr_ema), "Wilder and EMA methods should differ"
    
    # 但长度和索引应该相同
    assert len(atr_wilder) == len(atr_ema)
    assert atr_wilder.index.equals(atr_ema.index)


def test_adx_wilder_vs_ema_different_results():
    """ADX Wilder 方法和 EMA 方法应产生不同结果"""
    df = pd.DataFrame({
        "high": [100, 102, 105, 103, 107, 110, 108, 112, 115, 113] * 3,
        "low": [90, 92, 95, 93, 97, 100, 98, 102, 105, 103] * 3,
        "close": [95, 97, 100, 98, 102, 105, 103, 107, 110, 108] * 3,
    })
    
    adx_wilder = FeatureCalculator.adx(df, period=5, method="wilder")
    adx_ema = FeatureCalculator.adx(df, period=5, method="ema")
    
    # Wilder 和 EMA 平滑结果不同
    assert not adx_wilder.equals(adx_ema), "Wilder and EMA methods should differ"
    
    # 但长度和索引应该相同
    assert len(adx_wilder) == len(adx_ema)
    assert adx_wilder.index.equals(adx_ema.index)


def test_atr_invalid_method_raises():
    """ATR 不支持的方法应抛出异常"""
    df = pd.DataFrame({
        "high": [100, 102, 105],
        "low": [90, 92, 95],
        "close": [95, 97, 100],
    })
    
    with pytest.raises(ValueError, match="Unknown ATR method"):
        FeatureCalculator.atr(df, period=2, method="invalid")


def test_adx_invalid_method_raises():
    """ADX 不支持的方法应抛出异常"""
    df = pd.DataFrame({
        "high": [100, 102, 105],
        "low": [90, 92, 95],
        "close": [95, 97, 100],
    })
    
    with pytest.raises(ValueError, match="Unknown ADX method"):
        FeatureCalculator.adx(df, period=2, method="invalid")


def test_atr_wilder_smoothing_formula():
    """验证 Wilder 平滑公式：alpha = 1/period"""
    df = pd.DataFrame({
        "high": [100, 102, 105, 103, 107, 110],
        "low": [90, 92, 95, 93, 97, 100],
        "close": [95, 97, 100, 98, 102, 105],
    })
    
    period = 3
    
    # Wilder smoothing with alpha = 1/period
    atr_wilder = FeatureCalculator.atr(df, period=period, method="wilder")
    
    # 使用 ewm(alpha=1/3) 应该等价
    tr1 = df["high"] - df["low"]
    tr2 = abs(df["high"] - df["close"].shift(1))
    tr3 = abs(df["low"] - df["close"].shift(1))
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    
    atr_manual = tr.ewm(alpha=1.0/period, adjust=False).mean()
    
    pd.testing.assert_series_equal(atr_wilder, atr_manual, check_names=False)


def test_adx_index_after_multiple_operations():
    """ADX 在多次 pandas 操作后仍保留索引"""
    custom_index = ["bar_1", "bar_2", "bar_3", "bar_4", "bar_5"] * 10
    
    df = pd.DataFrame({
        "high": list(range(100, 150)),
        "low": list(range(90, 140)),
        "close": list(range(95, 145)),
    }, index=custom_index)
    
    adx = FeatureCalculator.adx(df, period=5, method="wilder")
    
    # 索引类型和值都必须保留
    assert adx.index.tolist() == custom_index
    assert len(adx) == len(df)
    
    # 即使是重复索引也能正确处理
    assert adx.index.tolist() == df.index.tolist()
