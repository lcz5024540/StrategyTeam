"""测试审批系统"""

from datetime import UTC, datetime
from pathlib import Path

import pytest

from htx_trend.research.ledger import (
    Approval,
    ApprovalDecision,
    ApprovalGate,
    LedgerManager,
)


def test_approval_creation(tmp_path):
    ledger = LedgerManager(tmp_path / "test.db")
    
    approval = Approval(
        approval_id=None,
        gate=ApprovalGate.PRE_PAPER,
        run_id="R0001",
        strategy_version="v1.0.0",
        approver="alice@example.com",
        decision=ApprovalDecision.APPROVED,
        rationale="All backtest metrics pass threshold",
        created_at=datetime.now(UTC),
    )
    
    approval_id = ledger.create_approval(approval)
    
    assert approval_id > 0


def test_get_latest_approval(tmp_path):
    ledger = LedgerManager(tmp_path / "test.db")
    
    approval1 = Approval(
        approval_id=None,
        gate=ApprovalGate.PRE_PAPER,
        run_id="R0001",
        strategy_version="v1.0.0",
        approver="alice@example.com",
        decision=ApprovalDecision.PENDING,
        rationale="Under review",
        created_at=datetime.now(UTC),
    )
    
    ledger.create_approval(approval1)
    
    approval2 = Approval(
        approval_id=None,
        gate=ApprovalGate.PRE_PAPER,
        run_id="R0001",
        strategy_version="v1.0.0",
        approver="bob@example.com",
        decision=ApprovalDecision.APPROVED,
        rationale="Review complete, approved",
        created_at=datetime.now(UTC),
    )
    
    ledger.create_approval(approval2)
    
    latest = ledger.get_latest_approval(ApprovalGate.PRE_PAPER, "R0001")
    
    assert latest is not None
    assert latest.decision == ApprovalDecision.APPROVED
    assert latest.approver == "bob@example.com"


def test_check_approval_gate(tmp_path):
    ledger = LedgerManager(tmp_path / "test.db")
    
    approved, msg = ledger.check_approval_gate(
        ApprovalGate.PRE_PAPER, "R0001", "v1.0.0"
    )
    
    assert not approved
    assert "No approval found" in msg
    
    approval = Approval(
        approval_id=None,
        gate=ApprovalGate.PRE_PAPER,
        run_id="R0001",
        strategy_version="v1.0.0",
        approver="alice@example.com",
        decision=ApprovalDecision.APPROVED,
        rationale="Approved for PAPER",
        created_at=datetime.now(UTC),
    )
    
    ledger.create_approval(approval)
    
    approved, msg = ledger.check_approval_gate(
        ApprovalGate.PRE_PAPER, "R0001", "v1.0.0"
    )
    
    assert approved
    assert "alice@example.com" in msg


def test_version_mismatch_rejection(tmp_path):
    ledger = LedgerManager(tmp_path / "test.db")
    
    approval = Approval(
        approval_id=None,
        gate=ApprovalGate.PRE_LIVE,
        run_id="R0002",
        strategy_version="v1.0.0",
        approver="alice@example.com",
        decision=ApprovalDecision.APPROVED,
        rationale="Approved for v1.0.0",
        created_at=datetime.now(UTC),
    )
    
    ledger.create_approval(approval)
    
    approved, msg = ledger.check_approval_gate(
        ApprovalGate.PRE_LIVE, "R0002", "v1.1.0"
    )
    
    assert not approved
    assert "version" in msg.lower()
    assert "v1.0.0" in msg
    assert "v1.1.0" in msg


def test_rejected_approval(tmp_path):
    ledger = LedgerManager(tmp_path / "test.db")
    
    approval = Approval(
        approval_id=None,
        gate=ApprovalGate.PRE_PAPER,
        run_id="R0003",
        strategy_version="v1.0.0",
        approver="bob@example.com",
        decision=ApprovalDecision.REJECTED,
        rationale="Sample size too small",
        created_at=datetime.now(UTC),
    )
    
    ledger.create_approval(approval)
    
    approved, msg = ledger.check_approval_gate(
        ApprovalGate.PRE_PAPER, "R0003", "v1.0.0"
    )
    
    assert not approved
    assert "REJECTED" in msg
    assert "Sample size too small" in msg
