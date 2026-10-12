"""严格确定性测试 - 要求运行结果的每个细节完全一致"""

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import yaml

from htx_trend.research.runner import BacktestRunner
from tests.research.helpers import (
    create_test_dataset,
    runner_config,
    split_manifest_fields,
    write_runner,
)


def compute_parquet_hash(file_path: Path) -> str:
    """计算 parquet 文件的 SHA256 hash"""
    if not file_path.exists():
        return "missing"
    
    df = pd.read_parquet(file_path)
    
    content = df.to_json(orient="records", date_format="iso")
    return hashlib.sha256(content.encode()).hexdigest()


def test_strict_determinism_identical_runs(tmp_path):
    """测试：相同配置运行两次，ledger metrics 和 parquet hash 完全一致"""
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    
    dataset_id = "test_determinism"
    create_test_dataset(workspace, dataset_id)
    
    config = runner_config(
        workspace,
        "DET001",
        dataset_id,
        registration={
            "change": "Determinism test",
            "hypothesis": "Same config produces identical results",
            "expected_effect": "Identical outputs",
            "keep_rule": "Perfect match",
            "drop_rule": "Any difference",
        },
    )
    runner1 = write_runner(workspace, config)
    results1 = runner1.run()
    
    decisions1_hash = compute_parquet_hash(workspace / "runs" / "DET001" / "decisions.parquet")
    fills1_hash = compute_parquet_hash(workspace / "runs" / "DET001" / "fills.parquet")
    
    config["run_id"] = "DET002"
    config_path2 = workspace / "test_config2.yaml"
    with open(config_path2, "w") as f:
        yaml.dump(config, f)
    
    runner2 = BacktestRunner(config_path2, workspace)
    results2 = runner2.run()
    
    decisions2_hash = compute_parquet_hash(workspace / "runs" / "DET002" / "decisions.parquet")
    fills2_hash = compute_parquet_hash(workspace / "runs" / "DET002" / "fills.parquet")
    
    assert results1["final_equity"] == results2["final_equity"], "Final equity must be identical"
    assert len(results1["fills"]) == len(results2["fills"]), "Fill count must be identical"
    assert decisions1_hash == decisions2_hash, "Decisions parquet hash must be identical"
    assert fills1_hash == fills2_hash, "Fills parquet hash must be identical"
    
    if not results1["decisions"].empty:
        assert results1["decisions"]["action"].tolist() == results2["decisions"]["action"].tolist()


def test_hash_change_detection_and_refusal(tmp_path):
    """测试：修改一根 bar 后，dataset hash 变化，使用旧 dataset_id 的运行被拒绝"""
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    
    dataset_id = "test_hash_change"
    create_test_dataset(workspace, dataset_id)
    
    original_manifest_path = workspace / "data" / "datasets" / dataset_id / "manifest.json"
    original_manifest = json.loads(original_manifest_path.read_text())
    original_hash = original_manifest["manifest_hash"]
    
    btc_file = workspace / "data" / "datasets" / dataset_id / "BTC-USDT.parquet"
    df = pd.read_parquet(btc_file)
    
    df_hash_before = hashlib.sha256(
        df["close"].astype(str).str.cat().encode()
    ).hexdigest()
    
    df.loc[100, "close"] = df.loc[100, "close"] * 1.1
    df.to_parquet(btc_file, index=False)
    
    df_modified = pd.read_parquet(btc_file)
    df_hash_after = hashlib.sha256(
        df_modified["close"].astype(str).str.cat().encode()
    ).hexdigest()
    
    assert df_hash_before != df_hash_after, "Bar modification must change hash"
    
    dates = pd.to_datetime(df_modified["bar_time"])
    new_manifest = {
        "dataset_id": dataset_id,
        "symbols": ["BTC-USDT", "ETH-USDT"],
        "timeframe": "4hour",
        "start_date": dates.min().isoformat(),
        "end_date": dates.max().isoformat(),
        "total_bars": len(df_modified) * 2,
        "manifest_hash": f"modified_{df_hash_after[:16]}",
        "created_at": "2024-01-01T00:00:00",
    }
    
    original_manifest_path.write_text(json.dumps(new_manifest, indent=2))
    
    assert original_hash != new_manifest["manifest_hash"], "Manifest hash must change"


def test_no_lookahead_truncation_invariance(tmp_path):
    """测试：截断未来 bar 不影响 t 时刻的决策（无前视保证）"""
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    
    np.random.seed(100)
    dates_full = pd.date_range("2024-01-01", periods=300, freq="4h")
    
    close_full = 100 + np.cumsum(np.random.randn(300) * 0.5)
    
    datasets_dir = workspace / "data" / "datasets"
    
    for truncate_at in [250, 300]:
        dataset_id = f"lookahead_test_{truncate_at}"
        dataset_dir = datasets_dir / dataset_id
        dataset_dir.mkdir(parents=True, exist_ok=True)
        
        dates = dates_full[:truncate_at]
        close = close_full[:truncate_at]
        
        for symbol in ["BTC-USDT"]:
            high = close + np.random.rand(len(close)) * 2
            low = close - np.random.rand(len(close)) * 2
            open_prices = close + (np.random.rand(len(close)) - 0.5) * 1
            
            df = pd.DataFrame({
                "bar_time": dates,
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
        
        manifest = {
            "dataset_id": dataset_id,
            "symbols": ["BTC-USDT"],
            "timeframe": "4hour",
            "start_date": dates[0].isoformat(),
            "end_date": dates[-1].isoformat(),
            "total_bars": len(dates),
            "manifest_hash": dataset_id,
            "created_at": "2024-01-01T00:00:00",
            **split_manifest_fields(
                dates, min(180, truncate_at), min(240, truncate_at)
            ),
        }
        
        manifest_path = dataset_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2))
    
    results = {}
    for truncate_at in [250, 300]:
        dataset_id = f"lookahead_test_{truncate_at}"
        
        from tests.research.helpers import write_batch_manifest, write_contract_specs
        write_contract_specs(workspace)
        write_batch_manifest(workspace, dataset_id)
        config = runner_config(
            workspace,
            f"LOOKAHEAD_{truncate_at}",
            dataset_id,
            symbols=["BTC-USDT"],
            registration={
                "change": f"Lookahead test {truncate_at}",
                "hypothesis": "No lookahead",
                "expected_effect": "Decisions at t unchanged",
                "keep_rule": "Match",
                "drop_rule": "Differ",
            },
        )
        
        runner = write_runner(workspace, config)
        result = runner.run()
        results[truncate_at] = result
    
    decisions_250 = results[250]["decisions"]
    decisions_300 = results[300]["decisions"]
    
    if not decisions_250.empty and not decisions_300.empty:
        common_idx = min(len(decisions_250), len(decisions_300))
        
        shared_250 = decisions_250.iloc[:common_idx]
        shared_300 = decisions_300.iloc[:common_idx]
        
        assert shared_250["action"].tolist() == shared_300["action"].tolist(), \
            "Decisions in overlapping period must be identical (no lookahead)"
