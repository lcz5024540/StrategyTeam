from decimal import Decimal
import pandas as pd
import pytest
from coinresearch.signals import plan_trades


def inputs():
    bars = pd.DataFrame(dict(ts=list(range(0, 80, 10)), open=list(range(100, 108))))
    events = pd.DataFrame(dict(ts=bars.ts, side=[1, -1, -1, 1, 0, 1, 0, -1]))
    return events, bars


def test_direction_next_open_exit_and_nonoverlap():
    e, b = inputs()
    result = plan_trades(e, b, coin='A', holding_bars=2, bar_seconds=10)
    rows = result['plans']
    assert [r['side'] for r in rows] == [1, -1]
    assert [(r['signal_bar_ts'], r['entry_ts'], r['exit_ts']) for r in rows] == [(0, 10, 30), (20, 30, 50)]
    assert [(r['entry_open'], r['exit_open']) for r in rows] == [(Decimal(101), Decimal(103)), (Decimal(103), Decimal(105))]
    assert [r['reason'] for r in result['skipped']] == ['OVERLAPPING_POSITION', 'OVERLAPPING_POSITION', 'INCOMPLETE_HORIZON', 'INCOMPLETE_HORIZON']
    assert all(a['exit_ts'] <= z['entry_ts'] for a, z in zip(rows, rows[1:]))
    # Other coins own independent holding state.
    assert len(plan_trades(e, b, coin='B', holding_bars=2, bar_seconds=10)['plans']) == 2


def test_programmable_holding_and_incomplete_tail():
    e, b = inputs()
    r = plan_trades(e, b, coin='A', holding_bars=1, bar_seconds=10)
    assert len(r['plans']) == 5
    assert all(p['exit_ts']-p['entry_ts'] == 10 for p in r['plans'])
    assert r['skipped'][-1]['reason'] == 'INCOMPLETE_HORIZON'
    e.side = 0
    assert plan_trades(e, b, coin='A', bar_seconds=10) == dict(plans=[], skipped=[])


@pytest.mark.parametrize('case', ['alignment', 'side', 'holding', 'open', 'gap'])
def test_signal_invalid_inputs(case):
    e, b = inputs()
    hold = 1
    if case == 'alignment': e.loc[0, 'ts'] = -10
    if case == 'side': e.loc[0, 'side'] = 2
    if case == 'holding': hold = 0
    if case == 'open': b.loc[0, 'open'] = float('nan')
    if case == 'gap': b.loc[1, 'ts'] = 11
    with pytest.raises(ValueError): plan_trades(e, b, coin='A', holding_bars=hold, bar_seconds=10)
