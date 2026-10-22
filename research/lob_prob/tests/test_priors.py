"""排队竞速先验，以及可选的 logit 加性失衡倾斜。"""
from decimal import Decimal

import pytest

from priors import (
    apply_logit_tilt,
    contemporaneous_return_residual,
    queue_race_p_up,
    tilt_from_residual,
    validate_tilt_window,
)


def test_queue_race_is_bid_share():
    assert queue_race_p_up(3, 1) == Decimal("0.75")
    assert queue_race_p_up(0, 5) == Decimal("0")
    assert queue_race_p_up(5, 0) == Decimal("1")


def test_queue_race_undefined_when_both_sides_empty_or_negative():
    with pytest.raises(ValueError):
        queue_race_p_up(0, 0)
    with pytest.raises(ValueError):
        queue_race_p_up(-1, 2)


def test_imbalance_tilt_is_logit_additive():
    tilted = apply_logit_tilt(Decimal("0.5"), Decimal(3).ln())
    assert abs(tilted - Decimal("0.75")) < Decimal("1e-24")
    assert apply_logit_tilt(Decimal("0.25"), Decimal("0")) == Decimal("0.25")


def test_tilt_uses_supplied_scale_on_contemporaneous_residual():
    residual = contemporaneous_return_residual(Decimal("5"), Decimal("2"))
    assert residual == Decimal("3")
    assert tilt_from_residual(residual, Decimal("0.1")) == Decimal("0.3")


def test_tilt_window_is_only_one_five_or_fifteen_minutes():
    for window in (1, 5, 15):
        assert validate_tilt_window(window) == window
    with pytest.raises(ValueError):
        validate_tilt_window(10)
