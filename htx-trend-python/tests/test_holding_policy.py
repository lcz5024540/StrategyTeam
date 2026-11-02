from dataclasses import replace
from decimal import Decimal as D
import json

import pytest

from htx_trend.continuation import BASELINE, CONFIRM
from htx_trend.exit_config import ExitConfig
from htx_trend.holding_policy import (HoldingPolicy, HoldingPolicyError, digest,
                                      paper_exit_config, resolve_holding_policy)
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.timeframes import TimeframeProfile


def policy(**overrides):
    return HoldingPolicy.create(**(dict(instance_key='p', account_id=7, strategy_family='TREND',
        strategy_name='HTX_TREND_V1', mode='LIVE', profile=TimeframeProfile.INTRADAY_15M,
        entry_config=JavaEntryConfig(), exit_config=ExitConfig(engine_enabled=True, engine_live=True)) | overrides))


def reseal(value):
    value = {k: v for k, v in value.items() if k != 'hash'}
    return {**value, 'hash': digest(value)}


def test_snapshot_is_complete_stable_and_cannot_be_mutated_by_caller():
    p = policy()
    restored = HoldingPolicy.load(json.loads(json.dumps(p.to_dict())))
    assert restored.exit == p.exit and restored.entry == p.entry
    copy = p.to_dict()
    copy['exit']['policy']['arm_atr_multiplier'] = '100'
    assert p.exit.policy.arm_atr_multiplier == D('.5')
    assert policy().encoded == p.encoded


@pytest.mark.parametrize('change', ['hash','parameter','implementation','extra','missing','owner'])
def test_corruption_incompleteness_and_unsupported_code_are_rejected(change):
    value = policy().to_dict()
    if change == 'hash':
        value['hash'] = '0'*64
    elif change == 'parameter':
        value['exit']['fee_rate'] = '.9'
    elif change == 'implementation':
        value['implementation_hashes']['exits.py'] = '0'*64
        value = reseal(value)
    elif change == 'extra':
        value['exit']['unknown'] = True
        value = reseal(value)
    elif change == 'missing':
        del value['entry']['slow_ema_period']
        value = reseal(value)
    else:
        del value['owner']['account_id']
        value = reseal(value)
    with pytest.raises(HoldingPolicyError):
        HoldingPolicy.load(value)


@pytest.mark.parametrize('field,value', [('fee_rate','NaN'),('flat_bars',True),('engine_live','false')])
def test_invalid_types_are_not_coerced_into_a_different_rule(field, value):
    data=policy().to_dict(); data['exit'][field]=value
    with pytest.raises(HoldingPolicyError):
        HoldingPolicy.load(reseal(data))


def test_account_profile_strategy_instance_and_mode_binding():
    p=policy()
    args=dict(instance_key='p', account_id=7, strategy_family='TREND', strategy_name='HTX_TREND_V1',
              mode='LIVE',profile=TimeframeProfile.INTRADAY_15M)
    p.require_owner(**args)
    for field,value in [('instance_key','other'),('account_id',2),('strategy_name','NEW'),
                        ('strategy_family','CARRY'),('mode','PAPER'),('profile',TimeframeProfile.SWING_4H)]:
        with pytest.raises(HoldingPolicyError,match='ownership'):
            p.require_owner(**(args | {field:value}))


def test_missing_legacy_policy_cannot_be_filled_from_current_configuration():
    assert resolve_holding_policy({}) is None  # offline legacy compatibility only
    with pytest.raises(HoldingPolicyError,match='adoption'):
        resolve_holding_policy({},required=True)
    with pytest.raises(HoldingPolicyError,match='continuation'):
        resolve_holding_policy({'holding_policy':policy().to_dict(),'continuation_version':CONFIRM})


def test_paper_freezes_its_actual_defaults_not_live_environment(monkeypatch):
    monkeypatch.setenv('HTX_TREND_A2_TIMEOUT_BARS','99')
    monkeypatch.setenv('HTX_TREND_INTRADAY_PROFIT_ARM_ATR_MULTIPLIER','7')
    config=paper_exit_config()
    assert config.timeout_bars is None and config.min_hold_bars is None
    assert config.flat_budget == 0 and config.flat_budget_rate == 0
    assert config.policy.arm_atr_multiplier == D('.5') and config.fee_rate == D('.0004')
    assert config.engine_enabled and config.engine_live


