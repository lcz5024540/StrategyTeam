from dataclasses import replace
from decimal import Decimal as D
import pytest
from htx_trend.execution import ExecutionConfig, ExecutionFacts, evaluate_gate, price_plan, size_plan
from htx_trend.market_data import TopOfBook
from htx_trend.models import ModelCode, TrendSide
from htx_trend.strategy_context import MarketContext, MarketRegime
from test_execution import candidate, inputs

CFG = ExecutionConfig(entry_plan_version='structure-cost-v1', min_notional=D(1))


def test_plan_selects_nearest_target_and_counts_each_cost_once():
    c = replace(candidate(), model=ModelCode.EMA_MOMENTUM_CONTINUATION)
    plan = price_plan(c, atr=D(2), execution_price=D(100), fee_rate=D('.0004'), config=CFG)
    assert plan.structure_target == 110
    assert plan.worst_acceptable_entry == D('100.2')
    assert plan.expected_net_reward == D('9.516') # 109.8-100.2 - (100.2+109.8)*.0004
    assert plan.net_risk == D('5.478') # 100.2-94.8 + (100.2+94.8)*.0004
    assert plan.expected_net_r == plan.expected_net_reward / plan.net_risk
    assert plan.funding_cost is None
    short = replace(c, side=TrendSide.SHORT, invalidation_price=D(105), target_low=D(85), target_high=D(90))
    p = price_plan(short, atr=D(2), execution_price=D(100), fee_rate=D('.0004'), config=CFG)
    assert p.structure_target == 90 and p.expected_net_reward == D('9.524') and p.net_risk == D('5.482')


def test_limit_plan_has_no_adverse_entry_slippage_and_uses_conservative_fee():
    p = price_plan(candidate(), atr=D(2), execution_price=D('99.9'), passive=True, config=CFG)
    assert p.style.value == 'PASSIVE_LIMIT' and p.worst_acceptable_entry == D('99.9')
    assert p.entry_slippage == 0 and p.exit_slippage == D('.2')
    assert p.entry_fee == D('99.9') * CFG.fee_rate
    free = price_plan(candidate(), atr=D(2), fee_rate=D(0), config=CFG)
    assert free.entry_fee == free.target_exit_fee == free.stop_exit_fee == 0


@pytest.mark.parametrize('changes', [dict(target_low=D(99)), dict(invalidation_price=D(101)),
    dict(target_low=D(120)), dict(target_high=D('NaN')), dict(entry_price=D(0))])
def test_bad_geometry_is_not_rescued_by_far_target(changes):
    with pytest.raises(ValueError):
        price_plan(replace(candidate(), **changes), atr=D(2), config=CFG)


def gate_args():
    return {**inputs(), 'config':CFG, 'ema_fast':D(102), 'ema_slow':D(99), 'adx':D(80),
        'momentum_quality':D(87.5), 'market_context':MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG, D('.8'))}


def test_paper_quality_matches_live_without_requiring_private_account_facts():
    c = replace(candidate(), model=ModelCode.EMA_MOMENTUM_CONTINUATION)
    paper = evaluate_gate(c, **gate_args())
    facts = ExecutionFacts(actual_taker_fee_rate=D('.0004'), executable_depth_contracts=D(10000),
        available_margin_usdt=D(1000), leverage=D(5))
    live = evaluate_gate(c, **{**gate_args(), 'execution_facts':facts, 'config':replace(CFG, require_execution_facts=True)})
    assert paper.allowed and live.allowed and paper.price_plan == live.price_plan
    assert paper.size_plan == live.size_plan
    # Existing Fib quality checks apply on PAPER as well; no new filter invented.
    assert evaluate_gate(candidate(), **gate_args()).stage == 'FIB_QUALITY'
    missing = gate_args(); missing.pop('ema_fast')
    assert evaluate_gate(c, **missing).stage == 'CANDIDATE_INPUTS'


def test_target_cannot_cover_cost_is_rejected_but_no_calibrated_net_r_threshold():
    c = replace(candidate(), model=ModelCode.EMA_MOMENTUM_CONTINUATION, target_low=D('100.25'))
    assert evaluate_gate(c, **gate_args()).stage == 'COST'
    c = replace(c, target_low=D('100.55'))
    result = evaluate_gate(c, **gate_args())
    assert result.allowed and 0 < result.price_plan.expected_net_r < D('.25')


def test_limit_gate_uses_actual_tick_price_for_size_and_cost():
    c = replace(candidate(), model=ModelCode.EMA_MOMENTUM_CONTINUATION)
    result = evaluate_gate(c, entry_limit_price=D('99.9'), **gate_args())
    assert result.allowed and result.price_plan.desired_entry == D('99.9')
    assert result.size_plan.risk_at_stop == result.price_plan.net_risk * D('.01') * result.size_plan.contracts
    for price in (D('99.99'), D('100.1')):
        assert evaluate_gate(c, entry_limit_price=price, **gate_args()).stage == 'PRICE_PLAN'


def test_legacy_formula_remains_available_and_version_is_explicit(monkeypatch):
    c = candidate()
    p = price_plan(c, atr=D(2))
    assert p.version == 'legacy' and p.structure_target is None
    assert p.expected_net_r == (D(110)-D('100.2')-D('100.2')*D('.0008')-D('.2'))/D('5.2')
    monkeypatch.setenv('HTX_TREND_ENTRY_PLAN_VERSION', 'structure-cost-v1')
    assert ExecutionConfig.from_env().entry_plan_version == 'structure-cost-v1'
    monkeypatch.setenv('HTX_TREND_ENTRY_PLAN_VERSION','typo')
    with pytest.raises(ValueError): ExecutionConfig.from_env()
