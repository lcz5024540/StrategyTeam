from decimal import Decimal

from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.policy import StrategyPolicy, confirmation_models, select_policy
from htx_trend.strategy_context import MarketContext, MarketRegime


def candidate(model: ModelCode, score: str = "95") -> ModelCandidate:
    structure = PriceStructure(Decimal("110"), Decimal("90"), Decimal("110"), Decimal("90"), Decimal("97"), Decimal("103"))
    return ModelCandidate(model, TrendSide.LONG, 1, Decimal(score), Decimal("100"), Decimal("95"),
                          Decimal("110"), Decimal("120"), Decimal("2"), structure, ("evidence",))


def policy(model: str, confirmations: str, threshold: str = "85") -> StrategyPolicy:
    return StrategyPolicy("P", "combo-v2", model, confirmations, Decimal("190"), Decimal("285"), Decimal(threshold), 32)


def test_policy_selection_matches_java_primary_confirmation_threshold_and_score_multiplier() -> None:
    item = candidate(ModelCode.DONCHIAN_BREAKOUT)
    selected = select_policy((item,), (policy("DONCHIAN_BREAKOUT", "BOX_BREAKOUT"),), MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG))
    assert len(selected) == 1
    assert selected[0].confirmation_models == "BOX_BREAKOUT"
    assert selected[0].sizing_multiplier == Decimal("1.5")
    assert selected[0].planned_notional_usdt == Decimal("285")


def test_policy_selection_uses_raw_score_for_threshold_and_does_not_admit_on_market_bonus() -> None:
    item = candidate(ModelCode.DONCHIAN_BREAKOUT, "84")
    selected = select_policy((item,), (policy("DONCHIAN_BREAKOUT", "BOX_BREAKOUT", "85"),),
                             MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG))
    assert not selected


def test_ema_policy_uses_same_confirmation_labels_as_java_policy_row() -> None:
    item = candidate(ModelCode.EMA_MOMENTUM_CONTINUATION, "90")
    assert confirmation_models(item) == "EMA_STACK,MOMENTUM"
    selected = select_policy((item,), (policy(item.model.value, "EMA_STACK,MOMENTUM", "88"),), MarketContext())
    assert len(selected) == 1


def test_java_policy_notional_uses_candidate_market_regime_not_cross_symbol_breadth() -> None:
    item = candidate(ModelCode.DONCHIAN_BREAKOUT, "95")
    item = ModelCandidate(item.model, item.side, item.bar_time, item.score, item.entry_price,
                          item.invalidation_price, item.target_low, item.target_high, item.expected_r,
                          item.structure, item.evidence, market_regime="BROAD_UP")
    selection = select_policy((item,), (policy("DONCHIAN_BREAKOUT", "BOX_BREAKOUT"),),
                              MarketContext(MarketRegime.MIXED))[0]
    # Java's HtxTrendCombinationService uses candidate.marketRegime here;
    # global breadth only adjusts ranking and later adaptive sizing.
    assert selection.sizing_multiplier == Decimal("1.5")
