import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from test_cos_archive import MemoryBackend
from htx_trend.sealing.control import Control, SealingError, NeedsRestore, initialize, lease
from htx_trend.sealing.lifecycle import archive_sealed, preview_release, apply_release
from htx_trend.archive.transfer import restore
from htx_trend.research.store import FileEvidenceStore, read_object
from htx_trend.realtime.storage import SegmentRecorder


def store_fixture(tmp_path, **options):
    root = tmp_path / 'source'
    initialize(root, kind='cas', contract='paper', max_bytes=1000, **options)
    store = FileEvidenceStore(root, compress=True, segmented=True, segment_contract='paper')
    store.segments.begin('paper')
    return store


def test_cas_seal_archive_pin_release_and_exact_restore(tmp_path):
    backend, work = MemoryBackend(), tmp_path / 'work'
    with store_fixture(tmp_path) as store:
        a = store.put('raw', {'value': 'a'})
        b = store.put('decision', {'value': 'b'}, refs=(a,))
        c = store.segments
        c.ready('paper',[b])
        epoch = c.seal(force=True)
        batch = archive_sealed(backend,store.root,work,min_free=0)
        assert len(batch['receipts']) == 1
        p = preview_release(backend,store.root,epoch,tmp_path/'protected.sqlite3',work=work,min_free=0)
        assert p['eligible_bytes'] == 0
        c.ready('paper',[])
        p = preview_release(backend,store.root,epoch,tmp_path/'release.sqlite3',work=work,min_free=0)
        assert p['counts']['eligible'] == 2
        result = apply_release(backend,p['plan'],p['approval_sha256'],work=work,min_free=0)
        assert result['status'] == 'RETIRED'
        assert store.root.joinpath('index.sqlite3').exists()
        with pytest.raises(NeedsRestore):store.get(b)
        restored = tmp_path/'restored'
        restore(backend,p['manifest'],restored,scratch=work,all_files=True,min_free=0)
        assert read_object(restored,b)['refs'] == [a]
        with FileEvidenceStore(restored) as plain:
            assert plain.verify(b)['objects'] == 2
        # Same bytes may be generated locally again; no COS call in writer.
        assert store.put('raw',{'value':'a'}) == a
        assert store.get(a)


def test_open_cross_generation_refs_and_failed_consumer_protect(tmp_path):
    b,work = MemoryBackend(),tmp_path/'work'
    with store_fixture(tmp_path) as store:
        a = store.put('raw',{'v':1})
        c = store.segments
        with pytest.raises(SealingError,match='handoff'):c.seal(force=True)
        c.ready('paper',[])
        epoch = c.seal(force=True)
        new = store.put('decision',{'v':2},refs=(a,))
        archive_sealed(b,store.root,work,min_free=0)
        p = preview_release(b,store.root,epoch,tmp_path/'plan.sqlite3',work=work,min_free=0)
        assert p['eligible_bytes'] == 0 # current generation references old raw
        c.begin('paper')
        with pytest.raises(SealingError,match='handoff'):
            preview_release(b,store.root,epoch,tmp_path/'blocked.sqlite3',work=work,min_free=0)
        assert store.get(new)


def test_new_pin_after_preview_blocks_release_and_reader_lease(tmp_path):
    b,work=MemoryBackend(),tmp_path/'work'
    with store_fixture(tmp_path) as store:
        a=store.put('raw',{'v':1});c=store.segments;c.ready('paper',[])
        epoch=c.seal(force=True);archive_sealed(b,store.root,work,min_free=0)
        p=preview_release(b,store.root,epoch,tmp_path/'plan.sqlite3',work=work,min_free=0)
        c.ready('paper',[a])
        assert apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)['removed_this_run']==0
        c.ready('paper',[])
        with lease(store.root):
            with pytest.raises(SealingError,match='in use'):
                apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
        assert store.get(a)


