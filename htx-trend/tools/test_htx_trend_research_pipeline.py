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

    def test_manifest_epoch_accepts_explicit_and_legacy_shanghai_timestamps(self):
        self.assertEqual(
            MODULE._manifest_epoch("2026-03-29T00:00:00+08:00"),
            MODULE._manifest_epoch("2026-03-29T00:00:00"),
        )

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

    def test_rolling_feature_cache_matches_model_indicator_definitions(self):
        bars = [MODULE.Bar(index, 100 + index, 102 + index + (index % 3), 99 + index - (index % 2),
                           100.5 + index, 10 + index, 1000 + index * 10) for index in range(100)]
        config = MODULE.ModelConfig()
        features = MODULE._rolling_entry_features(bars, config)
        for index in (1, 13, 14, 60, 99):
            completed = bars[:index + 1]
            closes = [bar.close for bar in completed]
            self.assertAlmostEqual(MODULE.ema(closes, config.fast_ema), features["fast"][index])
            self.assertAlmostEqual(MODULE.ema(closes, config.slow_ema), features["slow"][index])
            self.assertAlmostEqual(MODULE.adx(completed, config.atr_period), features["adx"][index])
            true_ranges = [max(abs(bar.high - bar.low), abs(bar.high - previous.close), abs(bar.low - previous.close))
                           for previous, bar in zip(completed, completed[1:])]
            self.assertAlmostEqual(sum(true_ranges[-config.atr_period:]) / len(true_ranges[-config.atr_period:])
                                   if true_ranges else 0.0, features["atr"][index])
            window = completed[-config.vwap_window:]
            expected_vwap = sum(((bar.high + bar.low + bar.close) / 3) * (bar.turnover or bar.amount) for bar in window) / \
                sum(bar.turnover or bar.amount for bar in window)
            self.assertAlmostEqual(expected_vwap, features["vwap"][index])


if __name__ == "__main__":
    unittest.main()
