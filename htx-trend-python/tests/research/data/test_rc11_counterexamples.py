"""rc.11 counterexamples from the rc.10 data-layer review."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pandas as pd
import pytest

from htx_trend.research.data.collector import (
    _funding_frame,
    funding_source_timeline,
    stitch_funding_history,
)
from htx_trend.research.data.dataset import DatasetManager, derive_quality_flags
from htx_trend.research.data.quality import (
    require_row_count_accounting,
    stitch_point_for,
)
from htx_trend.research.data.stitcher import DataStitcher, mark_stitch_window
from htx_trend.research.features.funding_asof import attach_funding_as_of
from tests.research.helpers import (
    create_test_dataset,
    runner_config,
    write_batch_manifest,
    write_runner,
)

U = UTC
STITCH = datetime(2020, 11, 1, tzinfo=U)
CUT = datetime(2030, 1, 1, tzinfo=U)
T400 = pd.date_range("2020-10-01", periods=400, freq="4h", tz="UTC")


def _bars(times, source="htx", close=1.0):
    return pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close,
            "low": close,
            "close": close,
            "volume": 1.0,
            "amount": close,
            "source": source,
        }
    )


def _derive_kw():
    return dict(
        symbol="BTC-USDT",
        timeframe="4h",
        cutoff=CUT,
        window_bars=30,
        ticks=[],
    )


def test_default_stitch_point_constant_removed():
    import htx_trend.research.data.quality as quality

    assert not hasattr(quality, "DEFAULT_STITCH_POINT")
    assert not hasattr(quality, "DEFAULT_STITCH_POINTS")


def test_stitch_point_for_undeclared_symbol_raises():
    with pytest.raises(ValueError, match="not declared"):
        stitch_point_for("SOL-USDT", points={"BTC-USDT": STITCH})


def test_stitch_point_for_declared_none_is_none():
    assert stitch_point_for("SOL-USDT", points={"SOL-USDT": None}) is None
    assert stitch_point_for("XRP-USDT", points={"XRP-USDT": "none"}) is None


def test_htx_only_400_bars_kept_when_stitch_declared_none():
    htx = _bars(T400, source="htx")
    stitcher = DataStitcher(0.01, window_bars=30)
    combined, stats = stitcher.stitch_klines(
        htx,
        pd.DataFrame(),
        symbol="SOL-USDT",
        stitch_point=None,
        stitch_points={"SOL-USDT": None},
        window_bars=30,
    )
    assert len(htx) == 400
    assert len(combined) == 400
    assert int(combined["non_comparable"].sum()) == 0
    assert stats["non_comparable_bars"] == 0
    accounting = stats["row_count_accounting"]
    assert accounting["in"] == 400
    assert accounting["out"] == 400
    assert sum(accounting["dropped"].values()) == 0


def test_htx_only_undeclared_symbol_raises_instead_of_default_stitch():
    htx = _bars(T400, source="htx")
    stitcher = DataStitcher(0.01, window_bars=30)
    with pytest.raises(ValueError, match="not declared"):
        stitcher.stitch_klines(
            htx,
            pd.DataFrame(),
            symbol="SOL-USDT",
            window_bars=30,
        )


def test_row_count_mismatch_without_registered_reason_raises():
    with pytest.raises(ValueError, match="registered reason"):
        require_row_count_accounting(400, {}, 214)
    with pytest.raises(ValueError, match="unregistered"):
        require_row_count_accounting(400, {"mystery_drop": 186}, 214)


def test_declared_stitch_row_count_accounts_for_replaced_side():
    times = pd.date_range("2020-10-20", periods=80, freq="4h", tz="UTC")
    htx = _bars(times, source="htx")
    bn = _bars(times, source="binance")
    stitcher = DataStitcher(0.003, window_bars=30)
    combined, stats = stitcher.stitch_klines(
        htx,
        bn,
        stitch_point=STITCH,
        symbol="BTC-USDT",
        stitch_points={"BTC-USDT": STITCH},
        window_bars=30,
    )
    accounting = stats["row_count_accounting"]
    assert accounting["in"] == len(htx) + len(bn)
    assert accounting["out"] == len(combined)
    assert accounting["in"] - sum(accounting["dropped"].values()) == accounting["out"]
    assert "htx_before_stitch" in accounting["dropped"]
    assert "binance_at_or_after_stitch" in accounting["dropped"]


def test_freeze_undeclared_symbol_raises(tmp_path):
    df = _bars(pd.date_range("2020-09-01", periods=110 * 6, freq="4h", tz="UTC"))
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="missing from stitch"):
        mgr.freeze_dataset(
            {"SOL-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc="2020-12-15T00:00:00Z",
            validation_start_utc="2020-11-20T00:00:00Z",
            test_start_utc="2020-12-01T00:00:00Z",
            extra_metadata={
                "non_comparable_window_bars": 30,
                "stitch_points": {"BTC-USDT": None},
            },
        )


def test_freeze_htx_only_declared_none_keeps_all_bars_and_hashes_none(tmp_path):
    times = pd.date_range("2020-10-01", periods=400, freq="4h", tz="UTC")
    df = _bars(times)
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    frozen = mgr.freeze_dataset(
        {"SOL-USDT": df},
        timeframe="4hour",
        data_cutoff_close_utc="2020-12-15T00:00:00Z",
        validation_start_utc="2020-11-20T00:00:00Z",
        test_start_utc="2020-12-01T00:00:00Z",
        extra_metadata={
            "non_comparable_window_bars": 30,
            "stitch_points": {"SOL-USDT": None},
        },
    )
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "SOL-USDT.parquet"
    )
    assert len(loaded) == 400
    assert int(loaded["non_comparable"].sum()) == 0
    written = json.loads(
        (tmp_path / "data" / "datasets" / frozen.dataset_id / "manifest.json").read_text()
    )
    assert written["stitch_points"]["SOL-USDT"] is None
    hashed = {
        "symbols": written["symbols"],
        "timeframe": written["timeframe"],
        "start_date": written["start_date"],
        "end_date": written["end_date"],
        "total_bars": written["total_bars"],
        "data_cutoff_close_utc": written["data_cutoff_close_utc"],
        "split": written["split"],
        "non_comparable_window_bars": 30,
        "stitch_points": {"SOL-USDT": None},
    }
    digest = hashlib.sha256(json.dumps(hashed, sort_keys=True).encode()).hexdigest()[:16]
    assert frozen.manifest_hash == digest
    other = dict(hashed)
    other["stitch_points"] = {"SOL-USDT": "2020-11-01T00:00:00Z"}
    assert hashlib.sha256(json.dumps(other, sort_keys=True).encode()).hexdigest()[:16] != digest
    assert written["row_count_accounting"]["SOL-USDT"]["in"] == 400
    assert written["row_count_accounting"]["SOL-USDT"]["out"] == 400


def test_derive_htx_only_declared_none_has_zero_non_comparable():
    flags = derive_quality_flags(_bars(T400), stitch_point=None, **_derive_kw())
    assert int(flags["non_comparable"].sum()) == 0
    assert len(flags) == 400


def test_derive_source_none_raises():
    df = _bars(T400)
    df.loc[3, "source"] = None
    with pytest.raises(ValueError, match="null"):
        derive_quality_flags(df, stitch_point=None, **_derive_kw())


def test_derive_missing_source_column_raises():
    with pytest.raises(ValueError, match="source"):
        derive_quality_flags(
            _bars(T400).drop(columns="source"),
            stitch_point=None,
            **_derive_kw(),
        )


def test_derive_window_20_raises():
    with pytest.raises(ValueError, match="non_comparable_window_bars"):
        derive_quality_flags(_bars(T400), stitch_point=None, **dict(_derive_kw(), window_bars=20))


def test_derive_window_missing_raises():
    kw = _derive_kw()
    kw["window_bars"] = None
    with pytest.raises(ValueError, match="required"):
        derive_quality_flags(_bars(T400), stitch_point=None, **kw)


def test_mark_stitch_window_requires_window_bars():
    df = _bars(T400)
    with pytest.raises(TypeError):
        mark_stitch_window(df, STITCH)
    with pytest.raises(ValueError, match="window_bars"):
        mark_stitch_window(df, STITCH, None)


def test_data_stitcher_requires_window_bars():
    with pytest.raises(TypeError):
        DataStitcher(0.003)


def test_stitch_klines_requires_window_bars():
    stitcher = DataStitcher(0.003, window_bars=30)
    htx = _bars(T400)
    with pytest.raises(ValueError, match="window_bars"):
        stitcher.stitch_klines(
            htx,
            pd.DataFrame(),
            symbol="SOL-USDT",
            stitch_points={"SOL-USDT": None},
        )


def test_freeze_requires_window_bars(tmp_path):
    df = _bars(pd.date_range("2020-09-01", periods=110 * 6, freq="4h", tz="UTC"))
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="non_comparable_window_bars"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc="2020-12-15T00:00:00Z",
            validation_start_utc="2020-11-20T00:00:00Z",
            test_start_utc="2020-12-01T00:00:00Z",
            extra_metadata={"stitch_points": {"BTC-USDT": None}},
        )


def test_stitch_point_after_data_end_raises():
    times = pd.date_range("2020-09-01", periods=100, freq="4h", tz="UTC")
    df = _bars(times, source="binance")
    with pytest.raises(ValueError, match="after"):
        derive_quality_flags(df, stitch_point=STITCH, **_derive_kw())
    stitcher = DataStitcher(0.003, window_bars=30)
    with pytest.raises(ValueError, match="after"):
        stitcher.stitch_klines(
            df,
            df,
            stitch_point=STITCH,
            symbol="BTC-USDT",
            stitch_points={"BTC-USDT": STITCH},
            window_bars=30,
        )
    with pytest.raises(ValueError, match="after"):
        mark_stitch_window(df, STITCH, 30)


def test_funding_frame_missing_source_raises():
    row = SimpleNamespace(
        funding_time=datetime(2020, 11, 1, tzinfo=U),
        funding_rate=0.0001,
        realized_rate=0.0001,
    )
    with pytest.raises(ValueError, match="source"):
        _funding_frame([row], "BTC-USDT")


def test_stitch_funding_history_missing_source_raises():
    stitch = datetime(2020, 11, 1, tzinfo=U)
    bn = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": [pd.Timestamp("2020-10-01", tz="UTC")],
            "funding_rate": [0.0001],
        }
    )
    htx = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": [pd.Timestamp("2020-12-01", tz="UTC")],
            "funding_rate": [0.0002],
            "source": ["htx"],
        }
    )
    with pytest.raises(ValueError, match="source"):
        stitch_funding_history(htx, bn, stitch)


def test_funding_source_timeline_missing_source_raises(tmp_path):
    fund = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": [pd.Timestamp("2020-12-01", tz="UTC")],
            "funding_rate": [0.0002],
        }
    )
    fund.to_parquet(tmp_path / "BTC-USDT_funding.parquet", index=False)
    with pytest.raises(ValueError, match="source"):
        funding_source_timeline(tmp_path, ["BTC-USDT"])


def test_stitcher_htx_missing_source_raises():
    htx = pd.DataFrame({"bar_time": T400, "close": 1.0})
    stitcher = DataStitcher(0.01, window_bars=30)
    with pytest.raises(ValueError, match="source"):
        stitcher.stitch_klines(
            htx,
            pd.DataFrame(),
            symbol="SOL-USDT",
            stitch_points={"SOL-USDT": None},
            window_bars=30,
        )


def test_empty_funding_raises_unless_allow_missing():
    bars = _bars(pd.date_range("2024-01-01", periods=4, freq="4h", tz="UTC"))
    empty = pd.DataFrame(columns=["funding_time", "funding_rate"])
    with pytest.raises(ValueError, match="funding"):
        attach_funding_as_of(bars, empty, "4hour")
    out = attach_funding_as_of(bars, empty, "4hour", allow_missing_funding=True)
    assert bool(out["funding_missing"].all())
    assert (out["funding_rate"] == 0).all()


def test_pre_first_funding_is_zero_with_marker():
    bars = pd.DataFrame(
        {
            "bar_time": [pd.Timestamp("2024-01-01 00:00", tz="UTC")],
            "close": [100.0],
        }
    )
    funding = pd.DataFrame(
        {
            "funding_time": [pd.Timestamp("2024-01-01 04:00:01", tz="UTC")],
            "funding_rate": [0.5],
        }
    )
    out = attach_funding_as_of(bars, funding, "4hour")
    assert float(out.loc[0, "funding_rate"]) == 0.0
    assert bool(out.loc[0, "pre_first_funding"]) is True


def test_other_funding_nan_raises():
    bars = pd.DataFrame(
        {
            "bar_time": [pd.Timestamp("2024-01-01 04:00", tz="UTC")],
            "close": [100.0],
        }
    )
    funding = pd.DataFrame(
        {
            "funding_time": [pd.Timestamp("2024-01-01 04:00", tz="UTC")],
            "funding_rate": [float("nan")],
        }
    )
    with pytest.raises(ValueError, match="NaN"):
        attach_funding_as_of(bars, funding, "4hour")


def test_runner_missing_source_raises(tmp_path):
    create_test_dataset(tmp_path, "no-source", n_bars=48, symbols=["BTC-USDT"])
    path = tmp_path / "data" / "datasets" / "no-source" / "BTC-USDT.parquet"
    df = pd.read_parquet(path).drop(columns=["source"])
    df.to_parquet(path, index=False)
    write_batch_manifest(tmp_path, "no-source")
    cfg = runner_config(tmp_path, "NOSRC", "no-source", symbols=["BTC-USDT"])
    runner = write_runner(tmp_path, cfg)
    with pytest.raises(ValueError, match="source"):
        runner._load_dataset()


def test_runner_keeps_non_comparable_independent_of_incomplete(tmp_path):
    create_test_dataset(tmp_path, "flag-indep", n_bars=48, symbols=["BTC-USDT"])
    path = tmp_path / "data" / "datasets" / "flag-indep" / "BTC-USDT.parquet"
    df = pd.read_parquet(path)
    df.loc[df.index[-1], "incomplete"] = True
    df.loc[df.index[-1], "non_comparable"] = False
    df.to_parquet(path, index=False)
    write_batch_manifest(tmp_path, "flag-indep")
    cfg = runner_config(tmp_path, "FLAG1", "flag-indep", symbols=["BTC-USDT"])
    runner = write_runner(tmp_path, cfg)
    data, _ = runner._load_dataset()
    row = data["BTC-USDT"].iloc[-1]
    assert bool(row["incomplete"]) is True
    assert bool(row["non_comparable"]) is False
