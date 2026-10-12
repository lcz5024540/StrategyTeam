"""Runtime files live under run/; systemd ReadWritePaths are dirs with '-'."""

from __future__ import annotations

import subprocess
import sys
from argparse import Namespace
from datetime import UTC, datetime
from pathlib import Path

import yaml

from htx_trend.research.cli import cmd_health
from htx_trend.research.ops.runtime_paths import (
    collector_state_file,
    heartbeat_file,
    status_file,
)
from htx_trend.research.ops.status import (
    HealthStatus,
    StatusInterface,
    get_status_file_path,
    set_status_file_path,
    update_status,
)


ROOT = Path(__file__).resolve().parents[2]
STATE = Path("/opt/coinstrategy/htx-trend-py-state")
RUN = STATE / "run"
ALLOWED_RW = frozenset(
    {
        f"-{STATE / 'data'}",
        f"-{STATE / 'logs'}",
        f"-{STATE / 'run'}",
        f"-{STATE / 'evidence'}",
        f"-{STATE / 'backups'}",
    }
)


def _units() -> dict[str, str]:
    systemd = ROOT / "deployment" / "systemd"
    return {
        path.name: path.read_text()
        for path in systemd.glob("*.service")
    }


def _readwrite_paths(text: str) -> list[str]:
    return [
        line.split("=", 1)[1].strip()
        for line in text.splitlines()
        if line.startswith("ReadWritePaths=")
    ]


def test_systemd_readwrite_paths_are_optional_dirs_only():
    units = _units()
    assert set(units) == {
        "htx-trend-collector.service",
        "htx-trend-heartbeat.service",
        "htx-trend-probe-1contract@.service",
    }
    for name, text in units.items():
        paths = _readwrite_paths(text)
        assert paths, f"{name} missing ReadWritePaths"
        assert set(paths) == ALLOWED_RW
        for item in paths:
            assert item.startswith("-"), item
            assert not item.endswith((".json", ".txt"))


def test_collector_and_heartbeat_write_under_run_dir():
    cfg = yaml.safe_load((ROOT / "configs" / "collector.yaml").read_text())
    assert Path(cfg["collector"]["state"]["file_path"]) == RUN / "collector_state.json"
    heartbeat = _units()["htx-trend-heartbeat.service"]
    assert f"--file {RUN / 'heartbeat.txt'}" in heartbeat.replace("\\\n", " ")


def test_health_and_status_defaults_match_collector_run_dir(tmp_path, monkeypatch):
    workspace = tmp_path / "ws"
    run = workspace / "run"
    run.mkdir(parents=True)
    (run / "collector_state.json").write_text("{}")
    (run / "heartbeat.txt").write_text(datetime.now(UTC).isoformat() + "\n")
    set_status_file_path(status_file(workspace))
    update_status(
        StatusInterface(
            run_id="collector",
            component="collector",
            timestamp=datetime.now(UTC),
            health=HealthStatus.HEALTHY,
        )
    )

    assert collector_state_file(workspace) == run / "collector_state.json"
    assert heartbeat_file(workspace) == run / "heartbeat.txt"
    assert status_file(workspace) == run / "status.json"
    assert get_status_file_path() == run / "status.json"

    monkeypatch.setattr("htx_trend.research.cli.sys.exit", lambda code=0: (_ for _ in ()).throw(SystemExit(code)))
    try:
        cmd_health(
            Namespace(
                workspace=str(workspace),
                max_age=300,
                threshold_minutes=10,
                collector_state=None,
            )
        )
    except SystemExit as exc:
        assert exc.code == 0

    env = {**dict(**__import__("os").environ), "PYTHONPATH": str(ROOT / "src")}
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "htx_trend",
            "status",
            "--json",
            "--workspace",
            str(workspace),
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=15,
    )
    assert result.returncode == 0
    assert "collector" in result.stdout
