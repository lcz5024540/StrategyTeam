from decimal import Decimal

from htx_trend.indicators import adx, atr, ema, macd, rsi, snapshot
from htx_trend.market_data import Candle
from htx_trend.models import ModelCode, TrendConfig, TrendSide, candidates, evaluate_signal, market_breadth


def bar(index: int, close: int) -> Candle:
    value = Decimal(close)
    return Candle(index * 900, value - 1, value + 2, value - 2, value, Decimal("1"), Decimal("2"), Decimal("100"), 1)


def test_indicator_functions_are_deterministic_and_non_forward_looking() -> None:
    values = [Decimal("1"), Decimal("2"), Decimal("3")]
    assert ema(values, 2) == Decimal("2.555555555555555556")
    candles = [bar(index, 100 + index) for index in range(60)]
    assert atr(candles, 14) > 0
    assert adx(candles, 14) > 0
    assert rsi(candles) > Decimal("50")
    line, signal, histogram = macd(candles)
    assert line > 0 and signal > 0 and histogram >= 0
    assert snapshot(candles).vwap > 0


def test_donchian_and_momentum_candidates_can_coexist_on_same_window() -> None:
    entry = [bar(index, 100 + index) for index in range(90)]
    entry[-1] = bar(89, 195)
    trend = [bar(index, 100 + index) for index in range(90)]
    config = TrendConfig(medium_ema_period=16, slow_ema_period=20, breakout_lookback=10, atr_period=5, adx_period=5, min_adx=Decimal("1"), min_atr_pct=Decimal("0"))
    signal = evaluate_signal(entry, trend, config)
    assert signal.side is TrendSide.LONG
    output = candidates(entry, signal, config)
    assert ModelCode.DONCHIAN_BREAKOUT in {item.model for item in output}
    assert ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION in {item.model for item in output}


def test_market_breadth_only_declares_direction_above_threshold() -> None:
    entry = [bar(i, 100 + i) for i in range(90)]
    entry[-1] = bar(89, 195)
    long = evaluate_signal(entry, [bar(i, 100 + i) for i in range(90)], TrendConfig(medium_ema_period=16, slow_ema_period=20, breakout_lookback=10, atr_period=5, adx_period=5, min_adx=Decimal("1"), min_atr_pct=Decimal("0")))
    flat = long.__class__(TrendSide.FLAT, None, Decimal("0"), None, None, None, TrendSide.FLAT, "flat")
    result = market_breadth([long, long, flat])
    assert result.side is TrendSide.FLAT
    assert result.ratio == Decimal("0.666666666666666667")
