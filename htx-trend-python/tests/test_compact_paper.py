from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace as NS
import copy
import json

import pytest
from test_paper_worker import Market, instance
from htx_trend.market_data import KlineWindow
from htx_trend.paper_worker import PaperTrendWorker, PaperWorkerConfig
from htx_trend.research.compact import CompactEvidence, load_window
from htx_trend.research.store import FileEvidenceStore
from htx_trend.strategy_profiles import STRATEGY_4H_V2


class EvidenceMarket(Market):
    def __init__(self, store):
        self.compact = CompactEvidence(store)
        self.failed = False

    def kline_window(self, contract, period, size, *, now_epoch):
        w = super().kline_window(contract, period, size, now_epoch=now_epoch)
        if self.failed:
            return replace(w, evidence_status='FAILED')
        ref = self.compact.capture(w, {'fixture': True}, observed_at=now_epoch, decision_as_of=now_epoch)
        return replace(w, evidence_refs=(ref,), evidence_status='COMPLETE', capture={'fixture': True, 'period': period})


@pytest.mark.parametrize('profile', ['15m','4h'])
def test_no_signal_decision_and_execution_refs_without_repeated_windows(tmp_path, profile):
    store = FileEvidenceStore(tmp_path/'evidence', compress=True)
    inst = instance()
    if profile == '4h':inst.strategy_name=STRATEGY_4H_V2
    market = EvidenceMarket(store)
    worker = PaperTrendWorker(inst, market, tmp_path/'state', PaperWorkerConfig(contracts=('BTC-USDT',)), store)
    assert worker.tick().status == 'HEALTHY'
    entry = store.keys('compact_entry_decision')
    assert len(entry) == 1
    payload = store.get(entry[0])['payload']
    assert payload['candidates'] == []
    assert payload['funding_paid'] is None
    assert len(load_window(store, payload['entry_window'][0]).candles) == 160
    assert len(store.keys('compact_execution_result')) == 1
    refs = [d['payload']['evidence_ref'] for d in worker.ledger.data['decisions'] if 'evidence_ref' in d['payload']]
    assert refs
    for ref in refs:assert store.verify(ref)['integrity'] == 'VERIFIED'
    worker.tick()
    assert len(store.keys('compact_entry_decision')) == 1
    worker.stop();store.close()


def open_position(worker):
    from datetime import UTC, datetime
    p = worker.exchange.open_position(worker.account,'BTC-USDT','LONG',2,D('110'),datetime.now(UTC),'open')
    worker.ledger.save_position_context(p.position_id, {
        'model':'FIB_PULLBACK_CONTINUATION','contract_size':'.01','initial_stop':'105','invalidation':'104',
        'structure_target':'130','entry_fee':'.00088','highest':'110','lowest':'110',
        'protection':None,'trailing_armed_at':None,'trailing_arm_reason':None,'max_favorable_r':'0'})
    return p


def test_exit_tracks_pre_post_state_and_actual_simulated_cost(tmp_path):
    store = FileEvidenceStore(tmp_path/'evidence', compress=True)
    worker = PaperTrendWorker(instance(), EvidenceMarket(store), tmp_path/'state', PaperWorkerConfig(), store)
    p = open_position(worker)
    assert worker.tick().status == 'HEALTHY'
    assert worker.ledger.position(p.position_id).status == 'CLOSED'
    ref = store.keys('compact_exit_decision')[0]
    row = store.get(ref)['payload']
    assert row['position_before']['quantity'] == 2
    assert row['context_before']['lowest'] == '110'
    assert row['decision']['action'] == 'FULL_CLOSE'
    result = [store.get(k)['payload'] for k in store.keys('compact_execution_result') if p.position_id in store.get(k)['payload']['positions']][0]
    assert result['positions'][p.position_id]['status'] == 'CLOSED'
    assert result['fills'] and D(result['fills'][0]['fee']) > 0
    assert store.verify(ref)['objects'] > 160
    worker.stop();store.close()