def test_live_restart_uses_original_exit_and_indicator_parameters(monkeypatch):
    from test_position_manager import Repo, Market, manager, position
    from htx_trend.strategy_context import MarketContext
    from htx_trend.exits import decide_v4
    from htx_trend.java_entry_contract import evaluate
    old_exit=replace(ExitConfig(engine_enabled=True,engine_live=True),flat_bars=9,timeout_bars=None)
    old_entry=replace(JavaEntryConfig(),min_adx=D(18))
    original=policy(exit_config=old_exit,entry_config=old_entry)
    repo=Repo();repo.state={'holding_policy':original.to_dict(),'continuation_version':BASELINE}
    market=Market();market.price=D(100)
    inputs=[]; configs=[]
    def capture_input(value):
        inputs.append(value)
        return decide_v4(value)
    def capture_signal(candles,config):
        configs.append(config)
        return evaluate(candles,config)
    monkeypatch.setattr('htx_trend.position_manager.decide_v4',capture_input)
    monkeypatch.setattr('htx_trend.position_manager.evaluate',capture_signal)
    new_config=replace(old_exit,flat_bars=1,timeout_bars=1,engine_live=False)
    mgr=manager(repo=repo,market=market,config=new_config)
    mgr.java_config=replace(old_entry,min_adx=D(80))
    mgr.require_holding_policy=True
    mgr._manage(position(),144900,MarketContext())
    assert inputs[0].required_flat_streak==9 and inputs[0].timeout_bars is None
    assert configs[0]==old_entry
    assert repo.state['holding_policy']==original.to_dict()


def test_missing_policy_blocks_normal_management_but_not_initial_stop():
    from test_position_manager import Repo, Market, Executor, manager, position
    from htx_trend.strategy_context import MarketContext
    market=Market();market.price=D(100)
    live=Executor();mgr=manager(repo=Repo(),market=market,executor=live);mgr.require_holding_policy=True
    with pytest.raises(HoldingPolicyError):
        mgr._manage(position(),144900,MarketContext())
    assert not live.sent
    market.price=D(80)
    mgr._manage(position(),144900,MarketContext())
    assert len(live.sent)==1 and live.sent[0].reduce_only


@pytest.mark.parametrize('fail_save',[False,True])
def test_live_policy_is_durable_before_submission_and_failure_prevents_order(monkeypatch,fail_save):
    from test_execution_service import Repository, Executor, executor, candidate
    from htx_trend.market_data import Candle, OrderBook, ContractSpec, FundingRate
    from htx_trend.strategy_context import MarketContext, MarketRegime
    from htx_trend.models import TrendSide
    monkeypatch.setattr('htx_trend.admission.runtime_guard',lambda *args:None)
    repo,live=Repository(),Executor()
    saved={}
    initialized=[]
    def initialize(position_id,state):
        initialized.append(position_id)
        if fail_save:
            raise OSError('ledger unavailable')
        saved.update(state)
    repo.initialize_entry_state=initialize
    original_place=live.place
    def place(request):
        assert resolve_holding_policy(saved,required=True).owner['instance_key']=='python-main'
        return original_place(request)
    live.place=place
    ex=executor(repo,live)
    ex._holding_policies={'INTRADAY_15M':policy(instance_key='python-main')}
    result=ex.submit(run_id='run',candidate=candidate(),period='15min',atr=D(2),ema_fast=D(101),
        ema_slow=D(99),adx=D(30),recent_candles=(Candle(9000,D(99),D(101),D(98),D(100)),),
        book=OrderBook(((D('99.99'),D(500)),),((D('100.01'),D(500)),)),
        contract=ContractSpec('BTC-USDT',D('.01'),D('.1'),1),funding=FundingRate('BTC-USDT',D('.0001')),
        market_context=MarketContext(MarketRegime.BROAD_UP,TrendSide.LONG,D('.8')),now_epoch=10000)
    assert initialized == [7]
    assert result.accepted is not fail_save
    assert len([v for k,v in live.calls if k=='place']) == (0 if fail_save else 1)


