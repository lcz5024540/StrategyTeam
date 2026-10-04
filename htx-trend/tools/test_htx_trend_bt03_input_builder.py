import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
spec = importlib.util.spec_from_file_location("bt03_builder", ROOT / "htx_trend_bt03_input_builder.py")
M = importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[spec.name] = M; spec.loader.exec_module(M)


class Bt03InputBuilderTest(unittest.TestCase):
    def test_requires_complete_plan_and_verified_future_candles(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); data = base / "data"; data.mkdir()
            candle = data / "BTC-USDT_15min.json"
            candle.write_text(json.dumps({"data": [
                {"id": 1000, "open": 100, "high": 101, "low": 99, "close": 100},
                {"id": 1900, "open": 100, "high": 102, "low": 99, "close": 101},
                {"id": 2800, "open": 101, "high": 104, "low": 100, "close": 103},
            ]}))
            manifest = base / "manifest.json"
            manifest.write_text(json.dumps({"schema": "htx-trend-research-dataset-manifest-v1", "inputIntegrity": "COMPLETE",
                "datasetKey": "research:test", "inputs": [{"contract": "BTC-USDT", "period": "15min", "file": candle.name,
                "coverage": "COMPLETE", "sha256": hashlib.sha256(candle.read_bytes()).hexdigest()}]}))
            plans = base / "plans.jsonl"
            plans.write_text(json.dumps({"contractCode": "BTC-USDT", "period": "15min", "barTime": 1000,
                "factorsJson": json.dumps({"schema": "trend-price-plan-v1", "runId": "run:1", "entry": {"side": "LONG",
                "idealPrice": 100, "maxAcceptablePrice": 101, "plannedContracts": 10, "contractSize": 0.1,
                "validUntilEpoch": 1900}, "exit": {"structureTargetPrice": 105}, "risk": {"plannedRiskUsdt": 1}})} ) + "\n")
            report = M.build(plans, data, manifest, base / "input.json")
            self.assertEqual("COMPLETE", report["inputIntegrity"])
            self.assertEqual(1, report["candidateCount"])
            self.assertEqual("run:1", report["candidates"][0]["runId"])

    def test_does_not_create_a_candidate_when_future_bars_are_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); plans = base / "plans.jsonl"; plans.write_text("")
            manifest = base / "manifest.json"; manifest.write_text(json.dumps({"schema": "htx-trend-research-dataset-manifest-v1", "inputIntegrity": "COMPLETE", "inputs": []}))
            report = M.build(plans, base, manifest, base / "input.json")
            self.assertEqual("INSUFFICIENT_EVIDENCE", report["inputIntegrity"])


if __name__ == "__main__": unittest.main()
