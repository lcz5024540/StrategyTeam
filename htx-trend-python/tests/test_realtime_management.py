from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace
import pytest

from test_realtime_execution import setup,submit
from test_realtime_plans import book
from htx_trend.paper_worker import WorkerPaperExchange
from htx_trend.runtime import PaperTrendRuntime
from htx_trend.realtime.management import PaperManagement, LiveManagement
from htx_trend.realtime import VERSION
from htx_trend.continuation import ContinuationConfig
from htx_trend.holding_policy import HoldingPolicy, paper_exit_config
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.market_data import OrderBook, FundingRate, KlineWindow, Candle
from htx_trend.models import TrendSide


def management(s,market,frames=None):
    policy=HoldingPolicy.load(s.ledger.position_context(s.ledger.active_positions('paper:p')[0].position_id)['holding_policy'])
    runtime=PaperTrendRuntime(s.ledger,WorkerPaperExchange(s.ledger),account_id='paper:p',strategy_version=VERSION,
        continuation_config=ContinuationConfig('linked-v1'),holding_policy=policy,require_holding_policy=True,
        evidence_context={'instance':'p','timeframe_profile':'INTRADAY_15M'})
    return PaperManagement(runtime,s.broker,frames or {},market,exit_config=paper_exit_config(),entry_config=JavaEntryConfig())


class Market:
    price=D(102)
    def order_book(self,_):return OrderBook(((self.price-D('.01'),D(1000)),),((self.price,D(1000)),))
    def funding_rate(self,_):return FundingRate('ETH-USDT',D(0))


def test_hard_stop_independent_of_frames_evidence_and_contract_fees(setup):
    s=setup;submit(s);s.broker.on_book(book(1300,'98','98.01'),now_ms=1300)
    p=s.ledger.active_positions('paper:p')[0];m=management(s,Market())
    assert not m.tick(1400)
    assert not s.ledger.active_positions('paper:p')
    fee=D(102)*p.quantity*D('.01')*D('.0004')
    assert s.ledger.data['fills'][-1]['fee']==fee
    assert s.ledger.position(p.position_id).realized_pnl==(D(98)-D(102))*p.quantity*D('.01')-fee
    count=len(s.ledger.data['fills']);m.tick(1500)
    assert len(s.ledger.data['fills'])==count


def test_authoritative_ledger_failure_stops_more_simulated_executions(setup,monkeypatch):
    s=setup;submit(s);s.broker.on_book(book(1300,'98','98.01'),now_ms=1300)
    m=management(s,Market());save=s.ledger.checkpoint
    def fail():raise OSError('full')
    monkeypatch.setattr(s.ledger,'checkpoint',fail)
    with pytest.raises(OSError):m.tick(1400)
    assert m.ledger_failed
    count=len(s.ledger.data['fills'])
    with pytest.raises(OSError):m.tick(1500)
    assert len(s.ledger.data['fills'])==count
    monkeypatch.setattr(s.ledger,'checkpoint',save)
    assert m.tick(1600)==['AUTHORITATIVE_LEDGER_RECOVERED']


def test_first_closed_bar_excludes_prefill_high_low_and_repeated_bar(setup,monkeypatch):
    s=setup;submit(s);s.broker.on_book(book(1300,'98','98.01'),now_ms=1300)
    p=s.ledger.active_positions('paper:p')[0]
    frames={}
    market=Market();market.price=D('98.01')
    m=management(s,market,frames)
    # 10:07 fill, with pre-fill extreme 50/150 in the 10:00 bar.
    first=(10*3600+7*60)*1000
    context=s.ledger.position_context(p.position_id)
    context.update(first_fill_ms=first,last_bar=35100,continuation_last_bar=35100,
                   highest='98',lowest='98',extrema_coverage='COMPLETE')
    s.ledger.save_position_context(p.position_id,context)
    bar=Candle(36000,D(100),D(150),D(50),D(98))
    window=KlineWindow('ETH-USDT','15min',(bar,),1)
    m.frames={'ETH-USDT':{'entry':window,'trend':window,'windows':{}}}
    monkeypatch.setattr('htx_trend.realtime.management.evaluate',lambda *_:SimpleNamespace(side=TrendSide.SHORT,
        indicators=SimpleNamespace(atr=D(2),ema_fast=D(97),ema_slow=D(99))))
    captured=[]
    m.runtime.manage=lambda **kw:captured.append(kw)
    assert not m.tick(36910000)
    assert captured[0]['highest_price']==98 and captured[0]['lowest_price']==98
    assert captured[0]['flat_streak']==0
    m.tick(36911000)
    assert len(captured)==1


