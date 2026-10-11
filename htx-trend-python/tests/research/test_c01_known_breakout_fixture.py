"""测试已知突破样例必须触发 C01 信号

这是技术审查规范要求的必备测试：构造一个已知突破的样例，信号必须触发。
"""

import pandas as pd
import pytest

from htx_trend.research.conditions.base import DecisionContext
from htx_trend.research.conditions.c01_donchian import C01DonchianEnsemble


def test_known_breakout_fixture_must_trigger_c01_long():
    """已知多头突破样例必须触发 C01 PASS LONG 信号"""
    
    # 构造明确的多头突破场景：
    # - 前 168 根 K 线价格在 90-110 区间震荡
    # - 最后一根突破所有 Donchian 高点
    
    bars = []
    
    # 前 168 根：震荡区间 [90, 110]
    for i in range(168):
        bars.append({
            "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4*i),
            "open": 95 + (i % 10),
            "high": 100 + (i % 15),
            "low": 90 + (i % 8),
            "close": 98 + (i % 12),
        })
    
    # 最后一根：明确向上突破
    # 所有 Donchian 周期的高点都在 110-115 之间
    # 收盘价 130 明显突破
    bars.append({
        "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4*168),
        "open": 115,
        "high": 132,
        "low": 114,
        "close": 130,  # 突破价格
    })
    
    df = pd.DataFrame(bars)
    
    # 计算 Donchian 通道（使用实际的 FeatureCalculator）
    from htx_trend.research.features.indicators import FeatureCalculator
    
    for period in [42, 84, 126, 168]:
        df[f"donchian_high_{period}"] = FeatureCalculator.donchian_high(df["high"], period)
        df[f"donchian_low_{period}"] = FeatureCalculator.donchian_low(df["low"], period)
    
    # 最后一根 K 线的 Donchian 高点应该都 < 130
    last_idx = len(df) - 1
    for period in [42, 84, 126, 168]:
        don_high = df[f"donchian_high_{period}"].iloc[last_idx]
        assert don_high < 130, f"Period {period} don_high should be < 130, got {don_high}"
    
    # 创建决策上下文
    ctx = DecisionContext(
        symbol="BTC-USDT",
        bar_time=df["bar_time"].iloc[last_idx],
        df=df,
        current_idx=last_idx,
    )
    
    # 评估 C01
    c01 = C01DonchianEnsemble(periods=[42, 84, 126, 168], threshold=0.25)
    result = c01.evaluate(ctx, {})
    
    # 断言：必须 PASS
    assert result.status == "PASS", f"Known breakout must trigger C01, got {result.status}: {result.reason}"
    
    # 断言：方向必须是 LONG
    assert result.evidence["direction"] == "LONG", f"Direction must be LONG, got {result.evidence['direction']}"
    
    # 断言：所有窗口都投票 LONG (1)
    assert all(v == 1 for v in result.evidence["votes"].values()), \
        f"All windows should vote LONG (1), got {result.evidence['votes']}"
    
    # 断言：平均信号 = 1.0
    assert result.value == 1.0, f"avg_signal should be 1.0, got {result.value}"
    
    print(f"✅ Known long breakout triggered C01: {result.reason}")
    print(f"   Votes: {result.evidence['votes']}")
    print(f"   Direction: {result.evidence['direction']}")


