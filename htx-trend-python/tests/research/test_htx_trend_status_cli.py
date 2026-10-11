"""health/status are exposed on python -m htx_trend; missing status is non-zero."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def test_htx_trend_status_json_exits_nonzero_without_status(tmp_path):
    env = {"PYTHONPATH": str(Path(__file__).resolve().parents[2] / "src")}
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "htx_trend",
            "status",
            "--json",
            "--status-file",
            str(tmp_path / "missing-status.json"),
        ],
        capture_output=True,
        text=True,
        env={**dict(**__import__("os").environ), **env},
        timeout=15,
    )
    assert result.returncode != 0


def test_research_cli_status_json_still_exits_nonzero_without_status(tmp_path):
    env = {"PYTHONPATH": str(Path(__file__).resolve().parents[2] / "src")}
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "htx_trend.research.cli",
            "status",
            "--json",
            "--status-file",
            str(tmp_path / "missing-status.json"),
        ],
        capture_output=True,
        text=True,
        env={**dict(**__import__("os").environ), **env},
        timeout=15,
    )
    assert result.returncode != 0