def test_live_stop_does_not_read_missing_bar_cache():
    row={'id':1,'contract_code':'ETH-USDT','side':'LONG','remaining_contract_volume':1,'stop_price':'90'}
    state={'first_fill_ms':1000,'highest':'100','lowest':'100'}
    repo=SimpleNamespace(managed_positions=lambda _:[row],exit_state=lambda _:dict(state),
                         save_management_state=lambda _,value:state.update(value))
    broker=SimpleNamespace(repo=repo,instance=SimpleNamespace())
    market=Market();market.price=D(80)
    called=[];manager=SimpleNamespace(_manage=lambda *args:called.append(args))
    m=LiveManagement(broker,manager,market)
    assert not m.tick(2000) and len(called)==1


def test_partial_entry_deferred_exit_survives_same_bar_cancel_recovery(monkeypatch):
    from test_position_manager import Repo,Market as ExitMarket,Executor,manager,position
    from htx_trend.strategy_context import MarketContext
    from htx_trend.models import TrendSide
    from htx_trend.exits import decide_v4
    repo=Repo();market=ExitMarket();market.price=D(100);live=Executor()
    row=position();row.update(primary_model='RT_RANGE_REJECTION',strategy_name=VERSION)
    repo.pending=[dict(position_id=1,leg='ENTRY',status='PARTIALLY_FILLED',exchange_order_id=77)]
    repo.state={'continuation_version':'linked-v1'}
    mgr=manager(repo=repo,market=market,executor=live)
    mgr.instance=replace(mgr.instance,strategy_name=VERSION)
    monkeypatch.setattr('htx_trend.position_manager.evaluate',lambda *_:SimpleNamespace(side=TrendSide.SHORT,
        indicators=SimpleNamespace(atr=D(2),ema_fast=D(98),ema_slow=D(100))))
    mgr._manage(row,144900,MarketContext())
    assert live.cancelled==[('BTC-USDT','77')] and not live.sent
    assert repo.state['pending_realtime_exit']['target_remaining']==0
    saved=repo.state['last_bar']
    repo.pending=[]
    def no_new_bar(*_a,**_k):raise AssertionError('deferred exit must not await another bar')
    market.kline_window=no_new_bar
    mgr._manage(row,144901,MarketContext())
    assert len(live.sent)==1 and live.sent[0].reduce_only and live.sent[0].volume=='4'
    assert repo.state['last_bar']==saved


def test_nondefault_owned_paper_exit_fee(setup):
    from htx_trend.realtime.brokers import RealtimePaperExchange
    from htx_trend.holding_policy import digest
    s=setup;submit(s);s.broker.on_book(book(1300,'98','98.01'),now_ms=1300)
    p=s.ledger.active_positions('paper:p')[0]
    # Create the distinct accepted entry policy before simulating its management.
    context=s.ledger.position_context(p.position_id)
    value=context['holding_policy'];value['exit']['fee_rate']='0.0006'
    value.pop('hash');value['hash']=digest(value)
    s.ledger.data['contexts'][p.position_id]=context
    m=management(s,Market());m.runtime._exchange=RealtimePaperExchange(s.ledger)
    m.tick(1400)
    assert s.ledger.data['fills'][-1]['fee']==D(102)*p.quantity*D('.01')*D('.0006')


def test_same_epoch_stale_data_marks_extrema_gap(setup):
    s=setup;submit(s);s.broker.on_book(book(1300,'98','98.01'),now_ms=1300)
    m=management(s,Market())
    p=s.ledger.active_positions('paper:p')[0]
    context=s.ledger.position_context(p.position_id);context['extrema_coverage']='COMPLETE'
    s.ledger.save_position_context(p.position_id,context)
    m.gap(contract='ETH-USDT')
    m.event(book(4000,'97','97.01'),4000)
    assert s.ledger.position_context(p.position_id)['extrema_coverage']=='GAP'


def test_manager_failure_does_not_restore_stale_cached_exit_state():
    row={'id':1,'contract_code':'ETH-USDT','side':'LONG','remaining_contract_volume':1,'stop_price':'90'}
    durable={'first_fill_ms':1000,'position_boundary_initialized':True,'highest':'100','lowest':'100'}
    repo=SimpleNamespace(managed_positions=lambda _:[row],exit_state=lambda _:dict(durable),
        save_management_state=lambda _,value:(durable.clear(),durable.update(value)))
    broker=SimpleNamespace(repo=repo,instance=SimpleNamespace())
    market=Market();market.price=D(80)
    def persist_then_fail(*_):
        durable['pending_realtime_exit']={'target_remaining':0,'reason':'protect'}
        raise TimeoutError('cancel result unknown')
    m=LiveManagement(broker,SimpleNamespace(_manage=persist_then_fail),market)
    assert m.tick(2000)
    m.event(book(2001,'100','100.01'),2001)
    assert m.state(1)['pending_realtime_exit']['target_remaining']==0
    m.flush();assert durable['pending_realtime_exit']['reason']=='protect'
