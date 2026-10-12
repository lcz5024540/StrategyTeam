"""Freeze cutoff + 60/20/20 UTC-00:00 split stored in the hashed manifest."""

from __future__ import annotations

import json
from argparse import Namespace
from datetime import UTC, datetime, timezone
from pathlib import Path

import pandas as pd
import pytest

from htx_trend.research.data import dataset as dataset_mod
from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.data.quality import BadTick
from htx_trend.research.data.manifest import DatasetManifest
from htx_trend.research.features.daily_resample import resample_4h_to_daily
from htx_trend.research.ledger import LedgerManager
from tests.research.helpers import (
    create_test_dataset,
    runner_config,
    write_batch_manifest,
    write_contract_specs,
    write_runner,
)


def _ts(value: str) -> pd.Timestamp:
    return pd.Timestamp(value, tz="UTC")


def _bars(start: str, n_bars: int, freq: str = "4h") -> pd.DataFrame:
    times = pd.date_range(start, periods=n_bars, freq=freq, tz="UTC")
    close = 100 + pd.Series(range(n_bars), dtype=float)
    return pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "amount": 100.0,
            "source": "htx",
        }
    )


V010_CUTOFF = "2026-10-12T00:00:00Z"


def _freeze(
    tmp_path: Path,
    df: pd.DataFrame,
    *,
    timeframe: str = "4hour",
    cutoff: str = V010_CUTOFF,
    test_start: str | None = None,
    validation_start: str | None = None,
    extra_metadata: dict | None = None,
) -> tuple[DatasetManager, object]:
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    kwargs: dict = {"data_cutoff_close_utc": cutoff}
    if test_start is not None:
        kwargs["test_start_utc"] = test_start
    if validation_start is not None:
        kwargs["validation_start_utc"] = validation_start
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df},
        timeframe=timeframe,
        extra_metadata=extra_metadata,
        **kwargs,
    )
    return mgr, frozen


def _written_manifest(tmp_path: Path, dataset_id: str) -> dict:
    path = tmp_path / "data" / "datasets" / dataset_id / "manifest.json"
    return json.loads(path.read_text())


def _as_utc(value) -> pd.Timestamp:
    ts = pd.Timestamp(value)
    if ts.tzinfo is None:
        return ts.tz_localize("UTC")
    return ts.tz_convert("UTC")


def test_require_timeframe_rejects_missing_and_blank():
    with pytest.raises(ValueError, match="timeframe"):
        dataset_mod.require_timeframe(None, None)
    with pytest.raises(ValueError, match="timeframe"):
        dataset_mod.require_timeframe("", "  ")
    assert dataset_mod.require_timeframe(None, "4hour") == "4hour"


def test_library_freeze_requires_explicit_cutoff(tmp_path):
    assert not hasattr(dataset_mod, "DEFAULT_DATA_CUTOFF_CLOSE_UTC")
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="data_cutoff_close_utc is required"):
        mgr.freeze_dataset({"BTC-USDT": _bars("2026-09-21", 21 * 6)}, timeframe="4hour")


def test_split_bounds_must_be_strictly_ordered_and_at_or_before_cutoff(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="validation_start < test_start <= cutoff"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc=V010_CUTOFF,
            validation_start_utc="2026-10-10T00:00:00Z",
            test_start_utc="2026-10-01T00:00:00Z",
        )
    with pytest.raises(ValueError, match="validation_start < test_start <= cutoff"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc="2026-10-01T00:00:00Z",
            validation_start_utc="2026-09-25T00:00:00Z",
            test_start_utc="2026-10-02T00:00:00Z",
        )


def test_split_bounds_must_fall_within_data_time_range(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="data time range"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc=V010_CUTOFF,
            validation_start_utc="2020-01-01T00:00:00Z",
            test_start_utc="2020-06-01T00:00:00Z",
        )
    with pytest.raises(ValueError, match="data time range"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc="2027-01-01T00:00:00Z",
            validation_start_utc="2026-09-25T00:00:00Z",
            test_start_utc="2026-12-01T00:00:00Z",
        )


