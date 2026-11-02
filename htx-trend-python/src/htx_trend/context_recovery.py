"""R1-α context recovery checks.

Read-only classification for daily closed-bar depth, degraded health, and the
pre-hot-update position report gate. This module does not trade, does not edit
``HTX_TREND_CONTRACTS``, and does not accept a lower daily bar threshold.
"""
from __future__ import annotations

from pathlib import Path
from typing import Iterable, Mapping

from .market_data import Candle, completed_candles

REQUIRED_BARS_1D = 121
_PUBLIC_DAILY_FETCH_SIZE = 200
_CONTEXT_FAILURE = "CONTEXT_UNAVAILABLE"
_OPEN_STATUSES = frozenset({
    "ACTIVE", "ENTRY_SENT", "CLOSE_SENT", "PARTIAL_CLOSE_SENT", "UNKNOWN",
})
_OVERRIDE_KEYS = frozenset({"required_bars", "required_bars_1d"})


def _clean_symbol(value: object) -> str:
    symbol = str(value).strip()
    if not symbol or any(char.isspace() for char in symbol) or "," in symbol:
        raise ValueError(f"invalid contract symbol: {value!r}")
    return symbol


def _require_count(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("closed_bars_1d must be a non-negative integer")
    return value


def contracts_from_env(environ: Mapping[str, str]) -> tuple[str, ...]:
    """Read the contract universe. Callers pass the mapping; this does not write it."""
    raw = environ.get("HTX_TREND_CONTRACTS", "")
    return tuple(part.strip() for part in str(raw).split(",") if part.strip())


def load_expected_thin(text: str) -> frozenset[str]:
    """Parse a classification registry. Empty text is an empty registry."""
    stripped = text.strip()
    if not stripped:
        return frozenset()
    if stripped.startswith("{") or stripped.startswith("["):
        import json
        payload = json.loads(stripped)
        if isinstance(payload, list):
            symbols = payload
        elif isinstance(payload, dict):
            if _OVERRIDE_KEYS.intersection(payload):
                raise ValueError("required_bars_1d is locked at 121 and cannot be supplied")
            symbols = payload.get("expected_thin_symbols", [])
        else:
            raise ValueError("expected thin registry must be a list or object")
        return frozenset(_clean_symbol(item) for item in symbols)
    symbols: list[str] = []
    for line in text.splitlines():
        item = line.split("#", 1)[0].strip()
        if item:
            symbols.append(_clean_symbol(item))
    return frozenset(symbols)


def load_closed_bars(payload: Mapping[str, object]) -> dict[str, int]:
    """Read a symbol → closed 1day bar count. Reject any attempt to supply a lower gate."""
    if not isinstance(payload, Mapping):
        raise ValueError("closed_bars payload must be an object")
    if _OVERRIDE_KEYS.intersection(payload):
        raise ValueError("required_bars_1d is locked at 121 and cannot be supplied")
    source = payload["closed_bars_1d"] if "closed_bars_1d" in payload else payload
    if not isinstance(source, Mapping):
        raise ValueError("closed_bars_1d mapping required")
    return {_clean_symbol(symbol): _require_count(count) for symbol, count in source.items()}


def load_open_symbols(payload: Mapping[str, object]) -> frozenset[str]:
    """Read a position inventory. Missing status is a refusal, not an assumed flat book."""
    if "open_symbols" in payload:
        return frozenset(_clean_symbol(item) for item in payload["open_symbols"])  # type: ignore[union-attr]
    if "positions" not in payload:
        raise ValueError("position report needs open_symbols or positions")
    found: list[str] = []
    for row in payload["positions"]:  # type: ignore[union-attr]
        if not isinstance(row, Mapping) or "status" not in row:
            raise ValueError("position row missing status; refusing to invent an open position")
        if str(row["status"]).strip().upper() in _OPEN_STATUSES:
            found.append(_clean_symbol(row["symbol"]))
    return frozenset(found)


def count_closed_daily_bars(candles: Iterable[Candle], *, now_epoch: int) -> int:
    """Count 1day bars closed at ``now_epoch`` with the runtime closed-bar filter.

    The count is the number of bars the exchange actually returned. Nothing is
    padded up to ``REQUIRED_BARS_1D``.
    """
    return len(completed_candles(tuple(candles), "1day", now_epoch=now_epoch))


def closed_bars_from_public_client(client: object, symbol: str, *, now_epoch: int) -> int:
    """Public kline read. The fetch page is fixed and is not a depth gate."""
    envelope = client.history_kline_envelope(symbol, "1day", _PUBLIC_DAILY_FETCH_SIZE)  # type: ignore[attr-defined]
    raw = envelope.get("data") if isinstance(envelope, Mapping) else None
    if not isinstance(raw, list):
        raise ValueError(f"HTX K-line response has no data list for {symbol}")
    candles = tuple(Candle.from_htx(item) for item in raw)
    return count_closed_daily_bars(candles, now_epoch=now_epoch)


def assemble_preflight(
    symbols: Iterable[str],
    closed_bars_1d: Mapping[str, int],
    expected_thin: Iterable[str] = (),
    open_symbols: Iterable[str] | None = None,
) -> dict[str, object]:
    """Classify one universe. The registry never removes a symbol."""
    universe = tuple(_clean_symbol(item) for item in symbols)
    if len(universe) != len(set(universe)):
        raise ValueError("duplicate symbol in universe")
    if not isinstance(closed_bars_1d, Mapping):
        raise ValueError("closed_bars_1d mapping required")
    if _OVERRIDE_KEYS.intersection(closed_bars_1d):
        raise ValueError("required_bars_1d is locked at 121 and cannot be supplied")
    registry = frozenset(_clean_symbol(item) for item in expected_thin)
    opened = None if open_symbols is None else frozenset(_clean_symbol(item) for item in open_symbols)
    rows: list[dict[str, object]] = []
    for symbol in universe:
        if symbol not in closed_bars_1d:
            raise ValueError(f"closed_bars_1d missing for {symbol}; refusing to synthesize bars")
        count = _require_count(closed_bars_1d[symbol])
        if count >= REQUIRED_BARS_1D:
            depth_class = "OK"
        elif symbol in registry:
            depth_class = "EXPECTED_THIN"
        else:
            depth_class = "BLOCK_UNEXPECTED"
        position: bool | str = "unknown" if opened is None else symbol in opened
        rows.append({
            "symbol": symbol,
            "closed_bars_1d": count,
            "required_bars_1d": REQUIRED_BARS_1D,
            "depth_class": depth_class,
            "has_open_position": position,
        })
    n_ok = sum(row["depth_class"] == "OK" for row in rows)
    n_expected_thin = sum(row["depth_class"] == "EXPECTED_THIN" for row in rows)
    n_block_unexpected = sum(row["depth_class"] == "BLOCK_UNEXPECTED" for row in rows)
    total = len(rows)
    if total == 0 or n_block_unexpected:
        verdict = "FAIL_UNEXPECTED"
    elif n_expected_thin:
        verdict = "PASS_WITH_EXPECTED_THIN"
    else:
        verdict = "PASS"
    return {
        "rows": rows,
        "n_ok": n_ok,
        "n_expected_thin": n_expected_thin,
        "n_block_unexpected": n_block_unexpected,
        "preflight_verdict": verdict,
        "coverage_preview": f"{n_ok}/{total}",
        "required_bars_1d": REQUIRED_BARS_1D,
        "writes_env": False,
        "removed_symbols": [],
        "registry_effect": "classification_only",
        "expected_thin_registry": sorted(registry),
    }


def classify_degraded(
    failures: Iterable[str],
    expected_thin: Iterable[str],
    *,
    process_alive: bool = True,
) -> dict[str, object]:
    """Separate registered daily-context gaps from unexpected failures.

    ``DEGRADED_EXPECTED_THIN`` is never reported as ``HEALTHY``.
    """
    registry = frozenset(_clean_symbol(item) for item in expected_thin)
    parsed: list[tuple[str | None, str, bool]] = []
    for item in failures:
        text = str(item).strip()
        if ":" not in text:
            parsed.append((None, text, False))
            continue
        symbol, code = (part.strip() for part in text.split(":", 1))
        qualified = code == _CONTEXT_FAILURE and symbol in registry
        parsed.append((symbol, text, qualified))
    expected_symbols = sorted({symbol for symbol, _, qualified in parsed if qualified and symbol})
    unexpected = [raw for _, raw, qualified in parsed if not qualified]
    if not process_alive:
        degraded_class = "UNHEALTHY"
        soft_accept = False
    elif not parsed:
        degraded_class = "HEALTHY"
        soft_accept = False
        expected_symbols = []
        unexpected = []
    elif unexpected:
        degraded_class = "DEGRADED_UNEXPECTED"
        soft_accept = False
    else:
        degraded_class = "DEGRADED_EXPECTED_THIN"
        soft_accept = True
    return {
        "degraded_class": degraded_class,
        "expected_thin_symbols": expected_symbols,
        "unexpected_failures": unexpected,
        "soft_accept": soft_accept,
    }


def evaluate_position_guard(
    *,
    report_path: str | None = None,
    allow_skip: bool = False,
    product_waiver: str | None = None,
) -> dict[str, object]:
    """Require a readonly position report unless a product waiver explicitly skips it.

    The decision never closes or reduces a position.
    """
    decision: dict[str, object] = {
        "allowed": False,
        "reason": "position readonly report required",
        "auto_close": False,
        "auto_reduce": False,
        "skipped_positions": False,
        "report_path": report_path,
    }
    if report_path:
        if Path(report_path).is_file():
            decision["allowed"] = True
            decision["reason"] = "position readonly report present"
        else:
            decision["reason"] = "position readonly report path is missing"
        return decision
    if allow_skip:
        waiver = product_waiver or ""
        if "PRODUCT-WAIVER" in waiver:
            decision["allowed"] = True
            decision["reason"] = "explicit product waiver skip"
            decision["skipped_positions"] = True
        else:
            decision["reason"] = "skip requires explicit PRODUCT-WAIVER text"
    return decision


def render_preflight_markdown(report: Mapping[str, object]) -> str:
    lines = [
        "# 部署预检 · universe 日K深度",
        "",
        f"- preflight_verdict: {report['preflight_verdict']}",
        f"- coverage_preview: {report['coverage_preview']}",
        f"- n_ok: {report['n_ok']}",
        f"- n_expected_thin: {report['n_expected_thin']}",
        f"- n_block_unexpected: {report['n_block_unexpected']}",
        f"- required_bars_1d: {REQUIRED_BARS_1D}",
        "- writes_env: false",
        "- removed_symbols: []",
        "- registry_effect: classification_only",
        "",
        "| symbol | closed_bars_1d | required_bars_1d | depth_class | has_open_position |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in report["rows"]:  # type: ignore[union-attr]
        position = row["has_open_position"]
        if position is True:
            position_cell = "true"
        elif position is False:
            position_cell = "false"
        else:
            position_cell = str(position)
        lines.append(
            f"| {row['symbol']} | {row['closed_bars_1d']} | {row['required_bars_1d']} | {row['depth_class']} | {position_cell} |"
        )
    lines.append("")
    return "\n".join(lines)
