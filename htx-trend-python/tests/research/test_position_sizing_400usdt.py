"""测试仓位计算在 400 USDT 初始资金下的最小合约检查"""

from htx_trend.research.risk.position_sizing import PositionSizer


def test_position_sizing_with_400_usdt_initial_capital():
    """验证 400 USDT 初始资金下的仓位计算"""
    sizer = PositionSizer(
        risk_per_trade_pct=0.005,  # 0.5%
        max_total_risk_pct=0.015,  # 1.5%
        max_positions=3,
        target_leverage=5.0,
    )
    
    # BTC-USDT: 价格 50000 USDT, 合约规模 0.001 BTC
    entry_price = 50000.0
    stop_loss = 48000.0  # 4% 止损
    contract_size = 0.001  # 1 张合约 = 0.001 BTC
    min_order_size = 1  # 最小 1 张
    
    account_equity = 400.0  # 初始资金
    
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=entry_price,
        stop_loss=stop_loss,
        account_equity=account_equity,
        contract_size=contract_size,
        min_order_size=min_order_size,
        existing_positions=[],
    )
    
    # 风险金额 = 400 * 0.005 = 2 USDT
    # 止损距离 = (50000 - 48000) / 50000 = 4%
    # 名义价值 = 2 / 0.04 = 50 USDT
    # 合约数量 = 50 / (50000 * 0.001) = 1 张
    
    assert pos.size == 1, f"Expected 1 contract, got {pos.size}"
    assert pos.skip_reason is None, f"Should not skip: {pos.skip_reason}"
    assert pos.risk_pct > 0


def test_position_sizing_rejects_too_small_account():
    """如果账户太小无法满足最小合约规模，应拒绝"""
    sizer = PositionSizer(
        risk_per_trade_pct=0.005,  # 0.5%
        max_total_risk_pct=0.015,
        max_positions=3,
        target_leverage=5.0,
    )
    
    # BTC-USDT: 价格 50000 USDT, 止损 2%
    entry_price = 50000.0
    stop_loss = 49000.0  # 2% 止损
    contract_size = 0.001
    min_order_size = 1
    
    # 账户过小：100 USDT
    account_equity = 100.0
    
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=entry_price,
        stop_loss=stop_loss,
        account_equity=account_equity,
        contract_size=contract_size,
        min_order_size=min_order_size,
        existing_positions=[],
    )
    
    # 风险金额 = 100 * 0.005 = 0.5 USDT
    # 止损距离 = 2%
    # 名义价值 = 0.5 / 0.02 = 25 USDT
    # 合约数量 = 25 / 50 = 0.5 → 取整为 0 < min_order_size
    
    assert pos.size == 0, f"Should skip, got size {pos.size}"
    assert pos.skip_reason is not None
    assert "Min order size" in pos.skip_reason


def test_position_sizing_with_400_usdt_tight_stop():
    """400 USDT + 紧止损（1%）应该允许更大仓位"""
    sizer = PositionSizer(
        risk_per_trade_pct=0.005,
        max_total_risk_pct=0.015,
        max_positions=3,
        target_leverage=5.0,
    )
    
    entry_price = 50000.0
    stop_loss = 49500.0  # 1% 止损（更紧）
    contract_size = 0.001
    min_order_size = 1
    account_equity = 400.0
    
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=entry_price,
        stop_loss=stop_loss,
        account_equity=account_equity,
        contract_size=contract_size,
        min_order_size=min_order_size,
        existing_positions=[],
    )
    
    # 风险金额 = 400 * 0.005 = 2 USDT
    # 止损距离 = 1%
    # 名义价值 = 2 / 0.01 = 200 USDT
    # 合约数量 = 200 / 50 = 4 张
    
    assert pos.size == 4, f"Expected 4 contracts with 1% stop, got {pos.size}"
    assert pos.skip_reason is None


def test_position_sizing_respects_max_total_risk_with_400_usdt():
    """400 USDT 下尊重总风险限制 1.5%"""
    sizer = PositionSizer(
        risk_per_trade_pct=0.005,  # 单笔 0.5%
        max_total_risk_pct=0.015,  # 总共 1.5%
        max_positions=5,  # 提高最大仓位数，以便测试总风险限制
        target_leverage=5.0,
    )
    
    entry_price = 50000.0
    stop_loss = 48000.0  # 4% 止损
    contract_size = 0.001
    min_order_size = 1
    account_equity = 400.0
    
    # 已有 3 个仓位，每个风险 0.5%（总 1.5%）
    existing_positions = [
        {"symbol": "BTC-USDT", "risk_amount": 2.0},  # 2 USDT = 0.5%
        {"symbol": "ETH-USDT", "risk_amount": 2.0},
        {"symbol": "SOL-USDT", "risk_amount": 2.0},
    ]
    
    pos = sizer.calculate_position(
        symbol="AVAX-USDT",
        side="LONG",
        entry_price=30.0,
        stop_loss=28.5,
        account_equity=account_equity,
        contract_size=1.0,
        min_order_size=1,
        existing_positions=existing_positions,
    )
    
    # 总风险已达 1.5%，应拒绝新仓位
    assert pos.size == 0
    assert "Total risk limit" in pos.skip_reason


def test_position_sizing_eth_with_400_usdt():
    """验证 ETH-USDT 在 400 USDT 资金下的仓位计算"""
    sizer = PositionSizer(
        risk_per_trade_pct=0.005,
        max_total_risk_pct=0.015,
        max_positions=3,
        target_leverage=5.0,
    )
    
    # ETH-USDT: 价格 3000 USDT, 合约规模 0.01 ETH
    entry_price = 3000.0
    stop_loss = 2880.0  # 4% 止损
    contract_size = 0.01  # 1 张合约 = 0.01 ETH
    min_order_size = 1
    account_equity = 400.0
    
    pos = sizer.calculate_position(
        symbol="ETH-USDT",
        side="LONG",
        entry_price=entry_price,
        stop_loss=stop_loss,
        account_equity=account_equity,
        contract_size=contract_size,
        min_order_size=min_order_size,
        existing_positions=[],
    )
    
    # 风险金额 = 400 * 0.005 = 2 USDT
    # 止损距离 = 4%
    # 名义价值 = 2 / 0.04 = 50 USDT
    # 合约数量 = 50 / (3000 * 0.01) = 50 / 30 = 1.67 → 取整为 1 张
    
    assert pos.size == 1, f"Expected 1 ETH contract, got {pos.size}"
    assert pos.skip_reason is None
