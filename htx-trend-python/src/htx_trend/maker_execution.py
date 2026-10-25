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

from .market_data import Candle, OrderBook, TopOfBook
from .models import TrendSide

ZERO = Decimal("0")


class MakerTimeoutAction(StrEnum):
    TAKER = "TAKER"          # convert the remainder to a taker/market order
    CANCEL = "CANCEL"        # cancel; the signal is treated as a missed pair


@dataclass(frozen=True, slots=True)
class MakerExecutionConfig:
    """Knobs for the maker/passive entry path.  All defaults keep the path off."""
    enabled: bool = False
    # Passive limit price = reference * (1 +/- offset_bps) depending on side.
    # Reference is the best same-side book price (best bid for LONG, best ask
    # for SHORT).  C1 freezes this at 0 bps, i.e. price = reference.
    offset_bps: Decimal = Decimal("2")
    # Maximum bars to leave a passive order working before timeout_action.
    max_wait_bars: int = 4
    # If reference price moves by more than this relative to the placed price,
    # the order should be amended (re-placed at the new passive level).
    amend_threshold_bps: Decimal = Decimal("1")
    # Maximum number of times the order may be amended/re-priced while waiting.
    # None means no explicit limit (preserves pre-C1 behaviour).  0 disables
    # amendments; required for the C1 freeze table.
    max_amends: int | None = None
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

        def maybe_int(name: str, default: int | None) -> int | None:
            value = environ.get(name)
            if value is None:
                return default
            stripped = value.strip().lower()
            if stripped in {"", "none", "null"}:
                return None
            return int(stripped)

        action = environ.get("HTX_TREND_MAKER_TIMEOUT_ACTION", defaults.timeout_action.value)
        return cls(
            enabled=flag("HTX_TREND_MAKER_ENABLED", defaults.enabled),
            offset_bps=dec("HTX_TREND_MAKER_OFFSET_BPS", defaults.offset_bps),
            max_wait_bars=int(environ.get("HTX_TREND_MAKER_MAX_WAIT_BARS", str(defaults.max_wait_bars))),
            amend_threshold_bps=dec("HTX_TREND_MAKER_AMEND_THRESHOLD_BPS", defaults.amend_threshold_bps),
            max_amends=maybe_int("HTX_TREND_MAKER_MAX_AMENDS", defaults.max_amends),
            cancel_threshold_bars=(None if action.upper() == "CANCEL" else defaults.cancel_threshold_bars),
            partial_accept=flag("HTX_TREND_MAKER_PARTIAL_ACCEPT", defaults.partial_accept),
            timeout_action=MakerTimeoutAction(action.upper()),
            maker_fee_rate=dec("HTX_TREND_MAKER_FEE_RATE", defaults.maker_fee_rate),
        )


def maker_reference_price(side: TrendSide, book: OrderBook | TopOfBook) -> Decimal | None:
    """Best same-side price from the order book.

    LONG entries price at the best bid; SHORT entries price at the best ask.
    If the book is empty on the required side the microstructure is missing
    and the function returns None; callers must treat that as a missed pair.
    Accepts a full OrderBook or a TopOfBook snapshot (used by PAPER runtime).
    """
    try:
        top = book if isinstance(book, TopOfBook) else book.top
    except ValueError:
        return None
    if side is TrendSide.LONG:
        return top.bid
    return top.ask


def maker_limit_price(side: TrendSide, reference: Decimal, offset_bps: Decimal) -> Decimal:
    """Passive limit price: better than the reference price by offset_bps.

    The reference price is typically the best same-side book price.  When
    offset_bps is 0 the limit price equals the reference price.
    """
    if reference <= ZERO:
        raise ValueError("reference must be positive")
    multiplier = Decimal("1") + offset_bps / Decimal("10000")
    if side is TrendSide.LONG:
        return (reference / multiplier).quantize(Decimal("0.01"), rounding=ROUND_DOWN)
    return (reference * multiplier).quantize(Decimal("0.01"), rounding=ROUND_DOWN)


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
class MakerLifecycleResult:
    """Outcome of the place -> wait -> amend -> timeout_action loop."""
    filled_quantity: int
    remaining_quantity: int
    average_price: Decimal | None
    final_status: str  # FILLED / PARTIAL_CANCELLED / CANCELLED / TAKER / PARTIAL_TAKER
    bars_held: int
    amends_done: int
    reason: str


