"""SQLite 账本管理 - 运行记录、实验登记、试验计数、审批"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import sqlite3
import uuid
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
    manifest_hash: str | None = None


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
                    event_id TEXT PRIMARY KEY,
                    trial_id TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    parent_run_id TEXT,
                    family_id TEXT NOT NULL,
                    changed_key TEXT,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    final_pnl REAL,
                    final_sharpe REAL,
                    error_message TEXT
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
            self._migrate_schema(conn)

    def _migrate_schema(self, conn: sqlite3.Connection) -> None:
        run_cols = {row[1] for row in conn.execute("PRAGMA table_info(runs)")}
        if "manifest_hash" not in run_cols:
            conn.execute("ALTER TABLE runs ADD COLUMN manifest_hash TEXT")

        trial_cols = {row[1] for row in conn.execute("PRAGMA table_info(trials)")}
        if "event_id" not in trial_cols:
            conn.execute("DROP TABLE IF EXISTS trials")
            conn.execute(
                """
                CREATE TABLE trials (
                    event_id TEXT PRIMARY KEY,
                    trial_id TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    parent_run_id TEXT,
                    family_id TEXT NOT NULL,
                    changed_key TEXT,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    final_pnl REAL,
                    final_sharpe REAL,
                    error_message TEXT
                )
                """
            )

        conn.executescript(
            """
            CREATE TRIGGER IF NOT EXISTS trials_no_update
            BEFORE UPDATE ON trials
            FOR EACH ROW
            BEGIN
                SELECT RAISE(ABORT, 'trials table is append-only, use status transitions via new rows');
            END;

            CREATE TRIGGER IF NOT EXISTS trials_no_delete
            BEFORE DELETE ON trials
            FOR EACH ROW
            BEGIN
                SELECT RAISE(ABORT, 'trials table is append-only');
            END;

            CREATE TRIGGER IF NOT EXISTS registrations_no_update
            BEFORE UPDATE ON registrations
            FOR EACH ROW
            BEGIN
                SELECT RAISE(ABORT, 'registrations table is append-only');
            END;

            CREATE TRIGGER IF NOT EXISTS registrations_no_delete
            BEFORE DELETE ON registrations
            FOR EACH ROW
            BEGIN
                SELECT RAISE(ABORT, 'registrations table is append-only');
            END;
            """
        )
    
    def register_run(self, run: RunRecord) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO runs (
                    run_id, parent_run_id, git_commit, dataset_hash,
                    environment, config_snapshot, cost_scenario,
                    created_at, total_trades, metrics, manifest_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    run.manifest_hash,
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
                       total_trades, metrics, manifest_hash
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
                manifest_hash=row[10] if len(row) > 10 else None,
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
        family_id = parent_run_id or "unknown"
        self.start_trial(
            trial_id=str(uuid.uuid4()),
            run_id=parent_run_id or "unknown",
            parent_run_id=parent_run_id,
            family_id=family_id,
            changed_key=condition,
            status="started",
            started_at=datetime.now(),
        )
        return self.count_trials(family_id)

    def start_trial(
        self,
        trial_id: str,
        run_id: str,
        parent_run_id: str | None,
        family_id: str,
        changed_key: str | None,
        status: str,
        started_at: datetime,
    ) -> str:
        event_id = str(uuid.uuid4())
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO trials (
                    event_id, trial_id, run_id, parent_run_id, family_id,
                    changed_key, status, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event_id,
                    trial_id,
                    run_id,
                    parent_run_id,
                    family_id,
                    changed_key,
                    status,
                    started_at.isoformat(),
                ),
            )
        return event_id

    def finalize_trial(
        self,
        trial_id: str,
        status: str,
        completed_at: datetime,
        final_pnl: float | None = None,
        final_sharpe: float | None = None,
        error_message: str | None = None,
        changed_key: str | None = None,
    ) -> str:
        latest = self.get_latest_trial_event(trial_id)
        if latest is None:
            raise ValueError(f"Cannot finalize unknown trial {trial_id}")
        event_id = str(uuid.uuid4())
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                """
                INSERT INTO trials (
                    event_id, trial_id, run_id, parent_run_id, family_id,
                    changed_key, status, started_at, completed_at,
                    final_pnl, final_sharpe, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event_id,
                    trial_id,
                    latest["run_id"],
                    latest["parent_run_id"],
                    latest["family_id"],
                    changed_key if changed_key is not None else latest["changed_key"],
                    status,
                    latest["started_at"],
                    completed_at.isoformat(),
                    final_pnl,
                    final_sharpe,
                    error_message,
                ),
            )
        return event_id

    def get_latest_trial_event(self, trial_id: str) -> dict[str, Any] | None:
        events = [t for t in self.get_all_trial_events() if t["trial_id"] == trial_id]
        return events[-1] if events else None

    def get_all_trial_events(self) -> list[dict[str, Any]]:
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                "SELECT * FROM trials ORDER BY rowid"
            ).fetchall()
        return [dict(row) for row in rows]

    def get_all_trials(self) -> list[dict[str, Any]]:
        """Latest event per trial_id."""
        latest: dict[str, dict[str, Any]] = {}
        for event in self.get_all_trial_events():
            latest[event["trial_id"]] = event
        return list(latest.values())

    def get_trials_by_family(self, family_id: str) -> list[dict[str, Any]]:
        return [t for t in self.get_all_trials() if t["family_id"] == family_id]

    def count_trials(self, family_id: str) -> int:
        return len(self.get_trials_by_family(family_id))

    def export_trials(
        self,
        family_id: str | None = None,
        fmt: str = "csv",
    ) -> str:
        trials = (
            self.get_trials_by_family(family_id)
            if family_id
            else self.get_all_trials()
        )
        fields = [
            "trial_id",
            "run_id",
            "family_id",
            "changed_key",
            "status",
            "started_at",
            "completed_at",
            "final_pnl",
            "final_sharpe",
        ]
        if fmt == "json":
            return json.dumps(
                [{k: t.get(k) for k in fields} for t in trials],
                indent=2,
            )
        buf = io.StringIO()
        writer = csv.DictWriter(buf, fieldnames=fields)
        writer.writeheader()
        for trial in trials:
            writer.writerow({k: trial.get(k) for k in fields})
        return buf.getvalue()

    def update_run_metrics(self, run_id: str, metrics: dict[str, Any]) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "UPDATE runs SET metrics = ? WHERE run_id = ?",
                (json.dumps(metrics), run_id),
            )

    def get_registration(self, run_id: str) -> ExperimentRegistration | None:
        with sqlite3.connect(self.db_path) as conn:
            row = conn.execute(
                """
                SELECT run_id, parent_run_id, change, hypothesis, expected_effect,
                       keep_rule, drop_rule, evidence_ref, trial_count, uses_holdout,
                       conclusion, decision
                FROM registrations WHERE run_id = ?
                """,
                (run_id,),
            ).fetchone()
        if not row:
            return None
        return ExperimentRegistration(
            run_id=row[0],
            parent_run_id=row[1],
            change=row[2],
            hypothesis=row[3],
            expected_effect=row[4],
            keep_rule=row[5],
            drop_rule=row[6],
            evidence_ref=row[7],
            trial_count=row[8],
            uses_holdout=bool(row[9]),
            conclusion=row[10],
            decision=row[11],
        )
    
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
