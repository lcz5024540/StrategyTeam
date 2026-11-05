from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace

import pytest
from htx_trend.realtime.regime import CANDIDATE, MODEL, regime_features, classify, confirms
from htx_trend.realtime.plans import Parameters, create_engine, StructurePlan
from htx_trend.realtime.storage import PaperPlanStore
from htx_trend.market_data import OrderBook, FundingRate
from htx_trend.execution import ExecutionConfig, ExecutionFacts
from htx_trend.realtime.risk import evaluate
from htx_trend.realtime.brokers import PaperBroker, RealtimePaperExchange
from htx_trend.realtime.management import PaperManagement
from htx_trend.runtime import PaperTrendRuntime
from htx_trend.continuation import ContinuationConfig
from htx_trend.paper_worker import PaperFileLedger
from htx_trend.realtime import VERSION
from htx_trend.holding_policy import HoldingPolicy, digest, paper_exit_config
from htx_trend.java_entry_contract import JavaEntryConfig
from test_indicator_union import frame, policy, NOW
from test_realtime_plans import book


def ab_frame(scenario="A", last=899100):
    f = frame(last)
    for key in ("entry", "trend"):
        w = f[key]
        bars = []
        for i, b in enumerate(w.candles):
            if key == "entry":
                close = D(100) + min(i, 130) * D(".1") + max(0, i-130) * D(".02")
            elif scenario == "A":
                close = D(200) - i * D(".3") + max(0, i-135) * D(".45")
            else:
                close = D(100) + i * D(".1") + i*i * D(".001")
            op = close - D(".05") if scenario == "A" else close + D(".05")
            bars.append(replace(b, open=op, high=max(op,close)+D(".2"), low=min(op,close)-D(".2"), close=close))
        f[key] = replace(w, candles=tuple(bars))
    f["windows"] = {"15min":f["entry"], "60min":f["trend"]}
    f["received_ms"] = NOW
    return f


def setup(tmp_path, scenario="A", mode="ATR_1_1", timing="WINDOW", **kw):
    p = Parameters(min_net_r=D(0),candidate=CANDIDATE,target_mode=mode,regime_timing=timing,**kw)
    ledger = PaperFileLedger(tmp_path/'state.json', {'instance':'p','strategy':VERSION}, mirror=lambda _:None)
    e = create_engine(PaperPlanStore(ledger),'p',parameters=p)
    f = ab_frame(scenario)
    e.refresh_union_frame(f,now_ms=NOW)
    px = f['entry'].candles[-1].close
    e.accept(book(NOW,str(px),str(px+D('.001'))),now_ms=NOW)
    bid = px + (D('.02') if scenario=='A' else D('-.02'))
    ev = book(NOW+1,str(bid),str(bid+D('.001')))
    return p,ledger,e,f,ev


@pytest.mark.parametrize('scenario', ['A','B'])
def test_real_features_and_b_not_mirrored(scenario):
    f = ab_frame(scenario);v=regime_features(f['entry'],f['trend'])
    assert classify(v)==scenario
    assert v['macd']>0 and v['m15_macd']<0
    for name in ('macd','m15_macd'):
        assert classify({**v,name:D(0)}) is None
    assert classify({**v,'h1_ema20':v['h1_ema60']}) is None


