import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from htx_trend_indicator_ablation import build_report, observation
from htx_trend_backtest import Bar


class IndicatorAblationTest(unittest.TestCase):
    def test_observation_does_not_read_future_bars(self):
        bars = self._bars(45)
        reference = observation(bars, 39)
        mutated = list(bars)
        mutated[40] = Bar(mutated[40].ts, 1, 999, 1, 999, 1, 1)

        self.assertEqual(reference, observation(mutated, 39))

    def test_report_keeps_ahr999_explicitly_unavailable_and_bins_post_fee_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bars = self._bars(45)
            (root / "BTC-USDT_15min.json").write_text(json.dumps([{
                "id": bar.ts, "open": bar.open, "high": bar.high, "low": bar.low,
                "close": bar.close, "amount": bar.amount, "trade_turnover": bar.turnover
            } for bar in bars]), encoding="utf-8")
            ledger = {"schema": "htx-trend-research-derived-ledger-v1", "datasetKey": "frozen-test",
                      "inputIntegrity": "COMPLETE", "labels": [{"contractCode": "BTC-USDT", "entryBarTime": 39,
                      "modelCode": "FIB_PULLBACK_CONTINUATION", "side": "LONG", "netProfit": 1.5,
                      "marketState": "TREND_UP"}]}
            ledger_path = root / "ledger.json"
            ledger_path.write_text(json.dumps(ledger), encoding="utf-8")
            manifest = {"schema": "htx-trend-research-dataset-manifest-v1", "datasetKey": "frozen-test",
                        "inputIntegrity": "COMPLETE", "inputStartAt": "2026-01-01T00:00:00+08:00",
                        "inputEndAt": "2026-01-02T00:00:00+08:00"}
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            report = build_report(root, ledger_path, manifest_path)

            self.assertEqual("OBSERVE_ONLY", report["decision"])
            self.assertEqual("NOT_AVAILABLE", report["ahr999"]["status"])
            self.assertEqual(1, report["analyzedLabelCount"])
            self.assertEqual(1, report["baselineByModel"]["FIB_PULLBACK_CONTINUATION"]["ALL"]["tradeCount"])
            self.assertIn("turnoverState", report["factorBuckets"])

    @staticmethod
    def _bars(size):
        return [Bar(index, 100 + index * index, 102 + index * index, 99 + index * index,
                    101 + index * index, 10 + index, (10 + index) * (101 + index * index))
                for index in range(size)]


if __name__ == "__main__":
    unittest.main()
