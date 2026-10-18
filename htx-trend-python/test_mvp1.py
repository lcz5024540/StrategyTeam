#!/usr/bin/env python3
"""MVP-1 快速验证脚本"""

import sys
from pathlib import Path

# 添加 src 到路径
sys.path.insert(0, str(Path(__file__).parent / "src"))

def test_imports():
    """测试所有核心模块可以导入"""
    print("Testing imports...")
    
    from htx_trend.research.adapters.htx import HtxMarketDataClient
    from htx_trend.research.adapters.htx.fees import DefaultFeeProvider
    from htx_trend.research.data import DataCollector
    from htx_trend.research.features import FeatureCalculator
    from htx_trend.research.context import RegimeLabeler
    from htx_trend.research.conditions import C01DonchianEnsemble, C02ATRStop
    from htx_trend.research.risk import PositionSizer, AccountLiquidationChecker
    from htx_trend.research.strategy import StateMachine
    from htx_trend.research.engine.backtest import SimBroker, CostScenario
    from htx_trend.research.ledger import LedgerManager, ApprovalGate
    from htx_trend.research.ops import HeartbeatManager, setup_logging
    
    print("✓ All modules imported successfully")


def test_fee_provider():
    """测试费率提供者"""
    print("\nTesting FeeProvider...")
    
    from htx_trend.research.adapters.htx.fees import DefaultFeeProvider
    
    provider = DefaultFeeProvider(maker_rate=0.0002, taker_rate=0.0006)
    rates = provider.get_fee_rates("BTC-USDT")
    
    assert rates.maker_rate == 0.0002
    assert rates.taker_rate == 0.0006
    assert "default" in rates.source
    
    print(f"✓ FeeProvider works: {rates.to_dict()}")


def test_approvals():
    """测试审批系统"""
    print("\nTesting Approvals...")
    
    import tempfile
    from datetime import UTC, datetime
    from htx_trend.research.ledger import (
        LedgerManager,
        Approval,
        ApprovalGate,
        ApprovalDecision,
    )
    
    with tempfile.TemporaryDirectory() as tmpdir:
        ledger = LedgerManager(Path(tmpdir) / "test.db")
        
        approval = Approval(
            approval_id=None,
            gate=ApprovalGate.PRE_PAPER,
            run_id="TEST001",
            strategy_version="v1.0.0",
            approver="test@example.com",
            decision=ApprovalDecision.APPROVED,
            rationale="Test approval",
            created_at=datetime.now(UTC),
        )
        
        approval_id = ledger.create_approval(approval)
        assert approval_id > 0
        
        is_approved, msg = ledger.check_approval_gate(
            ApprovalGate.PRE_PAPER, "TEST001", "v1.0.0"
        )
        assert is_approved
        
        print(f"✓ Approvals work: {msg}")


def test_account_liquidation():
    """测试账户级强平检查"""
    print("\nTesting AccountLiquidationChecker...")
    
    from htx_trend.research.risk import AccountLiquidationChecker
    
    checker = AccountLiquidationChecker()
    
    all_positions = [
        {"symbol": "BTC-USDT", "side": "LONG", "size": 1.0, "entry_price": 50000.0},
        {"symbol": "ETH-USDT", "side": "LONG", "size": 10.0, "entry_price": 3000.0},
    ]
    
    strategy_positions = [
        {"symbol": "BTC-USDT", "side": "LONG", "size": 1.0, "entry_price": 50000.0, "stop_loss": 49000.0},
    ]
    
    is_safe, liq_dist, msg = checker.check_account_liquidation_risk(
        account_equity=1000.0,
        all_positions=all_positions,
        strategy_positions=strategy_positions,
    )
    
    print(f"✓ AccountLiquidationChecker works: safe={is_safe}, dist={liq_dist:.4f}")
    
    non_strategy = checker.get_non_strategy_positions(
        all_positions, {"BTC-USDT"}
    )
    assert len(non_strategy) == 1
    assert non_strategy[0]["symbol"] == "ETH-USDT"
    
    print(f"✓ Non-strategy position detection works")


def test_features():
    """测试特征计算"""
    print("\nTesting FeatureCalculator...")
    
    import pandas as pd
    import numpy as np
    from htx_trend.research.features import FeatureCalculator
    
    np.random.seed(42)
    dates = pd.date_range("2024-01-01", periods=200, freq="4h")
    close = 100 + np.cumsum(np.random.randn(200) * 0.5)
    
    df = pd.DataFrame({
        "bar_time": dates,
        "open": close + (np.random.rand(200) - 0.5),
        "high": close + np.random.rand(200) * 2,
        "low": close - np.random.rand(200) * 2,
        "close": close,
        "volume": np.random.rand(200) * 1000,
    })
    
    calc = FeatureCalculator()
    atr = calc.atr(df, 14)
    
    assert len(atr) == 200
    assert atr.iloc[-1] > 0
    
    print(f"✓ FeatureCalculator works: ATR[-1]={atr.iloc[-1]:.2f}")


def main():
    print("=" * 60)
    print("HTX Trend Research System MVP-1 Verification")
    print("=" * 60)
    
    try:
        test_imports()
        test_fee_provider()
        test_approvals()
        test_account_liquidation()
        test_features()
        
        print("\n" + "=" * 60)
        print("✓ ALL TESTS PASSED")
        print("=" * 60)
        
        return 0
    
    except Exception as e:
        print(f"\n✗ TEST FAILED: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
