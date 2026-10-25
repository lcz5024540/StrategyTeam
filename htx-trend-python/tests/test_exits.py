from datetime import datetime, timezone
from decimal import Decimal

from htx_trend.exits import ExitAction, ExitInput, ExitPhase, decide, decide_v4, decide_legacy
from htx_trend.models import ModelCode, TrendSide


def base(**changes) -> ExitInput:
    values = dict(primary_model=ModelCode.FIB_PULLBACK_CONTINUATION, side=TrendSide.LONG, remaining_contracts=10,
                  contract_size=Decimal("1"), entry_price=Decimal("100"), initial_stop_price=Decimal("95"),
                  invalidation_price=Decimal("94"), structure_target_price=Decimal("110"), reference_price=Decimal("104"),
                  protection_price=None, highest_price=Decimal("106"), lowest_price=Decimal("99"), atr=Decimal("2"),
                  signal_side=TrendSide.LONG, ema_fast=Decimal("105"), ema_slow=Decimal("102"), major_timeframe_opposition=0,
                  market_context_opposes=False, current_net_pnl=Decimal("35"), entry_fee=Decimal("1"), estimated_close_fee=Decimal("1"),
                  trailing_armed_at=None, trailing_arm_reason=None, max_favorable_r=None,
                  evaluated_at=datetime(2026, 1, 1, tzinfo=timezone.utc))
    values.update(changes)
    return ExitInput(**values)


def test_v4_arms_durable_profit_protection_after_one_r() -> None:
    decision = decide_v4(base())
    assert decision.action is ExitAction.ARM_TRAILING
    assert decision.trailing_armed_at is not None
    assert decision.phase is ExitPhase.RUNNER


def test_v4_hard_invalidation_has_priority_over_profit_protection() -> None:
    decision = decide_v4(base(reference_price=Decimal("94"), highest_price=Decimal("106")))
    assert decision.action is ExitAction.FULL_CLOSE
    assert decision.phase is ExitPhase.INVALIDATED


def test_v4_closes_a_weakening_runner_that_breaks_protection() -> None:
    armed = datetime(2026, 1, 1, tzinfo=timezone.utc)
    decision = decide_v4(base(reference_price=Decimal("100"), highest_price=Decimal("106"), signal_side=TrendSide.FLAT,
                               ema_fast=Decimal("100"), ema_slow=Decimal("102"), trailing_armed_at=armed,
                               trailing_arm_reason="RISK_REWARD_MILESTONE", protection_price=Decimal("101")))
    assert decision.action is ExitAction.FULL_CLOSE
    assert decision.phase is ExitPhase.REVERSAL_CONFIRMED


def test_v4_flat_loss_control_needs_an_independent_failure() -> None:
    decision = decide_v4(base(current_net_pnl=Decimal("-2"), signal_side=TrendSide.FLAT, ema_fast=Decimal("100"),
                               ema_slow=Decimal("102"), flat_streak=3, flat_loss_budget=Decimal("1"), highest_price=Decimal("100")))
    assert decision.phase is ExitPhase.THESIS_EXPIRED
    assert decision.action is ExitAction.FULL_CLOSE


def test_legacy_models_keep_their_own_atr_exit_path() -> None:
    input_ = base(primary_model=ModelCode.DONCHIAN_BREAKOUT, reference_price=Decimal("102"), highest_price=Decimal("110"),
                  protection_price=Decimal("107"), trailing_armed_at=datetime(2026, 1, 1, tzinfo=timezone.utc))
    decision = decide_legacy(input_, trailing_atr_multiplier=Decimal("2"), trailing_activation_r=Decimal("1"))
    assert decision.action is ExitAction.FULL_CLOSE
    assert decision.reason == "legacy-atr-trailing-stop"


def test_legacy_short_trail_stays_above_best_low() -> None:
    decision = decide_legacy(base(primary_model=ModelCode.DONCHIAN_BREAKOUT, side=TrendSide.SHORT, entry_price=Decimal("100"),
                            initial_stop_price=Decimal("105"), invalidation_price=Decimal("106"), reference_price=Decimal("94"),
                            highest_price=Decimal("101"), lowest_price=Decimal("90"), signal_side=TrendSide.SHORT,
                            ema_fast=Decimal("95"), ema_slow=Decimal("98")), trailing_atr_multiplier=Decimal("2"), trailing_activation_r=Decimal("1"))
    assert decision.protection_price == Decimal("94")


