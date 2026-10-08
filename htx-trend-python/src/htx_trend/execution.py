"""Candidate preflight, price planning, cooldown and risk sizing.

This module has no HTX mutation dependency.  A candidate can pass every gate and
still only produce a paper order until P6 explicitly enables a single-account
live executor.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from enum import StrEnum
from typing import Sequence

from .market_data import Candle, ContractSpec, FundingRate, TopOfBook, period_seconds
from .models import ModelCandidate, ModelCode, TrendSide
from .strategy_context import CandidateTrendState, FibonacciQuality, MarketContext

ZERO = Decimal("0")


class ExecutionStyle(StrEnum):
    PASSIVE_LIMIT = "PASSIVE_LIMIT"
    IOC_OR_MARKET = "IOC_OR_MARKET"
    CONDITIONAL_OR_MARKET = "CONDITIONAL_OR_MARKET"


@dataclass(frozen=True, slots=True)
class ExecutionConfig:
    min_score: Decimal = Decimal("70")
    max_spread_rate: Decimal = Decimal("0.0010")
    min_average_turnover: Decimal = ZERO
    liquidity_lookback: int = 20
    max_adverse_funding_rate: Decimal = Decimal("0.0010")
    min_net_r: Decimal = Decimal("0.25")
    fee_rate: Decimal = Decimal("0.0004")
    slippage_atr_fraction: Decimal = Decimal("0.10")
    target_notional: Decimal = Decimal("190")
    min_notional: Decimal = Decimal("20")
    max_notional: Decimal = Decimal("190")
    risk_per_trade: Decimal = Decimal("1.5")
    loss_cooldown_seconds: int = 3_600
    breakeven_cooldown_seconds: int = 1_800
    profit_cooldown_seconds: int = 900
    strong_reentry_seconds: int = 600
    profit_threshold: Decimal = Decimal("1")
    strong_score: Decimal = Decimal("90")
    extreme_score: Decimal = Decimal("98")
    signal_freshness_seconds: int = 600
    max_entry_chase_atr_multiplier: Decimal = Decimal("0.20")
    min_depth_multiple: Decimal = Decimal("3")
    min_fib_volume_ratio: Decimal = Decimal("1")
    min_fib_body_to_range: Decimal = Decimal("0.20")
    max_fib_counter_wick_to_range: Decimal = Decimal("0.75")
    max_fib_entry_range_atr: Decimal = Decimal("2.50")
    max_atr_pct: Decimal = Decimal("0.0500")
    require_execution_facts: bool = False
    java_market_order_compatibility: bool = True
    margin_safety_ratio: Decimal = Decimal("1.20")
    min_available_margin: Decimal = Decimal("20")


@dataclass(frozen=True, slots=True)
class PriorTrade:
    contract_code: str
    closed_at_epoch: int
    net_profit: Decimal
    failure_type: str | None = None


@dataclass(frozen=True, slots=True)
class CooldownDecision:
    allowed: bool
    reason: str
    remaining_seconds: int
    override_reason: str | None = None


@dataclass(frozen=True, slots=True)
class PricePlan:
    style: ExecutionStyle
    desired_entry: Decimal
    worst_acceptable_entry: Decimal
    invalidation: Decimal
    target_low: Decimal
    target_high: Decimal
    expected_net_r: Decimal


@dataclass(frozen=True, slots=True)
class SizePlan:
    contracts: int
    notional: Decimal
    risk_at_stop: Decimal
    reason: str


@dataclass(frozen=True, slots=True)
class GateResult:
    allowed: bool
    stage: str
    reason: str
    price_plan: PricePlan | None = None
    size_plan: SizePlan | None = None


@dataclass(frozen=True, slots=True)
class ExecutionFacts:
    """Account and exchange facts supplied by an adapter, never inferred.

    ``None`` means unavailable rather than safe.  Paper/research callers may
    use the relaxed configuration; a future live adapter must set
    ``require_execution_facts=True`` and provide every required field.
    """

    actual_taker_fee_rate: Decimal | None = None
    same_direction_open_order: bool = False
    external_position_side: TrendSide | None = None
    exchange_position_consistent: bool = True
    executable_depth_contracts: Decimal | None = None
    available_margin_usdt: Decimal | None = None
    leverage: Decimal | None = None
    active_position_count: int | None = None
    max_active_positions: int | None = None
    reserved_notional_usdt: Decimal = ZERO
    risk_capacity_usdt: Decimal | None = None


def cooldown(candidate: ModelCandidate, prior: PriorTrade | None, *, now_epoch: int,
             config: ExecutionConfig = ExecutionConfig()) -> CooldownDecision:
    if prior is None:
        return CooldownDecision(True, "no prior trade", 0)
    elapsed = max(0, now_epoch - prior.closed_at_epoch)
    if prior.net_profit < ZERO:
        duration = config.loss_cooldown_seconds
        if candidate.score >= config.extreme_score and elapsed >= config.strong_reentry_seconds:
            return CooldownDecision(True, "loss cooldown overridden by extreme new structure", 0,
                                    f"score={candidate.score};previous_failure={prior.failure_type or 'unknown'}")
        category = "loss"
    elif prior.net_profit < config.profit_threshold:
        duration, category = config.breakeven_cooldown_seconds, "breakeven"
        if candidate.score >= config.strong_score and elapsed >= config.strong_reentry_seconds:
            return CooldownDecision(True, "breakeven cooldown overridden by strong signal", 0, f"score={candidate.score}")
    else:
        duration, category = config.profit_cooldown_seconds, "profit"
        if candidate.score >= config.strong_score and elapsed >= config.strong_reentry_seconds:
            return CooldownDecision(True, "profit cooldown overridden by strong signal", 0, f"score={candidate.score}")
    remaining = max(0, duration - elapsed)
    return CooldownDecision(remaining == 0, f"{category} cooldown", remaining)


def price_plan(candidate: ModelCandidate, *, atr: Decimal, execution_price: Decimal | None = None, fee_rate: Decimal | None = None,
               config: ExecutionConfig = ExecutionConfig()) -> PricePlan:
    # Java's current production trend path uses a V5 market order for every
    # model.  Passive Fibonacci probing is an experiment and must be selected
    # explicitly rather than silently changing parity.
    style = (ExecutionStyle.IOC_OR_MARKET if config.java_market_order_compatibility
             else ExecutionStyle.PASSIVE_LIMIT if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION
             else ExecutionStyle.CONDITIONAL_OR_MARKET if candidate.model is ModelCode.DONCHIAN_BREAKOUT
             else ExecutionStyle.IOC_OR_MARKET)
    desired = execution_price or candidate.entry_price
    adverse = atr * config.slippage_atr_fraction
    worst = desired + adverse if candidate.side is TrendSide.LONG else desired - adverse
    target = candidate.target_low
    gross_reward = target - worst if candidate.side is TrendSide.LONG else worst - target
    risk = worst - candidate.invalidation_price if candidate.side is TrendSide.LONG else candidate.invalidation_price - worst
    round_trip_cost = worst * (fee_rate if fee_rate is not None else config.fee_rate) * Decimal("2") + adverse
    net_reward = gross_reward - round_trip_cost
    expected_net_r = net_reward / risk if risk > ZERO else ZERO
    return PricePlan(style, desired, worst, candidate.invalidation_price, candidate.target_low,
                     candidate.target_high, expected_net_r)


def size_plan(candidate: ModelCandidate, contract: ContractSpec, price: PricePlan,
              config: ExecutionConfig = ExecutionConfig()) -> SizePlan:
    stop_distance = abs(price.desired_entry - price.invalidation)
    if stop_distance <= ZERO or contract.contract_size <= ZERO:
        return SizePlan(0, ZERO, ZERO, "invalid stop distance or contract size")
    risk_per_contract = stop_distance * contract.contract_size
    by_risk = int((config.risk_per_trade / risk_per_contract).to_integral_value(rounding=ROUND_DOWN))
    by_target = int((config.target_notional / (price.desired_entry * contract.contract_size)).to_integral_value(rounding=ROUND_DOWN))
    by_max = int((config.max_notional / (price.desired_entry * contract.contract_size)).to_integral_value(rounding=ROUND_DOWN))
    contracts = max(0, min(by_risk, by_target, by_max))
    notional = price.desired_entry * contract.contract_size * contracts
    if contracts == 0 or notional < config.min_notional:
        return SizePlan(0, notional, risk_per_contract * contracts, "sizing below minimum notional")
    return SizePlan(contracts, notional, risk_per_contract * contracts, "risk and notional capped")


def evaluate_gate(candidate: ModelCandidate, *, period: str, now_epoch: int, atr: Decimal, order_book: TopOfBook,
                  contract: ContractSpec, funding: FundingRate | None, recent_candles: Sequence[Candle],
                  prior_trade: PriorTrade | None, market_context: MarketContext | None = None,
                  candidate_state: CandidateTrendState | None = None, fibonacci_quality: FibonacciQuality | None = None,
                  execution_facts: ExecutionFacts | None = None, claimed_structure_keys: frozenset[str] = frozenset(),
                  config: ExecutionConfig = ExecutionConfig()) -> GateResult:
    """Apply the Java-equivalent gate sequence with durable stage semantics.

    The function is pure.  It does not query the exchange or mutate a
    structure claim; callers persist the returned decision and claim the
    structure only after an actual fill.
    """
    context = market_context or MarketContext.neutral()
    # A real execution adapter supplies the Java-equivalent factor state.  The
    # paper harness deliberately preserves its old, explicit relaxed behavior
    # when that snapshot is absent; it must never be mistaken for LIVE parity.
    state = candidate_state or (CandidateTrendState.from_candidate(candidate.score, candidate.expected_r, context, candidate.side)
                                if config.require_execution_facts else CandidateTrendState(
                                    candidate.score, candidate.expected_r > ZERO, False, "paper state not calibrated"))
    facts = execution_facts or ExecutionFacts()

    # MODE is intentionally an adapter concern.  A caller that reaches this
    # pure policy has already selected PAPER or an explicitly authorised LIVE instance.
    if candidate.score < config.min_score:
        return GateResult(False, "MODEL_POLICY", f"candidate score {candidate.score} below {config.min_score}")
    # HTX bar ids mark the open.  Java measures freshness from completed-bar
    # time, therefore the period belongs in this boundary as well.
    if now_epoch > candidate.bar_time + period_seconds(period) + config.signal_freshness_seconds:
        return GateResult(False, "DATA_FRESHNESS", f"candidate bar expired; max_age={config.signal_freshness_seconds}s")
    if candidate.structure_key and candidate.structure_key in claimed_structure_keys:
        return GateResult(False, "COOLDOWN_STRUCTURE", "identical model structure already claimed")
    if not state.tradable:
        return GateResult(False, "MARKET_CONTEXT", f"candidate trend state not tradable; {state.reason}")
    if context.regime.value in {"CHOP", "LOW_VOL_DEAD"}:
        return GateResult(False, "MARKET_CONTEXT", f"market regime blocks new trend entry: {context.regime.value}")
    if context.opposed(candidate.side) and not state.strong:
        return GateResult(False, "MARKET_CONTEXT", "candidate opposes broad market without strong independent evidence")

    cooldown_result = cooldown(candidate, prior_trade, now_epoch=now_epoch, config=config)
    if not cooldown_result.allowed:
        return GateResult(False, "COOLDOWN_STRUCTURE", f"{cooldown_result.reason}; remaining={cooldown_result.remaining_seconds}s")

    if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION:
        quality = fibonacci_quality
        if quality is None and config.require_execution_facts:
            return GateResult(False, "FIB_QUALITY", "Fibonacci execution quality evidence unavailable")
        if quality is not None:
            if not quality.ema_reclaimed:
                return GateResult(False, "FIB_QUALITY", "Fibonacci retest has not reclaimed EMA structure")
            if quality.volume_ratio is None and config.require_execution_facts:
                return GateResult(False, "FIB_QUALITY", "Fibonacci relative turnover unavailable")
            if quality.volume_ratio is not None and quality.volume_ratio < config.min_fib_volume_ratio:
                return GateResult(False, "FIB_QUALITY", f"Fibonacci relative volume below threshold ratio={quality.volume_ratio}")
            if quality.body_to_range is None or quality.body_to_range < config.min_fib_body_to_range:
                return GateResult(False, "FIB_QUALITY", "Fibonacci retest wick-only body")
            if quality.counter_wick_to_range is not None and quality.counter_wick_to_range > config.max_fib_counter_wick_to_range:
                return GateResult(False, "FIB_QUALITY", "Fibonacci retest counter wick too large")
            if quality.range_to_atr is not None and quality.range_to_atr > config.max_fib_entry_range_atr:
                return GateResult(False, "FIB_QUALITY", "Fibonacci retest one-bar spike")

    if order_book.spread_rate > config.max_spread_rate:
        return GateResult(False, "BOOK_SPREAD", f"spread {order_book.spread_rate} above {config.max_spread_rate}")
    if atr > ZERO and atr / candidate.entry_price > config.max_atr_pct:
        return GateResult(False, "BOOK_SPREAD", f"ATR percentage {atr / candidate.entry_price} above {config.max_atr_pct}")
    if facts.same_direction_open_order:
        return GateResult(False, "ORDER_POSITION", "same-direction open order already exists")
    if facts.external_position_side is not None:
        return GateResult(False, "ORDER_POSITION", f"external {facts.external_position_side.value} position needs manual reconciliation")
    if not facts.exchange_position_consistent:
        return GateResult(False, "ORDER_POSITION", "ledger and exchange position state are inconsistent")
    if facts.active_position_count is not None and facts.max_active_positions is not None and facts.active_position_count >= facts.max_active_positions:
        return GateResult(False, "MARGIN_RISK", "active position capacity exhausted")

    fill = order_book.ask if candidate.side is TrendSide.LONG else order_book.bid
    adverse = fill - candidate.entry_price if candidate.side is TrendSide.LONG else candidate.entry_price - fill
    if atr > ZERO and adverse > atr * config.max_entry_chase_atr_multiplier:
        return GateResult(False, "CHASE", f"entry price chased signal by {adverse / atr} ATR")
    if config.min_average_turnover > ZERO:
        values = [item.turnover for item in recent_candles[-config.liquidity_lookback:] if item.turnover is not None]
        average = sum(values, ZERO) / Decimal(len(values)) if values else ZERO
        if average < config.min_average_turnover:
            return GateResult(False, "DEPTH_LIQUIDITY", f"average turnover {average} below {config.min_average_turnover}")
    if funding is not None:
        side_cost = funding.rate if candidate.side is TrendSide.LONG else -funding.rate
        if side_cost > config.max_adverse_funding_rate:
            return GateResult(False, "COST", f"adverse funding {side_cost} above {config.max_adverse_funding_rate}")
    if config.require_execution_facts and facts.actual_taker_fee_rate is None:
        return GateResult(False, "COST", "actual account fee rate unavailable")
    planned_price = price_plan(candidate, atr=atr, execution_price=fill, fee_rate=facts.actual_taker_fee_rate, config=config)
    if planned_price.expected_net_r < config.min_net_r:
        return GateResult(False, "COST", f"expected net R {planned_price.expected_net_r} below {config.min_net_r}", planned_price)
    planned_size = size_plan(candidate, contract, planned_price, config=config)
    if planned_size.contracts <= 0:
        return GateResult(False, "MARGIN_RISK", planned_size.reason, planned_price, planned_size)
    if facts.executable_depth_contracts is not None and facts.executable_depth_contracts < Decimal(planned_size.contracts) * config.min_depth_multiple:
        return GateResult(False, "DEPTH_LIQUIDITY", "top book depth below planned-order multiplier", planned_price, planned_size)
    if config.require_execution_facts and facts.executable_depth_contracts is None:
        return GateResult(False, "DEPTH_LIQUIDITY", "executable multi-level depth unavailable", planned_price, planned_size)
    required_margin = (planned_size.notional / facts.leverage * config.margin_safety_ratio
                       if facts.leverage is not None and facts.leverage > ZERO else planned_size.notional)
    required_margin = max(required_margin, config.min_available_margin)
    if facts.available_margin_usdt is not None and required_margin > facts.available_margin_usdt:
        return GateResult(False, "MARGIN_RISK", "available margin below required entry margin", planned_price, planned_size)
    if facts.risk_capacity_usdt is not None and facts.reserved_notional_usdt + planned_size.notional > facts.risk_capacity_usdt:
        return GateResult(False, "MARGIN_RISK", "account risk reservation capacity exhausted", planned_price, planned_size)
    if config.require_execution_facts and (facts.available_margin_usdt is None or facts.leverage is None):
        return GateResult(False, "MARGIN_RISK", "margin or leverage fact unavailable", planned_price, planned_size)
    return GateResult(True, "READY", cooldown_result.override_reason or "all model, market, cost and risk gates passed", planned_price, planned_size)
