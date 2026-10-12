"""bad_tick kind: frozen vs spike for indicators, signals, and stops."""

from __future__ import annotations

import numpy as np
import pandas as pd

from htx_trend.research.data.quality import (
    DEFAULT_KIND_STOP_ON_BAR,
    stop_on_bar_for_kind,
)
from htx_trend.research.features.indicators import FeatureCalculator, compute_all_features
from htx_trend.research.runner import BacktestRunner
from tests.research.helpers import (
    DEFAULT_SPECS,
    create_test_dataset,
    runner_config,
    write_batch_manifest,
    write_runner,
)


def _ohlc_frame(n=50, spike_idx=42, spike_high=999.0):
    close = np.full(n, 100.0)
    high = close + 1.0
    low = close - 1.0
    high[spike_idx] = spike_high
    low[spike_idx] = 1.0
    df = pd.DataFrame(
        {
            "bar_time": pd.date_range("2024-01-01", periods=n, freq="4h", tz="UTC"),
            "open": close,
            "high": high,
            "low": low,
            "close": close,
            "volume": 1.0,
            "amount": 100.0,
            "bad_tick": False,
        }
    )
    df.loc[spike_idx, "bad_tick"] = True
    df.loc[spike_idx, "bad_tick_kind"] = "frozen"
    return df


def test_indicators_skip_bad_tick_and_extend_window():
    df = _ohlc_frame()
    out = compute_all_features(df)
    valid = df.loc[~df["bad_tick"]].copy()
    expected_high = FeatureCalculator.donchian_high(valid["high"], 5)
    next_valid = 43
    got = FeatureCalculator.donchian_high_excluding_bad_ticks(df["high"], 5, df["bad_tick"])
    assert got.iloc[next_valid] == expected_high.loc[next_valid]
    assert got.iloc[next_valid] == 101.0
    naive = FeatureCalculator.donchian_high(df["high"], 5)
    assert naive.iloc[next_valid] == 999.0
    assert got.iloc[next_valid] != naive.iloc[next_valid]
    valid_high42 = FeatureCalculator.donchian_high(valid["high"], 42)
    assert out.loc[~df["bad_tick"], "donchian_high_42"].isna().sum() == valid_high42.isna().sum()
    assert not pd.isna(got.loc[42])
    assert not pd.isna(out.loc[43, "atr14"])
    assert out.loc[43, "donchian_high_42"] == 101.0


def _delete_then_align(df: pd.DataFrame, compute):
    """Independent oracle: drop the bad bar, compute, reindex, ffill."""
    deleted = df.loc[~df["bad_tick"].astype(bool)]
    computed = compute(deleted)
    return computed.reindex(df.index).ffill()


def _replace_ohlc_with_prior(df: pd.DataFrame) -> pd.DataFrame:
    """Incorrect approach: keep the row but copy previous OHLC onto the bad bar."""
    replaced = df.copy()
    bad = replaced["bad_tick"].astype(bool)
    for col in ("open", "high", "low", "close"):
        replaced.loc[bad, col] = replaced[col].where(~bad).ffill().loc[bad]
    return replaced


def test_frozen_bad_tick_donchian168_atr14_match_deleted_series_not_replaced_ohlc():
    rng = np.random.default_rng(7)
    n = 240
    bad_idx = 190
    close = 100 + np.cumsum(rng.normal(0, 0.8, n))
    high = close + rng.uniform(0.4, 1.6, n)
    low = close - rng.uniform(0.4, 1.6, n)
    df = pd.DataFrame(
        {
            "bar_time": pd.date_range("2020-11-01", periods=n, freq="4h", tz="UTC"),
            "open": np.concatenate([[close[0]], close[:-1]]),
            "high": high,
            "low": low,
            "close": close,
            "volume": 1.0,
            "amount": close,
            "bad_tick": False,
        }
    )
    original_ohlc = df.loc[bad_idx, ["open", "high", "low", "close"]].copy()
    df.loc[bad_idx, ["open", "high", "low", "close"]] = [
        original_ohlc["open"],
        9_999.0,
        0.01,
        original_ohlc["close"],
    ]
    df.loc[bad_idx, "bad_tick"] = True
    df.loc[bad_idx, "bad_tick_kind"] = "frozen"
    before_ohlc = df.loc[bad_idx, ["open", "high", "low", "close"]].copy()

    pipeline = compute_all_features(df)
    after_ohlc = pipeline.loc[bad_idx, ["open", "high", "low", "close"]]
    pd.testing.assert_series_equal(after_ohlc, before_ohlc, check_names=False)
    assert pipeline.loc[bad_idx, "high"] == 9_999.0
    assert pipeline.loc[bad_idx, "low"] == 0.01

    expected_don_high = _delete_then_align(
        df, lambda valid: FeatureCalculator.donchian_high(valid["high"], 168)
    )
    expected_don_low = _delete_then_align(
        df, lambda valid: FeatureCalculator.donchian_low(valid["low"], 168)
    )
    expected_atr = _delete_then_align(df, lambda valid: FeatureCalculator.atr(valid, 14))

    window = list(range(bad_idx - 8, bad_idx + 9))
    pd.testing.assert_series_equal(
        pipeline["donchian_high_168"], expected_don_high, check_names=False
    )
    pd.testing.assert_series_equal(
        pipeline["donchian_low_168"], expected_don_low, check_names=False
    )
    pd.testing.assert_series_equal(pipeline["atr14"], expected_atr, check_names=False)
    pd.testing.assert_series_equal(
        pipeline.loc[window, "donchian_high_168"],
        expected_don_high.loc[window],
        check_names=False,
    )
    pd.testing.assert_series_equal(
        pipeline.loc[window, "atr14"], expected_atr.loc[window], check_names=False
    )

    replaced = _replace_ohlc_with_prior(df)
    assert (replaced.loc[bad_idx, ["open", "high", "low", "close"]] != before_ohlc).any()
    replaced_atr = FeatureCalculator.atr(replaced.drop(columns=["bad_tick"], errors="ignore"), 14)
    assert not replaced_atr.equals(pipeline["atr14"])
    assert replaced_atr.iloc[bad_idx + 1] != pipeline["atr14"].iloc[bad_idx + 1]


