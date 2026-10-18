"""rc.11: verify PACKAGE_MANIFEST on disk; dirty git tree refuses non-SMOKE."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from htx_trend.research.runner import BacktestRunner
from htx_trend.research.version_info import (
    ALLOWED_EGG_INFO_DIR,
    ALLOWED_EGG_INFO_FILES,
    BUILD_INFO_REL,
    PACKAGE_INTEGRITY_RUNTIME_EXCLUSIONS,
    file_sha256_for_manifest,
    get_version_info,
    package_content_hash_from_files,
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
    pkg = tmp_path / "pkg"
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(pkg, {rel: "print('ok')\n"})
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: pkg,
    )
    create_test_dataset(workspace, "pkg-mod", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(workspace, "PKGMOD", "pkg-mod", symbols=["BTC-USDT"])
    runner = write_runner(workspace, cfg)
    (pkg / rel).write_text("print('tampered')\n")
    with pytest.raises(ValueError, match="sim_broker.py"):
        runner.run()


@pytest.mark.require_runtime_integrity
def test_non_smoke_run_refuses_deleted_packaged_file(tmp_path, monkeypatch):
    pkg = tmp_path / "pkg"
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(pkg, {rel: "print('ok')\n"})
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: pkg,
    )
    create_test_dataset(workspace, "pkg-del", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(workspace, "PKGDEL", "pkg-del", symbols=["BTC-USDT"])
    runner = write_runner(workspace, cfg)
    (pkg / rel).unlink()
    with pytest.raises(ValueError, match="sim_broker.py"):
        runner.run()


@pytest.mark.require_runtime_integrity
def test_untouched_package_non_smoke_run_passes_integrity(tmp_path, monkeypatch):
    pkg = tmp_path / "pkg"
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(pkg, {rel: "print('ok')\n"})
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: pkg,
    )
    create_test_dataset(workspace, "pkg-ok", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(workspace, "PKGOK", "pkg-ok", symbols=["BTC-USDT"])
    runner = write_runner(workspace, cfg)
    results = runner.run()
    assert results is not None
    assert runner.ledger.get_run("PKGOK") is not None


@pytest.mark.require_runtime_integrity
def test_dirty_git_checkout_refuses_non_smoke(tmp_path, monkeypatch):
    monkeypatch.setattr(
        "htx_trend.research.version_info._git_toplevel",
        lambda root: tmp_path.resolve(),
    )
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


@pytest.mark.require_runtime_integrity
@pytest.mark.parametrize(
    "rel",
    [
        "evil_root.txt",
        "sitecustomize.py",
        "usercustomize.py",
        "src/htx_trend/research/evil.py",
        "configs/x.yaml",
        "tests/injected.py",
        "deployment/injected.sh",
        "docs/injected.md",
        "data/quality/evil.yaml",
        "scripts/evil.py",
    ],
)
def test_added_file_anywhere_except_runtime_dirs_fails(tmp_path, rel):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    extra = tmp_path / rel
    extra.parent.mkdir(parents=True, exist_ok=True)
    extra.write_text("injected\n")
    with pytest.raises(ValueError, match=rel) as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
@pytest.mark.parametrize(
    "rel",
    [
        "runs/injected.py",
        "logs/app.log",
        "run/status.json",
        "run/heartbeats/beat.txt",
        "data/raw/BTC-USDT_4hour_htx.parquet",
        "data/datasets/manifests/x.json",
        "data/klines/batch.bin",
        "ledger.db",
        ".pytest_cache/v/cache/nodeids",
        "__pycache__/evil.cpython-312.pyc",
        "src/htx_trend/research/__pycache__/evil.cpython-312.pyc",
        "src/htx_trend/research/engine/backtest/sim_broker.pyc",
    ],
)
def test_added_file_in_runtime_or_pycache_is_ignored(tmp_path, rel):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    extra = tmp_path / rel
    extra.parent.mkdir(parents=True, exist_ok=True)
    extra.write_bytes(b"runtime-or-bytecode")
    verify_package_files(tmp_path)
    require_runtime_integrity(is_smoke=False, root=tmp_path)


def test_runtime_exclusions_are_hardcoded_constant():
    assert isinstance(PACKAGE_INTEGRITY_RUNTIME_EXCLUSIONS, frozenset)
    assert PACKAGE_INTEGRITY_RUNTIME_EXCLUSIONS == frozenset(
        {
            "runs",
            "logs",
            "run",
            "data/raw",
            "data/datasets",
            "data/klines",
            "ledger.db",
            ".pytest_cache",
        }
    )
    assert ".venv" not in PACKAGE_INTEGRITY_RUNTIME_EXCLUSIONS
    assert ALLOWED_EGG_INFO_DIR == "src/coinstrategy_htx_trend.egg-info"
    assert "PKG-INFO" in ALLOWED_EGG_INFO_FILES


@pytest.mark.require_runtime_integrity
def test_integrity_failure_precedes_placeholder_config(tmp_path, monkeypatch):
    pkg = tmp_path / "pkg"
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    _write_package(
        pkg,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    (pkg / "src/htx_trend/research/evil.py").write_text("injected\n")
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: pkg,
    )
    cfg_path = workspace / "placeholder.yaml"
    cfg_path.write_text(
        "run_id: PLACE1\n"
        "dataset_id: TODO_FREEZE_DATASET\n"
        "manifest_hash: TODO_FREEZE_HASH\n"
    )
    runner = BacktestRunner(cfg_path, workspace)
    with pytest.raises(ValueError) as exc:
        runner.run()
    text = str(exc.value)
    assert "unexpected file" in text.lower()
    assert "evil.py" in text
    assert "placeholder" not in text.lower()
    assert "TODO" not in text


@pytest.mark.require_runtime_integrity
def test_shadow_package_dir_is_unexpected_file(tmp_path):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_package(tmp_path, {rel: "print('ok')\n"})
    shadow = (
        tmp_path
        / "src/htx_trend/research/engine/backtest/sim_broker/__init__.py"
    )
    shadow.parent.mkdir(parents=True, exist_ok=True)
    shadow.write_text("print('shadow')\n")
    with pytest.raises(ValueError, match="sim_broker/__init__.py") as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


def _write_anchored_package(root: Path, files: dict[str, str]) -> dict:
    listed = {}
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    write_build_info(
        root / BUILD_INFO_REL,
        version="0.1.0-rc.12",
        git_info={"commit": "packagedabc123", "branch": "release", "dirty": False},
        package_content_hash="",
    )
    for rel in list(files) + [BUILD_INFO_REL]:
        listed[rel] = file_sha256_for_manifest(root / rel, rel)
    content_hash = package_content_hash_from_files(listed)
    write_build_info(
        root / BUILD_INFO_REL,
        version="0.1.0-rc.12",
        git_info={"commit": "packagedabc123", "branch": "release", "dirty": False},
        package_content_hash=content_hash,
    )
    listed[BUILD_INFO_REL] = file_sha256_for_manifest(root / BUILD_INFO_REL, BUILD_INFO_REL)
    payload = {"files": listed, "content_hash": content_hash}
    (root / "PACKAGE_MANIFEST").write_text(json.dumps(payload, indent=2, sort_keys=True))
    return payload


@pytest.mark.require_runtime_integrity
def test_consistent_manifest_rewrite_after_code_change_is_refused(tmp_path):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    payload = _write_anchored_package(tmp_path, {rel: "print('ok')\n"})
    (tmp_path / rel).write_text("print('tampered')\n")
    payload["files"][rel] = file_sha256_for_manifest(tmp_path / rel, rel)
    payload["content_hash"] = package_content_hash_from_files(payload["files"])
    (tmp_path / "PACKAGE_MANIFEST").write_text(
        json.dumps(payload, indent=2, sort_keys=True)
    )
    with pytest.raises(ValueError, match="PACKAGE_CONTENT_HASH|content_hash"):
        require_runtime_integrity(is_smoke=False, root=tmp_path)


@pytest.mark.require_runtime_integrity
def test_packaged_build_without_manifest_refuses_git_fallback(tmp_path):
    rel = "src/htx_trend/research/engine/backtest/sim_broker.py"
    _write_anchored_package(tmp_path, {rel: "print('ok')\n"})
    (tmp_path / "PACKAGE_MANIFEST").unlink()
    with pytest.raises(ValueError, match="PACKAGE_MANIFEST is required"):
        require_runtime_integrity(is_smoke=False, root=tmp_path)


@pytest.mark.require_runtime_integrity
def test_package_inside_outer_git_without_manifest_is_refused(tmp_path):
    import subprocess

    outer = tmp_path / "outer"
    outer.mkdir()
    subprocess.run(["git", "init"], cwd=outer, check=True, capture_output=True)
    pkg = outer / "nested"
    pkg.mkdir()
    (pkg / "readme.txt").write_text("x\n")
    with pytest.raises(ValueError, match="git toplevel|PACKAGE_MANIFEST"):
        require_runtime_integrity(is_smoke=False, root=pkg)


@pytest.mark.require_runtime_integrity
def test_no_manifest_and_not_git_raises_value_error(tmp_path):
    with pytest.raises(ValueError, match="not a git checkout"):
        require_runtime_integrity(is_smoke=False, root=tmp_path)


@pytest.mark.require_runtime_integrity
def test_e2e_extracted_package_pass_and_tamper_fail(tmp_path, monkeypatch):
    pkg = tmp_path / "extract"
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    _write_anchored_package(
        pkg,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: pkg,
    )
    from tests.research.helpers import create_test_dataset, runner_config, write_runner

    create_test_dataset(workspace, "e2e-ok", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(workspace, "E2EOK", "e2e-ok", symbols=["BTC-USDT"])
    runner = write_runner(workspace, cfg)
    results = runner.run()
    assert results is not None
    stored = runner.ledger.get_run("E2EOK")
    assert stored is not None
    assert stored.integrity_verified is True
    assert stored.package_content_hash

    (
        pkg / "src/htx_trend/research/engine/backtest/sim_broker.py"
    ).write_text("print('tampered')\n")
    cfg_bad = runner_config(workspace, "E2EBAD", "e2e-ok", symbols=["BTC-USDT"])
    bad = write_runner(workspace, cfg_bad)
    with pytest.raises(ValueError, match="sim_broker.py"):
        bad.run()


@pytest.mark.require_runtime_integrity
def test_smoke_records_null_hash_and_integrity_not_verified(tmp_path, monkeypatch):
    from datetime import date

    pkg = tmp_path / "extract"
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    _write_anchored_package(
        pkg,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    monkeypatch.setattr(
        "htx_trend.research.version_info.package_root",
        lambda: pkg,
    )
    from tests.research.helpers import create_test_dataset, runner_config, write_runner

    create_test_dataset(workspace, "e2e-smoke", n_bars=80, symbols=["BTC-USDT"])
    today = date.today().strftime("%Y%m%d")
    cfg = runner_config(
        workspace,
        f"SMOKE-{today}-01",
        "e2e-smoke",
        symbols=["BTC-USDT"],
        run_type="SMOKE",
        split="train",
        smoke_bar_window=40,
    )
    runner = write_runner(workspace, cfg)
    runner.run()
    stored = runner.ledger.get_run(cfg["run_id"])
    assert stored is not None
    assert stored.package_content_hash is None
    assert stored.integrity_verified is False


@pytest.mark.require_runtime_integrity
def test_standard_egg_info_files_are_allowed(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    egg = tmp_path / ALLOWED_EGG_INFO_DIR
    egg.mkdir(parents=True)
    for name in (
        "PKG-INFO",
        "SOURCES.txt",
        "top_level.txt",
        "dependency_links.txt",
        "requires.txt",
        "entry_points.txt",
        "not_zip_safe",
    ):
        (egg / name).write_text("ok\n")
    verify_package_files(tmp_path)
    require_runtime_integrity(is_smoke=False, root=tmp_path)


@pytest.mark.require_runtime_integrity
def test_evil_egg_info_dir_is_rejected(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    evil = tmp_path / "src/evil.egg-info"
    evil.mkdir(parents=True)
    (evil / "PKG-INFO").write_text("injected\n")
    with pytest.raises(ValueError, match="evil.egg-info") as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_extra_py_in_allowed_egg_info_is_rejected(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    egg = tmp_path / ALLOWED_EGG_INFO_DIR
    egg.mkdir(parents=True)
    (egg / "PKG-INFO").write_text("ok\n")
    (egg / "evil.py").write_text("print('no')\n")
    with pytest.raises(ValueError, match="evil.py") as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_egg_info_subdir_is_rejected(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    nested = tmp_path / ALLOWED_EGG_INFO_DIR / "nested"
    nested.mkdir(parents=True)
    (nested / "PKG-INFO").write_text("ok\n")
    with pytest.raises(ValueError, match="nested") as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_package_root_pytest_cache_is_ignored_nested_is_not(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    cache = tmp_path / ".pytest_cache"
    cache.mkdir()
    (cache / "v" / "cache" / "nodeids").parent.mkdir(parents=True)
    (cache / "v" / "cache" / "nodeids").write_text("x\n")
    verify_package_files(tmp_path)
    nested = tmp_path / "src" / ".pytest_cache" / "evil"
    nested.parent.mkdir(parents=True)
    nested.write_text("no\n")
    with pytest.raises(ValueError, match=r"src/\.pytest_cache") as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_in_package_venv_is_not_excluded(tmp_path):
    _write_package(
        tmp_path,
        {"src/htx_trend/research/engine/backtest/sim_broker.py": "print('ok')\n"},
    )
    injected = tmp_path / ".venv" / "lib" / "sitecustomize.py"
    injected.parent.mkdir(parents=True)
    injected.write_text("print('venv')\n")
    with pytest.raises(ValueError, match=r"\.venv/") as exc:
        verify_package_files(tmp_path)
    assert "unexpected file" in str(exc.value).lower()


@pytest.mark.require_runtime_integrity
def test_deleted_cli_py_reports_missing_not_import_error(tmp_path):
    import subprocess
    import sys

    pkg = tmp_path / "pkg"
    src = pkg / "src" / "htx_trend" / "research"
    src.mkdir(parents=True)
    (pkg / "src" / "htx_trend" / "__init__.py").write_text("")
    (src / "__init__.py").write_text("")
    (src / "_version.py").write_text('__version__ = "0.1.0-rc.13"\n')
    version_info = Path(__file__).resolve().parents[2] / "src/htx_trend/research/version_info.py"
    (src / "version_info.py").write_text(version_info.read_text())
    (src / "__main__.py").write_text(
        "from .version_info import require_cli_entry_integrity\n"
        "require_cli_entry_integrity()\n"
        "from .cli import main\n"
        "main()\n"
    )
    (src / "cli.py").write_text("def main():\n    print('cli-ok')\n")
    payload = _write_package(
        pkg,
        {
            "src/htx_trend/__init__.py": "",
            "src/htx_trend/research/__init__.py": "",
            "src/htx_trend/research/_version.py": (src / "_version.py").read_text(),
            "src/htx_trend/research/version_info.py": (src / "version_info.py").read_text(),
            "src/htx_trend/research/__main__.py": (src / "__main__.py").read_text(),
            "src/htx_trend/research/cli.py": (src / "cli.py").read_text(),
        },
    )
    assert payload["files"]["src/htx_trend/research/cli.py"]
    (src / "cli.py").unlink()
    env = {**dict(**__import__("os").environ), "PYTHONPATH": str(pkg / "src")}
    result = subprocess.run(
        [sys.executable, "-m", "htx_trend.research", "--help"],
        cwd=pkg,
        capture_output=True,
        text=True,
        env=env,
        timeout=15,
    )
    combined = result.stdout + result.stderr
    assert result.returncode != 0
    assert "missing" in combined.lower()
    assert "cli.py" in combined
    assert "ModuleNotFoundError" not in combined
