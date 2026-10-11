"""Round-3 data review: non_comparable stops, frozen/spike fills, funding gate."""

from __future__ import annotations

from datetime import UTC, datetime

import numpy as np
import pandas as pd
import pytest

from htx_trend.research.engine.backtest.sim_broker import CostScenario, SimBroker
from htx_trend.research.engine.interfaces import Order
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_batch_manifest,
    write_runner,
)
from tests.research.test_bad_tick_kinds import _stop_runner


def _mark_bar(workspace, idx, **columns):
    path = workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet"
    df = pd.read_parquet(path)
    for key, value in columns.items():
        if key not in df.columns:
            df[key] = False if isinstance(value, bool) else ""
        df.loc[idx, key] = value
    df.to_parquet(path, index=False)
    write_batch_manifest(workspace, "ohlc")
    return df


def _ohlc_workspace(tmp_path, name="ws", n=230, mutate=None):
    workspace = tmp_path / name
    workspace.mkdir()
    close = np.full(n, 100.0)
    high = close + 1.0
    low = close - 1.0
    open_ = close.copy()
    close[210] = 110.0
    high[210] = 111.0
    low[210] = 109.0
    open_[210] = 100.0
    open_[211] = 110.0
    close[211] = 110.0
    high[211] = 111.0
    low[211] = 109.0
    close[212] = 110.0
    high[212] = 111.0
    low[212] = 50.0
    open_[212] = 110.0
    if mutate:
        mutate(open_, high, low, close)
    create_test_dataset(
        workspace,
        "ohlc",
        n_bars=n,
        train_end_idx=n,
        val_end_idx=n,
        symbols=["BTC-USDT"],
        specs={"BTC-USDT": DEFAULT_SPECS["BTC-USDT"]},
        ohlc={"BTC-USDT": {"open": open_, "high": high, "low": low, "close": close}},
    )
    return workspace


def test_non_comparable_still_checks_stop_while_in_position(tmp_path):
    workspace = _ohlc_workspace(tmp_path, "nc")
    _mark_bar(workspace, 212, non_comparable=True)
    results = _stop_runner(workspace, "NCSTOP").run()
    decisions = results["decisions"]
    target = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    stamp = pd.Timestamp(target.loc[212, "bar_time"])
    exits = decisions[decisions["action"] == "EXIT"]
    assert not exits.empty
    assert stamp in set(pd.to_datetime(exits["bar_time"], utc=True))
    enters = decisions[decisions["action"] == "ENTER"]
    if not enters.empty:
        assert stamp not in set(pd.to_datetime(enters["bar_time"], utc=True))


def test_incomplete_bar_still_skips_stop_check(tmp_path):
    workspace = _ohlc_workspace(tmp_path, "inc")
    _mark_bar(workspace, 212, incomplete=True, non_comparable=True)
    results = _stop_runner(workspace, "INCSTOP").run()
    decisions = results["decisions"]
    target = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    stamp = pd.Timestamp(target.loc[212, "bar_time"])
    exits = decisions[decisions["action"] == "EXIT"]
    if not exits.empty:
        assert stamp not in set(pd.to_datetime(exits["bar_time"], utc=True))


def test_frozen_bar_does_not_fill_pending_orders(tmp_path):
    workspace = _ohlc_workspace(tmp_path, "frzfill")
    _mark_bar(workspace, 211, bad_tick=True, bad_tick_kind="frozen")
    results = _stop_runner(workspace, "FRZFILL").run()
    target = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    frozen = pd.Timestamp(target.loc[211, "bar_time"])
    next_bar = pd.Timestamp(target.loc[212, "bar_time"])
    fills = results["fills"]
    fill_times = [pd.Timestamp(fill.time) for fill in fills]
    assert frozen not in fill_times
    assert any(stamp == next_bar for stamp in fill_times)


