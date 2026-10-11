"""Exactly one unified systemd unit set under deployment/systemd."""

from __future__ import annotations

from pathlib import Path

UNIFIED = frozenset(
    {
        "htx-trend-collector.service",
        "htx-trend-heartbeat.service",
    }
)


def test_repo_has_only_unified_venv_systemd_units():
    root = Path(__file__).resolve().parents[2]
    services = [
        path
        for path in root.rglob("*.service")
        if ".venv" not in path.parts and "venv" not in path.parts
    ]
    names = {path.name for path in services}
    assert names == UNIFIED
    for path in services:
        assert path.parent.name == "systemd"
        assert path.parent.parent.name == "deployment"
        text = path.read_text()
        assert "WorkingDirectory=/opt/coinstrategy/htx-trend-py" in text
        assert "/opt/coinstrategy/htx-trend-py/.venv/bin/python" in text
        assert "User=ubuntu" in text
        assert "coinstrategy-python-strategy@" not in text
    collector = next(path for path in services if path.name == "htx-trend-collector.service")
    assert "--config" in collector.read_text()
    assert "configs/collector.yaml" in collector.read_text()
    for path in services:
        text = path.read_text()
        for line in text.splitlines():
            if line.startswith("ReadWritePaths="):
                value = line.split("=", 1)[1]
                assert value.startswith("-")
                assert value.endswith(("/data", "/logs", "/run"))
                assert not value.endswith((".json", ".txt"))
