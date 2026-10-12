"""Funding settlement gaps must be registered; file hash enters the freeze manifest."""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime
from pathlib import Path

import pandas as pd
import pytest
import yaml

from htx_trend.research.data.dataset import DatasetManager
from htx_trend.research.data.quality import (
    EXPECTED_FUNDING_GAPS_SHA256,
    FundingGap,
    default_funding_gaps_path,
    funding_gaps_hash,
    load_funding_gaps,
    require_funding_settlement_gaps,
    utc_iso_z,
)
from htx_trend.research.features.funding_asof import attach_funding_as_of


def _times(*stamps: str) -> list[pd.Timestamp]:
    return [pd.Timestamp(item, tz="UTC") for item in stamps]


def _funding(times, rate: float = 0.0001) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "funding_time": pd.to_datetime(times, utc=True),
            "funding_rate": [rate] * len(times),
        }
    )


def _bars(start: str, n: int = 4) -> pd.DataFrame:
    index = pd.date_range(start, periods=n, freq="4h", tz="UTC")
    return pd.DataFrame({"bar_time": index, "close": 100.0})


def test_packaged_funding_gaps_file_is_byte_identical_and_123_entries():
    path = default_funding_gaps_path()
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == EXPECTED_FUNDING_GAPS_SHA256
    payload = yaml.safe_load(path.read_text())
    assert list(payload) == ["gaps"]
    assert len(payload["gaps"]) == 123
    symbols = {row["symbol"] for row in payload["gaps"]}
    assert {"BTC-USDT", "ETH-USDT"}.issubset(symbols)
    assert len(payload["gaps"]) == 123


def test_missing_funding_gaps_file_raises(tmp_path):
    missing = tmp_path / "missing.yaml"
    with pytest.raises(ValueError, match="funding gaps file not found"):
        load_funding_gaps(missing)
    with pytest.raises(ValueError, match="funding gaps file not found"):
        funding_gaps_hash(missing)


def test_deleted_ten_day_funding_gap_raises():
    times = pd.date_range("2024-01-01", periods=40, freq="8h", tz="UTC")
    # drop 10 days (30 settlements) from the middle
    kept = list(times[:3]) + list(times[3 + 30 :])
    with pytest.raises(ValueError, match="unregistered funding gap"):
        require_funding_settlement_gaps("BTC-USDT", kept, gaps=[])


def test_registered_gap_passes_without_inventing_fill():
    prev = pd.Timestamp("2024-01-02T00:00:00Z")
    nxt = pd.Timestamp("2024-01-12T00:00:00Z")
    before = pd.date_range("2024-01-01T00:00:00Z", prev, freq="8h", tz="UTC")
    after = pd.date_range(nxt, "2024-01-13T00:00:00Z", freq="8h", tz="UTC")
    times = list(before) + list(after)
    gaps = [
        FundingGap(
            symbol="BTC-USDT",
            prev=prev,
            next=nxt,
            reason="fixture 10-day hole",
            hours=240,
            split="train",
        )
    ]
    require_funding_settlement_gaps("BTC-USDT", times, gaps)
    bars = _bars("2024-01-01", n=6)
    out = attach_funding_as_of(
        bars,
        _funding(times),
        "4hour",
        symbol="BTC-USDT",
        gaps=gaps,
    )
    assert not out["funding_rate"].isna().any()


def test_registered_gap_missing_from_data_in_range_raises():
    times = pd.date_range("2024-01-01", periods=12, freq="8h", tz="UTC")
    gaps = [
        FundingGap(
            symbol="BTC-USDT",
            prev=datetime(2024, 1, 1, 8, 0, tzinfo=UTC),
            next=datetime(2024, 1, 1, 20, 0, tzinfo=UTC),
            reason="should exist but data is continuous 8h",
        )
    ]
    with pytest.raises(ValueError, match="registered funding gap missing"):
        require_funding_settlement_gaps("BTC-USDT", times, gaps)