def test_spike_allows_stop_fill_but_not_entry_fill():
    broker = SimBroker(10_000.0, CostScenario.ZERO, contract_sizes={"BTC-USDT": 0.001})
    t0 = datetime(2024, 1, 1, tzinfo=UTC)
    t1 = datetime(2024, 1, 1, 4, tzinfo=UTC)
    t2 = datetime(2024, 1, 1, 8, tzinfo=UTC)
    broker.submit_order(Order(symbol="BTC-USDT", side="LONG", size=1, order_type="MARKET"))
    broker.process_next_bar(
        "BTC-USDT",
        {"open": 100.0, "high": 101.0, "low": 99.0, "close": 100.0, "bar_time": t0},
    )
    assert len(broker.fills) == 1
    assert broker.fills[0].is_entry
    broker.submit_order(
        Order(
            symbol="BTC-USDT",
            side="SHORT",
            size=1,
            order_type="MARKET",
            reduce_only=True,
            is_stop=True,
            stop_price=90.0,
        )
    )
    broker.submit_order(Order(symbol="BTC-USDT", side="LONG", size=1, order_type="MARKET"))
    broker.process_next_bar(
        "BTC-USDT",
        {"open": 80.0, "high": 81.0, "low": 50.0, "close": 80.0, "bar_time": t1},
        allow_entry_fills=False,
        allow_exit_fills=True,
    )
    assert all(fill.time != t1 or not fill.is_entry for fill in broker.fills)
    assert any((not fill.is_entry) and fill.time == t1 for fill in broker.fills)
    assert len(broker.pending_orders) == 1
    assert broker.pending_orders[0][0].is_stop is False
    broker.process_next_bar(
        "BTC-USDT",
        {"open": 110.0, "high": 111.0, "low": 109.0, "close": 110.0, "bar_time": t2},
    )
    assert any(fill.is_entry and fill.time == t2 for fill in broker.fills)


def test_frozen_carries_pending_entry_and_exit():
    broker = SimBroker(10_000.0, CostScenario.ZERO, contract_sizes={"BTC-USDT": 0.001})
    t0 = datetime(2024, 1, 1, tzinfo=UTC)
    t1 = datetime(2024, 1, 1, 4, tzinfo=UTC)
    broker.submit_order(Order(symbol="BTC-USDT", side="LONG", size=1, order_type="MARKET"))
    broker.submit_order(
        Order(
            symbol="BTC-USDT",
            side="SHORT",
            size=1,
            order_type="MARKET",
            reduce_only=True,
            is_stop=True,
            stop_price=90.0,
        )
    )
    acks = broker.process_next_bar(
        "BTC-USDT",
        {"open": 100.0, "high": 101.0, "low": 50.0, "close": 100.0, "bar_time": t0},
        allow_entry_fills=False,
        allow_exit_fills=False,
    )
    assert acks == []
    assert broker.fills == []
    assert len(broker.pending_orders) == 2
    broker.process_next_bar(
        "BTC-USDT",
        {"open": 100.0, "high": 101.0, "low": 99.0, "close": 100.0, "bar_time": t1},
    )
    assert broker.fills
    assert all(fill.time == t1 for fill in broker.fills)


def test_missing_bad_tick_kind_raises():
    from htx_trend.research.data.quality import normalize_bad_tick_kind

    with pytest.raises(ValueError, match="kind"):
        normalize_bad_tick_kind(None)
    with pytest.raises(ValueError, match="kind"):
        normalize_bad_tick_kind("")


def test_load_bad_ticks_requires_kind(tmp_path):
    from htx_trend.research.data.quality import load_bad_ticks

    yaml_path = tmp_path / "bad_ticks.yaml"
    yaml_path.write_text(
        "\n".join(
            [
                "version: 1",
                "ticks:",
                "  - symbol: BTC-USDT",
                '    bar_time: "2021-02-19T12:00:00Z"',
                "    confirmed: true",
                "    reason: missing kind",
            ]
        )
    )
    with pytest.raises(ValueError, match="kind"):
        load_bad_ticks(yaml_path)


def test_missing_funding_is_hard_error_by_default(tmp_path):
    create_test_dataset(tmp_path, "nofund", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(tmp_path, "NOFUND", "nofund", symbols=["BTC-USDT"])
    cfg.pop("allow_missing_funding", None)
    runner = write_runner(tmp_path, cfg)
    with pytest.raises(ValueError, match="funding"):
        runner.run()


def test_allow_missing_funding_flags_report(tmp_path):
    create_test_dataset(tmp_path, "missfund", n_bars=48, symbols=["BTC-USDT"])
    runner = write_runner(
        tmp_path,
        runner_config(
            tmp_path,
            "MISSFUND",
            "missfund",
            symbols=["BTC-USDT"],
            allow_missing_funding=True,
        ),
    )
    results = runner.run()
    assert results["funding_missing"] == ["BTC-USDT"]
    report = (tmp_path / "runs" / "MISSFUND" / "report.md").read_text()
    assert "funding_missing" in report
    assert "BTC-USDT" in report
