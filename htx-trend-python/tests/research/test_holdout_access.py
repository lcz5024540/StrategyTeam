"""Test-set gate in DatasetManager: approval, access log, unknown splits."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.ledger import (
    Approval,
    ApprovalDecision,
    ApprovalGate,
    LedgerManager,
)
from tests.research.test_strict_determinism import create_test_dataset


def _mgr(tmp_path: Path) -> tuple[DatasetManager, LedgerManager, str]:
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = "holdout_ds"
    create_test_dataset(workspace, dataset_id)
    ledger = LedgerManager(workspace / "ledger.db")
    mgr = DatasetManager(
        workspace / "data" / "datasets",
        ledger=ledger,
        current_run_id="HOLD1",
    )
    return mgr, ledger, dataset_id


def _approve(
    ledger: LedgerManager,
    *,
    gate: ApprovalGate = ApprovalGate.STAGE_ACCEPTANCE,
    decision: ApprovalDecision = ApprovalDecision.APPROVED,
) -> int:
    return ledger.create_approval(
        Approval(
            approval_id=None,
            gate=gate,
            run_id="HOLD1",
            strategy_version="v0.1.0",
            approver="reviewer@example.com",
            decision=decision,
            rationale="stage acceptance for holdout test",
            created_at=datetime.now(timezone.utc),
        )
    )


def test_invalid_split_name_raises(tmp_path):
    mgr, _ledger, dataset_id = _mgr(tmp_path)
    with pytest.raises(ValueError, match="Invalid split name"):
        mgr.load_dataset(dataset_id, split="holdout")


def test_test_split_requires_approval_id(tmp_path):
    mgr, _ledger, dataset_id = _mgr(tmp_path)
    with pytest.raises(ValueError, match="approval_id"):
        mgr.load_dataset(dataset_id, split="test", run_id="HOLD1", strategy_version="v0.1.0")


def test_test_split_requires_stage_acceptance(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger, gate=ApprovalGate.PRE_PAPER)
    with pytest.raises(ValueError, match="STAGE_ACCEPTANCE"):
        mgr.load_dataset(dataset_id, split="test", approval_id=str(approval_id))


def test_test_split_requires_approved_status(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger, decision=ApprovalDecision.REJECTED)
    with pytest.raises(ValueError, match="REJECTED"):
        mgr.load_dataset(dataset_id, split="test", approval_id=str(approval_id))


def test_holdout_access_logged(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger)
    data, frozen = mgr.load_dataset(
        dataset_id,
        split="test",
        approval_id=str(approval_id),
        run_id="HOLD1",
        strategy_version="v0.1.0",
    )
    assert data
    logs = ledger.list_holdout_access(frozen.manifest_hash)
    assert len(logs) == 1
    assert logs[0]["split_requested"] == "test"
    assert logs[0]["approval_id"] == str(approval_id)
    assert logs[0]["dataset_id"] == dataset_id


def test_same_dataset_hash_second_access_blocked(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger)
    mgr.load_dataset(
        dataset_id,
        split="test",
        approval_id=str(approval_id),
        run_id="HOLD1",
        strategy_version="v0.1.0",
    )
    with pytest.raises(ValueError, match="already been accessed"):
        mgr.load_dataset(
            dataset_id,
            split="test",
            approval_id=str(approval_id),
            run_id="HOLD1",
            strategy_version="v0.1.0",
        )


def test_train_val_no_approval_needed(tmp_path):
    mgr, _ledger, dataset_id = _mgr(tmp_path)
    train, frozen = mgr.load_dataset(dataset_id, split="train")
    val, _ = mgr.load_dataset(dataset_id, split="val")
    symbol = next(iter(train))
    counts = frozen.segment_counts[symbol]
    assert len(train[symbol]) == counts["train"]
    assert len(val[symbol]) == counts["val"]


def test_dataset_manager_returns_only_requested_split(tmp_path):
    mgr, ledger, dataset_id = _mgr(tmp_path)
    approval_id = _approve(ledger)
    test, frozen = mgr.load_dataset(
        dataset_id,
        symbols=["BTC-USDT"],
        split="test",
        approval_id=str(approval_id),
        run_id="HOLD1",
        strategy_version="v0.1.0",
    )
    btc = test["BTC-USDT"]
    assert len(btc) == frozen.segment_counts["BTC-USDT"]["test"]
    assert "ETH-USDT" not in test
