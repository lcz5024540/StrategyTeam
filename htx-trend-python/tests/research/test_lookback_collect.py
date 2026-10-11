"""P1: lookback is explicit; max paginates HTX and backfills Binance."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd
import pytest

from htx_trend.research.adapters.htx.market_data import HtxMarketDataClient, Kline
from htx_trend.research.cli import main
from htx_trend.research.data.collector import (
    BINANCE_VISION_START,
    DataCollector,
    parse_lookback_days,
)
from tests.research.adapters.test_htx_api_params import _client_with_envelope, _ok_envelope


def _kline(ts: int, close: float = 100.0) -> dict:
    return {
        "id": ts,
        "open": close,
        "high": close + 1,
        "low": close - 1,
        "close": close,
        "vol": 10.0,
        "amount": 1000.0,
    }


def test_lookback_days_required_no_90_default():
    with pytest.raises(ValueError, match="required"):
        parse_lookback_days(None)
    with pytest.raises(ValueError, match="positive"):
        parse_lookback_days(0)
    assert parse_lookback_days("max") == "max"
    assert parse_lookback_days(30) == 30

    root = Path(__file__).resolve().parents[2]
    collector_src = (root / "src/htx_trend/research/data/collector.py").read_text()
    assert "lookback_days: int = 1095" not in collector_src
    cli_src = (root / "src/htx_trend/research/cli.py").read_text()
    assert "default=1095" not in cli_src
    smoke = (root / "scripts/smoke_e2e.sh").read_text()
    assert "LOOKBACK_DAYS:-90" not in smoke

    with patch("sys.argv", ["prog", "collect", "--contracts", "BTC-USDT"]):
        with pytest.raises(SystemExit):
            main()


def test_collect_max_uses_pagination(tmp_path):
    later = [
        _kline(1_704_200_000 + i * 14400, 110 + i) for i in range(2000)
    ]
    earlier = [
        _kline(1_704_200_000 - (2000 - i) * 14400, 90 + i) for i in range(50)
    ]
    client = _client_with_envelope({"status": "ok", "data": later})
    client.client.history_kline_envelope = MagicMock(
        side_effect=[
            {"status": "ok", "data": later},
            {"status": "ok", "data": earlier},
            {"status": "ok", "data": []},
        ]
    )
    result = client.fetch_klines_paginated("BTC-USDT", "4hour", max_pages=5, page_size=2000)
    assert client.client.history_kline_envelope.call_count >= 2
    assert len(result) == 2050
    first_kwargs = client.client.history_kline_envelope.call_args_list[0]
    assert first_kwargs.kwargs.get("start") is not None
    assert first_kwargs.kwargs.get("end") is not None

    class FakePaginated:
        def __init__(self):
            self.calls = []

        def fetch_klines_paginated(self, *args, **kwargs):
            self.calls.append({"args": args, "kwargs": kwargs})
            now = datetime.now(UTC)
            return [
                Kline(
                    bar_time=now - timedelta(hours=4),
                    open=1,
                    high=2,
                    low=0.5,
                    close=1.2,
                    volume=1,
                    amount=1,
                    source="htx",
                    first_seen_at=now,
                )
            ]

        def close(self) -> None:
            return None

    fake = FakePaginated()
    collector = DataCollector(tmp_path / "raw")
    df = collector.collect_htx_klines("BTC-USDT", "4hour", "max", client=fake)
    assert not df.empty
    assert fake.calls
    assert fake.calls[0]["kwargs"].get("max_pages", 0) >= 10


def test_binance_max_starts_2020():
    assert BINANCE_VISION_START == datetime(2020, 1, 1, tzinfo=UTC)


def test_freeze_writes_source_counts(tmp_path):
    from htx_trend.research.cli import cmd_freeze

    workspace = tmp_path / "ws"
    raw = workspace / "data" / "raw"
    raw.mkdir(parents=True)
    times_htx = pd.date_range("2024-06-01", periods=6, freq="4h", tz="UTC")
    times_bn = pd.date_range("2024-01-01", periods=6, freq="4h", tz="UTC")
    htx = pd.DataFrame(
        {
            "bar_time": times_htx,
            "open": 100,
            "high": 101,
            "low": 99,
            "close": 100,
            "volume": 1,
            "amount": 1,
            "source": "htx",
        }
    )
    bn = pd.DataFrame(
        {
            "bar_time": times_bn,
            "open": 90,
            "high": 91,
            "low": 89,
            "close": 90,
            "volume": 1,
            "amount": 1,
            "source": "binance",
        }
    )
    htx.to_parquet(raw / "BTC-USDT_4hour_htx.parquet", index=False)
    bn.to_parquet(raw / "BTCUSDT_4h_binance.parquet", index=False)

    args = type(
        "Args",
        (),
        {
            "workspace": str(workspace),
            "contracts": "BTC-USDT",
            "timeframe": "4hour",
            "train_ratio": 0.6,
            "val_ratio": 0.2,
            "backfill_binance": True,
            "overlap_threshold": 0.003,
            "daily_cut": None,
        },
    )()
    cmd_freeze(args)
    dataset_dirs = list((workspace / "data" / "datasets").glob("4hour_*"))
    assert dataset_dirs
    counts_path = dataset_dirs[0] / "source_counts.json"
    assert counts_path.exists()
    counts = json.loads(counts_path.read_text())
    assert counts["BTC-USDT"]["binance_backfill_bars"] > 0
    assert counts["BTC-USDT"]["htx_bars"] == 6
