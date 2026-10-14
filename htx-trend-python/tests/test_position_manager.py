from datetime import UTC, datetime
from decimal import Decimal as D
from types import SimpleNamespace

from htx_trend.market_data import Candle, KlineWindow, OrderBook, FundingRate, period_seconds
from htx_trend.models import TrendSide
from htx_trend.mysql_store import RuntimeInstance, TradeAccount
from htx_trend.position_manager import LivePositionManager, ExitConfig
from htx_trend.strategy_context import MarketContext
from htx_trend.timeframes import TimeframeProfile


class Repo:
    def __init__(self): self.state={}; self.orders=[]; self.status=[]; self.decisions=[]; self.pending=[]
    def pending_orders(self, **_): return self.pending
    def exit_state(self,_): return dict(self.state)
    def save_exit_state(self,_,state,decision): self.state=dict(state)
    def record_exit_decision(self,**value): self.decisions.append(value)
    def record_order_intent(self,value): self.orders.append(value)
    def mark_order_accepted(self,**_): pass
    def mark_order_unknown(self,**_): pass
    def mark_position_status(self,**value): self.status.append(value)
    def _write(self,*_): pass


class Market:
    price=D(80)
    def order_book(self,_): return OrderBook(((self.price,D(100)),),((self.price+D('.01'),D(100)),))
    def funding_rate(self,_): return FundingRate('BTC-USDT',D(0))
    def kline_window(self,code,period,size,**kwargs):
        step=period_seconds(period); end=kwargs["now_epoch"]//step*step-step
        return KlineWindow(code,period,tuple(Candle(end-(size-i)*step,D(100),D(101),D(99),D(100),turnover=D(1000)) for i in range(1,size+1)),size)


class Client:
    volume=4
    def positions(self,_): return [{'position_side':'long','volume':self.volume}]
    def swap_fee(self,_): return [{'taker_fee_rate':'.0004'}]


class Executor:
    def __init__(self): self.sent=[]; self.cancelled=[]
    def place(self,request): self.sent.append(request); return {'order_id':99}
    def cancel(self,*args): self.cancelled.append(args)


def position():
    return dict(id=1,position_key='p',account_id=7,contract_code='BTC-USDT',side='LONG',primary_model='FIB_PULLBACK_CONTINUATION',
        remaining_contract_volume=4,entry_filled_contracts=4,entry_avg_price='100',contract_size='.01',entry_fee='.02',stop_price='90',
        invalidation_price='90',target_zone_low='120',target_zone_high='130',timeframe_profile='INTRADAY_15M')


def manager(repo=None,market=None,client=None,executor=None):
    return LivePositionManager(repo or Repo(),RuntimeInstance('p','TREND','HTX_TREND_V1',7,'primary','LIVE',True,'unit',None),
        TradeAccount(7,'primary','PRIMARY',True,False,'test'),market or Market(),client or Client(),executor or Executor(),
        config=ExitConfig(engine_enabled=True,engine_live=True))


def test_manager_only_processes_positions_owned_by_its_profile():
    repo = Repo()
    intraday = position()
    swing = {**position(), 'id': 2, 'position_key': 'swing', 'contract_code': 'ETH-USDT',
             'timeframe_profile': 'SWING_4H'}
    repo.managed_positions = lambda _: [intraday, swing]
    managed = []
    worker = manager(repo=repo)
    worker._manage = lambda row, *_: managed.append(row['id'])
    worker.manage(144900, MarketContext())
    assert managed == [1]
    swing_worker = LivePositionManager(repo, worker.instance, worker.account, worker.market, worker.client, worker.executor,
        period='4hour', timeframe_profile=TimeframeProfile.SWING_4H,
        config=ExitConfig(engine_enabled=True, engine_live=True))
    swing_worker._manage = lambda row, *_: managed.append(row['id'])
    swing_worker.manage(144900, MarketContext())
    assert managed == [1, 2]


def test_initial_stop_sends_reduce_only_after_intent_without_decrementing_quantity():
    repo,live=Repo(),Executor(); p=position(); m=manager(repo=repo,executor=live)
    m._manage(p,144900,MarketContext())
    assert len(repo.orders)==len(live.sent)==1 and live.sent[0].reduce_only
    assert live.sent[0].side=='sell' and live.sent[0].volume=='4'
    assert p['remaining_contract_volume']==4 and repo.status[-1]['status']=='CLOSE_SENT'


def test_hard_stop_does_not_depend_on_model_or_higher_timeframe_data():
    class BrokenMarket(Market):
        def kline_window(self,*_args,**_kwargs): raise RuntimeError('history unavailable')
        def funding_rate(self,_): raise RuntimeError('funding unavailable')
    repo,live=Repo(),Executor()
    manager(repo=repo,market=BrokenMarket(),executor=live)._manage(position(),144900,MarketContext())
    assert len(live.sent)==1 and live.sent[0].reduce_only


def test_hard_stop_continues_when_evidence_store_is_unavailable():
    class BrokenEvidence:
        def put(self, *_args, **_kwargs): raise OSError('disk full')
    repo,live=Repo(),Executor()
    mgr=LivePositionManager(repo,RuntimeInstance('p','TREND','HTX_TREND_V1',7,'primary','LIVE',True,'unit',None),
        TradeAccount(7,'primary','PRIMARY',True,False,'test'),Market(),Client(),live,
        config=ExitConfig(engine_enabled=True,engine_live=True),evidence_store=BrokenEvidence())
    mgr._manage(position(),144900,MarketContext())
    assert len(live.sent)==1 and live.sent[0].reduce_only


def test_pending_entry_is_cancelled_before_any_close():
    repo,live=Repo(),Executor(); repo.pending=[dict(position_id=1,leg='ENTRY',status='PARTIALLY_FILLED',exchange_order_id=77)]
    manager(repo=repo,executor=live)._manage(position(),144900,MarketContext())
    assert live.cancelled==[('BTC-USDT','77')] and not live.sent


def test_distinct_completed_bars_and_restart_preserve_confirmation_count():
    repo,market=Repo(),Market(); market.price=D(100)
    manager(repo=repo,market=market)._manage(position(),144900,MarketContext())
    first=repo.state['flat']
    manager(repo=repo,market=market)._manage(position(),144910,MarketContext())
    assert repo.state['flat']==first==1


def test_exchange_mismatch_prevents_external_position_takeover():
    repo,client,live=Repo(),Client(),Executor(); client.volume=5
    repo.managed_positions=lambda _: [position()]
    failures=manager(repo=repo,client=client,executor=live).manage(144900,MarketContext())
    assert failures and not live.sent and repo.status[-1]['status']=='UNKNOWN'


def test_close_timeout_retains_unknown_intent():
    repo,live=Repo(),Executor()
    def lost(_): raise TimeoutError('response lost')
    live.place=lost
    repo.managed_positions=lambda _: [position()]
    failures=manager(repo=repo,executor=live).manage(144900,MarketContext())
    assert failures and len(repo.orders)==1 and repo.status[-1]['status']=='UNKNOWN'


def test_v4_observation_mode_records_but_never_executes():
    repo,market,live=Repo(),Market(),Executor(); market.price=D(121)
    mgr=LivePositionManager(repo,RuntimeInstance('p','TREND','HTX_TREND_V1',7,'primary','LIVE',True,'unit',None),
        TradeAccount(7,'primary','PRIMARY',True,False,'test'),market,Client(),live,
        config=ExitConfig(engine_enabled=True,engine_live=False))
    mgr._manage(position(),144900,MarketContext())
    assert repo.decisions and repo.decisions[-1]['block_reason']=='exit engine live disabled'
    assert not live.sent
