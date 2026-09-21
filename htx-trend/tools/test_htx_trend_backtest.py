#!/usr/bin/env python3
"""Focused regression tests for the offline HTX trend replay."""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("htx_trend_backtest.py")
SPEC = importlib.util.spec_from_file_location("htx_trend_backtest", MODULE_PATH)
assert SPEC and SPEC.loader
REPLAY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REPLAY
SPEC.loader.exec_module(REPLAY)


class HtxTrendBacktestTest(unittest.TestCase):
    def test_load_bars_accepts_raw_htx_response_envelope(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "BTC-USDT_15min.json"
            path.write_text(json.dumps({"status": "ok", "data": [{
                "id": 1, "open": 100, "high": 101, "low": 99, "close": 100.5,
            }]}), encoding="utf-8")
            bars = REPLAY.load_bars(Path(temp), "BTC-USDT", "15min")
        self.assertEqual(1, len(bars))
        self.assertEqual(100.5, bars[0].close)

    def test_recovery_profile_keeps_removed_early_exits_disabled(self):
        self.assertFalse(REPLAY.RECOVERY_PROFILE.adverse_shock_exit_enabled)
        self.assertFalse(REPLAY.RECOVERY_PROFILE.no_extension_exit_enabled)
        self.assertTrue(REPLAY.RECOVERY_PROFILE.direction_gate_enabled)

    def test_sleeve_decision_requires_a_minimum_sample(self):
        self.assertEqual("INSUFFICIENT_SAMPLE", REPLAY.sleeve_decision({
            "trades": 2, "net_pnl": 99.0, "profit_factor": 99.0, "max_loss": 0.0,
        }))
        self.assertEqual("REJECT", REPLAY.sleeve_decision({
            "trades": 3, "net_pnl": -0.01, "profit_factor": 1.5, "max_loss": -0.1,
        }))
        self.assertEqual("PAPER_CANDIDATE", REPLAY.sleeve_decision({
            "trades": 3, "net_pnl": 1.0, "profit_factor": 1.2, "max_loss": -1.5,
        }))


if __name__ == "__main__":
    unittest.main()
