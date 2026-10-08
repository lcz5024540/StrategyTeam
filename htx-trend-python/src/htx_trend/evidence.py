"""Fail-open, append-only runtime K-line evidence files.

Evidence is intentionally local JSON instead of a synchronous database write.
The writer has a bounded queue and drops evidence under pressure; strategy
calculation never waits for IO and the drop is observable through ``status``.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from decimal import Decimal
from hashlib import sha256
from pathlib import Path
from queue import Full, Queue
from threading import Event, Thread
from typing import Any
import json

from .market_data import Candle, KlineWindow


@dataclass(frozen=True, slots=True)
class CaptureRequest:
    stage: str
    window: KlineWindow
    target_bar_time: int | None = None
    run_id: str | None = None
    factor_snapshot_id: int | None = None
    position_id: int | None = None


@dataclass(frozen=True, slots=True)
class ArchiveStatus:
    attempts: int = 0
    completed: int = 0
    complete_windows: int = 0
    partial_windows: int = 0
    failures: int = 0
    dropped: int = 0
    queue_depth: int = 0


class RuntimeKlineArchive:
    """Asynchronous local evidence archive compatible with Java v1 snapshot semantics."""

    def __init__(self, root: Path, *, queue_capacity: int = 2_048) -> None:
        self._root = root
        self._queue: Queue[CaptureRequest | None] = Queue(maxsize=queue_capacity)
        self._stop = Event()
        self._status = ArchiveStatus()
        self._worker = Thread(target=self._run, name="htx-runtime-kline-evidence", daemon=True)
        self._worker.start()

    def capture(self, request: CaptureRequest) -> bool:
        if not request.stage or not request.window.contract_code or not request.window.period or not request.window.candles:
            return False
        self._status = self._replace(attempts=self._status.attempts + 1)
        try:
            self._queue.put_nowait(request)
            return True
        except Full:
            self._status = self._replace(dropped=self._status.dropped + 1)
            return False

    @property
    def status(self) -> ArchiveStatus:
        return self._replace(queue_depth=self._queue.qsize())

    def flush(self) -> None:
        self._queue.join()

    def close(self) -> None:
        if not self._stop.is_set():
            self.flush()
            self._stop.set()
            self._queue.put(None)
            self._worker.join(timeout=5)

    def __enter__(self) -> "RuntimeKlineArchive":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def _run(self) -> None:
        while True:
            request = self._queue.get()
            try:
                if request is None:
                    return
                self._persist(request)
            except Exception:
                self._status = self._replace(failures=self._status.failures + 1)
            finally:
                self._queue.task_done()

    def _persist(self, request: CaptureRequest) -> None:
        window = request.window
        candles = window.candles
        last_bar = candles[-1].open_time
        target = last_bar if request.target_bar_time is None or request.target_bar_time <= 0 else min(request.target_bar_time, last_bar)
        identity = ":".join(("runtime-kline-v1", request.stage, window.contract_code, window.period, str(target),
                             window.window_hash, request.run_id or "-", str(request.position_id or "-"),
                             str(request.factor_snapshot_id or "-")))
        snapshot_path = self._root / "snapshots" / f"{sha256(identity.encode()).hexdigest()}.json"
        if snapshot_path.exists():
            return
        captured_at = datetime.now(UTC).isoformat()
        for candle in candles:
            candle_path = self._root / "candles" / f"{candle.content_hash}.json"
            if not candle_path.exists():
                self._write_json(candle_path, self._candle_payload(candle, captured_at))
        payload: dict[str, Any] = {
            "snapshot_key": identity, "run_id": request.run_id, "factor_snapshot_id": request.factor_snapshot_id,
            "position_id": request.position_id, "capture_stage": request.stage, "contract_code": window.contract_code,
            "period": window.period, "target_bar_time": target, "window_start_bar_time": candles[0].open_time,
            "window_end_bar_time": last_bar, "expected_window_size": window.expected_size,
            "actual_window_size": len(candles), "complete_window": window.is_complete,
            "window_sha256": window.window_hash, "source_kind": "HTX_PUBLIC_RUNTIME",
            "source_reference": "market/history/kline", "data_integrity": "COMPLETE" if window.is_complete else "PARTIAL",
            "captured_at": captured_at, "candle_hashes": [item.content_hash for item in candles],
        }
        self._write_json(snapshot_path, payload)
        self._status = self._replace(completed=self._status.completed + 1,
                                     complete_windows=self._status.complete_windows + int(window.is_complete),
                                     partial_windows=self._status.partial_windows + int(not window.is_complete))

    def _write_json(self, path: Path, payload: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")), encoding="utf-8")
        temporary.replace(path)

    @staticmethod
    def _candle_payload(candle: Candle, captured_at: str) -> dict[str, Any]:
        def primitive(value: Decimal | int | None) -> str | int | None:
            return str(value) if isinstance(value, Decimal) else value
        return {
            "candle_hash": candle.content_hash, "bar_time": candle.open_time, "open": primitive(candle.open),
            "high": primitive(candle.high), "low": primitive(candle.low), "close": primitive(candle.close),
            "amount": primitive(candle.amount), "volume": primitive(candle.volume),
            "turnover": primitive(candle.turnover), "trade_count": candle.trade_count,
            "source_kind": "HTX_PUBLIC_RUNTIME", "captured_at": captured_at,
        }

    def _replace(self, **changes: int) -> ArchiveStatus:
        values = {field: getattr(self._status, field) for field in ArchiveStatus.__dataclass_fields__}
        values.update(changes)
        return ArchiveStatus(**values)