def test_capture_reclaim_updates_quota_and_full_audit_requires_restore(tmp_path):
    root=tmp_path/'capture'/'segments'
    initialize(root,kind='capture',contract='capture-public',max_bytes=1)
    recorder=SegmentRecorder(root,segmented=True,max_bytes=2000,segment_bytes=1)
    recorder.append({'kind':'DEPTH','received_ms':1,'data':[1]})
    first=recorder.last_ref
    recorder.segment_boundary() # first generation sealed, last_ref pinned
    backend,work=MemoryBackend(),tmp_path/'work'
    archive_sealed(backend,root,work,min_free=0)
    recorder.append({'kind':'DEPTH','received_ms':2,'data':[2]})
    recorder.segment_boundary()
    archive_sealed(backend,root,work,min_free=0)
    p=preview_release(backend,root,1,tmp_path/'plan.sqlite3',work=work,min_free=0)
    before=recorder.used
    assert p['counts']['eligible']==1
    assert apply_release(backend,p['plan'],p['approval_sha256'],work=work,min_free=0)['removed_this_run']==1
    recorder._refresh_released()
    assert recorder.used < before
    with pytest.raises(ValueError,match='archived generations'):recorder.verify()
    restore(backend,p['manifest'],tmp_path/'restore',scratch=work,all_files=True,min_free=0)
    restored=SegmentRecorder(tmp_path/'restore'/'segments')
    assert restored.verify()[0]['received_ms']==1
    assert not (root/(first+'.json.gz')).exists()
    recorder.segments.close()


def test_legacy_default_guard_and_explicit_enrollment(tmp_path):
    root=tmp_path/'legacy'
    with FileEvidenceStore(root) as store:
        key=store.put('raw',{'old':True})
    with pytest.raises(SealingError,match='stopped-writer'):
        initialize(root,kind='cas',contract='paper')
    initialize(root,kind='cas',contract='paper',enroll_existing=True,writer_stopped=True)
    with pytest.raises(ValueError,match='segment-aware'):
        FileEvidenceStore(root)
    with FileEvidenceStore(root,segmented=True,segment_contract='paper') as store:
        assert store.get(key)['payload']['old']


def test_outbox_obligation_explicit_and_compact_latest_is_pinned(tmp_path):
    b,work=MemoryBackend(),tmp_path/'work'
    with store_fixture(tmp_path,delivery_consumer=True) as store:
        key=store.put('raw',{'v':1});c=store.segments;c.ready('paper',[])
        epoch=c.seal(force=True);archive_sealed(b,store.root,work,min_free=0)
        p=preview_release(b,store.root,epoch,tmp_path/'outbox.sqlite3',work=work,min_free=0)
        assert p['eligible_bytes']==0
        assert store.db.execute('SELECT delivered FROM outbox').fetchone()[0]==0


def test_network_failure_does_not_delete_or_block_local_writer(tmp_path):
    b,work=MemoryBackend(),tmp_path/'work'
    with store_fixture(tmp_path) as store:
        key=store.put('raw',{'v':1});c=store.segments;c.ready('paper',[]);c.seal(force=True)
        b.fail=True
        with pytest.raises(OSError):archive_sealed(b,store.root,work,min_free=0)
        assert store.get(key)
        assert store.put('still_running',{'v':2})
        assert c.status()['generations'][0]['state']=='SEALED'


def archived(tmp_path):
    b, work = MemoryBackend(), tmp_path/'work'
    store = store_fixture(tmp_path)
    key = store.put('raw',{'v':1})
    store.segments.ready('paper',[])
    epoch = store.segments.seal(force=True)
    archive_sealed(b,store.root,work,min_free=0)
    p = preview_release(b,store.root,epoch,tmp_path/'release.sqlite3',work=work,min_free=0)
    return store,b,work,key,p


def test_interrupted_delete_resumes_but_never_removes_recreated_file(tmp_path,monkeypatch):
    import htx_trend.sealing.lifecycle as lc
    store,b,work,key,p = archived(tmp_path)
    sync = lc.sync_directory
    def crash(_): raise OSError('power loss after unlink')
    monkeypatch.setattr(lc,'sync_directory',crash)
    with pytest.raises(OSError):apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
    assert store.segments.db.execute('SELECT state FROM deletes').fetchone()[0]=='INTENT'
    monkeypatch.setattr(lc,'sync_directory',sync)
    assert apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)['status']=='RETIRED'
    assert apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)['removed_this_run']==0
    store.put('raw',{'v':1})
    assert apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)['removed_this_run']==0
    assert store.get(key)
    store.close()


def test_restore_original_layout_and_rollback_guard(tmp_path):
    from htx_trend.sealing.lifecycle import restore_local,rollback_layout
    store,b,work,key,p=archived(tmp_path)
    apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
    with pytest.raises(NeedsRestore):rollback_layout(store.root,writer_stopped=True)
    assert restore_local(b,store.root,work=work,min_free=0)['remaining_cold']==0
    assert store.get(key)
    assert rollback_layout(store.root,writer_stopped=True)['status']=='LEGACY_LAYOUT_VERIFIED'
    assert restore_local(b,store.root,work=work,min_free=0)['restored']==0
    store.close()


