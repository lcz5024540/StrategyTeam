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


def price_plan(candidate: ModelCandidate, *, atr: Decimal, config: ExecutionConfig = ExecutionConfig()) -> PricePlan:
    style = (ExecutionStyle.PASSIVE_LIMIT if candidate.model is ModelCode.FIB_PULLBACK_CONTINUATION
             else ExecutionStyle.CONDITIONAL_OR_MARKET if candidate.model is ModelCode.DONCHIAN_BREAKOUT
             else ExecutionStyle.IOC_OR_MARKET)
    adverse = atr * config.slippage_atr_fraction
    worst = candidate.entry_price + adverse if candidate.side is TrendSide.LONG else candidate.entry_price - adverse
    target = candidate.target_low
    gross_reward = target - worst if candidate.side is TrendSide.LONG else worst - target
    risk = worst - candidate.invalidation_price if candidate.side is TrendSide.LONG else candidate.invalidation_price - worst
    round_trip_cost = worst * config.fee_rate * Decimal("2") + adverse
    net_reward = gross_reward - round_trip_cost
    expected_net_r = net_reward / risk if risk > ZERO else ZERO
    return PricePlan(style, candidate.entry_price, worst, candidate.invalidation_price, candidate.target_low,
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
                  prior_trade: PriorTrade | None, config: ExecutionConfig = ExecutionConfig()) -> GateResult:
    if candidate.score < config.min_score:
        return GateResult(False, "MODEL_POLICY", f"candidate score {candidate.score} below {config.min_score}")
    if now_epoch > candidate.bar_time + period_seconds(period) * 2:
        return GateResult(False, "FRESHNESS", "candidate bar expired")
    if order_book.spread_rate > config.max_spread_rate:
        return GateResult(False, "EXECUTION", f"spread {order_book.spread_rate} above {config.max_spread_rate}")
    if config.min_average_turnover > ZERO:
        values = [item.turnover for item in recent_candles[-config.liquidity_lookback:] if item.turnover is not None]
        average = sum(values, ZERO) / Decimal(len(values)) if values else ZERO
        if average < config.min_average_turnover:
            return GateResult(False, "LIQUIDITY", f"average turnover {average} below {config.min_average_turnover}")
    if funding is not None:
        side_cost = funding.rate if candidate.side is TrendSide.LONG else -funding.rate
        if side_cost > config.max_adverse_funding_rate:
            return GateResult(False, "CARRY", f"adverse funding {side_cost} above {config.max_adverse_funding_rate}")
    cooldown_result = cooldown(candidate, prior_trade, now_epoch=now_epoch, config=config)
    if not cooldown_result.allowed:
        return GateResult(False, "COOLDOWN", f"{cooldown_result.reason}; remaining={cooldown_result.remaining_seconds}s")
    planned_price = price_plan(candidate, atr=atr, config=config)
    if planned_price.expected_net_r < config.min_net_r:
        return GateResult(False, "ECONOMICS", f"expected net R {planned_price.expected_net_r} below {config.min_net_r}", planned_price)
    planned_size = size_plan(candidate, contract, planned_price, config=config)
    if planned_size.contracts <= 0:
        return GateResult(False, "SIZING", planned_size.reason, planned_price, planned_size)
    return GateResult(True, "READY", cooldown_result.override_reason or "all model, market, cost and risk gates passed", planned_price, planned_size)
