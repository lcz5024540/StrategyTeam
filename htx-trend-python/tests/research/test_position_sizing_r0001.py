"""R0001 sizing: 300U, 1% per trade, 2% total cap, max 2 positions, no force-1."""

from __future__ import annotations

import pytest

from htx_trend.research.risk.position_sizing import (
    SKIP_MAX_POSITIONS,
    SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET,
    SKIP_TOTAL_RISK_CAP,
    PositionSizer,
)


def _r0001_sizer(**overrides) -> PositionSizer:
    values = dict(
        risk_per_trade_pct=0.01,
        max_total_risk_pct=0.02,
        max_positions=2,
        target_leverage=5.0,
    )
    values.update(overrides)
    return PositionSizer(**values)


def test_r0001_one_percent_of_300_is_three_usdt_per_trade():
    sizer = _r0001_sizer()
    # 1 BTC contract = 0.001 * 50000 = 50U notional; 4% stop → 2U risk < 3U budget.
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=48000.0,
        account_equity=300.0,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=[],
    )
    assert pos.size == 1
    assert pos.skip_reason is None
    assert pos.risk_pct <= 0.01 + 1e-12


def test_r0001_rejects_when_one_contract_exceeds_per_trade_budget():
    sizer = _r0001_sizer()
    # 1 contract notional 50U, 8% stop → 4U risk > 3U budget. Must not force 1 contract.
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=46000.0,
        account_equity=300.0,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=[],
    )
    assert pos.size == 0
    assert pos.skip_reason == SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET
    assert pos.one_contract_risk_usdt == pytest.approx(4.0)
    assert pos.risk_budget_usdt == pytest.approx(3.0)
    assert pos.one_contract_risk_usdt > pos.risk_budget_usdt


def test_r0001_enforces_max_two_positions():
    sizer = _r0001_sizer()
    existing = [
        {"symbol": "BTC-USDT", "risk_amount": 3.0},
        {"symbol": "ETH-USDT", "risk_amount": 3.0},
    ]
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=48000.0,
        account_equity=300.0,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=existing,
    )
    assert pos.size == 0
    assert pos.skip_reason == SKIP_MAX_POSITIONS


def test_r0001_enforces_two_percent_total_risk_cap():
    sizer = _r0001_sizer(max_positions=5)
    # 2% of 300 = 6U already used.
    existing = [
        {"symbol": "BTC-USDT", "risk_amount": 3.0},
        {"symbol": "ETH-USDT", "risk_amount": 3.0},
    ]
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=49500.0,
        account_equity=300.0,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=existing,
    )
    assert pos.size == 0
    assert pos.skip_reason == SKIP_TOTAL_RISK_CAP
