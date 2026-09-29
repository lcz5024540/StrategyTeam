#!/usr/bin/env python3
"""Regression tests for the multi-timeframe profitability research gate."""

import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("htx_mtf_profit", ROOT / "htx_multitimeframe_profitability_backtest.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def bars(period: int, values: list[float]):
    return [MODULE.Bar(index * period, value, value + 1, value - 1, value, 10, 100) for index, value in enumerate(values)]


class MultiTimeframeProfitabilityBacktestTest(unittest.TestCase):
    def test_direction_excludes_an_unfinished_future_candle(self):
        days = bars(MODULE.SECONDS["1day"], [100 + index for index in range(63)] + [1])
        as_of = 63 * MODULE.SECONDS["1day"]
        self.assertEqual("LONG", MODULE.direction(days, "1day", as_of))

    def test_price_plan_charges_both_legs_before_reporting_net_r(self):
        target, net_r = MODULE.price_plan("LONG", 100.0, 90.0, MODULE.Config())
        self.assertEqual(120.0, target)
        self.assertLess(net_r, 2.0)
        self.assertGreater(net_r, 1.9)

    def test_daily_fallback_omits_an_incomplete_four_hour_day(self):
        four_hours = MODULE.SECONDS["4hour"]
        values = [MODULE.Bar(-8 * 3600 + index * four_hours, 100 + index, 101 + index, 99 + index,
                             100 + index, 10, 100) for index in range(7)]
        daily = MODULE.derive_daily_from_4hour(values)
        self.assertEqual(1, len(daily))
        self.assertEqual(100, daily[0].open)
        self.assertEqual(105, daily[0].close)

    def test_model_cannot_pass_without_positive_after_fee_expectancy(self):
        config = MODULE.Config(min_model_trades=3)
        self.assertEqual("REJECT", MODULE.decision({
            "trades": 3, "net_pnl": -0.01, "profit_factor": 1.5, "expectancy": -0.01, "win_rate": .8, "max_loss": -.1,
        }, config))
        self.assertEqual("PAPER_CANDIDATE", MODULE.decision({
            "trades": 3, "net_pnl": 1.0, "profit_factor": 1.2, "expectancy": .3, "win_rate": .6, "max_loss": -.5,
        }, config))

    def test_negative_small_sample_is_rejected_not_presented_as_promising(self):
        self.assertEqual("REJECT", MODULE.decision({
            "trades": 1, "net_pnl": -.1, "profit_factor": .8, "expectancy": -.1, "win_rate": 0, "max_loss": -.1,
        }, MODULE.Config()))

    def test_partial_input_cannot_promote_or_reject_a_model_as_conclusive(self):
        self.assertEqual("INSUFFICIENT_EVIDENCE", MODULE.decision({
            "trades": 20, "net_pnl": 4.0, "profit_factor": 1.8, "expectancy": .2, "win_rate": .6, "max_loss": -1.0,
        }, MODULE.Config(), "PARTIAL"))


if __name__ == "__main__":
    unittest.main()
