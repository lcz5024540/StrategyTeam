from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace
import json
import pytest

from test_realtime_plans import plan, book
from htx_trend.execution import ExecutionFacts
from htx_trend.market_data import ContractSpec, OrderBook, FundingRate
from htx_trend.realtime.plans import TriggerEngine, RealtimeTrigger
from htx_trend.realtime.storage import PaperPlanStore
from htx_trend.realtime.brokers import PaperBroker, LiveBroker
from htx_trend.realtime.risk import evaluate
from htx_trend.paper_worker import PaperFileLedger
from htx_trend.holding_policy import HoldingPolicy, paper_exit_config
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.timeframes import TimeframeProfile
from htx_trend.realtime import VERSION


def policy():
    return HoldingPolicy.create(instance_key='p',account_id='paper:p',strategy_family='TREND',strategy_name=VERSION,
        mode='PAPER',profile=TimeframeProfile.INTRADAY_15M,entry_config=JavaEntryConfig(),
        exit_config=paper_exit_config(),continuation_version='linked-v1').to_dict()


@pytest.fixture
def setup(tmp_path):
    ledger=PaperFileLedger(tmp_path/'p.json',{'instance':'p','strategy':VERSION},mirror=lambda _:None)
    engine=TriggerEngine(PaperPlanStore(ledger),'p');engine.refresh('ETH-USDT',[plan()])
    broker=PaperBroker(ledger,engine,'paper:p')
    trigger=RealtimeTrigger(plan(),1000,1000,D(98),D(101),D(101),('a','b'),'book','a')
    contract=ContractSpec('ETH-USDT',D('.01'),D('.01'),1)
    facts=ExecutionFacts(actual_taker_fee_rate=D('.0004'),available_margin_usdt=D(300),account_equity_usdt=D(300),
        leverage=D(5),active_position_count=0,same_direction_position_count=0,reserved_margin_usdt=D(0),used_margin_usdt=D(0))
    gate,intent=evaluate(trigger,now_ms=1000,book=OrderBook(((D(98),D(10000)),),((D('98.01'),D(10000)),)),
        contract=contract,funding=FundingRate('ETH-USDT',D(0)),facts=facts,instance_key='p')
    assert gate.allowed
    yield SimpleNamespace(ledger=ledger,engine=engine,broker=broker,trigger=trigger,contract=contract,intent=intent,facts=facts)
    ledger.close()


def submit(s):
    return s.broker.submit(s.intent,s.trigger,s.contract,now_ms=1000,fee_rate=D('.0004'),holding_parameters=policy())


def test_next_book_partial_ioc_and_frozen_stop(setup):
    s=setup;assert submit(s)=='SUBMITTED'
    assert s.broker.on_book(book(1100,'98','98.01'),now_ms=1100)==[]
    event=replace(book(1300,'97.9','97.91'),bid_size=D(1))
    ids=s.broker.on_book(event,now_ms=1300)
    assert len(ids)==1
    p=s.ledger.position(ids[0]);ctx=s.ledger.position_context(ids[0])
    assert p.quantity==1 and p.entry_price==D('97.9')
    assert ctx['initial_stop']==str(s.intent.price.invalidation)
    assert ctx['first_fill_ms']==1300 and ctx['highest']=='97.9'
    assert s.engine.rows['p']['phase']=='PARTIAL' and not s.ledger.data['realtime_pending']
    assert s.broker.on_book(book(1400,'98','98.01'),now_ms=1400)==[]
    assert submit(s)=='UNKNOWN' and len(s.ledger.data['orders'])==1
    assert json.loads(s.ledger.path.read_text())['positions'][ids[0]]['quantity']==1


def test_zero_fill_and_restart_do_not_resubmit(setup):
    s=setup;submit(s)
    assert not s.broker.on_book(book(1300,'97','97.01'),now_ms=1300)
    assert s.engine.rows['p']['phase']=='NO_FILL'
    assert not s.ledger.active_positions('paper:p')
    assert submit(s)=='UNKNOWN'


