"""P0-3: timeframe-aware resume and cross-batch gap detection."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

from htx_trend.research.collectors.continuous_collector import ContinuousCollector
from htx_trend.research.data.immutable_storage import ImmutableKlineStorage


def _make_collector(tmp_path: Path, interval: str) -> ContinuousCollector:
    storage_dir = tmp_path / "klines"
    state_file = tmp_path / "collector_state.json"
    return ContinuousCollector(
        storage_dir=storage_dir,
        state_file=state_file,
        symbols=["BTC-USDT"],
        interval=interval,
    )


def _closed_bar(bar_time: datetime, close: float = 100.0) -> dict:
    return {
        "bar_time": bar_time,
        "open": close,
        "high": close + 1.0,
        "low": close - 1.0,
        "close": close,
        "volume": 1000.0,
    }


def test_resume_1day_interval(tmp_path):
    collector = _make_collector(tmp_path, "1day")
    last = datetime(2026, 1, 1, tzinfo=timezone.utc)
    collector.states["BTC-USDT"].last_bar_time = last.isoformat()

    resume = collector._load_resume_time("BTC-USDT")

    assert resume == last + timedelta(days=1)
    assert resume - last == timedelta(days=1)


def test_resume_4hour_interval(tmp_path):
    collector = _make_collector(tmp_path, "4hour")
    last = datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc)
    collector.states["BTC-USDT"].last_bar_time = last.isoformat()

    resume = collector._load_resume_time("BTC-USDT")

    assert resume == last + timedelta(hours=4)
    assert resume - last == timedelta(hours=4)


def test_cross_batch_gap_detection(tmp_path):
    collector = _make_collector(tmp_path, "4hour")
    last = datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc)
    collector.storage.append_batch(
        "BTC-USDT",
        "4hour",
        pd.DataFrame([_closed_bar(last)]),
    )

    new_first = last + timedelta(hours=12)
    new_batch = pd.DataFrame([_closed_bar(new_first, close=101.0)])

    gaps = collector._detect_cross_batch_gap("BTC-USDT", new_batch)

    assert len(gaps) == 1
    assert gaps[0]["missing_bars"] == 2
    assert gaps[0]["gap_hours"] == 8.0
    assert gaps[0]["symbol"] == "BTC-USDT"
    assert gaps[0]["interval"] == "4hour"


def test_no_gap_continuous_batches(tmp_path):
    collector = _make_collector(tmp_path, "4hour")
    last = datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc)
    collector.storage.append_batch(
        "BTC-USDT",
        "4hour",
        pd.DataFrame([_closed_bar(last)]),
    )

    new_first = last + timedelta(hours=4)
    new_batch = pd.DataFrame([_closed_bar(new_first, close=101.0)])

    gaps = collector._detect_cross_batch_gap("BTC-USDT", new_batch)

    assert gaps == []


def test_gap_events_logged(tmp_path):
    collector = _make_collector(tmp_path, "1day")
    last = datetime(2026, 1, 1, tzinfo=timezone.utc)
    collector.storage.append_batch(
        "BTC-USDT",
        "1day",
        pd.DataFrame([_closed_bar(last)]),
    )

    new_first = last + timedelta(days=3)
    new_batch = pd.DataFrame([_closed_bar(new_first, close=102.0)])

    gaps = collector._detect_cross_batch_gap("BTC-USDT", new_batch)
    assert len(gaps) == 1

    gap_log = collector.storage_dir / "gap_events.jsonl"
    assert gap_log.exists()
    event = json.loads(gap_log.read_text().strip().splitlines()[-1])
    assert event["symbol"] == "BTC-USDT"
    assert event["interval"] == "1day"
    assert event["missing_bars"] == 2
    assert event["gap_hours"] == 48.0
