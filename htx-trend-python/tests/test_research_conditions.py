from decimal import Decimal

from htx_trend.indicators import IndicatorSnapshot
from htx_trend.market_data import Candle
from htx_trend.models import TrendSide
from htx_trend.research.conditions import research_condition_results


def test_true_regression_slopes_replace_legacy_endpoint_and_ema_spread_proxies() -> None:
    closes = [Decimal("100") for _ in range(101)] + [Decimal(value) for value in range(120, 101, -1)] + [Decimal("104")]
    candles = tuple(Candle(index*900, close, close+1, close-1, close,
                           Decimal("100"), Decimal("100")) for index, close in enumerate(closes))
    factors = IndicatorSnapshot(
        Decimal("110"), Decimal("100"), Decimal("90"), Decimal("2"), Decimal(".02"),
        Decimal("30"), Decimal(".01"), Decimal("100"), Decimal("55"),
        Decimal("1"), Decimal(".5"), Decimal(".5"))
    results = {item.condition_id: item for item in research_condition_results(
        side=TrendSide.LONG, factors=factors, candles=candles,
        local_regime="TRENDING_UP", candidate_score=Decimal("80"))}
    assert results["LEGACY_PRICE_2BAR_PROXY"].passed is True
    assert results["LEGACY_EMA_SPREAD_PROXY"].passed is True
    assert results["ADD_PRICE_SLOPE"].passed is False
    assert results["ADD_EMA_SLOPE"].passed is False
    assert results["ADD_PRICE_SLOPE"].version == "r2-p3-v2"
    assert results["ADD_EMA_SLOPE"].version == "r2-p3-v2"


def test_future_candle_cannot_change_truncated_condition_results() -> None:
    candles = tuple(Candle(index*900, Decimal(index+1), Decimal(index+2), Decimal(index),
                           Decimal(index+1), Decimal("100"), Decimal("100")) for index in range(121))
    factors = IndicatorSnapshot(*(Decimal(value) for value in (110, 100, 90, 2, .02, 30, .01, 100, 55, 1, .5, .5)))
    before = research_condition_results(side=TrendSide.LONG, factors=factors, candles=candles,
                                        local_regime="TRENDING_UP", candidate_score=Decimal("80"))
    future = candles + (Candle(121*900, Decimal("999"), Decimal("1000"), Decimal("998"),
                               Decimal("999"), Decimal("100"), Decimal("100")),)
    after = research_condition_results(side=TrendSide.LONG, factors=factors, candles=future[:-1],
                                       local_regime="TRENDING_UP", candidate_score=Decimal("80"))
    assert before == after
