"""Literal Python port of Java's ensemble-to-combination entry contract.

The live Python worker must not invent a second trend score.  This module keeps
the same completed-bar inputs, score components, combination evidence, targets
and invalidation prices as ``HtxTrendEnsembleSignalCalculator`` plus
``HtxTrendCombinationCalculator``.  Policy, account preflight and execution
remain separate layers.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from os import environ
from typing import Sequence

from .diagnostics import CandidateDiagnosis, RejectCode
from .indicators import IndicatorSnapshot, adx, atr, divide, ema, roc, snapshot
from .market_data import Candle
from .models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from .timeframes import TimeframeProfile

ZERO = Decimal("0")
HUNDRED = Decimal("100")


@dataclass(frozen=True, slots=True)
class JavaEntryConfig:
    fast_ema_period: int = 20
    medium_ema_period: int = 60
    slow_ema_period: int = 120
    breakout_lookback: int = 20
    momentum_lookback: int = 12
    atr_period: int = 14
    adx_period: int = 14
    volume_lookback: int = 20
    min_atr_pct: Decimal = Decimal("0.0010")
    min_adx: Decimal = Decimal("16")
    min_momentum_pct: Decimal = Decimal("0.0015")
    min_volume_ratio: Decimal = Decimal("1.10")
    entry_score_threshold: Decimal = Decimal("60")
    min_score_gap: Decimal = Decimal("12")
    box_window_bars: int = 32
    fib_source_lookback_bars: int = 8
    # Keep Java's TRENDING_UP / TRENDING_DOWN entry precondition by default.  Disabling this is
    # only for an explicit ablation/gray release; all structure and policy gates remain active.
    combination_require_trending_regime: bool = True

    @classmethod
    def from_env(cls) -> "JavaEntryConfig":
        """Read the same HTX_TREND_ENSEMBLE_* knobs as Java application.yml."""
        def integer(name: str, default: int) -> int: return int(environ.get(name, str(default)))
        def decimal(name: str, default: Decimal) -> Decimal: return Decimal(environ.get(name, str(default)))
        def boolean(name: str, default: bool) -> bool:
            return environ.get(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}
        return cls(integer("HTX_TREND_ENSEMBLE_FAST_EMA_PERIOD", 20),
                   integer("HTX_TREND_ENSEMBLE_MEDIUM_EMA_PERIOD", 60),
                   integer("HTX_TREND_ENSEMBLE_SLOW_EMA_PERIOD", 120),
                   integer("HTX_TREND_ENSEMBLE_BREAKOUT_LOOKBACK", 20),
                   integer("HTX_TREND_ENSEMBLE_MOMENTUM_LOOKBACK", 12),
                   integer("HTX_TREND_ENSEMBLE_ATR_PERIOD", 14), integer("HTX_TREND_ENSEMBLE_ADX_PERIOD", 14),
                   integer("HTX_TREND_ENSEMBLE_VOLUME_LOOKBACK", 20), decimal("HTX_TREND_ENSEMBLE_MIN_ATR_PCT", Decimal("0.0010")),
                   decimal("HTX_TREND_ENSEMBLE_MIN_ADX", Decimal("16")), decimal("HTX_TREND_ENSEMBLE_MIN_MOMENTUM_PCT", Decimal("0.0015")),
                   decimal("HTX_TREND_ENSEMBLE_MIN_VOLUME_RATIO", Decimal("1.10")), decimal("HTX_TREND_ENSEMBLE_ENTRY_SCORE_THRESHOLD", Decimal("60")),
                   decimal("HTX_TREND_ENSEMBLE_MIN_SCORE_GAP", Decimal("12")), 32, 8,
                   boolean("HTX_TREND_COMBINATION_REQUIRE_TRENDING_REGIME", True))


@dataclass(frozen=True, slots=True)
class JavaTrendSignal:
    side: TrendSide
    bar_time: int | None
    close_price: Decimal
    indicators: IndicatorSnapshot | None
    breakout_high: Decimal | None
    breakout_low: Decimal | None
    reason: str
    regime: str
    score: Decimal
    long_score: Decimal
    short_score: Decimal
    score_gap: Decimal
    volume_ratio: Decimal


@dataclass(frozen=True, slots=True)
class ModelCandidateEvaluation:
    """One model's result from the production candidate calculation.

    Research consumes this record to explain rejected models.  The executable
    candidate list is flattened from these same records, so diagnostics cannot
    silently reimplement or drift from the live conditions.
    """

    model: ModelCode
    candidates: tuple[ModelCandidate, ...]
    reject_codes: tuple[str, ...] = ()


def evaluate(candles: Sequence[Candle], config: JavaEntryConfig = JavaEntryConfig()) -> JavaTrendSignal:
    """Port Java ensemble scoring, including its deliberately permissive RANGE signal."""
    required = max(config.slow_ema_period, config.breakout_lookback, config.momentum_lookback,
                   config.atr_period, config.adx_period, config.volume_lookback) + 5
    ordered = tuple(sorted(candles, key=lambda candle: candle.open_time))
    if len(ordered) < required or any(candle.close <= ZERO or candle.high <= ZERO or candle.low <= ZERO for candle in ordered):
        return JavaTrendSignal(TrendSide.FLAT, None, ZERO, None, None, None, "not enough candles", "RANGE", ZERO, ZERO, ZERO, ZERO, ZERO)
    closes = [item.close for item in ordered]
    factors = snapshot(ordered, fast_ema=config.fast_ema_period, medium_ema=config.medium_ema_period,
                       slow_ema=config.slow_ema_period, atr_period=config.atr_period, adx_period=config.adx_period,
                       roc_period=config.momentum_lookback, vwap_period=config.volume_lookback)
    last = ordered[-1]
    breakout_high = max(item.high for item in ordered[-config.breakout_lookback - 1:-1])
    breakout_low = min(item.low for item in ordered[-config.breakout_lookback - 1:-1])
    volume_ratio = _volume_ratio(ordered, config.volume_lookback)
    long_score, short_score, reason = _score(last.close, factors, breakout_high, breakout_low, volume_ratio, config)
    gap = abs(long_score - short_score)
    score = max(long_score, short_score)
    side = (TrendSide.FLAT if score < config.entry_score_threshold or gap < config.min_score_gap
            else TrendSide.LONG if long_score > short_score else TrendSide.SHORT)
    regime = _regime(last.close, factors, config)
    return JavaTrendSignal(side, last.open_time, last.close, factors, breakout_high, breakout_low, reason, regime,
                           score, long_score, short_score, gap, volume_ratio)


def candidates(candles: Sequence[Candle], signal: JavaTrendSignal,
               config: JavaEntryConfig = JavaEntryConfig(), period: str = "15min") -> tuple[ModelCandidate, ...]:
    """Port Java combination candidates and their canonical policy evidence."""
    output = [candidate for result in model_candidate_evaluations(candles, signal, config, period)
              for candidate in result.candidates]
    return tuple(sorted(output, key=lambda item: item.score, reverse=True))


def model_candidate_evaluations(candles: Sequence[Candle], signal: JavaTrendSignal,
                                config: JavaEntryConfig = JavaEntryConfig(),
                                period: str = "15min") -> tuple[ModelCandidateEvaluation, ...]:
    """Return a candidate or explicit rejection for every supported model."""
    models = tuple(ModelCode)

    def rejected(*codes: str | RejectCode) -> tuple[ModelCandidateEvaluation, ...]:
        normalized = tuple(code.value if isinstance(code, RejectCode) else str(code) for code in codes)
        return tuple(ModelCandidateEvaluation(model, (), normalized) for model in models)

    try:
        TimeframeProfile.for_period(period)
    except ValueError:
        return rejected(RejectCode.TIMEFRAME_UNSUPPORTED)
    if signal.indicators is None:
        return rejected(RejectCode.INSUFFICIENT_BARS)
    if signal.side is TrendSide.FLAT:
        codes = [RejectCode.NO_DIRECTION_SIGNAL]
        if signal.score < config.entry_score_threshold:
            codes.append("SCORE_BELOW_THRESHOLD")
        if signal.score_gap < config.min_score_gap:
            codes.append("SCORE_GAP_BELOW_THRESHOLD")
        return rejected(*codes)
    if config.combination_require_trending_regime and signal.regime not in {"TRENDING_UP", "TRENDING_DOWN"}:
        return rejected(RejectCode.REGIME_NOT_TRENDING)
    ordered = tuple(sorted(candles, key=lambda candle: candle.open_time))
    box = max(2, config.box_window_bars)
    if len(ordered) < box + 1:
        return rejected(RejectCode.INSUFFICIENT_BARS)
    factor, latest = signal.indicators, ordered[-1]
    structure = _structure(ordered, box)
    box_breakout = signal.close_price > structure.box_high if signal.side is TrendSide.LONG else signal.close_price < structure.box_low
    donchian = signal.close_price > signal.breakout_high if signal.side is TrendSide.LONG else signal.close_price < signal.breakout_low
    prior = _prior_breakout(ordered, signal.side, box, config.fib_source_lookback_bars)
    fib_retest = prior is not None and _fib_retest(signal.side, signal.close_price, prior, factor.ema_fast)
    donchian_candidates: list[ModelCandidate] = []
    if box_breakout and donchian:
        donchian_candidates.append(_candidate(ModelCode.DONCHIAN_BREAKOUT, "BOX_BREAKOUT", signal, structure, latest, prior,
                                              signal.score + Decimal("15"), "fresh channel breakout confirmed by box"))
    if donchian and not box_breakout:
        donchian_candidates.append(_candidate(ModelCode.DONCHIAN_BREAKOUT, "TREND_CONTEXT", signal, structure, latest, prior,
                                              signal.score + Decimal("12"), "fresh channel breakout aligned with trend context"))
    fib_candidates: list[ModelCandidate] = []
    if fib_retest and (box_breakout or prior[0]):
        fib_candidates.append(_candidate(ModelCode.FIB_PULLBACK_CONTINUATION, "BOX_BREAKOUT", signal, structure, latest, prior,
                                         signal.score + Decimal("10"), "Fibonacci retest confirmed by prior box breakout"))
    if fib_retest and (donchian or prior[1]):
        fib_candidates.append(_candidate(ModelCode.FIB_PULLBACK_CONTINUATION, "DONCHIAN_BREAKOUT", signal, structure, latest, prior,
                                         signal.score + Decimal("10"), "Fibonacci retest confirmed by prior channel breakout"))
    if fib_retest and (box_breakout or prior[0]) and (donchian or prior[1]):
        fib_candidates.append(_candidate(ModelCode.FIB_PULLBACK_CONTINUATION, "BOX_BREAKOUT,DONCHIAN_BREAKOUT", signal, structure, latest, prior,
                                         signal.score + Decimal("20"), "Fibonacci retest confirmed by box and channel"))
    stack = ((factor.ema_fast > factor.ema_medium > factor.ema_slow and factor.roc > ZERO)
             if signal.side is TrendSide.LONG else (factor.ema_fast < factor.ema_medium < factor.ema_slow and factor.roc < ZERO))
    ema_candidates = (() if factor.adx < Decimal("20") or not stack else
                      (_candidate(ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION, "EMA_STACK,MOMENTUM", signal,
                                  structure, latest, prior, signal.score + Decimal("12"),
                                  "EMA stack and momentum continuation"),))
    fib_rejects = (() if fib_candidates else
                   (RejectCode.NO_FIB_RETEST.value,) if not fib_retest else
                   (RejectCode.NO_STRUCTURE.value,))
    ema_rejects = (() if ema_candidates else tuple(
        code.value for passed, code in ((stack, RejectCode.EMA_MOMENTUM_CONFLICT),
                                        (factor.adx >= Decimal("20"), RejectCode.ADX_BELOW_20))
        if not passed))
    return (
        ModelCandidateEvaluation(ModelCode.DONCHIAN_BREAKOUT, tuple(donchian_candidates),
                                 () if donchian_candidates else (RejectCode.NO_DONCHIAN_BREAKOUT.value,)),
        ModelCandidateEvaluation(ModelCode.FIB_PULLBACK_CONTINUATION, tuple(fib_candidates), fib_rejects),
        ModelCandidateEvaluation(ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION, tuple(ema_candidates), ema_rejects),
    )


def explain_candidates(candles: Sequence[Candle], signal: JavaTrendSignal,
                       config: JavaEntryConfig = JavaEntryConfig(), period: str = "15min") -> CandidateDiagnosis:
    """Explain the existing gates in their actual evaluation order.

    In particular the period gate wins even over a FLAT signal. Structure
    flags reuse the combination helpers without creating or altering candidates.
    """
    try:
        TimeframeProfile.for_period(period)
    except ValueError:
        return CandidateDiagnosis("CONTRACT", RejectCode.TIMEFRAME_UNSUPPORTED)
    if signal.indicators is None:
        return CandidateDiagnosis("SIGNAL", RejectCode.INSUFFICIENT_BARS)
    if signal.side is TrendSide.FLAT:
        return CandidateDiagnosis("SIGNAL", RejectCode.NO_DIRECTION_SIGNAL, flags={
            "score_below_threshold": signal.score < config.entry_score_threshold,
            "gap_below_threshold": signal.score_gap < config.min_score_gap})
    if config.combination_require_trending_regime and signal.regime not in {"TRENDING_UP", "TRENDING_DOWN"}:
        return CandidateDiagnosis("CONTRACT", RejectCode.REGIME_NOT_TRENDING, flags={"regime": signal.regime})
    ordered = tuple(sorted(candles, key=lambda candle: candle.open_time))
    box = max(2, config.box_window_bars)
    if len(ordered) < box + 1:
        return CandidateDiagnosis("SIGNAL", RejectCode.INSUFFICIENT_BARS)
    factor = signal.indicators
    structure = _structure(ordered, box)
    box_breakout = signal.close_price > structure.box_high if signal.side is TrendSide.LONG else signal.close_price < structure.box_low
    donchian = signal.close_price > signal.breakout_high if signal.side is TrendSide.LONG else signal.close_price < signal.breakout_low
    prior = _prior_breakout(ordered, signal.side, box, config.fib_source_lookback_bars)
    fib_retest = prior is not None and _fib_retest(signal.side, signal.close_price, prior, factor.ema_fast)
    stack = ((factor.ema_fast > factor.ema_medium > factor.ema_slow and factor.roc > ZERO)
             if signal.side is TrendSide.LONG else (factor.ema_fast < factor.ema_medium < factor.ema_slow and factor.roc < ZERO))
    fib_box = bool(fib_retest and (box_breakout or prior[0]))
    fib_channel = bool(fib_retest and (donchian or prior[1]))
    count = int(donchian) + int(fib_box) + int(fib_channel) + int(fib_box and fib_channel) + int(stack and factor.adx >= Decimal("20"))
    flags = {"box_breakout": box_breakout, "donchian": donchian, "fib_retest": fib_retest,
             "ema_stack": stack, "adx": str(factor.adx)}
    if not count:
        flags["subcodes"] = [code.value for passed, code in (
            (box_breakout, RejectCode.NO_BOX_BREAKOUT), (donchian, RejectCode.NO_DONCHIAN_BREAKOUT),
            (fib_retest, RejectCode.NO_FIB_RETEST), (stack, RejectCode.EMA_MOMENTUM_CONFLICT),
            (factor.adx >= Decimal("20"), RejectCode.ADX_BELOW_20)) if not passed]
    return CandidateDiagnosis(None if count else "CONTRACT", None if count else RejectCode.NO_STRUCTURE, count, flags)


def _score(close: Decimal, factor: IndicatorSnapshot, high: Decimal, low: Decimal, volume_ratio: Decimal,
           config: JavaEntryConfig) -> tuple[Decimal, Decimal, str]:
    long, short, reasons = ZERO, ZERO, []
    if close > factor.ema_fast > factor.ema_medium > factor.ema_slow:
        long += Decimal("30"); reasons.append("EMA20/60/120 bullish stack")
    elif close < factor.ema_fast < factor.ema_medium < factor.ema_slow:
        short += Decimal("30"); reasons.append("EMA20/60/120 bearish stack")
    elif close > factor.ema_medium and factor.ema_fast > factor.ema_slow:
        long += Decimal("12"); reasons.append("partial bullish EMA alignment")
    elif close < factor.ema_medium and factor.ema_fast < factor.ema_slow:
        short += Decimal("12"); reasons.append("partial bearish EMA alignment")
    if factor.roc >= config.min_momentum_pct:
        long += _bounded(abs(factor.roc), config.min_momentum_pct, Decimal("20")); reasons.append("positive momentum")
    elif factor.roc <= -config.min_momentum_pct:
        short += _bounded(abs(factor.roc), config.min_momentum_pct, Decimal("20")); reasons.append("negative momentum")
    if close > high:
        long += Decimal("20"); reasons.append("close above Donchian high")
    elif close < low:
        short += Decimal("20"); reasons.append("close below Donchian low")
    bias = TrendSide.LONG if long > short or (long == short and factor.roc > ZERO) else TrendSide.SHORT if short > long or factor.roc < ZERO else TrendSide.FLAT
    if factor.adx >= config.min_adx and bias is not TrendSide.FLAT:
        long, short = (long + Decimal("10"), short) if bias is TrendSide.LONG else (long, short + Decimal("10"))
        reasons.append("ADX confirms trend strength")
    if factor.atr_pct >= config.min_atr_pct and bias is not TrendSide.FLAT:
        long, short = (long + Decimal("10"), short) if bias is TrendSide.LONG else (long, short + Decimal("10"))
        reasons.append("ATR volatility usable")
    if volume_ratio >= config.min_volume_ratio and bias is not TrendSide.FLAT:
        long, short = (long + Decimal("10"), short) if bias is TrendSide.LONG else (long, short + Decimal("10"))
        reasons.append("volume expansion")
    return min(HUNDRED, long), min(HUNDRED, short), "; ".join(reasons) or "no ensemble component reached activation threshold"


def _candidate(model: ModelCode, confirmations: str, signal: JavaTrendSignal, structure: PriceStructure, candle: Candle,
               prior, score: Decimal, hypothesis: str) -> ModelCandidate:
    side = signal.side
    if model is ModelCode.DONCHIAN_BREAKOUT:
        invalidation = signal.breakout_low if side is TrendSide.LONG else signal.breakout_high
        target_low = target_high = structure.box_high + (structure.box_high - structure.box_low) if side is TrendSide.LONG else structure.box_low - (structure.box_high - structure.box_low)
    elif model is ModelCode.FIB_PULLBACK_CONTINUATION:
        invalidation = structure.fib_lower if side is TrendSide.LONG else structure.fib_upper
        swing = structure.swing_high - structure.swing_low
        first = structure.swing_low + swing * Decimal("1.272") if side is TrendSide.LONG else structure.swing_high - swing * Decimal("1.272")
        second = structure.swing_low + swing * Decimal("1.618") if side is TrendSide.LONG else structure.swing_high - swing * Decimal("1.618")
        target_low, target_high = min(first, second), max(first, second)
    else:
        invalidation = min(signal.indicators.ema_slow, signal.close_price - signal.indicators.atr * Decimal("1.25")) if side is TrendSide.LONG else max(signal.indicators.ema_slow, signal.close_price + signal.indicators.atr * Decimal("1.25"))
        target_low = target_high = structure.swing_high if side is TrendSide.LONG else structure.swing_low
    reward = target_low - signal.close_price if side is TrendSide.LONG else signal.close_price - target_high
    risk = signal.close_price - invalidation if side is TrendSide.LONG else invalidation - signal.close_price
    expected = max(reward, ZERO) / risk if risk > ZERO else ZERO
    source = prior[2] if model is ModelCode.FIB_PULLBACK_CONTINUATION and prior is not None else None
    market_regime = ("BROAD_UP" if signal.regime == "TRENDING_UP" else
                     "BROAD_DOWN" if signal.regime == "TRENDING_DOWN" else
                     "LOW_VOL_DEAD" if signal.regime == "LOW_VOLATILITY" else "CHOP")
    return ModelCandidate(model, side, signal.bar_time or candle.open_time, min(HUNDRED, score), signal.close_price,
                          invalidation, target_low, target_high, expected, structure, (hypothesis,), source,
                          policy_confirmations=confirmations, market_regime=market_regime)


def _structure(candles: Sequence[Candle], box: int) -> PriceStructure:
    last = len(candles) - 1
    prior = candles[last - box:last]
    current = candles[max(0, last - box):last + 1]
    box_high, box_low = max(x.high for x in prior), min(x.low for x in prior)
    swing_high, swing_low = max(x.high for x in current), min(x.low for x in current)
    span = swing_high - swing_low
    return PriceStructure(box_high, box_low, swing_high, swing_low,
                          swing_high - span * Decimal("0.618"), swing_high - span * Decimal("0.382"))


def _prior_breakout(candles: Sequence[Candle], side: TrendSide, box: int, lookback: int):
    last = len(candles) - 1
    for index in range(last - 1, max(box, last - lookback) - 1, -1):
        prior = candles[index - box:index]
        high, low, breakout = max(x.high for x in prior), min(x.low for x in prior), candles[index]
        box_hit = breakout.close > high if side is TrendSide.LONG else breakout.close < low
        if not box_hit:
            continue
        impulse = candles[index:last]
        hi, lo = max(x.high for x in impulse), min(x.low for x in impulse)
        span = hi - lo
        if span <= ZERO:
            continue
        lower, upper = ((hi - span * Decimal("0.618"), hi - span * Decimal("0.382")) if side is TrendSide.LONG
                        else (lo + span * Decimal("0.382"), lo + span * Decimal("0.618")))
        return box_hit, box_hit, breakout.open_time, lower, upper
    return None


def _fib_retest(side: TrendSide, close: Decimal, prior, ema_fast: Decimal) -> bool:
    return prior[3] <= close <= prior[4] and ((side is TrendSide.LONG and close >= ema_fast) or (side is TrendSide.SHORT and close <= ema_fast))


def _regime(close: Decimal, factor: IndicatorSnapshot, config: JavaEntryConfig) -> str:
    if factor.atr_pct < config.min_atr_pct:
        return "LOW_VOLATILITY"
    if close > factor.ema_fast > factor.ema_medium > factor.ema_slow and factor.adx >= config.min_adx:
        return "TRENDING_UP"
    if close < factor.ema_fast < factor.ema_medium < factor.ema_slow and factor.adx >= config.min_adx:
        return "TRENDING_DOWN"
    return "RANGE"


def _volume_ratio(candles: Sequence[Candle], lookback: int) -> Decimal:
    values = [(_volume(candle)) for candle in candles[-lookback - 1:-1] if _volume(candle) > ZERO]
    return _volume(candles[-1]) / (sum(values, ZERO) / Decimal(len(values))) if values else ZERO


def _volume(candle: Candle) -> Decimal:
    return next((value for value in (candle.amount, candle.volume, candle.turnover) if value is not None and value > ZERO), ZERO)


def _bounded(actual: Decimal, threshold: Decimal, maximum: Decimal) -> Decimal:
    return min(maximum, (actual / threshold) * (maximum / Decimal("2"))) if threshold > ZERO else maximum