def test_orphan_publication_fences_maintenance_until_offline_reconcile(tmp_path,monkeypatch):
    store=store_fixture(tmp_path);c=store.segments
    original=c.track
    def fail(*a,**k):raise OSError('control unavailable after CAS publication')
    monkeypatch.setattr(c,'track',fail)
    with pytest.raises(OSError):store.put('orphan',{'v':9})
    c.ready('paper',[])
    with pytest.raises(SealingError,match='mutation'):c.seal(force=True)
    assert c.db.execute('SELECT count(*) FROM mutations').fetchone()[0]==1
    root=store.root;store.close()
    initialize(root,kind='cas',contract='paper',max_bytes=1000,enroll_existing=True,writer_stopped=True)
    with FileEvidenceStore(root,segmented=True,segment_contract='paper') as recovered:
        rc=recovered.segments
        assert rc.db.execute('SELECT count(*) FROM mutations').fetchone()[0]==0
        assert rc.db.execute('SELECT count(*) FROM objects').fetchone()[0]==1
        with pytest.raises(SealingError):rc.seal(force=True) # explicit fresh writer handoff still needed
        rc.ready('paper',[])
        assert rc.seal(force=True)==1


def test_backfill_alone_cannot_authorize_paper_release(tmp_path):
    with store_fixture(tmp_path) as store:
        store.put('raw',{'v':1})
        c=store.segments;c.ready('compact-backfill',[])
        with pytest.raises(SealingError,match='handoff'):c.seal(force=True)


@pytest.mark.parametrize('failure',['begin','ready','roots'])
def test_sealing_failure_does_not_skip_or_discard_position_management(tmp_path,monkeypatch,failure):
    import sqlite3
    from htx_trend.sealing import consumers
    with store_fixture(tmp_path) as store:
        c=store.segments
        worker=SimpleNamespace(runtime=SimpleNamespace(_evidence_store=store),_checkpoint_failed=False,_failures={})
        calls=[]
        def original(self):calls.append('managed');return {'positions':'persisted'}
        def fail(*a,**k):raise sqlite3.OperationalError('injected control failure')
        monkeypatch.setattr(consumers,'paper_roots',lambda *a:set())
        if failure=='roots':monkeypatch.setattr(consumers,'paper_roots',fail)
        else:monkeypatch.setattr(c,failure,fail)
        assert consumers.paper_tick(original)(worker)=={'positions':'persisted'}
        assert calls==['managed']
        assert (c.directory/'BLOCK_RELEASE').exists()
        with pytest.raises(SealingError):c.pin_snapshot()


def test_pin_latest_closed_positions_and_unconfirmed_results(tmp_path):
    from htx_trend.sealing.consumers import paper_roots
    with store_fixture(tmp_path) as store:
        a,b,c,d=[store.put('raw',{'v':n}) for n in range(4)]
        ledger=SimpleNamespace(data={'positions':{'old':{'status':'CLOSED','account_id':1,'contract_code':'BTC','closed_at':'2026-01-01'},
              'last':{'status':'CLOSED','account_id':1,'contract_code':'BTC','closed_at':'2026-01-02'},'open':{'status':'OPEN'}},
            'contexts':{'old':{'evidence_ref':a},'last':{'evidence_ref':b},'open':{'evidence_ref':c}},'orders':{},
            'decisions':[{'kind':'EVIDENCE_RESULT','payload':{'evidence_ref':d}}]})
        worker=SimpleNamespace(ledger=ledger,runtime=SimpleNamespace(_evidence_store=store))
        assert paper_roots(worker,store.segments)=={b,c,d}


def test_compact_revision_anchors_and_collector_checkpoint_contract(tmp_path):
    with store_fixture(tmp_path) as store:
        a=store.put('raw',{'v':1});c=store.segments
        store.db.execute('CREATE TABLE compact_latest(hash TEXT)');store.db.execute('INSERT INTO compact_latest VALUES(?)',(a,));store.db.commit()
        c.ready('paper',[]);c.seal(force=True)
        archive_sealed(MemoryBackend(),store.root,tmp_path/'work',min_free=0)
        assert a in c.pin_snapshot()[1]
    root=tmp_path/'collector'
    with FileEvidenceStore(root,segmented=True,segment_contract='collector') as store:
        old=store.put('window',{'v':1});new=store.put('window',{'v':2})
        store.checkpoint('coverage:BTC',{'ref':old});store.checkpoint('continuous:BTC',{'ref':new})
        c=store.segments;c.ready('continuous-collector',[]);c.seal(force=True)
        archive_sealed(MemoryBackend(),root,tmp_path/'work2',min_free=0)
        assert old not in c.pin_snapshot()[1] and new in c.pin_snapshot()[1]


