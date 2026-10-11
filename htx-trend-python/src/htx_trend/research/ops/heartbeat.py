"""心跳和健康检查系统"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Literal

from ..data.immutable_storage import _parse_interval_seconds


@dataclass
class Heartbeat:
    component: str
    timestamp: datetime
    last_processed_bar_time: datetime | None = None
    status: Literal["running", "idle", "error"] = "running"
    message: str | None = None
    
    def to_dict(self) -> dict:
        data = asdict(self)
        data["timestamp"] = self.timestamp.isoformat()
        if self.last_processed_bar_time:
            data["last_processed_bar_time"] = self.last_processed_bar_time.isoformat()
        return data
    
    @classmethod
    def from_dict(cls, data: dict) -> Heartbeat:
        data = data.copy()
        data["timestamp"] = datetime.fromisoformat(data["timestamp"])
        if data.get("last_processed_bar_time"):
            data["last_processed_bar_time"] = datetime.fromisoformat(
                data["last_processed_bar_time"]
            )
        return cls(**data)


class HeartbeatManager:
    def __init__(self, heartbeat_dir: Path):
        self.heartbeat_dir = heartbeat_dir
        self.heartbeat_dir.mkdir(parents=True, exist_ok=True)
    
    def write_heartbeat(
        self,
        component: str,
        last_processed_bar_time: datetime | None = None,
        status: Literal["running", "idle", "error"] = "running",
        message: str | None = None,
    ) -> None:
        heartbeat = Heartbeat(
            component=component,
            timestamp=datetime.now(UTC),
            last_processed_bar_time=last_processed_bar_time,
            status=status,
            message=message,
        )
        
        heartbeat_file = self.heartbeat_dir / f"{component}.json"
        heartbeat_file.write_text(
            json.dumps(heartbeat.to_dict(), ensure_ascii=False, indent=2)
        )
    
    def read_heartbeat(self, component: str) -> Heartbeat | None:
        heartbeat_file = self.heartbeat_dir / f"{component}.json"
        if not heartbeat_file.exists():
            return None
        
        try:
            data = json.loads(heartbeat_file.read_text())
            return Heartbeat.from_dict(data)
        except (json.JSONDecodeError, KeyError, ValueError):
            return None
    
    def check_health(
        self, component: str, max_age_seconds: int = 300
    ) -> tuple[bool, str]:
        heartbeat = self.read_heartbeat(component)
        
        if heartbeat is None:
            return False, f"No heartbeat found for {component}"
        
        age = datetime.now(UTC) - heartbeat.timestamp
        if age.total_seconds() > max_age_seconds:
            return False, f"Heartbeat stale: {age.total_seconds():.0f}s old (max {max_age_seconds}s)"
        
        if heartbeat.status == "error":
            return False, f"Component in error state: {heartbeat.message or 'unknown'}"
        
        return True, "healthy"
    
    def list_components(self) -> list[str]:
        return [f.stem for f in self.heartbeat_dir.glob("*.json")]


@dataclass
class BarStalenessCheck:
    symbol: str
    interval: str
    last_bar_time: datetime
    expected_next_close: datetime
    lag_minutes: float
    is_stale: bool
    threshold_minutes: float


def _as_utc(value: datetime | str) -> datetime:
    if isinstance(value, str):
        value = datetime.fromisoformat(value)
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def check_heartbeat_file(
    path: Path,
    max_age_seconds: int = 300,
    now: datetime | None = None,
) -> tuple[bool, str]:
    if not path.exists():
        return False, f"No heartbeat file at {path}"
    now = now or datetime.now(UTC)
    text = path.read_text().strip()
    try:
        stamp = datetime.fromisoformat(text)
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=UTC)
    except ValueError:
        stamp = datetime.fromtimestamp(path.stat().st_mtime, UTC)
    age = (now - stamp).total_seconds()
    if age > max_age_seconds:
        return False, f"Heartbeat stale: {age:.0f}s old (max {max_age_seconds}s)"
    return True, "healthy"


def check_health(
    heartbeat_dir: Path | None = None,
    collector_state_path: Path | None = None,
    max_heartbeat_age_seconds: int = 300,
    bar_staleness_threshold_minutes: int = 10,
    now: datetime | None = None,
    heartbeat_file: Path | None = None,
) -> tuple[bool, dict[str, Any]]:
    """心跳 + 各 timeframe 的 last_bar_time 新鲜度。"""
    now = now or datetime.now(UTC)
    is_healthy = True
    details: dict[str, Any] = {"heartbeat": {}, "bar_staleness": []}

    if heartbeat_file is not None:
        ok, message = check_heartbeat_file(
            heartbeat_file, max_heartbeat_age_seconds, now=now
        )
        details["heartbeat"]["file"] = {
            "path": str(heartbeat_file),
            "status": "ok" if ok else "stale",
            "message": message,
        }
        if not ok:
            is_healthy = False

    if heartbeat_dir is not None:
        mgr = HeartbeatManager(heartbeat_dir)
        components = mgr.list_components()
        if not components:
            details["heartbeat"] = {"status": "missing", "message": "No heartbeat files found"}
            is_healthy = False
        for component in components:
            ok, message = mgr.check_health(component, max_age_seconds=max_heartbeat_age_seconds)
            details["heartbeat"][component] = {
                "status": "ok" if ok else "stale",
                "message": message,
            }
            if not ok:
                is_healthy = False

    if collector_state_path and collector_state_path.exists():
        try:
            state = json.loads(collector_state_path.read_text())
        except (json.JSONDecodeError, OSError) as exc:
            details["bar_staleness_error"] = str(exc)
            return False, details

        for key, info in state.items():
            if not isinstance(info, dict):
                continue
            symbol = info.get("symbol") or str(key)
            interval = info.get("interval") or "4hour"
            last_raw = info.get("last_bar_time")
            if not last_raw:
                continue
            last_bar_time = _as_utc(last_raw)
            interval_seconds = _parse_interval_seconds(interval)
            expected_next_close = last_bar_time + timedelta(seconds=2 * interval_seconds)
            stale_deadline = expected_next_close + timedelta(
                minutes=float(bar_staleness_threshold_minutes)
            )
            lag_minutes = (now - expected_next_close).total_seconds() / 60.0
            is_stale = now > stale_deadline
            check = BarStalenessCheck(
                symbol=symbol,
                interval=interval,
                last_bar_time=last_bar_time,
                expected_next_close=expected_next_close,
                lag_minutes=lag_minutes,
                is_stale=is_stale,
                threshold_minutes=float(bar_staleness_threshold_minutes),
            )
            payload = asdict(check)
            payload["last_bar_time"] = last_bar_time.isoformat()
            payload["expected_next_close"] = expected_next_close.isoformat()
            details["bar_staleness"].append(payload)
            if is_stale:
                is_healthy = False

    return is_healthy, details


def _cli_heartbeat_loop() -> None:
    import argparse
    import time

    parser = argparse.ArgumentParser(description="HTX Trend heartbeat writer")
    parser.add_argument("--interval", type=int, default=60)
    parser.add_argument("--file", type=Path, required=True)
    args = parser.parse_args()
    args.file.parent.mkdir(parents=True, exist_ok=True)
    while True:
        args.file.write_text(datetime.now(UTC).isoformat() + "\n")
        time.sleep(args.interval)


if __name__ == "__main__":
    _cli_heartbeat_loop()
