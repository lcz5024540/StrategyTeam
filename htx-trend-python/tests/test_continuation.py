from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal as D
from types import SimpleNamespace as NS
import pytest
from test_exits import base
from test_paper_worker import Market, instance
from htx_trend.continuation import (BASELINE, CONFIRM, LINKED, ATR_REASON, CONFIRMED_REASON,
    ContinuationConfig, advance_confirmation, decide_continuation, reentry_gate, latest_completed)
from htx_trend.exits import ExitAction, decide
from htx_trend.market_data import Candle, TopOfBook
from htx_trend.models import TrendSide
from htx_trend.paper_worker import PaperTrendWorker, PaperWorkerConfig
T = int(datetime(2026,1,1,tzinfo=UTC).timestamp())


def inp(short=False, **kwargs):
    v=dict(reference_price=D('102'),highest_price=D('110'),protection_price=D('103'),
        trailing_armed_at=datetime.fromtimestamp(T,UTC),trailing_arm_reason='RISK_REWARD_MILESTONE')
    if short:v.update(side=TrendSide.SHORT,signal_side=TrendSide.SHORT,reference_price=D('98'),
        lowest_price=D('90'),highest_price=D('101'),protection_price=D('97'),initial_stop_price=D('105'),
        invalidation_price=D('106'),structure_target_price=D('90'),ema_fast=D('95'),ema_slow=D('98'))
    return base(**(v|kwargs))


def bar(at,price):return Candle(at,D(price),D(price)+1,D(price)-1,D(price))


@pytest.mark.parametrize('short',[False,True])
def test_confirmation_quote_and_1000_duplicate_polls(short):
    v=inp(short);c=bar(T+900,'98' if short else '102')
    assert decide(v).reason==ATR_REASON
    n,last=advance_confirmation(v,c,{})
    assert n==1 and decide_continuation(v,n).action is ExitAction.ARM_TRAILING
    state={'continuation_breach_count':n,'continuation_last_bar':last}
    for _ in range(1000):assert advance_confirmation(v,c,state)==(1,T+900)
    n,last=advance_confirmation(v,replace(c,open_time=T+1800),state)
    assert n==2 and decide_continuation(v,n).reason==CONFIRMED_REASON
    assert decide_continuation(replace(v,reference_price=D('96') if short else D('104')),n).action is not ExitAction.FULL_CLOSE


@pytest.mark.parametrize('offset,price,expected',[(1800,'104',0),(2700,'102',1),(900,'102',1)])
def test_recovery_gap_duplicate(offset,price,expected):
    assert advance_confirmation(inp(),bar(T+offset,price),{'continuation_last_bar':T+900,'continuation_breach_count':1})[0]==expected


def test_new_protection_and_prearm_candle_cannot_retroactively_confirm():
    v=inp(protection_price=None)
    assert advance_confirmation(v,bar(T+900,'102'),{})[0]==0
    assert decide_continuation(v,0).action is not ExitAction.FULL_CLOSE
    assert advance_confirmation(inp(trailing_armed_at=datetime.fromtimestamp(T+901,UTC)),bar(T+900,'102'),{})[0]==0


@pytest.mark.parametrize('kwargs',[dict(reference_price=D('94')),dict(signal_side=TrendSide.SHORT),
    dict(signal_side=TrendSide.FLAT,ema_fast=D('99'),ema_slow=D('102'),current_net_pnl=D('-1'))])
def test_original_priority(kwargs):
    v=inp(**kwargs)
    assert decide_continuation(v,0)==decide(v)
    assert decide(v).action is ExitAction.FULL_CLOSE


