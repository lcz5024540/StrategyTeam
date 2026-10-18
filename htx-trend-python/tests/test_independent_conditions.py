from dataclasses import replace
from decimal import Decimal

from htx_trend.market_data import Candle
from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.research.independent_conditions import (
    daily_trend_persistence_decisions,
    daily_trend_persistence_status,
    donchian_retest_reclaim_decisions,
)
from htx_trend.strategy_admission import DecisionSurface
from htx_trend.strategy_decision import HtxStrategyDecisionEngine


def _candle(time: int, close: str, *, low: str | None = None,
            high: str | None = None) -> Candle:
    value = Decimal(close)
    return Candle(time, value, Decimal(high or close), Decimal(low or close), value,
                  Decimal("100"), Decimal("100"), Decimal("100"), 1)


def _decision(candle: Candle, candidate: ModelCandidate | None):
    engine = HtxStrategyDecisionEngine()
    candles = tuple(_candle(candle.open_time-900*(159-index), str(100+index/100))
                    for index in range(159))+(candle,)
    decision = engine.decide(contract_code="BTC-USDT", period="15min", candles=candles,
                             surface=DecisionSurface.BACKTEST)
    return replace(decision, candidates=() if candidate is None else (candidate,))


def _candidate(time: int, side: TrendSide = TrendSide.LONG) -> ModelCandidate:
    structure = PriceStructure(*(Decimal(value) for value in ("105", "95", "106", "94", "98", "102")))
    return ModelCandidate(ModelCode.DONCHIAN_BREAKOUT, side, time, Decimal("80"), Decimal("101"),
                          Decimal("95") if side is TrendSide.LONG else Decimal("105"),
                          Decimal("110") if side is TrendSide.LONG else Decimal("90"),
                          Decimal("110") if side is TrendSide.LONG else Decimal("90"),
                          Decimal("1.5"), structure, ("donchian_breakout",),
                          structure_key=f"breakout:{time}")


def test_retest_reclaim_uses_frozen_boundary_and_delays_signal() -> None:
    bars = (_candle(0, "101", low="100", high="102"),
            _candle(900, "100.5", low="99.8", high="102"),
            _candle(1800, "101.2", low="99.9", high="102"),
            _candle(2700, "103", low="102", high="104"))
    first = _decision(bars[0], _candidate(0))
    boundary_signal = replace(first.signal, breakout_high=Decimal("100"), breakout_low=Decimal("96"))
    decisions = (replace(first, signal=boundary_signal),
                 _decision(bars[1], None), _decision(bars[2], None), _decision(bars[3], None))
    transformed, traces = donchian_retest_reclaim_decisions(bars, decisions)
    assert transformed[0].candidates == ()
    assert transformed[1].candidates[0].bar_time == 900
    assert transformed[1].candidates[0].source_breakout_time == 0
    assert transformed[1].candidates[0].entry_price == Decimal("100.5")
    assert traces[0].status == "PASS"


def test_retest_reclaim_history_does_not_change_when_future_bar_is_appended() -> None:
    bars = (_candle(0, "101"), _candle(900, "100.5", low="99.8", high="102"))
    first = _decision(bars[0], _candidate(0))
    decisions = (replace(first, signal=replace(first.signal, breakout_high=Decimal("100"))),
                 _decision(bars[1], None))
    before = donchian_retest_reclaim_decisions(bars, decisions)
    future = _candle(1800, "50", low="40", high="60")
    after = donchian_retest_reclaim_decisions(
        bars+(future,), decisions+(_decision(future, None),))
    assert before[0] == after[0][:2]
    assert before[1] == after[1][:len(before[1])]


def test_retest_reclaim_invalidates_before_reclaim() -> None:
    bars = (_candle(0, "101"), _candle(900, "96", low="94", high="101"),
            _candle(1800, "101", low="99", high="102"))
    first = _decision(bars[0], _candidate(0))
    decisions = (replace(first, signal=replace(first.signal, breakout_high=Decimal("100"))),
                 _decision(bars[1], None), _decision(bars[2], None))
    transformed, traces = donchian_retest_reclaim_decisions(bars, decisions)
    assert all(not item.candidates for item in transformed if item is not None)
    assert traces[0].status == "INVALIDATED"


def test_daily_persistence_ignores_future_bars_and_requires_three_prefixes() -> None:
    daily = tuple(_candle(index*86_400, str(100+index), low=str(99+index), high=str(101+index))
                  for index in range(130))
    close = daily[124].open_time+86_400
    before = daily_trend_persistence_status(daily, signal_close=close, side=TrendSide.LONG)
    after = daily_trend_persistence_status(
        daily+(_candle(999*86_400, "1", low="0.5", high="2"),),
        signal_close=close, side=TrendSide.LONG)
    assert before == after
    assert before[0] == "PASS"
    assert len(before[1]) == 3


def test_daily_persistence_is_unknown_without_indicator_warmup() -> None:
    daily = tuple(_candle(index*86_400, str(100+index)) for index in range(122))
    assert daily_trend_persistence_status(
        daily, signal_close=10**9, side=TrendSide.LONG) == ("UNKNOWN", ())


def test_daily_persistence_decisions_store_unknown_trace_and_filter_candidate() -> None:
    bar = _candle(200*86_400, "200")
    base = _decision(bar, replace(
        _candidate(bar.open_time), model=ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION))
    daily = tuple(_candle(index*86_400, str(100+index)) for index in range(10))
    transformed, traces = daily_trend_persistence_decisions((bar,), (base,), daily)
    assert transformed[0].candidates == ()
    assert traces[0].status == "UNKNOWN"
    assert traces[0].daily_evidence == ()
