"""One-shot 1-contract probe CLI — mocked HTX client, never hits the exchange."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from htx_trend.htx_v5 import HtxV5Client, LiveTradingDisabled, SwapOrderRequest
from htx_trend.probe_1contract import (
    ALLOWED_SYMBOLS,
    DEFAULT_SYMBOL,
    PROBE_MAX_QTY,
    ProbeAborted,
    main,
    place_probe_order,
)


def _client(handler, *, live: bool = True) -> HtxV5Client:
    return HtxV5Client(
        "access",
        "secret",
        base_url="https://example.test",
        live_trading_enabled=live,
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


def test_formal_place_order_is_not_qty_capped_without_probe_wrapper():
    """Probe must not rewrite HtxV5Client / formal strategy risk."""
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if request.url.path.endswith("/v5/trade/order"):
            return httpx.Response(200, json={"status": "ok", "data": {"order_id": "9"}})
        pytest.fail(f"unexpected {request.url.path}")

    client = _client(handler)
    result = client.place_order(
        SwapOrderRequest("ETH-USDT", "short", "buy", "market", "close-1", "2", reduce_only=True)
    )
    assert result == {"order_id": "9"}
    assert seen == ["/v5/trade/order"]


def test_place_order_still_hard_disabled_without_live_flag():
    client = HtxV5Client(
        client=httpx.Client(transport=httpx.MockTransport(lambda _: pytest.fail("must not call HTX")))
    )
    with pytest.raises(LiveTradingDisabled):
        client.place_order(SwapOrderRequest("BTC-USDT", "long", "buy", "market", "run-1", "1"))


def test_probe_place_requires_env_gate(monkeypatch):
    monkeypatch.delenv("LIVE_1CONTRACT_PROBE", raising=False)
    client = _client(lambda _: pytest.fail("must not call HTX"))
    request = SwapOrderRequest("BTC-USDT", "long", "buy", "market", "p1", "1")
    with pytest.raises(ProbeAborted, match="LIVE_PROBE_DISABLED"):
        place_probe_order(client, request, positions_before=[])


def test_probe_qty_above_one_rejected(monkeypatch):
    monkeypatch.setenv("LIVE_1CONTRACT_PROBE", "1")
    client = _client(lambda _: pytest.fail("must not call HTX"))
    request = SwapOrderRequest("BTC-USDT", "long", "buy", "market", "p1", "2")
    with pytest.raises(ProbeAborted, match="PROBE_QTY_LIMIT"):
        place_probe_order(client, request, positions_before=[])


def test_probe_rejects_when_position_would_exceed_one(monkeypatch):
    monkeypatch.setenv("LIVE_1CONTRACT_PROBE", "true")
    client = _client(lambda _: pytest.fail("must not place"))
    request = SwapOrderRequest("BTC-USDT", "long", "buy", "market", "p1", "1")
    with pytest.raises(ProbeAborted, match="PROBE_QTY_LIMIT"):
        place_probe_order(
            client,
            request,
            positions_before=[{"contract_code": "BTC-USDT", "position_side": "long", "volume": "1"}],
        )


def test_probe_open_places_one_buy(monkeypatch):
    monkeypatch.setenv("LIVE_1CONTRACT_PROBE", "1")
    bodies = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/v5/trade/order") and request.method == "POST":
            bodies.append(json.loads(request.content))
            return httpx.Response(200, json={"status": "ok", "data": {"order_id": "100"}})
        pytest.fail(request.url.path)

    result = place_probe_order(
        _client(handler),
        SwapOrderRequest("BTC-USDT", "long", "buy", "market", "p1", "1"),
        positions_before=[],
    )
    assert result == {"order_id": "100"}
    assert bodies == [
        {
            "contract_code": "BTC-USDT",
            "position_side": "long",
            "side": "buy",
            "type": "market",
            "client_order_id": "p1",
            "volume": "1",
            "reduce_only": 0,
            "margin_mode": "isolated",
            "time_in_force": "gtc",
            "self_match_prevent": "cancel_both",
        }
    ]


def test_defaults_are_btc_buy_open_sell_close_qty_one():
    assert DEFAULT_SYMBOL == "BTC-USDT"
    assert ALLOWED_SYMBOLS == ("BTC-USDT", "ETH-USDT")
    assert PROBE_MAX_QTY == 1


def test_cli_open_close_status_with_injected_client(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVE_1CONTRACT_PROBE", "1")
    evidence = tmp_path / "probe.json"
    posts = []
    snapshot = {
        "positions": [],
        "balance": {"available": "10"},
        "order": {"order_id": "7", "filled": "1"},
    }

    class FakeClient:
        live_trading_enabled = True

        def positions(self, contract_code):
            return list(snapshot["positions"])

        def balance(self):
            return dict(snapshot["balance"])

        def place_order(self, request):
            posts.append(request)
            return {"order_id": "7"}

        def order(self, contract_code, *, order_id=None, client_order_id=None):
            return dict(snapshot["order"])

    def factory(*_a, **_k):
        return FakeClient()

    creds = type(
        "C",
        (),
        {
            "account_id": 1,
            "account_label": "primary",
            "access_key": "ak",
            "secret_key": "sk",
            "base_url": "https://example.test",
        },
    )()

    open_code = main(
        ["open", "--evidence", str(evidence)],
        client_factory=factory,
        credentials_loader=lambda: creds,
    )
    assert open_code == 0
    payload = json.loads(evidence.read_text())
    assert payload["ok"] is True
    assert payload["action"] == "open"
    assert payload["symbol"] == "BTC-USDT"
    assert payload["side"] == "buy"
    assert payload["position_side"] == "long"
    assert payload["qty"] == "1"
    assert payload["order_id"] == "7"
    assert posts[-1].side == "buy"
    assert posts[-1].reduce_only is False

    snapshot["positions"] = [{"contract_code": "BTC-USDT", "position_side": "long", "volume": "1"}]
    close_code = main(
        ["close", "--evidence", str(evidence)],
        client_factory=factory,
        credentials_loader=lambda: creds,
    )
    assert close_code == 0
    closed = json.loads(evidence.read_text())
    assert closed["action"] == "close"
    assert closed["side"] == "sell"
    assert posts[-1].side == "sell"
    assert posts[-1].reduce_only is True

    status_code = main(
        ["status", "--evidence", str(evidence)],
        client_factory=factory,
        credentials_loader=lambda: creds,
    )
    assert status_code == 0
    status = json.loads(evidence.read_text())
    assert status["action"] == "status"
    assert "order_id" not in status or status["order_id"] is None


def test_close_failure_does_not_place_reverse(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVE_1CONTRACT_PROBE", "1")
    evidence = tmp_path / "probe.json"
    posts = []

    class FakeClient:
        def positions(self, contract_code):
            return [{"contract_code": "BTC-USDT", "position_side": "long", "volume": "1"}]

        def balance(self):
            return {"available": "10"}

        def place_order(self, request):
            posts.append(request)
            raise RuntimeError("exchange rejected close")

        def order(self, *a, **k):
            raise AssertionError("must not query after failed close")

    with pytest.raises(SystemExit):
        main(
            ["close", "--evidence", str(evidence)],
            client_factory=lambda *a, **k: FakeClient(),
            credentials_loader=lambda: type(
                "C",
                (),
                {
                    "account_id": 1,
                    "account_label": "primary",
                    "access_key": "ak",
                    "secret_key": "sk",
                    "base_url": "https://example.test",
                },
            )(),
        )
    assert len(posts) == 1
    assert posts[0].side == "sell"
    payload = json.loads(evidence.read_text())
    assert payload["ok"] is False
    assert payload["action"] == "close"


@pytest.mark.parametrize("action", ["status", "open", "close"])
@pytest.mark.parametrize("env_value", [None, "0", "", "false"])
def test_main_refuses_before_any_http_when_probe_env_off(
    action, env_value, tmp_path, monkeypatch
):
    if env_value is None:
        monkeypatch.delenv("LIVE_1CONTRACT_PROBE", raising=False)
    else:
        monkeypatch.setenv("LIVE_1CONTRACT_PROBE", env_value)
    with pytest.raises(ProbeAborted, match="LIVE_PROBE_DISABLED"):
        main(
            [action, "--evidence", str(tmp_path / "e.json")],
            client_factory=lambda *a, **k: pytest.fail("no client"),
            credentials_loader=lambda: pytest.fail("no creds"),
        )


def test_unknown_symbol_rejected(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVE_1CONTRACT_PROBE", "1")
    with pytest.raises(SystemExit):
        main(
            ["open", "--symbol", "DOGE-USDT", "--evidence", str(tmp_path / "x.json")],
            client_factory=lambda *a, **k: pytest.fail("no client"),
            credentials_loader=lambda: pytest.fail("no creds"),
        )


def test_formal_position_sizer_unchanged():
    from htx_trend.research.risk.position_sizing import PositionSizer

    sizer = PositionSizer()
    assert sizer.max_positions == 5
    assert sizer.risk_per_trade_pct == 0.02
    assert sizer.max_total_risk_pct == 0.10
