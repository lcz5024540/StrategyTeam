"""Trial records: start/finally, append-only triggers, export-trials."""

from __future__ import annotations

import csv
import io
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import pytest
import yaml

from htx_trend.research.cli import main
from htx_trend.research.ledger import LedgerManager, RunRecord
from htx_trend.research.runner import BacktestRunner
from tests.research.test_strict_determinism import create_test_dataset


def _base_config(run_id: str, dataset_id: str, parent_run_id=None, extra=None) -> dict:
    config = {
        "run_id": run_id,
        "parent_run_id": parent_run_id,
        "registration": {
            "change": extra.get("change") if extra else "Baseline trial",
            "hypothesis": "Trials are recorded",
            "expected_effect": "ledger has trial rows",
            "keep_rule": "trial finalized",
            "drop_rule": "trial missing",
            "evidence_ref": "docs/REMAINING_WORK.md",
        },
        "dataset_id": dataset_id,
        "symbols": ["BTC-USDT", "ETH-USDT"],
        "split": "train",
        "stage_acceptance": False,
        "initial_equity": 1000.0,
        "cost_scenario": "base",
        "risk_per_trade": 0.005,
        "max_total_risk": 0.015,
        "max_positions": 3,
        "conditions": {
            "C01": {"enabled": True, "periods": [42, 84, 126, 168], "threshold": 0.25},
            "C02": {"atr_period": 14, "atr_multiplier": 2.0},
        },
    }
    if extra:
        config.update({k: v for k, v in extra.items() if k != "change"})
        if "change" in extra:
            config["registration"]["change"] = extra["change"]
    return config


def _write_runner(workspace: Path, config: dict) -> BacktestRunner:
    path = workspace / f"{config['run_id']}.yaml"
    path.write_text(yaml.dump(config))
    return BacktestRunner(path, workspace)


def test_trial_written_at_start(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "trial_start"
    create_test_dataset(workspace, dataset_id)
    runner = _write_runner(workspace, _base_config("TSTART", dataset_id))
    seen: dict = {}

    orig = BacktestRunner._load_dataset

    def _wrapped(self):
        trials = self.ledger.get_all_trial_events()
        seen["n"] = len(trials)
        seen["status"] = trials[-1]["status"] if trials else None
        return orig(self)

    with patch.object(BacktestRunner, "_load_dataset", _wrapped):
        runner.run()

    assert seen["n"] >= 1
    assert seen["status"] == "started"


def test_trial_completed_on_success(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "trial_ok"
    create_test_dataset(workspace, dataset_id)
    runner = _write_runner(workspace, _base_config("TOK", dataset_id))
    runner.run()

    trials = runner.ledger.get_all_trials()
    assert len(trials) == 1
    assert trials[0]["status"] == "completed"
    assert trials[0]["run_id"] == "TOK"
    assert trials[0]["final_pnl"] is not None


def test_trial_failed_on_exception(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "trial_fail"
    create_test_dataset(workspace, dataset_id)
    runner = _write_runner(workspace, _base_config("TFAIL", dataset_id))

    def _boom(self):
        raise RuntimeError("dataset exploded")

    with patch.object(BacktestRunner, "_load_dataset", _boom):
        with pytest.raises(RuntimeError, match="dataset exploded"):
            runner.run()

    trials = runner.ledger.get_all_trials()
    assert len(trials) == 1
    assert trials[0]["status"] == "failed"
    assert "dataset exploded" in (trials[0]["error_message"] or "")


def test_trial_no_update_trigger(tmp_path):
    ledger = LedgerManager(tmp_path / "ledger.db")
    ledger.start_trial(
        trial_id="t1",
        run_id="R1",
        parent_run_id=None,
        family_id="R1",
        changed_key="baseline",
        status="started",
        started_at=datetime.now(timezone.utc),
    )
    with sqlite3.connect(tmp_path / "ledger.db") as conn:
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            conn.execute("UPDATE trials SET status = 'completed'")


def test_trial_no_delete_trigger(tmp_path):
    ledger = LedgerManager(tmp_path / "ledger.db")
    ledger.start_trial(
        trial_id="t1",
        run_id="R1",
        parent_run_id=None,
        family_id="R1",
        changed_key="baseline",
        status="started",
        started_at=datetime.now(timezone.utc),
    )
    with sqlite3.connect(tmp_path / "ledger.db") as conn:
        with pytest.raises(sqlite3.IntegrityError, match="append-only"):
            conn.execute("DELETE FROM trials")


def test_family_id_traces_to_baseline(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "trial_family"
    create_test_dataset(workspace, dataset_id)
    ledger = LedgerManager(workspace / "ledger.db")
    parent_config = _base_config("BASE", dataset_id)
    ledger.register_run(
        RunRecord(
            run_id="BASE",
            parent_run_id=None,
            git_commit="abc",
            dataset_hash=dataset_id,
            environment="BACKTEST",
            config_snapshot=parent_config,
            cost_scenario="base",
            created_at=datetime.now(timezone.utc),
        )
    )

    child = _base_config(
        "CHILD",
        dataset_id,
        parent_run_id="BASE",
        extra={"change": "risk_per_trade only", "risk_per_trade": 0.006},
    )
    runner = _write_runner(workspace, child)
    assert runner._determine_family_id() == "BASE"
    runner.run()
    trial = runner.ledger.get_all_trials()[0]
    assert trial["family_id"] == "BASE"


def test_export_trials_csv(tmp_path, monkeypatch):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    ledger = LedgerManager(workspace / "ledger.db")
    ledger.start_trial(
        trial_id="t-export",
        run_id="RX",
        parent_run_id=None,
        family_id="FAM",
        changed_key="baseline",
        status="started",
        started_at=datetime.now(timezone.utc),
    )
    ledger.finalize_trial(
        trial_id="t-export",
        status="completed",
        completed_at=datetime.now(timezone.utc),
        final_pnl=12.5,
        final_sharpe=1.1,
    )
    out = tmp_path / "trials.csv"
    monkeypatch.setattr(
        "sys.argv",
        [
            "htx-trend-research",
            "--workspace",
            str(workspace),
            "export-trials",
            "--format",
            "csv",
            "--output",
            str(out),
        ],
    )
    main()
    text = out.read_text()
    reader = csv.DictReader(io.StringIO(text))
    rows = list(reader)
    assert rows
    assert rows[0]["trial_id"] == "t-export"
    assert rows[0]["family_id"] == "FAM"
    assert rows[0]["status"] == "completed"
    assert rows[0]["final_pnl"] == "12.5"


def test_trial_count_from_db(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "trial_count"
    create_test_dataset(workspace, dataset_id)
    runner = _write_runner(workspace, _base_config("C1", dataset_id))
    runner.run()
    reg = runner.ledger.get_registration("C1")
    assert reg is not None
    assert reg.trial_count == runner.ledger.count_trials(runner._family_id)
    assert reg.trial_count == 1

    parent = runner.ledger.get_run("C1")
    assert parent is not None
    child_cfg = _base_config(
        "C2",
        dataset_id,
        parent_run_id="C1",
        extra={"change": "one param", "max_positions": 2},
    )
    child = _write_runner(workspace, child_cfg)
    child.run()
    reg2 = child.ledger.get_registration("C2")
    assert reg2 is not None
    assert reg2.trial_count == 2
    assert child.ledger.count_trials(child._family_id) == 2