@pytest.mark.parametrize('failure', ['market','exit','result'])
def test_evidence_failure_does_not_rollback_close_or_repeat_after_restart(tmp_path, monkeypatch, failure):
    store = FileEvidenceStore(tmp_path/'evidence', compress=True)
    market = EvidenceMarket(store)
    worker = PaperTrendWorker(instance(), market, tmp_path/'state', PaperWorkerConfig(contracts=('BTC-USDT',)), store)
    p = open_position(worker)
    original = store.put
    if failure == 'market':market.failed = True
    else:
        def fail(kind, *a, **kw):
            if kind == {'exit':'compact_exit_decision','result':'compact_execution_result'}[failure]:
                raise OSError('disk full')
            return original(kind,*a,**kw)
        monkeypatch.setattr(store,'put',fail)
    assert worker.tick().status == 'DEGRADED'
    assert worker.ledger.position(p.position_id).status == 'CLOSED'
    fills = len(worker.ledger.data['fills'])
    assert fills == 1
    worker.stop()
    monkeypatch.setattr(store,'put',original);market.failed=False
    worker = PaperTrendWorker(instance(), market, tmp_path/'state', PaperWorkerConfig(contracts=('BTC-USDT',)), store)
    worker.tick()
    assert len(worker.ledger.data['fills']) == fills
    assert worker.ledger.position(p.position_id).status == 'CLOSED'
    worker.stop();store.close()


def test_failed_entry_evidence_has_no_orders_and_can_retry(tmp_path, monkeypatch):
    store = FileEvidenceStore(tmp_path/'evidence', compress=True)
    worker = PaperTrendWorker(instance(), EvidenceMarket(store), tmp_path/'state', PaperWorkerConfig(contracts=('BTC-USDT',)), store)
    original = store.put
    def fail(kind,*a,**kw):
        if kind == 'compact_entry_decision':raise OSError('index full')
        return original(kind,*a,**kw)
    monkeypatch.setattr(store,'put',fail)
    assert worker.tick().status == 'DEGRADED'
    assert worker.ledger.data['orders'] == {} and worker.ledger.data['bars'] == {}
    monkeypatch.setattr(store,'put',original)
    assert worker.tick().status == 'HEALTHY'
    assert worker.ledger.data['bars']
    worker.stop();store.close()


def test_authoritative_checkpoint_failure_blocks_more_execution_until_persisted(tmp_path, monkeypatch):
    store = FileEvidenceStore(tmp_path/'evidence', compress=True)
    worker = PaperTrendWorker(instance(), EvidenceMarket(store), tmp_path/'state', PaperWorkerConfig(contracts=('BTC-USDT',)), store)
    p = open_position(worker)
    worker.ledger.checkpoint()
    original = worker.ledger.checkpoint
    monkeypatch.setattr(worker.ledger,'checkpoint',lambda: (_ for _ in ()).throw(OSError('state disk full')))
    with pytest.raises(OSError):worker.tick()
    assert worker._checkpoint_failed
    on_disk = json.loads(worker.ledger.path.read_text())
    assert on_disk['positions'][p.position_id]['status'] == 'OPEN'
    fills = len(worker.ledger.data['fills'])
    with pytest.raises(OSError):worker.tick()
    assert len(worker.ledger.data['fills']) == fills
    monkeypatch.setattr(worker.ledger,'checkpoint',original)
    assert worker.tick().status == 'DEGRADED'
    assert json.loads(worker.ledger.path.read_text())['positions'][p.position_id]['status'] == 'CLOSED'
    worker.stop();store.close()


def test_failed_ledger_result_is_provisional_and_never_confirmed_after_crash(tmp_path,monkeypatch):
    store=FileEvidenceStore(tmp_path/'evidence',compress=True)
    worker=PaperTrendWorker(instance(),EvidenceMarket(store),tmp_path/'state',PaperWorkerConfig(contracts=('BTC-USDT',)),store)
    p=open_position(worker);worker.ledger.checkpoint()
    monkeypatch.setattr(worker.ledger,'checkpoint',lambda: (_ for _ in ()).throw(OSError('disk full')))
    with pytest.raises(OSError):worker.tick()
    abandoned=store.keys('compact_execution_result')[0]
    assert store.get(abandoned)['payload']['commit_status']=='PROVISIONAL'
    assert not store.keys('compact_ledger_commit')
    with pytest.raises(OSError): worker.stop()
    recovered=PaperTrendWorker(instance(),EvidenceMarket(store),tmp_path/'state',PaperWorkerConfig(contracts=('BTC-USDT',)),store)
    recovered.tick()
    receipts=[store.get(k)['payload'] for k in store.keys('compact_ledger_commit')]
    assert len(receipts)==1 and abandoned not in receipts[0]['result_refs']
    assert recovered.ledger.position(p.position_id).status=='CLOSED'
    recovered.stop();store.close()


