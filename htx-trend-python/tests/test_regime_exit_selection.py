"""Pre-entry posterior selection, real execution and immutable bracket ownership."""
from dataclasses import replace
from decimal import Decimal as D
import json
import socket
import tarfile

import pytest

from htx_trend.realtime.plans import Parameters, BAYES_EXIT, create_engine
from htx_trend.realtime.regime_bayes import fit, Snapshot, group_key
from htx_trend.realtime.indicator_union import EXITS
from htx_trend.realtime.storage import PaperPlanStore
from htx_trend.realtime.regime_replay import account_replay, account_matrix, Paths
from htx_trend.realtime.brokers import initial_context
from htx_trend.research.store import digest
from htx_trend.market_data import OrderBook
from htx_trend.holding_policy import HoldingPolicy, supports_observed_implementation
from test_regime_bayes import labels, COST
from test_regime import setup, NOW, policy
from test_regime_net_reward import check
from test_regime_release import package_module
from test_regime_replay import observations, protocol
from test_realtime_replay import tape


def snapshot_for(mode='ATR_2_2', scenario='A'):
    return fit([{**r, 'scenario':scenario, 'exit_profile':mode} for r in labels()],
               cutoff_ms=NOW-1, cost_id=COST)


def params(value, **kw):
    return Parameters(candidate='REGIME_AB_V1', min_net_r=D(0), target_mode=BAYES_EXIT,
        bayes_threshold=D('.90'), bayes_snapshot_hash=value['hash'], bayes_cost_id=COST, **kw)


def test_positive_expectation_before_return_before_win_probability():
    rows=[]
    for r in labels():
        # 1_1 wins 20/24 but loses overall; 2_1 wins 22/24, 2_2 only 20/24
        # yet has the stronger mean net payoff after passing the probability gate.
        i=int(r['opportunity_id'])
        for mode, net in [('ATR_1_1', '.001' if i%6 else '-.03'),
                          ('ATR_2_1', '.002' if i%12 else '-.001'),
                          ('ATR_2_2', '.005' if i%6 else '-.001')]:
            rows.append({**r,'exit_profile':mode,'net_return':net})
    v=fit(rows,cutoff_ms=NOW-1,cost_id=COST)
    decision=Snapshot(v).select_exit('A',params(v),NOW)
    assert decision['selected_exit']=='ATR_2_2'
    assert decision['ranked_exits']==['ATR_2_2','ATR_2_1']
    assert not decision['assessments']['ATR_1_1']['allowed']
    assert D(decision['assessments']['ATR_2_2']['posterior_mean'])<D(decision['assessments']['ATR_2_1']['posterior_mean'])
    # Paired exit labels remain 24 per group; never a fake 72-trade posterior.
    assert all(g['n']==24 for g in decision['assessments'].values())


def test_tie_break_and_nonpositive_mean_floor():
    v=fit([{**r,'exit_profile':mode} for r in labels() for mode in EXITS],cutoff_ms=NOW-1,cost_id=COST)
    snap=Snapshot(v)
    assert snap.select_exit('A',params(v),NOW)['selected_exit']=='ATR_1_1'
    # Defensive consistency check for an externally supplied valid-hash snapshot.
    for g in snap.value['groups'].values():
        if g['n']:g['mean_net_return']=0
    d=snap.select_exit('A',params(v),NOW)
    assert d['selected_exit'] is None and not d['allowed']


