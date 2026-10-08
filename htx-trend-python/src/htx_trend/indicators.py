"""Deterministic indicator functions used by both replay and live paper runs."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from typing import Sequence

from .market_data import Candle

ZERO = Decimal("0")
ONE_HUNDRED = Decimal("100")
SCALE = Decimal("0.000000000000000001")


def divide(numerator: Decimal, denominator: Decimal) -> Decimal:
    return ZERO if denominator == ZERO else (numerator / denominator).quantize(SCALE, rounding=ROUND_HALF_UP)


def ema(values: Sequence[Decimal], period: int) -> Decimal:
    if not values or period < 1:
        raise ValueError("EMA requires values and a positive period")
    multiplier = divide(Decimal("2"), Decimal(period + 1))
    result = values[0]
    for value in values[1:]:
        result = (value - result) * multiplier + result
    return result.quantize(SCALE, rounding=ROUND_HALF_UP)


def true_ranges(candles: Sequence[Candle]) -> list[Decimal]:
    return [max(current.high - current.low, abs(current.high - previous.close), abs(current.low - previous.close))
            for previous, current in zip(candles, candles[1:])]


def atr(candles: Sequence[Candle], period: int) -> Decimal:
    values = true_ranges(candles)
    if not values:
        return ZERO
    tail = values[-period:]
    return divide(sum(tail, ZERO), Decimal(len(tail)))


def adx(candles: Sequence[Candle], period: int) -> Decimal:
    ranges = true_ranges(candles)
    if not ranges:
        return ZERO
    plus_dm: list[Decimal] = []
    minus_dm: list[Decimal] = []
    for previous, current in zip(candles, candles[1:]):
        up, down = current.high - previous.high, previous.low - current.low
        plus_dm.append(up if up > down and up > ZERO else ZERO)
        minus_dm.append(down if down > up and down > ZERO else ZERO)
    dx: list[Decimal] = []
    for end in range(max(0, len(ranges) - period), len(ranges)):
        start = max(0, end - period + 1)
        total_range = sum(ranges[start:end + 1], ZERO)
        if total_range <= ZERO:
            dx.append(ZERO)
            continue
        plus_di = divide(sum(plus_dm[start:end + 1], ZERO) * ONE_HUNDRED, total_range)
        minus_di = divide(sum(minus_dm[start:end + 1], ZERO) * ONE_HUNDRED, total_range)
        dx.append(divide(abs(plus_di - minus_di) * ONE_HUNDRED, plus_di + minus_di))
    return divide(sum(dx, ZERO), Decimal(len(dx))) if dx else ZERO


def roc(values: Sequence[Decimal], period: int) -> Decimal:
    if len(values) <= period or period < 1 or values[-period - 1] == ZERO:
        return ZERO
    return divide(values[-1] - values[-period - 1], values[-period - 1])


def vwap(candles: Sequence[Candle], period: int) -> Decimal:
    selected = candles[-period:]
    weighted = ZERO
    volume = ZERO
    for candle in selected:
        weight = candle.volume if candle.volume is not None and candle.volume > ZERO else candle.amount
        if weight is None or weight <= ZERO:
            continue
        typical = (candle.high + candle.low + candle.close) / Decimal("3")
        weighted += typical * weight
        volume += weight
    return divide(weighted, volume)


def rsi(candles: Sequence[Candle], period: int = 14) -> Decimal:
    changes = [current.close - previous.close for previous, current in zip(candles, candles[1:])][-period:]
    if not changes:
        return ZERO
    gains = sum((item for item in changes if item > ZERO), ZERO) / Decimal(len(changes))
    losses = sum((-item for item in changes if item < ZERO), ZERO) / Decimal(len(changes))
    if losses == ZERO:
        return ONE_HUNDRED if gains > ZERO else Decimal("50")
    relative_strength = gains / losses
    return (ONE_HUNDRED - (ONE_HUNDRED / (Decimal("1") + relative_strength))).quantize(SCALE, rounding=ROUND_HALF_UP)


def macd(candles: Sequence[Candle], fast: int = 12, slow: int = 26, signal: int = 9) -> tuple[Decimal, Decimal, Decimal]:
    values = [item.close for item in candles]
    line = ema(values, fast) - ema(values, slow)
    # Build the same sequence of point-in-time MACD values for a meaningful signal EMA.
    lines = [ema(values[:index], fast) - ema(values[:index], slow) for index in range(2, len(values) + 1)]
    signal_line = ema(lines, min(signal, len(lines))) if lines else ZERO
    return line, signal_line, line - signal_line


@dataclass(frozen=True, slots=True)
class IndicatorSnapshot:
    ema_fast: Decimal
    ema_medium: Decimal
    ema_slow: Decimal
    atr: Decimal
    atr_pct: Decimal
    adx: Decimal
    roc: Decimal
    vwap: Decimal
    rsi: Decimal
    macd: Decimal
    macd_signal: Decimal
    macd_histogram: Decimal


def snapshot(candles: Sequence[Candle], *, fast_ema: int = 12, medium_ema: int = 24, slow_ema: int = 48,
             atr_period: int = 14, adx_period: int = 14, roc_period: int = 12, vwap_period: int = 20) -> IndicatorSnapshot:
    if not candles:
        raise ValueError("indicator snapshot requires at least one candle")
    closes = [item.close for item in candles]
    current_atr = atr(candles, atr_period)
    macd_line, macd_signal, histogram = macd(candles)
    return IndicatorSnapshot(ema(closes, fast_ema), ema(closes, medium_ema), ema(closes, slow_ema), current_atr,
                             divide(current_atr, closes[-1]), adx(candles, adx_period), roc(closes, roc_period),
                             vwap(candles, vwap_period), rsi(candles), macd_line, macd_signal, histogram)
