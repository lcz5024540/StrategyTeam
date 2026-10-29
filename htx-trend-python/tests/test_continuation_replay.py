"""Full worker replay, point-in-time rejection and economic gate tests."""
import copy
from dataclasses import asdict, replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal as D
import socket
import pytest
from htx_trend.continuation_replay import replay, validate_tape, segments, events, net_equity, TapeMarket
from htx_trend.continuation import ContinuationConfig
from htx_trend.execution import ExecutionConfig
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.maker_execution import MakerExecutionConfig
from htx_trend.paper_worker import PaperWorkerConfig, PaperFileLedger
from htx_trend.research.store import digest, primitive
from htx_trend.scan_replay import load_inputs, OfflineMarket, Assumptions, MemoryLedger
from pathlib import Path


def sample_tape():
    """Locked historical candles, explicitly synthetic books/funding: not acceptance."""
    assumptions=Assumptions()
    candles,_,_=load_inputs(Path(__file__).parents[1]/'replay_inputs/20261021')
    market=OfflineMarket(candles,assumptions)
    worker=PaperWorkerConfig()
    execution=replace(ExecutionConfig(),account_equity_usdt=worker.equity,max_total_margin_rate=worker.margin_rate,
        max_active_positions=worker.max_active_positions,max_same_direction_positions=worker.max_active_positions,hard_risk_limits_enabled=True)
    config=primitive(dict(worker=asdict(worker),entry=asdict(JavaEntryConfig()),execution=asdict(execution),maker=asdict(MakerExecutionConfig())))
    start=datetime(2026,10,19,14,0,30,tzinfo=UTC);frames=[]
    for i in range(144):
        at=start+timedelta(minutes=15*i);market.now=int(at.timestamp());rows={}
        for c in worker.contracts:
            b=market.top_of_book(c);spec=market.contract_spec(c)
            rows[c]=dict(windows={p:dict(observed_at=at.isoformat(),candles=primitive(market.kline_window(c,p,160,now_epoch=market.now).candles)) for p in ('15min','60min')},
                book=dict(observed_at=at.isoformat(),bid=str(b.bid),ask=str(b.ask),bid_size='100000',ask_size='100000'),
                spec=dict(observed_at=at.isoformat(),**primitive(asdict(spec))),funding=dict(observed_at=at.isoformat(),rate='0'))
        frames.append(dict(at=at.isoformat(),config_ref=digest(config),markets=rows))
    return dict(schema='htx-continuation-tape-v1',provenance='ASSUMPTION_ONLY',initial_at=(start-timedelta(seconds=1)).isoformat(),
        initial_mark=dict(at=(start-timedelta(seconds=1)).isoformat(),markets={}),initial_ledger=MemoryLedger().data,
        configurations={digest(config):config},frames=frames,clock_start=start.isoformat(),funding_status='UNKNOWN')


@pytest.fixture(scope='module')
def tape():return sample_tape()


@pytest.fixture(scope='module')
def result(tape):return replay(tape)


def test_four_actual_workers_no_external_io(tape,monkeypatch):
    def forbidden(*a,**k):raise AssertionError('external IO in replay')
    monkeypatch.setattr(socket.socket,'connect',forbidden)
    monkeypatch.setattr(PaperFileLedger,'__init__',forbidden)
    r=replay(tape)
    assert set(r['groups'])==set('ABCD') and not r['eligible']
    assert 'ASSUMPTION_ONLY' in r['blockers']
    for g in r['groups'].values():
        assert not g['errors']
        assert g['decisions'] and g['fills']
        assert len(g['curve'])==len(tape['frames'])+1
        assert g['funding_paid'] is None and g['equity_cost_basis']=='EXCLUDES_UNKNOWN_FUNDING'
    assert r['groups']['A']['fills'] != r['groups']['B']['fills']


def test_complete_baseline_alignment_and_price_mutation(tape,result):
    v=copy.deepcopy(tape);v['reference_fills']=result['groups']['A']['fills']
    assert not replay(v)['alignment']
    v['reference_fills'][0]['price']='999999'
    assert 'BASELINE_MISMATCH' in replay(v)['blockers']


