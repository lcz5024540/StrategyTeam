"""Funding files in the manifest must cover every backtest symbol."""

from __future__ import annotations

import hashlib

import pandas as pd
import pytest

from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.data.manifest import DatasetManifest
from tests.research.helpers import (
    create_test_dataset,
    runner_config,
    write_nominal_funding,
    write_runner,
)


def _hash_file(path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def _funding_frame(symbol: str, rate: float) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "symbol": [symbol],
            "funding_time": pd.to_datetime(["2024-01-01T08:00:00Z"], utc=True),
            "funding_rate": [rate],
            "source": ["htx"],
        }
    )


def _set_funding_files(workspace, dataset_id: str, files: dict) -> str:
    path = workspace / "data" / "datasets" / "manifests" / f"{dataset_id}.json"
    batch = DatasetManifest.load(path)
    batch.metadata["funding_files"] = files
    batch.save(path)
    return batch.sha256()


def test_missing_funding_files_field_is_hard_error(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "nofield", n_bars=48, symbols=["BTC-USDT"])
    mgr = DatasetManager(workspace / "data" / "datasets")
    batch = DatasetManifest.load(
        workspace / "data" / "datasets" / "manifests" / "nofield.json"
    )
    assert "funding_files" not in (batch.metadata or {})
    with pytest.raises(ValueError, match="funding_files"):
        mgr.load_dataset_with_verification(
            "nofield",
            batch.sha256(),
            symbols=["BTC-USDT"],
        )


def test_funding_files_missing_eth_while_backtesting_btc_and_eth(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "btconly", n_bars=48, symbols=["BTC-USDT", "ETH-USDT"])
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    btc = raw / "BTC-USDT_funding.parquet"
    _funding_frame("BTC-USDT", 0.0001).to_parquet(btc, index=False)
    digest = _hash_file(btc)
    manifest_hash = _set_funding_files(
        workspace,
        "btconly",
        {"BTC-USDT": {"path": "BTC-USDT_funding.parquet", "sha256": digest}},
    )
    mgr = DatasetManager(workspace / "data" / "datasets")
    with pytest.raises(ValueError, match="ETH-USDT"):
        mgr.load_dataset_with_verification(
            "btconly",
            manifest_hash,
            symbols=["BTC-USDT", "ETH-USDT"],
        )


def test_runner_loads_funding_from_verified_path_not_rebuilt_default(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "verpath", n_bars=48, symbols=["BTC-USDT"])
    raw = workspace / "data" / "raw"
    alt_dir = raw / "verified"
    alt_dir.mkdir(parents=True)
    write_nominal_funding(workspace, ["BTC-USDT"], dataset_id="verpath", rate=0.0001)
    poison = pd.read_parquet(raw / "BTC-USDT_funding.parquet").copy()
    poison["funding_rate"] = 0.9999
    poison.to_parquet(raw / "BTC-USDT_poison.parquet", index=False)
    alt = alt_dir / "BTC-USDT_funding.parquet"
    pd.read_parquet(raw / "BTC-USDT_funding.parquet").to_parquet(alt, index=False)
    manifest_hash = _set_funding_files(
        workspace,
        "verpath",
        {
            "BTC-USDT": {
                "path": "verified/BTC-USDT_funding.parquet",
                "sha256": _hash_file(alt),
            }
        },
    )
    mgr = DatasetManager(workspace / "data" / "datasets")
    _data, frozen = mgr.load_dataset_with_verification(
        "verpath",
        manifest_hash,
        symbols=["BTC-USDT"],
    )
    assert frozen.verified_funding_paths["BTC-USDT"] == alt

    cfg = runner_config(
        workspace,
        "VERPATH",
        "verpath",
        symbols=["BTC-USDT"],
        allow_missing_funding=False,
    )
    cfg["manifest_hash"] = manifest_hash
    runner = write_runner(workspace, cfg)
    runner.run()
    loaded = runner._funding_by_symbol["BTC-USDT"]
    assert float(loaded["funding_rate"].iloc[0]) == pytest.approx(0.0001)
