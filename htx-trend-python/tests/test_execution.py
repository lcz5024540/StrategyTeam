from decimal import Decimal

from htx_trend.execution import ExecutionConfig, PriorTrade, cooldown, evaluate_gate, price_plan
from htx_trend.market_data import Candle, ContractSpec, FundingRate, TopOfBook
from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide


def candidate(score: str = "95") -> ModelCandidate:
    structure = PriceStructure(Decimal("110"), Decimal("90"), Decimal("110"), Decimal("90"), Decimal("97.64"), Decimal("102.36"))
    return ModelCandidate(ModelCode.FIB_PULLBACK_CONTINUATION, TrendSide.LONG, 9_000, Decimal(score), Decimal("100"),
                          Decimal("95"), Decimal("110"), Decimal("115"), Decimal("2"), structure, ("fib",), 7_200)


def inputs():
    return dict(period="15min", now_epoch=9_100, atr=Decimal("2"), order_book=TopOfBook(Decimal("99.99"), Decimal("100.01")),
                contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1),
                funding=FundingRate("BTC-USDT", Decimal("0.0001")),
                recent_candles=(Candle(8_100, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),),
                prior_trade=None, config=ExecutionConfig(min_score=Decimal("70"), min_net_r=Decimal("0"), min_notional=Decimal("1"), target_notional=Decimal("100"), max_notional=Decimal("100")))


def test_fib_uses_passive_price_plan_and_cost_adjusted_reward() -> None:
    plan = price_plan(candidate(), atr=Decimal("2"), config=ExecutionConfig())
    assert plan.style.value == "PASSIVE_LIMIT"
    assert plan.worst_acceptable_entry > plan.desired_entry
    assert plan.expected_net_r > 0


def test_loss_cooldown_requires_extreme_signal_to_override() -> None:
    prior = PriorTrade("BTC-USDT", 8_800, Decimal("-1"), "MODEL_INVALIDATION")
    assert not cooldown(candidate("95"), prior, now_epoch=9_100).allowed
    override = cooldown(candidate("99"), prior, now_epoch=9_500)
    assert override.allowed and override.override_reason is not None


def test_gate_returns_explainable_rejection_and_success() -> None:
    rejected = evaluate_gate(candidate("60"), **inputs())
    assert not rejected.allowed and rejected.stage == "MODEL_POLICY"
    passed = evaluate_gate(candidate(), **inputs())
    assert passed.allowed and passed.size_plan is not None and passed.size_plan.contracts > 0
