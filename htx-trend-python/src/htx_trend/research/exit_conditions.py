"""Versioned, research-only holding and exit conditions for R2 P6.

The conditions in this module can only shorten a frozen baseline trade.  They
do not replace the executable V4 policy and cannot reach PAPER or LIVE.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum

from ..models import ModelCandidate, ModelCode, TrendSide

ZERO = Decimal("0")


class ConditionStatus(StrEnum):
    PASS = "PASS"
    FAIL = "FAIL"
    UNKNOWN = "UNKNOWN"
    SKIPPED = "SKIPPED"


class ResearchExitAction(StrEnum):
    HOLD = "HOLD"
    ARM_PROTECTION = "ARM_PROTECTION"
    FULL_CLOSE = "FULL_CLOSE"


@dataclass(frozen=True, slots=True)
class ExitConditionSpec:
    condition_id: str
    version: str
    purpose: str
    formula: str


@dataclass(frozen=True, slots=True)
class ExitConditionContext:
    candidate: ModelCandidate
    bars_held: int
    entry_price: Decimal
    close: Decimal
    atr: Decimal
    adx: Decimal
    roc: Decimal
    ema_fast: Decimal
    ema_slow: Decimal
    close_slope20: Decimal | None
    ema_slope10: Decimal | None
    current_net_pnl: Decimal
    observed_mfe_net_pnl: Decimal
    estimated_close_fee: Decimal
    position_multiplier: Decimal
    max_favorable_r: Decimal
    signal_side: TrendSide
    decay_streak: int
    profile: str


@dataclass(frozen=True, slots=True)
class ExitConditionResult:
    condition_id: str
    version: str
    status: ConditionStatus
    actual: dict[str, Decimal | int | str | bool | None]
    threshold: dict[str, Decimal | int | str | bool]
    action: ResearchExitAction
    state_before: str
    state_after: str
    reason: str
    protection_price: Decimal | None = None
    next_decay_streak: int = 0


EXIT_CONDITION_SPECS = (
    ExitConditionSpec("exit.no_progress_timeout", "r2-p6-v1", "close stale risk",
                      "bars>=limit and max_favorable_r<0.25 and current_net_pnl<=0"),
    ExitConditionSpec("exit.cost_covered_protection", "r2-p6-v1", "protect paid trading cost",
                      "mfe_net>=2*close_fee and max_favorable_r>=0.25; protect entry plus round-trip cost"),
    ExitConditionSpec("exit.mfe_giveback", "r2-p6-v1", "retain profitable excursion",
                      "mfe_net>=2*close_fee, current_net_pnl>0 and giveback>=50%"),
    ExitConditionSpec("exit.trend_decay", "r2-p6-v1", "close persistent directional decay",
                      "at least 3/4 decay votes for two consecutive closed bars"),
    ExitConditionSpec("exit.opposite_signal", "r2-p6-v1", "audit formal opposite signal",
                      "signal_side is the position's opposite side"),
    ExitConditionSpec("exit.structure_invalidated", "r2-p6-v1", "close model structure loss",
                      "Donchian re-enters box, Fibonacci loses fib boundary, or EMA loses slow EMA"),
)

EXIT_CONDITIONS = {item.condition_id: item for item in EXIT_CONDITION_SPECS}


def evaluate_exit_condition(condition_id: str, context: ExitConditionContext) -> ExitConditionResult:
    spec = EXIT_CONDITIONS.get(condition_id)
    if spec is None:
        raise ValueError(f"unknown research exit condition: {condition_id}")
    if context.atr <= ZERO or context.entry_price <= ZERO:
        return _result(spec, ConditionStatus.UNKNOWN, {}, {}, ResearchExitAction.HOLD,
                       "required price or ATR is unavailable")
    if condition_id == "exit.no_progress_timeout":
        limit = 16 if context.profile == "INTRADAY_15M" else 6
        passed = (context.bars_held >= limit and context.max_favorable_r < Decimal("0.25")
                  and context.current_net_pnl <= ZERO)
        return _boolean(spec, passed,
                        {"bars_held": context.bars_held, "max_favorable_r": context.max_favorable_r,
                         "current_net_pnl": context.current_net_pnl},
                        {"bars_limit": limit, "max_favorable_r": Decimal("0.25"),
                         "current_net_pnl": ZERO}, ResearchExitAction.FULL_CLOSE,
                        "position made no useful progress within the frozen holding limit")
    if condition_id == "exit.cost_covered_protection":
        cost_floor = context.estimated_close_fee * Decimal("2")
        passed = (context.observed_mfe_net_pnl >= cost_floor
                  and context.max_favorable_r >= Decimal("0.25"))
        distance = (context.estimated_close_fee * Decimal("2") / context.position_multiplier
                    if context.position_multiplier > ZERO else ZERO)
        protection = (context.entry_price + distance if context.candidate.side is TrendSide.LONG
                      else context.entry_price - distance)
        return _boolean(spec, passed,
                        {"observed_mfe_net_pnl": context.observed_mfe_net_pnl,
                         "max_favorable_r": context.max_favorable_r},
                        {"mfe_cost_multiple": Decimal("2"), "minimum_r": Decimal("0.25")},
                        ResearchExitAction.ARM_PROTECTION,
                        "net favorable excursion covered the frozen cost floor", protection)
    if condition_id == "exit.mfe_giveback":
        cost_floor = context.estimated_close_fee * Decimal("2")
        giveback = ((context.observed_mfe_net_pnl-context.current_net_pnl)
                    / context.observed_mfe_net_pnl if context.observed_mfe_net_pnl > ZERO else ZERO)
        passed = (context.observed_mfe_net_pnl >= cost_floor and context.current_net_pnl > ZERO
                  and giveback >= Decimal("0.50"))
        return _boolean(spec, passed,
                        {"observed_mfe_net_pnl": context.observed_mfe_net_pnl,
                         "current_net_pnl": context.current_net_pnl, "giveback_ratio": giveback},
                        {"mfe_cost_multiple": Decimal("2"), "giveback_ratio": Decimal("0.50")},
                        ResearchExitAction.FULL_CLOSE,
                        "profitable excursion gave back at least half")
    if condition_id == "exit.trend_decay":
        if context.close_slope20 is None or context.ema_slope10 is None:
            return _result(spec, ConditionStatus.UNKNOWN, {}, {"decay_votes": 3, "streak": 2},
                           ResearchExitAction.HOLD, "slope warm-up is incomplete",
                           next_decay_streak=0)
        direction = Decimal("1") if context.candidate.side is TrendSide.LONG else Decimal("-1")
        votes = sum((context.adx < Decimal("18"), direction*context.roc <= ZERO,
                     direction*context.close_slope20 <= ZERO, direction*context.ema_slope10 <= ZERO))
        streak = context.decay_streak + 1 if votes >= 3 else 0
        passed = streak >= 2
        return _boolean(spec, passed,
                        {"decay_votes": votes, "decay_streak": streak, "adx": context.adx,
                         "roc": context.roc, "close_slope20": context.close_slope20,
                         "ema_slope10": context.ema_slope10},
                        {"decay_votes": 3, "streak": 2}, ResearchExitAction.FULL_CLOSE,
                        "directional trend decay persisted for two closed bars",
                        next_decay_streak=streak)
    if condition_id == "exit.opposite_signal":
        opposite = ((context.candidate.side is TrendSide.LONG and context.signal_side is TrendSide.SHORT)
                    or (context.candidate.side is TrendSide.SHORT and context.signal_side is TrendSide.LONG))
        return _boolean(spec, opposite, {"signal_side": context.signal_side.value},
                        {"required": "OPPOSITE"}, ResearchExitAction.FULL_CLOSE,
                        "the shared signal core produced an opposite direction")
    boundary = _structure_boundary(context.candidate, context.ema_slow)
    crossed = (context.close <= boundary if context.candidate.side is TrendSide.LONG
               else context.close >= boundary)
    return _boolean(spec, crossed, {"close": context.close, "boundary": boundary},
                    {"crossed": True}, ResearchExitAction.FULL_CLOSE,
                    "the model-specific structure boundary was lost")


def _structure_boundary(candidate: ModelCandidate, ema_slow: Decimal) -> Decimal:
    if candidate.model is ModelCode.DONCHIAN_BREAKOUT:
        return candidate.structure.box_high if candidate.side is TrendSide.LONG else candidate.structure.box_low
    if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION:
        return candidate.structure.fib_lower if candidate.side is TrendSide.LONG else candidate.structure.fib_upper
    return ema_slow


def _boolean(spec: ExitConditionSpec, passed: bool, actual: dict, threshold: dict,
             action: ResearchExitAction, reason: str, protection: Decimal | None = None,
             next_decay_streak: int = 0) -> ExitConditionResult:
    return _result(spec, ConditionStatus.PASS if passed else ConditionStatus.FAIL, actual, threshold,
                   action if passed else ResearchExitAction.HOLD,
                   reason if passed else "condition threshold not met", protection if passed else None,
                   next_decay_streak=next_decay_streak)


def _result(spec: ExitConditionSpec, status: ConditionStatus, actual: dict, threshold: dict,
            action: ResearchExitAction, reason: str, protection: Decimal | None = None,
            *, next_decay_streak: int = 0) -> ExitConditionResult:
    state_after = ("CLOSED" if action is ResearchExitAction.FULL_CLOSE else
                   "PROTECTED" if action is ResearchExitAction.ARM_PROTECTION else "OPEN")
    return ExitConditionResult(spec.condition_id, spec.version, status, actual, threshold,
                               action, "OPEN", state_after, reason, protection, next_decay_streak)