@pytest.mark.parametrize('mode',EXITS)
@pytest.mark.parametrize('scenario',['A','B'])
def test_selected_geometry_actual_intent_and_two_confirmations(tmp_path,mode,scenario,monkeypatch):
    v=snapshot_for(mode,scenario)
    p,l,e,f,ev=setup(tmp_path,scenario,mode=BAYES_EXIT,bayes_threshold=D('.9'),bayes_snapshot_hash=v['hash'],bayes_cost_id=COST)
    try:
        e.snapshot=Snapshot(v)
        t,=e.accept(ev,now_ms=NOW+1)
        assert t.plan.target_kind==mode and t.plan.parameter_fingerprint==p.fingerprint
        original=e.rows[t.plan.plan_id]['bayes_assessment']
        monkeypatch.setattr(e.snapshot,'select_exit',lambda *_:pytest.fail('preflight reselected exit'))
        book=OrderBook(((ev.bid,D(100000)),),((ev.ask,D(100000)),))
        assert e.confirm(t,book,NOW+1) and e.confirm(t,book,NOW+2)
        gate,intent=check(t,p,f,ev,'.0004')
        assert gate.allowed and intent.exit_policy_ref=='INDICATOR_BRACKET_V1/'+mode
        reject,none=check(t,p,f,ev,'.004')
        assert not reject.allowed and reject.stage=='NET_REWARD' and none is None
        assert e.rows[t.plan.plan_id]['bayes_assessment']==original
        assert not e.confirm(replace(t,plan=replace(t.plan,target=t.plan.target+1)),book,NOW+2)
        assert not e.accept(replace(ev,event_id='again',received_ms=NOW+3,exchange_ms=NOW+3),now_ms=NOW+3)
        # Snapshot rebinding cannot make a second same-bar opportunity on restart.
        restored=create_engine(PaperPlanStore(l),'p',parameters=replace(p,bayes_snapshot_hash='different'))
        restored.refresh_union_frame(f,now_ms=NOW+4)
        restored.accept(replace(ev,event_id='seed',received_ms=NOW+4,exchange_ms=NOW+4),now_ms=NOW+4)
        assert not restored.accept(replace(ev,event_id='again',received_ms=NOW+5,exchange_ms=NOW+5),now_ms=NOW+5)
    finally:l.close()


@pytest.mark.parametrize('cause',['missing','future','cost','insufficient','wrong_hash','negative'])
def test_no_fallback_or_submission_on_missing_evidence(tmp_path,cause):
    rows=labels(2 if cause=='insufficient' else 24)
    if cause=='negative':rows=[{**r,'net_return':'-.01'} for r in rows]
    v=fit(rows,cutoff_ms=NOW+100 if cause=='future' else NOW-1,cost_id=COST)
    p,l,e,f,ev=setup(tmp_path,mode=BAYES_EXIT,bayes_threshold=D('.9'),
        bayes_snapshot_hash='wrong' if cause=='wrong_hash' else v['hash'],
        bayes_cost_id='different' if cause=='cost' else COST)
    try:
        if cause!='missing':e.snapshot=Snapshot(v)
        assert not e.accept(ev,now_ms=NOW+1)
        row=next(iter(e.rows.values()))
        assert row['phase']=='REJECTED' and row['plan']['target_kind']=='UNSELECTED'
        assert not row['bayes_assessment']['allowed'] and not l.data['orders']
    finally:l.close()


def test_dynamic_requires_explicit_threshold_and_fixed_fingerprint_unchanged():
    with pytest.raises(ValueError,match='threshold'):
        Parameters(candidate='REGIME_AB_V1',min_net_r=D(0),target_mode=BAYES_EXIT)
    p=Parameters(candidate='REGIME_AB_V1',min_net_r=D(0),target_mode='ATR_1_1')
    assert p.payload==dict(band_atr=D('.1'),min_net_r=D(0),max_age_ms=2000,
        candidate='REGIME_AB_V1',target_mode='ATR_1_1',regime_timing='WINDOW',
        bayes_threshold=None,bayes_snapshot_hash='',bayes_cost_id='')


def test_exact_fixed_ab_predecessor_retained():
    from htx_trend.realtime.regime_compatibility import FIXED_REGIME_PREDECESSOR
    v=policy().to_dict();v['implementation_hashes']=dict(FIXED_REGIME_PREDECESSOR);v.pop('hash');v['hash']=digest(v)
    assert supports_observed_implementation(FIXED_REGIME_PREDECESSOR)
    assert HoldingPolicy.load(v).to_dict()==v
    bad={**FIXED_REGIME_PREDECESSOR,'realtime/regime.py':'0'*64}
    assert not supports_observed_implementation(bad)


def account_tape():
    t=tape();values=[z['event'] for z in observations()]
    obs=[values[0],dict(kind='FUNDING',received_ms=NOW,contract='ETH-USDT',rate='0'),dict(kind='CONTEXT',received_ms=NOW,value={})]
    for v in values[1:]:
        obs.append(dict(kind='DEPTH',received_ms=v['received_ms'],requested_ms=v['received_ms'],contract='ETH-USDT',bids=[[v['bid'],'100000']],asks=[[v['ask'],'100000']]))
        obs.append(v)
    t.update(start_ms=NOW,end_ms=NOW+400,observations=obs)
    t['configuration']['worker']['equity']='777';t['configuration']['execution']['account_equity_usdt']='777'
    t['configuration']['execution']['min_average_turnover']='0';t['configuration_hash']=digest(t['configuration'])
    return t


