"""Candidate preflight, price planning, cooldown and risk sizing.

This module has no HTX mutation dependency.  A candidate can pass every gate and
still only produce a paper order until P6 explicitly enables a single-account
live executor.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from enum import StrEnum
from os import environ
from typing import Any, Callable, Sequence

from .market_data import Candle, ContractSpec, FundingRate, TopOfBook, period_seconds
from .models import ModelCandidate, ModelCode, TrendSide
from .strategy_context import (CandidateTrendState, EntryStateConfig, FibonacciQuality, MarketContext,
                               evaluate_counter_market)

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
    max_adverse_funding_rate: Decimal = Decimal("0.0006")
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
    # application.yml explicitly disables the Java cost gate by default.
    fee_protection_enabled: bool = False
    min_expected_move_atr_multiplier: Decimal = Decimal("1")
    min_net_profit_rate: Decimal = Decimal("0.0010")
    max_entry_chase_rate: Decimal = ZERO
    candidate_trend_state_gate_enabled: bool = True
    counter_market_strong_candidate_enabled: bool = False
    mixed_market_requires_strong_candidate: bool = True
    mixed_market_min_model_score: Decimal = Decimal("92")
    mixed_market_min_expected_r: Decimal = Decimal("1.75")
    mixed_market_min_momentum_score: Decimal = Decimal("70")
    # Java combination entries size directly from policy planned notional.  A
    # Python-only stop-risk cap would silently create a different order volume.
    java_policy_notional_sizing: bool = False

    @classmethod
    def from_env(cls) -> "ExecutionConfig":
        """Read the same HTX_TREND entry knobs as Java application.yml."""
        def dec(name: str, default: Decimal) -> Decimal:
            return Decimal(environ.get(name, str(default)))
        def integer(name: str, default: int) -> int:
            return int(environ.get(name, str(default)))
        def flag(name: str, default: bool) -> bool:
            return environ.get(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}
        return cls(
            max_spread_rate=dec("HTX_TREND_MAX_ENTRY_SPREAD_RATE", cls.max_spread_rate),
            min_average_turnover=dec("HTX_TREND_MIN_ENTRY_AVG_TURNOVER_USDT", cls.min_average_turnover),
            liquidity_lookback=integer("HTX_TREND_LIQUIDITY_LOOKBACK_BARS", cls.liquidity_lookback),
            max_adverse_funding_rate=dec("HTX_TREND_MAX_ENTRY_FUNDING_COST_RATE", cls.max_adverse_funding_rate),
            fee_rate=dec("HTX_TREND_ESTIMATED_TAKER_FEE_RATE", cls.fee_rate),
            target_notional=dec("HTX_TREND_TARGET_QUOTE_NOTIONAL", cls.target_notional),
            min_notional=dec("HTX_TREND_MIN_QUOTE_NOTIONAL", cls.min_notional),
            max_notional=dec("HTX_TREND_MAX_QUOTE_NOTIONAL", cls.max_notional),
            risk_per_trade=dec("HTX_TREND_RISK_PER_TRADE_USDT", cls.risk_per_trade),
            loss_cooldown_seconds=integer("HTX_TREND_LOSS_COOLDOWN_MS", cls.loss_cooldown_seconds * 1000) // 1000,
            breakeven_cooldown_seconds=integer("HTX_TREND_BREAKEVEN_COOLDOWN_MS", cls.breakeven_cooldown_seconds * 1000) // 1000,
            profit_cooldown_seconds=integer("HTX_TREND_PROFIT_COOLDOWN_MS", cls.profit_cooldown_seconds * 1000) // 1000,
            strong_reentry_seconds=integer("HTX_TREND_PROFIT_COOLDOWN_STRONG_REENTRY_AFTER_MS", cls.strong_reentry_seconds * 1000) // 1000,
            profit_threshold=dec("HTX_TREND_PROFIT_COOLDOWN_MIN_NET_PROFIT_USDT", cls.profit_threshold),
            signal_freshness_seconds=integer("HTX_TREND_MAX_ENTRY_SIGNAL_AGE_SECONDS", cls.signal_freshness_seconds),
            max_entry_chase_atr_multiplier=dec("HTX_TREND_MAX_ENTRY_SIGNAL_CHASE_ATR_MULTIPLIER", cls.max_entry_chase_atr_multiplier),
            max_entry_chase_rate=dec("HTX_TREND_MAX_ENTRY_SIGNAL_CHASE_RATE", cls.max_entry_chase_rate),
            min_depth_multiple=dec("HTX_TREND_MIN_ENTRY_DEPTH_MULTIPLIER", cls.min_depth_multiple),
            min_fib_volume_ratio=dec("HTX_TREND_FIBONACCI_MIN_VOLUME_RATIO", cls.min_fib_volume_ratio),
            min_fib_body_to_range=dec("HTX_TREND_FIBONACCI_MIN_BODY_TO_RANGE", cls.min_fib_body_to_range),
            max_fib_counter_wick_to_range=dec("HTX_TREND_FIBONACCI_MAX_COUNTER_WICK_TO_RANGE", cls.max_fib_counter_wick_to_range),
            max_fib_entry_range_atr=dec("HTX_TREND_FIBONACCI_MAX_ENTRY_RANGE_ATR", cls.max_fib_entry_range_atr),
            max_atr_pct=dec("HTX_TREND_MAX_ENTRY_ATR_PCT", cls.max_atr_pct),
            margin_safety_ratio=dec("HTX_CONTRACT_MARGIN_SAFETY_RATIO", cls.margin_safety_ratio),
            min_available_margin=dec("HTX_TREND_MIN_CONTRACT_MARGIN_AVAILABLE", cls.min_available_margin),
            fee_protection_enabled=flag("HTX_TREND_FEE_PROTECTION_ENABLED", cls.fee_protection_enabled),
            min_expected_move_atr_multiplier=dec("HTX_TREND_MIN_EXPECTED_MOVE_ATR_MULTIPLIER", cls.min_expected_move_atr_multiplier),
            min_net_profit_rate=dec("HTX_TREND_MIN_NET_PROFIT_RATE", cls.min_net_profit_rate),
            candidate_trend_state_gate_enabled=flag("HTX_TREND_CANDIDATE_TREND_STATE_GATE_ENABLED", cls.candidate_trend_state_gate_enabled),
            counter_market_strong_candidate_enabled=flag("HTX_TREND_COUNTER_MARKET_STRONG_CANDIDATE_ENABLED", cls.counter_market_strong_candidate_enabled),
            mixed_market_requires_strong_candidate=flag("HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE", cls.mixed_market_requires_strong_candidate),
            mixed_market_min_model_score=dec("HTX_TREND_MIXED_MARKET_MIN_MODEL_SCORE", cls.mixed_market_min_model_score),
            mixed_market_min_expected_r=dec("HTX_TREND_MIXED_MARKET_MIN_EXPECTED_R", cls.mixed_market_min_expected_r),
            mixed_market_min_momentum_score=dec("HTX_TREND_MIXED_MARKET_MIN_MOMENTUM_SCORE", cls.mixed_market_min_momentum_score),
        )


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
    # Java blocks a contract when *any* open order is present.  Keep the old
    # field name as a compatibility alias for callers created before parity.
    same_direction_open_order: bool = False
    contract_open_order: bool = False
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
             strong_profit_reentry: bool = False,
             config: ExecutionConfig = ExecutionConfig()) -> CooldownDecision:
    """Literal Java cooldown categories and its profit-only early re-entry."""
    if prior is None:
        return CooldownDecision(True, "no prior trade", 0)
    elapsed = max(0, now_epoch - prior.closed_at_epoch)
    if prior.net_profit < ZERO:
        duration, category = config.loss_cooldown_seconds, "loss"
    elif prior.net_profit >= config.profit_threshold:
        duration, category = config.profit_cooldown_seconds, "profit"
        if elapsed >= config.strong_reentry_seconds and strong_profit_reentry:
            return CooldownDecision(True, "profit cooldown overridden by strong breakout", 0,
                                    "Java profit cooldown strong re-entry")
    else:
        duration, category = config.breakeven_cooldown_seconds, "breakeven"
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
    by_target = int((config.target_notional / (price.desired_entry * contract.contract_size)).to_integral_value(rounding=ROUND_DOWN))
    by_max = int((config.max_notional / (price.desired_entry * contract.contract_size)).to_integral_value(rounding=ROUND_DOWN))
    by_risk = int((config.risk_per_trade / risk_per_contract).to_integral_value(rounding=ROUND_DOWN))
    contracts = max(0, min(by_target, by_max) if config.java_policy_notional_sizing else min(by_risk, by_target, by_max))
    notional = price.desired_entry * contract.contract_size * contracts
    if contracts == 0 or notional < config.min_notional:
        return SizePlan(0, notional, risk_per_contract * contracts, "sizing below minimum notional")
    reason = "Java policy notional sized" if config.java_policy_notional_sizing else "risk and notional capped"
    return SizePlan(contracts, notional, risk_per_contract * contracts, reason)


def evaluate_gate(candidate: ModelCandidate, *, period: str, now_epoch: int, atr: Decimal, order_book: TopOfBook,
                  contract: ContractSpec, funding: FundingRate | None, recent_candles: Sequence[Candle],
                  prior_trade: PriorTrade | None, market_context: MarketContext | None = None,
                  candidate_state: CandidateTrendState | None = None, fibonacci_quality: FibonacciQuality | None = None,
                  execution_facts: ExecutionFacts | None = None, claimed_structure_keys: frozenset[str] = frozenset(),
                  momentum_quality: Decimal | None = None, ema_fast: Decimal | None = None,
                  ema_slow: Decimal | None = None, adx: Decimal | None = None,
                  strong_profit_reentry: bool = False,
                  condition_trace: Callable[[str, str, Any, Any, str], None] | None = None,
                  config: ExecutionConfig = ExecutionConfig()) -> GateResult:
    """Execute Java's combination-entry Gate in its production order.

    MODE, enabled policy lookup and candidate selection are deliberately owned
    by the runtime/executor before this function. This contract begins at
    ``fundingCostGate`` exactly as Java ``openIfEligible`` does.
    """
    context = market_context or MarketContext.neutral()
    facts = execution_facts or ExecutionFacts()
    quality_score = momentum_quality if momentum_quality is not None else candidate.score
    fast = ema_fast if ema_fast is not None else candidate.entry_price
    slow = ema_slow if ema_slow is not None else candidate.entry_price
    current_adx = adx if adx is not None else ZERO
    confirmations = candidate.policy_confirmations or ""
    state_config = EntryStateConfig(counter_enabled=config.counter_market_strong_candidate_enabled)
    counter = evaluate_counter_market(side=candidate.side, context=context, model_score=candidate.score,
                                      expected_r=candidate.expected_r, momentum_score=quality_score,
                                      adx=current_adx, ema_fast=fast, ema_slow=slow,
                                      confirmations=confirmations, config=state_config)
    state = candidate_state or CandidateTrendState.from_candidate(candidate.score, candidate.expected_r, context, candidate.side)
    def emit(condition_id: str, passed: bool, actual: Any = None, threshold: Any = None, reason: str = "",
             status: str | None = None) -> None:
        if condition_trace is not None:
            condition_trace(condition_id, status or ("PASS" if passed else "FAIL"), actual, threshold, reason)

    # Historical paper tests retain their deliberately relaxed contract. Live
    # execution always sets require_execution_facts=True and follows Java below.
    if not config.require_execution_facts and candidate.score < config.min_score:
        emit("gate.model_score", False, candidate.score, config.min_score, "below minimum score")
        return GateResult(False, "MODEL_POLICY", f"candidate score {candidate.score} below {config.min_score}")
    emit("gate.model_score", True, candidate.score, config.min_score)
    # Java requests funding before checking freshness.
    if funding is None:
        emit("gate.funding_available", False, None, "required", "funding unavailable")
        return GateResult(False, "FUNDING", "funding unavailable; skip directional entry")
    emit("gate.funding_available", True, funding.rate, "required")
    side_cost = funding.rate if candidate.side is TrendSide.LONG else -funding.rate
    if side_cost > config.max_adverse_funding_rate:
        emit("gate.funding_cost", False, side_cost, config.max_adverse_funding_rate, "adverse funding")
        return GateResult(False, "FUNDING", f"funding cost blocked side={candidate.side.value} funding={funding.rate}")
    emit("gate.funding_cost", True, side_cost, config.max_adverse_funding_rate)
    close_epoch = candidate.bar_time + period_seconds(period)
    age = now_epoch - close_epoch
    if age < 0:
        emit("data.signal_freshness", False, age, {"minimum": 0}, "bar not closed")
        return GateResult(False, "DATA_FRESHNESS", f"entry signal bar not closed ageSeconds={age}")
    if config.signal_freshness_seconds > 0 and age > config.signal_freshness_seconds:
        emit("data.signal_freshness", False, age, config.signal_freshness_seconds, "signal stale")
        return GateResult(False, "DATA_FRESHNESS", f"entry signal too stale ageSeconds={age} max={config.signal_freshness_seconds}")
    emit("data.signal_freshness", True, age, config.signal_freshness_seconds)
    cooldown_result = cooldown(candidate, prior_trade, now_epoch=now_epoch,
                               strong_profit_reentry=strong_profit_reentry, config=config)
    if not cooldown_result.allowed:
        emit("gate.cooldown", False, cooldown_result.remaining_seconds, 0, cooldown_result.reason)
        return GateResult(False, "COOLDOWN", f"{cooldown_result.reason}; remaining={cooldown_result.remaining_seconds}s")
    emit("gate.cooldown", True, cooldown_result.remaining_seconds, 0, cooldown_result.override_reason or cooldown_result.reason)
    if config.require_execution_facts and counter.blocks_entry:
        emit("gate.counter_market", False, counter.reason, "not blocked")
        return GateResult(False, "COUNTER_MARKET", f"counter-market candidate rejected: {counter.reason}")
    emit("gate.counter_market", True, counter.reason, "not blocked")
    if config.require_execution_facts and config.candidate_trend_state_gate_enabled and not state.tradable:
        emit("gate.candidate_state", False, state.reason, "tradable")
        return GateResult(False, "CANDIDATE_STATE", f"candidate trend state not tradable; {state.reason}")
    emit("gate.candidate_state", True, state.reason, "tradable")
    if (config.require_execution_facts and config.mixed_market_requires_strong_candidate and context.regime.value == "MIXED"
            and not (candidate.score >= config.mixed_market_min_model_score
                     and candidate.expected_r >= config.mixed_market_min_expected_r
                     and quality_score >= config.mixed_market_min_momentum_score)):
        emit("gate.mixed_market_quality", False,
             {"score": candidate.score, "expected_r": candidate.expected_r, "momentum": quality_score},
             {"score": config.mixed_market_min_model_score, "expected_r": config.mixed_market_min_expected_r,
              "momentum": config.mixed_market_min_momentum_score})
        return GateResult(False, "MIXED_MARKET", "mixed market requires A-grade candidate")
    emit("gate.mixed_market_quality", True, context.regime.value, "A-grade when MIXED")
    if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION and config.require_execution_facts:
        quality = fibonacci_quality
        if quality is None:
            emit("gate.fib_quality", False, None, "complete", "evidence unavailable")
            return GateResult(False, "FIB_QUALITY", "Fibonacci execution quality evidence unavailable")
        if not quality.ema_reclaimed:
            emit("gate.fib_quality", False, {"ema_reclaimed": False}, {"ema_reclaimed": True})
            return GateResult(False, "FIB_QUALITY", "Fibonacci retest has not reclaimed EMA structure")
        if quality.volume_ratio is None or quality.volume_ratio < config.min_fib_volume_ratio:
            emit("gate.fib_quality", False, quality.volume_ratio, config.min_fib_volume_ratio, "volume ratio")
            return GateResult(False, "FIB_QUALITY", f"Fibonacci relative volume below threshold ratio={quality.volume_ratio}")
        if quality.body_to_range is None or quality.body_to_range < config.min_fib_body_to_range:
            emit("gate.fib_quality", False, quality.body_to_range, config.min_fib_body_to_range, "body/range")
            return GateResult(False, "FIB_QUALITY", "Fibonacci retest wick-only body")
        if quality.range_to_atr is None or quality.range_to_atr > config.max_fib_entry_range_atr:
            emit("gate.fib_quality", False, quality.range_to_atr, config.max_fib_entry_range_atr, "range/ATR")
            return GateResult(False, "FIB_QUALITY", "Fibonacci retest one-bar spike")
        if quality.counter_wick_to_range is None or quality.counter_wick_to_range > config.max_fib_counter_wick_to_range:
            emit("gate.fib_quality", False, quality.counter_wick_to_range,
                 config.max_fib_counter_wick_to_range, "counter wick/range")
            return GateResult(False, "FIB_QUALITY", "Fibonacci retest counter wick too large")
    if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION:
        emit("gate.fib_quality", True, "complete", "complete")
    else:
        emit("gate.fib_quality", True, candidate.model.value, "required for Fibonacci only",
             "not a Fibonacci candidate", status="SKIPPED")
    if candidate.structure_key and candidate.structure_key in claimed_structure_keys:
        emit("gate.unique_structure", False, candidate.structure_key, "unclaimed")
        return GateResult(False, "STRUCTURE", "identical model structure already claimed")
    emit("gate.unique_structure", True, candidate.structure_key, "unclaimed")
    if order_book.spread_rate > config.max_spread_rate:
        emit("gate.book_spread", False, order_book.spread_rate, config.max_spread_rate)
        return GateResult(False, "BOOK_SPREAD", f"spread {order_book.spread_rate} above {config.max_spread_rate}")
    emit("gate.book_spread", True, order_book.spread_rate, config.max_spread_rate)
    if atr > ZERO and atr / candidate.entry_price > config.max_atr_pct:
        emit("gate.atr_pct", False, atr / candidate.entry_price, config.max_atr_pct)
        return GateResult(False, "LIQUIDITY", f"ATR percentage {atr / candidate.entry_price} above {config.max_atr_pct}")
    emit("gate.atr_pct", True, atr / candidate.entry_price if candidate.entry_price else None, config.max_atr_pct)
    if facts.same_direction_open_order or facts.contract_open_order:
        emit("gate.open_orders", False, True, False)
        return GateResult(False, "ORDER_POSITION", "contract has open orders; wait")
    emit("gate.open_orders", True, False, False)
    if facts.external_position_side is not None:
        emit("gate.external_position", False, facts.external_position_side.value, None)
        return GateResult(False, "ORDER_POSITION", f"external {facts.external_position_side.value} position needs manual reconciliation")
    emit("gate.external_position", True, None, None)
    if facts.active_position_count is not None and facts.max_active_positions is not None and facts.active_position_count >= facts.max_active_positions:
        emit("gate.position_capacity", False, facts.active_position_count, facts.max_active_positions)
        return GateResult(False, "POSITION_CAPACITY", "max active trend positions reached")
    emit("gate.position_capacity", True, facts.active_position_count, facts.max_active_positions)
    fill = order_book.ask if candidate.side is TrendSide.LONG else order_book.bid
    adverse = fill - candidate.entry_price if candidate.side is TrendSide.LONG else candidate.entry_price - fill
    if adverse > ZERO:
        max_chase = atr * config.max_entry_chase_atr_multiplier if atr > ZERO and config.max_entry_chase_atr_multiplier > ZERO else ZERO
        if config.max_entry_chase_rate > ZERO:
            rate_limit = candidate.entry_price * config.max_entry_chase_rate
            max_chase = min(max_chase, rate_limit) if max_chase > ZERO else rate_limit
        if max_chase > ZERO and adverse > max_chase:
            emit("gate.entry_chase", False, adverse, max_chase)
            return GateResult(False, "CHASE", f"entry price chased signal too far entryPrice={fill} signalClose={candidate.entry_price}")
    emit("gate.entry_chase", True, adverse, max_chase if adverse > ZERO else ZERO)
    planned_price = price_plan(candidate, atr=atr, execution_price=fill,
                               fee_rate=facts.actual_taker_fee_rate or config.fee_rate, config=config)
    planned_size = size_plan(candidate, contract, planned_price, config=config)
    if planned_size.contracts <= 0:
        emit("sizing.valid_contracts", False, planned_size.contracts, ">0", planned_size.reason)
        return GateResult(False, "SIZING", planned_size.reason, planned_price, planned_size)
    emit("sizing.valid_contracts", True, planned_size.contracts, ">0", planned_size.reason)
    if config.min_average_turnover > ZERO:
        values = [item.turnover for item in recent_candles[-config.liquidity_lookback:] if item.turnover is not None]
        average = sum(values, ZERO) / Decimal(len(values)) if values else None
        if average is None:
            emit("gate.average_turnover", False, None, config.min_average_turnover, "unavailable")
            return GateResult(False, "LIQUIDITY", "liquidity turnover unavailable", planned_price, planned_size)
        if average < config.min_average_turnover:
            emit("gate.average_turnover", False, average, config.min_average_turnover)
            return GateResult(False, "LIQUIDITY", f"liquidity turnover too low avgTurnover={average}", planned_price, planned_size)
        emit("gate.average_turnover", True, average, config.min_average_turnover)
    if config.require_execution_facts and facts.executable_depth_contracts is None:
        emit("gate.executable_depth", False, None, "available")
        return GateResult(False, "LIQUIDITY", "executable multi-level depth unavailable", planned_price, planned_size)
    if config.require_execution_facts and facts.executable_depth_contracts < Decimal(planned_size.contracts) * config.min_depth_multiple:
        emit("gate.executable_depth", False, facts.executable_depth_contracts,
             Decimal(planned_size.contracts) * config.min_depth_multiple)
        return GateResult(False, "LIQUIDITY", "top book depth below planned-order multiplier", planned_price, planned_size)
    emit("gate.executable_depth", True, facts.executable_depth_contracts,
         Decimal(planned_size.contracts) * config.min_depth_multiple if config.require_execution_facts else None)
    fee_rate = facts.actual_taker_fee_rate if facts.actual_taker_fee_rate is not None else config.fee_rate
    if config.fee_protection_enabled:
        expected_move = atr * config.min_expected_move_atr_multiplier
        required_move = fill * (order_book.spread_rate + fee_rate * Decimal("2") + config.min_net_profit_rate)
        if expected_move < required_move:
            emit("gate.fee_protection", False, expected_move, required_move)
            return GateResult(False, "COST", f"expected move too small after fees expectedMove={expected_move} requiredMove={required_move}", planned_price, planned_size)
        emit("gate.fee_protection", True, expected_move, required_move)
    if config.require_execution_facts:
        required_margin = (planned_size.notional / facts.leverage * config.margin_safety_ratio
                           if facts.leverage is not None and facts.leverage > ZERO else None)
        if required_margin is None or facts.available_margin_usdt is None:
            emit("gate.margin", False, facts.available_margin_usdt, required_margin, "unavailable")
            return GateResult(False, "MARGIN", "contract margin unavailable", planned_price, planned_size)
        if required_margin < config.min_available_margin:
            required_margin = config.min_available_margin
        if facts.available_margin_usdt < required_margin:
            emit("gate.margin", False, facts.available_margin_usdt, required_margin, "insufficient")
            return GateResult(False, "MARGIN", "contract margin insufficient", planned_price, planned_size)
        emit("gate.margin", True, facts.available_margin_usdt, required_margin)
    return GateResult(True, "READY", cooldown_result.override_reason or "all Java entry gates passed", planned_price, planned_size)
