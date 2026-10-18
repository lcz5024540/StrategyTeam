"""Contract spec files in the manifest must cover every backtest symbol."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from unittest.mock import patch

import pandas as pd
import pytest

from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.data.manifest import DatasetManifest
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_runner,
)


def _hash_file(path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def _strip_spec_files(workspace, dataset_id: str) -> str:
    path = workspace / "data" / "datasets" / "manifests" / f"{dataset_id}.json"
    batch = DatasetManifest.load(path)
    batch.metadata.pop("spec_files", None)
    batch.save(path)
    return batch.sha256()


def _set_spec_files(workspace, dataset_id: str, files: dict) -> str:
    path = workspace / "data" / "datasets" / "manifests" / f"{dataset_id}.json"
    batch = DatasetManifest.load(path)
    batch.metadata["spec_files"] = files
    batch.save(path)
    return batch.sha256()


def _poison_spec(symbol: str, contract_size: float) -> dict:
    spec = dict(DEFAULT_SPECS[symbol])
    spec["contract_size"] = contract_size
    return spec


def test_missing_spec_files_field_is_hard_error(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "nospecfield", n_bars=48, symbols=["BTC-USDT"])
    manifest_hash = _strip_spec_files(workspace, "nospecfield")
    mgr = DatasetManager(workspace / "data" / "datasets")
    batch = DatasetManifest.load(
        workspace / "data" / "datasets" / "manifests" / "nospecfield.json"
    )
    assert "spec_files" not in (batch.metadata or {})
    with pytest.raises(ValueError, match="spec_files"):
        mgr.load_dataset_with_verification(
            "nospecfield",
            manifest_hash,
            symbols=["BTC-USDT"],
            require_specs=True,
            allow_missing_funding=True,
        )


def test_spec_files_missing_eth_while_backtesting_btc_and_eth(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(
        workspace, "btcspec", n_bars=48, symbols=["BTC-USDT", "ETH-USDT"]
    )
    raw = workspace / "data" / "raw"
    btc = raw / "BTC-USDT_spec.json"
    manifest_hash = _set_spec_files(
        workspace,
        "btcspec",
        {"BTC-USDT": {"path": "BTC-USDT_spec.json", "sha256": _hash_file(btc)}},
    )
    mgr = DatasetManager(workspace / "data" / "datasets")
    with pytest.raises(ValueError, match="ETH-USDT"):
        mgr.load_dataset_with_verification(
            "btcspec",
            manifest_hash,
            symbols=["BTC-USDT", "ETH-USDT"],
            require_specs=True,
            allow_missing_funding=True,
        )


def test_spec_hash_mismatch_is_hard_error(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "badspec", n_bars=48, symbols=["BTC-USDT"])
    manifest_hash = _set_spec_files(
        workspace,
        "badspec",
        {
            "BTC-USDT": {
                "path": "BTC-USDT_spec.json",
                "sha256": "sha256:" + ("0" * 64),
            }
        },
    )
    mgr = DatasetManager(workspace / "data" / "datasets")
    with pytest.raises(ValueError, match="[Ss]pec"):
        mgr.load_dataset_with_verification(
            "badspec",
            manifest_hash,
            symbols=["BTC-USDT"],
            require_specs=True,
            allow_missing_funding=True,
        )


def test_runner_loads_spec_from_verified_path_not_rebuilt_default(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "verspec", n_bars=48, symbols=["BTC-USDT"])
    raw = workspace / "data" / "raw"
    alt_dir = raw / "verified"
    alt_dir.mkdir(parents=True)
    (raw / "BTC-USDT_spec.json").write_text(
        json.dumps(_poison_spec("BTC-USDT", 0.5), indent=2)
    )
    alt = alt_dir / "BTC-USDT_spec.json"
    alt.write_text(json.dumps(_poison_spec("BTC-USDT", 0.001), indent=2))
    manifest_hash = _set_spec_files(
        workspace,
        "verspec",
        {
            "BTC-USDT": {
                "path": "verified/BTC-USDT_spec.json",
                "sha256": _hash_file(alt),
            }
        },
    )
    mgr = DatasetManager(workspace / "data" / "datasets")
    _data, frozen = mgr.load_dataset_with_verification(
        "verspec",
        manifest_hash,
        symbols=["BTC-USDT"],
        require_specs=True,
        allow_missing_funding=True,
    )
    assert frozen.verified_spec_paths["BTC-USDT"] == alt

    cfg = runner_config(
        workspace,
        "VERSPEC",
        "verspec",
        symbols=["BTC-USDT"],
        allow_missing_funding=True,
    )
    cfg["manifest_hash"] = manifest_hash
    runner = write_runner(workspace, cfg)
    runner.run()
    assert runner._contract_sizes["BTC-USDT"] == pytest.approx(0.001)


def test_runner_opens_only_verifier_returned_data_paths(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "openonly", n_bars=48, symbols=["BTC-USDT"])
    dataset_dir = workspace / "data" / "datasets" / "openonly"
    unverified = dataset_dir / "overlap_deviation.json"
    unverified.write_text(json.dumps({"BTC-USDT": {"4hour": {"max": 0.01}}}))
    (workspace / "data" / "raw" / "SHOULD_NOT_READ.json").write_text("{}")

    verified_paths: dict[Path, str] = {}
    opened: list[Path] = []

    orig_load = DatasetManager.load_dataset_with_verification

    def wrapped_load(self, *args, **kwargs):
        data, frozen = orig_load(self, *args, **kwargs)
        for key, path in (getattr(frozen, "verified_input_paths", None) or {}).items():
            verified_paths[Path(path).resolve()] = key
        return data, frozen

    def _record(path) -> None:
        try:
            opened.append(Path(path).resolve())
        except (TypeError, ValueError):
            return

    orig_read_parquet = pd.read_parquet

    def tracking_read_parquet(path, *args, **kwargs):
        _record(path)
        return orig_read_parquet(path, *args, **kwargs)

    orig_read_text = Path.read_text

    def tracking_read_text(self, *args, **kwargs):
        _record(self)
        return orig_read_text(self, *args, **kwargs)

    orig_read_bytes = Path.read_bytes

    def tracking_read_bytes(self, *args, **kwargs):
        _record(self)
        return orig_read_bytes(self, *args, **kwargs)

    orig_open = Path.open

    def tracking_open(self, *args, **kwargs):
        _record(self)
        return orig_open(self, *args, **kwargs)

    cfg = runner_config(
        workspace,
        "OPENONLY",
        "openonly",
        symbols=["BTC-USDT"],
        allow_missing_funding=True,
    )
    runner = write_runner(workspace, cfg)
    with (
        patch.object(DatasetManager, "load_dataset_with_verification", wrapped_load),
        patch.object(pd, "read_parquet", tracking_read_parquet),
        patch.object(Path, "read_text", tracking_read_text),
        patch.object(Path, "read_bytes", tracking_read_bytes),
        patch.object(Path, "open", tracking_open),
    ):
        runner.run()

    data_root = (workspace / "data").resolve()
    unexpected = []
    for path in opened:
        try:
            path.relative_to(data_root)
        except ValueError:
            continue
        if path in verified_paths:
            continue
        unexpected.append(path)
    assert not unexpected, (
        "runner opened data paths not returned by the verifier: "
        + ", ".join(str(path) for path in unexpected)
    )
    assert unverified.resolve() not in {path.resolve() for path in opened}
    assert (workspace / "data" / "raw" / "SHOULD_NOT_READ.json").resolve() not in {
        path.resolve() for path in opened
    }
