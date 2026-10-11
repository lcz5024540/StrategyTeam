"""SQLite 账本管理 - 运行记录、实验登记、试验计数、审批"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any


class ApprovalGate(str, Enum):
    """审批门禁类型"""
    PRE_PAPER = "PRE_PAPER"
    PRE_LIVE = "PRE_LIVE"
    BREAKER_RESTART = "BREAKER_RESTART"


class ApprovalDecision(str, Enum):
    """审批决策"""
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    PENDING = "PENDING"


@dataclass
class Approval:
    """审批记录"""
    approval_id: int | None
    gate: ApprovalGate
    run_id: str
    strategy_version: str
    approver: str
    decision: ApprovalDecision
    rationale: str
    created_at: datetime
    
    def to_dict(self) -> dict[str, Any]:
        return {
            "approval_id": self.approval_id,
            "gate": self.gate.value,
            "run_id": self.run_id,
            "strategy_version": self.strategy_version,
            "approver": self.approver,
            "decision": self.decision.value,
            "rationale": self.rationale,
            "created_at": self.created_at.isoformat(),
        }


@dataclass
class RunRecord:
    run_id: str
    parent_run_id: str | None
    git_commit: str
    dataset_hash: str
    environment: str
    config_snapshot: dict[str, Any]
    cost_scenario: str
    created_at: datetime
    total_trades: int = 0
    metrics: dict[str, Any] | None = None


@dataclass
class ExperimentRegistration:
    run_id: str
    parent_run_id: str | None
    change: str
    hypothesis: str
    expected_effect: str
    keep_rule: str
    drop_rule: str
    evidence_ref: str | None
    trial_count: int
    uses_holdout: bool
    conclusion: str | None
    decision: str | None


class LedgerManager:
    def __init__(self, db_path: Path):
        self.db_path = db_path
        self._ensure_tables()
    
    def _ensure_tables(self) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS runs (
                    run_id TEXT PRIMARY KEY,
                    parent_run_id TEXT,
                    git_commit TEXT,
                    dataset_hash TEXT,
                    environment TEXT,
                    config_snapshot TEXT,
                    cost_scenario TEXT,
                    created_at TEXT,
                    total_trades INTEGER DEFAULT 0,
                    metrics TEXT
                );
                
                CREATE TABLE IF NOT EXISTS registrations (
                    run_id TEXT PRIMARY KEY,
                    parent_run_id TEXT,
                    change TEXT NOT NULL,
                    hypothesis TEXT NOT NULL,
                    expected_effect TEXT NOT NULL,
                    keep_rule TEXT NOT NULL,
                    drop_rule TEXT NOT NULL,
                    evidence_ref TEXT,
                    trial_count INTEGER DEFAULT 1,
                    uses_holdout INTEGER DEFAULT 0,
                    conclusion TEXT,
                    decision TEXT,
                    FOREIGN KEY (run_id) REFERENCES runs(run_id)
                );
                
                CREATE TABLE IF NOT EXISTS test_set_locks (
                    dataset_hash TEXT,
                    test_hash TEXT,
                    run_id TEXT,
                    locked_at TEXT,
                    PRIMARY KEY (dataset_hash, test_hash)
                );
                
                CREATE TABLE IF NOT EXISTS trials (
                    trial_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    parent_run_id TEXT,
                    condition_changed TEXT,
                    created_at TEXT,
                    result TEXT
                );
                
                CREATE TABLE IF NOT EXISTS approvals (
                    approval_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    gate TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    strategy_version TEXT NOT NULL,
                    approver TEXT NOT NULL,
                    decision TEXT NOT NULL,
                    rationale TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                
                CREATE INDEX IF NOT EXISTS idx_approvals_gate_decision 
                ON approvals(gate, decision, created_at DESC);
            """)
    
    def register_run(self, run: RunRecord) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO runs (
                    run_id, parent_run_id, git_commit, dataset_hash,
                    environment, config_snapshot, cost_scenario,
                    created_at, total_trades, metrics
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run.run_id,
                    run.parent_run_id,
                    run.git_commit,
                    run.dataset_hash,
                    run.environment,
                    json.dumps(run.config_snapshot),
                    run.cost_scenario,
                    run.created_at.isoformat(),
                    run.total_trades,
                    json.dumps(run.metrics) if run.metrics else None,
                ),
            )
    
    def get_run(self, run_id: str) -> RunRecord | None:
        """获取运行记录
        
        Args:
            run_id: 运行 ID
        
        Returns:
            RunRecord or None if not found
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(
                """
                SELECT run_id, parent_run_id, git_commit, dataset_hash,
                       environment, config_snapshot, cost_scenario, created_at,
                       total_trades, metrics
                FROM runs
                WHERE run_id = ?
                """,
                (run_id,),
            )
            
            row = cursor.fetchone()
            
            if not row:
                return None
            
            return RunRecord(
                run_id=row[0],
                parent_run_id=row[1],
                git_commit=row[2],
                dataset_hash=row[3],
                environment=row[4],
                config_snapshot=json.loads(row[5]),
                cost_scenario=row[6],
                created_at=datetime.fromisoformat(row[7]),
                total_trades=row[8],
                metrics=json.loads(row[9]) if row[9] else {},
            )
    
    def register_experiment(self, exp: ExperimentRegistration) -> None:
        if not exp.hypothesis or not exp.keep_rule or not exp.drop_rule:
            raise ValueError(
                "Experiment registration incomplete: hypothesis, keep_rule, and drop_rule are required"
            )
        
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO registrations (
                    run_id, parent_run_id, change, hypothesis,
                    expected_effect, keep_rule, drop_rule, evidence_ref,
                    trial_count, uses_holdout, conclusion, decision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    exp.run_id,
                    exp.parent_run_id,
                    exp.change,
                    exp.hypothesis,
                    exp.expected_effect,
                    exp.keep_rule,
                    exp.drop_rule,
                    exp.evidence_ref,
                    exp.trial_count,
                    1 if exp.uses_holdout else 0,
                    exp.conclusion,
                    exp.decision,
                ),
            )
    
    def increment_trial_count(self, parent_run_id: str, condition: str) -> int:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO trials (parent_run_id, condition_changed, created_at, result)
                VALUES (?, ?, ?, ?)
                """,
                (parent_run_id, condition, datetime.now().isoformat(), "pending"),
            )
            
            cursor = conn.execute(
                "SELECT COUNT(*) FROM trials WHERE parent_run_id = ?",
                (parent_run_id,),
            )
            count = cursor.fetchone()[0]
        
        return count
    
    def lock_test_set(
        self, dataset_hash: str, test_hash: str, run_id: str
    ) -> bool:
        try:
            with sqlite3.connect(self.db_path) as conn:
                conn.execute(
                    """
                    INSERT INTO test_set_locks (dataset_hash, test_hash, run_id, locked_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (dataset_hash, test_hash, run_id, datetime.now().isoformat()),
                )
            return True
        except sqlite3.IntegrityError:
            return False
    
    def is_test_set_locked(self, dataset_hash: str, test_hash: str) -> bool:
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(
                "SELECT COUNT(*) FROM test_set_locks WHERE dataset_hash = ? AND test_hash = ?",
                (dataset_hash, test_hash),
            )
            count = cursor.fetchone()[0]
        
        return count > 0
    
    def get_run(self, run_id: str) -> RunRecord | None:
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(
                "SELECT * FROM runs WHERE run_id = ?", (run_id,)
            )
            row = cursor.fetchone()
        
        if not row:
            return None
        
        return RunRecord(
            run_id=row[0],
            parent_run_id=row[1],
            git_commit=row[2],
            dataset_hash=row[3],
            environment=row[4],
            config_snapshot=json.loads(row[5]),
            cost_scenario=row[6],
            created_at=datetime.fromisoformat(row[7]),
            total_trades=row[8],
            metrics=json.loads(row[9]) if row[9] else None,
        )
    
    def create_approval(self, approval: Approval) -> int:
        """记录审批决策"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(
                """
                INSERT INTO approvals (
                    gate, run_id, strategy_version, approver,
                    decision, rationale, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    approval.gate.value,
                    approval.run_id,
                    approval.strategy_version,
                    approval.approver,
                    approval.decision.value,
                    approval.rationale,
                    approval.created_at.isoformat(),
                ),
            )
            return cursor.lastrowid
    
    def get_latest_approval(
        self, gate: ApprovalGate, run_id: str | None = None
    ) -> Approval | None:
        """获取最新的审批记录
        
        Args:
            gate: 门禁类型
            run_id: 可选，指定 run_id
        
        Returns:
            最新的审批记录，如果没有则返回 None
        """
        with sqlite3.connect(self.db_path) as conn:
            if run_id:
                cursor = conn.execute(
                    """
                    SELECT approval_id, gate, run_id, strategy_version,
                           approver, decision, rationale, created_at
                    FROM approvals
                    WHERE gate = ? AND run_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                    (gate.value, run_id),
                )
            else:
                cursor = conn.execute(
                    """
                    SELECT approval_id, gate, run_id, strategy_version,
                           approver, decision, rationale, created_at
                    FROM approvals
                    WHERE gate = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                    (gate.value,),
                )
            
            row = cursor.fetchone()
        
        if not row:
            return None
        
        return Approval(
            approval_id=row[0],
            gate=ApprovalGate(row[1]),
            run_id=row[2],
            strategy_version=row[3],
            approver=row[4],
            decision=ApprovalDecision(row[5]),
            rationale=row[6],
            created_at=datetime.fromisoformat(row[7]),
        )
    
    def check_approval_gate(
        self, gate: ApprovalGate, run_id: str, strategy_version: str
    ) -> tuple[bool, str]:
        """检查是否通过审批门禁
        
        Args:
            gate: 门禁类型
            run_id: 运行ID
            strategy_version: 策略版本
        
        Returns:
            (is_approved, message)
        """
        approval = self.get_latest_approval(gate, run_id)
        
        if approval is None:
            return (
                False,
                f"No approval found for {gate.value} gate, run {run_id}. "
                f"Promotion requires explicit approval.",
            )
        
        if approval.decision != ApprovalDecision.APPROVED:
            return (
                False,
                f"Latest approval for {gate.value} is {approval.decision.value}: "
                f"{approval.rationale}",
            )
        
        if approval.strategy_version != strategy_version:
            return (
                False,
                f"Approval is for version {approval.strategy_version}, "
                f"but current version is {strategy_version}. Re-approval required.",
            )
        
        return (
            True,
            f"Approved by {approval.approver} at {approval.created_at.isoformat()}",
        )
    
    def get_all_approvals_by_gate(self, gate: ApprovalGate) -> list[Approval]:
        """获取指定门禁的所有审批记录"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(
                """
                SELECT approval_id, gate, run_id, strategy_version,
                       approver, decision, rationale, created_at
                FROM approvals
                WHERE gate = ?
                ORDER BY created_at DESC
                """,
                (gate.value,),
            )
            rows = cursor.fetchall()
        
        return [
            Approval(
                approval_id=row[0],
                gate=ApprovalGate(row[1]),
                run_id=row[2],
                strategy_version=row[3],
                approver=row[4],
                decision=ApprovalDecision(row[5]),
                rationale=row[6],
                created_at=datetime.fromisoformat(row[7]),
            )
            for row in rows
        ]
