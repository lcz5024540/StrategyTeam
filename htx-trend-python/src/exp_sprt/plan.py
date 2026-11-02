"""Completed-candle structure plans for sprt-plan-v0.1.

Bias, zones, invalidation and targets are computed only from bars whose close
is at or before ``as_of_epoch``. A still-forming bar cannot change the plan.
"""
from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal

from htx_trend.market_data import Candle, completed_candles, period_seconds

from .schema import (
    DEDUPE_WINDOW_SECONDS,
    EXP_ID,
    GENERATOR_RULE,
    MAPPING_SENTENCE,
    PLAN_EXPIRY_BARS,
    SCHEMA_VERSION,
    STRUCTURE_BARS,
    SWING_ANCHOR_BARS,
    ZONE_WIDTH_FRACTION,
    money,
    sha256_text,
)

PLAN_FIELDS = (
    "plan_id",
    "schema_version",
    "generated_at",
    "symbol",
    "timeframe_structure",
    "bias",
    "trade_forbidden_if",
    "zone_support",
    "zone_resistance",
    "entry_behavior",
    "invalidation_price",
    "target_price",
    "target_structure_ref",
    "max_acceptable_fill_band",
    "rr_formula",
    "plan_expiry",
    "cancel_resting_if",
    "dedupe_cooldown",
    "reactivate_if",
    "evidence_ptr",
)


