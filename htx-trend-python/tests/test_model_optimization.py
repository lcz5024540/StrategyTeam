from dataclasses import replace
from decimal import Decimal

import pytest

from htx_trend.market_data import Candle
from htx_trend.models import ModelCode
from htx_trend.research.model_conditions import model_condition_passes, model_condition_status
from htx_trend.research.model_optimization import MODEL_BASELINES, P4_VARIANTS
from htx_trend.research.profile_backtest import (ProfileLifecycleResearch, ResearchVariant,
                                                 provisional_backtest_admission)
from htx_trend.research.swing_model_optimization import (P5_LEGACY_BASELINES,
                                                         P5_DECISION_WINDOW,
                                                         P5_MODEL_BASELINES, P5_VARIANTS)
from htx_trend.strategy_admission import DecisionSurface
from htx_trend.strategy_decision import HtxStrategyDecisionEngine
from htx_trend.strategy_profiles import profile_strategy
from htx_trend.timeframes import TimeframeProfile


def _trend(step: int, count: int, *, start: int = 0) -> tuple[Candle, ...]:
    rows = []
    for index in range(count):
        close = Decimal("100")+Decimal(index)/Decimal("10")
        rows.append(Candle(start+index*step, close-Decimal("0.05"), close+Decimal("0.10"),
                           close-Decimal("0.10"), close, amount=Decimal("100")+index))
    last = rows[-1]
    rows[-1] = replace(last, high=last.high+Decimal("0.25"), close=last.close+Decimal("0.25"))
    return tuple(rows)


def test_p4_search_budget_is_model_isolated_and_single_variable() -> None:
    assert len(P4_VARIANTS) == 22
    assert {item.models[0].value for item in P4_VARIANTS} == set(MODEL_BASELINES)
    assert all(len(item.models) == 1 for item in P4_VARIANTS)
    for item in P4_VARIANTS:
        changes = bool(item.model_condition)+len(item.entry_parameters)
        expected = 0 if item.variant_id in MODEL_BASELINES.values() else 1
        assert changes == expected


def test_higher_timeframe_condition_uses_only_bars_closed_at_signal_time() -> None:
    candles = _trend(900, 160)
    decision = HtxStrategyDecisionEngine().decide(
        contract_code="BTC-USDT", period="15min", candles=candles,
        surface=DecisionSurface.BACKTEST)
    candidate = next(item for item in decision.candidates
                     if item.model is ModelCode.DONCHIAN_BREAKOUT)
    signal_close = candles[-1].open_time+900
    context = _trend(3600, 140, start=signal_close-140*3600)
    baseline = model_condition_passes(
        "DONCHIAN_60M_ALIGN", condition_version="r2-p4-v1", candidate=candidate,
        decision=decision, entry_candles=candles, context={"60min": context})
    future = Candle(signal_close, Decimal("1"), Decimal("2"), Decimal("0.5"),
                    Decimal("0.6"), amount=Decimal("999999"))
    extended = model_condition_passes(
        "DONCHIAN_60M_ALIGN", condition_version="r2-p4-v1", candidate=candidate,
        decision=decision, entry_candles=candles, context={"60min": context+(future,)})
    assert baseline is True and extended is baseline


def test_p4_condition_versions_and_parameter_names_fail_closed(tmp_path) -> None:
    spec = profile_strategy(TimeframeProfile.INTRADAY_15M, read_env=False)
    runner = object.__new__(ProfileLifecycleResearch)
    runner.spec = spec
    assert runner._variant_config(ResearchVariant(
        "LOOKBACK", entry_parameters=(("breakout_lookback", 16),))).breakout_lookback == 16
    with pytest.raises(ValueError, match="unknown entry parameters"):
        runner._variant_config(ResearchVariant("BAD", entry_parameters=(("not_a_parameter", 1),)))

    candles = _trend(900, 160)
    decision = HtxStrategyDecisionEngine().decide(
        contract_code="BTC-USDT", period="15min", candles=candles,
        surface=DecisionSurface.BACKTEST)
    candidate = next(item for item in decision.candidates
                     if item.model is ModelCode.DONCHIAN_BREAKOUT)
    with pytest.raises(ValueError, match="version mismatch"):
        model_condition_passes(
            "DONCHIAN_BODY_RATIO_055", condition_version="wrong", candidate=candidate,
            decision=decision, entry_candles=candles, context={})


def test_p5_search_is_model_isolated_and_only_uses_qualified_confirmations() -> None:
    assert len(P5_VARIANTS) == 18
    assert all(len(item.models) == 1 for item in P5_VARIANTS)
    assert {item.models[0].value for item in P5_VARIANTS} == set(P5_MODEL_BASELINES)
    selectable = [item for item in P5_VARIANTS
                  if item.variant_id not in {*P5_MODEL_BASELINES.values(),
                                             *P5_LEGACY_BASELINES.values()}]
    for item in selectable:
        assert item.model_condition in {
            "DONCHIAN_1D_TREND", "FIB_1D_TREND", "EMA_1D_TREND"}
        assert bool(item.confirmation)+len(item.entry_parameters) == 1
    confirmations = {(item.models[0].value, item.confirmation)
                     for item in selectable if item.confirmation}
    assert confirmations == {
        (ModelCode.FIB_PULLBACK_CONTINUATION.value, "MACD"),
        (ModelCode.EMA_MOMENTUM_CONTINUATION.value, "PRICE_SLOPE"),
    }
    assert max(dict(item.entry_parameters).get("breakout_lookback", 0)
               for item in P5_VARIANTS)+5 <= P5_DECISION_WINDOW


def test_research_rejects_parameter_that_exceeds_decision_window(tmp_path) -> None:
    spec = profile_strategy(TimeframeProfile.SWING_4H, read_env=False)
    runner = object.__new__(ProfileLifecycleResearch)
    runner.spec, runner.window = spec, 160
    config = runner._variant_config(ResearchVariant(
        "TOO_LONG", entry_parameters=(("breakout_lookback", 168),)))
    with pytest.raises(ValueError, match="requires 173 bars"):
        runner._validate_config_window("TOO_LONG", config)


def test_p5_daily_context_is_closed_bar_only_and_missing_is_unknown() -> None:
    candles = _trend(14400, 160)
    spec = profile_strategy(TimeframeProfile.SWING_4H, read_env=False)
    decision = HtxStrategyDecisionEngine(
        strategy_version=spec.strategy.version, config=spec.entry_config,
        admission=provisional_backtest_admission(spec, "0"*64)).decide(
        contract_code="BTC-USDT", period="4hour", candles=candles,
        surface=DecisionSurface.BACKTEST)
    candidate = next(item for item in decision.candidates
                     if item.model is ModelCode.DONCHIAN_BREAKOUT)
    signal_close = candles[-1].open_time+14400
    context = _trend(86400, 140, start=signal_close-140*86400)
    status = model_condition_status(
        "DONCHIAN_1D_TREND", condition_version="r2-p5-v1", candidate=candidate,
        decision=decision, entry_candles=candles, context={"1day": context})
    future = Candle(signal_close, Decimal("1"), Decimal("2"), Decimal("0.5"),
                    Decimal("0.6"), amount=Decimal("999999"))
    extended = model_condition_status(
        "DONCHIAN_1D_TREND", condition_version="r2-p5-v1", candidate=candidate,
        decision=decision, entry_candles=candles, context={"1day": context+(future,)})
    missing = model_condition_status(
        "DONCHIAN_1D_TREND", condition_version="r2-p5-v1", candidate=candidate,
        decision=decision, entry_candles=candles, context={})
    assert status == "PASS" and extended == status
    assert missing == "UNKNOWN"
