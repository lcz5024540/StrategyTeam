"""P0: multi-symbol event loop, equity conservation, one point per timestamp."""

from __future__ import annotations

from unittest.mock import patch

import numpy as np
import pandas as pd
import pytest

from htx_trend.research.engine.contract_value import periods_per_year
from htx_trend.research.risk.position_sizing import PositionSizer
from htx_trend.research.runner import annualized_sharpe_from_equity
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_runner,
)


def _sync_close(n: int = 260, later_spike: bool = False) -> np.ndarray:
    close = np.full(n, 100.0)
    close[210:215] = 130.0
    close[215:218] = 40.0
    close[218:] = 20.0
    if later_spike:
        close[230:250] = 5000.0
    return close


def _eth_breakout(n: int = 260) -> np.ndarray:
    close = np.full(n, 100.0)
    close[210:] = 130.0
    return close


def _sync_workspace(tmp_path, name: str, later_spike: bool) -> object:
    workspace = tmp_path / name
    workspace.mkdir()
    create_test_dataset(
        workspace,
        "sync",
        n_bars=260,
        train_end_idx=260,
        val_end_idx=260,
        symbols=["BTC-USDT", "ETH-USDT"],
        specs={
            "BTC-USDT": {**DEFAULT_SPECS["BTC-USDT"], "contract_size": 0.001},
            "ETH-USDT": {**DEFAULT_SPECS["ETH-USDT"], "contract_size": 0.001},
        },
        close_series={
            "BTC-USDT": _sync_close(later_spike=later_spike),
            "ETH-USDT": _eth_breakout(),
        },
    )
    return workspace


def _sync_config(workspace, run_id: str) -> dict:
    cfg = runner_config(
        workspace,
        run_id,
        "sync",
        symbols=["BTC-USDT", "ETH-USDT"],
        initial_equity=10_000.0,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    )
    return cfg


def _first_eth_sizing_equity(workspace, run_id: str) -> float:
    seen: list[float] = []
    original = PositionSizer.calculate_position

    def wrapped(self, *args, **kwargs):
        symbol = kwargs.get("symbol") or (args[0] if args else None)
        if symbol == "ETH-USDT":
            seen.append(float(kwargs.get("account_equity") or args[4]))
        return original(self, *args, **kwargs)

    runner = write_runner(workspace, _sync_config(workspace, run_id))
    with patch.object(PositionSizer, "calculate_position", wrapped):
        runner.run()
    assert seen, "ETH never sized an entry"
    return seen[0]


def test_eth_entry_sizes_from_equity_without_future_btc_pnl(tmp_path):
    spike_ws = _sync_workspace(tmp_path, "spike", later_spike=True)
    flat_ws = _sync_workspace(tmp_path, "flat", later_spike=False)
    spike_equity = _first_eth_sizing_equity(spike_ws, "SPIKE")
    flat_equity = _first_eth_sizing_equity(flat_ws, "FLAT")
    assert spike_equity == pytest.approx(flat_equity, abs=1e-9)
    assert spike_equity < 50_000.0


def _cents(value: float) -> int:
    return int(round(float(value) * 100))


def test_equity_conservation_and_one_point_per_timestamp(tmp_path):
    workspace = _sync_workspace(tmp_path, "cons", later_spike=True)
    runner = write_runner(workspace, _sync_config(workspace, "CONS"))
    results = runner.run()

    initial = float(results["initial_equity"])
    final = float(results["final_equity"])
    fills = results["fills"]
    open_fills: dict[str, object] = {}
    realized = 0.0
    fees = 0.0
    funding = 0.0
    for fill in fills:
        fees += float(fill.fees) + float(fill.slippage)
        if fill.is_entry:
            open_fills[fill.symbol] = fill
            continue
        entry = open_fills.pop(fill.symbol, None)
        if entry is None:
            continue
        direction = 1.0 if entry.side == "LONG" else -1.0
        realized += (
            (float(fill.price) - float(entry.price))
            * float(entry.size)
            * float(entry.contract_size)
            * direction
        )
        funding += float(getattr(fill, "funding", 0.0) or 0.0)
    decisions = results["decisions"]
    unrealized = 0.0
    for symbol, entry in open_fills.items():
        symbol_rows = decisions[decisions["symbol"] == symbol]
        last_close = float(symbol_rows.iloc[-1]["close"]) if not symbol_rows.empty else float(entry.price)
        direction = 1.0 if entry.side == "LONG" else -1.0
        unrealized += (
            (last_close - float(entry.price))
            * float(entry.size)
            * float(entry.contract_size)
            * direction
        )
    expected = initial + realized - fees - funding + unrealized
    assert _cents(final) == _cents(expected)

    curve = pd.DataFrame(results["equity_curve"])
    assert not curve.empty
    times = pd.to_datetime(curve["bar_time"], utc=True)
    assert times.nunique() == len(times)
    assert "symbol" not in curve.columns or curve.groupby("bar_time").size().max() == 1

    ppy = periods_per_year("4hour")
    assert ppy == 2190
    sharpe = annualized_sharpe_from_equity(curve["equity"], ppy)
    rets = curve["equity"].pct_change().dropna()
    if len(rets) > 1 and float(rets.std(ddof=1)) != 0.0:
        assert sharpe == pytest.approx(
            float(rets.mean() / rets.std(ddof=1) * (ppy ** 0.5))
        )
    assert results["sharpe"] == pytest.approx(sharpe)
