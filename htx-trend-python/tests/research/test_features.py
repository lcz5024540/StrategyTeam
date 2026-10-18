"""测试技术指标计算"""

import numpy as np
import pandas as pd
import pytest

from htx_trend.research.features.indicators import FeatureCalculator


@pytest.fixture
def sample_ohlcv():
    np.random.seed(42)
    dates = pd.date_range("2024-01-01", periods=200, freq="4h")
    
    close = 100 + np.cumsum(np.random.randn(200) * 0.5)
    high = close + np.random.rand(200) * 2
    low = close - np.random.rand(200) * 2
    open_prices = close + (np.random.rand(200) - 0.5) * 1
    
    return pd.DataFrame({
        "bar_time": dates,
        "open": open_prices,
        "high": high,
        "low": low,
        "close": close,
        "volume": np.random.rand(200) * 1000,
    })


def test_atr_calculation(sample_ohlcv):
    calc = FeatureCalculator()
    atr = calc.atr(sample_ohlcv, period=14)
    
    assert len(atr) == len(sample_ohlcv)
    assert not atr.iloc[-1] == 0
    assert atr.iloc[-1] > 0


def test_ema_calculation(sample_ohlcv):
    calc = FeatureCalculator()
    ema20 = calc.ema(sample_ohlcv["close"], 20)
    
    assert len(ema20) == len(sample_ohlcv)
    assert not pd.isna(ema20.iloc[-1])


def test_donchian_channels(sample_ohlcv):
    calc = FeatureCalculator()
    
    high_42 = calc.donchian_high(sample_ohlcv["high"], 42)
    low_42 = calc.donchian_low(sample_ohlcv["low"], 42)
    
    assert len(high_42) == len(sample_ohlcv)
    assert len(low_42) == len(sample_ohlcv)
    
    assert high_42.iloc[-1] >= sample_ohlcv["high"].iloc[-42:].max() * 0.99


def test_adx_calculation(sample_ohlcv):
    calc = FeatureCalculator()
    adx = calc.adx(sample_ohlcv, period=14)
    
    assert len(adx) == len(sample_ohlcv)
    assert adx.iloc[-1] >= 0
    assert adx.iloc[-1] <= 100


def test_no_lookahead():
    dates = pd.date_range("2024-01-01", periods=100, freq="4h")
    close = list(range(100))
    
    df = pd.DataFrame({
        "bar_time": dates,
        "open": close,
        "high": [c + 1 for c in close],
        "low": [c - 1 for c in close],
        "close": close,
        "volume": [1000] * 100,
    })
    
    calc = FeatureCalculator()
    ema20 = calc.ema(df["close"], 20)
    
    assert ema20.iloc[19] < df["close"].iloc[19]
    
    assert ema20.iloc[50] > df["close"].iloc[30]
