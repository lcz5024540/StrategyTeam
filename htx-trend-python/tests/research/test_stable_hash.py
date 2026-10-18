"""P0-B: determinism hash drops identifiers and stays ordered."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from htx_trend.research.ops.stable_hash import hash_parquet, stable_frame_hash


def test_stable_hash_ignores_run_id():
    left = pd.DataFrame(
        {
            "run_id": ["SMOKE-20261011-01"],
            "symbol": ["BTC-USDT"],
            "action": ["ENTER"],
            "generated_at": ["2026-10-11T00:00:00Z"],
            "size": [1],
        }
    )
    right = pd.DataFrame(
        {
            "generated_at": ["2026-10-11T01:00:00Z"],
            "action": ["ENTER"],
            "run_id": ["SMOKE-20261011-02"],
            "size": [1],
            "symbol": ["BTC-USDT"],
        }
    )
    assert stable_frame_hash(left) == stable_frame_hash(right)

    changed = left.copy()
    changed.loc[0, "action"] = "EXIT"
    assert stable_frame_hash(left) != stable_frame_hash(changed)


def test_stable_hash_sorts_rows_and_columns(tmp_path: Path):
    first = pd.DataFrame(
        [
            {"symbol": "ETH-USDT", "bar_time": "2024-01-02", "action": "HOLD"},
            {"symbol": "BTC-USDT", "bar_time": "2024-01-01", "action": "ENTER"},
        ]
    )
    second = pd.DataFrame(
        [
            {"action": "ENTER", "bar_time": "2024-01-01", "symbol": "BTC-USDT"},
            {"action": "HOLD", "bar_time": "2024-01-02", "symbol": "ETH-USDT"},
        ]
    )
    assert stable_frame_hash(first) == stable_frame_hash(second)
    path = tmp_path / "decisions.parquet"
    first.to_parquet(path, index=False)
    assert hash_parquet(path) == stable_frame_hash(first)


def test_smoke_script_writes_summary_on_failure_and_closes_resources():
    script = (Path(__file__).resolve().parents[2] / "scripts/smoke_e2e.sh").read_text()
    assert "LOOKBACK_DAYS:-90" not in script
    assert 'LOOKBACK="${LOOKBACK_DAYS:-max}"' in script
    assert "trap on_exit EXIT" in script
    assert "write_summary" in script
    assert "hash_parquet" in script
    assert "source_counts" in script
    assert "ARROW_NUM_THREADS" in script
