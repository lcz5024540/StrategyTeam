"""E2E collector tests on the real ContinuousCollector path."""

from __future__ import annotations

import asyncio
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd
from htx_trend.research.adapters.htx.market_data import Kline
from htx_trend.research.collectors.continuous_collector import ContinuousCollector
from htx_trend.research.data.immutable_storage import ImmutableKlineStorage


def _kline(bar_time: datetime, close: float = 100.0) -> Kline:
    return Kline(
        bar_time=bar_time,
        open=close,
        high=close + 1,
        low=close - 1,
        close=close,
        volume=10,
        amount=1000,
        source="htx",
        first_seen_at=bar_time,
    )


class FakeHtxClient:
    def __init__(self, by_period: dict[tuple[str, str], list[Kline]]):
        self.by_period = by_period
        self.calls: list[dict] = []

    def fetch_klines(self, contract_code, period, size=2000, from_timestamp=None, to_timestamp=None, max_retries=3):
        self.calls.append(
            {
                "contract_code": contract_code,
                "period": period,
                "from_timestamp": from_timestamp,
                "to_timestamp": to_timestamp,
            }
        )
        if from_timestamp is None or to_timestamp is None:
            raise AssertionError("HTX request must send from AND to")
        return list(self.by_period.get((contract_code, period), []))

    def close(self) -> None:
        return None


def _collector(tmp_path: Path, client: FakeHtxClient, intervals=None) -> ContinuousCollector:
    return ContinuousCollector(
        storage_dir=tmp_path / "klines",
        state_file=tmp_path / "state.json",
        symbols=["BTC-USDT", "ETH-USDT"],
        intervals=intervals or ["4hour", "1day"],
        market_client=client,
    )


def test_unclosed_bar_filtered_before_append(tmp_path):
    now = datetime.now(timezone.utc)
    closed = now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=8)
    closed = closed.replace(hour=(closed.hour // 4) * 4)
    unclosed = now.replace(minute=0, second=0, microsecond=0)
    unclosed = unclosed.replace(hour=(unclosed.hour // 4) * 4)
    client = FakeHtxClient(
        {
            ("BTC-USDT", "4hour"): [_kline(closed, 100), _kline(unclosed, 101)],
            ("BTC-USDT", "1day"): [],
            ("ETH-USDT", "4hour"): [],
            ("ETH-USDT", "1day"): [],
        }
    )
    collector = _collector(tmp_path, client, intervals=["4hour", "1day"])
    asyncio.run(collector.collect_once())

    stored = collector.storage.load_all_batches("BTC-USDT", "4hour")
    assert not stored.empty
    assert all(pd.to_datetime(stored["bar_time"], utc=True) < now)
    times = pd.to_datetime(stored["bar_time"], utc=True)
    assert unclosed not in set(times)


def test_collect_once_collects_1day(tmp_path):
    now = datetime.now(timezone.utc)
    day = (now - timedelta(days=2)).replace(hour=16, minute=0, second=0, microsecond=0)
    client = FakeHtxClient(
        {
            ("BTC-USDT", "4hour"): [],
            ("ETH-USDT", "4hour"): [],
            ("BTC-USDT", "1day"): [_kline(day, 200)],
            ("ETH-USDT", "1day"): [_kline(day, 3000)],
        }
    )
    collector = _collector(tmp_path, client)
    asyncio.run(collector.collect_once())

    btc_1d = collector.storage.load_all_batches("BTC-USDT", "1day")
    assert not btc_1d.empty
    assert "BTC-USDT::1day" in collector.states
    assert collector.states["BTC-USDT::1day"].total_bars_collected >= 1


def test_htx_requests_send_from_and_to(tmp_path):
    now = datetime.now(timezone.utc)
    closed = now - timedelta(hours=8)
    closed = closed.replace(minute=0, second=0, microsecond=0)
    client = FakeHtxClient({("BTC-USDT", "4hour"): [_kline(closed)]})
    collector = ContinuousCollector(
        storage_dir=tmp_path / "klines",
        state_file=tmp_path / "state.json",
        symbols=["BTC-USDT"],
        intervals=["4hour", "1day"],
        market_client=client,
    )
    asyncio.run(collector.collect_once())
    assert client.calls
    for call in client.calls:
        assert call["from_timestamp"] is not None
        assert call["to_timestamp"] is not None


def test_cross_batch_gap_triggers_backfill(tmp_path):
    last = datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc)
    storage = ImmutableKlineStorage(tmp_path / "klines")
    storage.append_batch(
        "BTC-USDT",
        "4hour",
        pd.DataFrame(
            [{
                "bar_time": last,
                "open": 100,
                "high": 101,
                "low": 99,
                "close": 100,
                "volume": 1,
            }]
        ),
    )
    gap_fill = last + timedelta(hours=4)
    new_first = last + timedelta(hours=12)
    client = FakeHtxClient({})

    def fetch(contract_code, period, size=2000, from_timestamp=None, to_timestamp=None, max_retries=3):
        client.calls.append(
            {
                "from_timestamp": from_timestamp,
                "to_timestamp": to_timestamp,
                "period": period,
            }
        )
        if from_timestamp == int(last.timestamp()) or (
            from_timestamp and to_timestamp and to_timestamp - from_timestamp <= 12 * 3600
        ):
            return [_kline(gap_fill, 100.5)]
        return [_kline(new_first, 102)]

    client.fetch_klines = fetch
    collector = ContinuousCollector(
        storage_dir=tmp_path / "klines",
        state_file=tmp_path / "state.json",
        symbols=["BTC-USDT"],
        intervals=["4hour"],
        market_client=client,
    )
    asyncio.run(collector.collect_once())
    state = collector.states["BTC-USDT::4hour"]
    assert state.gaps_detected >= 1
    assert state.gaps_filled >= 1
    stored = collector.storage.load_all_batches("BTC-USDT", "4hour")
    times = set(pd.to_datetime(stored["bar_time"], utc=True))
    assert pd.Timestamp(gap_fill) in times or any(
        abs((t - pd.Timestamp(gap_fill)).total_seconds()) < 1 for t in times
    )


def test_collector_state_write_atomic(tmp_path):
    now = datetime.now(timezone.utc) - timedelta(hours=8)
    now = now.replace(minute=0, second=0, microsecond=0)
    client = FakeHtxClient({("BTC-USDT", "4hour"): [_kline(now)]})
    collector = ContinuousCollector(
        storage_dir=tmp_path / "klines",
        state_file=tmp_path / "state.json",
        symbols=["BTC-USDT"],
        intervals=["4hour"],
        market_client=client,
    )
    asyncio.run(collector.collect_once())
    assert collector.state_file.exists()
    payload = json.loads(collector.state_file.read_text())
    assert "BTC-USDT::4hour" in payload
    leftovers = list(tmp_path.glob(".collector_state.*.tmp"))
    assert leftovers == []
