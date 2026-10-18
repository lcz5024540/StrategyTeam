"""Entry fills must deduct fees and slippage from broker equity."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from htx_trend.research.engine.backtest.sim_broker import CostScenario, SimBroker
from htx_trend.research.engine.interfaces import Order


def test_entry_fill_deducts_fees_and_slippage_from_equity():
    broker = SimBroker(
        10_000.0,
        CostScenario.BASE,
        contract_sizes={"BTC-USDT": 0.001},
    )
    broker.submit_order(
        Order(
            symbol="BTC-USDT",
            side="LONG",
            size=1,
            order_type="MARKET",
            stop_slippage=0.1,
        )
    )
    broker.process_next_bar(
        "BTC-USDT",
        {
            "open": 50_000.0,
            "high": 50_100.0,
            "low": 49_900.0,
            "close": 50_000.0,
            "bar_time": datetime(2024, 1, 1, 0, tzinfo=UTC),
        },
    )
    assert broker.fills
    fill = broker.fills[0]
    assert fill.is_entry
    assert fill.fees > 0
    assert broker.equity == pytest.approx(10_000.0 - fill.fees - fill.slippage)