def test_actual_worker_selects_once_opens_and_closes(monkeypatch):
    v=snapshot_for();t=account_tape()
    monkeypatch.setattr(socket.socket,'connect',lambda *_a,**_k:pytest.fail('network used'))
    original=Snapshot.select_exit;calls=[]
    def select(self,*args):
        calls.append(args);return original(self,*args)
    monkeypatch.setattr(Snapshot,'select_exit',select)
    result=account_replay(t,params(v),v)
    assert not result['errors'] and len(result['fills'])==2
    assert len(calls)==1
    assert result['positions'][0]['status']=='CLOSED'
    row=next(r for r in result['plans'].values() if r['phase']=='FILLED')
    assert row['plan']['target_kind']=='ATR_2_2' and row['bayes_assessment']['selected_exit']=='ATR_2_2'
    matrix=account_matrix(t,params(v),v,exit_selection=True)
    assert len(matrix['results'])==21
    assert all(len(matrix['results']['AB_SELECT_'+q+'/BAYES_NET_V1']['fills'])==2 for q in ('.80','.90','.95'))


def test_selected_exit_identity_is_owned_by_holding_context(tmp_path):
    v=snapshot_for();p,l,e,f,ev=setup(tmp_path,mode=BAYES_EXIT,bayes_threshold=D('.9'),
        bayes_snapshot_hash=v['hash'],bayes_cost_id=COST)
    try:
        e.snapshot=Snapshot(v)
        trigger,=e.accept(ev,now_ms=NOW+1)
        gate,intent=check(trigger,p,f,ev,'.0004')
        assert gate.allowed
        context=initial_context(intent,trigger,f['contract'],policy().to_dict())
        decision=e.rows[trigger.plan.plan_id]['bayes_assessment']
        assert context['exit_policy_ref']=='INDICATOR_BRACKET_V1/ATR_2_2'
        assert context['entry_target_rule']=='ATR_2_2'
        assert context['exit_selection_policy']==BAYES_EXIT
        assert context['exit_selection_evidence_hash']==digest(decision)
    finally:l.close()


def test_quote_selector_is_prospective_and_resumes_same_decision():
    v=snapshot_for();pr={**protocol(),'cost_id':COST,'exit_selection':True}
    a=Paths(pr,Snapshot(v));b=Paths(pr,Snapshot(v));values=observations()
    for z in values:a.step(z)
    for z in values[:3]:b.step(z)
    restored=Paths(pr,Snapshot(v));restored.restore(json.loads(json.dumps(b.dump())))
    for z in values[3:]:restored.step(z)
    assert a.dump()==restored.dump()
    selected=[r for r in a.rows if r['policy'].startswith('AB_SELECT_')]
    assert len(selected)==3 and all(r['exit_profile']=='ATR_2_2' and r['status']=='CLOSED' for r in selected)
    missing=Paths(pr)
    for z in values:missing.step(z)
    assert all(r['status']=='REJECTED' and r['exit_profile'] is None for r in missing.rows if r['policy'].startswith('AB_SELECT_'))


@pytest.mark.parametrize('live',[False,True])
def test_selector_package_keeps_concrete_exits_separate_and_disabled(tmp_path,live):
    v=snapshot_for();path=tmp_path/'snapshot.json';path.write_text(json.dumps(v))
    m=package_module();archive=(m.build_live if live else m.build)(tmp_path/'pkg',regime_exit=BAYES_EXIT,regime_snapshot=path,regime_threshold='.90')
    m.verify(archive)
    with tarfile.open(archive) as tar:c=json.load(tar.extractfile('release/config.example.json'))
    assert c['exit_policy']==BAYES_EXIT and c['entry_enabled'] is False
    assert c['runtime_env_required']['HTX_REGIME_EXIT']==BAYES_EXIT


@pytest.mark.parametrize('scenario',['A','B'])
@pytest.mark.parametrize('mode',EXITS)
@pytest.mark.parametrize('cause',['target','stop'])
def test_selected_partial_fill_restart_management(tmp_path,monkeypatch,scenario,mode,cause):
    import test_regime as existing
    old_setup=existing.setup
    v=snapshot_for(mode,scenario)
    def selected_setup(path,scenario,ignored_mode):
        p,l,e,f,ev=old_setup(path,scenario,BAYES_EXIT,bayes_threshold=D('.90'),bayes_snapshot_hash=v['hash'],bayes_cost_id=COST)
        e.snapshot=Snapshot(v)
        return p,l,e,f,ev
    monkeypatch.setattr(existing,'setup',selected_setup)
    existing.test_ioc_partial_restart_and_frozen_bracket(tmp_path,scenario,mode,cause)
