from dataclasses import replace
import pytest
from coinresearch.halt import evaluate_halt, Health, HaltThresholds


def healthy(**overrides):
    return dict(forward_net_ci_lower_bps=1, current_drawdown=10,
                reference_drawdown=10, realized_rt_cost=12,
                backtest_rt_cost=12, health=Health(), **overrides)


def test_no_halt_and_strict_ratio_boundaries():
    assert evaluate_halt(**healthy()) == dict(should_halt=False, reasons=[])
    args = healthy()
    args.update(current_drawdown=20, realized_rt_cost=18)
    assert not evaluate_halt(**args)['should_halt']


@pytest.mark.parametrize('changes,reason', [
    (dict(forward_net_ci_lower_bps=0), 'FORWARD_NET_CI'),
    (dict(forward_net_ci_lower_bps=-1), 'FORWARD_NET_CI'),
    (dict(current_drawdown=20.01), 'DRAWDOWN_MULTIPLE'),
    (dict(realized_rt_cost=18.01), 'COST_DEVIATION'),
    (dict(realized_rt_cost=5.99), 'COST_DEVIATION'),
    (dict(health=Health(consecutive_failed_heartbeats=3)), 'PAPER_HEARTBEATS'),
    (dict(health=Health(fill_failure_rate=.1)), 'PAPER_FILL_FAILURE_RATE'),
    (dict(health=Health(data_delay_seconds=60)), 'PAPER_DATA_DELAY'),
])
def test_each_halt_rule(changes, reason):
    args = healthy(); args.update(changes)
    assert evaluate_halt(**args) == dict(should_halt=True, reasons=[reason])


def test_all_reasons_and_parameter_overrides():
    args = healthy()
    args.update(forward_net_ci_lower_bps=0, current_drawdown=21,
                realized_rt_cost=19, health=Health(3, .1, 60))
    result = evaluate_halt(**args)
    assert result['reasons'] == ['FORWARD_NET_CI', 'DRAWDOWN_MULTIPLE', 'COST_DEVIATION', 'PAPER_HEARTBEATS', 'PAPER_FILL_FAILURE_RATE', 'PAPER_DATA_DELAY']
    assert evaluate_halt(**args) == result  # pure, stable
    relaxed = HaltThresholds(-1, 3, 1, 4, .2, 61)
    assert not evaluate_halt(**args, thresholds=relaxed)['should_halt']


def test_zero_references():
    args = healthy()
    args.update(current_drawdown=0, reference_drawdown=0, realized_rt_cost=0, backtest_rt_cost=0)
    assert not evaluate_halt(**args)['should_halt']
    args.update(current_drawdown=1, realized_rt_cost=1)
    assert evaluate_halt(**args)['reasons'] == ['DRAWDOWN_MULTIPLE', 'COST_DEVIATION']


@pytest.mark.parametrize('changes', [dict(forward_net_ci_lower_bps=float('nan')),
                                     dict(current_drawdown=-1), dict(backtest_rt_cost=-1),
                                     dict(health=Health(-1)), dict(health=Health(fill_failure_rate=2))])
def test_invalid_metrics_rejected(changes):
    args = healthy(); args.update(changes)
    with pytest.raises(ValueError): evaluate_halt(**args)


@pytest.mark.parametrize('changes', [dict(drawdown_multiple=0), dict(failed_heartbeats=0),
                                     dict(cost_relative_deviation=-1), dict(data_delay_seconds=float('inf'))])
def test_invalid_thresholds_rejected(changes):
    with pytest.raises(ValueError): evaluate_halt(**healthy(), thresholds=replace(HaltThresholds(), **changes))
