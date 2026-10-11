"""P1-6: HTX kline API from/to params and error handling."""

from __future__ import annotations

from unittest.mock import MagicMock

import httpx
import pytest

from htx_trend.research.adapters.htx.market_data import HtxAPIError, HtxMarketDataClient


def _ok_envelope(n: int = 1) -> dict:
    return {
        "status": "ok",
        "data": [
            {
                "id": 1704067200 + i * 14400,
                "open": 100.0,
                "high": 101.0,
                "low": 99.0,
                "close": 100.5,
                "vol": 10.0,
                "amount": 1000.0,
            }
            for i in range(n)
        ],
    }


def _client_with_envelope(envelope_or_error) -> HtxMarketDataClient:
    client = HtxMarketDataClient()
    mock = MagicMock()
    if isinstance(envelope_or_error, Exception):
        mock.side_effect = envelope_or_error
    else:
        mock.return_value = envelope_or_error
    client.client.history_kline_envelope = mock
    return client


def test_fetch_klines_with_from_and_to():
    client = _client_with_envelope(_ok_envelope())
    result = client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        from_timestamp=1704067200,
        to_timestamp=1704153600,
    )
    assert len(result) == 1
    client.client.history_kline_envelope.assert_called_once()
    kwargs = client.client.history_kline_envelope.call_args
    assert kwargs.kwargs.get("start") == 1704067200
    assert kwargs.kwargs.get("end") == 1704153600


def test_fetch_klines_handles_api_error():
    client = _client_with_envelope(
        {"status": "error", "err-code": "invalid-parameter", "err-msg": "bad period"}
    )
    with pytest.raises(HtxAPIError, match="invalid-parameter"):
        client.fetch_klines("BTC-USDT", "4hour", size=10)


def test_fetch_klines_parses_error_code():
    client = _client_with_envelope(
        {"status": "error", "err-code": "bad-request", "err-msg": "size too large"}
    )
    with pytest.raises(HtxAPIError) as exc_info:
        client.fetch_klines("ETH-USDT", "1day")
    assert exc_info.value.err_code == "bad-request"
    assert exc_info.value.err_msg == "size too large"
    assert "bad-request" in str(exc_info.value)
    assert "size too large" in str(exc_info.value)


def test_fetch_klines_respects_size_limit():
    client = _client_with_envelope(_ok_envelope())
    client.fetch_klines("BTC-USDT", "4hour", size=5000)
    args, kwargs = client.client.history_kline_envelope.call_args
    # size is positional third arg
    assert args[2] == 2000


def test_fetch_klines_network_error_handling():
    client = _client_with_envelope(httpx.ConnectError("connection refused"))
    with pytest.raises(HtxAPIError, match="Network error"):
        client.fetch_klines("BTC-USDT", "4hour", size=10, max_retries=1)
