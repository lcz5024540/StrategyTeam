from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal as D
import gzip
import json
from pathlib import Path

import pytest
from htx_trend.market_data import HtxMarketData, KlineWindow, Candle, period_seconds, period_anchor_seconds
from htx_trend.research.compact import CompactEvidence, load_window
from htx_trend.research.store import FileEvidenceStore, IntegrityError


def window(period='15min', first=0, count=160):
    step = period_seconds(period)
    anchor = period_anchor_seconds(period)
    return KlineWindow('BTC-USDT', period, tuple(Candle(anchor+first+i*step, D('100'), D('102'), D('99'), D('101'), D('2'))
                                                for i in range(count)), count)


def envelope(w):
    return {'status': 'ok', 'data': [{'id': c.open_time, 'open': str(c.open), 'high': str(c.high),
                                    'low': str(c.low), 'close': str(c.close), 'amount': str(c.amount)} for c in w.candles]}


def test_gzip_legacy_same_identity_and_closure(tmp_path):
    with FileEvidenceStore(tmp_path) as old:
        ref = old.put('test', {'x': 1})
    with FileEvidenceStore(tmp_path, compress=True) as store:
        assert store.put('test', {'x': 1}) == ref
        child = store.put('child', {'x': 2}, refs=(ref,))
        assert store.verify(child)['objects'] == 2
        assert store._path(ref).exists()
        assert not store._path(ref).with_suffix('.json.gz').exists()
        assert store._path(child).with_suffix('.json.gz').exists()
    with FileEvidenceStore(tmp_path) as store:
        assert store.verify(child)['objects'] == 2
        store._path(child).with_suffix('.json.gz').write_bytes(b'broken')
        with pytest.raises(IntegrityError):store.get(child)


@pytest.mark.parametrize('period', ['15min', '60min', '4hour', '1day'])
def test_1000_unchanged_polls_do_not_add_large_objects(tmp_path, period):
    with FileEvidenceStore(tmp_path, compress=True) as store:
        compact = CompactEvidence(store); w = window(period)
        first = compact.capture(w, envelope(w), observed_at=1, decision_as_of=1)
        files = {p.name: p.stat().st_size for p in (tmp_path/'objects').rglob('*.gz')}
        for i in range(1000):
            assert compact.capture(w, {**envelope(w), 'ts': i}, observed_at=i+2, decision_as_of=i+2) == first
        assert {p.name: p.stat().st_size for p in (tmp_path/'objects').rglob('*.gz')} == files
        assert len(files) == 161
        assert load_window(store, first).candles == w.candles
        assert store.checkpoint(compact.stream(w.contract_code, period))['observations'] == 1001


def test_revision_preserved_and_restart_stable(tmp_path):
    w = window(count=3)
    with FileEvidenceStore(tmp_path, compress=True) as store:
        c = CompactEvidence(store)
        original = c.capture(w, envelope(w), observed_at=1, decision_as_of=1)
    with FileEvidenceStore(tmp_path, compress=True) as store:
        c = CompactEvidence(store)
        revised = replace(w, candles=(*w.candles[:-1], replace(w.candles[-1], close=D('102'))))
        newer = c.capture(revised, envelope(revised), observed_at=2, decision_as_of=2)
        assert newer != original
        assert load_window(store, original).candles == w.candles
        assert load_window(store, newer).candles == revised.candles
        assert len(store.keys('compact_kline_revision')) == 1
        refs = c.retain_input(replace(revised, evidence_refs=(newer,), evidence_status='COMPLETE', capture={'response': envelope(revised)}))
        assert store.get(refs[-1])['kind'] == 'compact_decision_raw'


