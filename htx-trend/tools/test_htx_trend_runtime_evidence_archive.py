import importlib.util
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("runtime_archive", ROOT / "htx_trend_runtime_evidence_archive.py")
MODULE = importlib.util.module_from_spec(SPEC); assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)


class RuntimeEvidenceArchiveTest(unittest.TestCase):
    def test_before_is_timezone_normalized_and_requires_timezone(self):
        self.assertEqual("2026-06-30 16:00:00.000", MODULE.parse_before("2026-07-01T00:00:00+08:00"))
        with self.assertRaises(ValueError):
            MODULE.parse_before("2026-07-01 00:00:00")

    def test_only_the_allowlisted_tables_can_be_exported(self):
        tables = MODULE.selected_tables(["htx_trend_runtime_kline_candle"])
        self.assertEqual(["htx_trend_runtime_kline_candle"], [table.name for table in tables])
        with self.assertRaises(ValueError):
            MODULE.selected_tables(["htx_trend_position"])

    def test_queries_are_read_only_and_bound_to_the_archive_table(self):
        table = MODULE.ARCHIVE_TABLES["htx_trend_runtime_kline_snapshot"]
        query = table.query("2026-07-01 00:00:00.000")
        self.assertTrue(query.startswith("SELECT JSON_OBJECT"))
        self.assertIn("FROM htx_trend_runtime_kline_snapshot", query)
        self.assertIn("captured_at < '2026-07-01 00:00:00.000'", query)
        self.assertNotIn("DELETE", query)

    def test_manifest_binds_row_counts_and_file_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / "sample.jsonl.zst"; data.write_bytes(b"compressed-evidence")
            manifest = MODULE.write_manifest(root, "2026-07-01 00:00:00.000", [{
                "table": "htx_trend_runtime_kline_candle", "file": data.name, "rows": 2,
                "sha256": MODULE.sha256(data), "bytes": data.stat().st_size, "compression": "zstd-jsonl"}])
            payload = json.loads(manifest.read_text())
            self.assertEqual("htx-trend-runtime-evidence-archive-v1", payload["schema"])
            self.assertEqual(2, payload["entries"][0]["rows"])
            self.assertEqual(MODULE.sha256(data), payload["entries"][0]["sha256"])

    @unittest.skipUnless(shutil.which("zstd"), "zstd is required for archive integration verification")
    def test_export_streams_read_only_mysql_rows_to_a_valid_zstd_jsonl_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mysql = root / "mysql"
            mysql.write_text("#!/bin/sh\nprintf '%s\\n' '{\"id\":1,\"contract_code\":\"BTC-USDT\"}'\n", encoding="utf-8")
            mysql.chmod(mysql.stat().st_mode | stat.S_IXUSR)
            environment = {"HTX_DB_HOST": "127.0.0.1", "HTX_DB_PORT": "3306", "HTX_DB_USERNAME": "reader",
                           "HTX_DB_PASSWORD": "not-printed", "PATH": str(root) + os.pathsep + os.environ["PATH"]}
            output = root / "candles.jsonl.zst"
            entry = MODULE.export_table(MODULE.ARCHIVE_TABLES["htx_trend_runtime_kline_candle"],
                                        "2026-07-01 00:00:00.000", "htx_trade_strategy", output, 1, environment)
            payload = subprocess.check_output(["zstd", "--quiet", "--decompress", "--stdout", str(output)], text=True)
            self.assertEqual([{"id": 1, "contract_code": "BTC-USDT"}], [json.loads(payload)])
            self.assertEqual(1, entry["rows"])
            self.assertEqual(MODULE.sha256(output), entry["sha256"])


if __name__ == "__main__":
    unittest.main()
