"""Versioned, side-effect-free research conditions for R2 candidate audits."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Sequence

from ..indicators import IndicatorSnapshot
from ..market_data import Candle
from ..models import TrendSide
from .features import feature_vector

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class ResearchConditionSpec:
    condition_id: str
    version: str
    formula: str
    usage: str = "RECORD_ONLY"
    legacy: bool = False


@dataclass(frozen=True, slots=True)
class ResearchConditionResult:
    condition_id: str
    version: str
    passed: bool
    actual: Decimal | str
    threshold: Decimal | str


CONDITION_SPECS = (
    ResearchConditionSpec("ADD_RSI", "r2-p3-v1", "LONG RSI14>=50; SHORT RSI14<=50"),
    ResearchConditionSpec("ADD_MACD", "r2-p3-v1", "LONG MACD histogram>0; SHORT histogram<0"),
    ResearchConditionSpec("ADD_PRICE_SLOPE", "r2-p3-v2", "OLS slope(close,20)/latest close follows side"),
    ResearchConditionSpec("ADD_EMA_SLOPE", "r2-p3-v2", "OLS slope(EMA20 series,10)/latest EMA20 follows side"),
    ResearchConditionSpec("ADD_REGIME", "r2-p3-v1", "local signal regime is not RANGE"),
    ResearchConditionSpec("SENSITIVITY_SCORE_65", "r2-p3-v1", "model candidate score>=65"),
    ResearchConditionSpec("SENSITIVITY_SCORE_70", "r2-p3-v1", "model candidate score>=70"),
    ResearchConditionSpec("LEGACY_PRICE_2BAR_PROXY", "deprecated-v1", "close[-1]-close[-3] follows side",
                          legacy=True),
    ResearchConditionSpec("LEGACY_EMA_SPREAD_PROXY", "deprecated-v1", "EMA20-EMA60 follows side",
                          legacy=True),
)


def research_condition_results(*, side: TrendSide, factors: IndicatorSnapshot,
                               candles: Sequence[Candle], local_regime: str,
                               candidate_score: Decimal) -> tuple[ResearchConditionResult, ...]:
    """Evaluate every P3 condition from data available at the signal close."""
    if len(candles) < 121:
        raise ValueError("research conditions require 121 completed candles")
    vector = feature_vector(candles)
    direction = Decimal("1") if side is TrendSide.LONG else Decimal("-1")
    if side is TrendSide.FLAT:
        raise ValueError("candidate research conditions require LONG or SHORT")

    values: dict[str, tuple[bool, Decimal | str, Decimal | str]] = {
        "ADD_RSI": ((factors.rsi >= Decimal("50")) if side is TrendSide.LONG else
                    (factors.rsi <= Decimal("50")), factors.rsi, Decimal("50")),
        "ADD_MACD": (factors.macd_histogram*direction > ZERO, factors.macd_histogram, ZERO),
        "ADD_PRICE_SLOPE": (vector.close_slope20*direction > ZERO, vector.close_slope20, ZERO),
        "ADD_EMA_SLOPE": (vector.ema20_slope10*direction > ZERO, vector.ema20_slope10, ZERO),
        "ADD_REGIME": (local_regime != "RANGE", local_regime, "not RANGE"),
        "SENSITIVITY_SCORE_65": (candidate_score >= Decimal("65"), candidate_score, Decimal("65")),
        "SENSITIVITY_SCORE_70": (candidate_score >= Decimal("70"), candidate_score, Decimal("70")),
        "LEGACY_PRICE_2BAR_PROXY": (
            (candles[-1].close-candles[-3].close)*direction > ZERO,
            candles[-1].close-candles[-3].close, ZERO),
        "LEGACY_EMA_SPREAD_PROXY": (
            (factors.ema_fast-factors.ema_medium)*direction > ZERO,
            factors.ema_fast-factors.ema_medium, ZERO),
    }
    specs = {item.condition_id: item for item in CONDITION_SPECS}
    return tuple(ResearchConditionResult(condition_id, specs[condition_id].version, *values[condition_id])
                 for condition_id in specs)


def research_confirmation_passes(name: str, *, side: TrendSide, factors: IndicatorSnapshot,
                                 candles: Sequence[Candle], local_regime: str,
                                 candidate_score: Decimal,
                                 condition_version: str | None = None) -> bool:
    try:
        result = next(item for item in research_condition_results(
            side=side, factors=factors, candles=candles, local_regime=local_regime,
            candidate_score=candidate_score) if item.condition_id == f"ADD_{name}")
    except StopIteration as error:
        raise ValueError(f"unknown research confirmation: {name}") from error
    if condition_version is not None and result.version != condition_version:
        raise ValueError(f"research confirmation version mismatch: {name}:{condition_version}")
    return result.passed
