from decimal import Decimal

from htx_trend.java_entry_contract import JavaEntryConfig, JavaTrendSignal, candidates, evaluate
from htx_trend.indicators import IndicatorSnapshot
from htx_trend.market_data import Candle
from htx_trend.models import ModelCode, TrendSide
from htx_trend.policy import market_adjustment
from htx_trend.strategy_context import MarketContext, MarketRegime


def _uptrend() -> tuple[Candle, ...]:
    rows = []
    for index in range(130):
        close = Decimal("100") + Decimal(index) / Decimal("10")
        rows.append(Candle(index * 900, close - Decimal("0.05"), close + Decimal("0.10"), close - Decimal("0.10"), close,
                           amount=Decimal("100") + Decimal(index)))
    # The final completed candle closes beyond the preceding channel; equality
    # is intentionally not a Java Donchian breakout.
    last = rows[-1]
    rows[-1] = Candle(last.open_time, last.open, last.high + Decimal("0.25"), last.low,
                      last.close + Decimal("0.25"), amount=last.amount)
    return tuple(rows)


def test_java_ensemble_contract_produces_java_box_donchian_candidate() -> None:
    signal = evaluate(_uptrend(), JavaEntryConfig(box_window_bars=32))
    found = candidates(_uptrend(), signal, JavaEntryConfig(box_window_bars=32))
    donchian = next(item for item in found if item.model is ModelCode.DONCHIAN_BREAKOUT)
    assert signal.side is TrendSide.LONG
    assert signal.score >= Decimal("90")
    assert donchian.score == Decimal("100")
    assert donchian.policy_confirmations == "BOX_BREAKOUT"


def test_java_market_rank_requires_breadth_and_both_anchor_symbols() -> None:
    signal = evaluate(_uptrend(), JavaEntryConfig(box_window_bars=32))
    item = next(value for value in candidates(_uptrend(), signal, JavaEntryConfig(box_window_bars=32))
                if value.model is ModelCode.DONCHIAN_BREAKOUT)
    aligned = MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG, Decimal("0.9"), TrendSide.LONG, TrendSide.LONG)
    mixed_anchor = MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG, Decimal("0.9"), TrendSide.LONG, TrendSide.FLAT)
    assert market_adjustment(item, aligned) == Decimal("9.60")
    assert market_adjustment(item, mixed_anchor) == Decimal("-2.40")


def _downtrend() -> tuple[Candle, ...]:
    rows = []
    for index in range(130):
        close = Decimal("120") - Decimal(index) / Decimal("10")
        rows.append(Candle(index * 900, close + Decimal("0.05"), close + Decimal("0.10"), close - Decimal("0.10"), close,
                           amount=Decimal("100") + Decimal(index)))
    last = rows[-1]
    rows[-1] = Candle(last.open_time, last.open, last.high, last.low - Decimal("0.25"),
                      last.close - Decimal("0.25"), amount=last.amount)
    return tuple(rows)

def _flat() -> tuple[Candle, ...]:
    return tuple(Candle(index * 900, Decimal("100"), Decimal("100.01"), Decimal("99.99"), Decimal("100"), amount=Decimal("1"))
                 for index in range(130))

def _at_18(value: Decimal) -> Decimal:
    return value.quantize(Decimal("0.000000000000000001"))

def test_java_fixed_kline_goldens_cover_long_short_and_low_volatility() -> None:
    # Values are independently asserted by HtxTrendPythonEntryParityTest in the
    # Java module.  Compare at the exchange execution precision (18 decimals).
    cases = (
        (_uptrend(), TrendSide.LONG, "TRENDING_UP", Decimal("113.15"), Decimal("90"), Decimal("0.217857142857142857"),
         Decimal("0.012981199641897941"), "DONCHIAN_BREAKOUT", Decimal("110.80"), Decimal("116.20"), Decimal("1.297872340425531915")),
        (_downtrend(), TrendSide.SHORT, "TRENDING_DOWN", Decimal("106.85"), Decimal("90"), Decimal("0.217857142857142857"),
         Decimal("-0.013388734995383195"), "DONCHIAN_BREAKOUT", Decimal("109.20"), Decimal("103.80"), Decimal("1.297872340425531915")),
        (_flat(), TrendSide.FLAT, "LOW_VOLATILITY", Decimal("100"), Decimal("0"), Decimal("0.020000000000000000"),
         Decimal("0"), None, None, None, None),
    )
    for candles, side, regime, close, score, atr, momentum, model, invalidation, target, expected_r in cases:
        signal = evaluate(candles, JavaEntryConfig())
        assert signal.side is side
        assert signal.regime == regime
        assert _at_18(signal.close_price) == _at_18(close)
        assert _at_18(signal.score) == _at_18(score)
        assert _at_18(signal.indicators.atr) == _at_18(atr)
        assert _at_18(signal.indicators.roc) == _at_18(momentum)
        resolved = candidates(candles, signal, JavaEntryConfig())
        if model is None:
            assert not resolved
            continue
        candidate = next(item for item in resolved if item.model.value == model)
        assert _at_18(candidate.invalidation_price) == _at_18(invalidation)
        assert _at_18(candidate.target_low) == _at_18(target)
        assert _at_18(candidate.target_high) == _at_18(target)
        assert _at_18(candidate.expected_r) == _at_18(expected_r)


def test_java_fibonacci_contract_preserves_prior_breakout_and_all_policy_confirmations() -> None:
    # This is the same fixed completed-bar setup asserted by Java's
    # HtxTrendCombinationCalculatorTest. It exercises a continuation, rather
    # than the fresh-start cases above.
    factor = IndicatorSnapshot(
        Decimal("106"), Decimal("104"), Decimal("102"), Decimal("2"), Decimal("0.01"), Decimal("30"),
        Decimal("0.02"), Decimal("0"), Decimal("0"), Decimal("0"), Decimal("0"), Decimal("0"))
    signal = JavaTrendSignal(TrendSide.LONG, 40, Decimal("114"), factor, Decimal("120"), Decimal("99"), "test",
                         "TRENDING_UP", Decimal("90"), Decimal("90"), Decimal("0"), Decimal("90"), Decimal("1.5"))
    candles = [Candle(index, Decimal("100"), Decimal("110"), Decimal("101"), Decimal("105"), amount=Decimal("1"))
               for index in range(32)]
    candles.append(Candle(32, Decimal("106"), Decimal("121"), Decimal("107"), Decimal("120"), amount=Decimal("1")))
    candles.extend(Candle(index, Decimal("116"), Decimal("120"), Decimal("112"), Decimal("116"), amount=Decimal("1"))
                   for index in range(33, 40))
    candles.append(Candle(40, Decimal("115"), Decimal("117"), Decimal("113"), Decimal("114"), amount=Decimal("1")))
    fibs = [item for item in candidates(tuple(candles), signal, JavaEntryConfig(box_window_bars=32))
            if item.model is ModelCode.FIB_PULLBACK_CONTINUATION]
    assert [item.policy_confirmations for item in fibs] == ["BOX_BREAKOUT", "DONCHIAN_BREAKOUT", "BOX_BREAKOUT,DONCHIAN_BREAKOUT"]
    for item in fibs:
        assert item.source_breakout_time == 32
        assert item.invalidation_price == Decimal("108.640")
        assert item.target_low == Decimal("126.440")
        assert item.target_high == Decimal("133.360")
        assert _at_18(item.expected_r) == Decimal("2.320895522388059701")
