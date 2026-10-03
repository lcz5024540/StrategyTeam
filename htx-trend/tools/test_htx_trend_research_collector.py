#!/usr/bin/env python3
import importlib.util
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("collector", ROOT / "htx_trend_research_collector.py")
assert SPEC and SPEC.loader
COLLECTOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = COLLECTOR
SPEC.loader.exec_module(COLLECTOR)


def payload(*ids):
    return {"status": "ok", "data": [{"id": value, "open": 1, "high": 2, "low": 0.5, "close": 1.5} for value in ids]}


class StubClient:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def fetch(self, symbol, period, start, end, size):
        self.calls.append((symbol, period, start, end, size))
        value = self.responses[(start, end)]
        if isinstance(value, COLLECTOR.CollectionError):
            raise value
        if isinstance(value, Exception):
            raise COLLECTOR.CollectionError(str(value))
        return value, "https://test.example", "https://test.example/request"


class CollectorTest(unittest.TestCase):
    def test_normalize_filters_range_and_deduplicates(self):
        rows = COLLECTOR.normalize_bars(payload(0, 900, 900, 1800, 2700), 900, 2700)
        self.assertEqual([900, 1800], [row["id"] for row in rows])

    def test_missing_intervals_compacts_adjacent_bars(self):
        self.assertEqual([(900, 1800), (3600, 3600)], COLLECTOR.missing_intervals(
            [{"id": 0}, {"id": 2700}], 0, 4500, 900))

    def test_resume_reuses_valid_raw_page_without_network(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = COLLECTOR.CollectionTarget("BTC-USDT", "15min")
            raw = COLLECTOR.raw_page_path(root, target, 0, 1800)
            COLLECTOR.atomic_json_write(raw, payload(0, 900))
            state = {"targets": {}}
            client = StubClient({})
            result = COLLECTOR.collect_target(target, root, 0, 1800, 2, 1, client, state,
                                              root / "state.json", threading.Lock())
            self.assertEqual("COMPLETE", result["status"])
            self.assertEqual([], client.calls)

    def test_failure_is_recorded_and_does_not_write_merged_input(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = {"targets": {}}
            client = StubClient({(0, 900): RuntimeError("offline")})
            result = COLLECTOR.collect_target(COLLECTOR.CollectionTarget("BTC-USDT", "15min"), root, 0, 1800, 2, 1,
                                              client, state, root / "state.json", threading.Lock())
            self.assertEqual("FAILED", result["status"])
            self.assertFalse((root / "BTC-USDT_15min.json").exists())
            self.assertEqual(1, len(state["targets"]["BTC-USDT:15min"]["failures"]))

    def test_rejected_exchange_response_is_preserved_as_raw_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = {"targets": {}}
            error = COLLECTOR.CollectionError("invalid from", {"status": "error", "err-code": "invalid-parameter"})
            client = StubClient({(0, 900): error})
            result = COLLECTOR.collect_target(COLLECTOR.CollectionTarget("BTC-USDT", "15min"), root, 0, 1800, 2, 1,
                                              client, state, root / "state.json", threading.Lock())
            self.assertEqual("FAILED", result["status"])
            evidence = root / "raw" / "BTC-USDT" / "15min" / "0-1800.error.json"
            self.assertTrue(evidence.exists())
            self.assertEqual("invalid-parameter", json.loads(evidence.read_text())["err-code"])

    def test_completed_target_merges_pages_and_records_gap(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            state = {"targets": {}}
            client = StubClient({(0, 900): payload(0, 900), (1800, 2700): payload(2700)})
            result = COLLECTOR.collect_target(COLLECTOR.CollectionTarget("BTC-USDT", "15min"), root, 0, 3600, 2, 1,
                                              client, state, root / "state.json", threading.Lock())
            self.assertEqual("PARTIAL", result["status"])
            self.assertEqual(1, result["missingIntervals"])
            merged = json.loads((root / "BTC-USDT_15min.json").read_text())
            self.assertEqual([0, 900, 2700], [row["id"] for row in merged["data"]])

    def test_failed_target_is_retried_in_a_later_round_without_discarding_prior_state(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); state = {"targets": {}}
            class FlakyClient:
                def __init__(self): self.calls = 0
                def fetch(self, symbol, period, start, end, size):
                    self.calls += 1
                    if self.calls == 1: raise COLLECTOR.CollectionError("temporary reset")
                    return payload(0, 900), "https://test.example", "https://test.example/request"
            results, rounds = COLLECTOR.collect_in_rounds([COLLECTOR.CollectionTarget("BTC-USDT", "15min")], root,
                0, 1800, 2, 1, FlakyClient(), state, root / "state.json", threading.Lock(), 1, 2, 0)
            self.assertEqual(2, rounds); self.assertEqual("COMPLETE", results[0]["status"])
            self.assertEqual(1, len(state["targets"]["BTC-USDT:15min"]["failures"]))
            self.assertEqual(2, state["targets"]["BTC-USDT:15min"]["lastRound"])

    def test_probe_requires_returned_rows_to_stay_in_requested_window(self):
        client = StubClient({(900, 2700): payload(900, 1800, 2700, 3600)})
        result = COLLECTOR.probe(client, "BTC-USDT", "15min", 900)
        self.assertTrue(result["rangeHonored"])
        self.assertEqual([900, 1800, 2700], result["returnedIds"])

    def test_state_refuses_to_resume_with_different_audited_universe(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "collector-state.json"
            universe = {"schema": "htx-trend-research-universe-v1", "contracts": ["BTC-USDT"], "excludedContracts": []}
            state = COLLECTOR.load_state(path, 0, 900, ["BTC-USDT"], ["15min"], universe)
            COLLECTOR.atomic_json_write(path, state)
            different = {"schema": "htx-trend-research-universe-v1", "contracts": ["BTC-USDT"],
                         "excludedContracts": [{"contract": "PONS-USDT", "reason": "INSUFFICIENT_HISTORY", "evidence": "probe"}]}
            with self.assertRaises(COLLECTOR.CollectionError):
                COLLECTOR.load_state(path, 0, 900, ["BTC-USDT"], ["15min"], different)

    def test_historical_fetch_does_not_mix_size_with_from_and_to(self):
        class Response:
            def read(self):
                return json.dumps(payload(0)).encode()
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
        captured = []
        original = COLLECTOR.urlopen
        try:
            COLLECTOR.urlopen = lambda request, timeout: (captured.append(request.full_url) or Response())
            COLLECTOR.HtxPublicKlineClient(("https://test.example",)).fetch("BTC-USDT", "15min", 0, 900, 2000)
        finally:
            COLLECTOR.urlopen = original
        self.assertIn("from=0", captured[0])
        self.assertIn("to=900", captured[0])
        self.assertNotIn("size=", captured[0])


if __name__ == "__main__":
    unittest.main()
