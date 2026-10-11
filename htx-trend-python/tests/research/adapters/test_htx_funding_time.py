"""HTX funding_time is milliseconds; parse errors must not be swallowed."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from htx_trend.research.adapters.htx.market_data import HtxMarketDataClient


REAL_MS = 1791763200000  # 2026-10-12 00:00:00 UTC


def test_htx_funding_time_real_format_milliseconds():
    client = HtxMarketDataClient()
    client.client.funding_history = lambda *args, **kwargs: [
        {
            "symbol": "BTC-USDT",
            "funding_time": REAL_MS,
            "funding_rate": "0.0001",
            "realized_rate": "0.0001",
        }
    ]
    rates = client.fetch_funding_history("BTC-USDT", strict=False)
    assert len(rates) == 1
    assert rates[0].funding_time == datetime(2026, 10, 12, 0, 0, tzinfo=UTC)


def test_htx_funding_time_string_milliseconds():
    client = HtxMarketDataClient()
    client.client.funding_history = lambda *args, **kwargs: [
        {
            "symbol": "BTC-USDT",
            "funding_time": str(REAL_MS),
            "funding_rate": "0.0001",
        }
    ]
    rates = client.fetch_funding_history("BTC-USDT", strict=False)
    assert rates[0].funding_time == datetime(2026, 10, 12, 0, 0, tzinfo=UTC)


def test_htx_funding_time_parse_error_not_swallowed():
    client = HtxMarketDataClient()
    client.client.funding_history = lambda *args, **kwargs: [
        {
            "symbol": "BTC-USDT",
            "funding_time": REAL_MS * 1_000_000,
            "funding_rate": "0.0001",
        }
    ]
    with pytest.raises((OverflowError, OSError, ValueError)):
        client.fetch_funding_history("BTC-USDT", strict=False)
