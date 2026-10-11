"""测试确定性 - 相同配置必须产生相同结果"""

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import yaml

from htx_trend.research.runner import BacktestRunner


def create_fixture_dataset(workspace: Path) -> str:
    np.random.seed(42)
    dates = pd.date_range("2024-01-01", periods=300, freq="4h")
    
    datasets_dir = workspace / "data" / "datasets"
    datasets_dir.mkdir(parents=True, exist_ok=True)
    
    for symbol in ["BTC-USDT", "ETH-USDT"]:
        close = 100 + np.cumsum(np.random.randn(300) * 0.5)
        high = close + np.random.rand(300) * 2
        low = close - np.random.rand(300) * 2
        open_prices = close + (np.random.rand(300) - 0.5) * 1
        
        df = pd.DataFrame({
            "bar_time": dates,
            "open": open_prices,
            "high": high,
            "low": low,
            "close": close,
            "volume": np.random.rand(300) * 1000,
            "amount": np.random.rand(300) * 100000,
            "source": "fixture",
        })
        
        dataset_id = "4hour_fixture_test"
        dataset_dir = datasets_dir / dataset_id
        dataset_dir.mkdir(parents=True, exist_ok=True)
        
        df.to_parquet(dataset_dir / f"{symbol}.parquet", index=False)
    
    manifest = {
        "dataset_id": dataset_id,
        "symbols": ["BTC-USDT", "ETH-USDT"],
        "timeframe": "4hour",
        "start_date": dates[0].isoformat(),
        "end_date": dates[-1].isoformat(),
        "total_bars": len(dates) * 2,
        "train_end_idx": 180,
        "val_end_idx": 240,
        "manifest_hash": "fixture_test",
        "created_at": "2024-01-01T00:00:00",
    }
    
    manifest_path = dataset_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    
    return dataset_id


def test_deterministic_backtest(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    
    dataset_id = create_fixture_dataset(workspace)
    
    config = {
        "run_id": "TEST001",
        "parent_run_id": None,
        "registration": {
            "change": "Test run",
            "hypothesis": "Testing determinism",
            "expected_effect": "Same results twice",
            "keep_rule": "Identical results",
            "drop_rule": "Different results",
        },
        "dataset_id": dataset_id,
        "symbols": ["BTC-USDT", "ETH-USDT"],
        "split": "train",
        "stage_acceptance": False,
        "initial_equity": 1000.0,
        "cost_scenario": "base",
        "risk_per_trade": 0.005,
        "max_total_risk": 0.015,
        "max_positions": 3,
        "conditions": {
            "C01": {
                "enabled": True,
                "periods": [42, 84, 126, 168],
                "threshold": 0.25,
            },
            "C02": {
                "atr_period": 14,
                "atr_multiplier": 2.0,
            },
        },
    }
    
    config_path = workspace / "test_config.yaml"
    with open(config_path, "w") as f:
        yaml.dump(config, f)
    
    runner1 = BacktestRunner(config_path, workspace)
    results1 = runner1.run()
    
    decisions1 = results1["decisions"]
    
    config["run_id"] = "TEST002"
    config_path2 = workspace / "test_config2.yaml"
    with open(config_path2, "w") as f:
        yaml.dump(config, f)
    
    runner2 = BacktestRunner(config_path2, workspace)
    results2 = runner2.run()
    
    decisions2 = results2["decisions"]
    
    assert results1["final_equity"] == results2["final_equity"]
    assert len(results1["fills"]) == len(results2["fills"])
    
    if not decisions1.empty and not decisions2.empty:
        actions1 = decisions1["action"].tolist()
        actions2 = decisions2["action"].tolist()
        assert actions1 == actions2


def test_data_change_detection(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    
    dataset_id = create_fixture_dataset(workspace)
    
    datasets_dir = workspace / "data" / "datasets" / dataset_id
    btc_file = datasets_dir / "BTC-USDT.parquet"
    
    df = pd.read_parquet(btc_file)
    
    hash1 = hashlib.sha256(
        df["close"].astype(str).str.cat().encode()
    ).hexdigest()
    
    df.loc[100, "close"] = df.loc[100, "close"] * 1.1
    df.to_parquet(btc_file, index=False)
    
    df_modified = pd.read_parquet(btc_file)
    hash2 = hashlib.sha256(
        df_modified["close"].astype(str).str.cat().encode()
    ).hexdigest()
    
    assert hash1 != hash2
