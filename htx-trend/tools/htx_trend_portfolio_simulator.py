"""Deterministic research-only execution and portfolio-risk primitives.

All inputs are supplied by a frozen research run or synthetic tests.  This module
never queries an exchange or account, and it does not decide live orders.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from htx_trend_backtest import Bar
from htx_trend_research_engine import OrderStyle, ResearchSignal, Side, _adverse_price


@dataclass(frozen=True)
class ExecutionCostModel:
    fee_rate: float = .0004
    entry_slippage_bps: float = 2.0
    funding_rate: float | None = None
    funding_interval_bars: int = 32


@dataclass(frozen=True)
class CandidateOrder:
    signal: ResearchSignal
    requested_notional: float
    cluster: str


@dataclass(frozen=True)
class FillDecision:
    status: str
    filled_notional: float
    unfilled_notional: float
    entry_price: float | None
    reason: str | None = None


@dataclass(frozen=True)
class PortfolioLimits:
    max_active_positions: int
    max_per_cluster: int
    max_gross_notional: float


@dataclass(frozen=True)
class AllocationDecision:
    candidate: CandidateOrder
    accepted: bool
    reason: str | None


def simulate_entry(order: CandidateOrder, next_bar: Bar, available_notional: float,
                   costs: ExecutionCostModel) -> FillDecision:
    """Simulate a bounded fill. LIMIT/IOC do not fill unless the next bar touches price."""
    plan, side = order.signal.price_plan, order.signal.side
    if order.requested_notional <= 0 or available_notional <= 0:
        return FillDecision("UNFILLED", 0.0, max(order.requested_notional, 0.0), None, "no_available_liquidity")
    if plan.order_style is not OrderStyle.MARKET and not (next_bar.low <= plan.ideal_entry <= next_bar.high):
        return FillDecision("UNFILLED", 0.0, order.requested_notional, None, "limit_not_touched")
    price = _adverse_price(side, next_bar.open, costs.entry_slippage_bps) if plan.order_style is OrderStyle.MARKET else plan.ideal_entry
    filled = min(order.requested_notional, available_notional)
    return FillDecision("FILLED" if filled == order.requested_notional else "PARTIALLY_FILLED", filled,
                        order.requested_notional - filled, price)


def funding_pnl(side: Side, notional: float, funding_rate: float | None, intervals_held: int) -> float | None:
    """Positive funding means longs pay and shorts receive; None preserves missing evidence."""
    if funding_rate is None:
        return None
    return -notional * funding_rate * intervals_held if side is Side.LONG else notional * funding_rate * intervals_held


def select_portfolio(candidates: Iterable[CandidateOrder], limits: PortfolioLimits) -> list[AllocationDecision]:
    """Apply deterministic slots, duplicate-symbol, correlation-cluster and gross-notional limits."""
    accepted: list[CandidateOrder] = []
    symbols: set[str] = set()
    cluster_counts: dict[str, int] = {}
    gross = 0.0
    decisions: list[AllocationDecision] = []
    for candidate in sorted(candidates, key=lambda item: (-item.signal.score, item.signal.symbol, item.signal.model)):
        if candidate.signal.symbol in symbols:
            decisions.append(AllocationDecision(candidate, False, "duplicate_symbol")); continue
        if len(accepted) >= limits.max_active_positions:
            decisions.append(AllocationDecision(candidate, False, "max_active_positions")); continue
        if cluster_counts.get(candidate.cluster, 0) >= limits.max_per_cluster:
            decisions.append(AllocationDecision(candidate, False, "cluster_limit")); continue
        if gross + candidate.requested_notional > limits.max_gross_notional:
            decisions.append(AllocationDecision(candidate, False, "gross_notional_limit")); continue
        accepted.append(candidate); symbols.add(candidate.signal.symbol); gross += candidate.requested_notional
        cluster_counts[candidate.cluster] = cluster_counts.get(candidate.cluster, 0) + 1
        decisions.append(AllocationDecision(candidate, True, None))
    return decisions