@pytest.mark.parametrize('period', ['15min', '4hour', '1day'])
def test_gap_repair_pages_and_resume_without_cursor_leap(tmp_path, period):
    step = period_seconds(period); base = window(period, count=3)
    later = window(period, first=10*step, count=3)
    class Client:
        def history_kline_envelope(self, symbol, period, *, start, end):
            return envelope(window(period, first=start-period_anchor_seconds(period), count=(end-start)//step+1))
    with FileEvidenceStore(tmp_path, compress=True) as store:
        c = CompactEvidence(store)
        c.capture(base, envelope(base), observed_at=1, decision_as_of=1)
        c.capture(later, envelope(later), observed_at=2, decision_as_of=2)
        state = store.checkpoint(c.stream('BTC-USDT', period))
        assert state['coverage_end'] == base.candles[-1].open_time+step
        assert c.repair_once(Client(), 'BTC-USDT', period, page_bars=4)
    with FileEvidenceStore(tmp_path, compress=True) as store:
        c = CompactEvidence(store)
        while c.repair_once(Client(), 'BTC-USDT', period, page_bars=4):pass
        state = store.checkpoint(c.stream('BTC-USDT', period))
        assert state['coverage_end'] == state['target_end']


def test_missing_duplicate_page_and_storage_failure_do_not_advance(tmp_path, monkeypatch):
    with FileEvidenceStore(tmp_path, compress=True) as store:
        c = CompactEvidence(store);w = window(count=3)
        c.capture(w, envelope(w), observed_at=1, decision_as_of=1)
        name = c.stream('BTC-USDT', '15min'); before = store.checkpoint(name)
        original = store.put
        def fail(kind, *a, **kw):
            if kind == 'compact_kline_window':raise OSError('disk full')
            return original(kind, *a, **kw)
        monkeypatch.setattr(store, 'put', fail)
        changed = window(first=900, count=3)
        with pytest.raises(OSError):c.capture(changed, envelope(changed), observed_at=2, decision_as_of=2)
        assert store.checkpoint(name) == before
        monkeypatch.setattr(store, 'put', original)
        c.capture(window(first=9000, count=3), {}, observed_at=3, decision_as_of=3)
        class Bad:
            def history_kline_envelope(self,*a,**kw):return {'status':'ok','data':[envelope(w)['data'][0]]*2}
        before = store.checkpoint(name)['coverage_end']
        assert not c.repair_once(Bad(), 'BTC-USDT', '15min')
        assert store.checkpoint(name)['coverage_end'] == before
        assert store.keys('compact_data_anomaly')


def test_market_capture_uses_stable_window_not_response_timestamp(tmp_path):
    w = window(count=162)
    class Client:
        def history_kline_envelope(self,*a,**kw):return envelope(w)
    with FileEvidenceStore(tmp_path, compress=True) as store:
        compact = CompactEvidence(store)
        market = HtxMarketData(Client(), evidence_store=store, compact=compact)
        first = market.kline_window('BTC-USDT','15min',160,now_epoch=200000)
        second = market.kline_window('BTC-USDT','15min',160,now_epoch=200045)
        assert first.evidence_status == 'COMPLETE'
        assert first.evidence_refs == second.evidence_refs
        assert first.capture['decision_as_of'] != second.capture['decision_as_of']
        assert not store.keys('runtime_raw_kline')


def test_revision_transition_back_to_previous_value_is_retained(tmp_path):
    with FileEvidenceStore(tmp_path,compress=True) as store:
        c=CompactEvidence(store); a=window(count=3)
        b=replace(a,candles=(*a.candles[:-1],replace(a.candles[-1],close=D('102'))))
        first=c.capture(a,envelope(a),observed_at=1,decision_as_of=1)
        c.capture(b,envelope(b),observed_at=2,decision_as_of=2)
        assert c.capture(a,envelope(a),observed_at=3,decision_as_of=3)==first
        revisions=[store.get(k)['payload'] for k in store.keys('compact_kline_revision')]
        assert len(revisions)==2
        assert {r['observed_at'] for r in revisions}=={2,3}
        assert all(r['previous_versions'] for r in revisions)


def test_unused_raw_gap_does_not_add_new_entry_gate(tmp_path):
    w=window(count=162)
    raw=envelope(w);raw['data'].pop(1)
    class Client:
        def history_kline_envelope(self,*a,**kw):return raw
    with FileEvidenceStore(tmp_path/'old') as old, FileEvidenceStore(tmp_path/'new',compress=True) as new:
        original=HtxMarketData(Client(),evidence_store=old).kline_window('BTC-USDT','15min',160,now_epoch=200000)
        compact=CompactEvidence(new)
        latest=HtxMarketData(Client(),evidence_store=new,compact=compact).kline_window('BTC-USDT','15min',160,now_epoch=200000)
        assert original.candles==latest.candles and latest.is_complete
        assert original.evidence_status==latest.evidence_status=='COMPLETE'
        assert new.keys('compact_data_anomaly')


@pytest.mark.parametrize('raw', [{'status':'ok','data':[{'id':0}]}, {'status':'error'}])
def test_malformed_source_preserves_original_failure_and_bounded_raw(tmp_path, raw):
    class Client:
        def history_kline_envelope(self, *args):return raw
    with FileEvidenceStore(tmp_path, compress=True) as store:
        compact=CompactEvidence(store)
        market=HtxMarketData(Client(),evidence_store=store,compact=compact)
        for _ in range(3):
            with pytest.raises(ValueError):market.kline_window('BTC-USDT','15min',160,now_epoch=900)
        keys=store.keys('compact_data_anomaly')
        assert len(keys)==1
        assert store.get(keys[0])['payload']['response']==raw
        assert store.checkpoint('failure:compact:BTC-USDT:15min')['count']==3


def test_index_write_failure_then_retry_keeps_cursor_and_recovers_objects(tmp_path):
    w=window(count=3)
    class Client:
        def history_kline_envelope(self,*args):return envelope(w)
    with FileEvidenceStore(tmp_path,compress=True) as store:
        compact=CompactEvidence(store)
        market=HtxMarketData(Client(),evidence_store=store,compact=compact)
        store.db.execute('PRAGMA query_only=ON')
        failed=market.kline_window('BTC-USDT','15min',3,now_epoch=3600)
        assert failed.is_complete and failed.evidence_status=='FAILED'
        assert store.checkpoint(compact.stream('BTC-USDT','15min')) is None
        store.db.execute('PRAGMA query_only=OFF')
        recovered=market.kline_window('BTC-USDT','15min',3,now_epoch=3600)
        assert recovered.evidence_status=='COMPLETE'
        assert store.verify(recovered.evidence_refs[0])['objects']==4
        assert len(store.keys('compact_candle'))==3


def test_concurrent_same_window_has_one_identity_and_no_lost_observations(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    w=window(count=3)
    with FileEvidenceStore(tmp_path,compress=True) as store:
        compact=CompactEvidence(store)
        def capture(i):return compact.capture(w,envelope(w),observed_at=i,decision_as_of=i)
        with ThreadPoolExecutor(max_workers=4) as pool:refs=list(pool.map(capture,range(32)))
        assert len(set(refs))==1
        assert len(store.keys('compact_kline_window'))==1
        assert store.checkpoint(compact.stream('BTC-USDT','15min'))['observations']==32


def test_unused_unaligned_row_audit_exception_does_not_change_market_status(tmp_path):
    w=window(first=900)
    payload=envelope(w)
    payload['data'].insert(0,{**payload['data'][0],'id':1})
    class Client:
        def history_kline_envelope(self,*args):return payload
    with FileEvidenceStore(tmp_path/'old') as old, FileEvidenceStore(tmp_path/'new',compress=True) as new:
        legacy=HtxMarketData(Client(),evidence_store=old).kline_window('BTC-USDT','15min',160,now_epoch=145810)
        compact=HtxMarketData(Client(),evidence_store=new,compact=CompactEvidence(new)).kline_window('BTC-USDT','15min',160,now_epoch=145810)
        assert legacy.candles==compact.candles==w.candles
        assert legacy.evidence_status==compact.evidence_status=='COMPLETE'
        assert len(new.keys('compact_data_anomaly'))==1


def test_checkpoint_failure_between_revision_and_stream_reconciles_cached_window(tmp_path, monkeypatch):
    w=window(count=3)
    changed=replace(w,candles=(*w.candles[:-1],replace(w.candles[-1],close=D('102'))))
    with FileEvidenceStore(tmp_path,compress=True) as store:
        compact=CompactEvidence(store)
        ref=compact.capture(w,envelope(w),observed_at=1,decision_as_of=1)
        checkpoint=store.checkpoint
        def fail(name,value=None):
            if value is not None and name==compact.stream('BTC-USDT','15min'):raise OSError('checkpoint full')
            return checkpoint(name,value)
        monkeypatch.setattr(store,'checkpoint',fail)
        with pytest.raises(OSError):compact.capture(changed,envelope(changed),observed_at=2,decision_as_of=2)
        monkeypatch.setattr(store,'checkpoint',checkpoint)
        assert compact.capture(w,envelope(w),observed_at=3,decision_as_of=3)==ref
        assert sorted(store.get(k)['payload']['observed_at'] for k in store.keys('compact_kline_revision'))==[2,3]
        latest=store.db.execute('SELECT hash FROM compact_latest WHERE symbol=? AND period=? AND bar_time=?',
                               ('BTC-USDT','15min',w.candles[-1].open_time)).fetchone()[0]
        assert D(store.get(latest)['payload']['candle']['close'])==D('101')
