import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("ghmm_regime", ROOT / "htx_trend_regime_ghmm.py")
MODULE = importlib.util.module_from_spec(SPEC); assert SPEC and SPEC.loader; sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)


def _write_dataset(root: Path, integrity="COMPLETE") -> Path:
    contracts = ["A-USDT", "B-USDT", "C-USDT", "D-USDT"]
    inputs = []
    for number, contract in enumerate(contracts):
        rows = []
        price = 100 + number
        for index in range(260):
            # Four repeatable market phases, with a high-volatility interval.
            phase = index // 65
            drift = (.003, -.003, .0001, .001)[phase]
            shock = (.0002 if index % 2 else -.0002) * (8 if phase == 2 else 1)
            price *= 1 + drift + shock + number * .000001
            rows.append({"id": index, "open": price * .999, "high": price * 1.002,
                         "low": price * .998, "close": price, "amount": 10 + index,
                         "vol": 1000 + index, "ts": 1_000_000 + index * 900})
        (root / (contract + "_15min.json")).write_text(json.dumps({"data": rows}))
        inputs.append({"contract": contract, "period": "15min", "file": contract + "_15min.json",
                       "sourceKind": "RAW_PUBLIC", "coverage": "COMPLETE"})
    manifest = {"schema": "htx-trend-research-dataset-manifest-v1", "datasetKey": "research:test-ghmm",
                "sourceKind": "FROZEN_PUBLIC_KLINE", "inputIntegrity": integrity,
                "contracts": contracts, "periods": ["15min"], "inputs": inputs}
    path = root / "dataset-manifest.json"; path.write_text(json.dumps(manifest)); return path


class GhmmRegimeTest(unittest.TestCase):
    def test_requires_complete_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest = _write_dataset(root, "PARTIAL")
            with self.assertRaisesRegex(ValueError, "COMPLETE"):
                MODULE.build_report(root, manifest)

    def test_emits_causal_state_probabilities_and_unique_business_labels(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest = _write_dataset(root)
            report = MODULE.build_report(root, manifest, MODULE.GhmmConfig(max_iterations=12))
            self.assertEqual("RESEARCH", report["decisionBoundary"])
            self.assertEqual(["VALIDATION", "OUT_OF_SAMPLE"], [fold["name"] for fold in report["folds"]])
            final = report["folds"][-1]
            self.assertEqual({"TREND_UP", "TREND_DOWN", "RANGE", "HIGH_VOL_RISK"}, set(final["stateMapping"].values()))
            self.assertTrue(final["records"])
            self.assertIn(final["stateHealth"]["decision"], {"PASS", "NEEDS_CALIBRATION"})
            self.assertEqual(set(final["stateCounts"]), set(final["stateHealth"]["stateShares"]))
            self.assertEqual(set(final["stateCounts"]), set(final["stateParameters"]))
            for record in final["records"]:
                self.assertAlmostEqual(1.0, sum(record["regimeProbabilities"].values()), places=8)
                self.assertAlmostEqual(1.0, sum(record["transitionProbability"].values()), places=8)

    def test_future_prices_do_not_change_prior_feature_rows(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest = _write_dataset(root)
            baseline = MODULE.market_feature_rows(root, manifest, MODULE.GhmmConfig())
            payload = json.loads((root / "A-USDT_15min.json").read_text())
            for item in payload["data"][180:]:
                item["close"] *= 2; item["high"] *= 2; item["low"] *= 2; item["open"] *= 2
            (root / "A-USDT_15min.json").write_text(json.dumps(payload))
            mutated = MODULE.market_feature_rows(root, manifest, MODULE.GhmmConfig())
            self.assertEqual(baseline[:120], mutated[:120])


if __name__ == "__main__":
    unittest.main()