@pytest.mark.parametrize('short',[False,True])
def test_reentry_price_time_evidence_direction(short):
    side=TrendSide.SHORT if short else TrendSide.LONG;cand=NS(side=side,bar_time=T+900)
    prev=dict(side=side.value,reason=ATR_REASON,protection='100',bar_high='103',bar_low='97',bar_time=T)
    price='96' if short else '104';c=bar(T+900,price);book=TopOfBook(D(price)-D('.1'),D(price)+D('.1'))
    assert reentry_gate(cand,c,book,prev) is None
    assert reentry_gate(NS(side=side,bar_time=T),c,book,prev).stage=='REENTRY_NEW_PRICE_REQUIRED'
    assert reentry_gate(cand,c,TopOfBook(D('99'),D('101')),prev).stage=='REENTRY_NEW_PRICE_REQUIRED'
    assert reentry_gate(cand,c,book,{'side':side.value,'reason':ATR_REASON}).stage=='REENTRY_EVIDENCE_MISSING'
    assert reentry_gate(cand,c,book,dict(prev,side='LONG' if short else 'SHORT')) is None
    assert reentry_gate(cand,c,book,dict(prev,reason='model-or-initial-stop-invalidated')) is None


def opened(w,version=LINKED):
    p=w.exchange.open_position(w.account,'BTC-USDT','LONG',2,D('100'),datetime.fromtimestamp(T,UTC),'open')
    w.ledger.save_position_context(p.position_id,dict(model='EMA_MOMENTUM_CONTINUATION',contract_size='1',
        initial_stop='95',invalidation='94',structure_target='120',entry_fee='.08',highest='110',lowest='99',
        protection='103',trailing_armed_at=datetime.fromtimestamp(T,UTC).isoformat(),
        trailing_arm_reason='RISK_REWARD_MILESTONE',max_favorable_r='2',
        **({'continuation_version':version} if version else {})))
    return p


def manage(w,p,at):
    return w.runtime.manage(run_id=str(at),position_id=p.position_id,reference_price=D('102'),
        highest_price=D('110'),lowest_price=D('99'),atr=D('2'),signal_side=TrendSide.LONG,
        ema_fast=D('105'),ema_slow=D('102'),major_timeframe_opposition=0,market_context_opposes=False,
        at=datetime.fromtimestamp(at+910,UTC),evaluation_candle=bar(at,'102'))


def test_restart_disable_and_fee_recovery(tmp_path):
    cfg=PaperWorkerConfig(contracts=('BTC-USDT',))
    w=PaperTrendWorker(instance(),Market(),tmp_path,cfg,continuation_config=ContinuationConfig(LINKED));p=opened(w)
    assert manage(w,p,T+900).action is ExitAction.ARM_TRAILING
    for _ in range(10):assert manage(w,p,T+900).reason=='continuation-duplicate-bar'
    assert not w.ledger.data['fills']
    w.ledger.checkpoint();w.stop()
    w=PaperTrendWorker(instance(),Market(),tmp_path,cfg,continuation_config=ContinuationConfig())
    assert manage(w,p,T+1800).reason==CONFIRMED_REASON
    assert len(w.ledger.data['fills'])==1
    prior,prev=latest_completed(w.ledger,w.account,'BTC-USDT')
    assert prev['bar_time']==T+1800 and prev['reason']==CONFIRMED_REASON
    assert prior.net_profit==D('4')-D('102')*2*D('.0004')-D('.08')
    w.stop()


def test_old_position_does_not_change_with_entry_switch(tmp_path):
    w=PaperTrendWorker(instance(),Market(),tmp_path,PaperWorkerConfig(),continuation_config=ContinuationConfig(LINKED))
    p=opened(w,None);assert manage(w,p,T+900).reason==ATR_REASON;w.stop()


def test_partial_exit_and_account_isolation(tmp_path):
    w=PaperTrendWorker(instance(),Market(),tmp_path,PaperWorkerConfig());p=opened(w)
    w.exchange.reduce_position(p,quantity=1,exit_price=D('101'),contract_size=D('1'),when=datetime.fromtimestamp(T+900,UTC),run_id='partial')
    assert latest_completed(w.ledger,w.account,'BTC-USDT')==(None,None)
    w.exchange.reduce_position(w.ledger.position(p.position_id),quantity=1,exit_price=D('99'),contract_size=D('1'),when=datetime.fromtimestamp(T+1800,UTC),run_id='full')
    prior,prev=latest_completed(w.ledger,w.account,'BTC-USDT')
    assert prior.net_profit==D('-.16')
    assert prev['reason'] is None
    assert latest_completed(w.ledger,'other','BTC-USDT')==(None,None)
    w.stop()


