"""P0: freeze must ship and hash the packaged 11-entry bad_ticks.yaml."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from htx_trend.research import cli as cli_mod
from htx_trend.research.cli import cmd_freeze
from htx_trend.research.data.quality import bad_ticks_hash

from tests.research.data.test_stitch_point_and_quality import (
    _around_stitch,
    _freeze_args,
    _write_raw,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
PACKAGED_BAD_TICKS = REPO_ROOT / "data" / "quality" / "bad_ticks.yaml"
EXPECTED_BAD_TICKS_HASH = (
    "sha256:09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b"
)


def _dataset_manifest(workspace: Path) -> dict:
    datasets = workspace / "data" / "datasets"
    dataset_dir = next(
        path for path in datasets.iterdir() if path.is_dir() and path.name != "manifests"
    )
    return json.loads((dataset_dir / "manifest.json").read_text())


def test_packaged_bad_ticks_yaml_has_exactly_eleven_entries():
    payload = yaml.safe_load(PACKAGED_BAD_TICKS.read_text())
    ticks = payload.get("ticks") or []
    assert len(ticks) == 11
    digest = bad_ticks_hash(PACKAGED_BAD_TICKS)
    assert digest == EXPECTED_BAD_TICKS_HASH
    assert digest.startswith("sha256:09e1ed52")
    assert digest.endswith("a4b")


def test_missing_explicit_bad_ticks_path_is_hard_error(tmp_path):
    times = _around_stitch(before=80, after=200)
    _write_raw(tmp_path, times)
    missing = tmp_path / "missing-bad-ticks.yaml"
    with pytest.raises(SystemExit, match="bad_ticks"):
        cmd_freeze(_freeze_args(tmp_path, bad_ticks=str(missing)))


def test_missing_workspace_and_packaged_bad_ticks_is_hard_error(tmp_path, monkeypatch):
    times = _around_stitch(before=80, after=200)
    _write_raw(tmp_path, times)
    monkeypatch.setattr(
        cli_mod,
        "packaged_bad_ticks_path",
        lambda: tmp_path / "packaged-missing.yaml",
    )
    assert not (tmp_path / "data" / "quality" / "bad_ticks.yaml").exists()
    with pytest.raises(SystemExit, match="bad_ticks"):
        cmd_freeze(_freeze_args(tmp_path, bad_ticks=None))


def test_freeze_from_packaged_layout_records_canonical_bad_ticks_hash(tmp_path):
    times = _around_stitch(before=80, after=200)
    _write_raw(tmp_path, times)
    assert not (tmp_path / "data" / "quality" / "bad_ticks.yaml").exists()
    cmd_freeze(_freeze_args(tmp_path, bad_ticks=None))
    written = _dataset_manifest(tmp_path)
    assert written["bad_ticks_hash"] == EXPECTED_BAD_TICKS_HASH
    assert written["bad_ticks_hash"].startswith("sha256:09e1ed52")
    assert written["bad_ticks_hash"].endswith("a4b")
    payload = yaml.safe_load(PACKAGED_BAD_TICKS.read_text())
    assert len(payload["ticks"]) == 11
