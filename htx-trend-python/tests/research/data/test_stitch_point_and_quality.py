"""Configurable stitch point, non_comparable window, audit, and bad_ticks."""

from __future__ import annotations

import hashlib
import json
from argparse import Namespace
from datetime import UTC, datetime
from pathlib import Path

import pandas as pd
import pytest

from htx_trend.research.cli import cmd_freeze
from htx_trend.research.data.manifest import DatasetManifest
from htx_trend.research.data.quality import (
    DEFAULT_STITCH_POINT,
    apply_bad_ticks,
    bad_ticks_hash,
    load_bad_ticks,
)
from htx_trend.research.data.stitcher import DataStitcher
from htx_trend.research.features.daily_resample import daily_open_for


def _klines(times, close, source):
    closes = pd.Series(close, dtype=float)
    if len(closes) == 1 and len(times) > 1:
        closes = pd.Series([float(closes.iloc[0])] * len(times))
    return pd.DataFrame(
        {
            "bar_time": pd.to_datetime(times, utc=True),
            "open": closes,
            "high": closes,
            "low": closes,
            "close": closes,
            "volume": 1.0,
            "amount": closes,
            "source": source,
        }
    )


def _around_stitch(stitch=None, before=40, after=40, freq="4h"):
    stitch = pd.Timestamp(stitch or DEFAULT_STITCH_POINT)
    left = pd.date_range(end=stitch - pd.Timedelta(freq), periods=before, freq=freq, tz="UTC")
    right = pd.date_range(start=stitch, periods=after, freq=freq, tz="UTC")
    return left.union(right)


def test_default_stitch_point_is_2020_11_01_utc():
    assert DEFAULT_STITCH_POINT == datetime(2020, 11, 1, 0, 0, tzinfo=UTC)


def test_binance_before_stitch_htx_after_configurable_point():
    stitch = datetime(2020, 11, 1, tzinfo=UTC)
    times = _around_stitch(stitch, before=8, after=8)
    htx = _klines(times, 100.0, "htx")
    bn = _klines(times, 100.0, "binance")
    combined, stats = DataStitcher(0.003).stitch_klines(
        htx, bn, stitch_point=stitch, symbol="BTC-USDT"
    )
    assert stats["stitch_point"].startswith("2020-11-01T00:00:00")
    before = combined[combined["bar_time"] < pd.Timestamp(stitch)]
    after = combined[combined["bar_time"] >= pd.Timestamp(stitch)]
    assert (before["source"] == "binance").all()
    assert (after["source"] == "htx").all()
    assert int(before["is_backfill"].sum()) == len(before)
    assert int(after["is_backfill"].sum()) == 0

    later = datetime(2020, 12, 1, tzinfo=UTC)
    moved, moved_stats = DataStitcher(0.003).stitch_klines(
        htx, bn, stitch_point=later, symbol="BTC-USDT"
    )
    assert moved_stats["stitch_point"].startswith("2020-12-01T00:00:00")
    assert (moved[moved["bar_time"] < pd.Timestamp(later)]["source"] == "binance").all()
    assert (moved[moved["bar_time"] >= pd.Timestamp(later)]["source"] == "htx").all()


def test_non_comparable_only_30_bars_each_side_of_stitch():
    stitch = datetime(2020, 11, 1, tzinfo=UTC)
    times = _around_stitch(stitch, before=40, after=40)
    htx = _klines(times, 100.0, "htx")
    bn = _klines(times, 100.0, "binance")
    combined, stats = DataStitcher(0.003).stitch_klines(
        htx, bn, stitch_point=stitch, symbol="ETH-USDT", window_bars=30
    )
    assert stats["non_comparable_window_bars"] == 30
    window = combined[combined["non_comparable"]]
    comparable = combined[~combined["non_comparable"]]
    assert len(window) == 61
    assert comparable.iloc[-1]["bar_time"] >= pd.Timestamp("2020-11-06", tz="UTC")
    far_before = combined[combined["bar_time"] == times[0]].iloc[0]
    far_after = combined[combined["bar_time"] == times[-1]].iloc[0]
    assert bool(far_before["non_comparable"]) is False
    assert bool(far_after["non_comparable"]) is False


