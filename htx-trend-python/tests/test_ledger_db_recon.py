"""真实证据最小片段回归；不访问证据目录或网络。"""
from decimal import Decimal as D
from pathlib import Path
import json
import os
import subprocess
import sys
import pytest
from htx_trend.tools.ledger_db_recon import read_rows, reconcile, markdown
FIX = Path(__file__).parent/'fixtures'/'ledger_pnl'


def report(**kwargs):
    return reconcile(*(read_rows(FIX/(name+'.json')) for name in ('positions', 'ledger', 'fills')),
                     funding_complete=True, window_start='2026-10-19T21:35:46+08:00', **kwargs)


def test_original_thirteen_exact_attribution():
    r = report(as_of='2026-10-21T09:29:00+08:00'); s = r['summary']
    assert s['matched_positions'] == 13 and s['incomplete_positions'] == 0
    assert s['ledger_net'] == D('-4.233935')
    assert s['gross'] == D('-3.5382') and s['closing_fee'] == D('.695735')
    assert s['opening_fee'] == s['difference'] == D('.69480092')
    assert s['db_final_net'] == s['authoritative_net'] == D('-4.92873592')
    assert s['funding_fee'] == 0
    assert {p['position_id'] for p in r['positions'] if '旧窗' in p['reasons']} == {'325','327','329'}
    assert sum((D(f['fee']) for f in read_rows(FIX/'fills.json') if f['leg']=='ENTRY' and f['position_id']!='20186'), D(0)) == D('.70467988')
    assert '开仓费' in markdown(r)


def test_eleven_window_trades_against_independent_db_and_fills():
    rows = {p['position_key']:p for p in report()['positions']}
    trades = read_rows(FIX/'trades_closed_window.csv')
    assert len(trades) == 11
    for trade in trades:
        p = rows[trade['position_key']]
        assert p['status'] == '已对账' and '旧窗' not in p['reasons']
        assert abs(p['authoritative_net']-p['db_final_net']) <= D('.01')
        assert abs(p['authoritative_net']-D(trade['net'])) <= D('.00000001')
    assert report()['summary']['matched_positions'] == 14


@pytest.mark.parametrize('fmt', ['csv','json','txt','tsv'])
def test_table_formats(tmp_path, fmt):
    text = {'csv':'id,status\n1,DONE\n','json':'[{"id":"1","status":"DONE"}]',
            'txt':'+---+---+\n| id | status |\n+---+---+\n| 1 | DONE |\n+---+---+\n',
            'tsv':'id\tstatus\n1\tDONE\n'}[fmt]
    path=tmp_path/('input.'+fmt); path.write_text(text)
    assert read_rows(path)==[{'id':'1','status':'DONE'}]


def test_missing_fills_or_funding_is_not_zero():
    ps, es, fs = (read_rows(FIX/(name+'.json')) for name in ('positions','ledger','fills'))
    r = reconcile(ps, es, [])
    assert r['summary']['matched_positions'] == 0
    assert all(p['authoritative_net'] is None for p in r['positions'])
    r = reconcile(ps, es, fs)
    assert r['summary']['matched_positions'] == 0
    assert '资金费覆盖未确认' in markdown(r)


def test_duplicate_fill_rejected():
    ps, es, fs = (read_rows(FIX/(name+'.json')) for name in ('positions','ledger','fills'))
    assert '重复成交标识' in markdown(reconcile(ps, es, fs+[fs[0]], funding_complete=True))


def test_live_separate_fee_events_and_funding():
    p=[dict(id='1',status='DONE',final_net_profit='8.5')]
    fs=[dict(position_id='1',fill_id='a',leg='ENTRY',quantity='2',price='100',fee='.4',side='LONG',contract_size='1',filled_at='2026-01-01T00:00:00+00:00'),
        dict(position_id='1',fill_id='b',leg='CLOSE',quantity='2',price='105',fee='.6',side='LONG',contract_size='1',filled_at='2026-01-02T00:00:00+00:00')]
    es=[dict(position_id='1',event_type='REALIZED_PNL',net_pnl_after_fee='10'),
        dict(position_id='1',event_type='FEE',net_pnl_after_fee='-1'),
        dict(position_id='1',event_type='FUNDING',net_pnl_after_fee='-.5',funding_fee_usdt='.5')]
    row=reconcile(p,es,fs,funding_complete=True)['positions'][0]
    assert row['authoritative_net']==row['ledger_net']==D('8.5') and row['difference']==0


def test_cli_read_only():
    args=[sys.executable,'-m','htx_trend.tools.ledger_db_recon']
    for name in ('positions','ledger','fills'): args += ['--'+name,str(FIX/(name+'.json'))]
    before = {p:p.read_bytes() for p in FIX.iterdir()}
    result=subprocess.run(args+['--funding-complete'],capture_output=True,text=True,check=True,
                          env={**os.environ, 'PYTHONPATH':str(Path(__file__).parents[1]/'src')})
    assert json.loads(result.stdout)['summary']['matched_positions']==14
    assert '汇总归因' in result.stderr
    assert all(p.read_bytes()==data for p,data in before.items())


def test_live_summary_does_not_claim_per_position_attribution():
    from htx_trend.tools.ledger_db_recon import aggregate_reconcile
    rows=aggregate_reconcile(FIX/'aggregates.txt')
    live=next(r for r in rows if r['account']=='primary')
    assert live['ledger_net']==D('-95.03177813')
    assert live['db_final_net']==D('-91.81271129')
    assert live['gross_difference']==D('-20.708267')
    assert live['fee_difference']==D('-17.48920016')
    assert live['net_difference']==live['gross_difference']-live['fee_difference']==D('-3.21906684')
    assert '证据不足' in live['status']


def test_unmatched_events_and_missing_summary_remain_visible():
    r=reconcile([], [dict(position_id='99',net_pnl_after_fee='1')], [],funding_complete=True)
    assert r['summary']['incomplete_positions']==1
    assert r['summary']['authoritative_net'] is None
    assert '缺少关联仓位' in markdown(r)
