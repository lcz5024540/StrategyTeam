"""rc.13: reachable P1 apply_bad_ticks raise; funding_gaps hash binding."""

from __future__ import annotations

from pathlib import Path

import pandas as pd
import pytest

from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.data.quality import (
    EXPECTED_FUNDING_GAPS_SHA256,
    apply_bad_ticks,
    default_funding_gaps_path,
    funding_gaps_hash,
)
from htx_trend.research.features.funding_asof import attach_funding_as_of
from tests.research.helpers import create_test_dataset, runner_config, write_runner


def _frame() -> pd.DataFrame:
    times = pd.date_range("2020-09-01", periods=20, freq="4h", tz="UTC")
    return pd.DataFrame(
        {
            "bar_time": times,
            "open": 1.0,
            "high": 1.0,
            "low": 1.0,
            "close": 1.0,
            "volume": 1.0,
            "amount": 1.0,
            "source": "htx",
        }
    )


def test_apply_bad_ticks_raises_when_flag_columns_missing():
    df = _frame()
    with pytest.raises(ValueError, match="missing required flag columns"):
        apply_bad_ticks(df, "BTC-USDT", [])


def test_freeze_raises_when_claimed_funding_gaps_hash_differs(tmp_path):
    df = _frame()
    df["incomplete"] = False
    df["non_comparable"] = False
    df["bad_tick"] = False
    df["external"] = False
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="funding_gaps_hash does not match"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc="2020-12-15T00:00:00Z",
            validation_start_utc="2020-11-20T00:00:00Z",
            test_start_utc="2020-12-01T00:00:00Z",
            extra_metadata={
                "non_comparable_window_bars": 30,
                "stitch_points": {"BTC-USDT": None},
                "funding_gaps_hash": "sha256:" + ("0" * 64),
            },
            funding_gaps_path=default_funding_gaps_path(),
        )


def test_packaged_funding_gaps_hash_matches_constant():
    assert funding_gaps_hash(default_funding_gaps_path()) == (
        "sha256:" + EXPECTED_FUNDING_GAPS_SHA256
    )


def test_run_refuses_one_byte_tampered_funding_gaps_registry(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "gaps-tamper", n_bars=48, symbols=["BTC-USDT"])
    original = default_funding_gaps_path().read_bytes()
    tampered = tmp_path / "funding_gaps.yaml"
    tampered.write_bytes(original + b"\n")
    assert tampered.read_bytes() != original
    cfg = runner_config(
        workspace,
        "GAPSTAMPER",
        "gaps-tamper",
        symbols=["BTC-USDT"],
        funding_gaps=str(tampered),
    )
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="funding_gaps_hash mismatch"):
        runner.run()


def test_run_refuses_manifest_missing_funding_gaps_hash(tmp_path):
    from types import SimpleNamespace

    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "gaps-missing", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(workspace, "GAPSMISS", "gaps-missing", symbols=["BTC-USDT"])
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="missing funding_gaps_hash"):
        runner._load_and_verify_funding_gaps(SimpleNamespace(funding_gaps_hash=None))


def test_attach_funding_as_of_requires_explicit_gaps():
    bars = _frame()
    funding = pd.DataFrame(
        {
            "funding_time": [pd.Timestamp("2020-09-01T00:00:00Z")],
            "funding_rate": [0.0001],
        }
    )
    with pytest.raises(ValueError, match="funding gaps registry is required"):
        attach_funding_as_of(bars, funding, "4hour", symbol="BTC-USDT")
