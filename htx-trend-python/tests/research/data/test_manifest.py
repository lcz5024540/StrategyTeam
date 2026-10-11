"""P1-4: dataset manifest generation and batch hash verification."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import pytest
import yaml

from htx_trend.research.data.immutable_storage import ImmutableKlineStorage
from htx_trend.research.data.manifest import DatasetManifest
from htx_trend.research.ledger import LedgerManager
from htx_trend.research.runner import BacktestRunner


def _closed_bar(bar_time: datetime, close: float = 100.0) -> dict:
    return {
        "bar_time": bar_time,
        "open": close,
        "high": close + 1.0,
        "low": close - 1.0,
        "close": close,
        "volume": 1000.0,
    }


def _seed_storage(tmp_path: Path) -> ImmutableKlineStorage:
    storage = ImmutableKlineStorage(tmp_path / "klines")
    t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
    storage.append_batch(
        "BTC-USDT",
        "4hour",
        pd.DataFrame([_closed_bar(t0), _closed_bar(t0.replace(hour=4), 101.0)]),
    )
    storage.append_batch(
        "ETH-USDT",
        "1day",
        pd.DataFrame([_closed_bar(t0, 2000.0)]),
    )
    return storage


def test_generate_manifest(tmp_path):
    storage = _seed_storage(tmp_path)
    manifest = DatasetManifest.generate_from_storage(storage, "ds-p14")

    assert manifest.dataset_id == "ds-p14"
    assert len(manifest.batches) == 2
    symbols = {b.symbol for b in manifest.batches}
    assert symbols == {"BTC-USDT", "ETH-USDT"}


def test_manifest_includes_hashes(tmp_path):
    storage = _seed_storage(tmp_path)
    manifest = DatasetManifest.generate_from_storage(storage, "ds-p14")

    for entry in manifest.batches:
        assert entry.content_hash.startswith("sha256:")
        assert len(entry.content_hash) == 71
        assert entry.size_bytes > 0
        assert entry.bar_count > 0


def test_verify_manifest_passes(tmp_path):
    storage = _seed_storage(tmp_path)
    manifest = DatasetManifest.generate_from_storage(storage, "ds-p14")

    result = manifest.verify(storage)
    assert result.is_valid
    assert result.mismatches == []
    assert result.missing_files == []


def test_verify_manifest_fails_tampering(tmp_path):
    storage = _seed_storage(tmp_path)
    manifest = DatasetManifest.generate_from_storage(storage, "ds-p14")

    target = next(storage.batches_dir.glob("BTC-USDT_4hour_*.parquet"))
    df = pd.read_parquet(target)
    df.loc[df.index[0], "close"] = 999.99
    df.to_parquet(target, index=False)

    result = manifest.verify(storage)
    assert not result.is_valid
    assert result.mismatches
    assert result.mismatches[0]["file"].startswith("batches/")
    assert result.mismatches[0]["expected"] != result.mismatches[0]["actual"]


def test_manifest_save_load(tmp_path):
    storage = _seed_storage(tmp_path)
    manifest = DatasetManifest.generate_from_storage(storage, "ds-p14")
    path = tmp_path / "manifests" / "ds-p14.json"
    manifest.save(path)

    loaded = DatasetManifest.load(path)
    assert loaded.dataset_id == manifest.dataset_id
    assert loaded.sha256() == manifest.sha256()
    assert len(loaded.batches) == len(manifest.batches)
    assert loaded.batches[0].content_hash == manifest.batches[0].content_hash


def test_runner_binds_manifest(tmp_path):
    from tests.research.test_strict_determinism import create_test_dataset

    workspace = tmp_path / "workspace"
    workspace.mkdir()
    dataset_id = "test_manifest_bind"
    create_test_dataset(workspace, dataset_id)

    from tests.research.helpers import write_batch_manifest

    manifest_hash = write_batch_manifest(workspace, dataset_id)

    config = {
        "run_id": "MANIFEST001",
        "parent_run_id": None,
        "registration": {
            "change": "Bind manifest",
            "hypothesis": "Run stores manifest hash",
            "expected_effect": "ledger.manifest_hash set",
            "keep_rule": "hash present",
            "drop_rule": "hash missing",
        },
        "dataset_id": dataset_id,
        "manifest_hash": manifest_hash,
        "symbols": ["BTC-USDT", "ETH-USDT"],
        "split": "train",
        "stage_acceptance": False,
        "initial_equity": 1000.0,
        "cost_scenario": "base",
        "risk_per_trade": 0.005,
        "max_total_risk": 0.015,
        "max_positions": 3,
        "leverage_target": 5.0,
        "allow_missing_funding": True,
        "conditions": {
            "C01": {"enabled": True, "periods": [42, 84, 126, 168], "threshold": 0.25},
            "C02": {
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    }
    config_path = workspace / "config.yaml"
    config_path.write_text(yaml.dump(config))

    runner = BacktestRunner(config_path, workspace)
    runner.run()

    ledger = LedgerManager(workspace / "ledger.db")
    run = ledger.get_run("MANIFEST001")
    assert run is not None
    assert run.manifest_hash == manifest_hash