@pytest.mark.parametrize('version,cooldown',[(LINKED,True),(CONFIRM,True),('typo',False)])
def test_invalid_config(version,cooldown):
    with pytest.raises(ValueError):ContinuationConfig(version,cooldown)


def test_4h_rejected_before_ledger_open(tmp_path):
    inst=instance();inst.strategy_name='HTX_TREND_4H_V2'
    with pytest.raises(ValueError,match='15m PAPER'):
        PaperTrendWorker(inst,Market(),tmp_path,PaperWorkerConfig(),continuation_config=ContinuationConfig(LINKED))
    assert not list(tmp_path.iterdir())


def test_cas_recovery_is_verified_and_missing_data_fails_closed(tmp_path):
    from test_compact_paper import EvidenceMarket
    from htx_trend.research.store import FileEvidenceStore
    store=FileEvidenceStore(tmp_path/'cas',compress=True)
    market=EvidenceMarket(store)
    w=PaperTrendWorker(instance(),market,tmp_path/'state',PaperWorkerConfig(),store)
    p=opened(w,None)
    windows=tuple(market.kline_window('BTC-USDT',period,160,now_epoch=T+2700) for period in ('15min','60min'))
    w.runtime.manage(run_id='close',position_id=p.position_id,reference_price=D('102'),highest_price=D('110'),
        lowest_price=D('99'),atr=D('2'),signal_side=TrendSide.LONG,ema_fast=D('105'),ema_slow=D('102'),
        major_timeframe_opposition=0,market_context_opposes=False,at=datetime.fromtimestamp(T+2700,UTC),
        evidence_windows=windows)
    prior,missing=latest_completed(w.ledger,w.account,'BTC-USDT')
    assert missing['evidence_status']=='MISSING'
    prior,recovered=latest_completed(w.ledger,w.account,'BTC-USDT',store)
    assert recovered['evidence_status']=='RECOVERED' and recovered['bar_time']==windows[0].candles[-1].open_time
    assert w.ledger.position_context(p.position_id)['complete_exit']==recovered
    w.stop();store.close()


@pytest.mark.parametrize('failure',['exit_evidence','checkpoint'])
def test_candidate_fault_management_checkpoint_and_retry(tmp_path,monkeypatch,failure):
    from test_compact_paper import EvidenceMarket, open_position
    from htx_trend.research.store import FileEvidenceStore
    store=FileEvidenceStore(tmp_path/'cas',compress=True)
    w=PaperTrendWorker(instance(),EvidenceMarket(store),tmp_path/'state',PaperWorkerConfig(),store,
        continuation_config=ContinuationConfig(LINKED))
    p=open_position(w);context=w.ledger.position_context(p.position_id)
    context['continuation_version']=LINKED;w.ledger.save_position_context(p.position_id,context)
    w.ledger.checkpoint()
    if failure=='exit_evidence':
        original=store.put
        def fail(kind,*a,**kw):
            if kind=='compact_exit_decision':raise OSError('index unavailable')
            return original(kind,*a,**kw)
        monkeypatch.setattr(store,'put',fail)
        assert w.tick().status=='DEGRADED'
        assert w.ledger.position(p.position_id).status=='CLOSED'
        assert len(w.ledger.data['fills'])==1
        w.tick();assert len(w.ledger.data['fills'])==1
    else:
        original=w.ledger.checkpoint
        monkeypatch.setattr(w.ledger,'checkpoint',lambda: (_ for _ in ()).throw(OSError('ledger full')))
        with pytest.raises(OSError):w.tick()
        assert w._checkpoint_failed and len(w.ledger.data['fills'])==1
        with pytest.raises(OSError):w.tick()
        assert len(w.ledger.data['fills'])==1
        monkeypatch.setattr(w.ledger,'checkpoint',original)
        assert w.tick().status=='DEGRADED'
        assert w.ledger.position(p.position_id).status=='CLOSED'
    w.stop();store.close()
