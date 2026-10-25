"""Configurable maker/passive execution helpers for trend entries.

This module is intentionally pure and stateless: it computes prices, evaluates
amend/cancel rules, and records shadow/pair evidence.  It does not send HTX
orders.  The PAPER default remains taker; enable the maker path only via config.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from enum import StrEnum
from os import environ
from typing import Any

from .models import TrendSide

ZERO = Decimal("0")


class MakerTimeoutAction(StrEnum):
    TAKER = "TAKER"          # convert the remainder to a taker/market order
    CANCEL = "CANCEL"        # cancel; the signal is treated as a missed pair


@dataclass(frozen=True, slots=True)
class MakerExecutionConfig:
    """Knobs for the maker/passive entry path.  All defaults keep the path off."""
    enabled: bool = False
    # Passive limit price = mid * (1 +/- offset_bps) depending on side.
    offset_bps: Decimal = Decimal("2")
    # Maximum bars to leave a passive order working before timeout_action.
    max_wait_bars: int = 4
    # If reference price moves by more than this relative to the placed price,
    # the order should be amended (re-placed at the new passive level).
    amend_threshold_bps: Decimal = Decimal("1")
    # Cancel after this many bars even if amend threshold is not breached.
    # None means rely solely on max_wait_bars.
    cancel_threshold_bars: int | None = None
    # Accept partial fills; remaining quantity follows timeout_action.
    partial_accept: bool = False
    # Behaviour after max_wait_bars is reached.
    timeout_action: MakerTimeoutAction = MakerTimeoutAction.TAKER
    # Estimated maker fee rate (used for shadow/pair cost logging only).
    maker_fee_rate: Decimal = Decimal("0.0002")

    @classmethod
    def from_env(cls) -> "MakerExecutionConfig":
        defaults = cls()

        def dec(name: str, default: Decimal) -> Decimal:
            return Decimal(environ.get(name, str(default)))

        def flag(name: str, default: bool) -> bool:
            return environ.get(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}

        action = environ.get("HTX_TREND_MAKER_TIMEOUT_ACTION", defaults.timeout_action.value)
        return cls(
            enabled=flag("HTX_TREND_MAKER_ENABLED", defaults.enabled),
            offset_bps=dec("HTX_TREND_MAKER_OFFSET_BPS", defaults.offset_bps),
            max_wait_bars=int(environ.get("HTX_TREND_MAKER_MAX_WAIT_BARS", str(defaults.max_wait_bars))),
            amend_threshold_bps=dec("HTX_TREND_MAKER_AMEND_THRESHOLD_BPS", defaults.amend_threshold_bps),
            cancel_threshold_bars=(None if action.upper() == "CANCEL" else defaults.cancel_threshold_bars),
            partial_accept=flag("HTX_TREND_MAKER_PARTIAL_ACCEPT", defaults.partial_accept),
            timeout_action=MakerTimeoutAction(action.upper()),
            maker_fee_rate=dec("HTX_TREND_MAKER_FEE_RATE", defaults.maker_fee_rate),
        )


def maker_limit_price(side: TrendSide, mid: Decimal, offset_bps: Decimal) -> Decimal:
    """Passive limit price: better than mid by offset_bps."""
    if mid <= ZERO:
        raise ValueError("mid must be positive")
    multiplier = Decimal("1") + offset_bps / Decimal("10000")
    if side is TrendSide.LONG:
        return (mid / multiplier).quantize(Decimal("0.01"), rounding=ROUND_DOWN)
    return (mid * multiplier).quantize(Decimal("0.01"), rounding=ROUND_DOWN)


def should_amend(limit_price: Decimal, reference: Decimal, amend_threshold_bps: Decimal) -> bool:
    """True when reference has drifted beyond amend_threshold_bps from limit."""
    if limit_price <= ZERO or reference <= ZERO or amend_threshold_bps <= ZERO:
        return False
    drift = abs(reference - limit_price) / limit_price
    return drift > amend_threshold_bps / Decimal("10000")


@dataclass(frozen=True, slots=True)
class MakerFillResult:
    filled_quantity: int
    remaining_quantity: int
    average_price: Decimal | None
    status: str  # FILLED / PARTIAL / OPEN / EXPIRED
    reason: str


def evaluate_maker_fill(*, side: TrendSide, quantity: int, limit_price: Decimal,
                        candle_low: Decimal, candle_high: Decimal,
                        partial_accept: bool = False) -> MakerFillResult:
    """Deterministic passive fill against one candle.

    A LONG buy limit is filled if candle_low <= limit_price.
    A SHORT sell limit is filled if candle_high >= limit_price.
    Filled at the limit price (passive improvement).
    """
    if quantity < 1:
        return MakerFillResult(0, 0, None, "EXPIRED", "zero quantity")
    if limit_price is None or limit_price <= ZERO:
        return MakerFillResult(0, quantity, None, "EXPIRED", "missing limit price")
    triggered = (side is TrendSide.LONG and candle_low <= limit_price) or (
        side is TrendSide.SHORT and candle_high >= limit_price)
    if not triggered:
        return MakerFillResult(0, quantity, None, "OPEN", "limit not touched")
    filled = quantity
    if partial_accept and filled > 0:
        # Simulate partial fill: half the order for evidence / unit-test coverage.
        filled = max(1, int((Decimal(quantity) / Decimal("2")).to_integral_value(rounding=ROUND_DOWN)))
    remaining = quantity - filled
    status = "FILLED" if remaining == 0 else "PARTIAL"
    return MakerFillResult(filled, remaining, limit_price, status,
                           f"maker fill {filled}/{quantity} @ {limit_price}")


@dataclass(frozen=True, slots=True)
class MakerShadowEvidence:
    """Pair-logging payload for taker vs maker cost comparison."""
    run_id: str | None
    contract_code: str
    side: str
    signal_price: Decimal | None
    taker_price: Decimal | None
    maker_price: Decimal | None
    taker_cost_bps: Decimal
    maker_cost_bps: Decimal
    diff_bps: Decimal  # positive means maker cheaper
    fill_status: str
    notes: str


def shadow_evidence(*, run_id: str | None, contract_code: str, side: TrendSide,
                    signal_price: Decimal | None, taker_price: Decimal | None,
                    maker_price: Decimal | None, maker_fee_rate: Decimal,
                    taker_fee_rate: Decimal, fill_status: str,
                    notes: str = "") -> MakerShadowEvidence:
    """Return a pair-logging record comparing taker vs maker all-in cost.

    Cost is expressed in bps of signal_price.  The diff is positive when the
    maker price is cheaper (taker_cost - maker_cost).
    """
    base = signal_price if signal_price is not None and signal_price > ZERO else taker_price
    if base is None or base <= ZERO:
        return MakerShadowEvidence(run_id, contract_code, side.value, signal_price, taker_price,
                                   maker_price, ZERO, ZERO, ZERO, fill_status,
                                   f"no price base; {notes}".strip())
    taker_slippage = ((taker_price - signal_price) / base * Decimal("10000")
                      if taker_price is not None else ZERO)
    maker_slippage = ((maker_price - signal_price) / base * Decimal("10000")
                      if maker_price is not None else ZERO)
    taker_cost = taker_slippage + taker_fee_rate * Decimal("10000")
    maker_cost = maker_slippage + maker_fee_rate * Decimal("10000")
    return MakerShadowEvidence(
        run_id, contract_code, side.value, signal_price, taker_price, maker_price,
        taker_cost, maker_cost, taker_cost - maker_cost, fill_status,
        notes)


def as_dict(evidence: MakerShadowEvidence) -> dict[str, Any]:
    return {
        "run_id": evidence.run_id,
        "contract_code": evidence.contract_code,
        "side": evidence.side,
        "signal_price": str(evidence.signal_price) if evidence.signal_price is not None else None,
        "taker_price": str(evidence.taker_price) if evidence.taker_price is not None else None,
        "maker_price": str(evidence.maker_price) if evidence.maker_price is not None else None,
        "taker_cost_bps": str(evidence.taker_cost_bps),
        "maker_cost_bps": str(evidence.maker_cost_bps),
        "diff_bps": str(evidence.diff_bps),
        "fill_status": evidence.fill_status,
        "notes": evidence.notes,
    }