def test_unknown_capture_interval_censors_fill(setup):
    s=setup;submit(s);s.broker.recover()
    assert s.engine.rows['p']['reason']=='RESTART_UNOBSERVED_EXECUTION'
    assert not s.broker.on_book(book(1300,'98','98.01'),now_ms=1300)


def test_ledger_failure_never_advertises_submitted_intent(setup,monkeypatch):
    s=setup
    def fail():raise OSError('disk full')
    monkeypatch.setattr(s.ledger,'checkpoint',fail)
    with pytest.raises(OSError):submit(s)
    assert not s.ledger.data['orders'] and s.engine.rows['p']['phase']=='WAITING'


def test_real_factory_constructs_disabled_paper(tmp_path,monkeypatch):
    from htx_trend.realtime.factory import paper
    from htx_trend.mysql_store import RuntimeInstance
    from htx_trend.research.store import FileEvidenceStore
    monkeypatch.setenv('HTX_RT_CAPTURE_DIR',str(tmp_path/'capture'))
    monkeypatch.delenv('HTX_RT_ENTRY_ENABLED',raising=False)
    instance=RuntimeInstance('p','TREND',VERSION,None,'paper','PAPER',True,'test',None)
    store=FileEvidenceStore(tmp_path/'evidence',compress=True)
    worker=paper(instance,SimpleNamespace(),tmp_path/'state',store)
    worker.broker.ledger.mirror=lambda _:None
    assert not worker.entry_enabled and worker.interval_seconds==.05
    assert worker.holding_parameters['owner']['strategy_name']==VERSION
    worker.stop();store.close()


@pytest.mark.parametrize('failure,phase',[(None,'SUBMITTED'),('network','UNKNOWN'),('leverage','REJECTED'),('intent','REJECTED')])
def test_live_durable_ioc_before_send_and_unknown_never_retries(setup,monkeypatch,failure,phase):
    from test_execution_service import Repository, Executor, executor
    from htx_trend.execution import ExecutionConfig
    s=setup;repo=Repository();network=Executor();sent=[]
    repo._in_transaction=False
    repo._connection=SimpleNamespace(begin=lambda:repo.calls.append(('begin',None)),
        commit=lambda:repo.calls.append(('commit',None)),rollback=lambda:repo.calls.append(('rollback',None)))
    entry=executor(repo,network,strategy_name=VERSION)
    broker=LiveBroker(entry,s.engine,guard=lambda:None)
    monkeypatch.setattr('htx_trend.realtime.brokers.time.time',lambda:1.1)
    if failure=='leverage':
        def fail_leverage(*_):raise TimeoutError()
        network.set_leverage=fail_leverage
    if failure=='intent':
        def fail_intent(*_):raise OSError()
        repo.record_order_intent=fail_intent
    def send(request):
        sent.append(request)
        assert s.engine.rows['p']['stage']=='INTENT_DURABLE'
        assert any(name=='commit' for name,_ in repo.calls)
        assert request.type=='limit' and request.time_in_force=='ioc' and request.price=='97.80'
        assert next(value for name,value in repo.calls if name=='position').primary_model=='RT_RANGE_REJECTION'
        if failure=='network':raise TimeoutError('unknown response')
        return {'order_id':'99','client_order_id':request.client_order_id}
    network.place=send
    result=broker.submit(s.intent,s.trigger,s.contract,now_ms=1000,fee_rate=D('.0004'),holding_parameters=policy(),
                        facts=s.facts,config=ExecutionConfig())
    assert result==phase
    if phase=='UNKNOWN':
        assert not any(name=='release' for name,_ in repo.calls)
        assert broker.submit(s.intent,s.trigger,s.contract,now_ms=1000,fee_rate=D('.0004'),holding_parameters=policy(),
                             facts=s.facts,config=ExecutionConfig())=='UNKNOWN'
        assert len(sent)==1
    if phase=='REJECTED':assert not sent and any(name=='release' for name,_ in repo.calls)
