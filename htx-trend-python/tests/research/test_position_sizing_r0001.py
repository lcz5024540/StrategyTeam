"""R0001 sizing: 300U, 2% per trade, 10% total cap, max 5 positions, no force-1."""

from __future__ import annotations

import pytest

from htx_trend.research.risk.position_sizing import (
    SKIP_MAX_POSITIONS,
    SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET,
    SKIP_TOTAL_RISK_CAP,
    PositionSizer,
)

EQUITY = 300.0
PER_TRADE = 0.02
CAP = 0.10
MAX_POSITIONS = 5


def _r0001_sizer(**overrides) -> PositionSizer:
    values = dict(
        risk_per_trade_pct=PER_TRADE,
        max_total_risk_pct=CAP,
        max_positions=MAX_POSITIONS,
        target_leverage=5.0,
    )
    values.update(overrides)
    return PositionSizer(**values)


def _full_risk_positions(n: int, *, equity: float = EQUITY, per_trade: float = PER_TRADE) -> list[dict]:
    amount = equity * per_trade
    return [{"symbol": f"SYM-{i}", "risk_amount": amount} for i in range(n)]


def _btc_kwargs(existing: list[dict]) -> dict:
    return dict(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=48000.0,
        account_equity=EQUITY,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=existing,
    )


def test_total_risk_cap_reason_is_distinct():
    assert SKIP_TOTAL_RISK_CAP == "total_risk_cap_exceeded"
    assert SKIP_TOTAL_RISK_CAP != SKIP_MAX_POSITIONS
    assert SKIP_TOTAL_RISK_CAP != SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET


def test_r0001_two_percent_of_300_is_six_usdt_per_trade():
    sizer = _r0001_sizer()
    # 1 BTC contract = 0.001 * 50000 = 50U notional; 4% stop → 2U risk < 6U budget.
    pos = sizer.calculate_position(**_btc_kwargs([]))
    assert pos.size >= 1
    assert pos.skip_reason is None
    assert pos.risk_pct <= PER_TRADE + 1e-12
    assert pos.risk_budget_usdt == pytest.approx(6.0)


def test_r0001_rejects_when_one_contract_exceeds_per_trade_budget():
    sizer = _r0001_sizer()
    # 1 contract notional 50U, 14% stop → 7U risk > 6U budget. Must not force 1 contract.
    pos = sizer.calculate_position(
        symbol="BTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=43000.0,
        account_equity=EQUITY,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=[],
    )
    assert pos.size == 0
    assert pos.skip_reason == SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET
    assert pos.one_contract_risk_usdt == pytest.approx(7.0)
    assert pos.risk_budget_usdt == pytest.approx(6.0)
    assert pos.one_contract_risk_usdt > pos.risk_budget_usdt


def test_r0001_enforces_max_five_positions():
    sizer = _r0001_sizer()
    existing = [{"symbol": f"P{i}", "risk_amount": 0.1} for i in range(5)]
    pos = sizer.calculate_position(**_btc_kwargs(existing))
    assert pos.size == 0
    assert pos.skip_reason == SKIP_MAX_POSITIONS
    assert pos.skip_reason != SKIP_TOTAL_RISK_CAP


def test_sixth_full_risk_position_rejected_with_total_risk_cap_exceeded():
    """2% per trade, 10% cap: five full-risk slots fill the cap; 6th is a cap skip."""
    sizer = _r0001_sizer(max_positions=10)
    existing = [
        {"symbol": "BTC-USDT", "risk_amount": 6.0},
        {"symbol": "ETH-USDT", "risk_amount": 6.0},
        {"symbol": "SOL-USDT", "risk_amount": 6.0},
        {"symbol": "XRP-USDT", "risk_amount": 6.0},
        {"symbol": "DOGE-USDT", "risk_amount": 6.0},
    ]
    pos = sizer.calculate_position(
        symbol="LTC-USDT",
        side="LONG",
        entry_price=50000.0,
        stop_loss=48000.0,
        account_equity=EQUITY,
        contract_size=0.001,
        min_order_size=1,
        existing_positions=existing,
    )
    assert pos.size == 0
    assert pos.skip_reason == SKIP_TOTAL_RISK_CAP
    assert pos.skip_reason == "total_risk_cap_exceeded"
    assert pos.skip_reason != SKIP_MAX_POSITIONS
    assert pos.skip_reason != SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET


@pytest.mark.parametrize(
    "per_trade,cap,n_existing,expect_reject",
    [
        (0.03, 0.10, 3, True),
        (0.03, 0.10, 2, False),
        (0.02, 0.10, 5, True),
        (0.02, 0.10, 4, False),
    ],
)
def test_nth_full_risk_position_respects_total_risk_cap(
    per_trade, cap, n_existing, expect_reject
):
    sizer = PositionSizer(
        risk_per_trade_pct=per_trade,
        max_total_risk_pct=cap,
        max_positions=20,
        target_leverage=5.0,
    )
    pos = sizer.calculate_position(**{
        **_btc_kwargs(_full_risk_positions(n_existing, per_trade=per_trade)),
    })
    if expect_reject:
        assert pos.size == 0
        assert pos.skip_reason == SKIP_TOTAL_RISK_CAP
        assert pos.skip_reason != SKIP_MAX_POSITIONS
        assert pos.skip_reason != SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET
    else:
        assert pos.size > 0
        assert pos.skip_reason is None
