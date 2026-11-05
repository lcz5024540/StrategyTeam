from dataclasses import asdict, replace
from decimal import Decimal as D
import gzip
import json
import socket
import pytest

from htx_trend.realtime.regime_replay import Paths, freeze, run, read, file_hash, account_replay
from htx_trend.research.store import primitive, digest
from htx_trend.realtime.plans import Parameters
from test_regime import ab_frame, NOW
from test_realtime_plans import book
from test_realtime_replay import tape


def observations():
    f=ab_frame();windows={p:{'received_ms':NOW,'candles':primitive(w.candles)} for p,w in f['windows'].items()}
    frame=dict(kind='FRAME',contract='ETH-USDT',received_ms=NOW,windows=windows,spec=primitive(f['contract']))
    px=f['entry'].candles[-1].close
    values=[frame,book(NOW,str(px),str(px+D('.001'))).dump(),
        book(NOW+1,str(px+D('.02')),str(px+D('.021'))).dump(),
        book(NOW+300,str(px+D('.02')),str(px+D('.021'))).dump(),
        book(NOW+400,str(px+D(2)),str(px+D('2.001'))).dump()]
    return [dict(event=e,segment='source',index=i) for i,e in enumerate(values)]


def protocol():
    return dict(start_ms=NOW,end_ms=NOW+400,latency_ms=250,fee_rate='.0004',cost_id='ASSUMPTION:4bp',exposure='SEEN')


def test_checkpoint_preserves_pending_and_duplicate_state():
    values=observations();full=Paths(protocol());part=Paths(protocol())
    for z in values:full.step(z)
    for z in values[:3]:part.step(z)
    restored=Paths(protocol());restored.restore(json.loads(json.dumps(part.dump())))
    for z in values[3:]:restored.step(z)
    assert full.dump()==restored.dump()
    rows=[r for r in full.rows if r['policy']=='AB_WINDOW/ATR_1_1']
    assert len(rows)==1 and rows[0]['status']=='CLOSED' and D(rows[0]['net_return'])>0
    assert rows[0]['entry_ms']==NOW+300 and not rows[0]['ioc_fill_verified']


def test_source_seal_resume_full_equivalence_and_mutation(tmp_path):
    source=tmp_path/'source';source.mkdir();p=source/'events.gz'
    with gzip.open(p,'wt') as f:
        for z in observations():f.write(json.dumps(z)+'\n')
    manifest=source/'manifest.json';manifest.write_text(json.dumps({'complete':True,'files':[{'path':p.name,'sha256':file_hash(p)}]}))
    for name in ('partial','full'):
        freeze(manifest,tmp_path/name,**protocol())
    assert run(tmp_path/'partial',max_events=3)['complete'] is False
    a=run(tmp_path/'partial');b=run(tmp_path/'full')
    assert a==b and a['complete']
    assert read(tmp_path/'partial'/'records.json.gz')==read(tmp_path/'full'/'records.json.gz')
    with open(p,'ab') as f:f.write(b'change')
    with pytest.raises(ValueError,match='source changed'):run(tmp_path/'full')


def test_gap_censors_open_not_fake_profit():
    p=Paths(protocol());seq=observations()
    for z in seq[:4]:p.step(z)
    p.step(dict(event={'kind':'GAP','received_ms':NOW+350},segment='gap',index=0))
    p.step(seq[4]);rows=[r for r in p.rows if r['policy']=='AB_WINDOW/ATR_1_1']
    assert rows[0]['status']=='CENSORED' and rows[0]['exit_ms'] is None


def test_account_uses_real_worker_with_explicit_non300_config(monkeypatch):
    t=tape();values=[z['event'] for z in observations()]
    obs=[values[0],dict(kind='FUNDING',received_ms=NOW,contract='ETH-USDT',rate='0'),dict(kind='CONTEXT',received_ms=NOW,value={})]
    for v in values[1:]:
        obs.append(dict(kind='DEPTH',received_ms=v['received_ms'],requested_ms=v['received_ms'],contract='ETH-USDT',bids=[[v['bid'],'100000']],asks=[[v['ask'],'100000']]))
        obs.append(v)
    t.update(start_ms=NOW,end_ms=NOW+400,observations=obs)
    t['configuration']['worker']['equity']='777';t['configuration']['execution']['account_equity_usdt']='777'
    t['configuration']['execution']['min_average_turnover']='0';t['configuration_hash']=digest(t['configuration'])
    monkeypatch.setattr(socket.socket,'connect',lambda *_a,**_k:pytest.fail('network used'))
    result=account_replay(t,Parameters(candidate='REGIME_AB_V1',min_net_r=D(0),target_mode='ATR_1_1'))
    assert result['positions'], result['errors']
    assert result['positions'][0]['status']=='CLOSED'
    assert result['positions'][0]['model']=='RT_REGIME_AB'
    assert len(result['fills'])==2
