from datetime import datetime, timezone
from decimal import Decimal

from htx_trend.exits import ExitAction, ExitInput, ExitPhase, decide, decide_v4
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
    decision = decide(input_)
    assert decision.action is ExitAction.FULL_CLOSE
    assert decision.reason == "legacy-atr-trailing-stop"


def test_legacy_short_trail_stays_above_best_low() -> None:
    decision = decide(base(primary_model=ModelCode.DONCHIAN_BREAKOUT, side=TrendSide.SHORT, entry_price=Decimal("100"),
                            initial_stop_price=Decimal("105"), invalidation_price=Decimal("106"), reference_price=Decimal("94"),
                            highest_price=Decimal("101"), lowest_price=Decimal("90"), signal_side=TrendSide.SHORT,
                            ema_fast=Decimal("95"), ema_slow=Decimal("98")))
    assert decision.protection_price == Decimal("94")
