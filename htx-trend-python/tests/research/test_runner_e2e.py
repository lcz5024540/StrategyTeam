"""E2E runner-path tests for reviewer P0/P1 items."""

from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import patch

import numpy as np
import pytest

from htx_trend.research.risk.liquidation_check import AccountLiquidationChecker
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_runner,
)


def _breakout_close(n: int = 260) -> np.ndarray:
    close = np.full(n, 100.0)
    close[210:215] = 130.0
    close[215:218] = 40.0
    close[218:] = 20.0
    return close


def _breakout_workspace(tmp_path, specs=None):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    close = _breakout_close()
    create_test_dataset(
        workspace,
        "breakout",
        n_bars=260,
        train_end_idx=260,
        val_end_idx=260,
        symbols=["BTC-USDT"],
        specs=specs or {"BTC-USDT": DEFAULT_SPECS["BTC-USDT"]},
        close_series={"BTC-USDT": close},
    )
    return workspace


def _breakout_config(workspace, run_id="E2E1", **overrides):
    cfg = runner_config(
        workspace,
        run_id,
        "breakout",
        symbols=["BTC-USDT"],
        initial_equity=10_000.0,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    )
    cfg.update(overrides)
    return cfg


def test_min_order_size_over_budget_skips_entry(tmp_path):
    specs = {
        "BTC-USDT": {**DEFAULT_SPECS["BTC-USDT"], "min_order_size": 1_000_000},
    }
    workspace = _breakout_workspace(tmp_path, specs)
    runner = write_runner(workspace, _breakout_config(workspace))
    results = runner.run()
    decisions = results["decisions"]
    assert not decisions.empty
    skips = decisions[decisions["action"] == "SKIP"]
    assert not skips.empty
    assert skips["skip_reason"].astype(str).str.contains("Min order size").any()
    assert (decisions["action"] == "ENTER").sum() == 0


def test_manifest_hash_required_no_unverified_load(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(workspace)
    del cfg["manifest_hash"]
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="manifest_hash"):
        runner.run()


def test_cost_scenario_required(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(workspace)
    del cfg["cost_scenario"]
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="cost_scenario"):
        runner.run()


def test_unknown_condition_c04_raises(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(workspace)
    cfg["conditions"]["C04"] = {"enabled": True, "periods": [20]}
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="C04"):
        runner.run()


def test_account_liquidation_checker_used_by_default(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    called = {"n": 0}
    original = AccountLiquidationChecker.check_account_liquidation_risk

    def wrapped(self, *args, **kwargs):
        called["n"] += 1
        return original(self, *args, **kwargs)

    runner = write_runner(workspace, _breakout_config(workspace))
    with patch.object(AccountLiquidationChecker, "check_account_liquidation_risk", wrapped):
        runner.run()
    assert called["n"] >= 1


def test_c02_independent_stops_for_consecutive_opposite_trades(tmp_path):
    specs = {
        "BTC-USDT": {**DEFAULT_SPECS["BTC-USDT"], "contract_size": 1.0, "min_order_size": 1},
    }
    workspace = _breakout_workspace(tmp_path, specs)
    runner = write_runner(
        workspace, _breakout_config(workspace, "C02ISO", initial_equity=1_000_000.0)
    )
    results = runner.run()
    decisions = results["decisions"]
    enters = decisions[decisions["action"] == "ENTER"]
    assert len(enters) >= 2
    sides = enters["side"].tolist()
    assert "LONG" in sides and "SHORT" in sides
    ids = enters["position_id"].tolist()
    assert len(set(ids)) == len(ids)
    stops = enters["stop_loss"].tolist()
    assert stops[0] != stops[1]


def test_smoke_not_counted_and_labeled(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    today = datetime.now(timezone.utc).strftime("%Y%m%d")
    run_id = f"SMOKE-{today}-01"
    cfg = _breakout_config(workspace, run_id, run_type="SMOKE", split="train")
    runner = write_runner(workspace, cfg)
    runner.run()
    assert runner.ledger.count_trials(run_id) == 0
    assert runner.ledger.get_all_trials() == []
    report = (workspace / "runs" / run_id / "report.md").read_text()
    assert "SMOKE" in report


def test_smoke_refuses_test_split(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(
        workspace,
        "SMOKE-20261011-02",
        run_type="SMOKE",
        split="test",
    )
    with pytest.raises(ValueError, match="train"):
        write_runner(workspace, cfg)


def test_trial_export_includes_sharpe_trades_and_return_series(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    runner = write_runner(workspace, _breakout_config(workspace, "DSREXP"))
    runner.run()
    exported = runner.ledger.export_trials(fmt="json")
    assert "final_sharpe" in exported
    assert "n_trades" in exported
    assert "return_series_path" in exported
    series = workspace / "runs" / "DSREXP" / "return_series.parquet"
    assert series.exists()
