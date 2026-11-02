"""Frozen accepted predecessor calculations; no Git or future data needed at test time."""

import importlib.util
import sys
from pathlib import Path
from dataclasses import asdict
from decimal import Decimal as D
from datetime import UTC, datetime
import random

from htx_trend.exits import ExitInput, decide_v4, decide_legacy
from htx_trend.models import ModelCode, TrendSide


def test_existing_models_match_frozen_predecessor_for_600_fixed_inputs():
    name = "htx_trend._realtime_test_predecessor"
    spec = importlib.util.spec_from_file_location(
        name, Path(__file__).parent / "fixtures/realtime/exits_eebde1cf.py"
    )
    old = importlib.util.module_from_spec(spec)
    sys.modules[name] = old
    spec.loader.exec_module(old)
    randomizer = random.Random(20261102)
    try:
        for _ in range(600):
            long = randomizer.choice((True, False))
            entry = D(100)
            now = datetime(2026, 11, 2, tzinfo=UTC)
            args = dict(
                primary_model=randomizer.choice(tuple(ModelCode)),
                side=TrendSide.LONG if long else TrendSide.SHORT,
                remaining_contracts=randomizer.choice((1, 3, 10)),
                contract_size=D(".01"),
                entry_price=entry,
                initial_stop_price=D(90 if long else 110),
                invalidation_price=D(90 if long else 110),
                structure_target_price=D(120 if long else 80),
                reference_price=D(randomizer.randrange(85, 116)),
                protection_price=D(102 if long else 98),
                highest_price=D(115),
                lowest_price=D(85),
                atr=D(3),
                signal_side=randomizer.choice(tuple(TrendSide)),
                ema_fast=D(randomizer.choice((99, 101))),
                ema_slow=D(100),
                major_timeframe_opposition=randomizer.randrange(3),
                market_context_opposes=randomizer.choice((True, False)),
                current_net_pnl=D(randomizer.randrange(-10, 20)) / 10,
                entry_fee=D(".01"),
                estimated_close_fee=D(".01"),
                trailing_armed_at=randomizer.choice((now, None)),
                trailing_arm_reason=None,
                max_favorable_r=D("1.5"),
                evaluated_at=now,
                flat_streak=randomizer.randrange(5),
                protection_breach_bars=randomizer.randrange(4),
                bars_held=randomizer.randrange(30),
                timeout_bars=16,
                min_hold_bars=0,
            )
            current = ExitInput(**args)
            before = old.ExitInput(**args)
            assert asdict(decide_v4(current)) == asdict(old.decide_v4(before))
            kwargs = dict(trailing_atr_multiplier=D(2), trailing_activation_r=D(1))
            assert asdict(decide_legacy(current, **kwargs)) == asdict(
                old.decide_legacy(before, **kwargs)
            )
    finally:
        sys.modules.pop(name, None)
