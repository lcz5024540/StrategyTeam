"""package.py records an explicit branch and never writes detached HEAD."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import package as package_mod


def test_parse_args_accepts_branch_and_version():
    args = package_mod.parse_args(["--branch", "release/mvp1", "--version", "0.2.0"])
    assert args.branch == "release/mvp1"
    assert args.version == "0.2.0"


def test_detached_head_without_branch_fails():
    with pytest.raises(SystemExit, match="detached"):
        package_mod.resolve_branch("HEAD", None)


def test_detached_head_with_branch_override():
    assert package_mod.resolve_branch("HEAD", "cursor/htx-trend-research-mvp1-7fc2") == (
        "cursor/htx-trend-research-mvp1-7fc2"
    )


def test_explicit_head_branch_is_rejected():
    with pytest.raises(SystemExit, match="HEAD"):
        package_mod.resolve_branch("main", "HEAD")


def test_delivery_template_documents_packaging_env_and_anchored_exclude(tmp_path):
    path = package_mod.generate_delivery_template(
        "0.2.0",
        {
            "commit": "abc123",
            "short_sha": "abc12300",
            "branch": "release/mvp1",
            "dirty": False,
        },
        "deadbeef",
        tmp_path / "htx-trend-python-0.2.0-abc12300.tar.gz",
    )
    text = path.read_text(encoding="utf-8")
    assert "Python 3.12" in text
    assert "requirements.lock" in text
    assert "deploy" in text.lower()
    assert "--exclude '/data'" in text or "--exclude /data" in text
    assert "src/htx_trend/research/data" in text


def test_deploy_sync_uses_anchored_data_exclude():
    text = (ROOT / "scripts" / "deploy_sync.sh").read_text(encoding="utf-8")
    assert "--exclude '/data" in text or "--exclude /data" in text
    assert "--exclude 'data'" not in text
    assert "--exclude data" not in text
    assert "research/data" in text
