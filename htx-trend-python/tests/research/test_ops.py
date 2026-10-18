"""测试运维监控功能"""

from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from htx_trend.research.ops import (
    Heartbeat,
    HeartbeatManager,
    HealthStatus,
    StatusInterface,
    get_status,
    update_status,
)


def test_heartbeat_write_read(tmp_path):
    mgr = HeartbeatManager(tmp_path)
    
    mgr.write_heartbeat(
        component="test_component",
        last_processed_bar_time=datetime(2024, 1, 1, 12, 0, tzinfo=UTC),
        status="running",
        message="Processing data",
    )
    
    hb = mgr.read_heartbeat("test_component")
    
    assert hb is not None
    assert hb.component == "test_component"
    assert hb.status == "running"
    assert hb.message == "Processing data"


def test_heartbeat_health_check(tmp_path):
    mgr = HeartbeatManager(tmp_path)
    
    mgr.write_heartbeat(component="healthy", status="running")
    
    healthy, msg = mgr.check_health("healthy", max_age_seconds=10)
    assert healthy
    
    healthy, msg = mgr.check_health("missing", max_age_seconds=10)
    assert not healthy
    assert "No heartbeat" in msg


def test_heartbeat_stale_detection(tmp_path):
    mgr = HeartbeatManager(tmp_path)
    
    old_time = datetime.now(UTC) - timedelta(minutes=10)
    hb = Heartbeat(
        component="stale",
        timestamp=old_time,
        status="running",
    )
    
    hb_file = tmp_path / "stale.json"
    import json
    hb_file.write_text(json.dumps(hb.to_dict()))
    
    healthy, msg = mgr.check_health("stale", max_age_seconds=60)
    assert not healthy
    assert "stale" in msg.lower()


def test_status_interface(tmp_path):
    from htx_trend.research.ops.status import set_status_file_path

    set_status_file_path(tmp_path / "run" / "status.json")
    status = StatusInterface(
        run_id="TEST001",
        component="backtest",
        timestamp=datetime.now(UTC),
        health=HealthStatus.HEALTHY,
        total_pnl=100.0,
        account_equity=1100.0,
    )
    
    update_status(status)
    
    retrieved = get_status()
    assert retrieved is not None
    assert retrieved.run_id == "TEST001"
    assert retrieved.total_pnl == 100.0
    
    status_dict = status.to_dict()
    assert "run_id" in status_dict
    assert "positions" in status_dict
    assert "circuit_breaker" in status_dict