def test_registry_entries_outside_range_or_other_symbols_are_ignored():
    times = pd.date_range("2024-01-01", periods=6, freq="8h", tz="UTC")
    gaps = load_funding_gaps()
    # real registry is 2021-2023; 2024 fixture range ignores those rows
    require_funding_settlement_gaps("BTC-USDT", times, gaps)
    require_funding_settlement_gaps("SOL-USDT", times, gaps)


def test_bars_more_than_one_nominal_interval_after_last_settlement_raise():
    funding = _funding(_times("2024-01-01T00:00:00Z", "2024-01-01T08:00:00Z"))
    bars = pd.DataFrame(
        {
            "bar_time": [pd.Timestamp("2024-01-01 20:00", tz="UTC")],
            "close": [100.0],
        }
    )
    with pytest.raises(ValueError, match="after last settlement"):
        attach_funding_as_of(bars, funding, "4hour", symbol="BTC-USDT", gaps=[])


def test_unregistered_non_nominal_interval_raises_even_if_under_8h():
    times = _times("2024-01-01T00:00:00Z", "2024-01-01T07:00:00Z")
    with pytest.raises(ValueError, match="unregistered funding gap"):
        require_funding_settlement_gaps("SHIB-USDT", times, gaps=[])


def test_exact_symbol_prev_next_match_is_required():
    times = _times("2024-01-01T00:00:00Z", "2024-01-01T10:00:00Z")
    gaps = [
        FundingGap(
            symbol="BTC-USDT",
            prev=datetime(2024, 1, 1, 0, 0, tzinfo=UTC),
            next=datetime(2024, 1, 1, 10, 0, tzinfo=UTC),
            reason="ok",
        )
    ]
    require_funding_settlement_gaps("BTC-USDT", times, gaps)
    with pytest.raises(ValueError, match="unregistered funding gap"):
        require_funding_settlement_gaps("ETH-USDT", times, gaps)


def test_freeze_manifest_hash_includes_funding_gaps_sha(tmp_path):
    times = pd.date_range("2020-09-01", periods=110 * 6, freq="4h", tz="UTC")
    df = pd.DataFrame(
        {
            "bar_time": times,
            "open": 1.0,
            "high": 1.0,
            "low": 1.0,
            "close": 1.0,
            "volume": 1.0,
            "amount": 1.0,
            "source": "htx",
        }
    )
    mgr = DatasetManager(tmp_path / "data" / "datasets")
    frozen = mgr.freeze_dataset(
        {"BTC-USDT": df},
        timeframe="4hour",
        data_cutoff_close_utc="2020-12-15T00:00:00Z",
        validation_start_utc="2020-11-20T00:00:00Z",
        test_start_utc="2020-12-01T00:00:00Z",
        extra_metadata={
            "non_comparable_window_bars": 30,
            "stitch_points": {"BTC-USDT": None},
        },
    )
    written = json_load(tmp_path / "data" / "datasets" / frozen.dataset_id / "manifest.json")
    assert written["funding_gaps_hash"] == "sha256:" + EXPECTED_FUNDING_GAPS_SHA256
    hashed = {
        key: written[key]
        for key in (
            "symbols",
            "timeframe",
            "start_date",
            "end_date",
            "total_bars",
            "data_cutoff_close_utc",
            "split",
            "non_comparable_window_bars",
            "stitch_points",
            "funding_gaps_hash",
        )
    }
    digest = hashlib.sha256(__import__("json").dumps(hashed, sort_keys=True).encode()).hexdigest()[:16]
    assert frozen.manifest_hash == digest
    hashed["funding_gaps_hash"] = "sha256:" + ("0" * 64)
    other = hashlib.sha256(__import__("json").dumps(hashed, sort_keys=True).encode()).hexdigest()[:16]
    assert other != frozen.manifest_hash


def json_load(path: Path) -> dict:
    return __import__("json").loads(path.read_text())


def test_funding_gaps_hash_helper_matches_file_bytes():
    assert funding_gaps_hash() == "sha256:" + EXPECTED_FUNDING_GAPS_SHA256
    assert utc_iso_z(datetime(2021, 2, 19, 8, 0, tzinfo=UTC)) == "2021-02-19T08:00:00Z"