def test_freeze_writes_cutoff_and_utc_midnight_split_to_hashed_manifest(tmp_path):
    # 21 UTC days of 4h (126 bars). idx-based 60% lands at 12:00; day-based must be 00:00.
    df = _bars("2026-09-21", 21 * 6)
    mgr, frozen = _freeze(tmp_path, df)
    written = _written_manifest(tmp_path, frozen.dataset_id)

    assert written["data_cutoff_close_utc"] in {
        "2026-10-12T00:00:00Z",
        "2026-10-12T00:00:00+00:00",
    }
    split = written["split"]
    val_start = _as_utc(split["validation_start_utc"])
    test_start = _as_utc(split["test_start_utc"])
    assert val_start.strftime("%H:%M:%S") == "00:00:00"
    assert test_start.strftime("%H:%M:%S") == "00:00:00"
    assert val_start.tzinfo is not None
    assert test_start > val_start

    unique_days = 21
    assert val_start == _ts("2026-09-21") + pd.Timedelta(days=int(unique_days * 0.6))
    assert test_start == _ts("2026-09-21") + pd.Timedelta(days=int(unique_days * 0.8))

    batch = DatasetManifest.load(
        tmp_path / "data" / "datasets" / "manifests" / f"{frozen.dataset_id}.json"
    )
    assert batch.metadata["data_cutoff_close_utc"] == written["data_cutoff_close_utc"]
    assert batch.metadata["split"] == written["split"]
    assert "train_end_idx" not in written
    assert "val_end_idx" not in written

    _, other = _freeze(tmp_path / "other", df, cutoff="2026-10-01T00:00:00Z")
    assert other.manifest_hash != frozen.manifest_hash
    assert other.dataset_id != frozen.dataset_id
    other_batch = DatasetManifest.load(
        tmp_path / "other" / "data" / "datasets" / "manifests" / f"{other.dataset_id}.json"
    )
    assert other_batch.sha256() != batch.sha256()


def test_freeze_writes_quality_flag_columns_as_bool_dtype(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    assert "external" not in df.columns
    _, frozen = _freeze(tmp_path, df)
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    for name in ("incomplete", "non_comparable", "bad_tick", "external"):
        assert name in loaded.columns
        assert loaded[name].dtype == bool
        assert not loaded[name].isna().any()
        assert (loaded[name] == False).all()


def test_freeze_raises_if_flag_derivation_leaves_na(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    df.loc[df.index[0], "source"] = pd.NA
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="external"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc=V010_CUTOFF,
        )


def test_freeze_output_flags_match_stitch_bad_tick_and_binance_source(tmp_path):
    stitch = pd.Timestamp("2020-11-01T00:00:00Z")
    times = pd.date_range("2020-10-20", periods=21 * 6, freq="4h", tz="UTC")
    close = 100 + pd.Series(range(len(times)), dtype=float)
    tick_time = pd.Timestamp("2020-11-05T00:00:00Z")
    df = pd.DataFrame(
        {
            "bar_time": times,
            "open": close,
            "high": close + 1,
            "low": close - 1,
            "close": close,
            "volume": 10.0,
            "amount": 100.0,
            "source": ["binance" if ts < stitch else "htx" for ts in times],
        }
    )
    ticks = [
        BadTick(
            symbol="BTC-USDT",
            bar_time=tick_time.to_pydatetime().replace(tzinfo=UTC),
            reason="fixture spike",
            confirmed=True,
            kind="spike",
        )
    ]
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df},
        timeframe="4hour",
        data_cutoff_close_utc="2020-11-10T00:00:00Z",
        validation_start_utc="2020-11-06T00:00:00Z",
        test_start_utc="2020-11-08T00:00:00Z",
        extra_metadata={
            "stitch_points": {"BTC-USDT": "2020-11-01T00:00:00Z"},
            "non_comparable_window_bars": 30,
        },
        bad_ticks=ticks,
    )
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    times_out = pd.to_datetime(loaded["bar_time"], utc=True)
    for name in ("incomplete", "non_comparable", "bad_tick", "external"):
        assert loaded[name].dtype == bool
        assert not loaded[name].isna().any()
    assert not loaded["incomplete"].any()
    assert (loaded.loc[times_out < stitch, "external"] == True).all()
    assert (loaded.loc[times_out >= stitch, "external"] == False).all()
    assert bool(loaded.loc[times_out == tick_time, "bad_tick"].iloc[0]) is True
    assert int(loaded["bad_tick"].sum()) == 1
    window = loaded[loaded["non_comparable"]]
    assert int(loaded["non_comparable"].sum()) == 61
    assert len(window) == 61
    far = loaded.loc[times_out == times[0]].iloc[0]
    assert bool(far["non_comparable"]) is False
    assert bool(far["external"]) is True
    assert bool(far["bad_tick"]) is False


