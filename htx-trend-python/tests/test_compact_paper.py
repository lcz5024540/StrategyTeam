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
