from __future__ import annotations

import base64
import hashlib
import hmac
import json
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from htx_trend.htx_v5 import HtxV5Client, LiveTradingDisabled, SwapOrderRequest


def test_public_kline_request_has_no_credentials() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.hbdm.com"
        assert request.url.path == "/linear-swap-ex/market/history/kline"
        assert dict(request.url.params) == {"contract_code": "BTC-USDT", "period": "15min", "size": "200"}
        assert "AccessKeyId" not in request.url.params
        return httpx.Response(200, json={"status": "ok", "data": [{"id": 1}]})

    client = HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert client.history_kline("BTC-USDT", "15min", 200) == [{"id": 1}]


FROZEN_TS = "2020-01-01T00:00:00"
FROZEN_QUERY = {
    "AccessKeyId": "access",
    "SignatureMethod": "HmacSHA256",
    "SignatureVersion": "2",
    "Timestamp": FROZEN_TS,
    "contract_code": "BTC-USDT",
}
FROZEN_CANONICAL = (
    "AccessKeyId=access&SignatureMethod=HmacSHA256&SignatureVersion=2"
    "&Timestamp=2020-01-01T00%3A00%3A00&contract_code=BTC-USDT"
)
FROZEN_PAYLOAD = (
    "GET\napi.hbdm.com\n/v5/trade/position/opens\n" + FROZEN_CANONICAL
)
FROZEN_SIGNATURE = "TDKfA3h5Z1rWATexPHFh0ezEiMpvkPrtwImQ/dwvgbE="


def test_signed_payload_uses_real_newlines_not_literal_backslash_n() -> None:
    payload = HtxV5Client.signature_payload(
        "GET", "api.hbdm.com", "/v5/trade/position/opens", FROZEN_QUERY
    )
    assert "\n" in payload
    assert "\\n" not in payload
    assert payload.count("\n") == 3
    lines = payload.splitlines()
    assert len(lines) == 4
    assert lines == [
        "GET",
        "api.hbdm.com",
        "/v5/trade/position/opens",
        FROZEN_CANONICAL,
    ]
    assert payload == FROZEN_PAYLOAD
    broken = (
        "GET\\napi.hbdm.com\\n/v5/trade/position/opens\\n" + FROZEN_CANONICAL
    )
    assert payload != broken
    assert HtxV5Client.sign_query(
        "GET", "api.hbdm.com", "/v5/trade/position/opens", "secret", FROZEN_QUERY
    ) == FROZEN_SIGNATURE


def test_private_request_uses_htx_canonical_hmac_signature(monkeypatch) -> None:
    from datetime import datetime as real_datetime

    class FrozenDateTime(real_datetime):
        @classmethod
        def now(cls, tz=None):
            return real_datetime(2020, 1, 1, tzinfo=tz)

    monkeypatch.setattr("htx_trend.htx_v5.datetime", FrozenDateTime)

    def handler(request: httpx.Request) -> httpx.Response:
        query = parse_qs(urlparse(str(request.url)).query)
        signature = query.pop("Signature")[0]
        flattened = {key: values[0] for key, values in query.items()}
        canonical = HtxV5Client.canonical_query(flattened)
        payload = HtxV5Client.signature_payload(
            "GET", "example.test", "/v5/trade/position/opens", flattened
        )
        assert "\n" in payload
        assert "\\n" not in payload
        assert len(payload.splitlines()) == 4
        expected = base64.b64encode(
            hmac.new(b"secret", payload.encode(), hashlib.sha256).digest()
        ).decode()
        assert signature == expected
        assert flattened["AccessKeyId"] == "access"
        assert flattened["contract_code"] == "BTC-USDT"
        assert flattened["Timestamp"] == FROZEN_TS
        return httpx.Response(200, json={"status": "ok", "data": []})

    client = HtxV5Client("access", "secret", base_url="https://example.test",
                         client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert client.positions("BTC-USDT") == []


def test_order_mutations_are_hard_disabled_by_default() -> None:
    client = HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(lambda _: pytest.fail("must not call HTX"))))
    request = SwapOrderRequest("BTC-USDT", "long", "buy", "market", "run-1", "1")

    with pytest.raises(LiveTradingDisabled):
        client.place_order(request)


def test_live_order_preserves_reduce_only_v5_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v5/trade/order"
        body = json.loads(request.content)
        assert body["contract_code"] == "ETH-USDT"
        assert body["position_side"] == "short"
        assert body["side"] == "buy"
        assert body["reduce_only"] == 1
        assert body["margin_mode"] == "isolated"
        return httpx.Response(200, json={"status": "ok", "data": {"order_id": "42"}})

    client = HtxV5Client("access", "secret", base_url="https://example.test", live_trading_enabled=True,
                         client=httpx.Client(transport=httpx.MockTransport(handler)))
    result = client.place_order(SwapOrderRequest("ETH-USDT", "short", "buy", "market", "close-1", "2",
                                                 reduce_only=True))
    assert result == {"order_id": "42"}


def test_private_fee_and_margin_reads_match_java_contract_endpoints() -> None:
    seen = []
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path, json.loads(request.content)))
        if request.url.path.endswith("swap_fee"):
            return httpx.Response(200, json={"status": "ok", "data": [{"taker_fee_rate": "0.0004"}]})
        return httpx.Response(200, json={"status": "ok", "data": [{"margin_available": "50"}]})
    client = HtxV5Client("access", "secret", base_url="https://example.test",
                         client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert client.swap_fee("BTC-USDT")[0]["taker_fee_rate"] == "0.0004"
    assert client.swap_account_info("BTC-USDT")[0]["margin_available"] == "50"
    assert seen == [("POST", "/linear-swap-api/v1/swap_fee", {"contract_code": "BTC-USDT", "business_type": "swap"}),
                    ("POST", "/linear-swap-api/v1/swap_account_info", {"contract_code": "BTC-USDT"})]
