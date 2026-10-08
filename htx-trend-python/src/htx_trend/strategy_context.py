"""Explicit facts shared by model routing and entry preflight.

These records intentionally contain facts only.  They make the strategy
decision reproducible and prevent a model or a gate from reaching into account
state, a global cache, or a live client behind the caller's back.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum
from typing import Sequence

from .market_data import Candle
from .models import TrendSide

ZERO = Decimal("0")


class MarketRegime(StrEnum):
    BROAD_UP = "BROAD_UP"
    BROAD_DOWN = "BROAD_DOWN"
    MIXED = "MIXED"
    CHOP = "CHOP"
    HIGH_VOL_RISK = "HIGH_VOL_RISK"
    LOW_VOL_DEAD = "LOW_VOL_DEAD"


@dataclass(frozen=True, slots=True)
class MarketContext:
    """Cross-market facts that can rank a candidate but cannot invent one."""

    regime: MarketRegime = MarketRegime.MIXED
    breadth_side: TrendSide = TrendSide.FLAT
    breadth_ratio: Decimal = ZERO
    btc_anchor: TrendSide = TrendSide.FLAT
    eth_anchor: TrendSide = TrendSide.FLAT
    volatility_expanding: bool = False

    @classmethod
    def neutral(cls) -> "MarketContext":
        return cls()

    def aligned(self, side: TrendSide) -> bool:
        return side is not TrendSide.FLAT and (
            self.breadth_side is side or (self.btc_anchor is side and self.eth_anchor is side)
        )

    def opposed(self, side: TrendSide) -> bool:
        opposite = TrendSide.SHORT if side is TrendSide.LONG else TrendSide.LONG
        return self.breadth_side is opposite and self.breadth_ratio >= Decimal("0.70")


@dataclass(frozen=True, slots=True)
class CandidateTrendState:
    """Quality of this candidate, never a proxy for recent trade outcomes."""

    score: Decimal
    tradable: bool
    strong: bool
    reason: str

    @classmethod
    def from_candidate(cls, score: Decimal, expected_r: Decimal, context: MarketContext) -> "CandidateTrendState":
        strong = score >= Decimal("90") and expected_r >= Decimal("1")
        tradable = score >= Decimal("70") and expected_r > ZERO
        reason = f"score={score}; expected_r={expected_r}; regime={context.regime.value}"
        return cls(score, tradable, strong, reason)


@dataclass(frozen=True, slots=True)
class FibonacciQuality:
    """Completed-bar quality evidence matching the Java Fibonacci entry gate."""

    volume_ratio: Decimal | None
    body_to_range: Decimal | None
    counter_wick_to_range: Decimal | None
    range_to_atr: Decimal | None
    ema_reclaimed: bool

    @classmethod
    def from_candles(cls, candles: Sequence[Candle], side: TrendSide, atr: Decimal, ema_fast: Decimal,
                     *, volume_lookback: int = 20) -> "FibonacciQuality":
        if not candles:
            return cls(None, None, None, None, False)
        current = candles[-1]
        range_ = current.high - current.low
        if range_ <= ZERO:
            return cls(None, None, None, None, False)
        body_high, body_low = max(current.open, current.close), min(current.open, current.close)
        body = abs(current.close - current.open) / range_
        # A counter-direction wick is the wick against the intended continuation.
        counter_wick = (current.high - body_high) / range_ if side is TrendSide.LONG else (body_low - current.low) / range_
        values = [item.turnover for item in candles[-volume_lookback - 1:-1] if item.turnover is not None and item.turnover > ZERO]
        current_turnover = current.turnover
        volume_ratio = None
        if current_turnover is not None and current_turnover > ZERO and values:
            volume_ratio = current_turnover / (sum(values, ZERO) / Decimal(len(values)))
        range_to_atr = range_ / atr if atr > ZERO else None
        reclaimed = current.close >= ema_fast if side is TrendSide.LONG else current.close <= ema_fast
        return cls(volume_ratio, body, counter_wick, range_to_atr, reclaimed)