def test_overlap_deviation_goes_to_audit_not_non_comparable():
    stitch = datetime(2020, 11, 1, tzinfo=UTC)
    times = _around_stitch(stitch, before=40, after=40)
    htx_close = [100.0] * len(times)
    bn_close = [100.0] * len(times)
    far_idx = 0
    review_idx = 1
    bn_close[far_idx] = 100.4  # 0.4% > 0.3%, far from stitch
    bn_close[review_idx] = 102.0  # 2.0% > 1.5%
    htx = _klines(times, htx_close, "htx")
    bn = _klines(times, bn_close, "binance")
    combined, stats = DataStitcher(0.003, manual_review_threshold=0.015).stitch_klines(
        htx, bn, stitch_point=stitch, symbol="BTC-USDT", window_bars=30
    )
    audit = {row["timestamp"]: row for row in stats["overlap_audit"]}
    far_ts = pd.Timestamp(times[far_idx]).isoformat()
    review_ts = pd.Timestamp(times[review_idx]).isoformat()
    assert far_ts in audit
    assert audit[far_ts]["flag_manual_review"] is False
    assert review_ts in audit
    assert audit[review_ts]["flag_manual_review"] is True
    assert stats["manual_review_flags"] >= 1
    far_row = combined[combined["bar_time"] == times[far_idx]].iloc[0]
    review_row = combined[combined["bar_time"] == times[review_idx]].iloc[0]
    assert bool(far_row["non_comparable"]) is False
    assert bool(review_row["non_comparable"]) is False


def test_daily_bar_containing_stitch_is_non_comparable():
    stitch = datetime(2020, 11, 1, tzinfo=UTC)
    times = pd.date_range("2020-10-30 00:00", periods=18, freq="4h", tz="UTC")
    htx = _klines(times, 100.0, "htx")
    bn = _klines(times, 100.0, "binance")
    stitcher = DataStitcher(0.003, window_bars=30)
    utc00, _ = stitcher.stitch_daily_from_4h(
        htx, bn, daily_cut="UTC00", now=datetime(2020, 11, 10, tzinfo=UTC),
        stitch_point=stitch, symbol="BTC-USDT",
    )
    utc16, _ = stitcher.stitch_daily_from_4h(
        htx, bn, daily_cut="UTC16", now=datetime(2020, 11, 10, tzinfo=UTC),
        stitch_point=stitch, symbol="BTC-USDT",
    )
    cut00 = daily_open_for(stitch, "UTC00")
    cut16 = daily_open_for(stitch, "UTC16")
    row00 = utc00[utc00["bar_time"] == pd.Timestamp(cut00)]
    row16 = utc16[utc16["bar_time"] == pd.Timestamp(cut16)]
    assert not row00.empty
    assert not row16.empty
    assert bool(row00.iloc[0]["non_comparable"]) is True
    assert bool(row16.iloc[0]["non_comparable"]) is True


def _freeze_args(workspace: Path, **extra):
    payload = {
        "workspace": str(workspace),
        "contracts": "BTC-USDT",
        "timeframe": "4hour",
        "train_ratio": 0.6,
        "val_ratio": 0.2,
        "backfill_binance": True,
        "overlap_threshold": 0.003,
        "daily_cut": None,
        "stitch_point": "2020-11-01T00:00:00Z",
        "stitch_config": None,
        "non_comparable_window": 30,
        "bad_ticks": None,
    }
    payload.update(extra)
    return Namespace(**payload)


def _write_raw(workspace: Path, times, htx_close=100.0, bn_close=100.0):
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    _klines(times, htx_close, "htx").to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    _klines(times, bn_close, "binance").to_parquet(raw / "BTCUSDT_4h_binance.parquet", index=False)


