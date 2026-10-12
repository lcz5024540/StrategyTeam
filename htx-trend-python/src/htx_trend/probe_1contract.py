"""One-shot HTX 1-contract connectivity probe. Not the formal live strategy.

Hard qty ≤ 1, gated by LIVE_1CONTRACT_PROBE, open then close, no signal loop.
Formal R0001 risk (5 positions / 2% / 10% / same-direction 3) is untouched.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from decimal import Decimal, InvalidOperation
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from .credentials import (
    ProbeCredentialError,
    forbid_exchange_plaintext_env,
    load_probe_primary_credentials,
)
from .htx_v5 import HtxV5Client, SwapOrderRequest
from .mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect


ALLOWED_SYMBOLS = ("BTC-USDT", "ETH-USDT")
DEFAULT_SYMBOL = "BTC-USDT"
PROBE_MAX_QTY = 1
PROBE_ENV_NAME = "LIVE_1CONTRACT_PROBE"
PROBE_ENV_OK = frozenset({"1", "true"})
_CLIENT_ORDER_ID_MAX = (1 << 63) - 1


class ProbeAborted(SystemExit):
    def __init__(self, reason_code: str, message: str) -> None:
        super().__init__(f"{reason_code}: {message}")
        self.reason_code = reason_code


def require_probe_env() -> None:
    value = (os.environ.get(PROBE_ENV_NAME) or "").strip().lower()
    if value not in PROBE_ENV_OK:
        raise ProbeAborted(
            "LIVE_PROBE_DISABLED",
            f"{PROBE_ENV_NAME} must be exactly 1 or true before any probe HTTP",
        )


def _qty(volume: str) -> Decimal:
    try:
        qty = Decimal(str(volume))
    except (InvalidOperation, ValueError) as error:
        raise ProbeAborted("PROBE_QTY_LIMIT", f"invalid volume {volume!r}") from error
    if qty <= 0 or qty > Decimal(PROBE_MAX_QTY):
        raise ProbeAborted("PROBE_QTY_LIMIT", f"volume {qty} exceeds hard cap {PROBE_MAX_QTY}")
    return qty


def _abs_contracts(positions: Sequence[Mapping[str, Any]], contract_code: str) -> Decimal:
    total = Decimal("0")
    for row in positions:
        if str(row.get("contract_code") or "") != contract_code:
            continue
        try:
            total += abs(Decimal(str(row.get("volume") or "0")))
        except (InvalidOperation, ValueError):
            raise ProbeAborted("PROBE_QTY_LIMIT", "position volume is not numeric")
    return total


def _side_contracts(
    positions: Sequence[Mapping[str, Any]], contract_code: str, position_side: str
) -> Decimal:
    total = Decimal("0")
    wanted = position_side.lower()
    for row in positions:
        if str(row.get("contract_code") or "") != contract_code:
            continue
        if str(row.get("position_side") or "").lower() != wanted:
            continue
        try:
            total += abs(Decimal(str(row.get("volume") or "0")))
        except (InvalidOperation, ValueError):
            raise ProbeAborted("PROBE_QTY_LIMIT", "position volume is not numeric")
    return total


def place_probe_order(
    client: HtxV5Client,
    request: SwapOrderRequest,
    *,
    positions_before: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Probe-only wrap around ``HtxV5Client.place_order``. Formal client is unchanged."""
    require_probe_env()
    qty = _qty(request.volume)
    if request.reduce_only:
        matching = _side_contracts(positions_before, request.contract_code, request.position_side)
        if qty > matching:
            raise ProbeAborted(
                "PROBE_QTY_LIMIT",
                "close would reverse/add; refusing (no reverse add)",
            )
    else:
        held = _abs_contracts(positions_before, request.contract_code)
        if held + qty > Decimal(PROBE_MAX_QTY):
            raise ProbeAborted(
                "PROBE_QTY_LIMIT",
                f"order would make position {held + qty} > {PROBE_MAX_QTY}",
            )
    return client.place_order(request)


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="HTX 1-contract live connectivity probe")
    parser.add_argument("action", choices=("open", "close", "status"))
    parser.add_argument("--symbol", default=DEFAULT_SYMBOL)
    parser.add_argument("--evidence", type=Path, default=None)
    return parser.parse_args(list(argv) if argv is not None else None)


