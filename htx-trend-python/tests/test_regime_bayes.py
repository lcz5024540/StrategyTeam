from dataclasses import replace
from decimal import Decimal as D
import json
import pytest

from htx_trend.realtime.regime_bayes import fit, Snapshot, group_key, bind_snapshot, code_seal
from htx_trend.research.store import digest
from test_regime import setup, NOW

COST = 'ASSUMPTION:fee4bp-each-no-funding'


def labels(n=24):
    return [dict(opportunity_id=str(i),contract='ETH-USDT',scenario='A',timing='WINDOW',
        rule='REGIME_AB_V1',exit_profile='ATR_1_1',cost_id=COST,cost_status='ASSUMPTION',
        entry_ms=i*3600000,exit_ms=i*3600000+1000,observed_ms=i*3600000+1000,
        status='CLOSED',net_return='.003' if i%6 else '-.001',source_refs=['source:'+str(i)],exposure='SEEN',
        label_code_seal=code_seal(),label_protocol_hash='fixture-protocol') for i in range(n)]


def test_analytic_posterior_and_deterministic_recomputation():
    rows=labels();a=fit(rows,cutoff_ms=NOW,cost_id=COST)
    assert a==fit(list(reversed(rows)),cutoff_ms=NOW,cost_id=COST)
    g=a['groups'][group_key('A','WINDOW','ATR_1_1',COST)]
    assert g['posterior']==[21,5] and g['posterior_mean']==21/26
    assert g['eligible'] and g['blocks']['60']['count']==24
    assert g['posterior_ci95'][0] < 21/26 < g['posterior_ci95'][1]
    assert g['blocks']['60']['positive_mean_mass']>.95


def test_duplicate_overlap_maturity_unknown_and_protected():
    rows=labels()
    extra=[rows[0],{**rows[1],'opportunity_id':'overlap','entry_ms':3600001},
           {**rows[2],'opportunity_id':'future','observed_ms':NOW+1},
           {**rows[3],'opportunity_id':'cost','cost_status':'UNKNOWN'},
           {**rows[4],'opportunity_id':'open','status':'OPEN'}]
    a=fit(rows+extra,cutoff_ms=NOW,cost_id=COST)
    assert a['groups'][group_key('A','WINDOW','ATR_1_1',COST)]['n']==24
    assert a['excluded']=={'duplicate':1,'IMMATURE_AT_CUTOFF':1,'UNKNOWN_COST':1,'OPEN':1,'OVERLAPPING_SAME_CONTRACT':1}
    with pytest.raises(ValueError,match='conflicting'):fit(rows+[{**rows[0],'net_return':'1'}],cutoff_ms=NOW,cost_id=COST)
    with pytest.raises(ValueError,match='unseen'):fit([{**rows[0],'exposure':'UNSEEN'}],cutoff_ms=NOW,cost_id=COST)
    with pytest.raises(ValueError,match='implementation'):fit([{**rows[0],'label_code_seal':'old'}],cutoff_ms=NOW,cost_id=COST)


def test_gate_missing_binding_time_and_own_group(tmp_path):
    value=fit(labels(),cutoff_ms=NOW-1,cost_id=COST)
    p,l,e,f,ev=setup(tmp_path,bayes_threshold=D('.95'),bayes_snapshot_hash=value['hash'],bayes_cost_id=COST)
    assert not e.assessment('A',NOW)['allowed']
    e.snapshot=Snapshot(value)
    assert e.assessment('A',NOW)['allowed']
    assert not e.assessment('B',NOW)['allowed']
    assert not e.assessment('A',NOW-2)['allowed']
    assert not e.snapshot.assess('A',replace(p,bayes_cost_id='different'),NOW)['allowed']
    assert len(e.accept(ev,now_ms=NOW+1))==1
    l.close()


def test_insufficient_all_positive_does_not_pass(tmp_path):
    rows=[{**r,'net_return':'.01'} for r in labels(2)]
    value=fit(rows,cutoff_ms=NOW,cost_id=COST)
    p,l,e,f,ev=setup(tmp_path,bayes_threshold=D('.8'),bayes_snapshot_hash=value['hash'],bayes_cost_id=COST)
    e.snapshot=Snapshot(value)
    assert not e.accept(ev,now_ms=NOW+1)
    row=next(iter(e.rows.values()))
    assert row['reason']=='BAYES:INSUFFICIENT_EVIDENCE' and row['trigger']
    l.close()


def test_snapshot_tamper_and_live_assumptions(tmp_path):
    value=fit(labels(),cutoff_ms=NOW,cost_id=COST)
    with pytest.raises(ValueError,match='hash'):Snapshot({**value,'cutoff_ms':0})
    data={**value,'code_seal':'changed'};data.pop('hash');data['hash']=digest(data)
    with pytest.raises(ValueError,match='code'):Snapshot(data)
    p,l,e,f,ev=setup(tmp_path,bayes_threshold=D('.9'),bayes_snapshot_hash=value['hash'],bayes_cost_id=COST)
    path=tmp_path/'snapshot.json';path.write_text(json.dumps(value))
    bind_snapshot(e,path,live=True);assert e.snapshot is None
    bind_snapshot(e,path,live=False);assert e.snapshot is not None
    l.close()
