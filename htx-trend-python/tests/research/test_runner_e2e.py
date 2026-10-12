"""E2E runner-path tests for reviewer P0/P1 items."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from unittest.mock import patch

import numpy as np
import pandas as pd
import pytest

from htx_trend.research.risk.liquidation_check import AccountLiquidationChecker
from htx_trend.research.conditions.c02_atr_stop import C02ATRStop
from htx_trend.research.engine.contract_value import periods_per_year
from htx_trend.research.features.indicators import FeatureCalculator
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_runner,
)


def _breakout_close(n: int = 260) -> np.ndarray:
    close = np.full(n, 100.0)
    close[210:215] = 130.0
    close[215:218] = 40.0
    close[218:] = 20.0
    return close


def _breakout_workspace(tmp_path, specs=None):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    close = _breakout_close()
    create_test_dataset(
        workspace,
        "breakout",
        n_bars=260,
        train_end_idx=260,
        val_end_idx=260,
        symbols=["BTC-USDT"],
        specs=specs or {"BTC-USDT": DEFAULT_SPECS["BTC-USDT"]},
        close_series={"BTC-USDT": close},
    )
    return workspace


def _breakout_config(workspace, run_id="E2E1", **overrides):
    cfg = runner_config(
        workspace,
        run_id,
        "breakout",
        symbols=["BTC-USDT"],
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
    cfg.update(overrides)
    return cfg


def test_min_order_size_over_budget_skips_entry(tmp_path):
    specs = {
        "BTC-USDT": {**DEFAULT_SPECS["BTC-USDT"], "min_order_size": 1_000_000},
    }
    workspace = _breakout_workspace(tmp_path, specs)
    runner = write_runner(workspace, _breakout_config(workspace))
    results = runner.run()
    decisions = results["decisions"]
    assert not decisions.empty
    skips = decisions[decisions["action"] == "SKIP"]
    assert not skips.empty
    assert skips["skip_reason"].astype(str).str.contains("Min order size").any()
    assert (decisions["action"] == "ENTER").sum() == 0


def test_manifest_hash_required_no_unverified_load(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(workspace)
    del cfg["manifest_hash"]
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="manifest_hash"):
        runner.run()


def test_cost_scenario_required(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(workspace)
    del cfg["cost_scenario"]
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="cost_scenario"):
        runner.run()


def test_unknown_condition_c04_raises(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(workspace)
    cfg["conditions"]["C04"] = {"enabled": True, "periods": [20]}
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="C04"):
        runner.run()


def test_account_liquidation_checker_used_by_default(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    called = {"n": 0}
    original = AccountLiquidationChecker.check_account_liquidation_risk

    def wrapped(self, *args, **kwargs):
        called["n"] += 1
        return original(self, *args, **kwargs)

    runner = write_runner(workspace, _breakout_config(workspace))
    with patch.object(AccountLiquidationChecker, "check_account_liquidation_risk", wrapped):
        runner.run()
    assert called["n"] >= 1


def test_c02_independent_stops_for_consecutive_opposite_trades(tmp_path):
    specs = {
        "BTC-USDT": {**DEFAULT_SPECS["BTC-USDT"], "contract_size": 1.0, "min_order_size": 1},
    }
    workspace = _breakout_workspace(tmp_path, specs)
    runner = write_runner(
        workspace, _breakout_config(workspace, "C02ISO", initial_equity=1_000_000.0)
    )
    results = runner.run()
    decisions = results["decisions"]
    enters = decisions[decisions["action"] == "ENTER"]
    assert len(enters) >= 2
    sides = enters["side"].tolist()
    assert "LONG" in sides and "SHORT" in sides
    ids = enters["position_id"].tolist()
    assert len(set(ids)) == len(ids)
    stops = enters["stop_loss"].tolist()
    assert stops[0] != stops[1]
    first_pid = enters.iloc[0]["position_id"]
    second_pid = enters.iloc[1]["position_id"]
    exits = decisions[decisions["action"] == "EXIT"]
    second_exits = exits[exits["position_id"] == second_pid]
    if not second_exits.empty:
        assert second_exits.iloc[0]["position_id"] != first_pid
        assert second_exits.iloc[0]["stop_loss"] != enters.iloc[0]["stop_loss"]
    else:
        owned = decisions[decisions["position_id"] == second_pid]
        assert not owned.empty
        assert owned.iloc[-1]["stop_loss"] != enters.iloc[0]["stop_loss"]


def test_smoke_not_counted_and_labeled(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    today = datetime.now(timezone.utc).strftime("%Y%m%d")
    run_id = f"SMOKE-{today}-01"
    cfg = _breakout_config(
        workspace, run_id, run_type="SMOKE", split="train", smoke_bar_window=80
    )
    runner = write_runner(workspace, cfg)
    runner.run()
    assert runner.ledger.count_trials(run_id) == 0
    assert runner.ledger.get_all_trials() == []
    report = (workspace / "runs" / run_id / "report.md").read_text()
    assert "SMOKE" in report


def test_smoke_refuses_test_split(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    cfg = _breakout_config(
        workspace,
        "SMOKE-20261011-02",
        run_type="SMOKE",
        split="test",
    )
    with pytest.raises(ValueError, match="train"):
        write_runner(workspace, cfg)


def test_trial_export_includes_sharpe_trades_and_return_series(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    runner = write_runner(workspace, _breakout_config(workspace, "DSREXP"))
    runner.run()
    exported = runner.ledger.export_trials(fmt="json")
    assert "final_sharpe" in exported
    assert "n_trades" in exported
    assert "return_series_path" in exported
    rows = json.loads(exported)
    assert rows
    sharpes = [row["final_sharpe"] for row in rows]
    assert any(value is not None for value in sharpes)
    numeric = [float(value) for value in sharpes if value is not None]
    assert numeric
    series = workspace / "runs" / "DSREXP" / "return_series.parquet"
    assert series.exists()
    returns = pd.read_parquet(series)
    if not returns.empty:
        assert "return" in returns.columns
        assert returns["return"].notna().any()


def _ohlc_workspace(tmp_path, n=230, mutate=None):
    workspace = tmp_path / "ws"
    workspace.mkdir(parents=True)
    close = np.full(n, 100.0)
    high = close + 1.0
    low = close - 1.0
    open_ = close.copy()
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


def _entry_workspace(tmp_path, run_id, mutate):
    workspace = _ohlc_workspace(tmp_path, mutate=mutate)
    runner = write_runner(
        workspace,
        runner_config(
            workspace,
            run_id,
            "ohlc",
            symbols=["BTC-USDT"],
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
        ),
    )
    results = runner.run()
    df = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    return workspace, results, df


def test_stop_hit_on_entry_bar(tmp_path):
    def mutate(open_, high, low, close):
        close[210] = 110.0
        high[210] = 111.0
        low[210] = 109.0
        open_[210] = 100.0
        open_[211] = 110.0
        close[211] = 100.0
        high[211] = 111.0
        low[211] = 50.0

    _, results, df = _entry_workspace(tmp_path, "ENTRYSTOP", mutate)
    decisions = results["decisions"]
    enters = decisions[decisions["action"] == "ENTER"]
    exits = decisions[decisions["action"] == "EXIT"]
    fills = [fill for fill in results["fills"] if fill.is_entry]
    assert not enters.empty
    assert not exits.empty
    assert fills
    signal_idx = df.index[pd.to_datetime(df["bar_time"]) == pd.to_datetime(enters.iloc[0]["bar_time"])][0]
    next_bar = df.iloc[signal_idx + 1]
    slip = 0.1 * 1
    expected_fill = float(next_bar["open"]) + slip
    truncated = FeatureCalculator.atr(df.iloc[: signal_idx + 1], 14).iloc[-1]
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    expected_stop = c02.calculate_stop_loss(expected_fill, float(truncated), "LONG")
    exit_row = exits.iloc[0]
    assert bool(exit_row["entry_bar_stop"])
    assert pd.to_datetime(exit_row["bar_time"]) == pd.to_datetime(next_bar["bar_time"])
    assert float(fills[0].price) == pytest.approx(expected_fill)
    assert float(exit_row["entry_price"]) == pytest.approx(expected_fill)
    assert float(exit_row["stop_loss"]) == pytest.approx(expected_stop)
    assert float(next_bar["low"]) <= float(exit_row["stop_loss"])
    assert float(df.iloc[signal_idx]["low"]) > float(exit_row["stop_loss"])


def test_entry_bar_stop_ignores_signal_bar_extreme(tmp_path):
    def mutate(open_, high, low, close):
        close[210] = 110.0
        high[210] = 111.0
        low[210] = 50.0
        open_[210] = 100.0
        open_[211] = 120.0
        close[211] = 120.0
        high[211] = 121.0
        low[211] = 119.0

    _, results, df = _entry_workspace(tmp_path, "NOSIGSTOP", mutate)
    decisions = results["decisions"]
    enters = decisions[decisions["action"] == "ENTER"]
    confirms = decisions[decisions["action"] == "CONFIRM_ENTRY"]
    exits = decisions[decisions["action"] == "EXIT"]
    assert not enters.empty
    assert not confirms.empty
    assert "entry_bar_stop" not in exits.columns or not bool(exits.get("entry_bar_stop").fillna(False).any())
    signal_idx = df.index[pd.to_datetime(df["bar_time"]) == pd.to_datetime(enters.iloc[0]["bar_time"])][0]
    next_bar = df.iloc[signal_idx + 1]
    fill = [item for item in results["fills"] if item.is_entry][0]
    stop = float(confirms.iloc[0]["stop_loss"])
    assert float(df.iloc[signal_idx]["low"]) <= stop
    assert float(next_bar["low"]) > stop
    assert float(fill.price) == pytest.approx(float(next_bar["open"]) + 0.1)
    assert float(confirms.iloc[0]["entry_price"]) == pytest.approx(float(fill.price))


def _confirm_entry(workspace, run_id, next_close):
    def mutate(open_, high, low, close):
        close[210] = 110.0
        high[210] = 111.0
        low[210] = 109.0
        open_[210] = 100.0
        open_[211] = 120.0
        close[211] = next_close
        high[211] = max(121.0, next_close)
        low[211] = 119.0

    workspace = _ohlc_workspace(workspace, mutate=mutate)
    runner = write_runner(
        workspace,
        runner_config(
            workspace,
            run_id,
            "ohlc",
            symbols=["BTC-USDT"],
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
        ),
    )
    results = runner.run()
    decisions = results["decisions"]
    enters = decisions[decisions["action"] == "ENTER"]
    confirms = decisions[decisions["action"] == "CONFIRM_ENTRY"]
    assert not enters.empty
    assert not confirms.empty
    fills = [fill for fill in results["fills"] if fill.is_entry]
    return workspace, enters.iloc[0], confirms.iloc[0], fills[0]


def test_signal_at_bar_t_close_fills_at_next_open_plus_slippage(tmp_path):
    workspace, enter, confirm, fill = _confirm_entry(tmp_path / "fill", "FILLNEXT", 120.0)
    parquet = workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet"
    df = pd.read_parquet(parquet)
    signal_idx = df.index[pd.to_datetime(df["bar_time"]) == pd.to_datetime(enter["bar_time"])][0]
    next_bar = df.iloc[signal_idx + 1]
    slip = 0.1 * 1
    expected_fill = float(next_bar["open"]) + slip
    channel = float(df["high"].iloc[signal_idx - 5 : signal_idx].max())
    assert float(fill.price) == pytest.approx(expected_fill)
    assert float(confirm["entry_price"]) == pytest.approx(expected_fill)
    assert pd.to_datetime(fill.time) == pd.to_datetime(next_bar["bar_time"])
    assert float(confirm["entry_price"]) != pytest.approx(float(enter["close"]))
    assert float(confirm["entry_price"]) != pytest.approx(float(df.iloc[signal_idx]["close"]))
    assert float(confirm["entry_price"]) != pytest.approx(channel)
    assert float(confirm["entry_price"]) != pytest.approx(float(next_bar["close"]))
    breakout = float(enter["close"])
    assert float(confirm["entry_price"]) != pytest.approx(breakout)


def test_entry_stop_atr_uses_signal_bar_only_not_next_close(tmp_path):
    _, enter_a, confirm_a, fill_a = _confirm_entry(tmp_path / "atr_a", "ATRA", 120.0)
    _, enter_b, confirm_b, fill_b = _confirm_entry(tmp_path / "atr_b", "ATRB", 999.0)
    workspace_a = tmp_path / "atr_a" / "ws"
    df = pd.read_parquet(workspace_a / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    signal_idx = df.index[pd.to_datetime(df["bar_time"]) == pd.to_datetime(enter_a["bar_time"])][0]
    truncated = FeatureCalculator.atr(df.iloc[: signal_idx + 1], 14).iloc[-1]
    full_through_t = FeatureCalculator.atr(df, 14).iloc[signal_idx]
    assert truncated == pytest.approx(full_through_t)
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    expected_stop = c02.calculate_stop_loss(float(fill_a.price), float(truncated), "LONG")
    implied_atr = (float(confirm_a["entry_price"]) - float(confirm_a["stop_loss"])) / 2.0
    assert implied_atr == pytest.approx(float(truncated))
    assert float(confirm_a["stop_loss"]) == pytest.approx(expected_stop)
    assert float(confirm_a["entry_price"]) == pytest.approx(float(fill_a.price))
    assert float(confirm_b["stop_loss"]) == pytest.approx(float(confirm_a["stop_loss"]))
    assert float(confirm_b["entry_price"]) == pytest.approx(float(confirm_a["entry_price"]))
    next_close_atr = FeatureCalculator.atr(
        pd.concat(
            [
                df.iloc[: signal_idx + 1],
                df.iloc[signal_idx + 1 : signal_idx + 2].assign(close=999.0),
            ]
        ).reset_index(drop=True),
        14,
    ).iloc[signal_idx + 1]
    poisoned_stop = c02.calculate_stop_loss(float(fill_b.price), float(next_close_atr), "LONG")
    assert float(confirm_b["stop_loss"]) != pytest.approx(poisoned_stop)


def test_entry_fill_stop_from_actual_fill(tmp_path):
    def mutate(open_, high, low, close):
        close[210] = 110.0
        high[210] = 111.0
        low[210] = 109.0
        open_[210] = 100.0
        open_[211] = 120.0
        close[211] = 120.0
        high[211] = 121.0
        low[211] = 119.0

    workspace = _ohlc_workspace(tmp_path, mutate=mutate)
    runner = write_runner(
        workspace,
        runner_config(
            workspace,
            "FILLSTOP",
            "ohlc",
            symbols=["BTC-USDT"],
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
        ),
    )
    results = runner.run()
    decisions = results["decisions"]
    enters = decisions[decisions["action"] == "ENTER"]
    assert not enters.empty
    confirms = decisions[decisions["action"].isin(["CONFIRM_ENTRY", "EXIT"])]
    assert not confirms.empty
    row = confirms.iloc[0]
    assert float(row["entry_price"]) != float(enters.iloc[0]["close"])
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    atr = float(results["decisions"].iloc[0].get("c01_value") or 2.0)
    # Stop must be computed from the actual fill, not the signal close.
    fill_price = float(row["entry_price"])
    signal_close = float(enters.iloc[0]["close"])
    assert abs(fill_price - signal_close) > 1
    assert float(row["stop_loss"]) < fill_price
    signal_stop = c02.calculate_stop_loss(signal_close, 1.0, "LONG")
    assert float(row["stop_loss"]) != pytest.approx(signal_stop)


def test_smoke_limited_window_no_metrics_records_smoke_runs(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    today = datetime.now(timezone.utc).strftime("%Y%m%d")
    run_id = f"SMOKE-{today}-09"
    cfg = _breakout_config(
        workspace,
        run_id,
        run_type="SMOKE",
        split="train",
        smoke_bar_window=40,
    )
    runner = write_runner(workspace, cfg)
    results = runner.run()
    assert len(results["decisions"]) <= 40
    report = (workspace / "runs" / run_id / "report.md").read_text()
    assert "绩效指标" not in report
    assert "SMOKE" in report
    rows = runner.ledger.list_smoke_runs(run_id)
    assert len(rows) == 1
    assert rows[0]["bar_window"] == 40
    assert rows[0]["status"] == "completed"
    assert runner.ledger.get_all_trials() == []
    metrics_path = workspace / "runs" / run_id / "return_series.parquet"
    assert not metrics_path.exists()


def test_market_on_open_entry_charged_taker(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    runner = write_runner(
        workspace, _breakout_config(workspace, "TAKER", initial_equity=10_000.0)
    )
    results = runner.run()
    entries = [fill for fill in results["fills"] if fill.is_entry]
    assert entries
    for fill in entries:
        assert fill.fees == pytest.approx(fill.notional * 0.0006, rel=1e-6)


def test_sharpe_numeric_from_known_equity_curve():
    from htx_trend.research.runner import annualized_sharpe_from_equity

    equity = pd.Series([100.0, 110.0, 99.0, 108.9])
    ppy = periods_per_year("4hour")
    sharpe = annualized_sharpe_from_equity(equity, ppy)
    rets = equity.pct_change().dropna()
    assert list(rets.round(12)) == [0.1, -0.1, 0.1]
    assert ppy == 2190
    assert sharpe == pytest.approx(13.509, abs=0.001)


def test_sharpe_net_of_fees_and_annualized(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    runner = write_runner(
        workspace, _breakout_config(workspace, "SHARPE", initial_equity=10_000.0)
    )
    results = runner.run()
    series = pd.read_parquet(workspace / "runs" / "SHARPE" / "return_series.parquet")
    if series.empty:
        pytest.skip("fixture produced no closed trades")
    assert "fees" in series.columns
    assert "funding" in series.columns
    assert results["sharpe"] is not None
    curve = pd.DataFrame(results["equity_curve"])
    assert not curve.empty
    rets = curve["equity"].pct_change().dropna()
    std = float(rets.std(ddof=1)) if len(rets) > 1 else 0.0
    expected = (
        float(rets.mean() / std * (periods_per_year("4hour") ** 0.5)) if std > 0 else 0.0
    )
    assert results["sharpe"] == pytest.approx(expected)
    trade_std = float(series["return"].std(ddof=1)) if len(series) > 1 else 0.0
    if trade_std > 0:
        old = float(series["return"].mean() / trade_std * (periods_per_year("4hour") ** 0.5))
        if abs(old - expected) > 1e-9:
            assert results["sharpe"] != pytest.approx(old)
    assert periods_per_year("4hour") == 6 * 365
    assert periods_per_year("1day") == 365


def test_smoke_failure_still_records_smoke_runs(tmp_path):
    workspace = _breakout_workspace(tmp_path)
    (workspace / "data" / "raw" / "BTC-USDT_spec.json").unlink()
    today = datetime.now(timezone.utc).strftime("%Y%m%d")
    run_id = f"SMOKE-{today}-08"
    cfg = _breakout_config(
        workspace, run_id, run_type="SMOKE", split="train", smoke_bar_window=40
    )
    runner = write_runner(workspace, cfg)
    with pytest.raises(ValueError, match="spec"):
        runner.run()
    rows = runner.ledger.list_smoke_runs(run_id)
    assert rows
    assert rows[-1]["status"] == "failed"
