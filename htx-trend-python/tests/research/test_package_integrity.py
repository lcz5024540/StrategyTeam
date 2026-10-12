"""rc.11: verify PACKAGE_MANIFEST on disk; dirty git tree refuses non-SMOKE."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from htx_trend.research.version_info import (
    get_version_info,
    require_runtime_integrity,
    verify_package_files,
    write_build_info,
)
from tests.research.helpers import create_test_dataset, runner_config, write_runner


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_package(root: Path, files: dict[str, str]) -> dict:
    listed = {}
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        listed[rel] = _sha(path)
    payload = {
        "files": listed,
        "content_hash": hashlib.sha256(
            json.dumps(listed, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
    }
    (root / "PACKAGE_MANIFEST").write_text(json.dumps(payload, indent=2, sort_keys=True))
    return payload


@pytest.mark.require_runtime_integrity
def test_untouched_package_passes_verify(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    verify_package_files(tmp_path)
    require_runtime_integrity(is_smoke=False, root=tmp_path)


@pytest.mark.require_runtime_integrity
def test_modified_packaged_py_fails_naming_the_file(tmp_path):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(tmp_path, {rel: "print('ok')\n"})
    (tmp_path / rel).write_text("print('tampered')\n")
    with pytest.raises(ValueError, match="sim_broker.py") as exc:
        verify_package_files(tmp_path)
    assert "mismatch" in str(exc.value).lower() or "sha256" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_deleted_packaged_file_fails(tmp_path):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(tmp_path, {rel: "print('ok')\n"})
    (tmp_path / rel).unlink()
    with pytest.raises(ValueError, match="sim_broker.py") as exc:
        verify_package_files(tmp_path)
    assert "missing" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_non_smoke_run_refuses_modified_packaged_file(tmp_path, monkeypatch):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(tmp_path, {rel: "print('ok')\n"})
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: tmp_path,
    )
    create_test_dataset(tmp_path, "pkg-mod", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(tmp_path, "PKGMOD", "pkg-mod", symbols=["BTC-USDT"])
    runner = write_runner(tmp_path, cfg)
    (tmp_path / rel).write_text("print('tampered')\n")
    with pytest.raises(ValueError, match="sim_broker.py"):
        runner.run()


@pytest.mark.require_runtime_integrity
def test_non_smoke_run_refuses_deleted_packaged_file(tmp_path, monkeypatch):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(tmp_path, {rel: "print('ok')\n"})
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: tmp_path,
    )
    create_test_dataset(tmp_path, "pkg-del", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(tmp_path, "PKGDEL", "pkg-del", symbols=["BTC-USDT"])
    runner = write_runner(tmp_path, cfg)
    (tmp_path / rel).unlink()
    with pytest.raises(ValueError, match="sim_broker.py"):
        runner.run()


@pytest.mark.require_runtime_integrity
def test_untouched_package_non_smoke_run_passes_integrity(tmp_path, monkeypatch):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(tmp_path, {rel: "print('ok')\n"})
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: tmp_path,
    )
    create_test_dataset(tmp_path, "pkg-ok", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(tmp_path, "PKGOK", "pkg-ok", symbols=["BTC-USDT"])
    runner = write_runner(tmp_path, cfg)
    results = runner.run()
    assert results is not None
    assert runner.ledger.get_run("PKGOK") is not None


@pytest.mark.require_runtime_integrity
def test_dirty_git_checkout_refuses_non_smoke(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "htx_trend.research.version_info._git_porcelain",
        lambda root: " M src/htx_trend/research/engine/backtest/sim_broker.py\n",
    )
    with pytest.raises(ValueError, match="working tree"):
        require_runtime_integrity(is_smoke=False, root=tmp_path)
    require_runtime_integrity(is_smoke=True, root=tmp_path)


def test_version_info_prefers_build_info_when_package_manifest_present(
    monkeypatch, tmp_path
):
    _write_package(tmp_path, {"readme.txt": "x\n"})
    write_build_info(
        tmp_path / "_build_info.py",
        version="0.1.0-rc.11",
        git_info={"commit": "packagedcommit", "branch": "release", "dirty": False},
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: tmp_path,
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info._load_build_info",
        lambda: {
            "commit": "packagedcommit",
            "branch": "release",
            "version": "0.1.0-rc.11",
            "dirty": False,
        },
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info.get_git_info",
        lambda: ("should-not-use", "git-branch", True),
    )
    info = get_version_info()
    assert info.commit == "packagedcommit"
    assert info.branch == "release"
    assert info.version == "0.1.0-rc.11"
    assert info.dirty is False
