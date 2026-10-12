"""rc.10 freeze quality-flag merge: keep-upstream, recompute-or-raise, 61-bar 4h window."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timezone
from pathlib import Path

import pandas as pd
import pytest

from htx_trend.research.data.collector import stitch_funding_history
from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.data.quality import STITCH_WINDOW_BARS, load_bad_ticks
from htx_trend.research.data.stitcher import DataStitcher, mark_stitch_window
from htx_trend.research.features.daily_resample import resample_4h_to_daily

STITCH = pd.Timestamp("2020-11-01T00:00:00Z")
STITCH_ISO = "2020-11-01T00:00:00Z"
CUTOFF_2020 = "2020-12-15T00:00:00Z"
VAL_2020 = "2020-11-20T00:00:00Z"
TEST_2020 = "2020-12-01T00:00:00Z"


def _bars(
    start: str,
    n_bars: int,
    *,
    source: str = "htx",
    freq: str = "4h",
    stitch: pd.Timestamp | None = None,
) -> pd.DataFrame:
    times = pd.date_range(start, periods=n_bars, freq=freq, tz="UTC")
    close = 100 + pd.Series(range(n_bars), dtype=float)
    if stitch is None:
        sources = [source] * n_bars
    else:
        sources = ["binance" if ts < stitch else "htx" for ts in times]
    return pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "amount": 100.0,
            "source": sources,
        }
    )


def _written_manifest(tmp_path: Path, dataset_id: str) -> dict:
    return json.loads(
        (tmp_path / "data" / "datasets" / dataset_id / "manifest.json").read_text()
    )


def _freeze_4h(tmp_path: Path, data: dict[str, pd.DataFrame], extra=None, **kwargs):
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    meta = {
        "non_comparable_window_bars": 30,
        "stitch_points": {symbol: None for symbol in data},
    }
    if extra:
        meta.update(extra)
        if "stitch_points" not in extra:
            meta["stitch_points"] = {symbol: None for symbol in data}
    frozen = mgr.freeze_dataset(
        data,
        timeframe="4hour",
        data_cutoff_close_utc=kwargs.get("cutoff", CUTOFF_2020),
        validation_start_utc=kwargs.get("validation_start", VAL_2020),
        test_start_utc=kwargs.get("test_start", TEST_2020),
        extra_metadata=meta,
        bad_ticks=kwargs.get("bad_ticks"),
    )
    return mgr, frozen


def test_htx_only_4h_without_stitch_points_has_zero_non_comparable(tmp_path):
    df = _bars("2020-09-01", 110 * 6, source="htx")
    _, frozen = _freeze_4h(tmp_path, {"BTC-USDT": df})
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    assert int(loaded["non_comparable"].sum()) == 0


def test_stitched_4h_marks_exactly_61_non_comparable_bars_per_symbol(tmp_path):
    data = {
        "BTC-USDT": _bars("2020-09-01", 110 * 6, stitch=STITCH),
        "ETH-USDT": _bars("2020-09-01", 110 * 6, stitch=STITCH),
    }
    extra = {
        "stitch_points": {
            "BTC-USDT": STITCH_ISO,
            "ETH-USDT": STITCH_ISO,
        }
    }
    _, frozen = _freeze_4h(tmp_path, data, extra=extra)
    for symbol in ("BTC-USDT", "ETH-USDT"):
        loaded = pd.read_parquet(
            tmp_path / "data" / "datasets" / frozen.dataset_id / f"{symbol}.parquet"
        )
        expected = mark_stitch_window(
            loaded[["bar_time"]].copy(), STITCH.to_pydatetime().replace(tzinfo=UTC), 30
        )
        assert int(loaded["non_comparable"].sum()) == 61
        assert int(expected["non_comparable"].sum()) == 61
        assert (loaded["non_comparable"].to_numpy() == expected["non_comparable"].to_numpy()).all()


def test_freeze_manifest_records_non_comparable_window_bars_in_hash(tmp_path):
    df = _bars("2020-09-01", 110 * 6, source="htx")
    _, frozen = _freeze_4h(tmp_path, {"BTC-USDT": df})
    written = _written_manifest(tmp_path, frozen.dataset_id)
    assert written["non_comparable_window_bars"] == STITCH_WINDOW_BARS == 30
    hashed = {
        "symbols": written["symbols"],
        "timeframe": written["timeframe"],
        "start_date": written["start_date"],
        "end_date": written["end_date"],
        "total_bars": written["total_bars"],
        "data_cutoff_close_utc": written["data_cutoff_close_utc"],
        "split": written["split"],
        "non_comparable_window_bars": 30,
        "stitch_points": written["stitch_points"],
    }
    digest = hashlib.sha256(json.dumps(hashed, sort_keys=True).encode()).hexdigest()[:16]
    assert frozen.manifest_hash == digest
    hashed["non_comparable_window_bars"] = 31
    other = hashlib.sha256(json.dumps(hashed, sort_keys=True).encode()).hexdigest()[:16]
    assert other != frozen.manifest_hash


def test_freeze_raises_if_non_comparable_window_is_not_30(tmp_path):
    df = _bars("2020-09-01", 110 * 6, source="htx")
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="non_comparable_window_bars"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc=CUTOFF_2020,
            validation_start_utc=VAL_2020,
            test_start_utc=TEST_2020,
            extra_metadata={"non_comparable_window_bars": 2},
        )


def test_upstream_flag_mismatch_raises(tmp_path):
    df = _bars("2020-09-01", 110 * 6, stitch=STITCH)
    df["incomplete"] = False
    df["non_comparable"] = False
    df["bad_tick"] = False
    df["external"] = False
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="does not match recomputation"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc=CUTOFF_2020,
            validation_start_utc=VAL_2020,
            test_start_utc=TEST_2020,
            extra_metadata={
            "stitch_points": {"BTC-USDT": STITCH_ISO},
            "non_comparable_window_bars": 30,
        },
        )


def test_re_freeze_keeps_flag_columns_and_segment_hashes(tmp_path):
    df = _bars("2020-09-01", 110 * 6, stitch=STITCH)
    extra = {
        "stitch_points": {"BTC-USDT": STITCH_ISO},
        "non_comparable_window_bars": 30,
    }
    mgr, frozen = _freeze_4h(tmp_path, {"BTC-USDT": df}, extra=extra)
    path = tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    first = pd.read_parquet(path)
    flags = ["incomplete", "non_comparable", "bad_tick", "external"]
    first_flags = first[flags].copy()
    written = _written_manifest(tmp_path, frozen.dataset_id)
    frozen2 = mgr.freeze_dataset(
        {"BTC-USDT": first.copy()},
        timeframe="4hour",
        data_cutoff_close_utc=CUTOFF_2020,
        validation_start_utc=VAL_2020,
        test_start_utc=TEST_2020,
        extra_metadata=extra,
    )
    second = pd.read_parquet(path)
    for name in flags:
        assert (first_flags[name].to_numpy() == second[name].to_numpy()).all()
    assert frozen2.manifest_hash == frozen.manifest_hash
    assert frozen2.test_hash == frozen.test_hash
    assert _written_manifest(tmp_path, frozen2.dataset_id)["train_hash"] == written["train_hash"]
    assert _written_manifest(tmp_path, frozen2.dataset_id)["val_hash"] == written["val_hash"]


def test_daily_stitch_day_is_non_comparable_with_utc00(tmp_path):
    times = pd.date_range("2020-10-01", periods=80 * 6, freq="4h", tz="UTC")
    close = 100 + pd.Series(range(len(times)), dtype=float)
    four_h = pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "source": ["binance" if ts < STITCH else "htx" for ts in times],
        }
    )
    four_h = mark_stitch_window(four_h, STITCH.to_pydatetime().replace(tzinfo=UTC), 30)
    daily = resample_4h_to_daily(
        four_h, daily_cut="UTC00", now=datetime(2020, 12, 1, tzinfo=timezone.utc)
    ).signal_bars
    assert not daily.empty
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": daily.copy()},
        timeframe="1day",
        data_cutoff_close_utc="2020-12-01T00:00:00Z",
        validation_start_utc="2020-11-20T00:00:00Z",
        test_start_utc="2020-11-25T00:00:00Z",
        extra_metadata={
            "stitch_points": {"BTC-USDT": STITCH_ISO},
            "daily_cut": "UTC00",
            "non_comparable_window_bars": 30,
        },
        bad_ticks=[],
    )
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    times_out = pd.to_datetime(loaded["bar_time"], utc=True)
    stitch_day = times_out == pd.Timestamp("2020-11-01T00:00:00Z")
    assert stitch_day.any()
    assert bool(loaded.loc[stitch_day, "non_comparable"].iloc[0]) is True


def test_daily_mixed_source_is_non_comparable(tmp_path):
    times = pd.date_range("2024-01-02", periods=6, freq="4h", tz="UTC")
    close = 100 + pd.Series(range(6), dtype=float)
    four_h = pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "source": ["binance", "binance", "binance", "htx", "htx", "htx"],
        }
    )
    daily = resample_4h_to_daily(
        four_h, daily_cut="UTC00", now=datetime(2024, 1, 10, tzinfo=timezone.utc)
    ).signal_bars
    assert bool(daily.iloc[0]["mixed_source"]) is True
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": daily.copy()},
        timeframe="1day",
        data_cutoff_close_utc="2024-01-10T00:00:00Z",
        validation_start_utc="2024-01-02T00:00:00Z",
        test_start_utc="2024-01-03T00:00:00Z",
        extra_metadata={
            "daily_cut": "UTC00",
            "non_comparable_window_bars": 30,
            "stitch_points": {"BTC-USDT": None},
        },
        bad_ticks=[],
    )
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    assert bool(loaded.iloc[0]["non_comparable"]) is True


def test_freeze_1day_raises_on_utc16_daily_cut(tmp_path):
    times = pd.date_range("2024-01-01", periods=12, freq="4h", tz="UTC")
    close = 100 + pd.Series(range(len(times)), dtype=float)
    four_h = pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "source": "htx",
        }
    )
    daily = resample_4h_to_daily(
        four_h, daily_cut="UTC00", now=datetime(2024, 1, 10, tzinfo=timezone.utc)
    ).signal_bars
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="UTC 00:00"):
        mgr.freeze_dataset(
            {"BTC-USDT": daily},
            timeframe="1day",
            data_cutoff_close_utc="2024-01-10T00:00:00Z",
            validation_start_utc="2024-01-02T00:00:00Z",
            test_start_utc="2024-01-03T00:00:00Z",
            extra_metadata={
                "daily_cut": "UTC16",
                "non_comparable_window_bars": 30,
                "stitch_points": {"BTC-USDT": None},
            },
            bad_ticks=[],
        )


def test_stitched_fixture_bad_tick_count_is_11(tmp_path):
    ticks = load_bad_ticks()
    times = pd.date_range("2020-09-01", "2022-06-01", freq="4h", tz="UTC")
    close = 100 + pd.Series(range(len(times)), dtype=float)
    base = pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "amount": 100.0,
            "source": ["binance" if ts < STITCH else "htx" for ts in times],
        }
    )
    extra = {
        "stitch_points": {
            "BTC-USDT": STITCH_ISO,
            "ETH-USDT": STITCH_ISO,
        }
    }
    _, frozen = _freeze_4h(
        tmp_path,
        {"BTC-USDT": base.copy(), "ETH-USDT": base.copy()},
        extra=extra,
        bad_ticks=ticks,
        cutoff="2022-06-02T00:00:00Z",
        validation_start="2021-06-01T00:00:00Z",
        test_start="2022-01-01T00:00:00Z",
    )
    total_bad = 0
    for symbol in ("BTC-USDT", "ETH-USDT"):
        loaded = pd.read_parquet(
            tmp_path / "data" / "datasets" / frozen.dataset_id / f"{symbol}.parquet"
        )
        assert int(loaded["non_comparable"].sum()) == 61
        total_bad += int(loaded["bad_tick"].sum())
    assert total_bad == 11
    assert len(ticks) == 11


def test_stitch_funding_history_raises_on_null_source():
    stitch = datetime(2020, 11, 1, tzinfo=UTC)
    bn = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": [pd.Timestamp("2020-10-01", tz="UTC")],
            "funding_rate": [0.0001],
            "realized_rate": [0.0001],
            "source": [pd.NA],
        }
    )
    htx = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": [pd.Timestamp("2020-12-01", tz="UTC")],
            "funding_rate": [0.0002],
            "realized_rate": [0.0002],
            "source": [pd.NA],
        }
    )
    with pytest.raises(ValueError, match="source"):
        stitch_funding_history(htx, bn, stitch)
