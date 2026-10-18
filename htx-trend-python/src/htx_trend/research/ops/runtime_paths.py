"""Canonical runtime file locations under workspace/run/."""

from __future__ import annotations

from pathlib import Path

RUN_DIR_NAME = "run"
STATUS_FILENAME = "status.json"
HEARTBEAT_FILENAME = "heartbeat.txt"
COLLECTOR_STATE_FILENAME = "collector_state.json"
HEARTBEATS_DIRNAME = "heartbeats"


def run_dir(workspace: Path | str) -> Path:
    return Path(workspace) / RUN_DIR_NAME


def status_file(workspace: Path | str) -> Path:
    return run_dir(workspace) / STATUS_FILENAME


def heartbeat_file(workspace: Path | str) -> Path:
    return run_dir(workspace) / HEARTBEAT_FILENAME


def collector_state_file(workspace: Path | str) -> Path:
    return run_dir(workspace) / COLLECTOR_STATE_FILENAME


def heartbeats_dir(workspace: Path | str) -> Path:
    return run_dir(workspace) / HEARTBEATS_DIRNAME
