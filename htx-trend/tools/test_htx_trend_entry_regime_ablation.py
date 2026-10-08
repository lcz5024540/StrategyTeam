import importlib.util
import sys
import unittest
from dataclasses import replace
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "htx-trend-python" / "src"))

from htx_trend.java_entry_contract import JavaTrendSignal
from htx_trend.models import TrendSide

SPEC = importlib.util.spec_from_file_location("entry_regime_ablation", Path(__file__).with_name("htx_trend_entry_regime_ablation.py"))
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class EntryRegimeAblationTest(unittest.TestCase):
    def signal(self, side, regime):
        return JavaTrendSignal(side, 1, Decimal("100"), None, None, None, "test", regime,
                               Decimal("70"), Decimal("70"), Decimal("0"), Decimal("70"), Decimal("1"))

    def test_relaxed_regime_changes_only_the_common_regime_precondition(self):
        signal = self.signal(TrendSide.LONG, "RANGE")
        result = MODULE.relaxed_regime(signal)
        self.assertEqual("TRENDING_UP", result.regime)
        self.assertEqual(signal.side, result.side)
        self.assertEqual(signal.score, result.score)
        self.assertEqual(signal.indicators, result.indicators)

    def test_flat_signal_is_not_promoted_by_regime_ablation(self):
        signal = self.signal(TrendSide.FLAT, "RANGE")
        self.assertEqual(signal, MODULE.relaxed_regime(signal))

    def test_metrics_include_cost_adjusted_candidate_quality(self):
        metrics = MODULE.Metrics()
        metrics.add(1.5)
        metrics.add(-0.5)
        report = metrics.report()
        self.assertEqual(2, report["candidateCount"])
        self.assertEqual(0.5, report["winRate"])
        self.assertEqual(3.0, report["profitFactor"])
        self.assertTrue(report["feeIncluded"])


if __name__ == "__main__":
    unittest.main()