@pytest.mark.parametrize('kind',['future_quote','missing_book','future_revision','gap','config_hash','missing_funding'])
def test_input_gaps_rejected(tape,kind):
    t=copy.deepcopy(tape);f=t['frames'][0];r=f['markets']['BTC-USDT']
    if kind=='future_quote':r['book']['observed_at']='2099-01-01T00:00:00+00:00'
    if kind=='missing_book':del r['book']['ask']
    if kind=='future_revision':r['windows']['15min']['observed_at']='2099-01-01T00:00:00+00:00'
    if kind=='gap':r['windows']['15min']['candles'].pop(20)
    if kind=='config_hash':t['configurations'][f['config_ref']]['worker']['leverage']='9'
    if kind=='missing_funding':del r['funding']
    with pytest.raises((ValueError,KeyError)):validate_tape(t)


def test_drawdown_uses_floating_not_only_closed_orders():
    start=datetime(2026,1,1,tzinfo=UTC)
    curve=[dict(at=(start+timedelta(minutes=15*i)).isoformat(),equity=str(v)) for i,v in enumerate([300,310,280,305,308,306,304,310,290,309])]
    m=segments(curve,[],start.isoformat())
    assert D(m['history']['diagnostic']['drawdown'])==30
    assert D(m['history']['holdout']['drawdown'])==20
    assert m['history']['diagnostic']['closing_equity']==m['history']['holdout']['opening_equity']


def test_strict_fee_roundtrip_gate_does_not_reward_no_trades():
    from htx_trend.continuation_replay import segments
    # Flat market/equal transaction counts do not meet strict reduction requirements.
    rows=[dict(at=f'2026-01-01T00:{i:02}:00+00:00',equity='300') for i in range(10)]
    m=segments(rows,[],rows[0]['at'])
    assert m['history']['diagnostic']['fees']=='0'


def test_full_interval_drawdown_catches_peak_before_split():
    start=datetime(2026,1,1,tzinfo=UTC)
    # Peak 329 before split, trough 311 after split: full loss is 18, not 9.
    curve=[dict(at=(start+timedelta(minutes=i)).isoformat(),equity=str(v)) for i,v in enumerate([300,329,329,329,329,329,329,320,311,322])]
    m=segments(curve,[],start.isoformat())['history']
    assert D(m['full']['drawdown'])==18
    assert D(m['diagnostic']['drawdown'])==9
    assert D(m['holdout']['drawdown'])==9


def test_receipts_need_original_inventory_not_just_true_manifest(tape):
    from htx_trend.continuation_replay import verify_receipts
    t=copy.deepcopy(tape);t['provenance']='OBSERVED';t['source_receipts']=True;t['funding_status']='VERIFIED_SCHEDULE'
    assert not verify_receipts(t,None)


def test_verified_receipt_closure_detects_deleted_frame(tmp_path,tape,result):
    from htx_trend.research.store import FileEvidenceStore
    from htx_trend.continuation_replay import verify_receipts
    t=copy.deepcopy(tape);t['reference_fills']=[]
    with FileEvidenceStore(tmp_path/'cas',compress=True) as store:
        t['initial_source_ref']=store.put('paper_replay_initial',{'at':t['initial_at'],'ledger':t['initial_ledger'],'mark':t['initial_mark']})
        for f in t['frames']:f['source_ref']=store.put('paper_replay_frame',dict(f))
        t['inventory_source_ref']=store.put('paper_replay_inventory',{'start':t['initial_at'],'end':t['frames'][-1]['at'],
            'frames':[{'at':f['at'],'source_ref':f['source_ref']} for f in t['frames']]})
        t['reference_source_ref']=store.put('paper_replay_reference_ledger',{'at':t['frames'][-1]['at'],'ledger':MemoryLedger().data})
        assert verify_receipts(t,tmp_path/'cas')
        t['frames'].pop(20)
        assert not verify_receipts(t,tmp_path/'cas')


def test_replay_wrappers_are_not_native_observation_proof(tape):
    from htx_trend.continuation_replay import verify_native_origins
    assert not verify_native_origins(tape,None)


@pytest.mark.parametrize('bad',['duplicate','late'])
def test_funding_settlement_not_double_or_late(tape,bad):
    t=copy.deepcopy(tape)
    first=t['frames'][0]
    event=dict(id='funding-1',at=first['at'],price='100',rate='.001',contract='BTC-USDT')
    first['settlements']=[event]
    if bad=='duplicate':t['frames'][1]['settlements']=[dict(event,at=t['frames'][1]['at'])]
    else:first['settlements'][0]['at']=t['initial_at']
    with pytest.raises(ValueError):validate_tape(t)