def evaluate_maker_lifecycle(*, side: TrendSide, quantity: int, placed_price: Decimal,
                            candles: tuple[Candle, ...], max_wait_bars: int,
                            max_amends: int | None, amend_threshold_bps: Decimal,
                            partial_accept: bool,
                            timeout_action: MakerTimeoutAction) -> MakerLifecycleResult:
    """Minimal deterministic maker state machine.

    place -> wait <= max_wait_bars -> (amend if amends remain and threshold
    breached) -> timeout_action.  When timeout_action is CANCEL the remaining
    quantity is *never* converted to a taker/market order.

    C1 freeze table drives this with max_wait_bars=1, max_amends=0,
    partial_accept=False, timeout_action=CANCEL: a single bar, zero-amend,
    cancel-on-timeout loop.
    """
    if quantity < 1:
        return MakerLifecycleResult(0, 0, None, "CANCELLED", 0, 0, "zero quantity")
    if placed_price is None or placed_price <= ZERO:
        return MakerLifecycleResult(0, quantity, None, "CANCELLED", 0, 0, "missing limit price")

    filled_total = 0
    remaining = quantity
    total_cost = Decimal("0")
    current_price = placed_price
    amends_done = 0
    bars_held = 0
    amend_budget = max_amends if max_amends is not None else 10_000_000

    window = candles[:max_wait_bars] if max_wait_bars > 0 else ()
    for i, candle in enumerate(window):
        bars_held = i + 1
        fill = evaluate_maker_fill(
            side=side, quantity=remaining, limit_price=current_price,
            candle_low=candle.low, candle_high=candle.high, partial_accept=partial_accept)
        if fill.filled_quantity > 0:
            filled_total += fill.filled_quantity
            remaining -= fill.filled_quantity
            total_cost += current_price * fill.filled_quantity
        if remaining == 0:
            avg = (total_cost / filled_total).quantize(Decimal("0.01"), rounding=ROUND_DOWN)
            return MakerLifecycleResult(
                filled_total, 0, avg, "FILLED", bars_held, amends_done,
                f"filled within {bars_held} bar(s)")
        if fill.status == "PARTIAL" and not partial_accept:
            # partial_accept=False: remainder is cancelled, never re-hung.
            avg = (total_cost / filled_total).quantize(Decimal("0.01"), rounding=ROUND_DOWN) if filled_total > 0 else None
            return MakerLifecycleResult(
                filled_total, remaining, avg, "PARTIAL_CANCELLED", bars_held, amends_done,
                "partial fill with partial_accept=False; remainder cancelled")

        # Consider amending for the next bar only if we have budget and bars left.
        if bars_held < max_wait_bars and amend_budget > 0:
            new_reference = candle.close
            if should_amend(current_price, new_reference, amend_threshold_bps):
                current_price = maker_limit_price(side, new_reference, Decimal("0"))
                amends_done += 1
                amend_budget -= 1

    # Wait window exhausted.
    avg = (total_cost / filled_total).quantize(Decimal("0.01"), rounding=ROUND_DOWN) if filled_total > 0 else None
    if timeout_action is MakerTimeoutAction.CANCEL:
        status = "PARTIAL_CANCELLED" if filled_total > 0 else "CANCELLED"
        return MakerLifecycleResult(
            filled_total, remaining, avg, status, bars_held, amends_done,
            "wait window expired; timeout_action=CANCEL")
    status = "PARTIAL_TAKER" if filled_total > 0 else "TAKER"
    return MakerLifecycleResult(
        filled_total, remaining, avg, status, bars_held, amends_done,
        "wait window expired; timeout_action=TAKER")


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


def _cost_bps(*, signal_price: Decimal | None, price: Decimal | None,
              fee_rate: Decimal, base: Decimal | None) -> Decimal:
    """All-in cost in bps relative to the signal price (or fallback base)."""
    if base is None or base <= ZERO:
        return ZERO
    slippage = ((price - signal_price) / base * Decimal("10000")
                if price is not None and signal_price is not None else ZERO)
    return slippage + fee_rate * Decimal("10000")


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
    taker_cost = _cost_bps(signal_price=signal_price, price=taker_price,
                           fee_rate=taker_fee_rate, base=base)
    maker_cost = _cost_bps(signal_price=signal_price, price=maker_price,
                           fee_rate=maker_fee_rate, base=base)
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


