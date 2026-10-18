"""测试状态原子写入和跨进程读取"""

import json
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

import pytest

from htx_trend.research.ops.status import (
    HealthStatus,
    StatusInterface,
    get_status,
    set_status_file_path,
    update_status,
)


def test_atomic_status_write_read_same_process():
    """测试同一进程内原子写入和读取"""
    with tempfile.TemporaryDirectory() as tmpdir:
        status_path = Path(tmpdir) / "status.json"
        set_status_file_path(status_path)
        
        # 写入状态
        status = StatusInterface(
            run_id="TEST_RUN",
            component="test",
            timestamp=datetime.now(timezone.utc),
            health=HealthStatus.HEALTHY,
            total_pnl=100.0,
            account_equity=1100.0,
        )
        
        update_status(status)
        
        # 读取状态
        loaded = get_status()
        
        assert loaded is not None
        assert loaded.run_id == "TEST_RUN"
        assert loaded.total_pnl == 100.0
        assert loaded.account_equity == 1100.0
        assert loaded.health == HealthStatus.HEALTHY


def test_atomic_status_cross_process_read():
    """测试跨进程读取状态（另一个进程写入，当前进程读取）"""
    with tempfile.TemporaryDirectory() as tmpdir:
        status_path = Path(tmpdir) / "status.json"
        
        # 在子进程中写入状态
        src_dir = Path(__file__).parent.parent.parent.parent / "src"
        writer_script = f"""
import sys
from pathlib import Path
from datetime import datetime, timezone
sys.path.insert(0, str(Path('{src_dir}')))

from htx_trend.research.ops.status import (
    HealthStatus,
    StatusInterface,
    set_status_file_path,
    update_status,
)

set_status_file_path(Path('{status_path}'))

status = StatusInterface(
    run_id="CROSS_PROCESS",
    component="writer",
    timestamp=datetime.now(timezone.utc),
    health=HealthStatus.HEALTHY,
    total_pnl=250.0,
    account_equity=1250.0,
    message="Written by subprocess",
)

update_status(status)
print("STATUS_WRITTEN")
"""
        
        # 执行子进程
        result = subprocess.run(
            ["python3", "-c", writer_script],
            capture_output=True,
            text=True,
            timeout=10,
            cwd=str(Path.cwd()),
        )
        
        assert result.returncode == 0, f"Writer failed: {result.stderr}"
        assert "STATUS_WRITTEN" in result.stdout
        
        # 在当前进程读取
        set_status_file_path(status_path)
        loaded = get_status()
        
        assert loaded is not None
        assert loaded.run_id == "CROSS_PROCESS"
        assert loaded.total_pnl == 250.0
        assert loaded.message == "Written by subprocess"


def test_atomic_status_concurrent_writes_no_corruption():
    """测试并发写入不会损坏文件"""
    with tempfile.TemporaryDirectory() as tmpdir:
        status_path = Path(tmpdir) / "status.json"
        set_status_file_path(status_path)
        
        # 快速连续写入多次
        for i in range(10):
            status = StatusInterface(
                run_id=f"RUN_{i}",
                component="test",
                timestamp=datetime.now(timezone.utc),
                health=HealthStatus.HEALTHY,
                total_pnl=float(i * 10),
                account_equity=1000.0 + i * 10,
            )
            update_status(status)
        
        # 最终状态应该可读且有效
        loaded = get_status()
        assert loaded is not None
        assert loaded.run_id.startswith("RUN_")
        assert loaded.total_pnl >= 0
        
        # 文件内容应该是有效 JSON
        with open(status_path) as f:
            data = json.load(f)
        
        assert "run_id" in data
        assert "health" in data


def test_status_file_missing_returns_none():
    """测试状态文件不存在时返回 None"""
    with tempfile.TemporaryDirectory() as tmpdir:
        status_path = Path(tmpdir) / "nonexistent.json"
        set_status_file_path(status_path)
        
        loaded = get_status()
        assert loaded is None


def test_status_json_readable_by_cli_from_another_process():
    """运行进程原子写入 status.json，另一进程 `status --json` 可读。"""
    with tempfile.TemporaryDirectory() as tmpdir:
        status_path = Path(tmpdir) / "status.json"
        src_dir = Path(__file__).parent.parent.parent.parent / "src"
        writer_script = f"""
import sys
from pathlib import Path
from datetime import datetime, timezone
sys.path.insert(0, '{src_dir}')
from htx_trend.research.ops.status import (
    HealthStatus, StatusInterface, set_status_file_path, update_status,
)
set_status_file_path(Path('{status_path}'))
update_status(StatusInterface(
    run_id="CLI_CROSS",
    component="writer",
    timestamp=datetime.now(timezone.utc),
    health=HealthStatus.HEALTHY,
    total_pnl=77.0,
    account_equity=1077.0,
    message="written-for-cli",
))
print("STATUS_WRITTEN")
"""
        writer = subprocess.run(
            ["python3", "-c", writer_script],
            capture_output=True,
            text=True,
            timeout=10,
        )
        assert writer.returncode == 0, writer.stderr
        assert "STATUS_WRITTEN" in writer.stdout

        reader = subprocess.run(
            [
                "python3",
                "-m",
                "htx_trend.research.cli",
                "status",
                "--json",
                "--status-file",
                str(status_path),
            ],
            capture_output=True,
            text=True,
            timeout=10,
            cwd=str(Path(__file__).resolve().parents[3]),
            env={**__import__("os").environ, "PYTHONPATH": str(src_dir)},
        )
        assert reader.returncode == 0, reader.stderr
        payload = json.loads(reader.stdout)
        assert payload["run_id"] == "CLI_CROSS"
        assert payload["total_pnl"] == 77.0
        assert payload["message"] == "written-for-cli"


def test_status_update_creates_parent_dirs():
    """测试更新状态时自动创建父目录"""
    with tempfile.TemporaryDirectory() as tmpdir:
        status_path = Path(tmpdir) / "nested" / "dir" / "status.json"
        set_status_file_path(status_path)
        
        status = StatusInterface(
            run_id="NESTED_TEST",
            component="test",
            timestamp=datetime.now(timezone.utc),
            health=HealthStatus.HEALTHY,
        )
        
        update_status(status)
        
        assert status_path.exists()
        assert status_path.parent.exists()
