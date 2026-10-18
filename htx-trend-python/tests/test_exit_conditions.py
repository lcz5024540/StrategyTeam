from dataclasses import replace
from decimal import Decimal

from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.research.exit_conditions import (ConditionStatus, ExitConditionContext,
                                                ResearchExitAction, evaluate_exit_condition)


def _candidate(model=ModelCode.DONCHIAN_BREAKOUT, side=TrendSide.LONG):
    structure = PriceStructure(Decimal("100"), Decimal("90"), Decimal("105"),
                               Decimal("92"), Decimal("97"), Decimal("100"))
    return ModelCandidate(model, side, 1, Decimal("80"), Decimal("101"), Decimal("90"),
                          Decimal("110"), Decimal("110"), Decimal("1"), structure, ())


def _context(**changes):
    value = ExitConditionContext(
        _candidate(), 20, Decimal("101"), Decimal("100"), Decimal("2"), Decimal("17"),
        Decimal("-0.01"), Decimal("100"), Decimal("101"), Decimal("-0.001"),
        Decimal("-0.001"), Decimal("-1"), Decimal("0.20"), Decimal("0.05"),
        Decimal("1"), Decimal("0.10"), TrendSide.FLAT, 1, "INTRADAY_15M")
    return replace(value, **changes)


def test_no_progress_timeout_is_profile_bounded() -> None:
    result = evaluate_exit_condition("exit.no_progress_timeout", _context())
    assert result.status is ConditionStatus.PASS
    assert result.action is ResearchExitAction.FULL_CLOSE
    assert result.threshold["bars_limit"] == 16


def test_cost_covered_protection_converts_usdt_cost_to_price_distance() -> None:
    result = evaluate_exit_condition(
        "exit.cost_covered_protection",
        _context(observed_mfe_net_pnl=Decimal("0.20"), max_favorable_r=Decimal("0.30")))
    assert result.status is ConditionStatus.PASS
    assert result.action is ResearchExitAction.ARM_PROTECTION
    assert result.protection_price == Decimal("101.1")


def test_trend_decay_requires_two_consecutive_closed_bars() -> None:
    first = evaluate_exit_condition("exit.trend_decay", _context(decay_streak=0))
    second = evaluate_exit_condition("exit.trend_decay", _context(decay_streak=first.next_decay_streak))
    assert first.status is ConditionStatus.FAIL
    assert first.next_decay_streak == 1
    assert second.status is ConditionStatus.PASS
    assert second.action is ResearchExitAction.FULL_CLOSE


def test_trend_decay_reports_unknown_without_slope_warmup() -> None:
    result = evaluate_exit_condition(
        "exit.trend_decay", _context(close_slope20=None, ema_slope10=None))
    assert result.status is ConditionStatus.UNKNOWN
    assert result.action is ResearchExitAction.HOLD


def test_structure_condition_is_model_specific() -> None:
    donchian = evaluate_exit_condition("exit.structure_invalidated", _context(close=Decimal("99")))
    ema = evaluate_exit_condition(
        "exit.structure_invalidated",
        _context(candidate=_candidate(ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION),
                 close=Decimal("100"), ema_slow=Decimal("101")))
    assert donchian.status is ConditionStatus.PASS
    assert ema.status is ConditionStatus.PASS