def test_freeze_keeps_last_4h_open_2026_10_11_20z_and_drops_later(tmp_path):
    kept = _bars("2026-10-11 20:00", 1)
    dropped = _bars("2026-10-12 00:00", 1)
    df = pd.concat([kept, dropped], ignore_index=True)
    mgr, frozen = _freeze(
        tmp_path,
        df,
        validation_start="2026-10-11T00:00:00Z",
        test_start="2026-10-12T00:00:00Z",
    )
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    opens = pd.to_datetime(loaded["bar_time"], utc=True)
    assert list(opens) == [_ts("2026-10-11 20:00")]
    assert frozen.total_bars == 1


def test_freeze_rejects_bars_whose_close_is_after_cutoff(tmp_path):
    df = pd.concat(
        [
            _bars("2026-10-10 00:00", 6),
            _bars("2026-10-12 04:00", 2),
        ],
        ignore_index=True,
    )
    _, frozen = _freeze(
        tmp_path,
        df,
        cutoff="2026-10-11T00:00:00Z",
        validation_start="2026-10-10T00:00:00Z",
        test_start="2026-10-11T00:00:00Z",
    )
    loaded = pd.read_parquet(
        tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    )
    closes = pd.to_datetime(loaded["bar_time"], utc=True) + pd.Timedelta(hours=4)
    assert (closes <= _ts("2026-10-11T00:00:00")).all()
    assert len(loaded) == 6