def test_runtime_coverage_pins_all_overlapping_segments():
    from htx_trend.sealing.consumers import capture_roots
    recorder=SimpleNamespace(last_ref='latest',records=[{'hash':str(i),'first_received_ms':i*10,'last_received_ms':i*10+9} for i in range(4)])
    worker=SimpleNamespace(recorder=recorder,management=SimpleNamespace(coverage=SimpleNamespace(books={'BTC':[(0,12),(1,28)]})))
    assert capture_roots(worker)=={'1','2','latest'}


def test_scheduler_archives_only_sealed_and_bounds_private_staging(tmp_path):
    from htx_trend.archive.scheduler import Scheduler
    with store_fixture(tmp_path) as store:
        store.put('raw',{'v':1});store.segments.ready('paper',[]);store.segments.seal(force=True)
        store.put('raw',{'v':2})
        b=MemoryBackend();work=tmp_path/'work'
        config={'schedule':{'enabled':True,'interval_seconds':900},'sources':{'paper':{'kind':'segments','root':str(store.root)}}}
        result=Scheduler(b,work,config,min_free=0).tick(now=1000)
        assert result['status']=='OK'
        assert len(result['sources']['paper']['receipts'])==1
        assert not list(work.glob('sealed-*'))
        assert store.segments.status()['generations'][-1]['state']=='OPEN'
        assert len(list(store.root.glob('objects/*/*')))==2


def test_public_capture_reader_sees_segmented_data_and_refuses_partial_history(tmp_path):
    from htx_trend.realtime.public_capture import iter_records,read_reference
    root=tmp_path/'capture';seg=root/'segments'
    initialize(seg,kind='capture',contract='capture-public',max_bytes=1)
    rec=SegmentRecorder(seg,segmented=True,segment_bytes=1)
    rec.append({'kind':'DEPTH','received_ms':1});rec.segment_boundary()
    rows=list(iter_records(root));assert len(rows)==1
    assert read_reference(root,rows[0][1])==rows[0][0]
    rec.append({'kind':'DEPTH','received_ms':2});rec.segment_boundary()
    b,work=MemoryBackend(),tmp_path/'work';archive_sealed(b,seg,work,min_free=0)
    p=preview_release(b,seg,1,tmp_path/'release.sqlite3',work=work,min_free=0)
    apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
    with pytest.raises(NeedsRestore):list(iter_records(root))
    with pytest.raises(NeedsRestore):read_reference(root,rows[0][1])
    from htx_trend.sealing.lifecycle import restore_local,rollback_layout
    restore_local(b,seg,work=work,min_free=0)
    assert len(list(iter_records(root)))==2
    assert rollback_layout(seg,writer_stopped=True)['status']=='LEGACY_LAYOUT_VERIFIED'
    assert len((seg/'segments.jsonl').read_text().splitlines())==2
    rec.segments.close()


def test_nested_and_parallel_shared_leases_do_not_unlock_each_other(tmp_path):
    import threading
    with store_fixture(tmp_path) as store:
        acquired=threading.Event();release=threading.Event()
        def reader():
            with lease(store.root):acquired.set();release.wait(3)
        thread=threading.Thread(target=reader);thread.start();assert acquired.wait(3)
        with lease(store.root):
            with lease(store.root):pass
            with pytest.raises(SealingError):
                with lease(store.root,exclusive=True,blocking=False):pass
        with pytest.raises(SealingError):
            with lease(store.root,exclusive=True,blocking=False):pass
        release.set();thread.join()
        with lease(store.root,exclusive=True,blocking=False):pass


def test_fence_after_pin_scan_blocks_short_release_handoff(tmp_path,monkeypatch):
    store,b,work,key,p=archived(tmp_path)
    original=Control.pin_snapshot
    def fence(self):
        value=original(self);self.block_release();return value
    monkeypatch.setattr(Control,'pin_snapshot',fence)
    with pytest.raises(SealingError,match='handoff'):
        apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
    assert store.get(key)
    store.close()


def test_interrupted_unlink_cannot_accept_new_pin_and_can_be_rehydrated(tmp_path,monkeypatch):
    import htx_trend.sealing.lifecycle as lc
    store,b,work,key,p=archived(tmp_path)
    original=lc.sync_directory
    monkeypatch.setattr(lc,'sync_directory',lambda _: (_ for _ in ()).throw(OSError('crash')))
    with pytest.raises(OSError):apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
    with pytest.raises(NeedsRestore):store.segments.ready('paper',[key])
    monkeypatch.setattr(lc,'sync_directory',original)
    assert lc.restore_local(b,store.root,work=work,min_free=0)['restored']==1
    store.segments.ready('paper',[key])
    assert apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)['removed_this_run']==0
    assert store.get(key)
    store.close()