def test_stop_on_bar_defaults_and_override():
    assert DEFAULT_KIND_STOP_ON_BAR["frozen"] is False
    assert DEFAULT_KIND_STOP_ON_BAR["spike"] is True
    assert stop_on_bar_for_kind("frozen") is False
    assert stop_on_bar_for_kind("spike") is True
    assert stop_on_bar_for_kind("frozen", {"frozen": {"stop_on_bar": True}}) is True
    assert stop_on_bar_for_kind("spike", {"spike": {"stop_on_bar": False}}) is False


def _stop_workspace(tmp_path, kind: str, n=230):
    workspace = tmp_path / kind
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
    path = workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet"
    df = pd.read_parquet(path)
    df["bad_tick"] = False
    df["bad_tick_kind"] = ""
    df.loc[212, "bad_tick"] = True
    df.loc[212, "bad_tick_kind"] = kind
    df.to_parquet(path, index=False)
    write_batch_manifest(workspace, "ohlc")
    return workspace


def _stop_runner(workspace, run_id: str) -> BacktestRunner:
    return write_runner(
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
                    "enabled": True,
                    "atr_period": 14,
                    "atr_multiplier": 2.0,
                    "tick_size": 0.1,
                    "stop_slippage_ticks": 1,
                },
            },
        ),
    )


def test_position_held_across_frozen_bad_tick_does_not_stop(tmp_path):
    workspace = _stop_workspace(tmp_path, "frozen")
    results = _stop_runner(workspace, "FROZENSTOP").run()
    decisions = results["decisions"]
    bar_times = pd.to_datetime(decisions["bar_time"], utc=True)
    target = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    frozen_time = pd.Timestamp(target.loc[212, "bar_time"])
    on_bar = decisions[bar_times == frozen_time]
    exits = decisions[decisions["action"] == "EXIT"]
    assert on_bar.empty or not (on_bar["action"] == "EXIT").any()
    if not exits.empty:
        exit_times = pd.to_datetime(exits["bar_time"], utc=True)
        assert frozen_time not in set(exit_times)


def test_package_bad_ticks_yaml_has_eleven_classified_entries():
    from htx_trend.research.data.quality import default_quality_dir, load_bad_ticks

    ticks = load_bad_ticks(default_quality_dir() / "bad_ticks.yaml")
    confirmed = [tick for tick in ticks if tick.confirmed]
    assert len(confirmed) == 11
    kinds = {(tick.symbol, tick.bar_time.isoformat(), tick.kind) for tick in confirmed}
    assert ("BTC-USDT", "2021-02-19T12:00:00+00:00", "frozen") in kinds
    assert ("BTC-USDT", "2021-04-27T08:00:00+00:00", "frozen") in kinds
    assert ("ETH-USDT", "2021-09-16T08:00:00+00:00", "frozen") in kinds
    assert ("BTC-USDT", "2022-04-27T04:00:00+00:00", "spike") in kinds
    assert ("ETH-USDT", "2022-04-27T04:00:00+00:00", "spike") in kinds


def test_position_stops_on_spike_bad_tick(tmp_path):
    workspace = _stop_workspace(tmp_path, "spike")
    results = _stop_runner(workspace, "SPIKESTOP").run()
    decisions = results["decisions"]
    target = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    spike_time = pd.Timestamp(target.loc[212, "bar_time"])
    exits = decisions[decisions["action"] == "EXIT"]
    assert not exits.empty
    exit_times = pd.to_datetime(exits["bar_time"], utc=True)
    assert spike_time in set(exit_times)
