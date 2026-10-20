"""Pre-registered, no-lookahead model conditions for R2 P4 research."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Mapping, Sequence

from ..market_data import Candle, period_seconds
from ..models import ModelCandidate, TrendSide
from ..strategy_decision import StrategyDecision
from .features import feature_vector

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class ModelConditionSpec:
    condition_id: str
    version: str
    model: str
    formula: str
    required_periods: tuple[str, ...] = ()
    usage: str = "RECORD_ONLY"


MODEL_CONDITION_SPECS = (
    ModelConditionSpec("DONCHIAN_BODY_RATIO_055", "r2-p4-v1", "DONCHIAN_BREAKOUT",
                       "abs(close-open)/(high-low)>=0.55"),
    ModelConditionSpec("DONCHIAN_BREAKOUT_ATR_010", "r2-p4-v1", "DONCHIAN_BREAKOUT",
                       "breakout distance>=0.10*ATR14"),
    ModelConditionSpec("DONCHIAN_VOLUME_110", "r2-p4-v1", "DONCHIAN_BREAKOUT",
                       "amount ratio20>=1.10"),
    ModelConditionSpec("DONCHIAN_60M_ALIGN", "r2-p4-v1", "DONCHIAN_BREAKOUT",
                       "60m EMA20/60/120 stack and price slope follow side", ("60min",)),
    ModelConditionSpec("FIB_60M_ALIGN", "r2-p4-v1", "FIB_PULLBACK_CONTINUATION",
                       "60m EMA20/60/120 stack and price slope follow side", ("60min",)),
    ModelConditionSpec("FIB_IMPULSE_ATR_300", "r2-p4-v1", "FIB_PULLBACK_CONTINUATION",
                       "signal structure span>=3*ATR14"),
    ModelConditionSpec("FIB_DEPTH_CENTER", "r2-p4-v1", "FIB_PULLBACK_CONTINUATION",
                       "close lies in central half of 0.382-0.618 retracement zone"),
    ModelConditionSpec("FIB_VOLUME_100", "r2-p4-v1", "FIB_PULLBACK_CONTINUATION",
                       "amount ratio20>=1.00"),
    ModelConditionSpec("EMA_60M_ALIGN", "r2-p4-v1", "EMA_MOMENTUM_CONTINUATION",
                       "60m EMA20/60/120 stack and EMA20 slope follow side", ("60min",)),
    ModelConditionSpec("EMA_4H_NOT_REVERSE", "r2-p4-v1", "EMA_MOMENTUM_CONTINUATION",
                       "4h EMA stack is aligned or mixed, never opposite", ("4hour",)),
    ModelConditionSpec("EMA_ADX_RISING", "r2-p4-v1", "EMA_MOMENTUM_CONTINUATION",
                       "ADX14 is greater than prior closed-bar ADX14"),
    ModelConditionSpec("EMA_MOMENTUM_RECLAIM", "r2-p4-v1", "EMA_MOMENTUM_CONTINUATION",
                       "close is on trend side of VWAP20 and within 0.5 ATR"),
    ModelConditionSpec("EMA_TARGET_SPACE_120", "r2-p4-v1", "EMA_MOMENTUM_CONTINUATION",
                       "candidate expected reward/risk>=1.20"),
    ModelConditionSpec("DONCHIAN_1D_TREND", "r2-p5-v1", "DONCHIAN_BREAKOUT",
                       "closed 1d EMA20/60/120 stack, OLS price slope and ROC12 follow side; ADX14>=16",
                       ("1day",), "RESEARCH_GATE"),
    ModelConditionSpec("FIB_1D_TREND", "r2-p5-v1", "FIB_PULLBACK_CONTINUATION",
                       "closed 1d EMA20/60/120 stack, OLS price slope and ROC12 follow side; ADX14>=16",
                       ("1day",), "RESEARCH_GATE"),
    ModelConditionSpec("EMA_1D_TREND", "r2-p5-v1", "EMA_MOMENTUM_CONTINUATION",
                       "closed 1d EMA20/60/120 stack, OLS price slope and ROC12 follow side; ADX14>=16",
                       ("1day",), "RESEARCH_GATE"),
)

_SPECS = {item.condition_id: item for item in MODEL_CONDITION_SPECS}


def required_periods(condition_id: str | None) -> tuple[str, ...]:
    if condition_id is None:
        return ()
    try:
        return _SPECS[condition_id].required_periods
    except KeyError as error:
        raise ValueError(f"unknown research model condition: {condition_id}") from error


def model_condition_passes(
    condition_id: str,
    *,
    condition_version: str | None,
    candidate: ModelCandidate,
    decision: StrategyDecision,
    entry_candles: Sequence[Candle],
    context: Mapping[str, Sequence[Candle]],
) -> bool:
    """Evaluate a condition using only bars closed by the signal close."""
    return model_condition_status(
        condition_id, condition_version=condition_version, candidate=candidate,
        decision=decision, entry_candles=entry_candles, context=context) == "PASS"


def model_condition_status(
    condition_id: str,
    *,
    condition_version: str | None,
    candidate: ModelCandidate,
    decision: StrategyDecision,
    entry_candles: Sequence[Candle],
    context: Mapping[str, Sequence[Candle]],
) -> str:
    """Return PASS/FAIL/UNKNOWN without treating missing higher-timeframe data as neutral."""
    try:
        spec = _SPECS[condition_id]
    except KeyError as error:
        raise ValueError(f"unknown research model condition: {condition_id}") from error
    if condition_version is not None and condition_version != spec.version:
        raise ValueError(f"research condition version mismatch: {condition_id}:{condition_version}")
    if candidate.model.value != spec.model:
        raise ValueError(f"condition {condition_id} cannot filter model {candidate.model.value}")
    if not entry_candles or decision.signal.indicators is None:
        return "UNKNOWN"

    candle, factors, side = entry_candles[-1], decision.signal.indicators, candidate.side
    direction = Decimal("1") if side is TrendSide.LONG else Decimal("-1")
    span = candle.high-candle.low
    if condition_id == "DONCHIAN_BODY_RATIO_055":
        passed = span > ZERO and abs(candle.close-candle.open)/span >= Decimal("0.55")
        return "PASS" if passed else "FAIL"
    if condition_id == "DONCHIAN_BREAKOUT_ATR_010":
        boundary = decision.signal.breakout_high if side is TrendSide.LONG else decision.signal.breakout_low
        if boundary is None:
            return "UNKNOWN"
        return "PASS" if (candle.close-boundary)*direction >= factors.atr*Decimal("0.10") else "FAIL"
    if condition_id == "DONCHIAN_VOLUME_110":
        return "PASS" if decision.signal.volume_ratio >= Decimal("1.10") else "FAIL"
    if condition_id in {"DONCHIAN_60M_ALIGN", "FIB_60M_ALIGN"}:
        values = _closed_context(context, "60min", candle.open_time+period_seconds(decision.period))
        if len(values) < 121:
            return "UNKNOWN"
        return "PASS" if _aligned(values, side, slope="price") else "FAIL"
    if condition_id == "FIB_IMPULSE_ATR_300":
        passed = candidate.structure.swing_high-candidate.structure.swing_low >= factors.atr*Decimal("3")
        return "PASS" if passed else "FAIL"
    if condition_id == "FIB_DEPTH_CENTER":
        indexes = [index for index, item in enumerate(entry_candles)
                   if item.open_time == candidate.source_breakout_time]
        if not indexes or indexes[0] >= len(entry_candles)-1:
            return "UNKNOWN"
        impulse = entry_candles[indexes[0]:-1]
        high, low = max(item.high for item in impulse), min(item.low for item in impulse)
        impulse_span = high-low
        if impulse_span <= ZERO:
            return "FAIL"
        if side is TrendSide.LONG:
            lower, upper = high-impulse_span*Decimal("0.618"), high-impulse_span*Decimal("0.382")
        else:
            lower, upper = low+impulse_span*Decimal("0.382"), low+impulse_span*Decimal("0.618")
        quarter = (upper-lower)/Decimal("4")
        return "PASS" if lower+quarter <= candle.close <= upper-quarter else "FAIL"
    if condition_id == "FIB_VOLUME_100":
        return "PASS" if decision.signal.volume_ratio >= Decimal("1.00") else "FAIL"
    if condition_id == "EMA_60M_ALIGN":
        values = _closed_context(context, "60min", candle.open_time+period_seconds(decision.period))
        if len(values) < 121:
            return "UNKNOWN"
        return "PASS" if _aligned(values, side, slope="ema") else "FAIL"
    if condition_id == "EMA_4H_NOT_REVERSE":
        values = _closed_context(context, "4hour", candle.open_time+period_seconds(decision.period))
        if len(values) < 121:
            return "UNKNOWN"
        vector = feature_vector(values[-160:])
        opposite = ((vector.ema20 < vector.ema60 < vector.ema120) if side is TrendSide.LONG
                    else (vector.ema20 > vector.ema60 > vector.ema120))
        return "FAIL" if opposite else "PASS"
    if condition_id == "EMA_ADX_RISING":
        if len(entry_candles) < 122:
            return "UNKNOWN"
        return "PASS" if factors.adx > feature_vector(entry_candles[:-1]).adx14 else "FAIL"
    if condition_id == "EMA_MOMENTUM_RECLAIM":
        delta = (candle.close-factors.vwap)*direction
        return "PASS" if delta >= ZERO and delta <= factors.atr*Decimal("0.5") else "FAIL"
    if condition_id == "EMA_TARGET_SPACE_120":
        return "PASS" if candidate.expected_r >= Decimal("1.20") else "FAIL"
    if condition_id in {"DONCHIAN_1D_TREND", "FIB_1D_TREND", "EMA_1D_TREND"}:
        values = _closed_context(context, "1day", candle.open_time+period_seconds(decision.period))
        if len(values) < 121:
            return "UNKNOWN"
        vector = feature_vector(values[-160:])
        if side is TrendSide.LONG:
            passed = (vector.ema20 > vector.ema60 > vector.ema120
                      and vector.close_slope20 > ZERO and vector.roc12 > ZERO
                      and vector.adx14 >= Decimal("16"))
        else:
            passed = (vector.ema20 < vector.ema60 < vector.ema120
                      and vector.close_slope20 < ZERO and vector.roc12 < ZERO
                      and vector.adx14 >= Decimal("16"))
        return "PASS" if passed else "FAIL"
    raise AssertionError(f"unhandled research condition: {condition_id}")


def _closed_context(context: Mapping[str, Sequence[Candle]], period: str,
                    signal_close: int) -> tuple[Candle, ...]:
    seconds = period_seconds(period)
    return tuple(item for item in context.get(period, ()) if item.open_time+seconds <= signal_close)


def _aligned(candles: Sequence[Candle], side: TrendSide, *, slope: str) -> bool:
    if len(candles) < 121:
        return False
    vector = feature_vector(candles[-160:])
    if side is TrendSide.LONG:
        stack = vector.ema20 > vector.ema60 > vector.ema120
        value = vector.close_slope20 if slope == "price" else vector.ema20_slope10
        return stack and value > ZERO
    stack = vector.ema20 < vector.ema60 < vector.ema120
    value = vector.close_slope20 if slope == "price" else vector.ema20_slope10
    return stack and value < ZERO