@pytest.mark.parametrize('scenario',['A','B'])
@pytest.mark.parametrize('mode',['ATR_1_1','ATR_2_1','ATR_2_2'])
@pytest.mark.parametrize('cause',['target','stop'])
def test_ioc_partial_restart_and_frozen_bracket(tmp_path,scenario,mode,cause):
    params,ledger,engine,f,event=setup(tmp_path,scenario,mode)
    trigger,=engine.accept(event,now_ms=NOW+1)
    assert trigger.plan.model==MODEL and StructurePlan.load(trigger.plan.dump())==trigger.plan
    facts=ExecutionFacts(actual_taker_fee_rate=D('.0004'),account_equity_usdt=D(777),available_margin_usdt=D(777),
        leverage=D(3),active_position_count=0,same_direction_position_count=0,reserved_margin_usdt=D(0),used_margin_usdt=D(0))
    config=replace(ExecutionConfig(),min_average_turnover=D(0),account_equity_usdt=D(777))
    gate,intent=evaluate(trigger,now_ms=NOW+1,book=OrderBook(((event.bid,D(100000)),),((event.ask,D(100000)),)),
        contract=f['contract'],funding=FundingRate('ETH-USDT',D(0)),facts=facts,instance_key='p',parameters=params,config=config)
    assert gate.allowed,gate
    broker=PaperBroker(ledger,engine,'paper:p')
    assert broker.submit(intent,trigger,f['contract'],now_ms=NOW+1,fee_rate=D('.0004'),holding_parameters=policy().to_dict())=='SUBMITTED'
    ids=broker.on_book(replace(event,received_ms=NOW+300,exchange_ms=NOW+300,event_id='fill',bid_size=D(1),ask_size=D(1)),now_ms=NOW+300)
    assert len(ids)==1
    ctx=ledger.position_context(ids[0]);assert ctx['regime_scenario']==scenario
    restart=create_engine(PaperPlanStore(ledger),'p',parameters=replace(params,target_mode='ATR_2_2'))
    assert restart.rows[trigger.plan.plan_id]['phase']=='PARTIAL'
    sign=D(1) if scenario=='A' else D(-1)
    px=(intent.price.structure_target+sign*D('.01')) if cause=='target' else (intent.price.invalidation-sign*D('.01'))
    market=SimpleNamespace(order_book=lambda _:OrderBook(((px,D(100)),),((px+D('.001'),D(100)),)))
    runtime=PaperTrendRuntime(ledger,RealtimePaperExchange(ledger),account_id='paper:p',strategy_version=VERSION,
        continuation_config=ContinuationConfig('linked-v1'),holding_policy=policy(),require_holding_policy=True,
        evidence_context={'instance':'p','timeframe_profile':'INTRADAY_15M'})
    m=PaperManagement(runtime,broker,{},market,exit_config=paper_exit_config(),entry_config=JavaEntryConfig())
    assert m.tick(NOW+400)==[]
    assert not ledger.active_positions('paper:p')
    n=len(ledger.data['fills']);assert m.tick(NOW+500)==[] and len(ledger.data['fills'])==n
    ledger.close()


def test_window_waits_but_first_quote_does_not(tmp_path):
    p,l,e,f,ev=setup(tmp_path)
    assert len(e.accept(ev,now_ms=NOW+1))==1
    assert not e.accept(ev,now_ms=NOW+1)
    e.refresh_union_frame(f,now_ms=NOW+2)
    assert not e.accept(replace(ev,event_id='next',received_ms=NOW+3,exchange_ms=NOW+3),now_ms=NOW+3)
    l.close()
    p,l,e,f,ev=setup(tmp_path/'first',timing='FIRST_QUOTE')
    assert not e.accept(ev,now_ms=NOW+1)
    l.close()


def test_expiry_revision_and_restart(tmp_path):
    p,l,e,f,ev=setup(tmp_path)
    trigger,=e.accept(ev,now_ms=NOW+1)
    depth=OrderBook(((ev.bid,D(10)),),((ev.ask,D(10)),))
    assert e.confirm(trigger,depth,NOW+1)
    e.refresh_union_frame(ab_frame('B'),now_ms=NOW+2)
    assert not e.confirm(trigger,depth,NOW+2)
    e2=create_engine(PaperPlanStore(l),'p',parameters=p)
    assert e2.rows[trigger.plan.plan_id]['phase']=='REJECTED'
    with pytest.raises(ValueError,match='stale'):e.validate_frame('ETH-USDT',NOW+900000)
    l.close()


def test_exact_predecessor_policy_and_tamper():
    from htx_trend.realtime.regime_compatibility import REGIME_PREDECESSOR
    v=policy().to_dict();v['implementation_hashes']=dict(REGIME_PREDECESSOR);v.pop('hash');v['hash']=digest(v)
    assert HoldingPolicy.load(v).to_dict()==v
    v['implementation_hashes']['realtime/management.py']='0'*64;v.pop('hash');v['hash']=digest(v)
    with pytest.raises(ValueError):HoldingPolicy.load(v)


def test_live_pending_close_contract(monkeypatch):
    import test_indicator_union as old
    monkeypatch.setattr(old,'MODEL',MODEL)
    old.test_live_bracket_actual_manager_cancel_unknown_partial_and_complete()