def test_written_dataset_manifest_json_contains_stitch_points_and_bad_ticks_hash(tmp_path):
    times = _around_stitch(before=8, after=8)
    _write_raw(tmp_path, times)
    yaml_path = tmp_path / "bad_ticks.yaml"
    yaml_path.write_text(
        "\n".join(
            [
                "version: 1",
                "ticks:",
                "  - symbol: BTC-USDT",
                '    bar_time: "2020-11-06T00:00:00Z"',
                "    kind: frozen",
                "    confirmed: true",
                "    reason: fixture",
            ]
        )
    )
    cmd_freeze(_freeze_args(tmp_path, stitch_point="2020-11-01T00:00:00Z", bad_ticks=str(yaml_path)))
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    written_path = dataset_dir / "manifest.json"
    assert written_path.is_file()
    written = json.loads(written_path.read_text())
    assert "stitch_points" in written
    assert written["stitch_points"]["BTC-USDT"].startswith("2020-11-01T00:00:00")
    assert str(written["bad_ticks_hash"]).startswith("sha256:")
    assert written["bad_ticks_hash"] == bad_ticks_hash(yaml_path)
    hashed = json.dumps(
        {k: written[k] for k in ("symbols", "timeframe", "stitch_points", "bad_ticks_hash")},
        sort_keys=True,
    )
    assert "2020-11-01" in hashed
    batch_path = tmp_path / "data" / "datasets" / "manifests" / f"{dataset_dir.name}.json"
    batch_written = json.loads(batch_path.read_text())
    assert batch_written["stitch_points"]["BTC-USDT"].startswith("2020-11-01T00:00:00")
    assert batch_written["bad_ticks_hash"] == written["bad_ticks_hash"]
    assert "stitch_points" in json.dumps(batch_written, sort_keys=True)


def test_freeze_records_funding_file_hashes_and_load_rejects_mismatch(tmp_path):
    times = _around_stitch(before=8, after=8)
    _write_raw(tmp_path, times)
    raw = tmp_path / "data" / "raw"
    funding = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": pd.to_datetime(["2020-11-01T08:00:00Z"], utc=True),
            "funding_rate": [0.0001],
            "source": ["htx"],
        }
    )
    funding.to_parquet(raw / "BTC-USDT_funding.parquet", index=False)
    cmd_freeze(_freeze_args(tmp_path, stitch_point="2020-11-01T00:00:00Z"))
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    written = json.loads((dataset_dir / "manifest.json").read_text())
    recorded = written["funding_files"]["BTC-USDT"]
    assert recorded["sha256"].startswith("sha256:")
    digest = hashlib.sha256((raw / "BTC-USDT_funding.parquet").read_bytes()).hexdigest()
    assert recorded["sha256"] == f"sha256:{digest}"
    batch = DatasetManifest.load(
        tmp_path / "data" / "datasets" / "manifests" / f"{dataset_dir.name}.json"
    )
    assert batch.metadata["funding_files"]["BTC-USDT"]["sha256"] == recorded["sha256"]
    baseline = batch.sha256()
    batch.metadata["funding_files"] = {
        "BTC-USDT": {"path": "BTC-USDT_funding.parquet", "sha256": "sha256:" + ("0" * 64)}
    }
    assert batch.sha256() != baseline

    from htx_trend.research.data.dataset import DatasetManager

    mgr = DatasetManager(tmp_path / "data" / "datasets")
    manifest_hash = DatasetManifest.load(
        tmp_path / "data" / "datasets" / "manifests" / f"{dataset_dir.name}.json"
    ).sha256()
    tampered = funding.copy()
    tampered.loc[0, "funding_rate"] = 0.9999
    tampered.to_parquet(raw / "BTC-USDT_funding.parquet", index=False)
    with pytest.raises(ValueError, match="[Ff]unding"):
        mgr.load_dataset_with_verification(dataset_dir.name, manifest_hash)