def test_load_hard_fails_when_manifest_split_missing(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    dataset_id = create_test_dataset(workspace, "no_split_ds")
    manifest_path = workspace / "data" / "datasets" / dataset_id / "manifest.json"
    payload = json.loads(manifest_path.read_text())
    payload.pop("split", None)
    payload.pop("data_cutoff_close_utc", None)
    payload.pop("validation_start_utc", None)
    payload.pop("test_start_utc", None)
    manifest_path.write_text(json.dumps(payload, indent=2))
    mgr = DatasetManager(workspace / "data" / "datasets")
    with pytest.raises(ValueError, match="split|data_cutoff"):
        mgr.load_dataset(dataset_id, split="train")


def test_load_uses_manifest_split_later_bars_do_not_move_boundaries(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    mgr, frozen = _freeze(tmp_path, df)
    written = _written_manifest(tmp_path, frozen.dataset_id)
    val_start = _as_utc(written["split"]["validation_start_utc"])
    test_start = _as_utc(written["split"]["test_start_utc"])

    train, _ = mgr.load_dataset(frozen.dataset_id, split="train")
    val, _ = mgr.load_dataset(frozen.dataset_id, split="val")
    train_n = len(train["BTC-USDT"])
    val_n = len(val["BTC-USDT"])
    assert pd.to_datetime(train["BTC-USDT"]["bar_time"], utc=True).max() < val_start
    assert pd.to_datetime(val["BTC-USDT"]["bar_time"], utc=True).min() >= val_start
    assert pd.to_datetime(val["BTC-USDT"]["bar_time"], utc=True).max() < test_start

    extra = _bars("2026-10-13 00:00", 12)
    path = tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    combined = pd.concat([pd.read_parquet(path), extra], ignore_index=True)
    combined.to_parquet(path, index=False)

    train2, _ = mgr.load_dataset(frozen.dataset_id, split="train")
    val2, _ = mgr.load_dataset(frozen.dataset_id, split="val")
    assert len(train2["BTC-USDT"]) == train_n
    assert len(val2["BTC-USDT"]) == val_n
    reloaded = _written_manifest(tmp_path, frozen.dataset_id)
    assert reloaded["split"] == written["split"]
    assert reloaded["data_cutoff_close_utc"] == written["data_cutoff_close_utc"]


def test_load_rejects_post_cutoff_bars_even_if_parquet_grows(tmp_path):
    from htx_trend.research.ledger import Approval, ApprovalDecision, ApprovalGate

    df = _bars("2026-10-11 16:00", 2)
    ledger = LedgerManager(tmp_path / "ledger.db")
    mgr = DatasetManager(
        tmp_path / "data" / "datasets",
        ledger=ledger,
        current_run_id="CUT1",
    )
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df},
        timeframe="4hour",
        data_cutoff_close_utc=V010_CUTOFF,
        validation_start_utc="2026-10-11T00:00:00Z",
        test_start_utc="2026-10-12T00:00:00Z",
    )
    path = tmp_path / "data" / "datasets" / frozen.dataset_id / "BTC-USDT.parquet"
    extra = _bars("2026-10-12 00:00", 3)
    pd.concat([pd.read_parquet(path), extra], ignore_index=True).to_parquet(path, index=False)
    approval_id = ledger.create_approval(
        Approval(
            approval_id=None,
            gate=ApprovalGate.STAGE_ACCEPTANCE,
            run_id="CUT1",
            strategy_version="v0.1.0",
            approver="reviewer@example.com",
            decision=ApprovalDecision.APPROVED,
            rationale="cutoff must still apply after parquet grows",
            created_at=datetime.now(timezone.utc),
        )
    )
    frames = []
    for split in ("train", "val"):
        data, _ = mgr.load_dataset(frozen.dataset_id, split=split)
        frames.append(data["BTC-USDT"])
    test, _ = mgr.load_dataset(
        frozen.dataset_id,
        split="test",
        approval_id=str(approval_id),
        run_id="CUT1",
        strategy_version="v0.1.0",
    )
    frames.append(test["BTC-USDT"])
    cutoff = _ts("2026-10-12T00:00:00")
    for frame in frames:
        if frame.empty:
            continue
        closes = pd.to_datetime(frame["bar_time"], utc=True) + pd.Timedelta(hours=4)
        assert (closes <= cutoff).all()
        assert (pd.to_datetime(frame["bar_time"], utc=True) < cutoff).all()


def test_split_boundaries_are_utc_midnight_not_mid_4h(tmp_path):
    df = _bars("2026-09-01 00:00", 21 * 6)
    _, frozen = _freeze(tmp_path, df)
    written = _written_manifest(tmp_path, frozen.dataset_id)
    for key in ("validation_start_utc", "test_start_utc"):
        ts = _as_utc(written["split"][key])
        assert ts.hour == 0 and ts.minute == 0 and ts.second == 0
        assert ts.nanosecond == 0


