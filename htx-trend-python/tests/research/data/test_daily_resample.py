"""Daily resample rules: completeness, no mix, configurable cut, no unfinished day."""

from __future__ import annotations

from datetime import datetime, timezone

import pandas as pd

from htx_trend.research.features.daily_resample import (
    DAILY_RESAMPLE_FEATURE,
    daily_open_for,
    daily_resample_manifest_fields,
    detect_daily_open_hour,
    resample_4h_to_daily,
)


def _bars(times, source="htx"):
    rows = []
    for i, ts in enumerate(times):
        close = 100 + i
        rows.append(
            {
                "bar_time": ts,
                "open": close,
                "high": close + 1,
                "low": close - 1,
                "close": close,
                "volume": 10.0,
                "source": source,
            }
        )
    return pd.DataFrame(rows)


def test_incomplete_day_excluded_no_fill_forward():
    times = pd.date_range("2024-01-02", periods=4, freq="4h", tz="UTC")
    result = resample_4h_to_daily(
        _bars(times),
        daily_cut="UTC00",
        now=datetime(2024, 1, 10, tzinfo=timezone.utc),
    )
    assert result.signal_bars.empty
    assert len(result.excluded) == 1
    assert bool(result.excluded.iloc[0]["incomplete"]) is True
    assert result.stats["fill_forward"] is False
    assert result.stats["incomplete_days"] == 1


def test_exactly_six_closed_4h_bars_make_a_signal_day():
    times = pd.date_range("2024-01-02 00:00", periods=6, freq="4h", tz="UTC")
    result = resample_4h_to_daily(
        _bars(times),
        daily_cut="UTC00",
        now=datetime(2024, 1, 10, tzinfo=timezone.utc),
    )
    assert len(result.signal_bars) == 1
    assert result.signal_bars.iloc[0]["volume"] == 60.0
    assert result.signal_bars.iloc[0]["daily_cut"] == "UTC00"


def test_unfinished_current_day_never_enters_frozen():
    now = datetime(2024, 1, 2, 10, tzinfo=timezone.utc)
    times = pd.date_range("2024-01-02 00:00", periods=2, freq="4h", tz="UTC")
    result = resample_4h_to_daily(_bars(times), daily_cut="UTC00", now=now)
    assert result.signal_bars.empty
    assert result.stats["unfinished_days_excluded"] == 1


def test_mixed_source_day_is_non_comparable_volume_not_summed():
    htx = pd.date_range("2024-01-02 00:00", periods=3, freq="4h", tz="UTC")
    bn = pd.date_range("2024-01-02 12:00", periods=3, freq="4h", tz="UTC")
    df = pd.concat([_bars(htx, "htx"), _bars(bn, "binance")], ignore_index=True)
    result = resample_4h_to_daily(
        df, daily_cut="UTC00", now=datetime(2024, 1, 10, tzinfo=timezone.utc)
    )
    assert len(result.signal_bars) == 1
    row = result.signal_bars.iloc[0]
    assert bool(row["non_comparable"]) is True
    assert bool(row["mixed_source"]) is True
    assert pd.isna(row["volume"])


def test_daily_cut_utc16_groups_from_16_00():
    ts = pd.Timestamp("2024-01-02 00:00", tz="UTC")
    assert daily_open_for(ts, "UTC16") == pd.Timestamp("2024-01-01 16:00", tz="UTC")
    times = [
        pd.Timestamp("2024-01-01 16:00", tz="UTC"),
        pd.Timestamp("2024-01-01 20:00", tz="UTC"),
        pd.Timestamp("2024-01-02 00:00", tz="UTC"),
        pd.Timestamp("2024-01-02 04:00", tz="UTC"),
        pd.Timestamp("2024-01-02 08:00", tz="UTC"),
        pd.Timestamp("2024-01-02 12:00", tz="UTC"),
    ]
    result = resample_4h_to_daily(
        _bars(times),
        daily_cut="UTC16",
        now=datetime(2024, 1, 10, tzinfo=timezone.utc),
    )
    assert len(result.signal_bars) == 1
    assert result.signal_bars.iloc[0]["bar_time"] == pd.Timestamp("2024-01-01 16:00", tz="UTC")
    assert result.signal_bars.iloc[0]["daily_cut"] == "UTC16"


def test_detect_htx_native_1d_open_hour_is_16():
    times = pd.date_range("2024-01-01 16:00", periods=5, freq="1D", tz="UTC")
    assert detect_daily_open_hour(pd.Series(times)) == 16


