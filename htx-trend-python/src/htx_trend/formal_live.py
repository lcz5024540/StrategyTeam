"""First-cut formal automatic LIVE deployment guards (R0001 order-path limits).

Validates operator env before the signal-driven worker places orders.
Does not enable trading by itself; ``HtxV5Client`` still requires
``live_trading_enabled=True`` on the executor path inside ``worker``.
"""
from __future__ import annotations

import os
from decimal import Decimal
from typing import Sequence

# R0001 / prereg-batch1 (300U, BTC+ETH 4h first cut)
FORMAL_LIVE_INITIAL_EQUITY_USDT = Decimal("300")
FORMAL_LIVE_MAX_POSITIONS = 5
FORMAL_LIVE_RISK_PER_TRADE_PCT = Decimal("0.02")
FORMAL_LIVE_MAX_TOTAL_RISK_PCT = Decimal("0.10")
FORMAL_LIVE_MAX_SAME_DIRECTION_POSITIONS = 3
FORMAL_LIVE_ALLOWED_CONTRACTS = frozenset({"BTC-USDT", "ETH-USDT"})
FORMAL_LIVE_ENTRY_PERIOD = "4hour"


def _decimal_env(name: str, default: Decimal) -> Decimal:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    return Decimal(str(raw))


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    return int(raw)


def parse_contract_list(raw: str | None) -> tuple[str, ...]:
    if not raw:
        return ()
    return tuple(dict.fromkeys(item.strip().upper() for item in raw.split(",") if item.strip()))


def validate_formal_live_deployment() -> None:
    """Fail fast if env exceeds the approved first-cut LIVE risk envelope."""
    contracts = parse_contract_list(os.environ.get("HTX_TREND_CONTRACTS"))
    if not contracts:
        raise SystemExit("formal LIVE requires HTX_TREND_CONTRACTS (BTC-USDT,ETH-USDT for first cut)")
    unknown = [c for c in contracts if c not in FORMAL_LIVE_ALLOWED_CONTRACTS]
    if unknown:
        raise SystemExit(
            f"formal LIVE first cut allows only {sorted(FORMAL_LIVE_ALLOWED_CONTRACTS)}; got extra {unknown}"
        )

    period = (os.environ.get("HTX_TREND_PERIOD") or "").strip()
    if period != FORMAL_LIVE_ENTRY_PERIOD:
        raise SystemExit(
            f"formal LIVE first cut requires HTX_TREND_PERIOD={FORMAL_LIVE_ENTRY_PERIOD!r}, got {period!r}"
        )

    max_positions = _int_env("HTX_TREND_MAX_ACTIVE_POSITIONS", 1)
    if max_positions < 1 or max_positions > FORMAL_LIVE_MAX_POSITIONS:
        raise SystemExit(
            f"HTX_TREND_MAX_ACTIVE_POSITIONS must be in [1, {FORMAL_LIVE_MAX_POSITIONS}], got {max_positions}"
        )

    risk_per_trade = _decimal_env(
        "HTX_TREND_RISK_PER_TRADE_USDT",
        FORMAL_LIVE_INITIAL_EQUITY_USDT * FORMAL_LIVE_RISK_PER_TRADE_PCT,
    )
    max_risk_per_trade = FORMAL_LIVE_INITIAL_EQUITY_USDT * FORMAL_LIVE_RISK_PER_TRADE_PCT
    if risk_per_trade > max_risk_per_trade:
        raise SystemExit(
            f"HTX_TREND_RISK_PER_TRADE_USDT {risk_per_trade} exceeds "
            f"{max_risk_per_trade} (2% of {FORMAL_LIVE_INITIAL_EQUITY_USDT}U)"
        )

    max_total_risk = _decimal_env(
        "HTX_TREND_MAX_TOTAL_RISK_USDT",
        FORMAL_LIVE_INITIAL_EQUITY_USDT * FORMAL_LIVE_MAX_TOTAL_RISK_PCT,
    )
    cap_total = FORMAL_LIVE_INITIAL_EQUITY_USDT * FORMAL_LIVE_MAX_TOTAL_RISK_PCT
    if max_total_risk > cap_total:
        raise SystemExit(
            f"HTX_TREND_MAX_TOTAL_RISK_USDT {max_total_risk} exceeds "
            f"{cap_total} (10% of {FORMAL_LIVE_INITIAL_EQUITY_USDT}U)"
        )

    same_dir = _int_env(
        "HTX_TREND_MAX_SAME_DIRECTION_POSITIONS",
        FORMAL_LIVE_MAX_SAME_DIRECTION_POSITIONS,
    )
    if same_dir > FORMAL_LIVE_MAX_SAME_DIRECTION_POSITIONS:
        raise SystemExit(
            f"HTX_TREND_MAX_SAME_DIRECTION_POSITIONS must be <= "
            f"{FORMAL_LIVE_MAX_SAME_DIRECTION_POSITIONS}, got {same_dir}"
        )


def formal_live_limits_summary() -> dict[str, str | int]:
    return {
        "initial_equity_usdt": str(FORMAL_LIVE_INITIAL_EQUITY_USDT),
        "max_positions": FORMAL_LIVE_MAX_POSITIONS,
        "risk_per_trade_pct": str(FORMAL_LIVE_RISK_PER_TRADE_PCT),
        "max_total_risk_pct": str(FORMAL_LIVE_MAX_TOTAL_RISK_PCT),
        "max_same_direction_positions": FORMAL_LIVE_MAX_SAME_DIRECTION_POSITIONS,
        "allowed_contracts": ",".join(sorted(FORMAL_LIVE_ALLOWED_CONTRACTS)),
        "entry_period": FORMAL_LIVE_ENTRY_PERIOD,
    }
