"""Golden cases mirrored by HtxTrendEntryExecutionContractTest in Java."""
from dataclasses import replace
from decimal import Decimal

from htx_trend.execution import ExecutionConfig, ExecutionFacts, evaluate_gate
from htx_trend.execution_service import _order_request
from htx_trend.market_data import Candle, ContractSpec, FundingRate, TopOfBook
from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.strategy_context import MarketContext, MarketRegime, evaluate_candidate_state


def candidate() -> ModelCandidate:
    structure = PriceStructure(Decimal("110"), Decimal("90"), Decimal("110"), Decimal("90"), Decimal("97.64"), Decimal("102.36"))
    return ModelCandidate(ModelCode.DONCHIAN_BREAKOUT, TrendSide.LONG, 9_000, Decimal("95"), Decimal("100"),
                          Decimal("95"), Decimal("110"), Decimal("110"), Decimal("2"), structure,
                          ("fresh channel breakout confirmed by box",), policy_confirmations="BOX_BREAKOUT")


def live_inputs(**overrides):
    context = MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG, Decimal("0.8"), TrendSide.LONG, TrendSide.LONG,
                            MarketRegime.BROAD_UP)
    state = evaluate_candidate_state(Decimal("95"), Decimal("2"), Decimal("62.5"), TrendSide.LONG,
                                     Decimal("101"), Decimal("99"), Decimal("30"), context)
    values = dict(period="15min", now_epoch=10_000, atr=Decimal("2"),
                  order_book=TopOfBook(Decimal("99.95"), Decimal("100.01")),
                  contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1),
                  funding=FundingRate("BTC-USDT", Decimal("0.0001")),
                  recent_candles=(Candle(9_000, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),),
                  prior_trade=None, market_context=context, candidate_state=state,
                  momentum_quality=Decimal("62.5"), ema_fast=Decimal("101"), ema_slow=Decimal("99"), adx=Decimal("30"),
                  execution_facts=ExecutionFacts(actual_taker_fee_rate=Decimal("0.0004"), executable_depth_contracts=Decimal("500"),
                      available_margin_usdt=Decimal("100"), leverage=Decimal("5"), active_position_count=0, max_active_positions=1),
                  config=ExecutionConfig(require_execution_facts=True, java_policy_notional_sizing=True,
                      target_notional=Decimal("100"), max_notional=Decimal("100"), min_notional=Decimal("20")))
    values.update(overrides)
    return values


def test_java_final_gate_goldens_cover_cost_freshness_chase_and_volume() -> None:
    assert evaluate_gate(candidate(), **live_inputs()).allowed
    assert evaluate_gate(candidate(), **live_inputs(funding=FundingRate("BTC-USDT", Decimal("0.0007")))).stage == "FUNDING"
    assert evaluate_gate(candidate(), **live_inputs(now_epoch=10_501)).stage == "DATA_FRESHNESS"
    cost = replace(live_inputs()["config"], fee_protection_enabled=True)
    assert evaluate_gate(candidate(), **live_inputs(atr=Decimal("0.02"),
        order_book=TopOfBook(Decimal("99.999"), Decimal("100.001")), config=cost)).stage == "COST"
    assert evaluate_gate(candidate(), **live_inputs(order_book=TopOfBook(Decimal("100.49"), Decimal("100.50")))).stage == "CHASE"
    ready = evaluate_gate(candidate(), **live_inputs())
    assert ready.size_plan is not None and ready.size_plan.contracts == 99


def test_java_market_order_intent_uses_the_same_side_position_side_and_contracts() -> None:
    request = _order_request("BTC-USDT", TrendSide.LONG, "123", 99, "market")
    assert (request.side, request.position_side, request.type, request.volume) == ("buy", "long", "market", "99")