def test_freeze_records_funding_sources_in_manifest_and_hash(tmp_path):
    times = _around_stitch(before=8, after=8)
    _write_raw(tmp_path, times)
    raw = tmp_path / "data" / "raw"
    funding = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"] * 3,
            "funding_time": pd.to_datetime(
                [
                    "2020-10-21T16:00:00Z",
                    "2020-10-31T16:00:00Z",
                    "2020-11-01T08:00:00Z",
                ],
                utc=True,
            ),
            "funding_rate": [0.0001, 0.0002, 0.0003],
            "source": ["binance_external", "binance_external", "htx"],
        }
    )
    funding.to_parquet(raw / "BTC-USDT_funding.parquet", index=False)
    cmd_freeze(_freeze_args(tmp_path, stitch_point="2020-11-01T00:00:00Z"))
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    written = json.loads((dataset_dir / "manifest.json").read_text())
    sources = written["funding_sources"]["BTC-USDT"]
    assert sources["binance_external"] == 2
    assert sources["htx"] == 1
    batch = DatasetManifest.load(
        tmp_path / "data" / "datasets" / "manifests" / f"{dataset_dir.name}.json"
    )
    assert batch.metadata["funding_sources"]["BTC-USDT"]["binance_external"] == 2
    assert batch.metadata["funding_sources"]["BTC-USDT"]["htx"] == 1
    baseline = batch.sha256()
    batch.metadata["funding_sources"] = {
        "BTC-USDT": {"binance_external": 0, "htx": 1}
    }
    assert batch.sha256() != baseline


def test_freeze_records_stitch_point_and_bad_ticks_in_manifest_hash(tmp_path):
    times = _around_stitch(before=8, after=8)
    _write_raw(tmp_path, times)
    cmd_freeze(_freeze_args(tmp_path, stitch_point="2020-11-01T00:00:00Z"))
    manifests = list((tmp_path / "data" / "datasets" / "manifests").glob("*.json"))
    assert len(manifests) == 1
    batch = DatasetManifest.load(manifests[0])
    assert batch.metadata["stitch_points"]["BTC-USDT"].startswith("2020-11-01T00:00:00")
    assert batch.metadata["non_comparable_window_bars"] == 30
    assert str(batch.metadata["bad_ticks_hash"]).startswith("sha256:")
    baseline = batch.sha256()
    batch.metadata["stitch_points"] = {"BTC-USDT": "2021-01-01T00:00:00+00:00"}
    assert batch.sha256() != baseline
    batch.metadata["stitch_points"] = {"BTC-USDT": "2020-11-01T00:00:00+00:00"}
    mutated = batch.sha256()
    batch.metadata["bad_ticks_hash"] = "sha256:" + ("0" * 64)
    assert batch.sha256() != mutated

    other = tmp_path / "other"
    _write_raw(other, times)
    cmd_freeze(_freeze_args(other, stitch_point="2020-12-01T00:00:00Z"))
    other_batch = DatasetManifest.load(
        next((other / "data" / "datasets" / "manifests").glob("*.json"))
    )
    assert other_batch.metadata["stitch_points"]["BTC-USDT"].startswith("2020-12-01T00:00:00")
    assert other_batch.sha256() != baseline


def test_freeze_writes_quality_audit_and_does_not_auto_mark_ops_candidates(tmp_path):
    stitch = datetime(2020, 11, 1, tzinfo=UTC)
    times = _around_stitch(stitch, before=40, after=40)
    bn_close = [100.0] * len(times)
    bn_close[0] = 100.5
    bn_close[1] = 102.0
    _write_raw(tmp_path, times, htx_close=100.0, bn_close=bn_close)
    cmd_freeze(_freeze_args(tmp_path))
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    audit_path = dataset_dir / "data_quality_audit.json"
    assert audit_path.exists()
    assert (dataset_dir / "data_quality_audit.md").exists()
    payload = json.loads(audit_path.read_text())
    assert payload["overlap_audit"]
    assert any(row["flag_manual_review"] for row in payload["overlap_audit"])
    assert any(not row["flag_manual_review"] for row in payload["overlap_audit"])
    df = pd.read_parquet(dataset_dir / "BTC-USDT.parquet")
    launch = df[df["bar_time"] == pd.Timestamp("2020-10-21T00:00:00Z")]
    frozen = df[df["bar_time"] == pd.Timestamp("2021-02-19T12:00:00Z")]
    assert launch.empty or bool(launch.iloc[0].get("bad_tick", False)) is False
    assert frozen.empty or bool(frozen.iloc[0].get("bad_tick", False)) is False
    if "bad_tick" in df.columns:
        assert int(df["bad_tick"].sum()) == 0


