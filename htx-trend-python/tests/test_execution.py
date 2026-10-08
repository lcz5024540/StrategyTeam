from decimal import Decimal

from htx_trend.execution import ExecutionConfig, ExecutionFacts, PriorTrade, cooldown, evaluate_gate, price_plan
from htx_trend.market_data import Candle, ContractSpec, FundingRate, TopOfBook
from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.strategy_context import FibonacciQuality, MarketContext, MarketRegime, evaluate_candidate_state


def candidate(score: str = "95") -> ModelCandidate:
    structure = PriceStructure(Decimal("110"), Decimal("90"), Decimal("110"), Decimal("90"), Decimal("97.64"), Decimal("102.36"))
    return ModelCandidate(ModelCode.FIB_PULLBACK_CONTINUATION, TrendSide.LONG, 9_000, Decimal(score), Decimal("100"),
                          Decimal("95"), Decimal("110"), Decimal("115"), Decimal("2"), structure, ("fib",), 7_200)


def inputs():
    return dict(period="15min", now_epoch=10_000, atr=Decimal("2"), order_book=TopOfBook(Decimal("99.99"), Decimal("100.01")),
                contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1),
                funding=FundingRate("BTC-USDT", Decimal("0.0001")),
                recent_candles=(Candle(8_100, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),),
                prior_trade=None, config=ExecutionConfig(min_score=Decimal("70"), min_net_r=Decimal("0"), min_notional=Decimal("1"), target_notional=Decimal("100"), max_notional=Decimal("100")))


def test_java_compatible_price_plan_uses_market_style_and_cost_adjusted_reward() -> None:
    plan = price_plan(candidate(), atr=Decimal("2"), config=ExecutionConfig())
    assert plan.style.value == "IOC_OR_MARKET"
    assert plan.worst_acceptable_entry > plan.desired_entry
    assert plan.expected_net_r > 0


def test_java_loss_cooldown_never_bypasses_on_score_alone() -> None:
    prior = PriorTrade("BTC-USDT", 8_800, Decimal("-1"), "MODEL_INVALIDATION")
    assert not cooldown(candidate("95"), prior, now_epoch=10_000).allowed
    no_override = cooldown(candidate("99"), prior, now_epoch=9_500)
    assert not no_override.allowed and no_override.override_reason is None


def test_gate_returns_explainable_rejection_and_success() -> None:
    rejected = evaluate_gate(candidate("60"), **inputs())
    assert not rejected.allowed and rejected.stage == "MODEL_POLICY"
    passed = evaluate_gate(candidate(), **inputs())
    assert passed.allowed and passed.size_plan is not None and passed.size_plan.contracts > 0


def test_gate_refuses_reused_structure_before_any_execution_check() -> None:
    item = candidate()
    item = ModelCandidate(item.model, item.side, item.bar_time, item.score, item.entry_price, item.invalidation_price,
                          item.target_low, item.target_high, item.expected_r, item.structure, item.evidence,
                          item.source_breakout_time, "FIB_PULLBACK_CONTINUATION:LONG:7200:9000")
    result = evaluate_gate(item, claimed_structure_keys=frozenset({item.structure_key}), **inputs())
    assert not result.allowed and result.stage == "STRUCTURE"


def test_fib_quality_blocks_wick_only_spike_even_when_score_is_high() -> None:
    quality = FibonacciQuality(Decimal("1.2"), Decimal("0.10"), Decimal("0.10"), Decimal("3"), True)
    strict = ExecutionConfig(min_score=Decimal("70"), min_net_r=Decimal("0"), min_notional=Decimal("1"),
                             target_notional=Decimal("100"), max_notional=Decimal("100"), require_execution_facts=True)
    result = evaluate_gate(candidate(), fibonacci_quality=quality, **{**inputs(), "config": strict})
    assert not result.allowed and result.stage == "FIB_QUALITY"


def test_strict_live_preflight_requires_account_fee_depth_and_margin_facts() -> None:
    strict = ExecutionConfig(min_score=Decimal("70"), min_net_r=Decimal("0"), min_notional=Decimal("1"),
                             target_notional=Decimal("100"), max_notional=Decimal("100"), require_execution_facts=True)
    result = evaluate_gate(candidate(), **{**inputs(), "config": strict})
    assert not result.allowed and result.stage == "FIB_QUALITY"
    quality = FibonacciQuality(Decimal("1.2"), Decimal("0.5"), Decimal("0.1"), Decimal("1"), True)
    result = evaluate_gate(candidate(), fibonacci_quality=quality,
                           execution_facts=ExecutionFacts(actual_taker_fee_rate=Decimal("0.0004"),
                                                         executable_depth_contracts=Decimal("100"),
                                                         available_margin_usdt=Decimal("100"), leverage=Decimal("5")),
                           **{**inputs(), "config": strict})
    assert result.allowed


def test_java_gate_rejects_unavailable_funding_before_later_preflight_checks() -> None:
    """Java fundingCostGate is the first real-execution gate after observe mode."""
    result = evaluate_gate(candidate(), **{**inputs(), "funding": None,
                                            "now_epoch": 20_000})
    assert not result.allowed
    assert result.stage == "FUNDING"


def test_java_fee_protection_uses_atr_expected_move_not_structure_target_net_r() -> None:
    """A nearby structural target cannot reject an entry when Java's ATR cost gate passes."""
    item = candidate()
    near_target = ModelCandidate(item.model, item.side, item.bar_time, item.score, item.entry_price,
                                 item.invalidation_price, Decimal("100.10"), Decimal("100.10"),
                                 item.expected_r, item.structure, item.evidence, item.source_breakout_time)
    result = evaluate_gate(near_target, **inputs())
    assert result.allowed


def test_java_gate_blocks_any_contract_open_order_not_only_same_direction_order() -> None:
    result = evaluate_gate(candidate(), **{**inputs(), "execution_facts": ExecutionFacts(
        contract_open_order=True,
    )})
    assert not result.allowed
    assert result.stage == "ORDER_POSITION"


def test_candidate_state_ports_java_counter_market_qualification() -> None:
    opposed = MarketContext(MarketRegime.BROAD_DOWN, TrendSide.SHORT, Decimal("0.9"), TrendSide.SHORT, TrendSide.SHORT)
    rejected = evaluate_candidate_state(Decimal("95"), Decimal("2"), Decimal("70"), TrendSide.LONG,
                                        Decimal("101"), Decimal("100"), Decimal("30"), opposed, True)
    assert not rejected.tradable
    from htx_trend.strategy_context import EntryStateConfig
    qualified = evaluate_candidate_state(Decimal("95"), Decimal("2"), Decimal("70"), TrendSide.LONG,
                                         Decimal("101"), Decimal("100"), Decimal("30"), opposed, True,
                                         EntryStateConfig(counter_enabled=True))
    assert qualified.tradable and "COUNTER_QUALIFIED" in qualified.reason