def test_1d_resample_uses_same_utc_midnight_boundaries(tmp_path):
    df_4h = _bars("2026-09-21", 21 * 6)
    _, frozen = _freeze(tmp_path, df_4h)
    written = _written_manifest(tmp_path, frozen.dataset_id)
    val_start = _as_utc(written["split"]["validation_start_utc"])
    test_start = _as_utc(written["split"]["test_start_utc"])

    daily = resample_4h_to_daily(
        df_4h,
        daily_cut="UTC00",
        now=datetime(2026, 10, 12, tzinfo=timezone.utc),
    ).signal_bars
    assert not daily.empty
    daily_times = pd.to_datetime(daily["bar_time"], utc=True)
    assert (daily_times.dt.hour == 0).all()

    def _segment(ts: pd.Timestamp) -> str:
        if ts < val_start:
            return "train"
        if ts < test_start:
            return "val"
        return "test"

    four_h = pd.to_datetime(df_4h["bar_time"], utc=True)
    for day, group in df_4h.assign(_t=four_h).groupby(four_h.dt.normalize()):
        segments = {_segment(ts) for ts in group["_t"]}
        assert len(segments) == 1, f"UTC day {day} split across {segments}"
        day_bar = daily_times[daily_times == day]
        if len(day_bar):
            assert _segment(day_bar.iloc[0]) == next(iter(segments))

    _, frozen_1d = _freeze(
        tmp_path / "daily",
        daily,
        timeframe="1day",
        cutoff=written["data_cutoff_close_utc"],
        validation_start=written["split"]["validation_start_utc"],
        test_start=written["split"]["test_start_utc"],
    )
    daily_manifest = _written_manifest(tmp_path / "daily", frozen_1d.dataset_id)
    assert daily_manifest["split"] == written["split"]
    assert daily_manifest["data_cutoff_close_utc"] == written["data_cutoff_close_utc"]


def test_runner_reads_split_from_manifest_only(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    df = _bars("2026-09-21", 21 * 6)
    datasets = workspace / "data" / "datasets"
    mgr = DatasetManager(datasets)
    write_contract_specs(workspace)
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df}, timeframe="4hour", data_cutoff_close_utc=V010_CUTOFF
    )
    write_batch_manifest(workspace, frozen.dataset_id)
    written = _written_manifest(workspace, frozen.dataset_id)

    config = runner_config(
        workspace,
        "SPLIT001",
        frozen.dataset_id,
        symbols=["BTC-USDT"],
        split="val",
        allow_missing_funding=True,
    )
    runner = write_runner(workspace, config)
    data, loaded = runner._load_dataset()
    times = pd.to_datetime(data["BTC-USDT"]["bar_time"], utc=True)
    val_start = _as_utc(written["split"]["validation_start_utc"])
    test_start = _as_utc(written["split"]["test_start_utc"])
    assert times.min() >= val_start
    assert times.max() < test_start
    assert _as_utc(loaded.validation_start_utc) == val_start
    assert _as_utc(loaded.test_start_utc) == test_start


def test_test_set_lock_uses_manifest_test_hash(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    ledger = LedgerManager(tmp_path / "ledger.db")
    mgr = DatasetManager(
        tmp_path / "data" / "datasets",
        ledger=ledger,
        current_run_id="LOCK1",
    )
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df}, timeframe="4hour", data_cutoff_close_utc=V010_CUTOFF
    )
    written = _written_manifest(tmp_path, frozen.dataset_id)
    assert written["test_hash"]
    from htx_trend.research.ledger import Approval, ApprovalDecision, ApprovalGate

    approval_id = ledger.create_approval(
        Approval(
            approval_id=None,
            gate=ApprovalGate.STAGE_ACCEPTANCE,
            run_id="LOCK1",
            strategy_version="v0.1.0",
            approver="reviewer@example.com",
            decision=ApprovalDecision.APPROVED,
            rationale="lock uses frozen test_hash",
            created_at=datetime.now(timezone.utc),
        )
    )
    mgr.load_dataset(
        frozen.dataset_id,
        split="test",
        approval_id=str(approval_id),
        run_id="LOCK1",
        strategy_version="v0.1.0",
    )
    assert ledger.is_test_set_locked(frozen.manifest_hash, written["test_hash"])


