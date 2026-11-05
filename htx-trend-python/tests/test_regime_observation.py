from copy import deepcopy
from decimal import Decimal as D
import pytest

from htx_trend.realtime.regime_bayes import fit, Snapshot
from htx_trend.realtime.regime_observation import family_key
from htx_trend.realtime.indicator_union import EXITS
from htx_trend.research.store import digest
from test_regime_bayes import labels, COST
from test_regime import NOW
from test_regime_exit_selection import params


def observation(value):
    return value['sample_observation']['families'][family_key('A', 'WINDOW', COST)]


def test_low_completion_warns_without_changing_selection():
    rows = labels()
    rows += [{**rows[0], 'opportunity_id': 'missing-' + str(i), 'status': 'CENSORED', 'net_return': None} for i in range(240)]
    value = fit(rows, cutoff_ms=NOW - 1, cost_id=COST)
    o = observation(value)
    assert o['unique_opportunities'] == 264
    assert o['exits']['ATR_1_1']['mature_results'] == 24
    assert D(o['exits']['ATR_1_1']['complete_fraction']) == D(24) / 264
    assert o['exits']['ATR_2_2']['missing_exit_records'] == 264
    assert o['paired']['n'] == 0 and o['warnings']
    decision = Snapshot(value).select_exit('A', params(value), NOW)
    assert decision['allowed'] and decision['selected_exit'] == 'ATR_1_1'
    assert decision['sample_observation']['affects_entry'] is False


def test_paired_cohort_and_exact_differences_are_deterministic():
    rows = [{**r, 'exit_profile': mode, 'net_return': str(D(r['net_return']) + D(j) / 1000)}
            for r in labels() for j, mode in enumerate(EXITS)]
    rows = [r for r in rows if not (r['exit_profile'] == 'ATR_2_2' and r['opportunity_id'] == '0')]
    a = fit(rows + [rows[0]], cutoff_ms=NOW - 1, cost_id=COST)
    b = fit(list(reversed(rows + [rows[0]])), cutoff_ms=NOW - 1, cost_id=COST)
    assert a == b
    o = observation(a)
    assert o['paired']['n'] == 23
    assert o['paired']['hour_blocks'] == 23
    assert o['paired']['comparisons']['ATR_1_1-ATR_2_2']['mean_net_return_difference'] == '-0.002'
    assert o['paired']['comparisons']['ATR_1_1-ATR_2_2']['right_better'] == 23
    assert o['exits']['ATR_1_1']['unique_opportunities'] == 24
    assert 'UNEQUAL_MATURE_COHORTS' in o['warnings']


def test_future_unknown_cost_overlap_excluded_from_paired_results():
    rows = [{**r, 'exit_profile': mode} for r in labels() for mode in EXITS]
    for r in rows:
        if r['exit_profile'] != 'ATR_2_2':
            continue
        if r['opportunity_id'] == '0': r['observed_ms'] = NOW
        if r['opportunity_id'] == '1': r['cost_status'] = 'UNKNOWN'
        if r['opportunity_id'] == '2': r['contract'] = 'BTC-USDT'
    v = fit(rows, cutoff_ms=NOW - 1, cost_id=COST)
    o = observation(v)
    assert o['paired']['n'] == 21
    assert o['paired']['identity_mismatches'] == ['2']
    assert o['exits']['ATR_2_2']['status_and_exclusion_counts']['IMMATURE_AT_CUTOFF'] == 1
    assert o['exits']['ATR_2_2']['status_and_exclusion_counts']['UNKNOWN_COST'] == 1


def test_missing_observation_is_nonblocking_and_statistics_unchanged():
    value = fit(labels(), cutoff_ms=NOW - 1, cost_id=COST)
    original = Snapshot(value).select_exit('A', params(value), NOW)
    legacy = deepcopy(value)
    legacy.pop('sample_observation')
    legacy.pop('hash')
    legacy['hash'] = digest(legacy)
    result = Snapshot(legacy).select_exit('A', params(legacy), NOW)
    assert result['sample_observation']['warnings'] == ['SAMPLE_OBSERVATION_UNAVAILABLE']
    for key in ('allowed', 'selected_exit', 'ranked_exits'):
        assert result[key] == original[key]
    for mode in EXITS:
        for key in ('allowed', 'reason', 'mean_net_return', 'posterior_mean'):
            assert result['assessments'][mode].get(key) == original['assessments'][mode].get(key)


def test_empty_input_has_no_invented_zero_performance():
    v = fit([], cutoff_ms=NOW, cost_id=COST)
    assert v['sample_observation']['families'] == {}


@pytest.mark.parametrize('metadata', [None, [], {'families': None}, {'families': {family_key('A', 'WINDOW', COST): None}}])
def test_malformed_optional_observation_cannot_block_valid_selection(metadata):
    value = fit(labels(), cutoff_ms=NOW - 1, cost_id=COST)
    value['sample_observation'] = metadata
    value.pop('hash')
    value['hash'] = digest(value)
    result = Snapshot(value).select_exit('A', params(value), NOW)
    assert result['allowed'] and result['selected_exit'] == 'ATR_1_1'
    assert not result['sample_observation']['affects_entry']
