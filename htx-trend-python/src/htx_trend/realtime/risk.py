"""Common price geometry and account sizing, without fake legacy model scores."""

from dataclasses import dataclass, replace
from decimal import Decimal as D, ROUND_CEILING, ROUND_FLOOR
from ..execution import (
    ExecutionConfig,
    ExecutionStyle,
    GateResult,
    PricePlan,
    SizePlan,
    ExecutionFacts,
    structured_price_plan,
    size_plan,
    account_risk_gate,
)
from ..models import TrendSide
from ..research.store import digest
from .plans import Parameters, RealtimeTrigger


@dataclass(frozen=True)
class EntryIntent:
    plan_id: str
    model: str
    side: TrendSide
    contract_code: str
    client_order_id: str
    trigger_ms: int
    structure_bar: int
    price: PricePlan
    size: SizePlan
    evidence_ref: str | None
    exit_policy_ref: str = "TREND_V4/linked-v1"
    leverage: D | None = None


def evaluate(
    trigger: RealtimeTrigger,
    *,
    now_ms,
    book,
    contract,
    funding,
    facts: ExecutionFacts,
    instance_key,
    parameters=Parameters(),
    config=ExecutionConfig(),
    evidence_ref=None,
    recent_candles=(),
):
    p = trigger.plan
    if not (
        0 <= now_ms - trigger.trigger_ms <= parameters.max_age_ms
        and 0 <= now_ms - trigger.exchange_ms <= parameters.max_age_ms
    ):
        return GateResult(False, "DATA_FRESHNESS", "realtime trigger expired"), None
    if p.parameter_fingerprint != parameters.fingerprint:
        return GateResult(False, "PARAMETERS", "plan parameter mismatch"), None
    if (
        contract.contract_status != 1
        or contract.contract_code != p.contract
        or contract.price_tick != p.tick
    ):
        return GateResult(
            False, "CONTRACT", "contract unavailable or tick changed"
        ), None
    if (
        facts.contract_open_order
        or facts.same_direction_open_order
        or facts.external_position_side is not None
        or not facts.exchange_position_consistent
    ):
        return GateResult(
            False, "EXPOSURE", "existing or inconsistent contract exposure"
        ), None
    required = (
        facts.account_equity_usdt,
        facts.actual_taker_fee_rate,
        facts.leverage,
        facts.available_margin_usdt,
        facts.used_margin_usdt,
        facts.reserved_margin_usdt,
        facts.active_position_count,
        facts.same_direction_position_count,
    )
    if (
        any(v is None for v in required)
        or facts.account_equity_usdt <= 0
        or facts.leverage <= 0
    ):
        return GateResult(
            False, "ACCOUNT_FACTS", "fresh account, fee and risk facts required"
        ), None
    numeric = (*required, facts.reserved_risk_usdt)
    if any(not D(str(v)).is_finite() or D(str(v)) < 0 for v in numeric):
        return GateResult(
            False, "ACCOUNT_FACTS", "account facts must be finite and nonnegative"
        ), None
    if config.min_average_turnover > 0:
        values = [
            c.turnover
            for c in recent_candles[-config.liquidity_lookback :]
            if c.turnover is not None
        ]
        if not values or sum(values, D(0)) / len(values) < config.min_average_turnover:
            return GateResult(
                False,
                "LIQUIDITY",
                "historical average turnover insufficient or missing",
            ), None
    if facts.active_position_count >= min(
        config.max_active_positions,
        facts.max_active_positions or config.max_active_positions,
    ):
        return GateResult(False, "RISK_LIMIT", "active position limit reached"), None
    if (
        funding is None
        or funding.contract_code != p.contract
        or not funding.rate.is_finite()
    ):
        return GateResult(False, "FUNDING", "funding unavailable"), None
    if (
        funding.rate if p.side is TrendSide.LONG else -funding.rate
    ) > config.max_adverse_funding_rate:
        return GateResult(False, "FUNDING", "adverse funding rate"), None
    top = book.top
    if top.bid <= 0 or top.ask < top.bid or top.spread_rate > config.max_spread_rate:
        return GateResult(False, "SPREAD", "executable spread invalid"), None
    if top.bid % p.tick != 0 or top.ask % p.tick != 0:
        return GateResult(
            False, "CONTRACT", "quote not aligned with current contract tick"
        ), None
    long = p.side is TrendSide.LONG
    union = p.model == "RT_INDICATOR_UNION"
    if not union and not (top.bid > p.line + p.band if long else top.ask < p.line - p.band):
        return GateResult(False, "RECOVERED", "price no longer confirms trigger"), None
    quote = top.ask if long else top.bid
    sign = D(1) if long else D(-1)
    if (
        sign * (quote - trigger.reference)
        > config.max_entry_chase_atr_multiplier * p.atr
    ):
        return GateResult(
            False, "CHASE", "price moved beyond trigger chase budget"
        ), None
    effective = replace(
        config,
        hard_risk_limits_enabled=True,
        entry_plan_version="structure-cost-v1",
        account_equity_usdt=facts.account_equity_usdt,
    )
    # Worst fill comes from the shared slippage formula; round toward a tighter limit.
    proposed = quote + sign * p.atr * config.slippage_atr_fraction
    chase_cap = trigger.reference + sign * config.max_entry_chase_atr_multiplier * p.atr
    proposed = min(proposed, chase_cap) if long else max(proposed, chase_cap)
    limit = (proposed / p.tick).to_integral_value(
        rounding=ROUND_FLOOR if long else ROUND_CEILING
    ) * p.tick
    if limit < quote if long else limit > quote:
        return GateResult(
            False, "PRICE_PLAN", "tick leaves no marketable IOC price"
        ), None
    try:
        price = structured_price_plan(
            trigger,
            atr=p.atr,
            execution_price=quote,
            fee_rate=facts.actual_taker_fee_rate,
            entry_fee_rate=facts.actual_taker_fee_rate,
            passive=False,
            config=effective,
            entry_limit=limit,
        )
        price = replace(price, style=ExecutionStyle.IOC_LIMIT, desired_entry=limit)
    except (ValueError, ArithmeticError):
        return GateResult(
            False, "PRICE_PLAN", "invalid structural reward or risk"
        ), None
    if not union and (price.expected_net_reward <= 0 or price.expected_net_r < parameters.min_net_r):
        return GateResult(
            False, "NET_R", "cost-adjusted target insufficient", price
        ), None
    remaining = (
        facts.account_equity_usdt * effective.max_total_open_risk_rate
        - facts.reserved_risk_usdt
    )
    if remaining <= 0:
        return GateResult(
            False, "RISK_LIMIT", "account stop risk exhausted", price
        ), None
    effective = replace(
        effective,
        max_risk_per_trade_usdt=min(effective.max_risk_per_trade_usdt, remaining),
    )
    size = size_plan(trigger, contract, price, config=effective)
    if size.contracts <= 0:
        return GateResult(
            False,
            "SIZING",
            "minimum contract/notional exceeds risk budget",
            price,
            size,
        ), None
    risk_gate = account_risk_gate(price, size, facts, config=effective)
    if risk_gate is not None:
        return risk_gate, None
    levels = book.asks if long else book.bids
    depth = (
        sum((qty for px, qty in levels if px <= limit), D(0))
        if long
        else sum((qty for px, qty in levels if px >= limit), D(0))
    )
    if depth < D(size.contracts) * effective.min_depth_multiple:
        return GateResult(
            False, "LIQUIDITY", "depth inside IOC cap insufficient", price, size
        ), None
    if facts.available_margin_usdt < max(
        effective.min_available_margin,
        size.notional / facts.leverage * effective.margin_safety_ratio,
    ):
        return GateResult(
            False, "MARGIN", "available margin insufficient", price, size
        ), None
    client = str(int(digest({"instance": instance_key, "plan": p.plan_id})[:15], 16))
    intent = EntryIntent(
        p.plan_id,
        p.model,
        p.side,
        p.contract,
        client,
        trigger.trigger_ms,
        p.structure_bar,
        price,
        size,
        evidence_ref,
        leverage=facts.leverage,
        exit_policy_ref=("INDICATOR_BRACKET_V1/" + parameters.target_mode) if union else "TREND_V4/linked-v1",
    )
    return GateResult(
        True, "READY", "realtime structure/cost/account gates passed", price, size
    ), intent