def test_freeze_rejects_stitch_point_after_validation_start(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    with pytest.raises(ValueError, match="stitch"):
        mgr.freeze_dataset(
            {"BTC-USDT": df},
            timeframe="4hour",
            data_cutoff_close_utc=V010_CUTOFF,
            extra_metadata={"stitch_points": {"BTC-USDT": "2026-10-05T00:00:00Z"}},
        )


def test_freeze_accepts_default_stitch_point_inside_train(tmp_path):
    # Sep 2020 through Dec 2020: 2020-11-01 is before the 60% UTC-day mark.
    df = _bars("2020-09-01", 120 * 6)
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df},
        timeframe="4hour",
        data_cutoff_close_utc=V010_CUTOFF,
        extra_metadata={"stitch_points": {"BTC-USDT": "2020-11-01T00:00:00Z"}},
    )
    written = _written_manifest(tmp_path, frozen.dataset_id)
    assert _as_utc(written["split"]["validation_start_utc"]) > _ts("2020-11-01")


def test_cli_freeze_default_cutoff_and_optional_test_start(tmp_path):
    from htx_trend.research.cli import cmd_freeze

    raw = tmp_path / "data" / "raw"
    raw.mkdir(parents=True)
    df = _bars("2026-09-21", 21 * 6)
    df.to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    cmd_freeze(_cli_ns(tmp_path))
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    written = json.loads((dataset_dir / "manifest.json").read_text())
    assert written["data_cutoff_close_utc"].startswith("2026-10-12T00:00:00")
    assert _as_utc(written["split"]["test_start_utc"]).hour == 0


def _cli_ns(tmp_path, **extra):
    payload = {
        "workspace": str(tmp_path),
        "contracts": "BTC-USDT",
        "timeframe": "4hour",
        "train_ratio": 0.6,
        "val_ratio": 0.2,
        "backfill_binance": False,
        "overlap_threshold": None,
        "daily_cut": None,
        "stitch_point": None,
        "stitch_config": None,
        "non_comparable_window": 30,
        "bad_ticks": None,
        "cutoff": "2026-10-12T00:00:00Z",
        "test_start": None,
        "validation_start": None,
        "split_from_manifest": None,
    }
    payload.update(extra)
    return Namespace(**payload)