def test_known_breakout_fixture_must_trigger_c01_short():
    """已知空头突破样例必须触发 C01 PASS SHORT 信号"""
    
    # 构造明确的空头突破场景：
    # - 前 168 根 K 线价格在 90-110 区间震荡
    # - 最后一根向下突破所有 Donchian 低点
    
    bars = []
    
    # 前 168 根：震荡区间 [90, 110]
    for i in range(168):
        bars.append({
            "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4*i),
            "open": 95 + (i % 10),
            "high": 100 + (i % 15),
            "low": 90 + (i % 8),
            "close": 98 + (i % 12),
        })
    
    # 最后一根：明确向下突破
    # 所有 Donchian 周期的低点都在 85-90 之间
    # 收盘价 70 明显突破
    bars.append({
        "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4*168),
        "open": 85,
        "high": 86,
        "low": 68,
        "close": 70,  # 突破价格
    })
    
    df = pd.DataFrame(bars)
    
    # 计算 Donchian 通道
    from htx_trend.research.features.indicators import FeatureCalculator
    
    for period in [42, 84, 126, 168]:
        df[f"donchian_high_{period}"] = FeatureCalculator.donchian_high(df["high"], period)
        df[f"donchian_low_{period}"] = FeatureCalculator.donchian_low(df["low"], period)
    
    # 最后一根 K 线的 Donchian 低点应该都 > 70
    last_idx = len(df) - 1
    for period in [42, 84, 126, 168]:
        don_low = df[f"donchian_low_{period}"].iloc[last_idx]
        assert don_low > 70, f"Period {period} don_low should be > 70, got {don_low}"
    
    # 创建决策上下文
    ctx = DecisionContext(
        symbol="BTC-USDT",
        bar_time=df["bar_time"].iloc[last_idx],
        df=df,
        current_idx=last_idx,
    )
    
    # 评估 C01
    c01 = C01DonchianEnsemble(periods=[42, 84, 126, 168], threshold=0.25)
    result = c01.evaluate(ctx, {})
    
    # 断言：必须 PASS
    assert result.status == "PASS", f"Known breakout must trigger C01, got {result.status}: {result.reason}"
    
    # 断言：方向必须是 SHORT
    assert result.evidence["direction"] == "SHORT", f"Direction must be SHORT, got {result.evidence['direction']}"
    
    # 断言：所有窗口都投票 SHORT (-1)
    assert all(v == -1 for v in result.evidence["votes"].values()), \
        f"All windows should vote SHORT (-1), got {result.evidence['votes']}"
    
    # 断言：平均信号 = -1.0
    assert result.value == -1.0, f"avg_signal should be -1.0, got {result.value}"
    
    print(f"✅ Known short breakout triggered C01: {result.reason}")
    print(f"   Votes: {result.evidence['votes']}")
    print(f"   Direction: {result.evidence['direction']}")


def test_known_non_breakout_must_not_trigger_c01():
    """已知非突破样例不应触发 C01"""
    
    # 构造价格在通道内的场景
    bars = []
    
    # 168 根 K 线：价格在 90-110 区间震荡
    for i in range(169):
        bars.append({
            "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4*i),
            "open": 95 + (i % 10),
            "high": 100 + (i % 15),
            "low": 90 + (i % 8),
            "close": 98 + (i % 12),
        })
    
    df = pd.DataFrame(bars)
    
    # 计算 Donchian 通道
    from htx_trend.research.features.indicators import FeatureCalculator
    
    for period in [42, 84, 126, 168]:
        df[f"donchian_high_{period}"] = FeatureCalculator.donchian_high(df["high"], period)
        df[f"donchian_low_{period}"] = FeatureCalculator.donchian_low(df["low"], period)
    
    last_idx = len(df) - 1
    
    # 创建决策上下文
    ctx = DecisionContext(
        symbol="BTC-USDT",
        bar_time=df["bar_time"].iloc[last_idx],
        df=df,
        current_idx=last_idx,
    )
    
    # 评估 C01
    c01 = C01DonchianEnsemble(periods=[42, 84, 126, 168], threshold=0.25)
    result = c01.evaluate(ctx, {})
    
    # 断言：不应 PASS
    assert result.status == "FAIL", f"Non-breakout should not trigger C01, got {result.status}"
    
    # 断言：方向为 None
    assert result.evidence["direction"] is None, f"Direction should be None, got {result.evidence['direction']}"
    
    print(f"✅ Non-breakout correctly failed C01: {result.reason}")
