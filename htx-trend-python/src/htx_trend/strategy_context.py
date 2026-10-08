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
    # Java treats the BTC/ETH anchor as an independent multi-timeframe regime;
    # keep it separate from breadth so the two cannot accidentally promote each other.
    anchor_regime: MarketRegime = MarketRegime.MIXED
    volatility_expanding: bool = False

    @classmethod
    def neutral(cls) -> "MarketContext":
        return cls()

    def breadth_aligned(self, side: TrendSide) -> bool:
        return side is TrendSide.LONG and self.regime is MarketRegime.BROAD_UP or side is TrendSide.SHORT and self.regime is MarketRegime.BROAD_DOWN

    def breadth_opposed(self, side: TrendSide) -> bool:
        return side is TrendSide.LONG and self.regime is MarketRegime.BROAD_DOWN or side is TrendSide.SHORT and self.regime is MarketRegime.BROAD_UP

    def anchor_aligned(self, side: TrendSide) -> bool:
        return side is TrendSide.LONG and self.anchor_regime is MarketRegime.BROAD_UP or side is TrendSide.SHORT and self.anchor_regime is MarketRegime.BROAD_DOWN

    def anchor_opposed(self, side: TrendSide) -> bool:
        return side is TrendSide.LONG and self.anchor_regime is MarketRegime.BROAD_DOWN or side is TrendSide.SHORT and self.anchor_regime is MarketRegime.BROAD_UP

    # Retained for existing callers. Java's trend-state evaluator deliberately
    # uses breadth only; market anchor has its own score and confidence path.
    def aligned(self, side: TrendSide) -> bool:
        return self.breadth_aligned(side)

    def opposed(self, side: TrendSide) -> bool:
        return self.breadth_opposed(side)


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


@dataclass(frozen=True, slots=True)
class CounterMarketDecision:
    """Literal result of Java ``HtxTrendCounterMarketEntryGate``."""

    counter_market: bool
    qualified: bool
    reason: str

    @property
    def blocks_entry(self) -> bool:
        return self.counter_market and not self.qualified


def evaluate_counter_market(*, side: TrendSide, context: MarketContext, model_score: Decimal,
                            expected_r: Decimal, momentum_score: Decimal, adx: Decimal,
                            ema_fast: Decimal, ema_slow: Decimal, confirmations: str,
                            config: EntryStateConfig = EntryStateConfig()) -> CounterMarketDecision:
    """Port Java's counter-market gate before candidate-state evaluation."""
    if not context.breadth_opposed(side):
        return CounterMarketDecision(False, False, "market direction aligned or mixed")
    if not config.counter_enabled:
        return CounterMarketDecision(True, False, "counter-market entry disabled")
    checks = ((model_score >= config.counter_min_model_score, f"modelScore={model_score}<{config.counter_min_model_score}"),
              (expected_r >= config.counter_min_expected_r, f"expectedR={expected_r}<{config.counter_min_expected_r}"),
              (momentum_score >= config.counter_min_momentum_score, f"momentum={momentum_score}<{config.counter_min_momentum_score}"),
              (adx >= config.counter_min_adx, f"adx={adx}<{config.counter_min_adx}"))
    for passed, reason in checks:
        if not passed:
            return CounterMarketDecision(True, False, reason)
    ema_aligned = ((side is TrendSide.LONG and ema_fast > ema_slow)
                   or (side is TrendSide.SHORT and ema_fast < ema_slow))
    if not ema_aligned:
        return CounterMarketDecision(True, False, "EMA not aligned with counter-market direction")
    labels = confirmations.upper()
    if "DONCHIAN_BREAKOUT" not in labels and "BOX_BREAKOUT" not in labels:
        return CounterMarketDecision(True, False, "missing independent breakout confirmation")
    return CounterMarketDecision(True, True, "independent structure confirmed; counter-market sizing applies")


def evaluate_candidate_state(model_score: Decimal, expected_r: Decimal, momentum_score: Decimal, side: TrendSide,
                             ema_fast: Decimal, ema_slow: Decimal, adx: Decimal, context: MarketContext,
                             has_independent_structure: bool = False, config: EntryStateConfig = EntryStateConfig(),
                             counter_market_qualified: bool | None = None) -> CandidateTrendState:
    """Literal port of Java ``HtxTrendStateEvaluator.evaluateEntry``.

    Counter-market eligibility is supplied by ``evaluate_counter_market`` so
    the explicit counter gate and candidate-state calculation cannot drift.
    """
    ema_aligned = (side is TrendSide.LONG and ema_fast > ema_slow) or (side is TrendSide.SHORT and ema_fast < ema_slow)
    broad_aligned = context.breadth_aligned(side)
    broad_opposed = context.breadth_opposed(side)
    if counter_market_qualified is None:
        # Compatibility fallback for paper callers that predate the explicit
        # counter-market decision. Live execution always supplies it.
        counter_market_qualified = broad_opposed and config.counter_enabled and all((
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
                   if broad_opposed and counter_market_qualified else score)
    tradable = (ema_aligned and (not broad_opposed or counter_market_qualified) and expected_r >= config.min_expected_r
                and momentum_score >= config.min_momentum_score and eligibility >= config.min_trend_score)
    # Java's extra TRENDING_UP/DOWN branch is unreachable here because this
    # input is persisted breadth (BROAD_*/MIXED), so breadth alignment is the
    # operative strong condition in both implementations.
    strong = tradable and not broad_opposed and score >= config.strong_trend_score and expected_r >= config.strong_expected_r and broad_aligned
    regime = "STRONG" if strong else "COUNTER_QUALIFIED" if tradable and broad_opposed else "QUALIFIED" if tradable else "WEAK"
    reason = (f"regime={regime}; model={model_score}; expected_r={expected_r}; momentum={momentum_score}; "
              f"ema_aligned={ema_aligned}; breadth={context.regime.value}; raw={raw}; score={eligibility}; "
              f"counter_qualified={counter_market_qualified}")
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
        # Java: for LONG the adverse wick is below the body, for SHORT it is
        # above the body.  The previous Python implementation inverted this.
        counter_wick = (body_low - current.low) / range_ if side is TrendSide.LONG else (current.high - body_high) / range_
        def volume(item: Candle) -> Decimal | None:
            return next((value for value in (item.amount, item.volume, item.turnover)
                         if value is not None and value > ZERO), None)
        values = [value for item in candles[-volume_lookback - 1:-1] if (value := volume(item)) is not None]
        current_volume = volume(current)
        volume_ratio = None
        if current_volume is not None and values:
            volume_ratio = current_volume / (sum(values, ZERO) / Decimal(len(values)))
        range_to_atr = range_ / atr if atr > ZERO else None
        reclaimed = current.close >= ema_fast if side is TrendSide.LONG else current.close <= ema_fast
        return cls(volume_ratio, body, counter_wick, range_to_atr, reclaimed)
