"""Report fields: evidence_ref/conclusion/decision and real parent diffs."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import yaml

from htx_trend.research.cli import main
from htx_trend.research.ledger import ExperimentRegistration, LedgerManager, RunRecord
from htx_trend.research.reports.report_generator import ReportGenerator
from htx_trend.research.runner import BacktestRunner
from tests.research.test_strict_determinism import create_test_dataset


def _registration() -> ExperimentRegistration:
    return ExperimentRegistration(
        run_id="REP1",
        parent_run_id="PARENT",
        change="risk_per_trade",
        hypothesis="Lower risk improves stability",
        expected_effect="smaller drawdown",
        keep_rule="PF>=1.2",
        drop_rule="PF<1.0",
        evidence_ref="docs/first-trend-strategy-parameter-rationale.md",
        trial_count=3,
        uses_holdout=False,
        conclusion="Keep the change",
        decision="KEEP",
    )


def test_report_includes_registration_fields(tmp_path):
    gen = ReportGenerator("REP1", tmp_path)
    path = gen.generate_report(
        config={"run_id": "REP1", "registration": {"evidence_ref": "ignored"}},
        fills=[],
        decisions=pd.DataFrame(),
        registration=_registration(),
    )
    text = path.read_text()
    assert "evidence_ref: docs/first-trend-strategy-parameter-rationale.md" in text
    assert "change: risk_per_trade" in text
    assert "hypothesis: Lower risk improves stability" in text
    assert "keep_rule: PF>=1.2" in text
    assert "trial_count: 3" in text


def test_report_includes_conclusion_decision(tmp_path):
    gen = ReportGenerator("REP1", tmp_path)
    path = gen.generate_report(
        config={"run_id": "REP1"},
        fills=[],
        decisions=pd.DataFrame(),
        registration=_registration(),
    )
    text = path.read_text()
    assert "conclusion: Keep the change" in text
    assert "decision: KEEP" in text


def test_parent_comparison_real_pnl(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "rep_parent"
    create_test_dataset(workspace, dataset_id)
    ledger = LedgerManager(workspace / "ledger.db")
    parent_cfg = {
        "run_id": "PARENT",
        "conditions": {
            "C01": {"enabled": True, "periods": [42, 84, 126, 168], "threshold": 0.25},
            "C02": {"atr_period": 14, "atr_multiplier": 2.0},
        },
        "risk_per_trade": 0.005,
        "max_positions": 3,
        "max_total_risk": 0.015,
        "initial_equity": 1000.0,
        "dataset_id": dataset_id,
        "split": "train",
        "cost_scenario": "base",
        "fees": None,
        "symbols": ["BTC-USDT", "ETH-USDT"],
        "leverage_target": 5.0,
    }
    ledger.register_run(
        RunRecord(
            run_id="PARENT",
            parent_run_id=None,
            git_commit="abc",
            dataset_hash=dataset_id,
            environment="BACKTEST",
            config_snapshot=parent_cfg,
            cost_scenario="base",
            created_at=datetime.now(timezone.utc),
            metrics={"total_pnl": 40.0},
        )
    )
    child_cfg = {
        **parent_cfg,
        "run_id": "CHILD",
        "parent_run_id": "PARENT",
        "risk_per_trade": 0.006,
        "registration": {
            "change": "risk_per_trade",
            "hypothesis": "h",
            "expected_effect": "e",
            "keep_rule": "k",
            "drop_rule": "d",
            "evidence_ref": "docs/evidence.md",
        },
    }
    path = workspace / "child.yaml"
    path.write_text(yaml.dump(child_cfg))
    runner = BacktestRunner(path, workspace)
    runner._changed_key = "risk_per_trade"
    report = runner._generate_report(
        {
            "fills": [],
            "decisions": pd.DataFrame(),
            "final_equity": 1080.0,
            "initial_equity": 1000.0,
        }
    )
    text = report.read_text()
    assert "父运行PnL: $40.00" in text
    assert "当前PnL: $80.00" in text
    assert "差值: $40.00" in text


def test_parent_comparison_calculates_diff(tmp_path):
    gen = ReportGenerator("CHILD", tmp_path)
    path = gen.generate_report(
        config={"run_id": "CHILD"},
        fills=[],
        decisions=pd.DataFrame(),
        parent_comparison={
            "parent_run_id": "PARENT",
            "changed_key": "risk_per_trade",
            "current_pnl": 80.0,
            "parent_pnl": 40.0,
            "pnl_diff": 40.0,
            "pct_change": 100.0,
        },
    )
    text = path.read_text()
    assert "差值: $40.00" in text
    assert "百分比变化: 100.00%" in text


def test_report_splits_performance_before_and_after_stitch(tmp_path):
    fills = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 1,
            "price": 100.0,
            "time": datetime(2020, 10, 1, tzinfo=timezone.utc),
            "fees": 0.0,
            "slippage": 0.0,
            "is_entry": True,
        },
        {
            "symbol": "BTC-USDT",
            "side": "SHORT",
            "size": 1,
            "price": 110.0,
            "time": datetime(2020, 10, 15, tzinfo=timezone.utc),
            "fees": 0.0,
            "slippage": 0.0,
            "is_entry": False,
        },
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 1,
            "price": 100.0,
            "time": datetime(2020, 11, 2, tzinfo=timezone.utc),
            "fees": 0.0,
            "slippage": 0.0,
            "is_entry": True,
        },
        {
            "symbol": "BTC-USDT",
            "side": "SHORT",
            "size": 1,
            "price": 90.0,
            "time": datetime(2020, 11, 10, tzinfo=timezone.utc),
            "fees": 0.0,
            "slippage": 0.0,
            "is_entry": False,
        },
    ]
    path = ReportGenerator("SEG1", tmp_path).generate_report(
        config={"run_id": "SEG1"},
        fills=fills,
        decisions=pd.DataFrame(),
        stitch_points={"BTC-USDT": "2020-11-01T00:00:00+00:00"},
    )
    text = path.read_text()
    assert "绩效分段" in text or "stitch" in text.lower()
    assert "2020-11-01" in text
    before_idx = text.lower().find("before")
    after_idx = text.lower().find("after")
    assert before_idx != -1
    assert after_idx != -1
    assert before_idx < after_idx
    assert "$10.00" in text
    assert "$-10.00" in text or "-$10.00" in text


def test_update_conclusion_cli(tmp_path, monkeypatch):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    ledger = LedgerManager(workspace / "ledger.db")
    ledger.register_experiment(
        ExperimentRegistration(
            run_id="REP1",
            parent_run_id=None,
            change="baseline",
            hypothesis="h",
            expected_effect="e",
            keep_rule="k",
            drop_rule="d",
            evidence_ref="ref",
            trial_count=1,
            uses_holdout=False,
            conclusion=None,
            decision=None,
        )
    )
    monkeypatch.setattr(
        "sys.argv",
        [
            "htx-trend-research",
            "--workspace",
            str(workspace),
            "update-conclusion",
            "--run-id",
            "REP1",
            "--conclusion",
            "Meets keep rule",
            "--decision",
            "KEEP",
        ],
    )
    main()
    loaded = ledger.get_registration("REP1")
    assert loaded is not None
    assert loaded.conclusion == "Meets keep rule"
    assert loaded.decision == "KEEP"
    event = ledger.get_latest_conclusion("REP1")
    assert event is not None
    assert event["decision"] == "KEEP"