def test_verified_adoption_preserves_protection_and_pending_intent():
    from htx_trend.holding_adoption import HoldingAdoption, holding_identity, adopted_state
    row=dict(id=1,position_key='p1',account_id=7,strategy_family='TREND',strategy_name='HTX_TREND_V1',
             contract_code='BTC-USDT',side='LONG',timeframe_profile='INTRADAY_15M')
    p=policy()
    manifest=HoldingAdoption.create(instance_key='p',account_id=7,mode='LIVE',observed_at='2026-11-02T10:00:00+00:00',
        source_commit='a'*40,source_package='old-package',
        holdings=[dict(identity=holding_identity(row,mode='LIVE'),policy=p.to_dict())])
    state=dict(highest='110',protection='103',continuation_breach_count=1,entry_execution={'cancel_at_epoch':123})
    adopted=adopted_state(state,manifest.policy_for(row,state,instance_key='p',mode='LIVE'))
    assert all(adopted[k]==v for k,v in state.items())
    assert adopted_state(adopted,p)==adopted
    with pytest.raises(HoldingPolicyError,match='absent'):
        manifest.policy_for({**row,'id':2},state,instance_key='p',mode='LIVE')
    with pytest.raises(HoldingPolicyError,match='immutable'):
        adopted_state(adopted,policy(exit_config=replace(p.exit,flat_bars=6)))
    with pytest.raises(HoldingPolicyError,match='instance'):
        manifest.policy_for(row,state,instance_key='another',mode='LIVE')


def test_export_uses_observed_old_configuration_and_preserves_already_frozen_policy(monkeypatch):
    from dataclasses import asdict
    from htx_trend.holding_policy import implementation_hashes, canonical
    from htx_trend.tools.holding_export import from_observation
    row=dict(id=1,position_key='p1',account_id=7,strategy_family='TREND',strategy_name='HTX_TREND_V1',
             contract_code='BTC-USDT',side='LONG',timeframe_profile='INTRADAY_15M')
    old=replace(ExitConfig(),flat_bars=8,timeout_bars=31)
    observation=json.loads(canonical(dict(instance_key='p',account_id='7',mode='LIVE',strategy_family='TREND',
        strategy_name='HTX_TREND_V1',observed_at='2026-11-02T10:00:00+00:00',source_commit='a'*40,
        source_package='old',implementation_hashes=implementation_hashes(),
        profiles={'INTRADAY_15M':dict(entry=asdict(JavaEntryConfig()),exit=asdict(old))},
        holdings=[dict(identity=row,state={})])))
    monkeypatch.setenv('HTX_TREND_A2_TIMEOUT_BARS','1')
    manifest=from_observation(observation)
    exported=manifest.policy_for(row,{},instance_key='p',mode='LIVE')
    assert exported.exit==old
    observation['profiles']['INTRADAY_15M']['exit']['timeout_bars']=99
    observation['holdings'][0]['state']['holding_policy']=exported.to_dict()
    assert from_observation(observation).policy_for(row,{},instance_key='p',mode='LIVE').exit==old
    observation['implementation_hashes']={'exits.py':'invalid'}
    with pytest.raises(HoldingPolicyError,match='not supported'):
        from_observation(observation)


@pytest.mark.parametrize('corrupt',[False,True,'model','profile','decimal'])
def test_strict_paper_missing_or_corrupt_policy_keeps_known_price_stop(tmp_path,corrupt):
    from datetime import UTC, datetime
    from pathlib import Path
    from htx_trend.ledger import TrendLedger, Account
    from htx_trend.paper import PaperExchange
    from htx_trend.runtime import PaperTrendRuntime
    from htx_trend.models import TrendSide
    path=tmp_path/'ledger.sqlite'
    TrendLedger.initialize(path,Path(__file__).parents[1]/'sql/schema.sql')
    ledger=TrendLedger(path);ledger.account(Account('main','Main','PAPER'))
    exchange=PaperExchange(ledger);now=datetime(2026,11,2,tzinfo=UTC)
    position=exchange.open_position('main','BTC-USDT','LONG',2,D(100),now,'entry')
    context=dict(model='FIB_PULLBACK_CONTINUATION',contract_size='1',initial_stop='95',invalidation='94',
                 structure_target='110',entry_fee='.08',highest='101',lowest='99',max_favorable_r='0')
    if corrupt:
        context['holding_policy']={'broken':'metadata'}
    if corrupt == 'model':
        context['model']='invalid'
        del context['structure_target']
    if corrupt in ('profile', 'decimal'):
        value = policy(mode='PAPER',exit_config=paper_exit_config()).to_dict()
        if corrupt == 'profile':
            value['owner']['timeframe_profile']='BOGUS'
        else:
            value['exit']['fee_rate']='abc'
        context['holding_policy']=reseal(value)
    ledger.save_position_context(position.position_id,context)
    runtime=PaperTrendRuntime(ledger,exchange,account_id='main',require_holding_policy=True)
    args=dict(run_id='manage',position_id=position.position_id,highest_price=D(101),lowest_price=D(99),atr=D(2),
              signal_side=TrendSide.LONG,ema_fast=D(101),ema_slow=D(99),major_timeframe_opposition=0,
              market_context_opposes=False,at=now)
    with pytest.raises(HoldingPolicyError):
        runtime.manage(reference_price=D(100),**args)
    assert ledger.position(position.position_id).status=='OPEN'
    result=runtime.manage(reference_price=D(93),**args)
    assert result.action.value=='FULL_CLOSE' and ledger.position(position.position_id).status=='CLOSED'
    ledger.close()


