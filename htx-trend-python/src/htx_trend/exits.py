"""Python implementation of the trend exit engine v4.

This module is pure: it returns a decision and the durable state that must be
saved by a ledger.  It never sends an HTX order.  The v4 name is intentional:
Python must match the production policy before a newer policy is discussed.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, ROUND_DOWN
from enum import StrEnum

from .models import ModelCode, TrendSide

ZERO = Decimal("0")


class ExitAction(StrEnum):
    HOLD = "HOLD"
    ARM_TRAILING = "ARM_TRAILING"
    PARTIAL_CLOSE = "PARTIAL_CLOSE"
    FULL_CLOSE = "FULL_CLOSE"


class ExitPhase(StrEnum):
    THESIS_INTACT = "THESIS_INTACT"
    PROFIT_AT_RISK = "PROFIT_AT_RISK"
    PROFIT_PROTECTED = "PROFIT_PROTECTED"
    RUNNER = "RUNNER"
    REVERSAL_CONFIRMED = "REVERSAL_CONFIRMED"
    INVALIDATED = "INVALIDATED"
    THESIS_EXPIRED = "THESIS_EXPIRED"
    FUNDING_RATE = "FUNDING_RATE"


class HoldingState(StrEnum):
    TRENDING = "TRENDING"
    WEAKENING = "WEAKENING"
    INVALIDATED = "INVALIDATED"


@dataclass(frozen=True, slots=True)
class ProfitProtectionPolicy:
    arm_atr_multiplier: Decimal = Decimal("0.50")
    lock_atr_multiplier: Decimal = Decimal("0.15")
    partial_atr_multiplier: Decimal = Decimal("0.75")
    pullback_atr_multiplier: Decimal = Decimal("0.40")
    min_net_fee_multiple: Decimal = Decimal("2.00")
    protection_breach_confirmation_bars: int = 2


@dataclass(frozen=True, slots=True)
class ExitInput:
    primary_model: ModelCode
    side: TrendSide
    remaining_contracts: int
    contract_size: Decimal
    entry_price: Decimal
    initial_stop_price: Decimal
    invalidation_price: Decimal
    structure_target_price: Decimal | None
    reference_price: Decimal
    protection_price: Decimal | None
    highest_price: Decimal
    lowest_price: Decimal
    atr: Decimal
    signal_side: TrendSide
    ema_fast: Decimal | None
    ema_slow: Decimal | None
    major_timeframe_opposition: int
    market_context_opposes: bool
    current_net_pnl: Decimal
    entry_fee: Decimal
    estimated_close_fee: Decimal
    trailing_armed_at: datetime | None
    trailing_arm_reason: str | None
    max_favorable_r: Decimal | None
    evaluated_at: datetime
    flat_streak: int = 0
    required_flat_streak: int = 3
    flat_loss_budget: Decimal = ZERO
    policy: ProfitProtectionPolicy = ProfitProtectionPolicy()
    protection_breach_bars: int = 0
    # Current adverse funding rate gate; never cumulative settled funding PnL.
    funding_rate_exit_reason: str | None = None
    observed_mfe_net_pnl: Decimal | None = None
    assessed_state: HoldingState | None = None


@dataclass(frozen=True, slots=True)
class ExitDecision:
    phase: ExitPhase
    action: ExitAction
    target_remaining_contracts: int | None
    protection_price: Decimal | None
    max_favorable_r: Decimal
    trailing_armed_at: datetime | None
    trailing_arm_reason: str | None
    reason: str
    evidence: dict[str, str | int | bool | Decimal | None]


def evaluate_holding(input_: ExitInput) -> HoldingState:
    opposite = ((input_.side is TrendSide.LONG and input_.signal_side is TrendSide.SHORT)
                or (input_.side is TrendSide.SHORT and input_.signal_side is TrendSide.LONG))
    if opposite or (input_.major_timeframe_opposition >= 2 and input_.market_context_opposes):
        return HoldingState.INVALIDATED
    ema_against = ((input_.side is TrendSide.LONG and _below(input_.ema_fast, input_.ema_slow))
                   or (input_.side is TrendSide.SHORT and _above(input_.ema_fast, input_.ema_slow)))
    if input_.signal_side is TrendSide.FLAT or ema_against or input_.major_timeframe_opposition > 0 or input_.market_context_opposes:
        return HoldingState.WEAKENING
    return HoldingState.TRENDING


def decide_v4(input_: ExitInput) -> ExitDecision:
    """Return the V4 decision policy for all primary models."""
    if input_.primary_model not in tuple(ModelCode) or not _valid(input_):
        return _decision(input_, ExitPhase.THESIS_INTACT, ExitAction.HOLD, None, input_.protection_price,
                         ZERO if input_.max_favorable_r is None else input_.max_favorable_r,
                         input_.trailing_armed_at, input_.trailing_arm_reason, "unsupported-or-incomplete")
    policy = input_.policy
    risk_distance = abs(input_.entry_price - input_.initial_stop_price)
    favorable = _favorable(input_)
    max_r = max(favorable / risk_distance, input_.max_favorable_r or ZERO)
    adverse = _adverse_from_best(input_)
    cost_distance = (input_.entry_fee + input_.estimated_close_fee) / (input_.contract_size * input_.remaining_contracts)
    current_state = input_.assessed_state or evaluate_holding(input_)
    structure_reached = _target_reached(input_.side, input_.reference_price, input_.structure_target_price)
    profitable = input_.current_net_pnl > ZERO
    minimum_mfe_profit = input_.estimated_close_fee * policy.min_net_fee_multiple
    mfe_net = max(input_.observed_mfe_net_pnl or ZERO, favorable * input_.contract_size * input_.remaining_contracts - input_.entry_fee - input_.estimated_close_fee)
    net_mfe_floor = profitable and mfe_net >= minimum_mfe_profit and favorable >= cost_distance
    profit_lock_distance = cost_distance + input_.atr * policy.lock_atr_multiplier
    arm_distance = max(input_.atr * policy.arm_atr_multiplier, profit_lock_distance)
    cost_covered = input_.current_net_pnl >= input_.estimated_close_fee * policy.min_net_fee_multiple
    milestone = ((profitable and (structure_reached or max_r >= Decimal("1") or (cost_covered and favorable >= arm_distance)))
                 or net_mfe_floor)
    armed_at, arm_reason, newly_armed = input_.trailing_armed_at, input_.trailing_arm_reason, False
    if armed_at is None and milestone:
        armed_at, newly_armed = input_.evaluated_at, True
        arm_reason = ("STRUCTURE_TARGET" if structure_reached else "RISK_REWARD_MILESTONE" if max_r >= Decimal("1")
                      else "NET_COST_AND_ATR_MILESTONE" if cost_covered and favorable >= arm_distance
                      else "NET_POSITIVE_MFE_COST_FLOOR")
    base_lock = _protection(input_.side, input_.entry_price, cost_distance if arm_reason == "NET_POSITIVE_MFE_COST_FLOOR" else profit_lock_distance)
    protection = input_.protection_price if armed_at is None else _tighten(input_.side, input_.protection_price, base_lock)
    if armed_at is not None and current_state is HoldingState.WEAKENING:
        protection = _tighten(input_.side, protection, _protection(input_.side, input_.reference_price, -input_.atr * Decimal("0.15")))

    invalidated = _crossed(input_.side, input_.reference_price, input_.invalidation_price) or _crossed(input_.side, input_.reference_price, input_.initial_stop_price)
    if invalidated or current_state is HoldingState.INVALIDATED:
        return _decision(input_, ExitPhase.INVALIDATED, ExitAction.FULL_CLOSE, 0, protection, max_r, armed_at, arm_reason,
                         "model-or-initial-stop-invalidated", current_state, favorable, adverse, mfe_net)
    independent_failure = _opposite(input_) or _ema_against(input_) or input_.major_timeframe_opposition > 0 or input_.market_context_opposes
    if armed_at is None and input_.signal_side is TrendSide.FLAT and input_.flat_streak >= input_.required_flat_streak and input_.current_net_pnl < ZERO and input_.flat_loss_budget > ZERO and -input_.current_net_pnl >= input_.flat_loss_budget and independent_failure:
        return _decision(input_, ExitPhase.THESIS_EXPIRED, ExitAction.FULL_CLOSE, 0, protection, max_r, None, None,
                         "unarmed-flat-loss-budget-with-independent-failure", current_state, favorable, adverse, mfe_net)

    dynamic_pullback = input_.atr > ZERO and adverse >= input_.atr
    early_pullback = input_.atr > ZERO and adverse >= input_.atr * policy.pullback_atr_multiplier
    reversal_evidence = int(dynamic_pullback) + int(_opposite(input_) or (input_.signal_side is TrendSide.FLAT and _ema_against(input_))) + int(input_.major_timeframe_opposition > 0 or input_.market_context_opposes)
    expected_reward = _expected_reward(input_)
    protected_risk = _protected_risk(input_, protection)
    continuation = current_state is HoldingState.TRENDING and expected_reward > protected_risk and reversal_evidence <= 1
    reference_breach = armed_at is not None and (not newly_armed or arm_reason == "NET_POSITIVE_MFE_COST_FLOOR") and _crossed(input_.side, input_.reference_price, protection)
    confirmed_breach = armed_at is not None and not newly_armed and input_.protection_breach_bars >= policy.protection_breach_confirmation_bars

    if input_.funding_rate_exit_reason and not reference_breach:
        return _decision(input_, ExitPhase.FUNDING_RATE, ExitAction.FULL_CLOSE, 0, protection, max_r, armed_at, arm_reason,
                         f"funding-rate-exit: {input_.funding_rate_exit_reason}", current_state, favorable, adverse, mfe_net)
    if reference_breach and current_state is HoldingState.WEAKENING:
        if profitable and expected_reward > protected_risk:
            remaining = _target_remaining(input_.remaining_contracts, expected_reward, protected_risk, adverse, favorable)
            if remaining > 0:
                return _decision(input_, ExitPhase.PROFIT_AT_RISK, ExitAction.PARTIAL_CLOSE, remaining, protection, max_r, armed_at, arm_reason,
                                 "trend-weakened-protection-breach-partial", current_state, favorable, adverse, mfe_net)
        return _decision(input_, ExitPhase.REVERSAL_CONFIRMED, ExitAction.FULL_CLOSE, 0, protection, max_r, armed_at, arm_reason,
                         "trend-weakened-protection-breach", current_state, favorable, adverse, mfe_net)
    if reference_breach and dynamic_pullback:
        return _decision(input_, ExitPhase.REVERSAL_CONFIRMED, ExitAction.FULL_CLOSE, 0, protection, max_r, armed_at, arm_reason,
                         "armed-protection-breach-atr-impulse", current_state, favorable, adverse, mfe_net)
    if confirmed_breach:
        return _decision(input_, ExitPhase.REVERSAL_CONFIRMED, ExitAction.FULL_CLOSE, 0, protection, max_r, armed_at, arm_reason,
                         "confirmed-profit-protection-breach", current_state, favorable, adverse, mfe_net)
    if reference_breach:
        return _decision(input_, ExitPhase.PROFIT_AT_RISK, ExitAction.ARM_TRAILING, None, protection, max_r, armed_at, arm_reason,
                         "armed-protection-breach-awaiting-bar-confirmation", current_state, favorable, adverse, mfe_net)
    partial_qualified = cost_covered and favorable >= input_.atr * policy.partial_atr_multiplier
    if not newly_armed and profitable and partial_qualified and not continuation and (early_pullback or current_state is HoldingState.WEAKENING) and (reversal_evidence >= 2 or current_state is HoldingState.WEAKENING):
        remaining = _target_remaining(input_.remaining_contracts, expected_reward, protected_risk, adverse, favorable)
        action = ExitAction.PARTIAL_CLOSE if remaining > 0 else ExitAction.FULL_CLOSE
        phase = ExitPhase.PROFIT_AT_RISK if remaining > 0 else ExitPhase.REVERSAL_CONFIRMED
        return _decision(input_, phase, action, remaining, protection, max_r, armed_at, arm_reason,
                         "profit-at-risk-with-deteriorating-continuation", current_state, favorable, adverse, mfe_net)
    phase = ExitPhase.RUNNER if armed_at is not None and continuation else ExitPhase.PROFIT_AT_RISK if profitable else ExitPhase.THESIS_INTACT
    return _decision(input_, phase, ExitAction.ARM_TRAILING if armed_at is not None else ExitAction.HOLD, None, protection, max_r,
                     armed_at, arm_reason, "runner-hold-with-positive-remaining-value" if continuation else "thesis-not-yet-actionable", current_state, favorable, adverse, mfe_net)


def _decision(input_: ExitInput, phase: ExitPhase, action: ExitAction, remaining: int | None, protection: Decimal | None,
              max_r: Decimal, armed_at: datetime | None, arm_reason: str | None, reason: str, state: HoldingState | None = None,
              favorable: Decimal | None = None, adverse: Decimal | None = None, mfe: Decimal | None = None) -> ExitDecision:
    return ExitDecision(phase, action, remaining, protection, max_r, armed_at, arm_reason, reason,
                        {"schema": "trend-exit-decision-v4", "state": state.value if state else None, "favorable": favorable,
                         "adverse": adverse, "observed_mfe_net_pnl": mfe, "flat_streak": input_.flat_streak,
                         "protection_breach_bars": input_.protection_breach_bars})


def _valid(x: ExitInput) -> bool:
    return x.remaining_contracts > 0 and x.contract_size > ZERO and x.entry_price > ZERO and x.initial_stop_price > ZERO and x.reference_price > ZERO and x.highest_price > ZERO and x.lowest_price > ZERO

def _favorable(x: ExitInput) -> Decimal: return max(x.highest_price - x.entry_price, ZERO) if x.side is TrendSide.LONG else max(x.entry_price - x.lowest_price, ZERO)
def _adverse_from_best(x: ExitInput) -> Decimal: return max(x.highest_price - x.reference_price, ZERO) if x.side is TrendSide.LONG else max(x.reference_price - x.lowest_price, ZERO)
def _protection(side: TrendSide, price: Decimal, distance: Decimal) -> Decimal: return price + distance if side is TrendSide.LONG else price - distance
def _tighten(side: TrendSide, current: Decimal | None, candidate: Decimal | None) -> Decimal | None:
    if current is None or current <= ZERO: return candidate
    if candidate is None or candidate <= ZERO: return current
    return max(current, candidate) if side is TrendSide.LONG else min(current, candidate)
def _crossed(side: TrendSide, price: Decimal, boundary: Decimal | None) -> bool: return boundary is not None and boundary > ZERO and (price <= boundary if side is TrendSide.LONG else price >= boundary)
def _target_reached(side: TrendSide, price: Decimal, target: Decimal | None) -> bool: return target is not None and target > ZERO and (price >= target if side is TrendSide.LONG else price <= target)
def _above(first: Decimal | None, second: Decimal | None) -> bool: return first is not None and second is not None and first > second
def _below(first: Decimal | None, second: Decimal | None) -> bool: return first is not None and second is not None and first < second
def _ema_against(x: ExitInput) -> bool: return _below(x.ema_fast, x.ema_slow) if x.side is TrendSide.LONG else _above(x.ema_fast, x.ema_slow)
def _opposite(x: ExitInput) -> bool: return (x.side is TrendSide.LONG and x.signal_side is TrendSide.SHORT) or (x.side is TrendSide.SHORT and x.signal_side is TrendSide.LONG)
def _expected_reward(x: ExitInput) -> Decimal:
    if x.structure_target_price is None: return ZERO
    move = x.structure_target_price - x.reference_price if x.side is TrendSide.LONG else x.reference_price - x.structure_target_price
    return max(move, ZERO) * x.contract_size * x.remaining_contracts - x.estimated_close_fee
def _protected_risk(x: ExitInput, protection: Decimal | None) -> Decimal:
    if protection is None: return ZERO
    return abs(x.reference_price - protection) * x.contract_size * x.remaining_contracts + x.estimated_close_fee
def _target_remaining(remaining: int, reward: Decimal, risk: Decimal, adverse: Decimal, favorable: Decimal) -> int:
    if remaining <= 1 or reward <= ZERO or risk <= ZERO: return 0
    ratio = reward / (reward + risk) * (Decimal("1") - min(Decimal("1"), adverse / favorable if favorable > ZERO else Decimal("1")))
    return min(remaining - 1, max(0, int((Decimal(remaining) * ratio).to_integral_value(rounding=ROUND_DOWN))))


def decide(input_: ExitInput, *, legacy_trailing_atr_multiplier: Decimal = Decimal("2"),
           legacy_trailing_activation_r: Decimal = Decimal("1")) -> ExitDecision:
    """Route every supported primary model through V4."""
    return decide_v4(input_)


def decide_legacy(input_: ExitInput, *, trailing_atr_multiplier: Decimal, trailing_activation_r: Decimal) -> ExitDecision:
    """Explicit historical Java exit policy for replay and compatibility tests."""
    if not _valid(input_):
        return _decision(input_, ExitPhase.THESIS_INTACT, ExitAction.HOLD, None, input_.protection_price, ZERO,
                         input_.trailing_armed_at, input_.trailing_arm_reason, "legacy-unsupported-or-incomplete")
    risk = abs(input_.entry_price - input_.initial_stop_price)
    favorable = _favorable(input_)
    max_r = max(favorable / risk, input_.max_favorable_r or ZERO)
    if _crossed(input_.side, input_.reference_price, input_.invalidation_price) or _crossed(input_.side, input_.reference_price, input_.initial_stop_price):
        return _decision(input_, ExitPhase.INVALIDATED, ExitAction.FULL_CLOSE, 0, input_.protection_price, max_r,
                         input_.trailing_armed_at, input_.trailing_arm_reason, "legacy-model-or-initial-stop-invalidated")
    if _opposite(input_):
        return _decision(input_, ExitPhase.REVERSAL_CONFIRMED, ExitAction.FULL_CLOSE, 0, input_.protection_price, max_r,
                         input_.trailing_armed_at, input_.trailing_arm_reason, "legacy-opposite-trend-signal")
    arm = input_.trailing_armed_at
    if arm is None and max_r >= trailing_activation_r:
        arm = input_.evaluated_at
    distance = input_.atr * trailing_atr_multiplier
    trail = input_.highest_price - distance if input_.side is TrendSide.LONG else input_.lowest_price + distance
    protection = _tighten(input_.side, input_.protection_price, trail) if arm is not None else input_.protection_price
    if arm is not None and _crossed(input_.side, input_.reference_price, protection):
        return _decision(input_, ExitPhase.REVERSAL_CONFIRMED, ExitAction.FULL_CLOSE, 0, protection, max_r, arm,
                         input_.trailing_arm_reason or "LEGACY_ATR_MILESTONE", "legacy-atr-trailing-stop")
    state = evaluate_holding(input_)
    return _decision(input_, ExitPhase.RUNNER if arm else ExitPhase.THESIS_INTACT,
                     ExitAction.ARM_TRAILING if arm else ExitAction.HOLD, None, protection, max_r, arm,
                     input_.trailing_arm_reason or ("LEGACY_ATR_MILESTONE" if arm else None), "legacy-hold", state,
                     favorable, _adverse_from_best(input_))