def test_1d_boundaries_equal_4h_boundaries_via_split_from_manifest(tmp_path):
    from htx_trend.research.cli import cmd_freeze

    df_4h = _bars("2026-09-21", 7 * 6)
    raw = tmp_path / "4h" / "data" / "raw"
    raw.mkdir(parents=True)
    df_4h.to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    cmd_freeze(_cli_ns(tmp_path / "4h"))
    four_h_dir = next(
        path
        for path in (tmp_path / "4h" / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    four_h = json.loads((four_h_dir / "manifest.json").read_text())
    daily = resample_4h_to_daily(
        df_4h, daily_cut="UTC00", now=datetime(2026, 10, 12, tzinfo=timezone.utc)
    ).signal_bars
    assert len(daily) == 7
    daily_ws = tmp_path / "1d"
    raw_1d = daily_ws / "data" / "raw"
    raw_1d.mkdir(parents=True)
    df_4h.to_parquet(raw_1d / "BTC-USDT_4hour_htx.parquet", index=False)
    daily.to_parquet(raw_1d / "BTC-USDT_1day_htx.parquet", index=False)
    cmd_freeze(
        _cli_ns(
            daily_ws,
            timeframe="1day",
            daily_cut="UTC00",
            cutoff=None,
            split_from_manifest=str(four_h_dir / "manifest.json"),
        )
    )
    one_d_dir = next(
        path
        for path in (daily_ws / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    one_d = json.loads((one_d_dir / "manifest.json").read_text())
    assert one_d["split"] == four_h["split"]
    assert one_d["data_cutoff_close_utc"] == four_h["data_cutoff_close_utc"]


def test_cli_test_start_without_validation_start_errors(tmp_path):
    from htx_trend.research.cli import cmd_freeze

    raw = tmp_path / "data" / "raw"
    raw.mkdir(parents=True)
    _bars("2026-09-21", 7 * 6).to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    with pytest.raises(SystemExit, match="validation-start"):
        cmd_freeze(_cli_ns(tmp_path, test_start="2026-09-26T00:00:00Z", validation_start=None))


def test_cli_validation_start_without_test_start_errors(tmp_path):
    from htx_trend.research.cli import cmd_freeze

    raw = tmp_path / "data" / "raw"
    raw.mkdir(parents=True)
    _bars("2026-09-21", 7 * 6).to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    with pytest.raises(SystemExit, match="test-start"):
        cmd_freeze(_cli_ns(tmp_path, validation_start="2026-09-25T00:00:00Z", test_start=None))


def test_cli_cutoff_is_required(tmp_path):
    from htx_trend.research.cli import cmd_freeze

    raw = tmp_path / "data" / "raw"
    raw.mkdir(parents=True)
    _bars("2026-09-21", 21 * 6).to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    with pytest.raises(SystemExit, match="cutoff"):
        cmd_freeze(_cli_ns(tmp_path, cutoff=None))


def test_get_split_raises_index_based_api_removed(tmp_path):
    df = _bars("2026-09-21", 21 * 6)
    _, frozen = _freeze(tmp_path, df)
    with pytest.raises(ValueError, match="index|timestamp|manifest"):
        frozen.get_split("train")


def test_segment_counts_and_hashes_per_symbol_by_timestamp(tmp_path):
    from io import StringIO
    from unittest.mock import patch

    from htx_trend.research.cli import cmd_freeze

    raw = tmp_path / "data" / "raw"
    raw.mkdir(parents=True)
    _bars("2026-09-21", 10 * 6).to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    _bars("2026-09-21", 21 * 6).to_parquet(raw / "ETH-USDT_4hour_htx.parquet", index=False)
    buf = StringIO()
    with patch("sys.stdout", buf):
        cmd_freeze(_cli_ns(tmp_path, contracts="BTC-USDT,ETH-USDT"))
    output = buf.getvalue()
    dataset_dir = next(
        path
        for path in (tmp_path / "data" / "datasets").iterdir()
        if path.is_dir() and path.name != "manifests"
    )
    written = json.loads((dataset_dir / "manifest.json").read_text())
    counts = written["segment_counts"]
    assert counts["BTC-USDT"]["train"] == 10 * 6
    assert counts["BTC-USDT"]["val"] == 0
    assert counts["BTC-USDT"]["test"] == 0
    assert counts["ETH-USDT"]["train"] == 12 * 6
    assert counts["ETH-USDT"]["val"] == 4 * 6
    assert counts["ETH-USDT"]["test"] == 5 * 6
    assert "BTC-USDT: train=60" in output
    assert "ETH-USDT: train=72" in output
    assert "Test: 17832" not in output
    assert written["test_hash"]
    assert "train_end_idx" not in written
    assert "val_end_idx" not in written
    btc = pd.read_parquet(dataset_dir / "BTC-USDT.parquet")
    eth = pd.read_parquet(dataset_dir / "ETH-USDT.parquet")
    start = counts["BTC-USDT"]["train"] + counts["BTC-USDT"]["val"]
    end = len(eth)
    assert len(eth.iloc[start:end]) > 0
    hasher = __import__("hashlib").sha256()
    for symbol, frame in (("BTC-USDT", btc), ("ETH-USDT", eth)):
        sliced = frame.iloc[start:end]
        hasher.update(symbol.encode())
        hasher.update(sliced["bar_time"].astype(str).str.cat().encode())
        hasher.update(sliced["close"].astype(str).str.cat().encode())
    idx_hash = hasher.hexdigest()[:16]
    assert written["test_hash"] != idx_hash
    written_split = written["split"]
    recomputed = DatasetManager(tmp_path / "data" / "datasets")._compute_split_hash_by_time(
        {"BTC-USDT": btc, "ETH-USDT": eth},
        ["BTC-USDT", "ETH-USDT"],
        "test",
        pd.Timestamp(written_split["validation_start_utc"]).to_pydatetime(),
        pd.Timestamp(written_split["test_start_utc"]).to_pydatetime(),
        pd.Timestamp(written["data_cutoff_close_utc"]).to_pydatetime(),
        "4hour",
    )
    assert written["test_hash"] == recomputed
