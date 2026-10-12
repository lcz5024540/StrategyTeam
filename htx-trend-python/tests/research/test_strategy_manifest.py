"""Strategy condition manifest: C01–C09 catalog, hash, CLI check, ledger, package."""

from __future__ import annotations

import hashlib
import json
import sqlite3
from argparse import Namespace
from datetime import datetime, timezone
from pathlib import Path

import pytest
import yaml

from htx_trend.research.ledger import LedgerManager, RunRecord
from htx_trend.research.strategy_manifest import (
    CONDITION_IDS,
    PACKAGE_ROOT,
    build_strategy_manifest,
    canonical_manifest_payload,
    check_committed_manifest,
    load_run_config,
    manifest_sha256,
    write_strategy_manifest,
)
from tests.research.helpers import create_test_dataset, runner_config, write_runner

R0001 = PACKAGE_ROOT / "configs" / "runs" / "R0001.yaml"
COMMITTED = PACKAGE_ROOT / "strategy_manifest.yaml"
C01_PATH = PACKAGE_ROOT / "src" / "htx_trend" / "research" / "conditions" / "c01_donchian.py"
C02_PATH = PACKAGE_ROOT / "src" / "htx_trend" / "research" / "conditions" / "c02_atr_stop.py"


def _r0001() -> dict:
    return load_run_config(R0001)


def test_catalog_lists_c01_through_c09_in_order():
    assert CONDITION_IDS == tuple(f"C0{i}" for i in range(1, 10))
    built = build_strategy_manifest(_r0001())
    ids = [row["id"] for row in built["conditions"]]
    assert ids == list(CONDITION_IDS)


def test_r0001_marks_c01_c02_implemented_and_enabled():
    built = build_strategy_manifest(_r0001())
    by_id = {row["id"]: row for row in built["conditions"]}
    assert by_id["C01"]["implemented"] is True
    assert by_id["C01"]["enabled"] is True
    assert by_id["C01"]["name"]
    assert by_id["C01"]["params"]["periods"] == [42, 84, 126, 168]
    assert by_id["C01"]["params"]["threshold"] == 0.25
    assert by_id["C01"]["code_path"].endswith("c01_donchian.py")
    assert by_id["C02"]["implemented"] is True
    assert by_id["C02"]["enabled"] is True
    assert by_id["C02"]["params"]["atr_period"] == 14
    assert by_id["C02"]["params"]["atr_multiplier"] == 2.0
    assert by_id["C02"]["params"]["tick_size"] == 0.1
    assert by_id["C02"]["params"]["stop_slippage_ticks"] == 1
    for cid in ("C03", "C04", "C05", "C06", "C07", "C08", "C09"):
        assert by_id[cid]["implemented"] is False
        assert by_id[cid]["enabled"] is False
        assert by_id[cid]["params"] == {}
        assert by_id[cid]["code_path"] is None
        assert by_id[cid]["code_sha256"] is None


def test_code_sha256_is_sha256_of_condition_module_bytes():
    built = build_strategy_manifest(_r0001())
    by_id = {row["id"]: row for row in built["conditions"]}
    assert by_id["C01"]["code_sha256"] == hashlib.sha256(C01_PATH.read_bytes()).hexdigest()
    assert by_id["C02"]["code_sha256"] == hashlib.sha256(C02_PATH.read_bytes()).hexdigest()


def test_manifest_level_fields_come_from_config_and_package():
    from htx_trend.research._version import __version__

    built = build_strategy_manifest(_r0001(), git_commit="abc123")
    assert built["package_version"] == __version__
    assert built["git_commit"] == "abc123"
    assert built["timeframe"] == "4hour"
    assert built["symbols"] == ["BTC-USDT", "ETH-USDT"]
    assert built["initial_equity"] == 300
    assert built["risk_per_trade"] == 0.02
    assert built["max_total_risk"] == 0.10
    assert built["max_positions"] == 5


def test_manifest_sha256_is_canonical_json_excluding_hash_field():
    built = build_strategy_manifest(_r0001(), git_commit="deadbeef")
    payload = canonical_manifest_payload(built)
    assert "manifest_sha256" not in payload
    expected = hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    assert built["manifest_sha256"] == expected
    assert manifest_sha256(built) == expected
    mutated = dict(_r0001())
    mutated["conditions"] = {
        **mutated["conditions"],
        "C01": {**mutated["conditions"]["C01"], "threshold": 0.5},
    }
    other = build_strategy_manifest(mutated, git_commit="deadbeef")
    assert other["manifest_sha256"] != built["manifest_sha256"]


def test_enabled_false_when_config_omits_enabled_key():
    cfg = dict(_r0001())
    cfg["conditions"] = {
        "C01": {"enabled": True, "periods": [42], "threshold": 0.25},
        "C02": {
            "atr_period": 14,
            "atr_multiplier": 2.0,
            "tick_size": 0.1,
            "stop_slippage_ticks": 1,
        },
    }
    built = build_strategy_manifest(cfg, git_commit="x")
    by_id = {row["id"]: row for row in built["conditions"]}
    assert by_id["C02"]["enabled"] is False
    assert by_id["C02"]["params"]["atr_period"] == 14


def test_build_raises_when_enabled_implemented_condition_missing_params():
    cfg = dict(_r0001())
    cfg["conditions"] = {"C01": {"enabled": True, "threshold": 0.25}}
    with pytest.raises(ValueError, match="C01"):
        build_strategy_manifest(cfg, git_commit="x")


