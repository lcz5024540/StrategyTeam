#!/usr/bin/env python3
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("dataset_manifest", ROOT / "htx_trend_dataset_manifest.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class DatasetManifestTest(unittest.TestCase):
    def test_manifest_records_explicit_exclusion_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            root.joinpath("BTC-USDT_15min.json").write_text(json.dumps({"data": [
                {"id": 1788192000, "open": 100, "high": 101, "low": 99, "close": 100},
                {"id": 1788192900, "open": 100, "high": 101, "low": 99, "close": 100},
            ]}), encoding="utf-8")
            universe = {"schema": "htx-trend-research-universe-v1", "contracts": ["BTC-USDT"],
                        "excludedContracts": [{"contract": "PONS-USDT", "reason": "INSUFFICIENT_HISTORY",
                                               "evidence": "HTX public 1d probes returned zero bars before 2026-09-03"}]}
            value = MODULE.build_manifest(root, ["BTC-USDT"], ["15min"],
                                          "2026-09-01T00:00:00", "2026-09-01T00:30:00", universe)
        self.assertEqual("PONS-USDT", value["researchUniverse"]["excludedContracts"][0]["contract"])
        self.assertEqual("INSUFFICIENT_HISTORY", value["researchUniverse"]["excludedContracts"][0]["reason"])
        self.assertEqual(64, len(value["researchUniverse"]["sha256"]))

    def test_manifest_rejects_silent_contract_exclusion(self):
        with self.assertRaises(ValueError):
            MODULE.canonical_universe({"schema": "htx-trend-research-universe-v1", "contracts": ["BTC-USDT"],
                                       "excludedContracts": [{"contract": "BTC-USDT", "reason": "bad", "evidence": "bad"}]},
                                      ["BTC-USDT"])

    def test_manifest_marks_missing_timeframe_as_partial_without_inventing_input(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            root.joinpath("BTC-USDT_15min.json").write_text(json.dumps({"data": [
                {"id": 1788192000, "open": 100, "high": 101, "low": 99, "close": 100},
                {"id": 1788192900, "open": 100, "high": 101, "low": 99, "close": 100},
            ]}), encoding="utf-8")
            value = MODULE.build_manifest(root, ["BTC-USDT"], ["15min", "1day"],
                                          "2026-09-01T00:00:00", "2026-09-01T00:30:00")
        self.assertEqual("PARTIAL", value["inputIntegrity"])
        self.assertIn("BTC-USDT:1day:missing-file", value["missingOrPartial"])
        self.assertEqual(1, len(value["inputs"]))
        self.assertTrue(value["datasetKey"].startswith("research:"))


if __name__ == "__main__":
    unittest.main()