def test_compact_candidate_packages_keep_profiles_separate(tmp_path):
    from test_g3_release import package
    for four in (False,True):
        archive=package.build(tmp_path/('4h' if four else '15m'),paper=True,paper_4h=four,compact_evidence=True)
        receipt=package.verify(archive)
        assert receipt['version'].startswith('0.1.7-evidence-v2-')
        assert receipt['version'].endswith('-4h')==four
        assert receipt['bindings']['instance_key']==('htx-trend-paper-4h' if four else 'htx-trend-paper-primary')


@pytest.mark.parametrize('profile', ['15m','4h'])
def test_legacy_compact_hold_protection_partial_full_and_restart_equivalence(tmp_path, monkeypatch, profile):
    from dataclasses import asdict
    from datetime import UTC, datetime
    from itertools import count
    from uuid import UUID
    from htx_trend.models import TrendSide
    now=datetime(2026,1,1,tzinfo=UTC)
    class Clock(datetime):
        @classmethod
        def now(cls,tz=None):return now
    monkeypatch.setattr('htx_trend.paper.datetime',Clock)
    outputs=[]
    for compact_mode in (False,True):
        ids=count(1)
        monkeypatch.setattr('uuid.uuid4',lambda: UUID(int=next(ids)))
        inst=instance()
        if profile=='4h':inst.strategy_name=STRATEGY_4H_V2
        with FileEvidenceStore(tmp_path/str(compact_mode)/'evidence',compress=compact_mode) as store:
            market=EvidenceMarket(store) if compact_mode else Market()
            worker=PaperTrendWorker(inst,market,tmp_path/str(compact_mode)/'state',PaperWorkerConfig(),store)
            worker.exchange.contract_size=D('1')
            p=worker.exchange.open_position(worker.account,'BTC-USDT','LONG',10,D('100'),now,'open')
            worker.ledger.save_position_context(p.position_id,{'model':'FIB_PULLBACK_CONTINUATION','contract_size':'1',
                'initial_stop':'95','invalidation':'94','structure_target':'110','entry_fee':'.4',
                'highest':'100','lowest':'99','max_favorable_r':'0'})
            periods=worker.profile.contract
            windows=tuple(market.kline_window('BTC-USDT',period,160,now_epoch=int(now.timestamp()))
                          for period in (periods.entry_period,periods.trend_period))
            decisions=[]
            for index,(price,high,side) in enumerate((('99','100',TrendSide.LONG),('104','106',TrendSide.LONG),
                                                     ('103','106',TrendSide.FLAT),('94','106',TrendSide.SHORT))):
                before=copy.deepcopy(worker.ledger.data)
                decision=worker.runtime.manage(run_id=str(index),position_id=p.position_id,reference_price=D(price),
                    highest_price=D(high),lowest_price=D('94') if index==3 else D('99'),atr=D('2'),signal_side=side,
                    ema_fast=D('105'),ema_slow=D('102'),major_timeframe_opposition=0,market_context_opposes=False,
                    at=now,evidence_windows=windows)
                decisions.append(asdict(decision))
                assert worker._record_run_evidence(str(index),'BTC-USDT',before,now)
                worker.ledger.checkpoint();assert worker._confirm_evidence()
            assert [d['action'].value for d in decisions]==['HOLD','ARM_TRAILING','PARTIAL_CLOSE','FULL_CLOSE']
            data=copy.deepcopy(worker.ledger.data)
            outputs.append((decisions,data['positions'],data['contexts'],data['orders'],data['fills']))
            if compact_mode:
                rows=[store.get(k)['payload'] for k in store.keys('compact_exit_decision')]
                assert len(rows)==4
                assert len(store.keys('compact_ledger_commit'))==4
                partial=next(r for r in rows if r['decision']['action']=='PARTIAL_CLOSE')
                assert partial['position_before']['quantity']==10
                assert partial['context_before']['protection'] is not None
            worker.stop()
            restored=PaperTrendWorker(inst,market,tmp_path/str(compact_mode)/'state',PaperWorkerConfig(),store)
            assert restored.ledger.position(p.position_id).status=='CLOSED'
            assert restored.ledger.data['fills']==json.loads(json.dumps(data['fills'],default=str))
            restored.stop()
    assert outputs[0]==outputs[1]


