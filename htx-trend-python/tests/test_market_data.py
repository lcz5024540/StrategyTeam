from decimal import Decimal

import httpx

from htx_trend.htx_v5 import HtxV5Client
from htx_trend.market_data import (Candle, HtxMarketData, KlineWindow, completed_candles,
                                   period_anchor_seconds, period_seconds)
from htx_trend.research.store import FileEvidenceStore


def candle(open_time: int, close: str = "101") -> Candle:
    return Candle(open_time, Decimal("100"), Decimal("102"), Decimal("99"), Decimal(close), Decimal("10"),
                  Decimal("20"), Decimal("2000"), 4)


def test_period_and_completed_filter_exclude_forming_bar() -> None:
    assert period_seconds("4hour") == 14_400
    assert period_anchor_seconds("1day") == 57_600
    assert completed_candles((candle(0), candle(900), candle(1800)), "15min", now_epoch=2702, close_grace_seconds=3) == (candle(0), candle(900))


def test_window_requires_contiguous_completed_bars() -> None:
    assert KlineWindow("BTC-USDT", "15min", (candle(0), candle(900)), 2).is_complete
    assert not KlineWindow("BTC-USDT", "15min", (candle(0), candle(1800)), 2).is_complete


def test_market_data_requests_one_extra_bar_and_removes_open_bar() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["size"] == "3"
        return httpx.Response(200, json={"status": "ok", "data": [
            {"id": 1800, "open": "1", "high": "2", "low": "0.5", "close": "1.5"},
            {"id": 900, "open": "1", "high": "2", "low": "0.5", "close": "1.5"},
            {"id": 0, "open": "1", "high": "2", "low": "0.5", "close": "1.5"},
        ]})
    client = HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler)))
    result = HtxMarketData(client, close_grace_seconds=3).kline_window("BTC-USDT", "15min", 2, now_epoch=2702)
    assert [item.open_time for item in result.candles] == [0, 900]


def test_market_data_freezes_raw_response_and_normalized_window(tmp_path) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "ok", "data": [
            {"id": 900, "open": "1", "high": "2", "low": ".5", "close": "1.5"},
            {"id": 0, "open": "1", "high": "2", "low": ".5", "close": "1.5"},
        ]})
    with FileEvidenceStore(tmp_path) as store:
        market = HtxMarketData(HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler))),
                               evidence_store=store)
        result = market.kline_window("BTC-USDT", "15min", 2, now_epoch=1800)
        assert result.evidence_status == "COMPLETE"
        assert len(result.evidence_refs) == 2
        assert store.verify(result.evidence_refs[-1])["objects"] == 2


def test_typed_book_contract_and_funding_inputs() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/depth"):
            return httpx.Response(200, json={"status": "ok", "tick": {"bids": [["99.9", "3"]], "asks": [["100.1", "4"]]}})
        if request.url.path.endswith("/swap_contract_info"):
            return httpx.Response(200, json={"status": "ok", "data": [{"contract_code": "BTC-USDT", "contract_size": "0.001", "price_tick": "0.1", "contract_status": 1}]})
        if request.url.path.endswith("/swap_funding_rate"):
            return httpx.Response(200, json={"status": "ok", "data": {"funding_rate": "0.0001", "funding_time": 123}})
        raise AssertionError(request.url.path)
    market = HtxMarketData(HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler))))
    book = market.top_of_book("BTC-USDT")
    assert book.mid == Decimal("100.0")
    assert book.spread_rate == Decimal("0.002")
    assert market.contract_spec("BTC-USDT").contract_size == Decimal("0.001")
    assert market.funding_rate("BTC-USDT").rate == Decimal("0.0001")