def test_old_env_probe_is_literal_and_isolated(tmp_path, monkeypatch):
    from htx_trend.tools import holding_export
    captured = {}
    monkeypatch.setenv('HTX_NEW_ONLY', 'must-not-leak')
    def probe(package, environment, instance):
        captured.update(environment)
        return {'instance': instance}
    monkeypatch.setattr(holding_export, '_probe', probe)
    env = tmp_path / 'old.env'
    env.write_text("HTX_OLD='original value'\nEMPTY=\n# ignored\n")
    assert holding_export.environment_observation(tmp_path, env, 'p') == {'instance':'p'}
    assert captured['HTX_OLD'] == 'original value' and captured['EMPTY'] == ''
    assert 'HTX_NEW_ONLY' not in captured
    for assignment in ('A=$OTHER', 'A=${OTHER}', 'A=$(command)', 'A=`command`', 'A=one two'):
        env.write_text(assignment)
        with pytest.raises(HoldingPolicyError):
            holding_export.environment_observation(tmp_path, env, 'p')


def test_unsupported_paper_engine_is_rejected():
    with pytest.raises(HoldingPolicyError, match='PAPER exit semantics'):
        policy(mode='PAPER', exit_config=ExitConfig(engine_enabled=False))


def test_adoption_repository_locks_and_rolls_back_on_identity_mismatch():
    from htx_trend.mysql_store import HtxTrendMySqlRepository
    from htx_trend.holding_adoption import HoldingAdoption, holding_identity
    row=dict(id=1,position_key='p1',account_id=7,strategy_family='TREND',strategy_name='HTX_TREND_V1',
             contract_code='BTC-USDT',side='LONG',timeframe_profile='INTRADAY_15M')
    manifest=HoldingAdoption.create(instance_key='p',account_id=7,mode='LIVE',observed_at='2026-11-02T10:00:00+00:00',
        source_commit='a'*40,source_package='old',
        holdings=[dict(identity=holding_identity(row,mode='LIVE'),policy=policy().to_dict())])
    from types import SimpleNamespace
    calls=[]
    connection=SimpleNamespace(rollback=lambda:calls.append('rollback'),commit=lambda:calls.append('commit'))
    repo=HtxTrendMySqlRepository(connection)
    def one(sql,params):
        calls.append(sql)
        return row if 'htx_trend_position WHERE' in sql else {'state_json':'{"highest":"110","breach_count":1}'}
    repo._one=one
    repo._write=lambda sql,params:calls.append(('write',json.loads(params[1])))
    restored=repo.adopt_holding_policy(1,manifest,instance_key='p')
    assert restored['highest']=='110' and restored['breach_count']==1
    assert calls[-1]=='commit'
    assert sum('FOR UPDATE' in x for x in calls if isinstance(x,str))==2
    calls.clear()
    with pytest.raises(HoldingPolicyError):
        repo.adopt_holding_policy(1,manifest,instance_key='wrong')
    assert calls[-1]=='rollback' and not any(isinstance(x,tuple) for x in calls)


def test_probe_pid_must_bind_the_requested_worker():
    from htx_trend.tools.holding_export import verify_process_instance
    verify_process_instance(['python','-m','htx_trend.worker','--strategy-instance','p'],'p')
    verify_process_instance(['python','-m','htx_trend.worker','--strategy-instance=p'],'p')
    for cmd in (['python','-m','htx_trend.worker','--strategy-instance','other'],
                ['python','-m','something','--strategy-instance','p'],
                ['python','-m','htx_trend.worker','--strategy-instance','p','--strategy-instance','other']):
        with pytest.raises(HoldingPolicyError,match='PID'):
            verify_process_instance(cmd,'p')


def test_legacy_adaptation_is_exact_and_unknown_control_flow_is_rejected():
    from htx_trend.holding_policy import implementation_hashes, LEGACY_MANAGEMENT, supports_observed_implementation
    legacy = {**implementation_hashes(), **LEGACY_MANAGEMENT}
    assert supports_observed_implementation(legacy)
    assert not supports_observed_implementation({**legacy,'timeframes.py':'changed'})
    assert not supports_observed_implementation({**legacy,'position_manager.py':'changed'})
