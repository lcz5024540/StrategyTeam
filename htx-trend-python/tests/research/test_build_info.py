"""Packaged builds embed commit/branch/version when .git is absent."""

from __future__ import annotations

from pathlib import Path

from htx_trend.research.version_info import get_version_info, write_build_info


def test_version_info_uses_build_info_when_git_absent(monkeypatch, tmp_path):
    build_path = tmp_path / "_build_info.py"
    write_build_info(
        build_path,
        version="9.9.9",
        git_info={"commit": "abc123def456", "branch": "release", "dirty": False},
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info.get_git_info",
        lambda: ("unknown", "unknown", False),
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info._load_build_info",
        lambda: {
            "commit": "abc123def456",
            "branch": "release",
            "version": "9.9.9",
            "dirty": False,
        },
    )
    info = get_version_info()
    assert info.commit == "abc123def456"
    assert info.branch == "release"
    assert info.version == "9.9.9"
    assert "abc123de" in str(info)


def test_write_build_info_emits_module(tmp_path):
    path = tmp_path / "_build_info.py"
    write_build_info(
        path,
        version="0.2.0",
        git_info={"commit": "deadbeef", "branch": "main", "dirty": True},
    )
    text = path.read_text()
    assert "COMMIT = 'deadbeef'" in text
    assert "BRANCH = 'main'" in text
    assert "VERSION = '0.2.0'" in text
    assert "DIRTY = True" in text