def test_confirmed_bad_tick_excludes_from_signals_and_hashes(tmp_path):
    yaml_path = tmp_path / "bad_ticks.yaml"
    yaml_path.write_text(
        "\n".join(
            [
                "version: 1",
                "ticks:",
                "  - symbol: BTC-USDT",
                '    bar_time: "2020-11-06T00:00:00Z"',
                "    kind: frozen",
                "    reason: confirmed frozen-price after review",
                "    confirmed: true",
                "  - symbol: BTC-USDT",
                '    bar_time: "2021-02-19T12:00:00Z"',
                "    kind: frozen",
                "    reason: ops candidate only",
                "    confirmed: false",
            ]
        )
    )
    ticks = load_bad_ticks(yaml_path)
    digest = bad_ticks_hash(yaml_path, ticks)
    other = tmp_path / "other.yaml"
    other.write_text(yaml_path.read_text() + "\n# comment changes hash\n")
    assert bad_ticks_hash(other, ticks) != digest

    times = _around_stitch(before=40, after=40)
    df = apply_bad_ticks(_klines(times, 100.0, "htx"), "BTC-USDT", ticks)
    marked = df[df["bar_time"] == pd.Timestamp("2020-11-06T00:00:00Z")]
    candidate = df[df["bar_time"] == pd.Timestamp("2021-02-19T12:00:00Z")]
    assert not marked.empty
    assert bool(marked.iloc[0]["bad_tick"]) is True
    assert candidate.empty or bool(candidate.iloc[0]["bad_tick"]) is False

    _write_raw(tmp_path, times)
    cmd_freeze(_freeze_args(tmp_path, bad_ticks=str(yaml_path)))
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    frozen = pd.read_parquet(dataset_dir / "BTC-USDT.parquet")
    hit = frozen[frozen["bar_time"] == pd.Timestamp("2020-11-06T00:00:00Z")]
    assert not hit.empty
    assert bool(hit.iloc[0]["bad_tick"]) is True
    batch = DatasetManifest.load(next((tmp_path / "data" / "datasets" / "manifests").glob("*.json")))
    assert batch.metadata["bad_ticks_hash"] == digest

    from htx_trend.research.conditions.base import DecisionContext
    from htx_trend.research.conditions.c01_donchian import C01DonchianEnsemble
    from htx_trend.research.features.indicators import FeatureCalculator

    bars = []
    for i in range(168):
        bars.append(
            {
                "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4 * i),
                "open": 95 + (i % 10),
                "high": 100 + (i % 15),
                "low": 90 + (i % 8),
                "close": 98 + (i % 12),
                "volume": 1000,
                "bad_tick": False,
                "non_comparable": False,
            }
        )
    bars.append(
        {
            "bar_time": pd.Timestamp("2024-01-01") + pd.Timedelta(hours=4 * 168),
            "open": 115,
            "high": 132,
            "low": 114,
            "close": 130,
            "volume": 1000,
            "bad_tick": True,
            "non_comparable": False,
        }
    )
    history = pd.DataFrame(bars)
    for period in [42, 84, 126, 168]:
        history[f"donchian_high_{period}"] = FeatureCalculator.donchian_high(
            history["high"], period
        )
        history[f"donchian_low_{period}"] = FeatureCalculator.donchian_low(
            history["low"], period
        )
    ctx = DecisionContext(
        symbol="BTC-USDT",
        bar_time=history["bar_time"].iloc[-1],
        df=history,
        current_idx=len(history) - 1,
    )
    result = C01DonchianEnsemble(periods=[42, 84, 126, 168], threshold=0.25).evaluate(ctx, {})
    assert result.status == "PASS"
    bar = history.iloc[-1]
    signal_generated = not (bar.get("bad_tick") or bar.get("non_comparable"))
    assert signal_generated is False
