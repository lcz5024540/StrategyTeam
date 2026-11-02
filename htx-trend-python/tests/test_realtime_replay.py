from dataclasses import asdict,replace
from datetime import UTC,datetime
from decimal import Decimal as D
import copy
import socket

import pytest
from htx_trend.execution import ExecutionConfig
from htx_trend.exit_config import ExitConfig
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.maker_execution import MakerExecutionConfig
from htx_trend.paper_worker import PaperWorkerConfig
from htx_trend.realtime.plans import GRID
from htx_trend.realtime.replay import Actor,SCHEMA,validate,replay,comparison
from htx_trend.research.store import primitive,digest
from test_realtime_plans import book,trade


def tape():
    start=int(datetime(2026,11,1,12,0,10,tzinfo=UTC).timestamp()*1000)
    worker=PaperWorkerConfig(contracts=('ETH-USDT',))
    execution=replace(ExecutionConfig(),account_equity_usdt=worker.equity,max_total_margin_rate=worker.margin_rate,
        max_active_positions=worker.max_active_positions,max_same_direction_positions=worker.max_active_positions,hard_risk_limits_enabled=True)
    config=primitive({'worker':asdict(worker),'entry':asdict(JavaEntryConfig()),'execution':asdict(execution),
        'maker':asdict(MakerExecutionConfig()),'exit':asdict(ExitConfig()),'baseline_continuation':'linked-v1'})
    windows={}
    for period,step in [('15min',900),('60min',3600),('4hour',14400)]:
        end=(start//1000)//step*step-step
        bars=[]
        for i in range(160):
            price=D(200-i)/2 if period=='60min' else D(100)
            high,low=price+2,price-2
            if period=='15min' and i==141:high,low=D(110),D(78)
            bars.append(dict(open_time=end-(159-i)*step,open=str(price),high=str(high),low=str(low),close=str(price),turnover='1000000'))
        windows[period]={'received_ms':start,'candles':bars}
    observations=[dict(kind='FRAME',received_ms=start,contract='ETH-USDT',windows=windows,
                       spec={'contract_code':'ETH-USDT','contract_size':'.01','price_tick':'.01','contract_status':1}),
                  dict(kind='FUNDING',received_ms=start,contract='ETH-USDT',rate='0'),
                  dict(kind='CONTEXT',received_ms=start,value={})]
    def quote(at,bid,ask):
        observations.append(dict(kind='DEPTH',received_ms=at,requested_ms=at,contract='ETH-USDT',bids=[[bid,'10000']],asks=[[ask,'10000']]))
        observations.append(book(at,bid,ask).dump())
    quote(start+1,'107','107.01')
    observations.append(dict(kind='SCAN',received_ms=start+2))
    for offset,price in [(10,'107'),(20,'110'),(30,'108.9'),(40,'108.8')]:
        quote(start+offset,str(D(price)-D('.01')),price)
        observations.append(trade(start+offset,price).dump())
    quote(start+400,'108.79','108.80')
    quote(start+800,'105','105.01')
    quote(start+3000,'103','103.01')
    quote(start+7000,'102','102.01')
    quote(start+10000,'101','101.01')
    return {'schema':SCHEMA,'provenance':'ASSUMPTION_ONLY','configuration':config,'configuration_hash':digest(config),
            'start_ms':start-1,'end_ms':start+10000,'observations':observations,
            'latency':{'milliseconds':250,'source':'ASSUMPTION'},'funding_status':'UNKNOWN','clock_start_ms':start}


def test_all_opportunities_replay_uses_real_trigger_and_subsequent_book_no_network(monkeypatch):
    data=tape();validate(data)
    monkeypatch.setattr(socket.socket,'connect',lambda *_a,**_k:pytest.fail('replay used network'))
    actor=Actor(data,GRID[0])
    try:
        for row in data['observations']:actor.step(row)
        result=actor.result()
        assert result['plans'] and result['fills']
        assert result['fills'][0]['action']=='OPEN'
        assert result['fills'][0]['model']=='RT_RANGE_REJECTION'
        assert result['fills'][0]['price']=='108.79'
        assert result['funding_paid'] is None
    finally:actor.close()


def test_six_registered_choices_and_no_formal_claim_from_assumptions():
    result=replay(tape())
    assert len(result['selection']['diagnostic'])==6
    assert not result['eligible'] and result['status']=='NOT_ELIGIBLE'
    assert 'LATENCY_ASSUMED' in result['blockers'] and 'FUNDING_UNKNOWN' in result['blockers']
    assert result['selection']['uses_holdout'] is False
    for key,value in result['groups'].items():
        if key not in ('A',result['selected']):
            assert max(datetime.fromisoformat(p['at']).timestamp()*1000 for p in value['curve'])<=result['selection']['split_ms']


@pytest.mark.parametrize('change',['future_window','future_candle','missing_depth','misordered','config'])
def test_bad_or_incomplete_inputs_are_not_silently_repaired(change):
    data=tape()
    if change=='future_window':data['observations'][0]['windows']['15min']['received_ms']=data['end_ms']+1
    elif change=='future_candle':data['observations'][0]['windows']['15min']['candles'][-1]['open_time']=data['end_ms']//1000
    elif change=='missing_depth':data['observations']=[x for x in data['observations'] if x['kind']!='DEPTH']
    elif change=='misordered':data['observations'][4]['received_ms']=data['start_ms']-1
    else:data['configuration']['worker']['equity']='999'
    with pytest.raises((ValueError,KeyError)):validate(data)


def test_acceptance_requires_every_metric_not_only_net_profit():
    a={'net':D(1),'drawdown':D(2),'fees':D('.2'),'roundtrips':3}
    assert comparison(dict(a),a)
    for key,value in [('net',D(0)),('drawdown',D(3)),('fees',D('.3')),('roundtrips',4)]:
        assert not comparison({**a,key:value},a)


def test_holdout_failure_never_falls_back_to_another_diagnostic_candidate(monkeypatch):
    import htx_trend.realtime.replay as module
    data=tape();seen={}
    class FakeActor:
        def __init__(self,tape,parameters=None):
            self.p=parameters;self.times=[];self.tape=tape
            seen[parameters.fingerprint if parameters else 'A']=self.times
        def step(self,row):self.times.append(row['received_ms'])
        def close(self):pass
        def result(self):
            start=self.tape['start_ms'];winner=self.p==GRID[0]
            points=[{'at':module.iso(start),'equity':'300'}]
            for at in self.times:
                diag=at<=start+7001
                points.append({'at':module.iso(at),'equity':str(300+(3 if winner else 1) if diag and self.p else 290 if self.p else 300)})
            return {'curve':points,'fills':[],'errors':[],'decisions':[],'positions':[],'plans':{}}
    monkeypatch.setattr(module,'Actor',FakeActor)
    report=module.replay(data)
    assert report['selected']==GRID[0].fingerprint
    assert 'HOLDOUT_FAILED_NO_RESELECTION' in report['blockers']
    for key,times in seen.items():
        if key not in ('A',report['selected']):assert max(times)<=report['selection']['split_ms']
