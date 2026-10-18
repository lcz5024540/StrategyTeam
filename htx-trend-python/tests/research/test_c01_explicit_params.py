"""测试 C01 显式参数和投票记录"""

import pandas as pd
import pytest

from htx_trend.research.conditions.base import DecisionContext
from htx_trend.research.conditions.c01_donchian import C01DonchianEnsemble


def test_c01_requires_explicit_periods():
    """C01 必须显式传递 periods 参数"""
    with pytest.raises(ValueError, match="'periods' must be provided"):
        C01DonchianEnsemble(periods=[], threshold=0.25)


def test_c01_requires_explicit_threshold():
    """C01 必须显式传递 threshold 参数"""
    with pytest.raises(ValueError, match="'threshold' must be provided"):
        C01DonchianEnsemble(periods=[42, 84], threshold=None)


def test_c01_validates_threshold_range():
    """C01 threshold 必须在 [0, 1] 范围内"""
    with pytest.raises(ValueError, match="threshold.*must be in"):
        C01DonchianEnsemble(periods=[42, 84], threshold=1.5)
    
    with pytest.raises(ValueError, match="threshold.*must be in"):
        C01DonchianEnsemble(periods=[42, 84], threshold=-0.1)


def test_c01_records_direction_and_votes_long():
    """C01 记录 LONG 方向和每个窗口的投票"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=5, freq="4h"),
        "close": [100, 101, 102, 103, 111],  # 最后一根突破
        "donchian_high_42": [100, 100, 100, 100, 105],  # 111 > 105 → LONG
        "donchian_low_42": [90, 90, 90, 90, 95],
        "donchian_high_84": [100, 100, 100, 100, 106],  # 111 > 106 → LONG
        "donchian_low_84": [88, 88, 88, 88, 94],
    })
    
    ctx = DecisionContext(
        symbol="BTCUSDT",
        bar_time=df["bar_time"].iloc[4],
        df=df,
        current_idx=4,
    )
    
    c01 = C01DonchianEnsemble(periods=[42, 84], threshold=0.5)
    
    result = c01.evaluate(ctx, {})
    
    assert result.status == "PASS"
    assert result.evidence["direction"] == "LONG"
    
    # 两个窗口都投票 LONG (1)
    assert result.evidence["votes"] == {42: 1, 84: 1}
    
    # 平均信号 = (1 + 1) / 2 = 1.0 > 0.5
    assert result.evidence["avg_signal"] == 1.0
    assert result.value == 1.0


def test_c01_records_direction_and_votes_short():
    """C01 记录 SHORT 方向和每个窗口的投票"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=5, freq="4h"),
        "close": [100, 99, 98, 97, 85],  # 最后一根向下突破
        "donchian_high_42": [100, 100, 100, 100, 105],
        "donchian_low_42": [90, 90, 90, 90, 95],  # 85 < 95 → SHORT
        "donchian_high_84": [100, 100, 100, 100, 106],
        "donchian_low_84": [88, 88, 88, 88, 94],  # 85 < 94 → SHORT
        "donchian_high_126": [100, 100, 100, 100, 107],
        "donchian_low_126": [87, 87, 87, 87, 93],  # 85 < 93 → SHORT
    })
    
    ctx = DecisionContext(
        symbol="BTCUSDT",
        bar_time=df["bar_time"].iloc[4],
        df=df,
        current_idx=4,
    )
    
    c01 = C01DonchianEnsemble(periods=[42, 84, 126], threshold=0.6)
    
    result = c01.evaluate(ctx, {})
    
    assert result.status == "PASS"
    assert result.evidence["direction"] == "SHORT"
    
    # 三个窗口都投票 SHORT (-1)
    assert result.evidence["votes"] == {42: -1, 84: -1, 126: -1}
    
    # 平均信号 = (-1 + -1 + -1) / 3 = -1.0 < -0.6
    assert result.evidence["avg_signal"] == -1.0
    assert result.value == -1.0


def test_c01_mixed_votes_with_neutral():
    """C01 混合投票：部分 LONG，部分 neutral"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=5, freq="4h"),
        "close": [100, 101, 102, 103, 107],
        "donchian_high_42": [100, 100, 100, 100, 105],  # 107 > 105 → LONG
        "donchian_low_42": [90, 90, 90, 90, 95],
        "donchian_high_84": [100, 100, 100, 100, 106],  # 107 > 106 → LONG
        "donchian_low_84": [88, 88, 88, 88, 94],
        "donchian_high_126": [100, 100, 100, 100, 108],  # 107 < 108 → neutral
        "donchian_low_126": [87, 87, 87, 87, 93],
    })
    
    ctx = DecisionContext(
        symbol="BTCUSDT",
        bar_time=df["bar_time"].iloc[4],
        df=df,
        current_idx=4,
    )
    
    c01 = C01DonchianEnsemble(periods=[42, 84, 126], threshold=0.5)
    
    result = c01.evaluate(ctx, {})
    
    # votes: {42: 1, 84: 1, 126: 0}
    # avg = (1 + 1 + 0) / 3 = 0.667 > 0.5 → PASS LONG
    assert result.status == "PASS"
    assert result.evidence["direction"] == "LONG"
    assert result.evidence["votes"] == {42: 1, 84: 1, 126: 0}
    assert result.evidence["avg_signal"] == pytest.approx(0.6667, abs=0.01)


def test_c01_fails_when_below_threshold():
    """C01 当集成信号低于阈值时 FAIL"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=5, freq="4h"),
        "close": [100, 101, 102, 103, 104],
        "donchian_high_42": [100, 100, 100, 100, 105],  # neutral
        "donchian_low_42": [90, 90, 90, 90, 95],
        "donchian_high_84": [100, 100, 100, 100, 106],  # neutral
        "donchian_low_84": [88, 88, 88, 88, 94],
    })
    
    ctx = DecisionContext(
        symbol="BTCUSDT",
        bar_time=df["bar_time"].iloc[4],
        df=df,
        current_idx=4,
    )
    
    c01 = C01DonchianEnsemble(periods=[42, 84], threshold=0.5)
    
    result = c01.evaluate(ctx, {})
    
    # 所有投票都是 neutral (0)
    assert result.status == "FAIL"
    assert result.evidence["direction"] is None
    assert result.evidence["votes"] == {42: 0, 84: 0}
    assert result.evidence["avg_signal"] == 0.0


def test_c01_evidence_includes_close_and_threshold():
    """C01 evidence 包含 close 和 threshold 以便审计"""
    df = pd.DataFrame({
        "bar_time": pd.date_range("2024-01-01", periods=5, freq="4h"),
        "close": [100, 101, 102, 103, 112.5],
        "donchian_high_42": [100, 100, 100, 100, 110],
        "donchian_low_42": [90, 90, 90, 90, 95],
    })
    
    ctx = DecisionContext(
        symbol="BTCUSDT",
        bar_time=df["bar_time"].iloc[4],
        df=df,
        current_idx=4,
    )
    
    c01 = C01DonchianEnsemble(periods=[42], threshold=0.75)
    
    result = c01.evaluate(ctx, {})
    
    assert result.evidence["close"] == 112.5
    assert result.evidence["threshold"] == 0.75
    assert "avg_signal" in result.evidence
    assert "votes" in result.evidence
