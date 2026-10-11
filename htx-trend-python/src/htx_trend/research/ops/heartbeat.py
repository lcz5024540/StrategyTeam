"""心跳和健康检查系统"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Literal


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
