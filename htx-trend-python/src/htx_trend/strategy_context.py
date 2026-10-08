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
    def from_candidate(cls, score: Decimal, expected_r: Decimal, context: MarketContext,
                       side: TrendSide = TrendSide.LONG) -> "CandidateTrendState":
        # Compatibility fallback for call sites that do not yet carry the
        # factor snapshot.  Real execution uses ``evaluate_candidate_state``.
        return evaluate_candidate_state(score, expected_r, score, side, Decimal("2") if side is TrendSide.LONG else Decimal("1"), Decimal("1") if side is TrendSide.LONG else Decimal("2"),
                                        Decimal("100"), context, False)


@dataclass(frozen=True, slots=True)
class EntryStateConfig:
    """Default values mirror ``HtxTrendProperties`` in the Java strategy."""

    min_trend_score: Decimal = Decimal("75")
    strong_trend_score: Decimal = Decimal("88")
    min_expected_r: Decimal = Decimal("1.25")
    strong_expected_r: Decimal = Decimal("1.75")
    min_momentum_score: Decimal = Decimal("60")
    counter_enabled: bool = False
    counter_min_model_score: Decimal = Decimal("88")
    counter_min_expected_r: Decimal = Decimal("2.00")
    counter_min_momentum_score: Decimal = Decimal("65")
    counter_min_adx: Decimal = Decimal("25")


def evaluate_candidate_state(model_score: Decimal, expected_r: Decimal, momentum_score: Decimal, side: TrendSide,
                             ema_fast: Decimal, ema_slow: Decimal, adx: Decimal, context: MarketContext,
                             has_independent_structure: bool, config: EntryStateConfig = EntryStateConfig()) -> CandidateTrendState:
    """Port of Java ``HtxTrendStateEvaluator`` and counter-market gate."""
    ema_aligned = (side is TrendSide.LONG and ema_fast > ema_slow) or (side is TrendSide.SHORT and ema_fast < ema_slow)
    broad_aligned = context.aligned(side)
    broad_opposed = context.opposed(side)
    counter_qualified = broad_opposed and config.counter_enabled and all((
        model_score >= config.counter_min_model_score, expected_r >= config.counter_min_expected_r,
        momentum_score >= config.counter_min_momentum_score, adx >= config.counter_min_adx,
        ema_aligned, has_independent_structure,
    ))
    expected_adjustment = Decimal("8") if expected_r >= config.min_expected_r else Decimal("-25")
    momentum_adjustment = Decimal("5") if momentum_score >= config.min_momentum_score else Decimal("-15")
    ema_adjustment = Decimal("5") if ema_aligned else Decimal("-25")
    breadth_adjustment = Decimal("8") if broad_aligned else Decimal("-40") if broad_opposed else ZERO
    raw = model_score + expected_adjustment + momentum_adjustment + ema_adjustment + breadth_adjustment
    score = min(Decimal("100"), max(ZERO, raw))
    eligibility = (min(Decimal("100"), max(ZERO, model_score + expected_adjustment + momentum_adjustment + ema_adjustment))
                   if broad_opposed and counter_qualified else score)
    tradable = (ema_aligned and (not broad_opposed or counter_qualified) and expected_r >= config.min_expected_r
                and momentum_score >= config.min_momentum_score and eligibility >= config.min_trend_score)
    strong = tradable and not broad_opposed and score >= config.strong_trend_score and expected_r >= config.strong_expected_r and broad_aligned
    regime = "STRONG" if strong else "COUNTER_QUALIFIED" if tradable and broad_opposed else "QUALIFIED" if tradable else "WEAK"
    reason = (f"regime={regime}; model={model_score}; expected_r={expected_r}; momentum={momentum_score}; "
              f"ema_aligned={ema_aligned}; breadth={context.regime.value}; raw={raw}; score={eligibility}; "
              f"counter_qualified={counter_qualified}")
    return CandidateTrendState(eligibility, tradable, strong, reason)


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