def test_capture_mutation_failure_returns_failure_without_escaping_management(tmp_path,monkeypatch):
    import sqlite3
    rec=SegmentRecorder(tmp_path/'capture',segmented=True,segment_bytes=1)
    def fail():raise sqlite3.OperationalError('sidecar full')
    monkeypatch.setattr(rec.segments,'mutation',fail)
    assert rec.append({'kind':'BBO','received_ms':1}) is False
    assert rec.failure=='CAPTURE_CONTROL_IO'
    assert (rec.segments.directory/'BLOCK_RELEASE').exists()
    rec.segments.close()


def test_consumer_heartbeat_does_not_rescan_capture_files(tmp_path,monkeypatch):
    rec=SegmentRecorder(tmp_path/'capture',segmented=True,segment_bytes=1)
    rec.append({'kind':'BBO','received_ms':1})
    rec.segment_boundary()
    def unexpected():raise AssertionError('full rescan on consumer-only change')
    monkeypatch.setattr(rec.segments,'local_records',unexpected)
    rec.segments.begin('recorder');rec.segments.ready('recorder',[rec.last_ref])
    assert rec.append({'kind':'BBO','received_ms':2})
    rec.segments.close()


def test_enrollment_splits_historical_members_and_preserves_originals(tmp_path):
    root=tmp_path/'old'
    with FileEvidenceStore(root) as store:
        keys=[store.put('raw',{'v':n}) for n in range(7)]
    initialize(root,kind='cas',contract='paper',max_files=2,enroll_existing=True,writer_stopped=True)
    c=Control(root)
    assert len(c.status()['generations'])==4
    assert max(n for n, in c.db.execute('SELECT count(*) FROM members GROUP BY generation'))==2
    with pytest.raises(SealingError):c.pin_snapshot()
    for key in keys:assert read_object(root,key)
    c.close()


def test_old_full_source_adapter_cannot_silently_snapshot_segmented_source(tmp_path):
    from htx_trend.archive.transfer import archive
    with store_fixture(tmp_path) as store:
        store.put('raw',{'v':1})
        with pytest.raises(ValueError,match='segmented active'):
            archive(MemoryBackend(),tmp_path/'work','wrong',{'kind':'cas','root':str(store.root)},min_free=0)


def test_corrupt_remote_pack_prevents_release(tmp_path):
    from htx_trend.archive.transfer import open_catalogue
    store,b,work,key,p=archived(tmp_path)
    with open_catalogue(b,p['manifest'],work,0) as (_,catalogue):
        pack=catalogue.execute('SELECT pack FROM files LIMIT 1').fetchone()[0]
    b.objects[pack]=b'corrupt'
    with pytest.raises(Exception):apply_release(b,p['plan'],p['approval_sha256'],work=work,min_free=0)
    assert store.get(key)
    store.close()


def test_legacy_unknown_ownership_stays_pinned_until_explicit_review(tmp_path):
    root=tmp_path/'legacy'
    with FileEvidenceStore(root) as store:key=store.put('raw',{'old':True})
    initialize(root,kind='cas',contract='paper',enroll_existing=True,writer_stopped=True)
    with FileEvidenceStore(root,segmented=True,segment_contract='paper') as store:
        c=store.segments;c.ready('paper',[]);epoch=c.seal(force=True)
        b,work=MemoryBackend(),tmp_path/'work';archive_sealed(b,root,work,min_free=0)
        assert preview_release(b,root,epoch,tmp_path/'keep.sqlite3',work=work,min_free=0)['eligible_bytes']==0
        c.external_pin('external:frozen',[key],reason='frozen local manifest root')
        c.external_pin('external:legacy-enrollment',[key],remove=True,reason='reviewed exact archived mapping')
        assert preview_release(b,root,epoch,tmp_path/'still-keep.sqlite3',work=work,min_free=0)['eligible_bytes']==0
        with pytest.raises(SealingError):c.external_pin('paper',[key],remove=True,reason='cannot spoof runtime')
        c.external_pin('external:frozen',[key],remove=True,reason='verified independent cache restored')
        assert preview_release(b,root,epoch,tmp_path/'eligible.sqlite3',work=work,min_free=0)['counts']['eligible']==1
