"""Exactly one unified systemd unit set under deployment/systemd."""

from __future__ import annotations

from pathlib import Path

UNIFIED = frozenset(
    {
        "htx-trend-collector.service",
        "htx-trend-heartbeat.service",
    }
)
CURRENT = "/opt/coinstrategy/htx-trend-py/current"
VENV = "/opt/coinstrategy/htx-trend-py-venv"
STATE = "/opt/coinstrategy/htx-trend-py-state"


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
        assert f"WorkingDirectory={CURRENT}" in text
        assert f"{VENV}/bin/python" in text
        assert "/opt/coinstrategy/htx-trend-py/.venv" not in text
        assert f"PYTHONPATH={CURRENT}/src" in text
        assert "User=ubuntu" in text
        assert "coinstrategy-python-strategy@" not in text
        assert f"{STATE}/data" in text
        assert f"{STATE}/logs" in text
        assert f"{STATE}/run" in text
    collector = next(path for path in services if path.name == "htx-trend-collector.service")
    assert "--config" in collector.read_text()
    assert f"{CURRENT}/configs/collector.yaml" in collector.read_text()
    for path in services:
        text = path.read_text()
        for line in text.splitlines():
            if line.startswith("ReadWritePaths="):
                value = line.split("=", 1)[1]
                assert value.startswith("-")
                assert value.startswith(f"-{STATE}/")
                assert not value.endswith((".json", ".txt"))
                assert "/releases/" not in value
                assert "/current" not in value or value.endswith("/current") is False


def test_systemd_units_reference_no_in_package_venv_or_state():
    root = Path(__file__).resolve().parents[2]
    for path in (root / "deployment" / "systemd").glob("*.service"):
        text = path.read_text()
        assert "/opt/coinstrategy/htx-trend-py/.venv" not in text
        assert "/opt/coinstrategy/htx-trend-py/data" not in text
        assert "/opt/coinstrategy/htx-trend-py/logs" not in text
        assert "/opt/coinstrategy/htx-trend-py/run" not in text
        assert "pip install -e" not in text