@dataclass(frozen=True, slots=True)
class PShadowTakerLeg:
    """One leg of a P-SHADOW-TAKER pair record."""
    leg: str  # "maker_live" or "taker_shadow"
    run_id: str | None
    contract_code: str
    side: str
    signal_price: Decimal | None
    price: Decimal | None
    fee_rate: Decimal
    cost_bps: Decimal
    fill_status: str
    notes: str


@dataclass(frozen=True, slots=True)
class PShadowTakerEvidence:
    """Audit record for the C1 P-SHADOW-TAKER requirement.

    The primary order is a real maker LIMIT (maker_live).  The same signal also
    records a hypothetical taker execution (taker_shadow) for cost comparison,
    but no taker order is sent.
    """
    run_id: str | None
    contract_code: str
    side: str
    legs: tuple[PShadowTakerLeg, ...]
    diff_bps: Decimal  # positive means maker_live cheaper than taker_shadow
    fill_status: str
    notes: str


def p_shadow_taker_evidence(*, run_id: str | None, contract_code: str, side: TrendSide,
                            signal_price: Decimal | None,
                            maker_price: Decimal | None, taker_price: Decimal | None,
                            maker_fee_rate: Decimal, taker_fee_rate: Decimal,
                            fill_status: str = "INTENT",
                            notes: str = "") -> PShadowTakerEvidence:
    """Build a P-SHADOW-TAKER evidence pair.

    maker_price is the live LIMIT price actually sent to the exchange.
    taker_price is the shadow taker assumption, typically the best opposite-side
    book price (best ask for LONG, best bid for SHORT).
    """
    base = signal_price if signal_price is not None and signal_price > ZERO else maker_price
    maker_cost = _cost_bps(signal_price=signal_price, price=maker_price,
                           fee_rate=maker_fee_rate, base=base)
    taker_cost = _cost_bps(signal_price=signal_price, price=taker_price,
                           fee_rate=taker_fee_rate, base=base)
    maker_leg = PShadowTakerLeg(
        leg="maker_live", run_id=run_id, contract_code=contract_code, side=side.value,
        signal_price=signal_price, price=maker_price, fee_rate=maker_fee_rate,
        cost_bps=maker_cost, fill_status=fill_status,
        notes=f"live maker LIMIT; {notes}".strip())
    taker_leg = PShadowTakerLeg(
        leg="taker_shadow", run_id=run_id, contract_code=contract_code, side=side.value,
        signal_price=signal_price, price=taker_price, fee_rate=taker_fee_rate,
        cost_bps=taker_cost, fill_status=fill_status,
        notes=f"shadow taker assumption; {notes}".strip())
    return PShadowTakerEvidence(
        run_id=run_id, contract_code=contract_code, side=side.value,
        legs=(maker_leg, taker_leg),
        diff_bps=taker_cost - maker_cost,
        fill_status=fill_status,
        notes=notes)


def p_shadow_taker_as_dict(evidence: PShadowTakerEvidence) -> dict[str, Any]:
    def leg_dict(leg: PShadowTakerLeg) -> dict[str, Any]:
        return {
            "leg": leg.leg,
            "run_id": leg.run_id,
            "contract_code": leg.contract_code,
            "side": leg.side,
            "signal_price": str(leg.signal_price) if leg.signal_price is not None else None,
            "price": str(leg.price) if leg.price is not None else None,
            "fee_rate": str(leg.fee_rate),
            "cost_bps": str(leg.cost_bps),
            "fill_status": leg.fill_status,
            "notes": leg.notes,
        }

    return {
        "run_id": evidence.run_id,
        "contract_code": evidence.contract_code,
        "side": evidence.side,
        "legs": [leg_dict(leg) for leg in evidence.legs],
        "diff_bps": str(evidence.diff_bps),
        "fill_status": evidence.fill_status,
        "notes": evidence.notes,
    }
