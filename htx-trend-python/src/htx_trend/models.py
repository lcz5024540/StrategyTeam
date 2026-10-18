"""Trend signals and independent model candidates.

A candle may satisfy more than one model.  Candidates retain their own entry,
invalidation and target hypothesis so later gates can compare them without
combining incompatible model assumptions.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum
from typing import Iterable, Sequence

from .indicators import IndicatorSnapshot, adx, atr, divide, ema, roc, snapshot
from .market_data import Candle

ZERO = Decimal("0")


class TrendSide(StrEnum):
    LONG = "LONG"
    SHORT = "SHORT"
    FLAT = "FLAT"


class ModelCode(StrEnum):
    DONCHIAN_BREAKOUT = "DONCHIAN_BREAKOUT"
    FIB_PULLBACK_CONTINUATION = "FIB_PULLBACK_CONTINUATION"
    EMA_MOMENTUM_CONTINUATION = "EMA_MOMENTUM_CONTINUATION"


@dataclass(frozen=True, slots=True)
class TrendConfig:
    fast_ema_period: int = 12
    medium_ema_period: int = 24
    slow_ema_period: int = 48
    trend_fast_ema_period: int = 8
    trend_slow_ema_period: int = 21
    breakout_lookback: int = 20
    atr_period: int = 14
    adx_period: int = 14
    min_adx: Decimal = Decimal("18")
    min_atr_pct: Decimal = Decimal("0.0010")
    fib_min_range_atr_multiplier: Decimal = Decimal("1.2")
    box_window_bars: int = 8
    fib_source_lookback_bars: int = 8


@dataclass(frozen=True, slots=True)
class TrendSignal:
    side: TrendSide
    bar_time: int | None
    close_price: Decimal
    indicators: IndicatorSnapshot | None
    breakout_high: Decimal | None
    breakout_low: Decimal | None
    higher_timeframe_side: TrendSide
    reason: str


@dataclass(frozen=True, slots=True)
class PriceStructure:
    box_high: Decimal
    box_low: Decimal
    swing_high: Decimal
    swing_low: Decimal
    fib_lower: Decimal
    fib_upper: Decimal


@dataclass(frozen=True, slots=True)
class ModelCandidate:
    model: ModelCode
    side: TrendSide
    bar_time: int
    score: Decimal
    entry_price: Decimal
    invalidation_price: Decimal
    target_low: Decimal
    target_high: Decimal
    expected_r: Decimal
    structure: PriceStructure
    evidence: tuple[str, ...]
    source_breakout_time: int | None = None
    structure_key: str = ""
    confirmation_models: tuple[ModelCode, ...] = ()
    conflict_evidence: tuple[str, ...] = ()
    # The Java policy table matches a canonical evidence string, not merely a
    # model name.  It is optional for legacy/research candidates only.
    policy_confirmations: str | None = None
    # ``HtxTrendCombinationCalculator.MarketState`` is local to the candidate
    # K-line, and is intentionally distinct from cross-symbol market breadth.
    market_regime: str | None = None


@dataclass(frozen=True, slots=True)
class CandidateRoute:
    """Primary execution hypothesis plus still-visible supporting candidates."""

    primary: ModelCandidate | None
    ranked: tuple[ModelCandidate, ...]
    reason: str


def higher_trend(candles: Sequence[Candle], config: TrendConfig) -> TrendSide:
    if len(candles) < max(config.trend_fast_ema_period, config.trend_slow_ema_period):
        return TrendSide.FLAT
    closes = [item.close for item in candles]
    fast, slow = ema(closes, config.trend_fast_ema_period), ema(closes, config.trend_slow_ema_period)
    return TrendSide.LONG if fast > slow else TrendSide.SHORT if fast < slow else TrendSide.FLAT


def evaluate_signal(entry: Sequence[Candle], trend: Sequence[Candle], config: TrendConfig = TrendConfig()) -> TrendSignal:
    required = max(config.slow_ema_period, config.breakout_lookback) + max(config.atr_period, config.adx_period) + 3
    if len(entry) < required or len(trend) < max(config.trend_fast_ema_period, config.trend_slow_ema_period) + 3:
        return TrendSignal(TrendSide.FLAT, None, ZERO, None, None, None, TrendSide.FLAT, "not enough completed candles")
    latest, previous = entry[-1], entry[-2]
    factors = snapshot(entry, fast_ema=config.fast_ema_period, medium_ema=config.medium_ema_period,
                       slow_ema=config.slow_ema_period, atr_period=config.atr_period, adx_period=config.adx_period)
    high = max(item.high for item in entry[-config.breakout_lookback - 1:-1])
    low = min(item.low for item in entry[-config.breakout_lookback - 1:-1])
    higher = higher_trend(trend, config)
    if factors.atr_pct < config.min_atr_pct:
        return TrendSignal(TrendSide.FLAT, latest.open_time, latest.close, factors, high, low, higher, "ATR below threshold")
    if factors.adx < config.min_adx:
        return TrendSignal(TrendSide.FLAT, latest.open_time, latest.close, factors, high, low, higher, "ADX below threshold")
    if higher is TrendSide.LONG and latest.close > high and factors.ema_fast > factors.ema_slow:
        return TrendSignal(TrendSide.LONG, latest.open_time, latest.close, factors, high, low, higher, "Donchian breakout aligned with higher timeframe")
    if higher is TrendSide.SHORT and latest.close < low and factors.ema_fast < factors.ema_slow:
        return TrendSignal(TrendSide.SHORT, latest.open_time, latest.close, factors, high, low, higher, "Donchian breakout aligned with higher timeframe")
    range_ = high - low
    long_low, long_high = high - range_ * Decimal("0.618"), high - range_ * Decimal("0.382")
    short_low, short_high = low + range_ * Decimal("0.382"), low + range_ * Decimal("0.618")
    enough_range = range_ >= factors.atr * config.fib_min_range_atr_multiplier
    if higher is TrendSide.LONG and factors.ema_fast > factors.ema_slow and enough_range and latest.close > previous.close and latest.close > factors.ema_slow and long_low <= latest.close <= long_high:
        return TrendSignal(TrendSide.LONG, latest.open_time, latest.close, factors, high, low, higher, "Fibonacci pullback continuation aligned with higher timeframe")
    if higher is TrendSide.SHORT and factors.ema_fast < factors.ema_slow and enough_range and latest.close < previous.close and latest.close < factors.ema_slow and short_low <= latest.close <= short_high:
        return TrendSignal(TrendSide.SHORT, latest.open_time, latest.close, factors, high, low, higher, "Fibonacci pullback continuation aligned with higher timeframe")
    return TrendSignal(TrendSide.FLAT, latest.open_time, latest.close, factors, high, low, higher, "no aligned breakout or Fibonacci continuation")


def _structure(candles: Sequence[Candle], box: int) -> PriceStructure:
    prior, current = candles[-box - 1:-1], candles[-box - 1:]
    box_high, box_low = max(item.high for item in prior), min(item.low for item in prior)
    swing_high, swing_low = max(item.high for item in current), min(item.low for item in current)
    range_ = swing_high - swing_low
    return PriceStructure(box_high, box_low, swing_high, swing_low,
                          swing_high - range_ * Decimal("0.618"), swing_high - range_ * Decimal("0.382"))


def _expected_r(side: TrendSide, entry: Decimal, invalidation: Decimal, target: Decimal) -> Decimal:
    reward = target - entry if side is TrendSide.LONG else entry - target
    risk = entry - invalidation if side is TrendSide.LONG else invalidation - entry
    return divide(max(reward, ZERO), risk) if risk > ZERO else ZERO


def _prior_breakout(candles: Sequence[Candle], side: TrendSide, config: TrendConfig) -> tuple[int, Decimal, Decimal, Decimal, Decimal] | None:
    last = len(candles) - 1
    first = max(config.box_window_bars, last - config.fib_source_lookback_bars)
    for index in range(last - 1, first - 1, -1):
        previous = candles[index - config.box_window_bars:index]
        high, low, breakout = max(item.high for item in previous), min(item.low for item in previous), candles[index]
        is_breakout = breakout.close > high if side is TrendSide.LONG else breakout.close < low
        if not is_breakout:
            continue
        impulse = candles[index:last]
        impulse_high, impulse_low = max(item.high for item in impulse), min(item.low for item in impulse)
        range_ = impulse_high - impulse_low
        if range_ <= ZERO:
            continue
        if side is TrendSide.LONG:
            return breakout.open_time, impulse_high, impulse_low, impulse_high - range_ * Decimal("0.618"), impulse_high - range_ * Decimal("0.382")
        return breakout.open_time, impulse_high, impulse_low, impulse_low + range_ * Decimal("0.382"), impulse_low + range_ * Decimal("0.618")
    return None


def candidates(candles: Sequence[Candle], signal: TrendSignal, config: TrendConfig = TrendConfig()) -> tuple[ModelCandidate, ...]:
    if signal.side is TrendSide.FLAT or signal.indicators is None or len(candles) < config.box_window_bars + 1:
        return ()
    structure = _structure(candles, config.box_window_bars)
    factor, entry = signal.indicators, signal.close_price
    result: list[ModelCandidate] = []
    base_score = min(Decimal("100"), factor.adx + abs(factor.roc) * Decimal("1000"))
    donchian = entry > signal.breakout_high if signal.side is TrendSide.LONG else entry < signal.breakout_low
    if donchian:
        target = structure.box_high + (structure.box_high - structure.box_low) if signal.side is TrendSide.LONG else structure.box_low - (structure.box_high - structure.box_low)
        invalidation = signal.breakout_low if signal.side is TrendSide.LONG else signal.breakout_high
        result.append(ModelCandidate(ModelCode.DONCHIAN_BREAKOUT, signal.side, signal.bar_time or candles[-1].open_time,
                                     min(Decimal("100"), base_score + Decimal("12")), entry, invalidation, target, target,
                                     _expected_r(signal.side, entry, invalidation, target), structure,
                                     ("donchian_breakout", "higher_timeframe_aligned")))
    prior = _prior_breakout(candles, signal.side, config)
    if prior is not None:
        source_time, impulse_high, impulse_low, lower, upper = prior
        retest = lower <= entry <= upper and ((signal.side is TrendSide.LONG and entry >= factor.ema_fast) or (signal.side is TrendSide.SHORT and entry <= factor.ema_fast))
        if retest:
            range_ = impulse_high - impulse_low
            extension_1272 = impulse_low + range_ * Decimal("1.272") if signal.side is TrendSide.LONG else impulse_high - range_ * Decimal("1.272")
            extension_1618 = impulse_low + range_ * Decimal("1.618") if signal.side is TrendSide.LONG else impulse_high - range_ * Decimal("1.618")
            target_low, target_high = min(extension_1272, extension_1618), max(extension_1272, extension_1618)
            invalidation = lower if signal.side is TrendSide.LONG else upper
            result.append(ModelCandidate(ModelCode.FIB_PULLBACK_CONTINUATION, signal.side, signal.bar_time or candles[-1].open_time,
                                         min(Decimal("100"), base_score + Decimal("10")), entry, invalidation, target_low, target_high,
                                         _expected_r(signal.side, entry, invalidation, target_low), structure,
                                         ("prior_breakout", "fib_retest", "ema_reclaim"), source_time))
    long_aligned = factor.ema_fast > factor.ema_medium > factor.ema_slow and factor.roc > ZERO and entry >= factor.vwap
    short_aligned = factor.ema_fast < factor.ema_medium < factor.ema_slow and factor.roc < ZERO and entry <= factor.vwap
    if factor.adx >= Decimal("20") and ((signal.side is TrendSide.LONG and long_aligned) or (signal.side is TrendSide.SHORT and short_aligned)):
        target = structure.swing_high if signal.side is TrendSide.LONG else structure.swing_low
        invalidation = min(factor.ema_slow, entry - factor.atr * Decimal("1.25")) if signal.side is TrendSide.LONG else max(factor.ema_slow, entry + factor.atr * Decimal("1.25"))
        result.append(ModelCandidate(ModelCode.EMA_MOMENTUM_CONTINUATION, signal.side, signal.bar_time or candles[-1].open_time,
                                     min(Decimal("100"), base_score + Decimal("12")), entry, invalidation, target, target,
                                     _expected_r(signal.side, entry, invalidation, target), structure,
                                     ("ema_stack", "roc", "vwap")))
    return _attach_structure_keys(result)


def model_candidates(entry: Sequence[Candle], trend: Sequence[Candle], config: TrendConfig = TrendConfig()) -> tuple[ModelCandidate, ...]:
    """Generate the three models independently from the same completed windows.

    ``evaluate_signal`` remains available for Java-compatible legacy telemetry.
    Entry selection must call this function so a missing Donchian breakout does
    not prevent a valid EMA momentum continuation (research VWAP filter) from being evaluated.
    """
    required = max(config.slow_ema_period, config.breakout_lookback) + max(config.atr_period, config.adx_period) + 3
    if len(entry) < required or len(trend) < max(config.trend_fast_ema_period, config.trend_slow_ema_period) + 3:
        return ()
    factor = snapshot(entry, fast_ema=config.fast_ema_period, medium_ema=config.medium_ema_period,
                      slow_ema=config.slow_ema_period, atr_period=config.atr_period, adx_period=config.adx_period)
    if factor.atr_pct < config.min_atr_pct or factor.adx < config.min_adx:
        return ()
    latest = entry[-1]
    high = max(item.high for item in entry[-config.breakout_lookback - 1:-1])
    low = min(item.low for item in entry[-config.breakout_lookback - 1:-1])
    higher = higher_trend(trend, config)
    structure = _structure(entry, config.box_window_bars)
    base_score = min(Decimal("100"), factor.adx + abs(factor.roc) * Decimal("1000"))
    result: list[ModelCandidate] = []

    for side in (TrendSide.LONG, TrendSide.SHORT):
        aligned_higher = higher is side
        breakout = latest.close > high if side is TrendSide.LONG else latest.close < low
        ema_direction = factor.ema_fast > factor.ema_slow if side is TrendSide.LONG else factor.ema_fast < factor.ema_slow
        if aligned_higher and breakout and ema_direction:
            target = structure.box_high + (structure.box_high - structure.box_low) if side is TrendSide.LONG else structure.box_low - (structure.box_high - structure.box_low)
            invalidation = low if side is TrendSide.LONG else high
            result.append(ModelCandidate(ModelCode.DONCHIAN_BREAKOUT, side, latest.open_time,
                min(Decimal("100"), base_score + Decimal("15")), latest.close, invalidation, target, target,
                _expected_r(side, latest.close, invalidation, target), structure,
                ("donchian_breakout", "higher_timeframe_aligned", "box_structure")))

        prior = _prior_breakout(entry, side, config)
        if aligned_higher and prior is not None and ema_direction:
            source_time, impulse_high, impulse_low, lower, upper = prior
            retest = lower <= latest.close <= upper and ((side is TrendSide.LONG and latest.close >= factor.ema_fast)
                                                           or (side is TrendSide.SHORT and latest.close <= factor.ema_fast))
            if retest:
                range_ = impulse_high - impulse_low
                extension_1272 = impulse_low + range_ * Decimal("1.272") if side is TrendSide.LONG else impulse_high - range_ * Decimal("1.272")
                extension_1618 = impulse_low + range_ * Decimal("1.618") if side is TrendSide.LONG else impulse_high - range_ * Decimal("1.618")
                target_low, target_high = min(extension_1272, extension_1618), max(extension_1272, extension_1618)
                invalidation = lower if side is TrendSide.LONG else upper
                result.append(ModelCandidate(ModelCode.FIB_PULLBACK_CONTINUATION, side, latest.open_time,
                    min(Decimal("100"), base_score + Decimal("10")), latest.close, invalidation, target_low, target_high,
                    _expected_r(side, latest.close, invalidation, target_low), structure,
                    ("prior_breakout", "fib_retest", "ema_reclaim", f"source_breakout={source_time}"), source_time))

        stack = (factor.ema_fast > factor.ema_medium > factor.ema_slow and factor.roc > ZERO and latest.close >= factor.vwap
                 if side is TrendSide.LONG else factor.ema_fast < factor.ema_medium < factor.ema_slow and factor.roc < ZERO and latest.close <= factor.vwap)
        if aligned_higher and factor.adx >= Decimal("20") and stack:
            target = structure.swing_high if side is TrendSide.LONG else structure.swing_low
            atr_boundary = latest.close - factor.atr * Decimal("1.25") if side is TrendSide.LONG else latest.close + factor.atr * Decimal("1.25")
            invalidation = min(factor.ema_slow, atr_boundary) if side is TrendSide.LONG else max(factor.ema_slow, atr_boundary)
            result.append(ModelCandidate(ModelCode.EMA_MOMENTUM_CONTINUATION, side, latest.open_time,
                min(Decimal("100"), base_score + Decimal("12")), latest.close, invalidation, target, target,
                _expected_r(side, latest.close, invalidation, target), structure,
                ("ema_stack", "roc", "vwap", "higher_timeframe_aligned")))
    return _attach_structure_keys(result)


def _attach_structure_keys(items: Sequence[ModelCandidate]) -> tuple[ModelCandidate, ...]:
    """Assign immutable per-setup identity and peer-model confirmations.

    A new completed bar/setup receives a new key.  The key deliberately
    contains the breakout source for Fibonacci, so a later trade cannot reuse
    a previous position's structure merely because it is the same contract.
    """
    grouped = tuple(items)
    output: list[ModelCandidate] = []
    for item in grouped:
        peers = tuple(peer.model for peer in grouped if peer is not item and peer.side is item.side)
        opposite = tuple(peer.model.value for peer in grouped if peer.side not in (item.side, TrendSide.FLAT))
        source = item.source_breakout_time if item.source_breakout_time is not None else item.bar_time
        key = f"{item.model.value}:{item.side.value}:{source}:{item.bar_time}:{item.structure.box_high}:{item.structure.box_low}"
        output.append(ModelCandidate(item.model, item.side, item.bar_time, item.score, item.entry_price,
            item.invalidation_price, item.target_low, item.target_high, item.expected_r, item.structure, item.evidence,
            item.source_breakout_time, key, peers, opposite))
    return tuple(sorted(output, key=lambda item: (item.score, item.expected_r), reverse=True))


def route_candidates(items: Sequence[ModelCandidate], *, market_context=None) -> CandidateRoute:
    """Rank models without suppressing their simultaneous observations.

    The primary is an execution choice for this scan, not a claim that the bar
    belongs to one model.  Same-direction peer models are confirmations;
    opposite-direction models remain conflict evidence on every candidate.
    """
    if not items:
        return CandidateRoute(None, (), "no independently valid model candidate")
    context = market_context
    def score(item: ModelCandidate) -> tuple[Decimal, Decimal, Decimal]:
        context_bonus = Decimal("4") if context is not None and context.aligned(item.side) else ZERO
        context_penalty = Decimal("8") if context is not None and context.opposed(item.side) else ZERO
        confirmation_bonus = Decimal(len(item.confirmation_models)) * Decimal("2")
        conflict_penalty = Decimal(len(item.conflict_evidence)) * Decimal("2")
        return (item.score + context_bonus + confirmation_bonus - context_penalty - conflict_penalty,
                item.expected_r, item.score)
    ranked = tuple(sorted(items, key=score, reverse=True))
    primary = ranked[0]
    return CandidateRoute(primary, ranked,
                          f"primary={primary.model.value}; score={score(primary)[0]}; confirmations={len(primary.confirmation_models)}")


@dataclass(frozen=True, slots=True)
class MarketBreadth:
    side: TrendSide
    aligned_count: int
    total_count: int
    ratio: Decimal


def market_breadth(signals: Iterable[TrendSignal], threshold: Decimal = Decimal("0.70")) -> MarketBreadth:
    values = tuple(signals)
    long_count = sum(item.side is TrendSide.LONG for item in values)
    short_count = sum(item.side is TrendSide.SHORT for item in values)
    total = len(values)
    side, count = (TrendSide.LONG, long_count) if long_count >= short_count else (TrendSide.SHORT, short_count)
    ratio = divide(Decimal(count), Decimal(total)) if total else ZERO
    return MarketBreadth(side if ratio >= threshold else TrendSide.FLAT, count, total, ratio)
