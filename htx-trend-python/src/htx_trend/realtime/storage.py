"""Explicit plan persistence and quota-bound, verifiable event segments."""

from __future__ import annotations

import gzip
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import threading
from ..research.store import canonical, digest

TERMINAL = frozenset(
    {"REJECTED", "INVALIDATED", "NO_FILL", "FILLED", "PARTIAL", "SUBMITTED", "UNKNOWN"}
)


class PlanStore:
    """Local research/PAPER adapter; LIVE uses MySqlPlanStore, never this DB."""

    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS plans (instance TEXT, id TEXT, value TEXT, PRIMARY KEY(instance,id))"
        )
        self.db.commit()

    def all(self, instance):
        return {
            r[0]: json.loads(r[1])
            for r in self.db.execute(
                "SELECT id,value FROM plans WHERE instance=?", (instance,)
            )
        }

    def save(self, instance, ident, value):
        with self.db:
            self.db.execute(
                "INSERT INTO plans VALUES(?,?,?) ON CONFLICT(instance,id) DO UPDATE SET value=excluded.value",
                (instance, ident, canonical(value).decode()),
            )

    def close(self):
        self.db.close()


class PaperPlanStore:
    def __init__(self, ledger):
        self.ledger = ledger

    def all(self, instance):
        return self.ledger.data.get("realtime_plans", {}).get(instance, {}).copy()

    def save(self, instance, ident, value):
        self.ledger.data.setdefault("realtime_plans", {}).setdefault(instance, {})[
            ident
        ] = json.loads(canonical(value))
        self.ledger.checkpoint()


class MySqlPlanStore:
    """No startup DDL. Existing unique key is the durable plan/intent identity."""

    def __init__(self, connection):
        self.db = connection
        with self.db.cursor() as c:
            c.execute(
                "SELECT instance_key,plan_id,state_json FROM htx_trend_realtime_plan LIMIT 0"
            )

    def all(self, instance):
        with self.db.cursor() as c:
            c.execute(
                "SELECT plan_id,state_json FROM htx_trend_realtime_plan WHERE instance_key=%s",
                (instance,),
            )
            return {
                r["plan_id"]: json.loads(r["state_json"])
                if isinstance(r["state_json"], str)
                else r["state_json"]
                for r in c.fetchall()
            }

    def save(self, instance, ident, value):
        with self.db.cursor() as c:
            c.execute(
                "INSERT INTO htx_trend_realtime_plan(instance_key,plan_id,state_json) VALUES(%s,%s,%s) "
                "ON DUPLICATE KEY UPDATE state_json=VALUES(state_json)",
                (instance, ident, canonical(value).decode()),
            )
        self.db.commit()


class SegmentRecorder:
    """Append-only gzip segments. No deletion, no silent quota loss or full-window copies."""

    def __init__(self, root, *, max_bytes=2 * 1024**3, segment_bytes=1024**2):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.max_bytes, self.segment_bytes = max_bytes, segment_bytes
        self.used = sum(p.stat().st_size for p in self.root.iterdir() if p.is_file())
        self.pending = []
        self.pending_bytes = 0
        self.failure = None
        self.lock = threading.RLock()
        self.last_ref = None
        manifest = self.root / "segments.jsonl"
        self.indexed = (
            {json.loads(line)["hash"] for line in manifest.read_text().splitlines()}
            if manifest.exists()
            else set()
        )

    def append(self, event):
        with self.lock:
            if self.failure:
                return False
            value = event.dump() if hasattr(event, "dump") else event
            self.pending.append(value)
            self.pending_bytes += len(canonical(value))
            return (
                self.flush() is not None
                if self.pending_bytes >= self.segment_bytes
                else True
            )

    def flush(self):
        with self.lock:
            if self.failure:
                return None
            if not self.pending:
                return self.last_ref
            raw = canonical(
                {"schema": "htx-realtime-segment-v1", "events": self.pending}
            )
            sha = digest(json.loads(raw))
            packed = gzip.compress(raw, mtime=0)
            record = {
                "hash": sha,
                "bytes": len(packed),
                "count": len(self.pending),
                "first_received_ms": self.pending[0].get("received_ms"),
                "last_received_ms": self.pending[-1].get("received_ms"),
            }
            line = canonical(record) + b"\n"
            if self.used + len(packed) + len(line) > self.max_bytes:
                self.failure = "CAPTURE_QUOTA_EXCEEDED"
                self.pending.clear()
                self.pending_bytes = 0
                return None
            path = self.root / (sha + ".json.gz")
            try:
                if not path.exists():
                    with tempfile.NamedTemporaryFile(
                        dir=self.root, prefix=".pending-", delete=False
                    ) as f:
                        temporary = Path(f.name)
                        f.write(packed)
                        f.flush()
                        os.fsync(f.fileno())
                    os.replace(temporary, path)
                    self.used += len(packed)
                if sha not in self.indexed:
                    # A crash after object rename but before manifest append is recoverable.
                    if digest(json.loads(gzip.decompress(path.read_bytes()))) != sha:
                        raise OSError("orphan segment hash mismatch")
                    with (self.root / "segments.jsonl").open("ab") as f:
                        f.write(line)
                        f.flush()
                        os.fsync(f.fileno())
                    fd = os.open(self.root, os.O_RDONLY)
                    try:
                        os.fsync(fd)
                    finally:
                        os.close(fd)
                    self.used += len(line)
                    self.indexed.add(sha)
                self.last_ref = sha
                self.pending.clear()
                self.pending_bytes = 0
                return sha
            except OSError as error:
                self.failure = "CAPTURE_IO:" + type(error).__name__
                return None

    def verify(self):
        result = []
        manifest = self.root / "segments.jsonl"
        for line in manifest.read_text().splitlines() if manifest.exists() else []:
            row = json.loads(line)
            raw = gzip.decompress((self.root / (row["hash"] + ".json.gz")).read_bytes())
            obj = json.loads(raw)
            if digest(obj) != row["hash"] or len(obj["events"]) != row["count"]:
                raise ValueError("realtime segment integrity failure")
            result.extend(obj["events"])
        listed = (
            {json.loads(line)["hash"] for line in manifest.read_text().splitlines()}
            if manifest.exists()
            else set()
        )
        orphans = {
            p.name.removesuffix(".json.gz") for p in self.root.glob("*.json.gz")
        } - listed
        if orphans:
            raise ValueError("unindexed realtime segments: incomplete coverage")
        return result
