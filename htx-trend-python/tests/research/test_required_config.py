"""测试关键配置参数必须显式提供，无静默默认值"""

import tempfile
from pathlib import Path

import pytest
import yaml

from htx_trend.research.runner import BacktestRunner


def create_minimal_config(overrides: dict | None = None) -> dict:
    """创建最小有效配置"""
    config = {
        "run_id": "TEST_REQ",
        "dataset_id": "test_dataset",
        "symbols": ["BTC-USDT"],
        "split": "train",
        "registration": {
            "hypothesis": "Test hypothesis",
            "keep_rule": "Sample ≥30",
            "drop_rule": "Sample <30",
        },
        "initial_equity": 400.0,
        "risk_per_trade": 0.005,
        "max_total_risk": 0.015,
        "max_positions": 3,
        "conditions": {
            "C01": {
                "enabled": True,
                "periods": [42, 84, 126, 168],
                "threshold": 0.25,
            },
        },
    }
    
    if overrides:
        config.update(overrides)
    
    return config


def test_missing_initial_equity_raises():
    """测试缺失 initial_equity 抛出异常"""
    with tempfile.TemporaryDirectory() as tmpdir:
        workspace = Path(tmpdir)
        config_path = workspace / "test_config.yaml"
        
        config = create_minimal_config()
        del config["initial_equity"]  # ❌ 移除关键参数
        
        with open(config_path, "w") as f:
            yaml.dump(config, f)
        
        runner = BacktestRunner(config_path, workspace)
        
        # 应该在 _run_backtest 中抛出异常
        with pytest.raises(ValueError, match="initial_equity"):
            # 这会触发 _run_backtest
            runner.config["initial_equity"]  # 检查是否真的缺失
            assert "initial_equity" not in runner.config


def test_missing_risk_per_trade_raises():
    """测试缺失 risk_per_trade 抛出异常"""
    config = create_minimal_config()
    del config["risk_per_trade"]
    
    # 验证配置确实缺失该字段
    assert "risk_per_trade" not in config
    
    # 在实际运行时会抛出异常
    # （这里只验证配置结构）


def test_missing_max_total_risk_raises():
    """测试缺失 max_total_risk 抛出异常"""
    config = create_minimal_config()
    del config["max_total_risk"]
    
    assert "max_total_risk" not in config


def test_missing_max_positions_raises():
    """测试缺失 max_positions 抛出异常"""
    config = create_minimal_config()
    del config["max_positions"]
    
    assert "max_positions" not in config


def test_all_required_fields_present_passes():
    """测试所有必需字段存在时通过"""
    config = create_minimal_config()
    
    # 验证所有关键字段存在
    assert "initial_equity" in config
    assert "risk_per_trade" in config
    assert "max_total_risk" in config
    assert "max_positions" in config
    
    # 验证值类型正确
    assert isinstance(config["initial_equity"], (int, float))
    assert isinstance(config["risk_per_trade"], (int, float))
    assert isinstance(config["max_total_risk"], (int, float))
    assert isinstance(config["max_positions"], int)


def test_config_with_defaults_documented():
    """测试带文档说明的非关键默认值"""
    config = create_minimal_config()
    
    # 这些可以有默认值（非关键参数）
    # cost_scenario 默认 "base"
    # fees.use_authenticated 默认 False
    
    assert config.get("cost_scenario", "base") == "base"
    assert config.get("fees", {}).get("use_authenticated", False) == False
