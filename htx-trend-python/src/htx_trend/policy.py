"""Database-authorized trend candidate routing compatible with Java combinations.

The Java executor never promotes a raw model candidate directly.  It first
matches ``primary_model + confirmation_models`` against the enabled policy
ledger, checks the *unadjusted* model score, then applies market state only to
ranking and sizing.  This module keeps those three decisions separate.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Iterable

from .diagnostics import PolicyDiagnosis, RejectCode
from .models import ModelCandidate, ModelCode, TrendSide
from .strategy_context import MarketContext, MarketRegime

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class StrategyPolicy:
    policy_code: str
    policy_version: str
    primary_model: str
    confirmation_models: str
    base_notional_usdt: Decimal
    max_notional_usdt: Decimal
    score_threshold: Decimal
    box_window_bars: int


@dataclass(frozen=True, slots=True)
class PolicySelection:
    candidate: ModelCandidate
    policy: StrategyPolicy
    base_score: Decimal
    market_adjustment: Decimal
    effective_score: Decimal
    sizing_multiplier: Decimal
    planned_notional_usdt: Decimal
    confirmation_models: str


def confirmation_models(candidate: ModelCandidate) -> str:
    """Derive Java policy evidence names from one independent hypothesis.

    A Python ``ModelCandidate`` retains simultaneous model observations, while
    Java's policy table stores structural confirmations as a canonical string.
    The mapping intentionally uses evidence owned by the candidate; it does
    not reinterpret an unrelated prior order as confirmation.
    """
    # A Java-combination candidate carries the exact policy evidence string.
    # Do not reconstruct it from a lossy Python model label: Java has distinct
    # DONCHIAN/FIB combinations such as BOX_BREAKOUT,DONCHIAN_BREAKOUT.
    if candidate.policy_confirmations:
        return candidate.policy_confirmations
    labels: list[str]
    if candidate.model is ModelCode.DONCHIAN_BREAKOUT:
        labels = ["BOX_BREAKOUT"]
    elif candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION:
        # Fibonacci is derived from a completed breakout impulse and box.
        labels = ["BOX_BREAKOUT"]
        if ModelCode.DONCHIAN_BREAKOUT in candidate.confirmation_models:
            labels.append("DONCHIAN_BREAKOUT")
    elif candidate.model is ModelCode.EMA_MOMENTUM_CONTINUATION:
        labels = ["EMA_STACK", "MOMENTUM"]
    else:  # Defensive: unknown models must never gain live policy access.
        labels = []
    return ",".join(labels)


def market_adjustment(candidate: ModelCandidate, context: MarketContext) -> Decimal:
    """Exact Java ``MarketEntryRank`` arithmetic; it ranks but never admits."""
    # HtxTrendFollowingService constructs MarketEntryRank(8, 12, 2).  The
    # model weight is part of the Java score contract, including MIXED.
    weight = (Decimal("1.20") if candidate.model is ModelCode.DONCHIAN_BREAKOUT
              else Decimal("1") if candidate.model is ModelCode.EMA_MOMENTUM_CONTINUATION
              else Decimal("0.80"))
    # Java creates an aligned side only when breadth and the separately
    # calculated BTC/ETH multi-timeframe anchor classify the same direction.
    anchor_side = (TrendSide.LONG if context.anchor_regime is MarketRegime.BROAD_UP
                   else TrendSide.SHORT if context.anchor_regime is MarketRegime.BROAD_DOWN
                   # Compatibility only for callers that build the old five-field
                   # fact record. The live worker always supplies anchor_regime.
                   else TrendSide.LONG if context.btc_anchor is TrendSide.LONG and context.eth_anchor is TrendSide.LONG
                   else TrendSide.SHORT if context.btc_anchor is TrendSide.SHORT and context.eth_anchor is TrendSide.SHORT
                   else TrendSide.FLAT)
    aligned_side = context.breadth_side if context.breadth_side is anchor_side else TrendSide.FLAT
    if aligned_side is TrendSide.FLAT:
        return -(Decimal("2") * weight)
    if candidate.side is aligned_side:
        return Decimal("8") * weight
    return -(Decimal("12") * weight)


def sizing_multiplier(base_score: Decimal, regime: MarketRegime | str) -> Decimal:
    value = regime.value if isinstance(regime, MarketRegime) else regime
    if value == MarketRegime.MIXED.value:
        return Decimal("0.5")
    if base_score >= Decimal("95"):
        return Decimal("1.5")
    if base_score >= Decimal("90"):
        return Decimal("1.25")
    return Decimal("1")


def select_policy(candidates: Iterable[ModelCandidate], policies: Iterable[StrategyPolicy],
                  context: MarketContext) -> tuple[PolicySelection, ...]:
    """Select all Java-authorized combinations, highest effective score first.

    Exact confirmation-string matching is deliberate: changing policy evidence
    requires a dashboard/DB policy change, not an accidental code fallback.
    Threshold checks use base score just as Java does.
    """
    enabled = tuple(policies)
    resolved: list[PolicySelection] = []
    for candidate in candidates:
        confirmations = confirmation_models(candidate)
        for policy in enabled:
            if (policy.primary_model != candidate.model.value
                    or policy.confirmation_models != confirmations
                    or candidate.score < policy.score_threshold):
                continue
            adjustment = market_adjustment(candidate, context)
            effective = min(Decimal("100"), max(ZERO, candidate.score + adjustment))
            # Java combination sizing is based on the candidate's own local
            # market state.  Cross-symbol breadth/anchor affects only rank;
            # it is applied once later by HtxTrendFollowingService.sizingContext.
            multiplier = sizing_multiplier(candidate.score, candidate.market_regime or context.regime)
            planned = min(policy.max_notional_usdt, policy.base_notional_usdt * multiplier)
            resolved.append(PolicySelection(candidate, policy, candidate.score, adjustment, effective,
                                            multiplier, planned, confirmations))
    return tuple(sorted(resolved, key=lambda item: (item.effective_score, item.base_score,
                                                     item.candidate.expected_r), reverse=True))


def explain_policy(candidates: Iterable[ModelCandidate], policies: Iterable[StrategyPolicy]) -> tuple[PolicyDiagnosis, ...]:
    """Explain exact evidence matching and raw-score thresholds, without ranking."""
    enabled = tuple(policies)
    result = []
    for candidate in candidates:
        confirmations = confirmation_models(candidate)
        matches = tuple(policy for policy in enabled if policy.primary_model == candidate.model.value
                        and policy.confirmation_models == confirmations)
        code = (RejectCode.NO_ENABLED_POLICY if not enabled else
                RejectCode.POLICY_NOT_MATCHED if not matches else
                RejectCode.POLICY_SCORE_BELOW_THRESHOLD if all(candidate.score < p.score_threshold for p in matches)
                else None)
        result.append(PolicyDiagnosis(candidate.model.value, confirmations, code, candidate.score,
                                      tuple(p.score_threshold for p in matches)))
    return tuple(result)
