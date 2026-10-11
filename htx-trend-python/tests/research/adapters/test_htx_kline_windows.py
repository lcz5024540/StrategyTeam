"""HTX from/to windows must stay <= 1990 bars; empty pages stop collection."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from unittest.mock import MagicMock

import httpx
import pytest

from htx_trend.htx_v5 import HtxApiError, HtxV5Client
from htx_trend.research.adapters.htx.market_data import (
    SAFE_KLINE_BARS,
    HtxAPIError,
    HtxMarketDataClient,
    kline_window_seconds,
)
from tests.research.adapters.test_htx_api_params import _client_with_envelope


INVALID_FROM_TO = {
    "status": "error",
    "err-code": "bad-request",
    "err-msg": "history kline query with invalid from to",
}

JAN_2020_EMPTY = {"status": "ok", "data": []}

FOUR_H = 4 * 3600
DAY = 24 * 3600
END_TS = 1_704_067_200  # 2024-01-01 UTC


def _bar(ts: int, close: float = 100.0) -> dict:
    return {
        "id": ts,
        "open": close,
        "high": close + 1,
        "low": close - 1,
        "close": close,
        "vol": 1.0,
        "amount": 1.0,
    }


def _recording_client(side_effect):
    client = HtxMarketDataClient()
    client.client.history_kline_envelope = side_effect
    client.client.contract_info = MagicMock(return_value=[])
    return client


def test_safe_window_is_under_2000_bars():
    assert SAFE_KLINE_BARS <= 1990
    assert kline_window_seconds("4hour") == SAFE_KLINE_BARS * FOUR_H
    assert kline_window_seconds("1day") == SAFE_KLINE_BARS * DAY
    assert kline_window_seconds("4hour") < 400 * DAY
    assert kline_window_seconds("4hour") <= 1990 * FOUR_H


def test_paginated_windows_never_span_more_than_1990_bars():
    calls: list[tuple[int, int]] = []

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        calls.append((int(start), int(end)))
        return JAN_2020_EMPTY

    client = _recording_client(envelope)
    client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        to_timestamp=END_TS,
        max_pages=4,
        page_sleep=0,
        empty_pages_to_stop=2,
    )
    assert calls
    for start, end in calls:
        assert end > start
        assert (end - start) / FOUR_H <= 1990
        assert (end - start) / FOUR_H <= SAFE_KLINE_BARS


def test_400_day_4h_lookback_is_split_not_rejected():
    """400 days of 4h is 2400 bars; HTX rejects that as one from/to window."""
    start_ts = END_TS - 400 * DAY
    calls: list[tuple[int, int]] = []

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        calls.append((int(start), int(end)))
        span_bars = (int(end) - int(start)) / FOUR_H
        if span_bars > 2000:
            return INVALID_FROM_TO
        cursor = int(start)
        data = []
        while cursor <= int(end) and len(data) < 3:
            data.append(_bar(cursor))
            cursor += FOUR_H
        return {"status": "ok", "data": data}

    client = _recording_client(envelope)
    result = client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        from_timestamp=start_ts,
        to_timestamp=END_TS,
        max_pages=10,
        page_sleep=0,
    )
    assert len(calls) >= 2
    for start, end in calls:
        assert (end - start) / FOUR_H <= 1990
    assert result
    times = [int(k.bar_time.timestamp()) for k in result]
    assert len(times) == len(set(times))


def test_boundary_bars_are_deduped():
    edge = END_TS - SAFE_KLINE_BARS * FOUR_H
    pages = [
        {"status": "ok", "data": [_bar(edge), _bar(END_TS)]},
        {"status": "ok", "data": [_bar(edge - FOUR_H), _bar(edge)]},
        JAN_2020_EMPTY,
        JAN_2020_EMPTY,
    ]

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        return pages.pop(0) if pages else JAN_2020_EMPTY

    client = _recording_client(envelope)
    result = client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        to_timestamp=END_TS,
        max_pages=5,
        page_sleep=0,
        empty_pages_to_stop=2,
    )
    times = [int(k.bar_time.timestamp()) for k in result]
    assert times.count(edge) == 1
    assert len(times) == len(set(times))


def test_empty_ok_pages_stop_after_two_consecutive():
    calls: list[tuple[int, int]] = []

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        calls.append((int(start), int(end)))
        return JAN_2020_EMPTY

    client = _recording_client(envelope)
    result = client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        to_timestamp=END_TS,
        max_pages=10,
        page_sleep=0,
        empty_pages_to_stop=2,
    )
    assert result == []
    assert len(calls) == 2


def test_data_then_two_empty_pages_does_not_keep_walking():
    calls = {"n": 0}

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        calls["n"] += 1
        if calls["n"] == 1:
            return {"status": "ok", "data": [_bar(int(end) - FOUR_H)]}
        return JAN_2020_EMPTY

    client = _recording_client(envelope)
    result = client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        to_timestamp=END_TS,
        max_pages=10,
        page_sleep=0,
        empty_pages_to_stop=2,
    )
    assert len(result) == 1
    assert calls["n"] == 3


def test_stops_before_contract_listing():
    listed = END_TS - 10 * FOUR_H
    calls: list[tuple[int, int]] = []

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        calls.append((int(start), int(end)))
        return {"status": "ok", "data": [_bar(int(end) - FOUR_H)]}

    client = _recording_client(envelope)
    client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        to_timestamp=END_TS,
        listed_from=datetime.fromtimestamp(listed, UTC),
        max_pages=10,
        page_sleep=0,
    )
    assert calls
    for start, _end in calls:
        assert start >= listed


def test_hyphenated_invalid_from_to_error_payload():
    client = _client_with_envelope(INVALID_FROM_TO)
    with pytest.raises(HtxAPIError) as exc_info:
        client.fetch_klines(
            "BTC-USDT",
            "4hour",
            from_timestamp=END_TS - 400 * DAY,
            to_timestamp=END_TS,
        )
    assert exc_info.value.err_code == "bad-request"
    assert exc_info.value.err_msg == "history kline query with invalid from to"
    message = str(exc_info.value)
    assert "bad-request" in message
    assert "history kline query with invalid from to" in message


def test_v5_request_includes_hyphenated_err_code_and_msg():
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=INVALID_FROM_TO)

    client = HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler)))
    with pytest.raises(HtxApiError) as exc_info:
        client.history_kline_envelope(
            "BTC-USDT",
            "4hour",
            start=END_TS - 400 * DAY,
            end=END_TS,
        )
    message = str(exc_info.value)
    assert "bad-request" in message
    assert "history kline query with invalid from to" in message
    assert getattr(exc_info.value, "err_code", None) == "bad-request"
    assert getattr(exc_info.value, "err_msg", None) == "history kline query with invalid from to"


def test_funding_pagination_stops_after_consecutive_empty_pages():
    client = HtxMarketDataClient()
    pages = [
        [
            {
                "symbol": "BTC-USDT",
                "funding_time": END_TS * 1000,
                "funding_rate": "0.0001",
            }
        ],
        [],
        [],
        [
            {
                "symbol": "BTC-USDT",
                "funding_time": (END_TS - 8 * 3600) * 1000,
                "funding_rate": "0.0002",
            }
        ],
    ]
    seen = {"n": 0}

    def funding_history(contract_code, page_size=50, page_index=1):
        seen["n"] += 1
        if not pages:
            return []
        return pages.pop(0)

    client.client.funding_history = funding_history
    rates = client.fetch_funding_history_paginated(
        "BTC-USDT",
        max_pages=10,
        page_size=50,
        empty_pages_to_stop=2,
        page_sleep=0,
    )
    assert len(rates) == 1
    assert seen["n"] == 3


def test_funding_pagination_continues_until_exhausted():
    """BTC-USDT has ~131 pages / 6544 rows; default must not stop at 20 pages."""
    client = HtxMarketDataClient()
    page_count = 25
    per_page = 2
    seen = {"n": 0}

    def funding_history(contract_code, page_size=50, page_index=1):
        seen["n"] += 1
        if page_index > page_count:
            return []
        base = END_TS - (page_index - 1) * 8 * 3600 * per_page
        return [
            {
                "symbol": "BTC-USDT",
                "funding_time": (base - i * 8 * 3600) * 1000,
                "funding_rate": "0.0001",
            }
            for i in range(per_page)
        ]

    client.client.funding_history = funding_history
    rates = client.fetch_funding_history_paginated(
        "BTC-USDT",
        page_size=50,
        page_sleep=0,
    )
    assert seen["n"] == page_count + 2
    assert len(rates) == page_count * per_page
    earliest = min(row.funding_time for row in rates)
    assert earliest < datetime(2024, 1, 1, tzinfo=UTC)


def test_funding_pagination_end_time_does_not_count_filtered_pages_as_empty():
    """Newest API pages after end_time must not trip the empty-page stop."""
    client = HtxMarketDataClient()
    now = datetime(2026, 10, 11, 16, tzinfo=UTC)
    end_time = now - timedelta(days=90)
    earliest = datetime(2020, 10, 21, 16, tzinfo=UTC)
    page_count = 131
    seen = {"n": 0}

    def funding_history(contract_code, page_size=50, page_index=1):
        seen["n"] += 1
        if page_index > page_count:
            return []
        span = (now - earliest).total_seconds()
        stamp = now - timedelta(seconds=span * (page_index - 1) / (page_count - 1))
        return [
            {
                "symbol": "BTC-USDT",
                "funding_time": int(stamp.timestamp() * 1000),
                "funding_rate": "0.0001",
            }
        ]

    client.client.funding_history = funding_history
    rates = client.fetch_funding_history_paginated(
        "BTC-USDT",
        end_time=end_time,
        page_sleep=0,
    )
    assert seen["n"] == page_count + 2
    assert min(row.funding_time for row in rates) == earliest
    assert max(row.funding_time for row in rates) <= end_time


def test_kline_pagination_empty_stop_uses_raw_api_window():
    """Kline stop must use raw empty windows, not a post-filter of bars."""
    calls = []

    def envelope(contract_code, period, size=None, *, start=None, end=None):
        calls.append((start, end))
        if start is None or end is None:
            return {"status": "ok", "data": [_bar(END_TS)]}
        if start >= END_TS - 8 * FOUR_H:
            return {"status": "ok", "data": [_bar(END_TS - FOUR_H)]}
        return {"status": "ok", "data": []}

    client = _recording_client(envelope)
    start = datetime(2020, 10, 21, 16, tzinfo=UTC)
    client.fetch_klines_paginated(
        "BTC-USDT",
        "4hour",
        start_time=start,
        end_time=datetime.fromtimestamp(END_TS, UTC),
        max_pages=20,
        page_size=2,
        empty_pages_to_stop=2,
        page_sleep=0,
    )
    assert len(calls) > 2


def test_fetch_contract_info_raises_on_exception():
    client = HtxMarketDataClient()
    client.client.contract_info = MagicMock(side_effect=RuntimeError("htx down"))
    with pytest.raises(RuntimeError, match="htx down"):
        client.fetch_contract_info("BTC-USDT")


def test_fetch_contract_info_defaults_min_order_to_htx_constant():
    from htx_trend.research.adapters.htx.market_data import HTX_MIN_ORDER_CONTRACTS

    assert HTX_MIN_ORDER_CONTRACTS == 1
    client = HtxMarketDataClient()
    client.client.contract_info = MagicMock(
        return_value=[
            {
                "contract_code": "BTC-USDT",
                "symbol": "BTC",
                "contract_size": "0.001",
                "price_tick": "0.1",
                "create_date": 0,
            }
        ]
    )
    spec = client.fetch_contract_info("BTC-USDT")
    assert spec.min_order_size == HTX_MIN_ORDER_CONTRACTS
    source = (
        __import__("pathlib").Path(__file__).resolve().parents[3]
        / "src/htx_trend/research/adapters/htx/market_data.py"
    ).read_text()
    assert "HTX_MIN_ORDER_CONTRACTS" in source
    assert "contract_info has no such field" in source
