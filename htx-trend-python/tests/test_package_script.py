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


def test_java_and_golden_fixtures_are_vendored():
    assert (ROOT / "tests/fixtures/java/schema.sql").is_file()
    assert (
        ROOT / "tests/fixtures/java/2026-10-07b__htx_trend_structure_lifecycle_and_final_pnl.sql"
    ).is_file()
    assert (ROOT / "tests/fixtures/trend-exit-v5/golden.json").is_file()


def test_tests_contain_no_absolute_workspace_paths():
    hits = []
    for path in (ROOT / "tests").rglob("*"):
        if not path.is_file() or path.suffix not in {".py", ".md", ".yaml", ".sql", ".json"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if ("/" + "workspace" + "/") in text:
            hits.append(str(path.relative_to(ROOT)))
    assert hits == []


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
    assert "src/htx_trend/research/data" in text
    assert "--cutoff 2026-10-12T00:00:00Z" in text
    assert "validation_start_utc" in text
    assert "test_start_utc" in text
    assert "sha256sum -c" in text
    assert "rsync" not in text.lower()
    assert "/opt/coinstrategy/htx-trend-py" in text
    assert ".venv" in text
    assert "run/" in text
    assert "logs/" in text
    assert "pip install --require-hashes" in text
    assert "cp -a" in text
    assert "data/quality" in text


def test_package_script_has_no_rsync_dependency():
    text = (ROOT / "scripts" / "package.py").read_text(encoding="utf-8")
    assert "rsync" not in text.lower()


def test_create_package_writes_tarball_and_sha256(tmp_path):
    tar_path, sha256 = package_mod.create_package(
        "0.2.0",
        {"short_sha": "deadbeef", "commit": "deadbeef", "branch": "release", "dirty": False},
        output_dir=tmp_path,
    )
    assert tar_path.is_file()
    assert tar_path.name.endswith(".tar.gz")
    digest_file = tmp_path / f"{tar_path.name}.sha256"
    assert digest_file.is_file()
    recorded = digest_file.read_text().split()[0]
    assert recorded == sha256
    assert len(sha256) == 64


def test_create_package_includes_quality_fixtures_deployment_and_sql(tmp_path):
    import tarfile

    tar_path, _ = package_mod.create_package(
        "0.2.0",
        {"short_sha": "deadbeef", "commit": "deadbeef", "branch": "release", "dirty": False},
        output_dir=tmp_path,
    )
    with tarfile.open(tar_path) as tar:
        names = {name.replace("\\", "/") for name in tar.getnames()}
    assert any(name.endswith("data/quality/bad_ticks.yaml") for name in names)
    assert any(name.endswith("deployment/systemd/htx-trend-collector.service") for name in names)
    assert any(name.endswith("sql/schema.sql") for name in names)
    assert any("tests/fixtures/real/" in name for name in names)
    assert any(name.endswith("tests/fixtures/java/schema.sql") for name in names)
    assert any(name.endswith("tests/fixtures/trend-exit-v5/golden.json") for name in names)
    assert any(name.endswith("configs/runs/R0001.yaml") for name in names)