def test_manifest_records_daily_cut_and_versioned_feature():
    fields = daily_resample_manifest_fields(daily_cut="UTC00", htx_detected_daily_open_hour=16)
    assert fields["daily_cut"] == "UTC00"
    assert fields["daily_resample"]["name"] == "daily_resample"
    assert fields["daily_resample"]["version"] == DAILY_RESAMPLE_FEATURE["version"]
    assert fields["htx_native_1d_used_for_signals"] is False
    assert fields["htx_detected_daily_open_hour"] == 16


def _write_raw_klines(workspace, contract, interval, times, source="htx"):
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    df = _bars(times, source=source)
    suffix = "binance" if source == "binance" else "htx"
    symbol = contract.replace("-", "") if source == "binance" else contract
    interval_name = {"4hour": "4h", "1day": "1d"}.get(interval, interval) if source == "binance" else interval
    df.to_parquet(raw / f"{symbol}_{interval_name}_{suffix}.parquet", index=False)


def test_freeze_1day_writes_daily_cut_and_resample_to_manifest(tmp_path):
    from argparse import Namespace

    from htx_trend.research.cli import cmd_freeze
    from htx_trend.research.data.manifest import DatasetManifest

    times = list(pd.date_range("2024-01-02", periods=18, freq="4h", tz="UTC"))
    _write_raw_klines(tmp_path, "BTC-USDT", "4hour", times, "htx")
    _write_raw_klines(
        tmp_path,
        "BTC-USDT",
        "1day",
        [pd.Timestamp("2024-01-02 00:00", tz="UTC")],
        "htx",
    )
    stitch_yaml = tmp_path / "stitch_points.yaml"
    stitch_yaml.write_text("version: 1\nBTC-USDT: none\n")
    cmd_freeze(
        Namespace(
            workspace=str(tmp_path),
            contracts="BTC-USDT",
            timeframe="1day",
            train_ratio=0.6,
            val_ratio=0.2,
            backfill_binance=False,
            overlap_threshold=None,
            daily_cut="UTC00",
            cutoff="2026-10-12T00:00:00Z",
            validation_start="2024-01-03T00:00:00Z",
            test_start="2024-01-04T00:00:00Z",
            split_from_manifest=None,
            stitch_point=None,
            stitch_config=str(stitch_yaml),
            non_comparable_window=30,
            bad_ticks=None,
        )
    )
    manifests = list((tmp_path / "data" / "datasets" / "manifests").glob("*.json"))
    assert len(manifests) == 1
    batch = DatasetManifest.load(manifests[0])
    assert batch.metadata["daily_cut"] == "UTC00"
    assert batch.metadata["daily_resample"]["name"] == "daily_resample"
    assert batch.metadata["daily_resample"]["version"] == DAILY_RESAMPLE_FEATURE["version"]
    assert batch.metadata["daily_resample"]["daily_cut"] == "UTC00"
    assert batch.metadata["htx_native_1d_used_for_signals"] is False


def test_freeze_1day_excludes_incomplete_and_does_not_cross_source_resample(tmp_path):
    from argparse import Namespace

    from htx_trend.research.cli import cmd_freeze

    incomplete = pd.date_range("2024-01-03 00:00", periods=4, freq="4h", tz="UTC")
    htx_mix = pd.date_range("2024-01-02 00:00", periods=3, freq="4h", tz="UTC")
    bn_mix = pd.date_range("2024-01-02 12:00", periods=3, freq="4h", tz="UTC")
    _write_raw_klines(
        tmp_path, "BTC-USDT", "4hour", list(htx_mix) + list(incomplete), "htx"
    )
    _write_raw_klines(tmp_path, "BTC-USDT", "4hour", list(bn_mix), "binance")
    _write_raw_klines(
        tmp_path,
        "BTC-USDT",
        "1day",
        [pd.Timestamp("2024-01-01 16:00", tz="UTC")],
        "htx",
    )
    stitch_yaml = tmp_path / "stitch_points.yaml"
    stitch_yaml.write_text("version: 1\nBTC-USDT: 2024-01-02T12:00:00Z\n")
    cmd_freeze(
        Namespace(
            workspace=str(tmp_path),
            contracts="BTC-USDT",
            timeframe="1day",
            train_ratio=0.6,
            val_ratio=0.2,
            backfill_binance=True,
            overlap_threshold=0.003,
            daily_cut="UTC00",
            cutoff="2026-10-12T00:00:00Z",
            validation_start=None,
            test_start=None,
            split_from_manifest=None,
            stitch_point=None,
            stitch_config=str(stitch_yaml),
            non_comparable_window=30,
            bad_ticks=None,
        )
    )
    datasets_root = tmp_path / "data" / "datasets"
    datasets = [
        path
        for path in datasets_root.iterdir()
        if path.is_dir() and path.name != "manifests"
    ] if datasets_root.exists() else []
    assert datasets == []
    assert not (tmp_path / "data" / "datasets" / "manifests").exists()