@pytest.mark.parametrize('outcome', ['filled','rejected','unfilled'])
def test_actual_candidate_gate_and_maker_outcome_equivalent(tmp_path,outcome):
    from datetime import UTC, datetime
    from pathlib import Path
    from dataclasses import asdict
    from htx_trend.ledger import Account, TrendLedger
    from htx_trend.paper import PaperExchange
    from htx_trend.runtime import PaperTrendRuntime
    from htx_trend.market_data import Candle, ContractSpec, FundingRate, TopOfBook
    from htx_trend.maker_execution import MakerExecutionConfig, MakerTimeoutAction
    from test_paper_maker_shadow import _entry_config, _execution_config
    from test_runtime import bar
    outputs=[]
    for enabled in (False,True):
        root=tmp_path/str(enabled);root.mkdir()
        TrendLedger.initialize(root/'paper.sqlite',Path(__file__).parents[1]/'sql/schema.sql')
        ledger=TrendLedger(root/'paper.sqlite');ledger.account(Account('main','Main','PAPER'))
        with FileEvidenceStore(root/'evidence',compress=enabled) as store:
            compact=CompactEvidence(store) if enabled else None
            config=_execution_config()
            if outcome=='rejected':config=replace(config,min_score=D('10000'))
            maker=MakerExecutionConfig(enabled=outcome=='unfilled',offset_bps=D('0'),max_wait_bars=1,max_amends=0,
                                       partial_accept=False,timeout_action=MakerTimeoutAction.CANCEL)
            runtime=PaperTrendRuntime(ledger,PaperExchange(ledger),account_id='main',entry_config=_entry_config(),
                execution_config=config,maker_config=maker,evidence_store=store,compact=compact)
            rows=[bar(i,100+i) for i in range(90)];rows[-1]=bar(89,195)
            windows=[]
            for period,step in (('15min',900),('60min',3600)):
                w=KlineWindow('BTC-USDT',period,tuple(replace(b,open_time=i*step) for i,b in enumerate(rows)),90)
                if enabled:
                    ref=compact.capture(w,{'synthetic':True},observed_at=90*3600,decision_as_of=90*3600)
                    w=replace(w,evidence_refs=(ref,),evidence_status='COMPLETE',capture={'synthetic':True,'period':period})
                windows.append(w)
            result=runtime.scan(run_id='r1',entry_window=windows[0],trend_window=windows[1],now_epoch=90*900,
                at=datetime(2026,1,1,tzinfo=UTC),book=TopOfBook(D('194.99'),D('195.01')),
                contract=ContractSpec('BTC-USDT',D('.01'),D('.1'),1),funding=FundingRate('BTC-USDT',D('.0001')),
                execution_candle=Candle(89*900,D('196'),D('198'),D('196'),D('197')))
            assert result.candidates
            assert (result.paper_order_id is not None)==(outcome=='filled')
            outputs.append(([asdict(c) for c in result.candidates],[asdict(g) for g in result.gates],
                            [(p.quantity,p.entry_price,p.status) for p in ledger.active_positions('main')]))
            if enabled:
                assert len(store.keys('compact_entry_decision'))==1
                assert store.verify(store.keys('compact_entry_decision')[0])['integrity']=='VERIFIED'
        ledger.close()
    assert outputs[0]==outputs[1]
