"""Shared research-test fixtures."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

from htx_trend.research.data.collector import hash_funding_files, hash_spec_files
from htx_trend.research.data.dataset import utc_iso
from htx_trend.research.data.manifest import DatasetManifest
from htx_trend.research.runner import BacktestRunner


DEFAULT_SPECS = {
    "BTC-USDT": {
        "contract_code": "BTC-USDT",
        "symbol": "BTC",
        "contract_size": 0.001,
        "price_tick": 0.1,
        "min_order_size": 1,
        "create_date": 0,
    },
    "ETH-USDT": {
        "contract_code": "ETH-USDT",
        "symbol": "ETH",
        "contract_size": 0.01,
        "price_tick": 0.01,
        "min_order_size": 1,
        "create_date": 0,
    },
}


def write_nominal_funding(
    workspace: Path,
    symbols: list[str],
    *,
    dataset_id: str | None = None,
    start=None,
    end=None,
    rate: float = 0.0001,
) -> None:
    """Write 8h funding covering the fixture bars so gap checks pass."""
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    if start is None or end is None:
        if not dataset_id:
            raise ValueError("write_nominal_funding requires dataset_id or start/end")
        frames = [
            pd.read_parquet(path)
            for path in sorted((workspace / "data" / "datasets" / dataset_id).glob("*.parquet"))
        ]
        times = pd.concat(
            [pd.to_datetime(frame["bar_time"], utc=True) for frame in frames]
        )
        start = times.min() - pd.Timedelta(hours=8)
        end = times.max() + pd.Timedelta(hours=8)
    stamps = pd.date_range(start, end, freq="8h", tz="UTC")
    for symbol in symbols:
        pd.DataFrame(
            {
                "symbol": [symbol] * len(stamps),
                "funding_time": stamps,
                "funding_rate": [rate] * len(stamps),
                "source": ["htx"] * len(stamps),
            }
        ).to_parquet(raw / f"{symbol}_funding.parquet", index=False)


def write_contract_specs(workspace: Path, specs: dict | None = None) -> None:
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    payload = specs or DEFAULT_SPECS
    for symbol, spec in payload.items():
        (raw / f"{symbol}_spec.json").write_text(json.dumps(spec, indent=2))


def record_funding_files(workspace: Path, dataset_id: str, symbols: list[str]) -> str:
    raw = workspace / "data" / "raw"
    path = workspace / "data" / "datasets" / "manifests" / f"{dataset_id}.json"
    batch = DatasetManifest.load(path)
    files: dict[str, dict[str, str]] = {}
    for symbol in symbols:
        funding = raw / f"{symbol}_funding.parquet"
        if not funding.exists():
            continue
        digest = hashlib.sha256(funding.read_bytes()).hexdigest()
        files[symbol] = {
            "path": f"{symbol}_funding.parquet",
            "sha256": f"sha256:{digest}",
        }
    batch.metadata["funding_files"] = files
    batch.save(path)
    return batch.sha256()


def split_manifest_fields(
    dates,
    train_end_idx: int,
    val_end_idx: int,
    interval: str = "4h",
) -> dict:
    """Write frozen split timestamps for fixture manifests (idx-compatible)."""
    index = pd.to_datetime(dates, utc=True)
    delta = pd.Timedelta(interval)

    def _bound(idx: int):
        if idx < len(index):
            return index[idx]
        return index[-1] + delta

    return {
        "data_cutoff_close_utc": utc_iso(index[-1] + delta),
        "split": {
            "validation_start_utc": utc_iso(_bound(train_end_idx)),
            "test_start_utc": utc_iso(_bound(val_end_idx)),
        },
        "test_hash": "fixture-test-hash",
    }


def write_batch_manifest(workspace: Path, dataset_id: str) -> str:
    dataset_dir = workspace / "data" / "datasets" / dataset_id
    manifest = DatasetManifest.generate_from_dataset_dir(dataset_dir, dataset_id)
    raw = workspace / "data" / "raw"
    symbols = [parquet.stem for parquet in sorted(dataset_dir.glob("*.parquet"))]
    spec_files = hash_spec_files(raw, symbols) if raw.exists() else {}
    if spec_files:
        manifest.metadata["spec_files"] = spec_files
    funding_files = hash_funding_files(raw, symbols) if raw.exists() else {}
    if funding_files:
        manifest.metadata["funding_files"] = funding_files
    aux: dict[str, dict[str, str]] = {}
    simple = dataset_dir / "manifest.json"
    if simple.exists():
        written = json.loads(simple.read_text())
        if "stitch_points" in written:
            manifest.metadata["stitch_points"] = written["stitch_points"]
        aux["dataset_manifest"] = {
            "path": "manifest.json",
            "sha256": f"sha256:{hashlib.sha256(simple.read_bytes()).hexdigest()}",
        }
    overlap = dataset_dir / "overlap_deviation.json"
    if overlap.exists():
        aux["overlap_deviation"] = {
            "path": "overlap_deviation.json",
            "sha256": f"sha256:{hashlib.sha256(overlap.read_bytes()).hexdigest()}",
        }
    if aux:
        manifest.metadata["aux_files"] = aux
    path = workspace / "data" / "datasets" / "manifests" / f"{dataset_id}.json"
    manifest.save(path)
    return manifest.sha256()


def create_test_dataset(
    workspace: Path,
    dataset_id: str,
    seed: int = 42,
    n_bars: int = 300,
    train_end_idx: int = 180,
    val_end_idx: int = 240,
    symbols: list[str] | None = None,
    specs: dict | None = None,
    close_series: dict[str, np.ndarray] | None = None,
    ohlc: dict[str, dict[str, np.ndarray]] | None = None,
) -> str:
    np.random.seed(seed)
    dates = pd.date_range("2024-01-01", periods=n_bars, freq="4h", tz="UTC")
    datasets_dir = workspace / "data" / "datasets"
    dataset_dir = datasets_dir / dataset_id
    dataset_dir.mkdir(parents=True, exist_ok=True)
    symbols = symbols or ["BTC-USDT", "ETH-USDT"]

    for symbol in symbols:
        if ohlc and symbol in ohlc:
            frame = ohlc[symbol]
            close = np.asarray(frame["close"], dtype=float)
            high = np.asarray(frame["high"], dtype=float)
            low = np.asarray(frame["low"], dtype=float)
            open_prices = np.asarray(frame["open"], dtype=float)
        elif close_series and symbol in close_series:
            close = np.asarray(close_series[symbol], dtype=float)
            high = close + 0.5
            low = close - 0.5
            open_prices = close
        else:
            close = 100 + np.cumsum(np.random.randn(n_bars) * 0.5)
            high = close + np.abs(np.random.rand(len(close)) * 2)
            low = close - np.abs(np.random.rand(len(close)) * 2)
            open_prices = close + (np.random.rand(len(close)) - 0.5) * 1
        df = pd.DataFrame({
            "bar_time": dates[: len(close)],
            "open": open_prices,
            "high": high,
            "low": low,
            "close": close,
            "volume": np.random.rand(len(close)) * 1000,
            "amount": np.random.rand(len(close)) * 100000,
            "source": "fixture",
            "incomplete": False,
            "non_comparable": False,
            "bad_tick": False,
            "external": False,
        })
        df.to_parquet(dataset_dir / f"{symbol}.parquet", index=False)

    used_dates = dates[: len(close)]
    manifest = {
        "dataset_id": dataset_id,
        "symbols": symbols,
        "timeframe": "4hour",
        "start_date": used_dates[0].isoformat(),
        "end_date": used_dates[-1].isoformat(),
        "total_bars": n_bars * len(symbols),
        "manifest_hash": dataset_id,
        "created_at": "2024-01-01T00:00:00",
        "segment_counts": {
            symbol: {
                "train": train_end_idx,
                "val": val_end_idx - train_end_idx,
                "test": n_bars - val_end_idx,
            }
            for symbol in symbols
        },
        **split_manifest_fields(used_dates, train_end_idx, val_end_idx),
        "stitch_points": {symbol: None for symbol in symbols},
    }
    (dataset_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))
    write_contract_specs(workspace, specs)
    write_batch_manifest(workspace, dataset_id)
    return dataset_id


def manifest_hash_for(workspace: Path, dataset_id: str) -> str:
    path = workspace / "data" / "datasets" / "manifests" / f"{dataset_id}.json"
    return DatasetManifest.load(path).sha256()


def runner_config(workspace: Path, run_id: str, dataset_id: str, **overrides) -> dict:
    config = {
        "run_id": run_id,
        "parent_run_id": None,
        "registration": {
            "change": "Baseline",
            "hypothesis": "h",
            "expected_effect": "e",
            "keep_rule": "k",
            "drop_rule": "d",
            "evidence_ref": "docs/REMAINING_WORK.md",
        },
        "dataset_id": dataset_id,
        "manifest_hash": manifest_hash_for(workspace, dataset_id),
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
                "enabled": True,
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    }
    if "registration" in overrides and isinstance(overrides["registration"], dict):
        config["registration"].update(overrides.pop("registration"))
    config.update(overrides)
    return config


def write_runner(workspace: Path, config: dict) -> BacktestRunner:
    if "strategy_manifest_sha256" not in config:
        from htx_trend.research.strategy_manifest import build_strategy_manifest

        config["strategy_manifest_sha256"] = build_strategy_manifest(config)[
            "manifest_sha256"
        ]
    path = workspace / f"{config['run_id']}.yaml"
    path.write_text(yaml.dump(config))
    return BacktestRunner(path, workspace)
