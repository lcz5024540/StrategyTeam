"""Frozen R3 structural conditions with explicit no-lookahead semantics."""
from __future__ import annotations

from dataclasses import dataclass, replace
from decimal import Decimal
from typing import Sequence

from ..market_data import Candle, period_seconds
from ..models import ModelCandidate, ModelCode, TrendSide
from ..strategy_decision import StrategyDecision
from .features import feature_vector

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class PendingDonchianBreakout:
    candidate: ModelCandidate
    boundary: Decimal
    invalidation: Decimal
    breakout_index: int


@dataclass(frozen=True, slots=True)
class RetestReclaimTrace:
    bar_time: int
    side: str
    age: int
    status: str
    boundary: Decimal
    invalidation: Decimal


@dataclass(frozen=True, slots=True)
class DailyPersistenceTrace:
    bar_time: int
    side: str
    status: str
    daily_evidence: tuple[dict[str, object], ...]


def donchian_retest_reclaim_decisions(
    candles: Sequence[Candle],
    decisions: Sequence[StrategyDecision | None],
) -> tuple[tuple[StrategyDecision | None, ...], tuple[RetestReclaimTrace, ...]]:
    """Delay Donchian entries until a frozen-boundary retest and reclaim.

    A breakout can be confirmed only by one of the next three completed bars.
    The breakout boundary, invalidation and target remain frozen.  The returned
    decision is stamped on the reclaim bar, so the lifecycle simulator still
    fills it at the following bar open.
    """
    if len(candles) != len(decisions):
        raise ValueError("candles and decisions must have identical lengths")
    output: list[StrategyDecision | None] = list(decisions)
    pending: list[PendingDonchianBreakout] = []
    traces: list[RetestReclaimTrace] = []
    for index, candle in enumerate(candles):
        emitted: list[ModelCandidate] = []
        remaining: list[PendingDonchianBreakout] = []
        for item in pending:
            side = item.candidate.side
            age = index-item.breakout_index
            if age < 1:
                continue
            invalidated = (candle.low <= item.invalidation if side is TrendSide.LONG
                           else candle.high >= item.invalidation)
            expired = age > 3
            touched = (candle.low <= item.boundary if side is TrendSide.LONG
                       else candle.high >= item.boundary)
            reclaimed = (candle.close > item.boundary if side is TrendSide.LONG
                         else candle.close < item.boundary)
            if invalidated:
                status = "INVALIDATED"
            elif expired:
                status = "EXPIRED"
            elif touched and reclaimed:
                status = "PASS"
                emitted.append(_reclaimed_candidate(item.candidate, candle))
            else:
                status = "WAITING"
                remaining.append(item)
            traces.append(RetestReclaimTrace(
                candle.open_time, side.value, age, status, item.boundary, item.invalidation))

        decision = decisions[index]
        if decision is not None:
            if emitted:
                output[index] = replace(decision, candidates=tuple(emitted))
            else:
                output[index] = replace(decision, candidates=())
            for candidate in decision.candidates:
                if candidate.model is not ModelCode.DONCHIAN_BREAKOUT:
                    continue
                boundary = (decision.signal.breakout_high if candidate.side is TrendSide.LONG
                            else decision.signal.breakout_low)
                if boundary is not None:
                    remaining.append(PendingDonchianBreakout(
                        candidate, boundary, candidate.invalidation_price, index))
        pending = remaining
    return tuple(output), tuple(traces)


def daily_trend_persistence_decisions(
    candles: Sequence[Candle],
    decisions: Sequence[StrategyDecision | None],
    daily_candles: Sequence[Candle],
) -> tuple[tuple[StrategyDecision | None, ...], tuple[DailyPersistenceTrace, ...]]:
    """Filter 4h EMA momentum candidates and retain every condition evaluation."""
    if len(candles) != len(decisions):
        raise ValueError("candles and decisions must have identical lengths")
    output: list[StrategyDecision | None] = list(decisions)
    traces: list[DailyPersistenceTrace] = []
    for index, (candle, decision) in enumerate(zip(candles, decisions)):
        if decision is None:
            continue
        passed: list[ModelCandidate] = []
        signal_close = candle.open_time+period_seconds(decision.period)
        for candidate in decision.candidates:
            if candidate.model is not ModelCode.EMA_MOMENTUM_CONTINUATION:
                continue
            status, evidence = daily_trend_persistence_status(
                daily_candles, signal_close=signal_close, side=candidate.side)
            traces.append(DailyPersistenceTrace(
                candle.open_time, candidate.side.value, status, evidence))
            if status == "PASS":
                passed.append(replace(
                    candidate,
                    evidence=(*candidate.evidence, "r3_1d_trend_persistence_3"),
                    structure_key=f"{candidate.structure_key}:daily-persistence-3",
                ))
        output[index] = replace(decision, candidates=tuple(passed))
    return tuple(output), tuple(traces)


def daily_trend_persistence_status(
    daily_candles: Sequence[Candle], *, signal_close: int, side: TrendSide,
) -> tuple[str, tuple[dict[str, object], ...]]:
    """Evaluate the last three daily bars that were closed at ``signal_close``.

    Every daily observation is calculated from its own historical prefix.  A
    later daily bar can therefore never alter an earlier decision.
    """
    closed = tuple(item for item in daily_candles
                   if item.open_time+period_seconds("1day") <= signal_close)
    if len(closed) < 123:
        return "UNKNOWN", ()
    evidence: list[dict[str, object]] = []
    passed = True
    for end in range(len(closed)-2, len(closed)+1):
        vector = feature_vector(closed[:end])
        if side is TrendSide.LONG:
            aligned = (vector.ema20 > vector.ema60 > vector.ema120
                       and vector.close_slope20 > ZERO and vector.roc12 > ZERO
                       and vector.adx14 >= Decimal("16"))
        elif side is TrendSide.SHORT:
            aligned = (vector.ema20 < vector.ema60 < vector.ema120
                       and vector.close_slope20 < ZERO and vector.roc12 < ZERO
                       and vector.adx14 >= Decimal("16"))
        else:
            aligned = False
        passed = passed and aligned
        evidence.append({
            "bar_time": closed[end-1].open_time,
            "ema20": vector.ema20,
            "ema60": vector.ema60,
            "ema120": vector.ema120,
            "close_slope20": vector.close_slope20,
            "roc12": vector.roc12,
            "adx14": vector.adx14,
            "result": "PASS" if aligned else "FAIL",
        })
    return ("PASS" if passed else "FAIL"), tuple(evidence)


def _reclaimed_candidate(candidate: ModelCandidate, candle: Candle) -> ModelCandidate:
    risk = (candle.close-candidate.invalidation_price if candidate.side is TrendSide.LONG
            else candidate.invalidation_price-candle.close)
    reward = (candidate.target_low-candle.close if candidate.side is TrendSide.LONG
              else candle.close-candidate.target_low)
    expected_r = max(reward, ZERO)/risk if risk > ZERO else ZERO
    return replace(
        candidate,
        bar_time=candle.open_time,
        entry_price=candle.close,
        expected_r=expected_r,
        evidence=(*candidate.evidence, "r3_donchian_retest_reclaim"),
        source_breakout_time=candidate.bar_time,
        structure_key=f"{candidate.structure_key}:reclaim={candle.open_time}",
    )
