"""Minimal typed HTX V5 USDT-perpetual client for the trend runtime.

The client deliberately exposes only the operations required by the Python trend
migration. It preserves the current Java contract endpoints and keeps mutation
behind an explicit live-trading boundary.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Any, Mapping
from urllib.parse import quote, urlencode, urlparse

import httpx


class HtxApiError(RuntimeError):
    """HTX returned a transport or business error response."""


class LiveTradingDisabled(RuntimeError):
    """A mutation was attempted without the explicit live-trading opt-in."""


@dataclass(frozen=True)
class SwapOrderRequest:
    contract_code: str
    position_side: str
    side: str
    type: str
    client_order_id: str
    volume: str
    price: str | None = None
    reduce_only: bool = False
    margin_mode: str = "isolated"
    time_in_force: str = "gtc"
    self_match_prevent: str = "cancel_both"

    def payload(self) -> dict[str, Any]:
        values = asdict(self)
        values["reduce_only"] = 1 if values["reduce_only"] else 0
        return {key: value for key, value in values.items() if value is not None}


class HtxV5Client:
    """HTX API adapter scoped to the contract-trend migration.

    The signature string follows HTX's documented canonical form:
    ``METHOD\\nHOST\\nPATH\\nSORTED_QUERY``. Public market reads never carry
    credentials. Private reads and all mutations use the same signed transport.
    """

    def __init__(
        self,
        access_key: str | None = None,
        secret_key: str | None = None,
        *,
        base_url: str = "https://api.hbdm.com",
        live_trading_enabled: bool = False,
        client: httpx.Client | None = None,
    ) -> None:
        parsed = urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("base_url must be an absolute HTTP(S) URL")
        self._base_url = base_url.rstrip("/")
        self._host = parsed.hostname
        self._access_key = access_key
        self._secret_key = secret_key
        self._live_trading_enabled = live_trading_enabled
        self._client = client or httpx.Client(timeout=httpx.Timeout(15.0, connect=5.0))
        self._owns_client = client is None

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> "HtxV5Client":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def history_kline_envelope(self, contract_code: str, period: str, size: int | None = None,
                               *, start: int | None = None, end: int | None = None) -> dict[str, Any]:
        """Preserve the HTX envelope; size and from/to are mutually exclusive."""
        params = {"contract_code": contract_code, "period": period}
        if size is not None:
            if start is not None or end is not None or not 1 <= size <= 2000:
                raise ValueError("invalid size/range combination")
            params["size"] = str(size)
        else:
            if start is None or end is None or start > end:
                raise ValueError("explicit historical range required")
            params.update({"from": str(start), "to": str(end)})
        return self._public_get("/linear-swap-ex/market/history/kline", params)

    def history_kline(self, contract_code: str, period: str, size: int) -> list[dict[str, Any]]:
        return self._data_list(self.history_kline_envelope(contract_code, period, size))

    def depth(self, contract_code: str, depth: int) -> dict[str, Any]:
        response = self._public_get("/linear-swap-ex/market/depth", {
            "contract_code": contract_code, "type": f"step{depth}",
        })
        return response.get("tick") or response.get("data") or {}

    def contract_info(self, contract_code: str) -> list[dict[str, Any]]:
        response = self._public_get("/linear-swap-api/v1/swap_contract_info", {
            "contract_code": contract_code, "business_type": "swap",
        })
        return self._data_list(response)

    def funding_rate(self, contract_code: str) -> dict[str, Any]:
        response = self._public_get("/linear-swap-api/v1/swap_funding_rate", {"contract_code": contract_code})
        return self._data_object(response)

    def swap_fee(self, contract_code: str) -> list[dict[str, Any]]:
        """Read the account-specific contract fee profile used by Java.

        HTX exposes this as a signed V1 POST even while order placement uses
        V5.  It is read-only and is deliberately available when live mutation
        authority is disabled.
        """
        return self._data_list(self._signed("POST", "/linear-swap-api/v1/swap_fee", body={
            "contract_code": contract_code, "business_type": "swap",
        }))

    def swap_account_info(self, contract_code: str) -> list[dict[str, Any]]:
        """Read isolated margin facts for the Java-compatible margin gate."""
        response = self._signed("POST", "/linear-swap-api/v1/swap_account_info", body={
            "contract_code": contract_code,
        })
        return self._data_list(response)

    def balance(self) -> dict[str, Any]:
        return self._data_object(self._signed("GET", "/v5/account/balance"))

    def positions(self, contract_code: str) -> list[dict[str, Any]]:
        response = self._signed("GET", "/v5/trade/position/opens", {"contract_code": contract_code})
        return self._data_list(response)

    def open_orders(self, contract_code: str) -> list[dict[str, Any]]:
        response = self._signed("GET", "/v5/trade/order/opens", {
            "contract_code": contract_code, "margin_mode": "isolated", "limit": "100",
        })
        return self._data_list(response)

    def set_leverage(self, contract_code: str, position_side: str, leverage: int) -> dict[str, Any]:
        self._require_live_trading()
        return self._data_object(self._signed("POST", "/v5/position/lever", body={
            "contract_code": contract_code,
            "margin_mode": "isolated",
            "position_side": position_side,
            "lever_rate": str(leverage),
        }))

    def place_order(self, request: SwapOrderRequest) -> dict[str, Any]:
        self._require_live_trading()
        return self._data_object(self._signed("POST", "/v5/trade/order", body=request.payload()))

    def cancel_order(self, contract_code: str, order_id: str) -> dict[str, Any]:
        self._require_live_trading()
        return self._data_object(self._signed("POST", "/v5/trade/cancel_order", body={
            "contract_code": contract_code,
            "order_id": str(order_id),
        }))

    def order(self, contract_code: str, *, order_id: str | None = None,
              client_order_id: str | None = None) -> dict[str, Any]:
        if (order_id is None) == (client_order_id is None):
            raise ValueError("exactly one of order_id or client_order_id is required")
        query = {"contract_code": contract_code, "margin_mode": "isolated"}
        query["order_id" if order_id is not None else "client_order_id"] = str(order_id or client_order_id)
        return self._data_object(self._signed("GET", "/v5/trade/order", query))

    def _public_get(self, path: str, query: Mapping[str, str]) -> dict[str, Any]:
        return self._request("GET", path, query=query)

    def _signed(self, method: str, path: str, query: Mapping[str, str] | None = None,
                body: Mapping[str, Any] | None = None) -> dict[str, Any]:
        if not self._access_key or not self._secret_key:
            raise HtxApiError("private HTX endpoint requires access_key and secret_key")
        signed_query = {
            "AccessKeyId": self._access_key,
            "SignatureMethod": "HmacSHA256",
            "SignatureVersion": "2",
            "Timestamp": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S"),
            **(query or {}),
        }
        canonical = self.canonical_query(signed_query)
        payload = f"{method.upper()}\\n{self._host}\\n{path}\\n{canonical}"
        signature = base64.b64encode(hmac.new(self._secret_key.encode(), payload.encode(), hashlib.sha256).digest()).decode()
        signed_query["Signature"] = signature
        return self._request(method, path, query=signed_query, body=body)

    @staticmethod
    def canonical_query(query: Mapping[str, str]) -> str:
        pairs = sorted((str(key), str(value)) for key, value in query.items() if value is not None)
        return urlencode(pairs, quote_via=quote, safe="~")

    def _request(self, method: str, path: str, *, query: Mapping[str, str] | None = None,
                 body: Mapping[str, Any] | None = None) -> dict[str, Any]:
        try:
            response = self._client.request(method, f"{self._base_url}{path}", params=query, json=body)
            response.raise_for_status()
        except httpx.HTTPError as error:
            raise HtxApiError(f"HTX transport failure for {method} {path}: {error}") from error
        try:
            payload = response.json()
        except ValueError as error:
            raise HtxApiError(f"HTX returned non-JSON for {method} {path}") from error
        if not isinstance(payload, dict):
            raise HtxApiError(f"HTX returned non-object envelope for {method} {path}")
        status, code = payload.get("status"), payload.get("code")
        if status not in (None, "ok") or (code is not None and int(code) != 200):
            raise HtxApiError(f"HTX business error for {method} {path}: {payload.get('err_msg') or payload.get('message')}")
        return payload

    @staticmethod
    def _data_list(response: Mapping[str, Any]) -> list[dict[str, Any]]:
        data = response.get("data")
        if not isinstance(data, list):
            raise HtxApiError("HTX response data is not a list")
        return data

    @staticmethod
    def _data_object(response: Mapping[str, Any]) -> dict[str, Any]:
        data = response.get("data")
        if not isinstance(data, dict):
            raise HtxApiError("HTX response data is not an object")
        return data

    def _require_live_trading(self) -> None:
        if not self._live_trading_enabled:
            raise LiveTradingDisabled("HTX Python trend runtime has live trading disabled")
