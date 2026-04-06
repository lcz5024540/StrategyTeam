#!/usr/bin/env python3
import json
import os
import sys
import traceback

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)


def write_json(payload, code=0):
    sys.stdout.write(json.dumps(payload, ensure_ascii=True))
    sys.stdout.flush()
    sys.exit(code)


def fail(message, extra=None, code=1):
    payload = {"success": False, "message": message}
    if extra is not None:
        payload["details"] = extra
    write_json(payload, code)


try:
    import httpx
    from py_clob_client.client import ClobClient
    from py_clob_client.clob_types import ApiCreds, MarketOrderArgs, OpenOrderParams, OrderArgs, OrderType
    from py_clob_client.order_builder.constants import BUY, SELL
    from polymarket_http import install_py_clob_http_overrides
except Exception as exc:
    fail(
        "py-clob-client is not installed. Run `pip install py-clob-client` before enabling LIVE Polymarket execution.",
        str(exc),
    )

install_py_clob_http_overrides()


def read_payload():
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        fail("Invalid JSON payload passed to Polymarket helper.", str(exc))


def env(name, default=""):
    return os.environ.get(name, default)


def build_client():
    host = env("POLY_CLOB_HOST", "https://clob.polymarket.com")
    chain_id = int(env("POLY_CHAIN_ID", "137"))
    private_key = env("POLY_PRIVATE_KEY")
    funder = env("POLY_FUNDER") or None
    signature_type = int(env("POLY_SIGNATURE_TYPE", "0"))

    if not private_key:
        fail("POLY_PRIVATE_KEY is required for LIVE Polymarket execution.")

    client = ClobClient(
        host,
        key=private_key,
        chain_id=chain_id,
        signature_type=signature_type,
        funder=funder,
    )

    api_key = env("POLY_API_KEY")
    api_secret = env("POLY_API_SECRET")
    api_passphrase = env("POLY_API_PASSPHRASE")
    derive_if_missing = env("POLY_DERIVE_API_CREDS_IF_MISSING", "true").lower() == "true"

    if api_key and api_secret and api_passphrase:
        creds = ApiCreds(api_key=api_key, api_secret=api_secret, api_passphrase=api_passphrase)
        client.set_api_creds(creds)
        return client, creds, False

    if derive_if_missing:
        creds = client.create_or_derive_api_creds()
        client.set_api_creds(creds)
        return client, creds, True

    fail("Missing Polymarket API credentials and derive mode is disabled.")


def order_type_of(raw_value):
    value = (raw_value or env("POLY_ORDER_TYPE", "GTC")).upper()
    if value == "FOK":
        return OrderType.FOK
    if value == "FAK":
        return getattr(OrderType, "FAK", OrderType.GTC)
    return OrderType.GTC


def normalize_side(raw_value):
    value = (raw_value or "BUY").upper()
    return SELL if value == "SELL" else BUY


def to_plain(value):
    if hasattr(value, "__dict__"):
        return {k: to_plain(v) for k, v in value.__dict__.items()}
    if isinstance(value, dict):
        return {str(k): to_plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [to_plain(v) for v in value]
    return value


def handle_derive_api_creds():
    _, creds, derived = build_client()
    write_json(
        {
            "success": True,
            "derived": derived,
            "apiKey": creds.api_key,
            "apiSecret": creds.api_secret,
            "apiPassphrase": creds.api_passphrase,
        }
    )


def handle_place_order(payload):
    client, creds, derived = build_client()
    order_type = order_type_of(payload.get("orderType"))
    side = normalize_side(payload.get("side"))
    token_id = str(payload.get("tokenId") or "").strip()
    if not token_id:
        fail("tokenId is required for CLOB order placement.")

    if order_type == OrderType.FOK:
        amount = float(payload.get("amount") or 0)
        if amount <= 0:
            fail("amount must be positive for FOK market orders.")
        args = MarketOrderArgs(
            token_id=token_id,
            amount=amount,
            side=side,
            order_type=order_type,
        )
        signed = client.create_market_order(args)
    else:
        price = float(payload.get("price") or 0)
        size = float(payload.get("size") or 0)
        if price <= 0 or size <= 0:
            fail("price and size must be positive for limit orders.")
        args = OrderArgs(
            token_id=token_id,
            price=price,
            size=size,
            side=side,
        )
        signed = client.create_order(args)

    response = client.post_order(signed, order_type)
    order_id = None
    if isinstance(response, dict):
        order_id = response.get("orderID") or response.get("orderId") or response.get("id")
    write_json(
        {
            "success": True,
            "derivedApiCreds": derived,
            "orderID": order_id,
            "response": to_plain(response),
            "apiKey": creds.api_key if derived else None,
            "apiSecret": creds.api_secret if derived else None,
            "apiPassphrase": creds.api_passphrase if derived else None,
        }
    )


def handle_get_order(payload):
    client, _, _ = build_client()
    order_id = str(payload.get("orderId") or "").strip()
    if not order_id:
        fail("orderId is required.")
    if hasattr(client, "get_order"):
        response = client.get_order(order_id)
        write_json({"success": True, "response": to_plain(response)})

    orders = client.get_orders(OpenOrderParams())
    for item in orders or []:
        if str(item.get("id")) == order_id or str(item.get("orderID")) == order_id:
            write_json({"success": True, "response": to_plain(item)})
    fail("Order not found in open orders.", order_id)


def handle_cancel_order(payload):
    client, _, _ = build_client()
    order_id = str(payload.get("orderId") or "").strip()
    if not order_id:
        fail("orderId is required.")
    response = client.cancel(order_id)
    write_json({"success": True, "response": to_plain(response)})


def main():
    if len(sys.argv) < 2:
        fail("Expected helper command, e.g. derive-api-creds or place-order.")
    command = sys.argv[1]
    payload = read_payload()

    if command == "derive-api-creds":
        handle_derive_api_creds()
    if command == "place-order":
        handle_place_order(payload)
    if command == "get-order":
        handle_get_order(payload)
    if command == "cancel-order":
        handle_cancel_order(payload)
    fail(f"Unsupported helper command: {command}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        fail("Polymarket helper failed unexpectedly.", {"error": str(exc), "traceback": traceback.format_exc()})