def _default_client(credentials) -> HtxV5Client:
    return HtxV5Client(
        credentials.access_key,
        credentials.secret_key,
        base_url=credentials.base_url,
        live_trading_enabled=True,
    )


def _load_credentials():
    pinned = os.environ.get("HTX_PROBE_ACCOUNT_ID", "").strip()
    pinned_id = int(pinned) if pinned else None
    repository = HtxTrendMySqlRepository(connect(MySqlSettings.from_env()))
    try:
        return load_probe_primary_credentials(repository, pinned_account_id=pinned_id)
    finally:
        repository.close()


def _write_evidence(path: Path | None, payload: dict[str, Any]) -> None:
    if path is None:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def _client_order_id(action: str) -> str:
    """HTX v5 requires numeric client_order_id in [1, 2^63-1] (int64 string)."""
    micro = int(datetime.now(UTC).timestamp() * 1_000_000)
    salt = uuid.uuid4().int % 1_000_000
    action_salt = {"open": 1, "close": 2}.get(action, 3)
    value = (micro * 16 + action_salt * 4 + salt) % _CLIENT_ORDER_ID_MAX
    if value < 1:
        value = 1
    return str(value)


def main(
    argv: Sequence[str] | None = None,
    *,
    client_factory: Callable[..., HtxV5Client] | None = None,
    credentials_loader: Callable[[], Any] | None = None,
) -> int:
    forbid_exchange_plaintext_env()
    args = _parse_args(argv)
    require_probe_env()
    symbol = str(args.symbol).upper()
    if symbol not in ALLOWED_SYMBOLS:
        raise SystemExit(f"PROBE_SYMBOL_FORBIDDEN: {symbol} not in {ALLOWED_SYMBOLS}")
    credentials = (credentials_loader or _load_credentials)()
    client = (client_factory or _default_client)(credentials)
    evidence: dict[str, Any] = {
        "ok": False,
        "action": args.action,
        "symbol": symbol,
        "qty": str(PROBE_MAX_QTY),
        "account_id": getattr(credentials, "account_id", None),
        "account_label": getattr(credentials, "account_label", None),
    }
    try:
        position_before = client.positions(symbol)
        balance_before = client.balance()
        evidence["position_before"] = position_before
        evidence["balance_before"] = balance_before
        if args.action == "status":
            evidence["ok"] = True
            evidence["position_after"] = position_before
            evidence["balance_after"] = balance_before
            evidence["order_id"] = None
            evidence["fills"] = []
            _write_evidence(args.evidence, evidence)
            return 0
        if args.action == "open":
            request = SwapOrderRequest(
                symbol, "long", "buy", "market", _client_order_id("open"), str(PROBE_MAX_QTY)
            )
            evidence["side"] = "buy"
            evidence["position_side"] = "long"
        else:
            request = SwapOrderRequest(
                symbol,
                "long",
                "sell",
                "market",
                _client_order_id("close"),
                str(PROBE_MAX_QTY),
                reduce_only=True,
            )
            evidence["side"] = "sell"
            evidence["position_side"] = "long"
        placed = place_probe_order(client, request, positions_before=position_before)
        order_id = str(placed.get("order_id") or placed.get("id") or "")
        evidence["order_id"] = order_id or None
        fills: Any = placed
        if order_id:
            try:
                fills = client.order(symbol, order_id=order_id)
            except Exception:
                fills = placed
        evidence["fills"] = fills
        evidence["position_after"] = client.positions(symbol)
        evidence["balance_after"] = client.balance()
        evidence["ok"] = True
        _write_evidence(args.evidence, evidence)
        return 0
    except (ProbeAborted, ProbeCredentialError) as error:
        evidence["ok"] = False
        evidence["reason_code"] = getattr(error, "reason_code", None)
        evidence["error"] = str(error)
        _write_evidence(args.evidence, evidence)
        raise
    except Exception as error:
        evidence["ok"] = False
        evidence["error"] = str(error)
        _write_evidence(args.evidence, evidence)
        raise SystemExit(f"PROBE_FAILED: {error}") from error


if __name__ == "__main__":
    raise SystemExit(main())
