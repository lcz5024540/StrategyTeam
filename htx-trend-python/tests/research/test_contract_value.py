"""P0-A: HTX contract_size must price notional/fees/PnL/funding/margin."""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from htx_trend.research.engine.backtest.sim_broker import CostScenario, SimBroker
from htx_trend.research.engine.contract_value import (
    HTX_LINEAR_SPECS,
    coin_notional,
    require_contract_size,
)
from htx_trend.research.engine.interfaces import Order
from htx_trend.research.risk.liquidation_check import AccountLiquidationChecker
from tests.research.helpers import DEFAULT_SPECS, write_runner
from tests.research.test_runner_e2e import _breakout_config, _breakout_workspace


def test_htx_linear_specs_match_exchange():
    assert HTX_LINEAR_SPECS["BTC-USDT"]["contract_size"] == 0.001
    assert HTX_LINEAR_SPECS["ETH-USDT"]["contract_size"] == 0.01
    assert DEFAULT_SPECS["BTC-USDT"]["contract_size"] == 0.001
    assert DEFAULT_SPECS["ETH-USDT"]["contract_size"] == 0.01


def test_missing_contract_size_fails():
    with pytest.raises(ValueError, match="contract_size"):
        SimBroker(400.0)
    with pytest.raises(ValueError, match="contract_size"):
        require_contract_size(None, "BTC-USDT")
    with pytest.raises(ValueError, match="contract_size"):
        AccountLiquidationChecker().check_account_liquidation_risk(
            400.0,
            [{"symbol": "BTC-USDT", "size": 1, "entry_price": 80000}],
            [],
        )


def test_contract_size_applied_to_fees_pnl_and_400u_equity(tmp_path):
    btc_cs = HTX_LINEAR_SPECS["BTC-USDT"]["contract_size"]
    eth_cs = HTX_LINEAR_SPECS["ETH-USDT"]["contract_size"]
    price = 80459.5
    contracts = 14
    taker = 0.0006
    expected_notional = contracts * btc_cs * price
    expected_fee = expected_notional * taker

    broker = SimBroker(
        400.0,
        CostScenario.BASE,
        contract_sizes={"BTC-USDT": btc_cs, "ETH-USDT": eth_cs},
    )
    broker.submit_order(
        Order(symbol="BTC-USDT", side="LONG", size=contracts, order_type="MARKET")
    )
    bar_time = datetime(2024, 1, 2, tzinfo=timezone.utc)
    broker.process_next_bar(
        "BTC-USDT",
        {"open": price, "close": price, "high": price, "low": price, "bar_time": bar_time},
    )
    assert broker.fills
    fill = broker.fills[0]
    assert fill.contract_size == btc_cs
    assert fill.notional == pytest.approx(expected_notional)
    assert fill.notional == pytest.approx(coin_notional(price, contracts, btc_cs, "BTC-USDT"))
    assert fill.fees == pytest.approx(expected_fee)
    assert fill.fees == pytest.approx(expected_notional * taker)
    # Regression: 14 contracts must not be billed as 14 BTC (225.29U maker).
    assert fill.fees == pytest.approx(0.6758598)
    assert fill.fees < 1.0

    broker.apply_funding("BTC-USDT", 0.0001)
    pos = broker.positions["BTC-USDT"]
    assert pos.accumulated_funding == pytest.approx(expected_notional * 0.0001)

    broker.submit_order(
        Order(
            symbol="BTC-USDT",
            side="SHORT",
            size=contracts,
            order_type="MARKET",
            reduce_only=True,
        )
    )
    exit_price = price + 10
    broker.process_next_bar(
        "BTC-USDT",
        {
            "open": exit_price,
            "close": exit_price,
            "high": exit_price,
            "low": exit_price,
            "bar_time": datetime(2024, 1, 2, 4, tzinfo=timezone.utc),
        },
    )
    equity = broker.get_account_equity()
    assert 50.0 <= equity <= 2000.0
    assert equity > 0
    broker.close()

    workspace = _breakout_workspace(tmp_path)
    runner = write_runner(
        workspace,
        _breakout_config(workspace, "CS400", initial_equity=400.0),
    )
    results = runner.run()
    assert results["initial_equity"] == 400.0
    assert 50.0 <= results["final_equity"] <= 2000.0
    for item in results["fills"]:
        expected = item.size * item.contract_size * item.price
        assert item.notional == pytest.approx(expected)
        assert item.fees == pytest.approx(item.notional * 0.0006, rel=1e-6)
    runner.close()
