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
                               evaluate_counter_market, evaluate_candidate_state)

ZERO = Decimal("0")


class ExecutionStyle(StrEnum):
    PASSIVE_LIMIT = "PASSIVE_LIMIT"
    IOC_LIMIT = "IOC_LIMIT"
    IOC_OR_MARKET = "IOC_OR_MARKET"
    CONDITIONAL_OR_MARKET = "CONDITIONAL_OR_MARKET"


@dataclass(frozen=True, slots=True)
class ExecutionConfig:
    min_score: Decimal = Decimal("70")
    max_spread_rate: Decimal = Decimal("0.0010")
    min_average_turnover: Decimal = ZERO
    liquidity_lookback: int = 20
    max_adverse_funding_rate: Decimal = Decimal("0.0006")
    # Retained legacy field; no calibrated net-R threshold is enabled.
    min_net_r: Decimal = Decimal("0.25")
    entry_plan_version: str = "legacy"
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
    # Hard account guardrails for an approved LIVE worker. These remain
    # separate from Java-compatible policy sizing: policy sizing may rank a
    # candidate, but it must never raise the per-trade or position-count
    # boundaries.
    hard_risk_limits_enabled: bool = False
    account_equity_usdt: Decimal = Decimal("300")
    # Configured capital, margin basis: 300U * 10% = 30U (300U notional at 10x).
    max_total_margin_rate: Decimal = Decimal("0.10")
    max_risk_per_trade_usdt: Decimal = Decimal("6")
    max_risk_per_trade_rate: Decimal = Decimal("0.005")
    max_total_open_risk_rate: Decimal = Decimal("0.015")
    max_active_positions: int = 3
    max_same_direction_positions: int = 3

    def __post_init__(self):
        if self.entry_plan_version not in ("legacy", "structure-cost-v1"):
            raise ValueError("unknown entry plan version")

    @classmethod
    def from_env(cls) -> "ExecutionConfig":
        """Read the same HTX_TREND entry knobs as Java application.yml."""
        defaults = cls()

        def dec(name: str, default: Decimal) -> Decimal:
            return Decimal(environ.get(name, str(default)))

        def integer(name: str, default: int) -> int:
            return int(environ.get(name, str(default)))

        def flag(name: str, default: bool) -> bool:
            return environ.get(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}

        return cls(
            entry_plan_version=environ.get("HTX_TREND_ENTRY_PLAN_VERSION", defaults.entry_plan_version),
            max_spread_rate=dec("HTX_TREND_MAX_ENTRY_SPREAD_RATE", defaults.max_spread_rate),
            min_average_turnover=dec("HTX_TREND_MIN_ENTRY_AVG_TURNOVER_USDT", defaults.min_average_turnover),
            liquidity_lookback=integer("HTX_TREND_LIQUIDITY_LOOKBACK_BARS", defaults.liquidity_lookback),
            max_adverse_funding_rate=dec("HTX_TREND_MAX_ENTRY_FUNDING_COST_RATE", defaults.max_adverse_funding_rate),
            fee_rate=dec("HTX_TREND_ESTIMATED_TAKER_FEE_RATE", defaults.fee_rate),
            target_notional=dec("HTX_TREND_TARGET_QUOTE_NOTIONAL", defaults.target_notional),
            min_notional=dec("HTX_TREND_MIN_QUOTE_NOTIONAL", defaults.min_notional),
            max_notional=dec("HTX_TREND_MAX_QUOTE_NOTIONAL", defaults.max_notional),
            risk_per_trade=dec("HTX_TREND_RISK_PER_TRADE_USDT", defaults.risk_per_trade),
            loss_cooldown_seconds=integer("HTX_TREND_LOSS_COOLDOWN_MS", defaults.loss_cooldown_seconds * 1000) // 1000,
            breakeven_cooldown_seconds=integer("HTX_TREND_BREAKEVEN_COOLDOWN_MS", defaults.breakeven_cooldown_seconds * 1000) // 1000,
            profit_cooldown_seconds=integer("HTX_TREND_PROFIT_COOLDOWN_MS", defaults.profit_cooldown_seconds * 1000) // 1000,
            strong_reentry_seconds=integer("HTX_TREND_PROFIT_COOLDOWN_STRONG_REENTRY_AFTER_MS", defaults.strong_reentry_seconds * 1000) // 1000,
            profit_threshold=dec("HTX_TREND_PROFIT_COOLDOWN_MIN_NET_PROFIT_USDT", defaults.profit_threshold),
            signal_freshness_seconds=integer("HTX_TREND_MAX_ENTRY_SIGNAL_AGE_SECONDS", defaults.signal_freshness_seconds),
            max_entry_chase_atr_multiplier=dec("HTX_TREND_MAX_ENTRY_SIGNAL_CHASE_ATR_MULTIPLIER", defaults.max_entry_chase_atr_multiplier),
            max_entry_chase_rate=dec("HTX_TREND_MAX_ENTRY_SIGNAL_CHASE_RATE", defaults.max_entry_chase_rate),
            min_depth_multiple=dec("HTX_TREND_MIN_ENTRY_DEPTH_MULTIPLIER", defaults.min_depth_multiple),
            min_fib_volume_ratio=dec("HTX_TREND_FIBONACCI_MIN_VOLUME_RATIO", defaults.min_fib_volume_ratio),
            min_fib_body_to_range=dec("HTX_TREND_FIBONACCI_MIN_BODY_TO_RANGE", defaults.min_fib_body_to_range),
            max_fib_counter_wick_to_range=dec("HTX_TREND_FIBONACCI_MAX_COUNTER_WICK_TO_RANGE", defaults.max_fib_counter_wick_to_range),
            max_fib_entry_range_atr=dec("HTX_TREND_FIBONACCI_MAX_ENTRY_RANGE_ATR", defaults.max_fib_entry_range_atr),
            max_atr_pct=dec("HTX_TREND_MAX_ENTRY_ATR_PCT", defaults.max_atr_pct),
            margin_safety_ratio=dec("HTX_CONTRACT_MARGIN_SAFETY_RATIO", defaults.margin_safety_ratio),
            min_available_margin=dec("HTX_TREND_MIN_CONTRACT_MARGIN_AVAILABLE", defaults.min_available_margin),
            fee_protection_enabled=flag("HTX_TREND_FEE_PROTECTION_ENABLED", defaults.fee_protection_enabled),
            min_expected_move_atr_multiplier=dec("HTX_TREND_MIN_EXPECTED_MOVE_ATR_MULTIPLIER", defaults.min_expected_move_atr_multiplier),
            min_net_profit_rate=dec("HTX_TREND_MIN_NET_PROFIT_RATE", defaults.min_net_profit_rate),
            candidate_trend_state_gate_enabled=flag("HTX_TREND_CANDIDATE_TREND_STATE_GATE_ENABLED", defaults.candidate_trend_state_gate_enabled),
            counter_market_strong_candidate_enabled=flag("HTX_TREND_COUNTER_MARKET_STRONG_CANDIDATE_ENABLED", defaults.counter_market_strong_candidate_enabled),
            mixed_market_requires_strong_candidate=flag("HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE", defaults.mixed_market_requires_strong_candidate),
            mixed_market_min_model_score=dec("HTX_TREND_MIXED_MARKET_MIN_MODEL_SCORE", defaults.mixed_market_min_model_score),
            mixed_market_min_expected_r=dec("HTX_TREND_MIXED_MARKET_MIN_EXPECTED_R", defaults.mixed_market_min_expected_r),
            mixed_market_min_momentum_score=dec("HTX_TREND_MIXED_MARKET_MIN_MOMENTUM_SCORE", defaults.mixed_market_min_momentum_score),
            hard_risk_limits_enabled=flag("HTX_TREND_HARD_RISK_LIMITS_ENABLED", defaults.hard_risk_limits_enabled),
            account_equity_usdt=dec("HTX_TREND_ACCOUNT_EQUITY_USDT", defaults.account_equity_usdt),
            max_risk_per_trade_usdt=dec("HTX_TREND_MAX_RISK_PER_TRADE_USDT", defaults.max_risk_per_trade_usdt),
            max_risk_per_trade_rate=dec("HTX_TREND_MAX_RISK_PER_TRADE_RATE", defaults.max_risk_per_trade_rate),
            max_total_open_risk_rate=dec("HTX_TREND_MAX_TOTAL_OPEN_RISK_RATE", defaults.max_total_open_risk_rate),
            max_active_positions=integer("HTX_TREND_MAX_ACTIVE_POSITIONS", defaults.max_active_positions),
            max_same_direction_positions=integer("HTX_TREND_MAX_SAME_DIRECTION_POSITIONS", defaults.max_same_direction_positions),
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
    version: str = "legacy"
    structure_target: Decimal | None = None
    expected_net_reward: Decimal | None = None
    net_risk: Decimal | None = None
    entry_fee: Decimal | None = None
    target_exit_fee: Decimal | None = None
    stop_exit_fee: Decimal | None = None
    entry_slippage: Decimal | None = None
    exit_slippage: Decimal | None = None
    funding_cost: Decimal | None = None  # Future holding duration/rates are unknown.


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
    account_equity_usdt: Decimal | None = None
    leverage: Decimal | None = None
    active_position_count: int | None = None
    max_active_positions: int | None = None
    same_direction_position_count: int | None = None
    reserved_notional_usdt: Decimal = ZERO
    reserved_risk_usdt: Decimal = ZERO
    reserved_margin_usdt: Decimal | None = None
    used_margin_usdt: Decimal | None = None
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


def structural_target(candidate: ModelCandidate) -> Decimal:
    """Nearest declared structural boundary; do not search farther to inflate R."""
    return candidate.target_low if candidate.side is TrendSide.LONG else candidate.target_high


def structured_price_plan(candidate, *, atr, execution_price, fee_rate, entry_fee_rate, passive, config, entry_limit=None):
    desired = candidate.entry_price if execution_price is None else execution_price
    close_rate = config.fee_rate if fee_rate is None else fee_rate
    open_rate = close_rate if entry_fee_rate is None else entry_fee_rate
    values = (desired, candidate.invalidation_price, candidate.target_low, candidate.target_high)
    if not all(v.is_finite() and v > ZERO for v in values):
        raise ValueError("non-positive or non-finite structure prices")
    if candidate.side not in (TrendSide.LONG, TrendSide.SHORT) or candidate.target_low > candidate.target_high:
        raise ValueError("invalid direction or target range")
    if not all(v.is_finite() and v >= ZERO for v in (atr, config.slippage_atr_fraction, close_rate, open_rate)):
        raise ValueError("invalid volatility or fee inputs")
    sign = Decimal(1) if candidate.side is TrendSide.LONG else Decimal(-1)
    slip = atr * config.slippage_atr_fraction
    entry_slip = ZERO if passive else slip
    worst = desired + sign * entry_slip if entry_limit is None else entry_limit
    if entry_limit is not None:
        if not worst.is_finite() or worst <= ZERO or sign*(worst-desired) < ZERO:
            raise ValueError("invalid IOC price cap")
        entry_slip = abs(worst-desired)
    target = structural_target(candidate)
    stop = candidate.invalidation_price
    if sign * (target - worst) <= ZERO or sign * (worst - stop) <= ZERO:
        raise ValueError("target or invalidation is on wrong side of planned entry")
    target_fill, stop_fill = target - sign * slip, stop - sign * slip
    if min(worst, target_fill, stop_fill) <= ZERO:
        raise ValueError("slippage creates invalid execution price")
    entry_fee, target_fee, stop_fee = worst * open_rate, target_fill * close_rate, stop_fill * close_rate
    reward = sign * (target_fill - worst) - entry_fee - target_fee
    risk = sign * (worst - stop_fill) + entry_fee + stop_fee
    return PricePlan(ExecutionStyle.PASSIVE_LIMIT if passive else ExecutionStyle.IOC_OR_MARKET,
        desired, worst, stop, candidate.target_low, candidate.target_high, reward / risk,
        "structure-cost-v1", target, reward, risk, entry_fee, target_fee, stop_fee,
        entry_slip, slip)


def price_plan(candidate: ModelCandidate, *, atr: Decimal, execution_price: Decimal | None = None, fee_rate: Decimal | None = None,
               config: ExecutionConfig = ExecutionConfig(), entry_fee_rate: Decimal | None = None,
               passive: bool = False) -> PricePlan:
    # Java's current production trend path uses a V5 market order for every
    # model.  Passive Fibonacci probing is an experiment and must be selected
    # explicitly rather than silently changing parity.
    if config.entry_plan_version == "structure-cost-v1":
        return structured_price_plan(candidate, atr=atr, execution_price=execution_price,
            fee_rate=fee_rate, entry_fee_rate=entry_fee_rate, passive=passive, config=config)
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
    risk_per_contract = (price.net_risk if price.net_risk is not None else stop_distance) * contract.contract_size
    by_target = int((config.target_notional / (price.desired_entry * contract.contract_size)).to_integral_value(rounding=ROUND_DOWN))
    by_max = int((config.max_notional / (price.desired_entry * contract.contract_size)).to_integral_value(rounding=ROUND_DOWN))
    risk_budget = (min(config.max_risk_per_trade_usdt,
                       config.account_equity_usdt * config.max_risk_per_trade_rate)
                   if config.hard_risk_limits_enabled else config.risk_per_trade)
    by_risk = int((risk_budget / risk_per_contract).to_integral_value(rounding=ROUND_DOWN))
    contracts = max(0, (min(by_target, by_max, by_risk) if config.hard_risk_limits_enabled
                        else min(by_target, by_max) if config.java_policy_notional_sizing
                        else min(by_risk, by_target, by_max)))
    notional = price.desired_entry * contract.contract_size * contracts
    if contracts == 0 or notional < config.min_notional:
        return SizePlan(0, notional, risk_per_contract * contracts, "sizing below minimum notional")
    reason = ("hard account risk and notional capped" if config.hard_risk_limits_enabled
              else "risk and notional capped")
    return SizePlan(contracts, notional, risk_per_contract * contracts, reason)


def evaluate_gate(candidate: ModelCandidate, *, period: str, now_epoch: int, atr: Decimal, order_book: TopOfBook,
                  contract: ContractSpec, funding: FundingRate | None, recent_candles: Sequence[Candle],
                  prior_trade: PriorTrade | None, market_context: MarketContext | None = None,
                  candidate_state: CandidateTrendState | None = None, fibonacci_quality: FibonacciQuality | None = None,
                  execution_facts: ExecutionFacts | None = None, claimed_structure_keys: frozenset[str] = frozenset(),
                  momentum_quality: Decimal | None = None, ema_fast: Decimal | None = None,
                  ema_slow: Decimal | None = None, adx: Decimal | None = None,
                  strong_profit_reentry: bool = False, entry_limit_price: Decimal | None = None,
                  entry_fee_rate: Decimal | None = None,
                  condition_trace: Callable[[str, str, Any, Any, str], None] | None = None,
                  config: ExecutionConfig = ExecutionConfig()) -> GateResult:
    """Execute Java's combination-entry Gate in its production order.

    MODE, enabled policy lookup and candidate selection are deliberately owned
    by the runtime/executor before this function. This contract begins at
    ``fundingCostGate`` exactly as Java ``openIfEligible`` does.
    """
    context = market_context or MarketContext.neutral()
    facts = execution_facts or ExecutionFacts()
    shared_quality = config.require_execution_facts or config.entry_plan_version == "structure-cost-v1"
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
    if config.entry_plan_version == "structure-cost-v1" and any(v is None for v in (momentum_quality, ema_fast, ema_slow, adx)):
        return GateResult(False, "CANDIDATE_INPUTS", "actual indicator/quality inputs required")
    state = candidate_state or (evaluate_candidate_state(candidate.score, candidate.expected_r, quality_score,
        candidate.side, fast, slow, current_adx, context, counter_market_qualified=counter.qualified)
        if config.entry_plan_version == "structure-cost-v1" else
        CandidateTrendState.from_candidate(candidate.score, candidate.expected_r, context, candidate.side))
    def emit(condition_id: str, passed: bool, actual: Any = None, threshold: Any = None, reason: str = "",
             status: str | None = None) -> None:
        if condition_trace is not None:
            condition_trace(condition_id, status or ("PASS" if passed else "FAIL"), actual, threshold, reason)

    # Historical paper tests retain their deliberately relaxed contract. Live
    # execution always sets require_execution_facts=True and follows Java below.
    if not shared_quality and candidate.score < config.min_score:
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
        emit("gate.funding_rate", False, side_cost, config.max_adverse_funding_rate, "adverse funding")
        return GateResult(False, "FUNDING", f"current funding rate blocked side={candidate.side.value} funding={funding.rate}")
    emit("gate.funding_rate", True, side_cost, config.max_adverse_funding_rate)
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
    if shared_quality and counter.blocks_entry:
        emit("gate.counter_market", False, counter.reason, "not blocked")
        return GateResult(False, "COUNTER_MARKET", f"counter-market candidate rejected: {counter.reason}")
    emit("gate.counter_market", True, counter.reason, "not blocked")
    if shared_quality and config.candidate_trend_state_gate_enabled and not state.tradable:
        emit("gate.candidate_state", False, state.reason, "tradable")
        return GateResult(False, "CANDIDATE_STATE", f"candidate trend state not tradable; {state.reason}")
    emit("gate.candidate_state", True, state.reason, "tradable")
    if (shared_quality and config.mixed_market_requires_strong_candidate and context.regime.value == "MIXED"
            and not (candidate.score >= config.mixed_market_min_model_score
                     and candidate.expected_r >= config.mixed_market_min_expected_r
                     and quality_score >= config.mixed_market_min_momentum_score)):
        emit("gate.mixed_market_quality", False,
             {"score": candidate.score, "expected_r": candidate.expected_r, "momentum": quality_score},
             {"score": config.mixed_market_min_model_score, "expected_r": config.mixed_market_min_expected_r,
              "momentum": config.mixed_market_min_momentum_score})
        return GateResult(False, "MIXED_MARKET", "mixed market requires A-grade candidate")
    emit("gate.mixed_market_quality", True, context.regime.value, "A-grade when MIXED")
    if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION and shared_quality:
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
    if config.entry_plan_version == "structure-cost-v1" and entry_limit_price is not None:
        if (not entry_limit_price.is_finite() or entry_limit_price <= ZERO
                or contract.price_tick <= ZERO or entry_limit_price % contract.price_tick != ZERO
                or (entry_limit_price >= order_book.ask if candidate.side is TrendSide.LONG else entry_limit_price <= order_book.bid)):
            return GateResult(False, "PRICE_PLAN", "invalid passive limit/tick or crossing quote")
        fill = entry_limit_price
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
    try:
        planned_price = price_plan(candidate, atr=atr, execution_price=fill,
            fee_rate=facts.actual_taker_fee_rate if facts.actual_taker_fee_rate is not None else config.fee_rate,
            entry_fee_rate=entry_fee_rate, passive=entry_limit_price is not None, config=config)
    except ValueError as error:
        emit("gate.price_geometry", False, None, "valid structure", str(error))
        return GateResult(False, "PRICE_PLAN", str(error))
    if config.entry_plan_version == "structure-cost-v1":
        if planned_price.expected_net_reward <= ZERO:
            emit("gate.net_reward", False, planned_price.expected_net_reward, ">0")
            return GateResult(False, "COST", "structural reward cannot cover estimated costs", planned_price)
        emit("gate.net_reward", True, planned_price.expected_net_reward, ">0")
        emit("gate.net_r", True, planned_price.expected_net_r, None,
             "threshold not calibrated; recorded only", status="SKIPPED")
    planned_size = size_plan(candidate, contract, planned_price, config=config)
    if planned_size.contracts <= 0:
        emit("sizing.valid_contracts", False, planned_size.contracts, ">0", planned_size.reason)
        return GateResult(False, "SIZING", planned_size.reason, planned_price, planned_size)
    emit("sizing.valid_contracts", True, planned_size.contracts, ">0", planned_size.reason)
    account_gate = account_risk_gate(planned_price, planned_size, facts, config=config, emit=emit)
    if account_gate is not None:
        return account_gate
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


def account_risk_gate(planned_price, planned_size, facts, *, config, emit=None):
    """Shared unchanged account risk block for closed-bar and realtime entry."""
    if emit is None:
        emit = lambda *args, **kwargs: None
    if config.hard_risk_limits_enabled:
        from .live_approval import validate_margin_config, projected_margin
        try:
            cap = validate_margin_config(config)
            margin = projected_margin(facts.used_margin_usdt, facts.reserved_margin_usdt,
                                      planned_size.notional, facts.leverage)
            if margin > cap:
                raise ValueError("total margin exposure exceeds 10% capital")
        except ValueError as error:
            emit("risk.total_margin", False, None, "margin <= 10% configured capital", str(error))
            return GateResult(False, "RISK_LIMIT", str(error), planned_price, planned_size)
        emit("risk.total_margin", True, margin, cap)
        account_equity = facts.account_equity_usdt
        if account_equity is None or account_equity <= ZERO:
            emit("risk.account_equity", False, account_equity, ">0")
            return GateResult(False, "RISK_LIMIT", "account equity must be positive for LIVE risk limits",
                              planned_price, planned_size)
        max_trade_risk = min(config.max_risk_per_trade_usdt,
                             account_equity * config.max_risk_per_trade_rate)
        if planned_size.risk_at_stop > max_trade_risk:
            emit("risk.max_per_trade", False, planned_size.risk_at_stop, max_trade_risk)
            return GateResult(False, "RISK_LIMIT", "planned stop risk exceeds per-trade account limit",
                              planned_price, planned_size)
        emit("risk.max_per_trade", True, planned_size.risk_at_stop, max_trade_risk)
        max_total_risk = account_equity * config.max_total_open_risk_rate
        projected_total_risk = facts.reserved_risk_usdt + planned_size.risk_at_stop
        if projected_total_risk > max_total_risk:
            emit("risk.max_total_open", False, projected_total_risk, max_total_risk)
            return GateResult(False, "RISK_LIMIT", "projected total open stop risk exceeds account limit",
                              planned_price, planned_size)
        emit("risk.max_total_open", True, projected_total_risk, max_total_risk)
        emit("risk.total_notional", True, facts.reserved_notional_usdt, None,
             "notional is audit-only; total margin is capped separately",
             status="SKIPPED")
        direction_count = facts.same_direction_position_count
        if direction_count is None:
            emit("risk.max_same_direction", False, None, config.max_same_direction_positions,
                 "same-direction position count unavailable")
            return GateResult(False, "RISK_LIMIT", "same-direction position count unavailable",
                              planned_price, planned_size)
        if direction_count >= config.max_same_direction_positions:
            emit("risk.max_same_direction", False, direction_count, config.max_same_direction_positions)
            return GateResult(False, "RISK_LIMIT", "same-direction position limit reached",
                              planned_price, planned_size)
        emit("risk.max_same_direction", True, direction_count, config.max_same_direction_positions)
    return None
