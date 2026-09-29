#!/usr/bin/env python3
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("research_engine", ROOT / "htx_trend_research_engine.py")
MODULE = importlib.util.module_from_spec(SPEC); assert SPEC and SPEC.loader; sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)


def bars(count=90, start=0, step=900):
    result=[]
    for index in range(count):
        # Trending then a shallow pullback/recovery: suitable for parallel model analysis.
        close=100 + index * .5
        if index == count - 2: close -= 4
        if index == count - 1: close -= 2
        result.append(MODULE.Bar(start + index * step, close - .3, close + .6, close - .7, close, 10, 1000))
    return result


class ResearchEngineTest(unittest.TestCase):
    def setUp(self):
        self.cfg = MODULE.ModelConfig(); self.execution = MODULE.ExecutionAssumptions()

    def test_models_analyze_the_same_bar_without_exclusive_routing(self):
        class StaticModel:
            def __init__(self, name): self.name = name
            def evaluate(self, context, config, execution):
                plan = MODULE.PricePlan(100, 100, 100.02, 95, 110, MODULE.OrderStyle.MARKET, 1, 1.5)
                return [MODULE.ResearchSignal(self.name, context.symbol, context.side, context.bar_time, 1, (self.name,), plan)]
        context = MODULE.SignalContext("BTC-USDT", bars()[-1].ts, bars(), MODULE.Side.LONG, "4h trend aligned")
        signals = MODULE.analyze_models([StaticModel("DONCHIAN_BREAKOUT"), StaticModel("EMA_VWAP_MOMENTUM")],
                                        context, self.cfg, self.execution)
        self.assertEqual(["DONCHIAN_BREAKOUT", "EMA_VWAP_MOMENTUM"], [signal.model for signal in signals])
        self.assertEqual({signal.bar_time for signal in signals}, {context.bar_time})

    def test_donchian_and_fibonacci_have_distinct_executable_price_plans(self):
        breakout = [MODULE.Bar(i * 900, 100 + i * .5 - .1, 100 + i * .5 + .1, 100 + i * .5 - .2,
                                100 + i * .5, 10, 1000) for i in range(90)]
        donchian_context = MODULE.SignalContext("BTC-USDT", breakout[-1].ts, breakout, MODULE.Side.LONG, "4h aligned")
        donchian = MODULE.DonchianBreakoutModel().evaluate(donchian_context, self.cfg, self.execution)
        self.assertEqual(1, len(donchian)); self.assertEqual(MODULE.OrderStyle.MARKET, donchian[0].price_plan.order_style)

        closes = [100 + i * .5 for i in range(70)] + [135 + (i - 70) * .5 for i in range(70, 81)] \
                 + [140 - (i - 80) * .5 for i in range(81, 89)] + [136.7]
        pullback = [MODULE.Bar(i * 900, close - .1, close + .1, close - .2, close, 10, 1000)
                    for i, close in enumerate(closes)]
        fib_context = MODULE.SignalContext("BTC-USDT", pullback[-1].ts, pullback, MODULE.Side.LONG, "4h aligned")
        fibonacci = MODULE.FibonacciPullbackModel().evaluate(fib_context, self.cfg, self.execution)
        self.assertEqual(1, len(fibonacci)); self.assertEqual(MODULE.OrderStyle.LIMIT, fibonacci[0].price_plan.order_style)
        self.assertNotEqual(donchian[0].price_plan.invalidation_price, fibonacci[0].price_plan.invalidation_price)

    def test_market_fill_charges_adverse_slippage_and_same_bar_stop_wins(self):
        signal = MODULE.ResearchSignal("TEST", "BTC-USDT", MODULE.Side.LONG, 0, 1, (),
            MODULE.PricePlan(100,100,100.02,95,110,MODULE.OrderStyle.MARKET,1,1.5))
        fill = MODULE.simulate_entry(signal, MODULE.Bar(900,100,101,99,100,1,1), self.execution)
        self.assertGreater(fill.entry_price, 100)
        trade = MODULE.simulate_exit(fill, [MODULE.Bar(1800,100,111,94,105,1,1)], self.execution)
        self.assertEqual("INITIAL_STOP", trade.exit_reason)
        self.assertLess(trade.net_pnl, 0)

    def test_limit_signal_that_is_not_touched_is_not_filled(self):
        signal = MODULE.ResearchSignal("TEST", "BTC-USDT", MODULE.Side.LONG, 0, 1, (),
            MODULE.PricePlan(100,99,99,95,108,MODULE.OrderStyle.LIMIT,1,1.5))
        self.assertIsNone(MODULE.simulate_entry(signal, MODULE.Bar(900,101,102,100,101,1,1), self.execution))

    def test_walk_forward_keeps_windows_disjoint_and_partial_data_cannot_pass(self):
        windows = MODULE.walk_forward_windows(0, 1_000)
        self.assertEqual(["TRAIN", "VALIDATION", "OUT_OF_SAMPLE"], [item.name for item in windows])
        self.assertEqual(windows[0].end, windows[1].start); self.assertEqual(windows[1].end, windows[2].start)
        partial = MODULE.evaluate_walk_forward(windows, lambda window: [], MODULE.DatasetIntegrity.PARTIAL)
        self.assertEqual("INSUFFICIENT_EVIDENCE", partial["decision"])


if __name__ == '__main__':
    unittest.main()
