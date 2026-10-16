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


def test_private_request_uses_htx_canonical_hmac_signature() -> None:
    secret = "secret"

    def handler(request: httpx.Request) -> httpx.Response:
        query = parse_qs(urlparse(str(request.url)).query)
        signature = query.pop("Signature")[0]
        flattened = {key: values[0] for key, values in query.items()}
        canonical = HtxV5Client.canonical_query(flattened)
        payload = f"GET\nexample.test\n/v5/trade/position/opens\n{canonical}"
        expected = base64.b64encode(hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest()).decode()
        assert signature == expected
        assert flattened["AccessKeyId"] == "access"
        assert flattened["contract_code"] == "BTC-USDT"
        return httpx.Response(200, json={"status": "ok", "data": []})

    client = HtxV5Client("access", secret, base_url="https://example.test",
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
        body = json.loads(request.content) if request.content else {}
        seen.append((request.method, request.url.path, body))
        if request.url.path.endswith("swap_fee"):
            return httpx.Response(200, json={"status": "ok", "data": [{"taker_fee_rate": "0.0004"}]})
        assert request.method == "GET"
        assert request.url.path == "/v5/account/balance"
        return httpx.Response(200, json={"status": "ok", "data": {"available_margin": "50"}})
    client = HtxV5Client("access", "secret", base_url="https://example.test",
                         client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert client.swap_fee("BTC-USDT")[0]["taker_fee_rate"] == "0.0004"
    assert client.account_balance()["available_margin"] == "50"
    assert seen == [("POST", "/linear-swap-api/v1/swap_fee", {"contract_code": "BTC-USDT", "business_type": "swap"}),
                    ("GET", "/v5/account/balance", {})]
