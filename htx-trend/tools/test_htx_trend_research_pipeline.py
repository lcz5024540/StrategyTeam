import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("research_pipeline", ROOT / "htx_trend_research_pipeline.py")
MODULE = importlib.util.module_from_spec(SPEC); assert SPEC and SPEC.loader; sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)


class ResearchPipelineTest(unittest.TestCase):
    def test_rejects_partial_manifest_before_loading_any_market_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = root / "dataset-manifest.json"
            manifest.write_text(json.dumps({"schema": "htx-trend-research-dataset-manifest-v1", "inputIntegrity": "PARTIAL"}))
            with self.assertRaisesRegex(ValueError, "COMPLETE"):
                MODULE.build_ledger(root, manifest)

    def test_same_bar_label_prefers_stop_to_avoid_lookahead_profit(self):
        bar = MODULE.Bar(1, 100, 111, 94, 101, 1, 1)
        barrier, timestamp, ambiguity = MODULE._barrier(MODULE.Side.LONG, 100, 95, 105, 110, [bar])
        self.assertEqual(("STOP", 1, "same_bar_stop_first"), (barrier, timestamp, ambiguity))

    def test_evaluation_needs_sufficient_comparable_samples_before_promotion(self):
        rows = [{"netProfit": 1.0, "estimatedEntryFee": .1, "estimatedExitFee": .1} for _ in range(29)]
        result = MODULE._evaluation(rows, "research:test", "MODEL", "OUT_OF_SAMPLE")
        self.assertEqual("INSUFFICIENT_SAMPLE", result["decision"])

    def test_train_and_validation_cannot_promote_even_when_profitable(self):
        rows = [{"netProfit": 1.0, "estimatedEntryFee": .1, "estimatedExitFee": .1} for _ in range(30)]
        result = MODULE._evaluation(rows, "research:test", "MODEL", "TRAIN")
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["decision"])

    def test_cross_sectional_breadth_uses_only_completed_non_flat_symbols(self):
        up = [MODULE.Bar(index, 1, 1, 1, 100 + index, 1, 1) for index in range(70)]
        down = [MODULE.Bar(index, 1, 1, 1, 200 - index, 1, 1) for index in range(70)]
        breadth = MODULE._market_breadth({
            "UP-USDT": {MODULE.ENTRY_PERIOD: up}, "DOWN-USDT": {MODULE.ENTRY_PERIOD: down}})
        self.assertEqual((0.5, 2), breadth[69])


if __name__ == "__main__":
    unittest.main()
