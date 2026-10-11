"""Pre-registration gates: canonical diff, append-only runs/holdout, approval binding."""

from __future__ import annotations

import sqlite3
from datetime import datetime, timezone

import pytest

from htx_trend.research.ledger import (
    Approval,
    ApprovalDecision,
    ApprovalGate,
    LedgerManager,
    RunRecord,
)
from tests.research.helpers import create_test_dataset, runner_config, write_runner
from tests.research.test_holdout_access import _approve, _mgr


def test_canonical_diff_detects_manifest_hash_and_account(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "ds")
    parent = runner_config(workspace, "P", "ds")
    parent["account"] = {"account_id": "MAIN"}
    ledger = LedgerManager(workspace / "ledger.db")
    ledger.register_run(
        RunRecord(
            run_id="P",
            parent_run_id=None,
            git_commit="x",
            dataset_hash="ds",
            environment="BACKTEST",
            config_snapshot=parent,
            cost_scenario="base",
            created_at=datetime.now(timezone.utc),
            manifest_hash=parent["manifest_hash"],
        )
    )
    child = dict(parent)
    child["run_id"] = "C"
    child["parent_run_id"] = "P"
    child["manifest_hash"] = parent["manifest_hash"] + "ff"
    child["registration"] = {
        **parent["registration"],
        "change": "manifest_hash",
    }
    runner = write_runner(workspace, child)
    with pytest.raises(ValueError, match="[Hh]ash"):
        runner.run()


def test_runs_table_is_append_only(tmp_path):
    ledger = LedgerManager(tmp_path / "ledger.db")
    ledger.register_run(
        RunRecord(
            run_id="R",
            parent_run_id=None,
            git_commit="x",
            dataset_hash="d",
            environment="BACKTEST",
            config_snapshot={"run_id": "R"},
            cost_scenario="base",
            created_at=datetime.now(timezone.utc),
        )
    )
    with sqlite3.connect(tmp_path / "ledger.db") as conn:
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            conn.execute("UPDATE runs SET cost_scenario = 'zero'")
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            conn.execute("DELETE FROM runs")


def test_holdout_access_log_is_append_only(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger)
    mgr.load_dataset(
        dataset_id,
        split="test",
        approval_id=str(approval_id),
        run_id="HOLD1",
        strategy_version="v0.1.0",
    )
    with sqlite3.connect(tmp_path / "ws" / "ledger.db") as conn:
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            conn.execute("DELETE FROM holdout_access_log")
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            conn.execute("UPDATE holdout_access_log SET run_id = 'OTHER'")


def test_holdout_approval_bound_to_run_id_and_strategy_version(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger)
    with pytest.raises(ValueError, match="bound to run"):
        mgr.load_dataset(
            dataset_id,
            split="test",
            approval_id=str(approval_id),
            run_id="OTHER",
            strategy_version="v0.1.0",
        )
    with pytest.raises(ValueError, match="strategy_version"):
        mgr.load_dataset(
            dataset_id,
            split="test",
            approval_id=str(approval_id),
            run_id="HOLD1",
            strategy_version="9.9.9",
        )