def test_committed_manifest_matches_code_and_r0001():
    check_committed_manifest(COMMITTED, R0001)


def test_check_fails_when_committed_file_is_stale(tmp_path):
    stale = tmp_path / "strategy_manifest.yaml"
    built = build_strategy_manifest(_r0001(), git_commit="stale")
    built["conditions"][0]["params"]["threshold"] = 0.99
    built["manifest_sha256"] = "0" * 64
    write_strategy_manifest(stale, built)
    with pytest.raises(SystemExit, match="stale"):
        check_committed_manifest(stale, R0001)


def test_cli_write_and_check(tmp_path):
    from htx_trend.research.cli import cmd_strategy_manifest

    out = tmp_path / "strategy_manifest.yaml"
    cmd_strategy_manifest(
        Namespace(config=str(R0001), write=str(out), check=False)
    )
    loaded = yaml.safe_load(out.read_text())
    assert loaded["manifest_sha256"]
    assert loaded["conditions"][0]["id"] == "C01"
    cmd_strategy_manifest(
        Namespace(config=str(R0001), write=None, check=str(out))
    )


def test_ledger_stores_strategy_manifest_json_and_sha256(tmp_path):
    built = build_strategy_manifest(_r0001(), git_commit="cafebabe")
    ledger = LedgerManager(tmp_path / "ledger.db")
    cols = {
        row[1]
        for row in sqlite3.connect(tmp_path / "ledger.db").execute(
            "PRAGMA table_info(runs)"
        )
    }
    assert "strategy_manifest_sha256" in cols
    assert "strategy_manifest" in cols
    ledger.register_run(
        RunRecord(
            run_id="M1",
            parent_run_id=None,
            git_commit="cafebabe",
            dataset_hash="d",
            environment="BACKTEST",
            config_snapshot={"run_id": "M1"},
            cost_scenario="base",
            created_at=datetime.now(timezone.utc),
            strategy_manifest_sha256=built["manifest_sha256"],
            strategy_manifest=built,
        )
    )
    loaded = ledger.get_run("M1")
    assert loaded is not None
    assert loaded.strategy_manifest_sha256 == built["manifest_sha256"]
    assert loaded.strategy_manifest["conditions"][0]["id"] == "C01"
    assert loaded.strategy_manifest["manifest_sha256"] == built["manifest_sha256"]


def test_sql_migration_documents_strategy_manifest_columns():
    path = PACKAGE_ROOT / "sql" / "2026-10-12__runs_strategy_manifest.sql"
    text = path.read_text()
    assert "strategy_manifest_sha256" in text
    assert "strategy_manifest" in text


def test_runner_records_strategy_manifest_or_fails(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "breakout", n_bars=260, train_end_idx=260, val_end_idx=260)
    cfg = runner_config(
        workspace,
        "MAN1",
        "breakout",
        symbols=["BTC-USDT"],
        allow_missing_funding=True,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "enabled": True,
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    )
    runner = write_runner(workspace, cfg)
    runner.run()
    recorded = runner.ledger.get_run("MAN1")
    assert recorded is not None
    assert recorded.strategy_manifest_sha256
    assert recorded.strategy_manifest["conditions"][0]["enabled"] is True
    rebuilt = build_strategy_manifest(cfg, git_commit=recorded.git_commit)
    assert recorded.strategy_manifest_sha256 == rebuilt["manifest_sha256"]


def test_runner_fails_if_manifest_cannot_be_built(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "breakout", n_bars=260, train_end_idx=260, val_end_idx=260)
    cfg = runner_config(
        workspace,
        "MANBAD",
        "breakout",
        symbols=["BTC-USDT"],
        allow_missing_funding=True,
        conditions={"C01": {"enabled": True}},
    )
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="C01|manifest"):
        runner.run()


def test_package_includes_strategy_manifest_and_tag_script(tmp_path):
    import sys
    import tarfile

    sys.path.insert(0, str(PACKAGE_ROOT / "scripts"))
    import package as package_mod

    tar_path, _ = package_mod.create_package(
        "0.1.0-rc.9",
        {"short_sha": "deadbeef", "commit": "deadbeef", "branch": "release", "dirty": False},
        output_dir=tmp_path,
    )
    with tarfile.open(tar_path) as tar:
        names = {name.replace("\\", "/") for name in tar.getnames()}
    assert "strategy_manifest.yaml" in names
    assert "scripts/tag_message.py" in names


def test_write_build_info_includes_strategy_manifest_sha256(tmp_path):
    from htx_trend.research.version_info import write_build_info

    path = tmp_path / "_build_info.py"
    write_build_info(
        path,
        version="0.1.0-rc.9",
        git_info={"commit": "abc", "branch": "release", "dirty": False},
        strategy_manifest_sha256="ff" * 32,
    )
    text = path.read_text()
    assert "STRATEGY_MANIFEST_SHA256 = '" + "ff" * 32 + "'" in text


def test_tag_message_prints_v0_1_0_summary_without_creating_a_tag(capsys):
    import runpy

    with pytest.raises(SystemExit) as exc:
        runpy.run_path(str(PACKAGE_ROOT / "scripts" / "tag_message.py"), run_name="__main__")
    assert exc.value.code in (0, None)
    out = capsys.readouterr().out
    assert "v0.1.0" in out
    assert "C01" in out
    assert "C02" in out
    committed = yaml.safe_load(COMMITTED.read_text())
    assert committed["manifest_sha256"] in out
    assert "git tag" not in out.lower()
