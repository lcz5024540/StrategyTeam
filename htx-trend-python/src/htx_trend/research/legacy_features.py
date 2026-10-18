"""Reusable strategy-independent statistical features and performance metrics."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from math import sqrt
from statistics import fmean, pstdev
from typing import Sequence

from ..indicators import adx, atr, ema, macd, roc, rsi, vwap
from ..market_data import Candle

ZERO = Decimal("0")


def donchian(candles: Sequence[Candle], period: int, *, lag: int = 1) -> tuple[Decimal, Decimal]:
    if period < 1 or lag < 0 or len(candles) < period + lag:
        raise ValueError("insufficient candles for lagged Donchian")
    end = len(candles) - lag if lag else len(candles)
    values = candles[end-period:end]
    return max(x.high for x in values), min(x.low for x in values)


def simple_returns(candles: Sequence[Candle]) -> tuple[Decimal, ...]:
    return tuple((b.close / a.close - 1) if a.close else ZERO for a, b in zip(candles, candles[1:]))


def rolling_volatility(candles: Sequence[Candle], period: int) -> Decimal:
    values = simple_returns(candles)[-period:]
    if len(values) < period:
        raise ValueError("insufficient returns for volatility")
    mean = sum(values, ZERO) / Decimal(len(values))
    return (sum((x-mean)*(x-mean) for x in values) / Decimal(len(values))).sqrt()


def volume_ratio(candles: Sequence[Candle], period: int, *, field: str = "amount") -> Decimal:
    if len(candles) < period + 1 or field not in {"amount", "volume", "turnover"}:
        raise ValueError("invalid volume ratio input")
    values = [getattr(x, field) for x in candles[-period-1:]]
    if any(x is None or x < 0 for x in values):
        raise ValueError("volume ratio requires complete nonnegative values")
    average = sum(values[:-1], ZERO) / Decimal(period)
    return values[-1] / average if average else ZERO


def regression_slope(values: Sequence[Decimal], period: int, *, normalize_by: Decimal | None = None) -> Decimal:
    if period < 2 or len(values) < period:
        raise ValueError("insufficient values for slope")
    selected = values[-period:]
    mean_x = Decimal(period - 1) / 2
    mean_y = sum(selected, ZERO) / Decimal(period)
    denominator = sum((Decimal(i)-mean_x) ** 2 for i in range(period))
    slope = sum((Decimal(i)-mean_x) * (y-mean_y) for i, y in enumerate(selected)) / denominator
    if normalize_by is not None:
        if normalize_by == 0:
            raise ValueError("slope normalization cannot be zero")
        slope /= normalize_by
    return slope


def ema_series(values: Sequence[Decimal], period: int) -> tuple[Decimal, ...]:
    if not values or period < 1:
        raise ValueError("EMA series requires input")
    alpha = Decimal(2) / Decimal(period + 1)
    result = [values[0]]
    for value in values[1:]:
        result.append((value-result[-1])*alpha+result[-1])
    return tuple(result)


@dataclass(frozen=True, slots=True)
class FeatureVector:
    ema20: Decimal
    ema60: Decimal
    ema120: Decimal
    donchian_high20: Decimal
    donchian_low20: Decimal
    vwap20: Decimal
    rsi14: Decimal
    macd12_26: Decimal
    macd_signal9: Decimal
    macd_histogram: Decimal
    atr14: Decimal
    adx14: Decimal
    roc12: Decimal
    return1: Decimal
    volatility20: Decimal
    amount_ratio20: Decimal
    close_slope20: Decimal
    ema20_slope10: Decimal


def feature_vector(candles: Sequence[Candle]) -> FeatureVector:
    if len(candles) < 121:
        raise ValueError("feature vector requires 121 completed candles")
    closes = tuple(x.close for x in candles)
    high, low = donchian(candles, 20, lag=1)
    line, signal, histogram = macd(candles, 12, 26, 9)
    ema20_values = ema_series(closes, 20)
    return FeatureVector(ema(closes, 20), ema(closes, 60), ema(closes, 120), high, low,
        vwap(candles, 20), rsi(candles, 14), line, signal, histogram, atr(candles, 14), adx(candles, 14),
        roc(closes, 12), simple_returns(candles)[-1], rolling_volatility(candles, 20),
        volume_ratio(candles, 20, field="amount"), regression_slope(closes, 20, normalize_by=closes[-1]),
        regression_slope(ema20_values, 10, normalize_by=ema20_values[-1]))


@dataclass(frozen=True, slots=True)
class Performance:
    trades: int
    net_profit: Decimal
    gross_profit: Decimal
    gross_loss: Decimal
    fees: Decimal
    win_rate: Decimal
    profit_factor: Decimal | None
    max_drawdown: Decimal
    sharpe: Decimal | None
    sortino: Decimal | None
    calmar: Decimal | None
    mfe: Decimal
    mae: Decimal


def performance(trades: Sequence[dict]) -> Performance:
    pnl = [Decimal(str(x["net_pnl"])) for x in trades]
    fees = sum((Decimal(str(x.get("fees", 0))) for x in trades), ZERO)
    wins = sum((x for x in pnl if x > 0), ZERO)
    losses = -sum((x for x in pnl if x < 0), ZERO)
    equity = peak = ZERO
    drawdown = ZERO
    for value in pnl:
        equity += value
        peak = max(peak, equity)
        drawdown = max(drawdown, peak-equity)
    win_rate = Decimal(sum(x > 0 for x in pnl)) / Decimal(len(pnl)) if pnl else ZERO
    pf = wins/losses if losses else None
    floating = [float(x) for x in pnl]
    deviation = pstdev(floating) if len(floating) > 1 else 0
    sharpe = Decimal(str(fmean(floating)/deviation*sqrt(len(floating)))) if deviation else None
    downside = [min(x, 0.0) for x in floating]
    downside_dev = sqrt(sum(x*x for x in downside)/len(downside)) if downside else 0
    sortino = Decimal(str(fmean(floating)/downside_dev*sqrt(len(floating)))) if downside_dev else None
    calmar = sum(pnl, ZERO)/drawdown if drawdown else None
    mfe = sum((Decimal(str(x.get("mfe", 0))) for x in trades), ZERO)
    mae = sum((Decimal(str(x.get("mae", 0))) for x in trades), ZERO)
    return Performance(len(trades), sum(pnl, ZERO), wins, losses, fees, win_rate, pf, drawdown,
                       sharpe, sortino, calmar, mfe, mae)
