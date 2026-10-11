"""BTC+ETH equity conservation with a known funding series and an open position."""

from __future__ import annotations

from unittest.mock import patch

import numpy as np
import pandas as pd
import pytest

from htx_trend.research.risk.position_sizing import PositionSize, PositionSizer
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    record_funding_files,
    runner_config,
    write_runner,
)

INITIAL_EQUITY = 10_000.0
CONTRACT_SIZE = 1.0
FIXED_SIZE = 1.0
ENTRY_PRICE = 130.0
FUNDING_RATE = 0.10
SETTLEMENTS_PER_SYMBOL = 2
# DefaultFeeProvider BASE taker; ZERO scenario still uses the provider.
TAKER_RATE = 0.0006
# notional = 130 * 1 * 1 = 130; LONG pays 130 * 0.10 = 13 per settlement
HAND_COMPUTED_FUNDING = 13.0 * SETTLEMENTS_PER_SYMBOL * 2
HAND_COMPUTED_ENTRY_FEES = ENTRY_PRICE * FIXED_SIZE * CONTRACT_SIZE * TAKER_RATE * 2
HAND_COMPUTED_FINAL_EQUITY = INITIAL_EQUITY - HAND_COMPUTED_FUNDING - HAND_COMPUTED_ENTRY_FEES
FUNDING_TIMES = (
    pd.Timestamp("2024-02-05T08:00:00Z"),
    pd.Timestamp("2024-02-05T16:00:00Z"),
)


def _stay_open(n: int = 260) -> np.ndarray:
    close = np.full(n, 100.0)
    close[210:] = ENTRY_PRICE
    return close


def _fixed_size(self, symbol, side, entry_price, stop_loss, account_equity, **kwargs):
    return PositionSize(
        symbol=symbol,
        side=side,
        size=FIXED_SIZE,
        leverage=5.0,
        stop_loss=stop_loss,
        risk_pct=0.005,
    )


def test_equity_conservation_with_known_funding_and_open_position(tmp_path):
    workspace = tmp_path / "fund"
    workspace.mkdir()
    specs = {
        "BTC-USDT": {**DEFAULT_SPECS["BTC-USDT"], "contract_size": CONTRACT_SIZE},
        "ETH-USDT": {**DEFAULT_SPECS["ETH-USDT"], "contract_size": CONTRACT_SIZE},
    }
    create_test_dataset(
        workspace,
        "fund",
        n_bars=260,
        train_end_idx=260,
        val_end_idx=260,
        symbols=["BTC-USDT", "ETH-USDT"],
        specs=specs,
        close_series={
            "BTC-USDT": _stay_open(),
            "ETH-USDT": _stay_open(),
        },
    )
    for symbol in ("BTC-USDT", "ETH-USDT"):
        frame = pd.DataFrame(
            {
                "funding_time": list(FUNDING_TIMES),
                "funding_rate": [FUNDING_RATE, FUNDING_RATE],
                "symbol": [symbol, symbol],
            }
        )
        path = workspace / "data" / "raw" / f"{symbol}_funding.parquet"
        frame.to_parquet(path, index=False)
    record_funding_files(workspace, "fund", ["BTC-USDT", "ETH-USDT"])

    cfg = runner_config(
        workspace,
        "FUNDCONS",
        "fund",
        symbols=["BTC-USDT", "ETH-USDT"],
        initial_equity=INITIAL_EQUITY,
        allow_missing_funding=False,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 0,
            },
        },
    )
    cfg["cost_scenario"] = "zero"
    runner = write_runner(workspace, cfg)
    with patch.object(PositionSizer, "calculate_position", _fixed_size):
        results = runner.run()

    fills = results["fills"]
    entries = [fill for fill in fills if fill.is_entry]
    exits = [fill for fill in fills if not fill.is_entry]
    assert {fill.symbol for fill in entries} == {"BTC-USDT", "ETH-USDT"}
    assert exits == []
    assert {fill.price for fill in entries} == {ENTRY_PRICE}
    assert {fill.size for fill in entries} == {FIXED_SIZE}

    events = results["funding_events"]
    assert len(events) == 4
    assert {event["symbol"] for event in events} == {"BTC-USDT", "ETH-USDT"}
    assert sum(float(event["payment"]) for event in events) == pytest.approx(
        HAND_COMPUTED_FUNDING
    )

    assert results["final_equity"] == pytest.approx(HAND_COMPUTED_FINAL_EQUITY)
    curve = pd.DataFrame(results["equity_curve"])
    assert float(curve["equity"].iloc[-1]) == pytest.approx(HAND_COMPUTED_FINAL_EQUITY)
    assert HAND_COMPUTED_FINAL_EQUITY == pytest.approx(9947.844)
    assert HAND_COMPUTED_ENTRY_FEES == pytest.approx(0.156)
