import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).parent; sys.path.insert(0, str(ROOT))
for name in ('htx_trend_research_engine', 'portfolio_simulator'):
    spec = importlib.util.spec_from_file_location(name, ROOT / ('htx_trend_research_engine.py' if name.startswith('htx_') else 'htx_trend_portfolio_simulator.py'))
    module = importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[name] = module; spec.loader.exec_module(module)
ENGINE, MODULE = sys.modules['htx_trend_research_engine'], sys.modules['portfolio_simulator']


def candidate(symbol='BTC-USDT', score=90, style=None, notional=100, cluster='BTC'):
    plan = ENGINE.PricePlan(100, 100, 100.02, 95, 110, style or ENGINE.OrderStyle.MARKET, 1, 1.5)
    signal = ENGINE.ResearchSignal('TEST', symbol, ENGINE.Side.LONG, 1, score, (), plan)
    return MODULE.CandidateOrder(signal, notional, cluster)


class PortfolioSimulatorTest(unittest.TestCase):
    def test_market_order_can_partially_fill_against_bounded_liquidity(self):
        fill = MODULE.simulate_entry(candidate(), ENGINE.Bar(2, 100, 101, 99, 100, 1, 1), 40, MODULE.ExecutionCostModel())
        self.assertEqual(('PARTIALLY_FILLED', 40, 60), (fill.status, fill.filled_notional, fill.unfilled_notional))
        self.assertGreater(fill.entry_price, 100)

    def test_limit_order_not_touched_remains_unfilled(self):
        fill = MODULE.simulate_entry(candidate(style=ENGINE.OrderStyle.LIMIT), ENGINE.Bar(2, 102, 103, 101, 102, 1, 1), 100, MODULE.ExecutionCostModel())
        self.assertEqual(('UNFILLED', 'limit_not_touched'), (fill.status, fill.reason))

    def test_funding_direction_and_missing_evidence_are_explicit(self):
        self.assertEqual(-.1, MODULE.funding_pnl(ENGINE.Side.LONG, 100, .001, 1))
        self.assertEqual(.1, MODULE.funding_pnl(ENGINE.Side.SHORT, 100, .001, 1))
        self.assertIsNone(MODULE.funding_pnl(ENGINE.Side.LONG, 100, None, 1))

    def test_portfolio_limits_prevent_correlated_duplicate_exposure(self):
        values = MODULE.select_portfolio([candidate('BTC-USDT', 100, cluster='L1'), candidate('ETH-USDT', 90, cluster='L1'), candidate('BTC-USDT', 80, cluster='L2')], MODULE.PortfolioLimits(3, 1, 300))
        self.assertEqual([True, False, False], [item.accepted for item in values])
        self.assertEqual(['cluster_limit', 'duplicate_symbol'], [item.reason for item in values[1:]])


if __name__ == '__main__': unittest.main()