# MAINLINE-A A2 timeout exit tests

def _unarmed(**changes):
    """Return a base input that has not armed profit protection / continuation."""
    values = dict(entry_price=Decimal("100"), reference_price=Decimal("99"),
                  highest_price=Decimal("100"), lowest_price=Decimal("99"),
                  current_net_pnl=Decimal("-2"), protection_price=None,
                  trailing_armed_at=None, trailing_arm_reason=None,
                  max_favorable_r=None, signal_side=TrendSide.LONG,
                  ema_fast=Decimal("99"), ema_slow=Decimal("98"))
    values.update(changes)
    return base(**values)


def test_a2_timeout_triggers_full_close_when_unarmed_and_bars_held_reaches_t_max() -> None:
    decision = decide_v4(_unarmed(bars_held=16, timeout_bars=16))
    assert decision.action is ExitAction.FULL_CLOSE
    assert decision.phase is ExitPhase.THESIS_EXPIRED
    assert "a2-thesis-timeout" in decision.reason


def test_a2_timeout_does_not_trigger_before_t_max() -> None:
    decision = decide_v4(_unarmed(bars_held=15, timeout_bars=16))
    assert decision.action is not ExitAction.FULL_CLOSE
    assert "a2-thesis-timeout" not in decision.reason


def test_a2_timeout_is_suppressed_when_profit_protection_is_armed() -> None:
    armed = datetime(2026, 1, 1, tzinfo=timezone.utc)
    decision = decide_v4(_unarmed(bars_held=20, timeout_bars=16, trailing_armed_at=armed,
                                  trailing_arm_reason="RISK_REWARD_MILESTONE"))
    assert decision.action is not ExitAction.FULL_CLOSE
    assert "a2-thesis-timeout" not in decision.reason


def test_a2_timeout_disabled_when_timeout_bars_is_zero() -> None:
    decision = decide_v4(_unarmed(bars_held=100, timeout_bars=0))
    assert decision.action is not ExitAction.FULL_CLOSE
    assert "a2-thesis-timeout" not in decision.reason


def test_a2_timeout_never_delays_hard_stop() -> None:
    decision = decide_v4(_unarmed(reference_price=Decimal("94"), highest_price=Decimal("106"),
                                  initial_stop_price=Decimal("95"), invalidation_price=Decimal("94"),
                                  bars_held=100, timeout_bars=16))
    assert decision.action is ExitAction.FULL_CLOSE
    assert decision.phase is ExitPhase.INVALIDATED
    assert "a2-thesis-timeout" not in decision.reason


# MAINLINE-A A1' minimum hold tests (default off; hard stop never delayed)

def test_a1_prime_default_off_does_not_suppress_soft_exit() -> None:
    decision = decide_v4(base(current_net_pnl=Decimal("-2"), signal_side=TrendSide.FLAT, ema_fast=Decimal("100"),
                               ema_slow=Decimal("102"), flat_streak=3, flat_loss_budget=Decimal("1"), highest_price=Decimal("100"),
                               bars_held=1, min_hold_bars=0))
    assert decision.action is ExitAction.FULL_CLOSE


def test_a1_prime_delays_flat_loss_but_not_hard_stop() -> None:
    soft = decide_v4(base(current_net_pnl=Decimal("-2"), signal_side=TrendSide.FLAT, ema_fast=Decimal("100"),
                          ema_slow=Decimal("102"), flat_streak=3, flat_loss_budget=Decimal("1"), highest_price=Decimal("100"),
                          bars_held=1, min_hold_bars=4))
    assert soft.action is ExitAction.HOLD
    assert "min-hold-suppressed" in soft.reason
    hard = decide_v4(base(reference_price=Decimal("94"), highest_price=Decimal("106"),
                        bars_held=1, min_hold_bars=4))
    assert hard.action is ExitAction.FULL_CLOSE
    assert hard.phase is ExitPhase.INVALIDATED