def _iso(epoch_seconds: int) -> str:
    return datetime.fromtimestamp(epoch_seconds, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _behavior_block(kind: str) -> dict[str, object]:
    if kind == "REJECTION":
        primary, sensitivity, exploration = "R1", ["R2"], ["R3", "R4"]
    elif kind == "BREAKOUT":
        primary, sensitivity, exploration = "B2", ["B1"], ["B3"]
    else:
        raise ValueError(f"unsupported entry behavior: {kind}")
    return {
        "kind": kind,
        "primary_predicate": primary,
        "sensitivity_predicates": sensitivity,
        "exploration_predicates": exploration,
        "status": "FROZEN_P3",
        "dual_primary_forbidden": True,
        "track": "探索",
        "phase": "phase-0",
        "exp_id": EXP_ID,
        "freeze_ref": "product-stamp-exp-sprt-p3-freeze-20261102",
    }


def build_plan_document(
    *,
    symbol: str,
    generated_at: str,
    bias: str,
    zone_support: tuple[tuple[str, str], ...],
    zone_resistance: tuple[tuple[str, str], ...],
    behavior: str,
    invalidation_price: str,
    target_price: str,
    target_structure_ref: str,
    band_low: str,
    band_high: str,
    evidence_hash: str,
    signal_close_epoch: int,
    timeframe: str,
) -> dict[str, object]:
    """Assemble one frozen-field plan. No extra top-level key is added."""
    support = [{"low": low, "high": high} for low, high in zone_support]
    resistance = [{"low": low, "high": high} for low, high in zone_resistance]
    identity = "|".join((
        symbol, timeframe, bias, behavior,
        *(f"{low}:{high}" for low, high in zone_support),
        *(f"{low}:{high}" for low, high in zone_resistance),
        invalidation_price, target_price, band_low, band_high, evidence_hash,
    ))
    plan_id = f"sprt-{symbol}-{sha256_text(identity).split(':', 1)[1][:20]}"
    expires = signal_close_epoch + PLAN_EXPIRY_BARS * period_seconds(timeframe)
    return {
        "plan_id": plan_id,
        "schema_version": SCHEMA_VERSION,
        "generated_at": generated_at,
        "symbol": symbol,
        "timeframe_structure": timeframe,
        "bias": bias,
        "trade_forbidden_if": [] if bias in {"LONG", "SHORT"} else ["flat_bias"],
        "zone_support": support,
        "zone_resistance": resistance,
        "entry_behavior": _behavior_block(behavior),
        "invalidation_price": invalidation_price,
        "target_price": target_price,
        "target_structure_ref": target_structure_ref,
        "max_acceptable_fill_band": {"low": band_low, "high": band_high},
        "rr_formula": {
            "expression": "net_rr = reward_after_fee / risk_including_fee",
            "fee_rate_assumption": "0.0004",
            "reward_after_fee": "directional_reward * (1 - fee_rate) - executable * fee_rate",
            "risk_including_fee": "directional_risk * (1 + fee_rate) + executable * fee_rate",
            "min_net_rr_gate": "UNFROZEN_NOT_APPLIED",
            "track": "探索/Phase-0",
        },
        "plan_expiry": {
            "expires_at": _iso(expires),
            "bars": PLAN_EXPIRY_BARS,
            "anchor": "signal_bar_close",
            "log_only": True,
        },
        "cancel_resting_if": {
            "mode": "LOG_ONLY_NO_ORDERS",
            "conditions": [
                "superseded_by_new_plan_id",
                "plan_expiry_reached",
                "price_beyond_invalidation_before_confirmation",
            ],
        },
        "dedupe_cooldown": {
            "window_seconds": DEDUPE_WINDOW_SECONDS,
            "scope": "plan_id",
            "effect": "CENSOR_NOT_M2_DENOMINATOR",
        },
        "reactivate_if": {
            "conditions": ["price_left_zone_and_reentered", "cooldown_elapsed"],
            "log_only": True,
        },
        "evidence_ptr": {
            "kline_window_sha256": evidence_hash,
            "generator_rule": GENERATOR_RULE,
            "break_level_mapping_sentence": MAPPING_SENTENCE,
            "track": "探索/Phase-0",
            "excluded_incomplete_bars": True,
        },
    }


def _bias(structure: tuple[Candle, ...], swing_low: Decimal, swing_high: Decimal) -> str:
    midpoint = (swing_low + swing_high) / Decimal("2")
    anchor = structure[-1 - SWING_ANCHOR_BARS].close
    last = structure[-1].close
    if last > midpoint and last > anchor:
        return "LONG"
    if last < midpoint and last < anchor:
        return "SHORT"
    return "FLAT"


def _select_behavior(bias: str, signal_close: Decimal, support_low: Decimal, resistance_high: Decimal) -> str:
    if bias == "SHORT" and signal_close < support_low:
        return "BREAKOUT"
    if bias == "LONG" and signal_close > resistance_high:
        return "BREAKOUT"
    return "REJECTION"


def generate_plan(candles: tuple[Candle, ...] | list[Candle], *, symbol: str, as_of_epoch: int,
                  timeframe: str = "15min") -> dict[str, object]:
    """Build one plan from completed candles only."""
    if timeframe != "15min":
        raise ValueError("Phase-0 arm A is the structure 15m close; timeframe_structure must be 15min")
    completed = completed_candles(candles, timeframe, now_epoch=as_of_epoch, close_grace_seconds=0)
    needed = STRUCTURE_BARS + 1
    if len(completed) < needed:
        raise ValueError(f"need {needed} completed 15m bars, got {len(completed)}")
    structure = completed[-needed:-1]
    signal = completed[-1]
    swing_high = max(bar.high for bar in structure)
    swing_low = min(bar.low for bar in structure)
    if swing_high <= swing_low:
        raise ValueError("completed structure range is empty")
    width = (swing_high - swing_low) * ZONE_WIDTH_FRACTION
    support_low, support_high = swing_low, swing_low + width
    resistance_low, resistance_high = swing_high - width, swing_high
    bias = _bias(structure, swing_low, swing_high)
    behavior = _select_behavior(bias, signal.close, support_low, resistance_high)
    span = swing_high - swing_low
    if behavior == "BREAKOUT" and bias == "SHORT":
        band_low, band_high = support_low - width, support_low
        invalidation, target = swing_high, swing_low - span
    elif behavior == "BREAKOUT" and bias == "LONG":
        band_low, band_high = resistance_high, resistance_high + width
        invalidation, target = swing_low, swing_high + span
    elif bias == "LONG":
        band_low, band_high = support_low, support_high + width
        invalidation, target = swing_low, swing_high
    else:
        band_low, band_high = resistance_low - width, resistance_high
        invalidation, target = swing_high, swing_low
    low_bar = min(structure, key=lambda bar: (bar.low, bar.open_time))
    high_bar = max(structure, key=lambda bar: (bar.high, bar.open_time))
    if bias == "LONG":
        target_ref = f"{GENERATOR_RULE}:high:{high_bar.open_time}"
    else:
        target_ref = f"{GENERATOR_RULE}:low:{low_bar.open_time}"
    preimage = "\n".join(bar.content_hash for bar in (*structure, signal)) + f"\n{GENERATOR_RULE}"
    close_epoch = signal.open_time + period_seconds(timeframe)
    return build_plan_document(
        symbol=symbol,
        generated_at=_iso(close_epoch),
        bias=bias,
        zone_support=((money(support_low), money(support_high)),),
        zone_resistance=((money(resistance_low), money(resistance_high)),),
        behavior=behavior,
        invalidation_price=money(invalidation),
        target_price=money(target),
        target_structure_ref=target_ref,
        band_low=money(band_low),
        band_high=money(band_high),
        evidence_hash=sha256_text(preimage),
        signal_close_epoch=close_epoch,
        timeframe=timeframe,
    )
