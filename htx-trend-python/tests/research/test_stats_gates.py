"""Statistical gate CLIs: bootstrap PF and permutation."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pandas as pd
import pytest

from htx_trend.research.ledger import LedgerManager
from htx_trend.research.stats.bootstrap import run_pf_bootstrap
from htx_trend.research.stats.metrics import profit_factor, summarize_pf_bootstrap
from collections import deque

from htx_trend.research.stats.permutation import build_forced_entry_queues, run_permutation_test
from htx_trend.research.stats.trades import (
    TREND_STATE_SLICE_RANGE,
    TREND_STATE_SLICE_TREND,
    TREND_STATE_SLICE_TRANSITION,
    trend_state_slice_from_regime,
)
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_runner,
)


def _breakout_close(n: int = 260):
    import numpy as np

    close = np.full(n, 100.0)
    close[210:215] = 130.0
    close[215:218] = 40.0
    close[218:] = 20.0
    return close


def _sample_trades(path: Path) -> None:
    df = pd.DataFrame(
        {
            "symbol": ["BTC-USDT", "BTC-USDT", "ETH-USDT"],
            "entry_time": pd.to_datetime(
                [
                    "2024-01-01T00:00:00Z",
                    "2024-02-01T00:00:00Z",
                    "2024-01-15T00:00:00Z",
                ],
                utc=True,
            ),
            "exit_time": pd.to_datetime(
                [
                    "2024-01-02T00:00:00Z",
                    "2024-02-02T00:00:00Z",
                    "2024-01-16T00:00:00Z",
                ],
                utc=True,
            ),
            "side": ["LONG", "SHORT", "LONG"],
            "net_pnl": [10.0, -4.0, 6.0],
            "return": [0.01, -0.004, 0.006],
            "fees": [0.1, 0.1, 0.1],
            "funding": [0.0, 0.0, 0.0],
        }
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(path, index=False)


def test_profit_factor_matches_legacy_definition() -> None:
    assert profit_factor([2.0, 3.0, -1.0]) == 5.0
    assert profit_factor([1.0]) is None


def test_pf_bootstrap_reproducible(tmp_path: Path) -> None:
    trades = tmp_path / "return_series.parquet"
    _sample_trades(trades)
    import numpy as np

    rng = np.random.default_rng(7)
    pnls = [10.0, -4.0, 6.0, 2.0, -1.0]
    a = summarize_pf_bootstrap(pnls, rng=rng, n_bootstrap=500, confidence=0.9)
    rng = np.random.default_rng(7)
    b = summarize_pf_bootstrap(pnls, rng=rng, n_bootstrap=500, confidence=0.9)
    assert a == b
    assert a["profit_factor_bootstrap"]["ci_lower"] <= a["observed_profit_factor"]


def test_pf_bootstrap_cli_json_stable(tmp_path: Path) -> None:
    run_id = "STAT-BOOT-1"
    trades = tmp_path / "runs" / run_id / "return_series.parquet"
    _sample_trades(trades)
    kw = {
        "workspace": tmp_path,
        "run_id": run_id,
        "trades_path": None,
        "target_run_id": run_id,
        "n_bootstrap": 200,
        "confidence": 0.9,
        "seed": 123,
        "group_by": [],
        "output": tmp_path / "out.json",
        "write_ledger": False,
    }
    first = run_pf_bootstrap(**kw)
    second = run_pf_bootstrap(**kw)
    assert first["slices"] == second["slices"]
    assert first["seed"] == second["seed"] == 123
    assert "overall" in first["slices"]


def test_trend_state_slice_three_groups() -> None:
    assert trend_state_slice_from_regime("TREND_UP_MID") == TREND_STATE_SLICE_TREND
    assert trend_state_slice_from_regime("TREND_DOWN_LOW") == TREND_STATE_SLICE_TREND
    assert trend_state_slice_from_regime("RANGE_MID") == TREND_STATE_SLICE_RANGE
    assert trend_state_slice_from_regime("TRANSITION_MID") == TREND_STATE_SLICE_TRANSITION


@pytest.mark.require_runtime_integrity
def test_forced_queues_disable_c01_entries(tmp_path: Path) -> None:
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(
        workspace,
        "forced",
        n_bars=260,
        train_end_idx=260,
        val_end_idx=260,
        symbols=["BTC-USDT"],
        specs={"BTC-USDT": DEFAULT_SPECS["BTC-USDT"]},
        close_series={"BTC-USDT": _breakout_close()},
    )
    run_id = "FORCED-NO-C01"
    cfg = runner_config(
        workspace,
        run_id,
        "forced",
        symbols=["BTC-USDT"],
        initial_equity=10_000.0,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "enabled": True,
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    )
    with patch("htx_trend.research.runner.require_runtime_integrity", return_value="test"):
        baseline = write_runner(workspace, cfg)
        baseline_results = baseline._load_dataset()
        data, frozen = baseline_results
        normal = baseline._run_backtest(data, frozen, forced_entry_queues=None)
        normal_entries = sum(1 for f in normal["fills"] if f.is_entry)

        forced_runner = write_runner(workspace, {**cfg, "run_id": "FORCED-NO-C01-b"})
        data2, frozen2 = forced_runner._load_dataset()
        times = pd.to_datetime(data2["BTC-USDT"]["bar_time"], utc=True)
        bar_time = times.iloc[220]
        queues = {
            "BTC-USDT": deque([(bar_time, "LONG")]),
        }
        forced = forced_runner._run_backtest(
            data2, frozen2, forced_entry_queues=queues
        )
        forced_entries = sum(1 for f in forced["fills"] if f.is_entry)
        forced_runner.close()
        baseline.close()

    assert normal_entries > 1
    assert forced_entries == 1


def test_build_forced_queues_preserves_sides() -> None:
    import numpy as np

    templates = {
        "BTC-USDT": [
            (pd.Timestamp("2024-01-01", tz="UTC"), "LONG"),
            (pd.Timestamp("2024-02-01", tz="UTC"), "SHORT"),
        ]
    }
    eligible = {
        "BTC-USDT": [
            pd.Timestamp("2024-01-05", tz="UTC"),
            pd.Timestamp("2024-01-10", tz="UTC"),
            pd.Timestamp("2024-02-05", tz="UTC"),
        ]
    }
    rng = np.random.default_rng(1)
    queues = build_forced_entry_queues(templates, eligible, rng)
    sides = [side for _t, side in queues["BTC-USDT"]]
    assert sides == ["LONG", "SHORT"]


@pytest.mark.require_runtime_integrity
def test_permutation_smoke(tmp_path: Path) -> None:
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(
        workspace,
        "permset",
        n_bars=260,
        train_end_idx=260,
        val_end_idx=260,
        symbols=["BTC-USDT"],
        specs={"BTC-USDT": DEFAULT_SPECS["BTC-USDT"]},
        close_series={"BTC-USDT": _breakout_close()},
    )
    run_id = "PERM-SMOKE-01"
    cfg = runner_config(
        workspace,
        run_id,
        "permset",
        symbols=["BTC-USDT"],
        initial_equity=10_000.0,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "enabled": True,
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    )
    config_path = workspace / f"{run_id}.yaml"

    with patch("htx_trend.research.runner.require_runtime_integrity", return_value="test"):
        runner = write_runner(workspace, cfg)
        runner.run()
        runner.close()

    trades_path = workspace / "runs" / run_id / "return_series.parquet"
    trades = pd.read_parquet(trades_path)
    if trades.empty or len(trades) < 1:
        pytest.skip("smoke backtest produced no trades")
    trades = trades.head(2).copy()
    trades.to_parquet(trades_path, index=False)

    ledger = LedgerManager(workspace / "ledger.db")
    run = ledger.get_run(run_id)
    assert run is not None

    with patch("htx_trend.research.runner.require_runtime_integrity", return_value="test"):
        payload = run_permutation_test(
            workspace,
            source_run_id=run_id,
            config_path=config_path,
            n_permutations=5,
            seed=11,
            output=workspace / "perm.json",
            write_ledger=True,
        )
    assert payload["n_permutations"] == 5
    assert payload["null_distribution"]["n_samples"] == 5
    assert payload["observed"]["n_trades"] == len(trades)
    assert 0.0 < payload["p_value"] <= 1.0
    updated = ledger.get_run(run_id)
    assert updated.metrics["statistical_gates"]["permutation"]["seed"] == 11
    assert updated.metrics["statistical_gates"]["permutation"]["null_n_samples"] == 5
