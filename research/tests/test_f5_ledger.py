from decimal import Decimal as D
import pandas as pd
import pytest
from coinresearch.ledger import build_ledger
from coinresearch.signals import plan_trades
from coinresearch.report import build_report


def test_f5_hand_calc_table():
    """B1 exact bps: funding raw=1.25-0.125=1.125, RT=12.

    id     side entry exit gross   RT funding  net
    long     +1 100   101   100    12  1.125   86.875
    short    -1 100    99   100    12 -1.125   89.125
    loss     +1 100    98  -200    12  1.125 -213.125
    flat     -1 100   100     0    12 -1.125  -10.875
    Every displayed gross/funding/net difference from hand calculation is 0.
    """
    examples = [('long', 1, '101', '100', '1.125', '86.875'),
                ('short', -1, '99', '100', '-1.125', '89.125'),
                ('loss', 1, '98', '-200', '1.125', '-213.125'),
                ('flat', -1, '100', '0', '-1.125', '-10.875')]
    trades = [dict(id=id_, coin='A', side=side, entry_ts=0, exit_ts=16,
                   entry_open='100', exit_open=exit_, funding_timestamps=[8, 16])
              for id_, side, exit_, *_ in examples]
    result = build_ledger(trades, {'A': {8: '1.25', 16: '-0.125'}})
    assert not result['excluded']
    print('\nid side entry exit gross rt_cost funding net error')
    for row, (id_, side, exit_, gross, funding, net) in zip(result['ledger'], examples):
        expected = dict(entry_open=D(100), exit_open=D(exit_), gross_bps=D(gross),
                        rt_cost_bps=D(12), funding_cost_bps=D(funding), net_bps=D(net))
        assert all(isinstance(row[k], D) and row[k]-v == 0 for k, v in expected.items())
        assert (row['side'], row['entry_ts'], row['exit_ts']) == (side, 0, 16)
        print(id_, side, *(str(row[k]) for k in expected), '0')


def test_missing_funding_excluded_no_proxy_and_gross_compatibility():
    trade = dict(id='a', coin='A', side=1, entry_ts=0, exit_ts=16,
                 gross_bps='100.125', funding_timestamps=[8, 16])
    assert build_ledger([trade], {'A': {8: '1'}})['excluded'][0]['reason'] == 'MISSING_FUNDING_PERIOD'
    assert not build_ledger([trade], {'A': {8: '1'}})['ledger']
    r = build_ledger([trade], {'A': {8: '1.25', 16: '-.125'}})['ledger'][0]
    assert r['net_bps'] == D('87.000') and 'entry_open' not in r
    assert build_ledger([dict(trade, funding_timestamps=None)], {})['excluded']


def test_signals_ledger_report_chain():
    b = pd.DataFrame(dict(ts=[0, 10, 20], open=['99', '100', '101']))
    e = pd.DataFrame(dict(ts=b.ts, side=[1, 0, 0]))
    plans = plan_trades(e, b, coin='A', bar_seconds=10)['plans']
    # Expected calendar is explicit and independent of observed rate availability.
    trades = [dict(p, funding_timestamps=[20]) for p in plans]
    rows = build_ledger(trades, {'A': {20: '1.125'}})['ledger']
    assert rows[0]['net_bps'] == D('86.875')
    assert build_report(rows)['by_year']['1970']['net_bps_sum'] == '86.875'


def test_supplied_price_metadata_validated_even_with_gross():
    t = dict(id='a', coin='A', side=1, entry_ts=0, exit_ts=1, funding_timestamps=[], gross_bps='1', entry_open=0)
    with pytest.raises(ValueError): build_ledger([t], {'A': {}})
