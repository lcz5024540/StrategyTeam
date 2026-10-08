import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("runtime_freeze", ROOT / "htx_trend_runtime_kline_freeze.py")
MODULE = importlib.util.module_from_spec(SPEC); assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)


def row(contract: str, period: str, ts: int) -> list[str]:
    return [contract, period, str(ts), "100", "101", "99", "100.5", "12", "13", "1300", "7", "a" * 64, "2026-10-08 12:00:00"]


class RuntimeKlineFreezeTest(unittest.TestCase):
    def test_completed_end_excludes_active_bar(self):
        self.assertEqual(86_400, MODULE.completed_end(90_000, "1day"))
        self.assertEqual(86_400, MODULE.completed_end(100_000, "4hour"))

    def test_complete_dataset_has_period_specific_windows_and_first_observed_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            rows = [row(contract, period, stamp) for contract in ("BTC-USDT", "ETH-USDT")
                    for period, stamps in (("1day", (0, 86_400)), ("4hour", (57_600, 72_000, 86_400))) for stamp in stamps]
            manifest = MODULE.write_dataset(rows, root, ["BTC-USDT", "ETH-USDT"], {"1day": 0, "4hour": 57_600},
                                            {"1day": 172_800, "4hour": 100_800}, "2026-01-02T12:00:00+08:00",
                                            [{"contract": "RAY-USDT", "reason": "INSUFFICIENT_HISTORY", "evidence": "32 bars"}])
            self.assertEqual("COMPLETE", manifest["inputIntegrity"])
            self.assertEqual("FROZEN_RUNTIME_KLINE", manifest["sourceKind"])
            self.assertEqual(57_600, manifest["periodWindows"]["4hour"]["start"])
            payload = json.loads((root / "BTC-USDT_1day.json").read_text())
            self.assertEqual([0, 86_400], [item["id"] for item in payload["data"]])

    def test_gap_is_partial_and_is_not_silently_hidden(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest = MODULE.write_dataset([row("BTC-USDT", "1day", 0), row("BTC-USDT", "4hour", 0)], Path(temp),
                                            ["BTC-USDT"], {"1day": 0, "4hour": 0}, {"1day": 172_800, "4hour": 28_800},
                                            "2026-01-02T12:00:00+08:00", [])
            self.assertEqual("PARTIAL", manifest["inputIntegrity"])
            self.assertEqual(2, len(manifest["missingOrPartial"]))


if __name__ == "__main__":
    unittest.main()
