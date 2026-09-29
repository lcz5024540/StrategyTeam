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
