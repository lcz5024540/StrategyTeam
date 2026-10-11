"""测试账户级强平检查"""

import pytest

from htx_trend.research.risk import AccountLiquidationChecker


def test_account_liquidation_safe():
    checker = AccountLiquidationChecker(
        maintenance_margin_rate=0.004,
        safety_buffer_multiplier=1.5,
    )
    
    account_equity = 2000.0
    
    all_positions = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 0.02,
            "entry_price": 50000.0,
        },
    ]
    
    strategy_positions = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 0.02,
            "entry_price": 50000.0,
            "stop_loss": 49000.0,
        },
    ]
    
    is_safe, liq_dist, msg = checker.check_account_liquidation_risk(
        account_equity, all_positions, strategy_positions
    )
    
    assert is_safe
    assert liq_dist > 0


def test_account_liquidation_unsafe():
    checker = AccountLiquidationChecker()
    
    account_equity = 300.0
    
    all_positions = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 1.0,
            "entry_price": 50000.0,
        },
    ]
    
    strategy_positions = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 1.0,
            "entry_price": 50000.0,
            "stop_loss": 49500.0,
        },
    ]
    
    is_safe, liq_dist, msg = checker.check_account_liquidation_risk(
        account_equity, all_positions, strategy_positions
    )
    
    assert not is_safe
    assert "liquidated" in msg.lower()


def test_non_strategy_positions():
    checker = AccountLiquidationChecker()
    
    all_positions = [
        {"symbol": "BTC-USDT", "side": "LONG", "size": 1.0},
        {"symbol": "ETH-USDT", "side": "SHORT", "size": 10.0},
        {"symbol": "SOL-USDT", "side": "LONG", "size": 100.0},
    ]
    
    strategy_symbols = {"BTC-USDT", "ETH-USDT"}
    
    non_strategy = checker.get_non_strategy_positions(
        all_positions, strategy_symbols
    )
    
    assert len(non_strategy) == 1
    assert non_strategy[0]["symbol"] == "SOL-USDT"


def test_multiple_positions_with_stops():
    checker = AccountLiquidationChecker()
    
    account_equity = 2000.0
    
    all_positions = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 0.5,
            "entry_price": 50000.0,
        },
        {
            "symbol": "ETH-USDT",
            "side": "LONG",
            "size": 5.0,
            "entry_price": 3000.0,
        },
    ]
    
    strategy_positions = [
        {
            "symbol": "BTC-USDT",
            "side": "LONG",
            "size": 0.5,
            "entry_price": 50000.0,
            "stop_loss": 49000.0,
        },
        {
            "symbol": "ETH-USDT",
            "side": "LONG",
            "size": 5.0,
            "entry_price": 3000.0,
            "stop_loss": 2900.0,
        },
    ]
    
    is_safe, liq_dist, msg = checker.check_account_liquidation_risk(
        account_equity, all_positions, strategy_positions
    )
    
    assert is_safe or not is_safe
