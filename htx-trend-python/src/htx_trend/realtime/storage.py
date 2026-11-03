"""Explicit plan persistence and quota-bound, verifiable event segments."""

from __future__ import annotations

from contextlib import nullcontext
from functools import wraps
import gzip
from hashlib import sha256
import json
import os
from pathlib import Path

import sqlite3
import tempfile
import threading
from .segment_codec import segment_events
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



def recorder_lease(method):
    @wraps(method)
    def guarded(self, *args, **kwargs):
        control = getattr(self, 'segments', None)
        try:
            with control.lease() if control else nullcontext():
                with control.mutation() if control and method.__name__ == 'flush' else nullcontext():
                    return method(self, *args, **kwargs)
        except (OSError, sqlite3.Error, ValueError):
            if not control or method.__name__ not in {'append','flush'}:
                raise
            from ..sealing.consumers import fence_failure
            fence_failure(control)
            self.failure = 'CAPTURE_CONTROL_IO'
            return False if method.__name__ == 'append' else None
    return guarded


class SegmentRecorder:
    """Append-only gzip segments. No deletion, no silent quota loss or full-window copies."""

    def __init__(
        self,
        root,
        *,
        max_bytes=2 * 1024**3,
        segment_bytes=1024**2,
        gzip_level=9,
        json_ready=False,
        segmented=False,
        segment_contract="capture-public",
    ):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.segments = None
        marker = self.root / '.sealing/config.json'
        if marker.exists() and not segmented:
            raise ValueError('segmented capture requires explicit segment-aware reader/writer')
        if segmented:
            from ..sealing.control import Control, initialize
            if not marker.exists():
                initialize(self.root, kind='capture', contract=segment_contract)
            self.segments = Control(self.root, contract=segment_contract)
            self.segments.begin('recorder')
            self.segment_revision = -1
        self.max_bytes, self.segment_bytes = max_bytes, segment_bytes
        self.used = sum(p.stat().st_size for p in self.root.iterdir() if p.is_file())
        self.pending = []
        self.encoded = []
        self.gzip_level, self.json_ready = gzip_level, json_ready
        self.pending_bytes = 0
        self.failure = None
        self.lock = threading.RLock()
        self.last_ref = None
        manifest = self.root / "segments.jsonl"
        self.records = (
            [json.loads(line) for line in manifest.read_text().splitlines()]
            if manifest.exists()
            else []
        )
        if self.segments:
            self.records = self.segments.local_records()
            self.last_ref = self.records[-1]['hash'] if self.records else None
        self.indexed = {record["hash"] for record in self.records}

    @recorder_lease
    def append(self, event):
        with self.lock:
            self._refresh_released()
            if self.failure:
                return False
            value = event.dump() if hasattr(event, "dump") else event
            encoded = (
                json.dumps(
                    value,
                    sort_keys=True,
                    separators=(",", ":"),
                    ensure_ascii=False,
                    allow_nan=False,
                ).encode()
                if self.json_ready
                else canonical(value)
            )
            self.pending.append(value)
            self.encoded.append(encoded)
            self.pending_bytes += len(encoded)
            return (
                self.flush() is not None
                if self.pending_bytes >= self.segment_bytes
                else True
            )

    @recorder_lease
    def flush(self):
        with self.lock:
            self._refresh_released()
            if self.failure:
                return None
            if not self.pending:
                return self.last_ref
            # Each immutable observation is canonicalized once at append. Joining
            # its bytes preserves the old canonical object/hash exactly.
            raw = (
                b'{"events":['
                + b",".join(self.encoded)
                + b'],"schema":"htx-realtime-segment-v1"}'
            )
            sha = sha256(raw).hexdigest()
            packed = gzip.compress(raw, compresslevel=self.gzip_level, mtime=0)
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
                self.encoded.clear()
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
                    if sha256(gzip.decompress(path.read_bytes())).hexdigest() != sha:
                        raise OSError("orphan segment hash mismatch")
                    if not self.segments:
                        with (self.root / "segments.jsonl").open("ab") as f:
                            f.write(line)
                            f.flush()
                            os.fsync(f.fileno())
                    fd = os.open(self.root, os.O_RDONLY)
                    try:
                        os.fsync(fd)
                    finally:
                        os.close(fd)
                    if not self.segments:
                        self.used += len(line)
                    self.indexed.add(sha)
                    self.records.append(record)
                if self.segments:
                    self.segments.track(sha, path.name, metadata=record)
                    self.segment_revision = self.segments.layout_revision()
                self.last_ref = sha
                self.pending.clear()
                self.encoded.clear()
                self.pending_bytes = 0
                return sha
            except (OSError, sqlite3.Error, ValueError) as error:
                if self.segments:
                    from ..sealing.consumers import fence_failure
                    fence_failure(self.segments)
                self.failure = 'CAPTURE_IO:' + type(error).__name__
                return None

    def _refresh_released(self):
        if not self.segments:
            return
        try:
            revision = self.segments.layout_revision()
        except Exception:
            from ..sealing.consumers import fence_failure
            fence_failure(self.segments)
            self.failure = 'CAPTURE_CONTROL_IO'
            return
        if revision != self.segment_revision:
            self.records = self.segments.local_records()
            self.indexed = {r['hash'] for r in self.records}
            self.used = sum(p.stat().st_size for p in self.root.iterdir() if p.is_file())
            self.segment_revision = revision

    def segment_boundary(self, *, extra_roots=(), safe=True, force=False):
        if not self.segments:
            return
        roots = set(extra_roots)
        if self.last_ref:
            roots.add(self.last_ref)
        try:
            self.segments.ready('recorder', roots, error=None if safe and not self.failure else 'CAPTURE_HANDOFF_FAILED')
            if safe and not self.failure:
                self.segments.seal(force=force)
        except Exception:
            # Maintenance failure blocks deletion; it does not alter order/exit behavior.
            try:
                self.segments.ready('recorder', roots, error='CAPTURE_HANDOFF_FAILED')
            except Exception:
                pass  # Existing durable begin/poison blocks reclamation.

    @recorder_lease
    def verify(self):
        result = []
        manifest = self.root / "segments.jsonl"
        if self.segments:
            with self.segments.guard:
                if self.segments.db.execute('SELECT 1 FROM objects WHERE local=0').fetchone():
                    raise ValueError('capture includes archived generations; restore required for complete audit')
            records = self.segments.local_records()
        else:
            records = [json.loads(line) for line in manifest.read_text().splitlines()] if manifest.exists() else []
        for row in records:
            raw = gzip.decompress((self.root / (row["hash"] + ".json.gz")).read_bytes())
            obj = json.loads(raw)
            events = segment_events(obj)
            if digest(obj) != row["hash"] or len(events) != row["count"]:
                raise ValueError("realtime segment integrity failure")
            result.extend(events)
        listed = (
            {json.loads(line)["hash"] for line in manifest.read_text().splitlines()}
            if manifest.exists()
            else set()
        )
        if self.segments:
            listed = {r["hash"] for r in records}
        orphans = {
            p.name.removesuffix(".json.gz") for p in self.root.glob("*.json.gz")
        } - listed
        if orphans:
            raise ValueError("unindexed realtime segments: incomplete coverage")
        return result
