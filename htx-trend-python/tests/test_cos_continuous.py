import json
import os
from pathlib import Path
import sqlite3

import pytest

from test_cos_archive import MemoryBackend, cas_fixture
from htx_trend.archive.backend import ArchiveError
from htx_trend.archive.cache import ReplayCache
from htx_trend.archive.retirement import preview, apply
from htx_trend.archive.scheduler import Scheduler
from htx_trend.archive.transfer import archive, exclusive, restore
from htx_trend.research.store import FileEvidenceStore, read_object


def test_incremental_skips_immutable_reads_and_noop_has_no_objects(tmp_path, monkeypatch):
    spec, a, b = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    first = archive(backend, work, 'a', spec, min_free=0)
    puts = backend.puts
    import htx_trend.archive.transfer as transfer
    original = transfer.stable_read
    seen = []
    def observed(path, *args):
        seen.append(str(path))
        return original(path, *args)
    monkeypatch.setattr(transfer, 'stable_read', observed)
    second = archive(backend, work, 'b', spec, previous=first['manifest'], min_free=0)
    assert second['unchanged'] and second['manifest'] == first['manifest']
    assert backend.puts == puts
    assert not any('/objects/' in p for p in seen)
    with FileEvidenceStore(Path(spec['root'])) as store:
        c = store.put('revision', {'v': 2}, refs=(b,))
    seen.clear()
    third = archive(backend, work, 'c', spec, previous=first['manifest'], min_free=0)
    assert third['files'] == 4
    assert len([p for p in seen if '/objects/' in p]) == 1
    restore(backend, third['manifest'], tmp_path / 'restored', scratch=work, cas=[c], min_free=0)
    assert read_object(tmp_path / 'restored', a)


def test_incremental_detects_restored_mtime(tmp_path):
    root = tmp_path / 'source'
    root.mkdir()
    p = root / 'sample.json'
    p.write_text('{"a":1}')
    spec = {'kind': 'tree', 'root': str(root), 'extensions': ['.json']}
    backend, work = MemoryBackend(), tmp_path / 'work'
    a = archive(backend, work, 'a', spec, min_free=0)
    stat = p.stat()
    p.write_text('{"a":2}')
    os.utime(p, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    b = archive(backend, work, 'b', spec, min_free=0, previous=a['manifest'])
    assert b['manifest'] != a['manifest']
    restore(backend, b['manifest'], tmp_path / 'result', scratch=work, all_files=True, min_free=0)
    assert (tmp_path / 'result/sample.json').read_text() == '{"a":2}'


def test_scheduler_disabled_backoff_resume_independent_sources(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    config = {'sources': {'paper': spec}, 'schedule': {'enabled': False, 'interval_seconds': 900}}
    s = Scheduler(backend, tmp_path / 'work', config, min_free=0)
    assert s.tick(1000)['status'] == 'DISABLED'
    config['schedule']['enabled'] = True
    backend.fail = True
    assert s.tick(1000)['status'] == 'DEGRADED'
    state1 = json.loads((s.control / 'state.json').read_bytes())
    assert s.tick(1001)['sources']['paper']['status'] == 'WAITING'
    result = s.tick(1060)
    assert result['sources']['paper']['recovered_failures'] == 1
    state2 = json.loads((s.control / 'state.json').read_bytes())
    assert state1['sources']['paper']['generation'] == state2['sources']['paper']['generation']
    assert Scheduler(backend, tmp_path / 'work', config, min_free=0).tick(1960)['sources']['paper']['status'] == 'UNCHANGED'
    assert len(list((tmp_path / 'work').glob('scheduled-*'))) == 1
    with exclusive(s.control), pytest.raises(ArchiveError, match='another archive'):
        s.tick(3000)


def test_cache_offline_hit_lock_and_corruption(tmp_path):
    spec, a, b = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    receipt = archive(backend, work, 'a', spec, min_free=0)
    cache = ReplayCache(backend, tmp_path / 'cache', max_bytes=1024**2, min_free=0)
    with cache.lease(receipt['manifest'], cas=[b]) as data:
        assert read_object(data, a)
        with pytest.raises(ArchiveError, match='another archive'):
            with cache.lease(receipt['manifest'], cas=[b]):
                pass
    reads = len(backend.reads)
    saved, backend.objects = backend.objects, {}
    with cache.lease(receipt['manifest'], cas=[b]) as data:
        assert read_object(data, b)
        (data / 'objects' / a[:2] / (a + '.json')).write_text('{}')
    assert len(backend.reads) == reads
    with pytest.raises(KeyError):
        with cache.lease(receipt['manifest'], cas=[b]):
            pass
    backend.objects = saved
    with cache.lease(receipt['manifest'], cas=[b]) as data:
        assert read_object(data, a)


def test_cache_quota_and_verify_only(tmp_path):
    spec, _, b = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    receipt = archive(backend, work, 'a', spec, min_free=0)
    with pytest.raises(ArchiveError, match='quota'):
        with ReplayCache(backend, tmp_path / 'cache', max_bytes=1, min_free=0).lease(receipt['manifest'], cas=[b]):
            pass
    result = restore(backend, receipt['manifest'], tmp_path / 'verify', scratch=work,
                     all_files=True, verify_only=True, min_free=0)
    assert result['scope'] == 'verification_only'
    assert not list((tmp_path / 'verify').rglob('*.json.gz'))
    assert not (tmp_path / 'verify/index.sqlite3').exists()


def test_retirement_active_protected_and_frozen_recoverable(tmp_path):
    spec, a, b = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    receipt = archive(backend, work, 'a', spec, min_free=0)
    p = preview(backend, receipt['manifest'], spec, {}, tmp_path / 'active.sqlite3', scratch=work, min_free=0)
    assert p['eligible_bytes'] == 0
    policy = {'state': 'FROZEN', 'no_local_readers': True, 'attestation': 'isolated test source; closed writer'}
    p = preview(backend, receipt['manifest'], spec, policy, tmp_path / 'plan.sqlite3', scratch=work, min_free=0)
    assert p['counts']['eligible'] == 2
    with pytest.raises(ArchiveError, match='hash mismatch'):
        apply(backend, p['plan'], 'wrong', policy, scratch=work, min_free=0)
    r = apply(backend, p['plan'], p['approval_sha256'], policy, scratch=work, min_free=0)
    assert r['released_this_run'] == p['eligible_bytes']
    assert Path(spec['root'], 'index.sqlite3').exists()
    assert apply(backend, p['plan'], p['approval_sha256'], policy, scratch=work, min_free=0)['released_this_run'] == 0
    restore(backend, receipt['manifest'], tmp_path / 'restored', scratch=work, all_files=True, min_free=0)
    assert read_object(tmp_path / 'restored', b)['refs'] == [a]


def test_retirement_remote_failure_and_source_change_no_deletion(tmp_path):
    spec, a, _ = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    receipt = archive(backend, work, 'a', spec, min_free=0)
    policy = {'state': 'FROZEN', 'no_local_readers': True, 'attestation': 'isolated'}
    p = preview(backend, receipt['manifest'], spec, policy, tmp_path / 'plan.sqlite3', scratch=work, min_free=0)
    saved, backend.objects = backend.objects, {}
    with pytest.raises(KeyError):
        apply(backend, p['plan'], p['approval_sha256'], policy, scratch=work, min_free=0)
    source = Path(spec['root']) / 'objects' / a[:2] / (a + '.json')
    assert source.exists()
    backend.objects = saved
    source.write_text('{}')
    with pytest.raises(ArchiveError, match='source changed'):
        apply(backend, p['plan'], p['approval_sha256'], policy, scratch=work, min_free=0)
    assert source.exists()


def test_backoff_is_degraded_and_source_reset_preserves_bad_snapshot(tmp_path, monkeypatch):
    from htx_trend.archive.backend import SnapshotChanged
    import htx_trend.archive.scheduler as module
    spec, _, _ = cas_fixture(tmp_path)
    b = MemoryBackend()
    c = {'sources': {'paper': spec}, 'schedule': {'enabled': True, 'interval_seconds': 900}}
    scheduler = Scheduler(b, tmp_path / 'work', c, min_free=0)
    b.fail = True
    assert scheduler.tick(1000)['status'] == 'DEGRADED'
    assert scheduler.tick(1001)['status'] == 'DEGRADED'
    original = module.archive
    def invalid(*args, **kwargs):
        raise SnapshotChanged('changed')
    monkeypatch.setattr(module, 'archive', invalid)
    scheduler.tick(1060)
    assert scheduler.tick(9999)['sources']['paper']['status'] == 'BLOCKED'
    scheduler.reset_pending('paper', 'source checked; newer immutable boundary')
    assert list(scheduler.control.glob('*.superseded.json'))
    monkeypatch.setattr(module, 'archive', original)
    assert scheduler.tick(10000)['status'] == 'OK'


def test_stale_archive_cannot_retire_newer_source(tmp_path):
    spec, a, b = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    r = archive(backend, work, 'a', spec, min_free=0)
    with FileEvidenceStore(Path(spec['root'])) as store:
        store.put('new', {'v': 1}, refs=(b,))
    policy = {'state': 'FROZEN', 'no_local_readers': True, 'attestation': 'test'}
    p = preview(backend, r['manifest'], spec, policy, tmp_path / 'stale.sqlite3', scratch=work, min_free=0)
    assert p['eligible_bytes'] == 0 and 'source-boundary-changed' in p['counts']


def test_wal_cannot_change_approved_plan(tmp_path):
    from htx_trend.archive.retirement import approved_plan
    from htx_trend.archive.backend import file_hash
    p = tmp_path / 'plan.sqlite3'
    db = sqlite3.connect(p)
    db.execute('CREATE TABLE value(v)')
    db.execute('INSERT INTO value VALUES(1)')
    db.commit()
    db.close()
    original, approval = p.read_bytes(), file_hash(p)
    db = sqlite3.connect(p)
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('UPDATE value SET v=2')
    db.commit()
    p.write_bytes(original)
    assert file_hash(p) == approval
    with pytest.raises(ArchiveError, match='standalone'):
        with approved_plan(p, approval, tmp_path):
            pass
    db.close()


def test_cache_child_inherits_lifetime_lock(tmp_path):
    import subprocess
    import sys
    spec, _, b = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    r = archive(backend, work, 'a', spec, min_free=0)
    cache = ReplayCache(backend, tmp_path / 'cache', max_bytes=1024**2, min_free=0)
    with cache.lease(r['manifest'], cas=[b]):
        child = subprocess.Popen([sys.executable, '-c', 'import time;print("ready",flush=True);time.sleep(10)'],
                                 pass_fds=(cache.lease_fd,), stdout=subprocess.PIPE, text=True)
        assert child.stdout.readline().strip() == 'ready'
    try:
        with pytest.raises(ArchiveError, match='another archive'):
            with cache.lease(r['manifest'], cas=[b]):
                pass
    finally:
        child.terminate()
        child.wait(timeout=5)
    with cache.lease(r['manifest'], cas=[b]) as data:
        assert read_object(data, b)


def test_apply_rejects_approved_but_unarchived_member(tmp_path):
    from htx_trend.archive.backend import file_hash
    spec, _, _ = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    receipt = archive(backend, work, 'a', spec, min_free=0)
    policy = {'state': 'FROZEN', 'no_local_readers': True, 'attestation': 'isolated'}
    p = preview(backend, receipt['manifest'], spec, policy, tmp_path / 'plan.sqlite3', scratch=work, min_free=0)
    with sqlite3.connect(p['plan']) as plan:
        plan.execute("UPDATE files SET hash=? WHERE blocked IS NULL", ('f'*64,))
    with pytest.raises(ArchiveError, match='not recoverable'):
        apply(backend, p['plan'], file_hash(p['plan']), policy, scratch=work, min_free=0)
    assert len(list((Path(spec['root']) / 'objects').rglob('*.json*'))) == 2


def test_delta_chain_revisions_removed_paths_and_old_snapshot(tmp_path):
    root = tmp_path / 'source'
    root.mkdir()
    for i in range(100):
        (root / f'{i}.json').write_text(json.dumps({'v': i, 'text': 'x'*100}))
    spec = {'kind': 'tree', 'root': str(root), 'extensions': ['.json']}
    b, work = MemoryBackend(), tmp_path / 'work'
    first = archive(b, work, 'a', spec, min_free=0)
    (root / '0.json').write_text('{"changed":true}')
    second = archive(b, work, 'b', spec, min_free=0, previous=first['manifest'])
    assert second['schema'] == 'htx-cos-archive-v2' and second['chain_depth'] == 1
    (root / '1.json').unlink()
    (root / 'new.json').write_text('{}')
    third = archive(b, work, 'c', spec, min_free=0, previous=second['manifest'])
    assert third['chain_depth'] == 2
    restore(b, third['manifest'], tmp_path / 'new', scratch=work, all_files=True, min_free=0)
    assert not (tmp_path / 'new/1.json').exists()
    assert (tmp_path / 'new/new.json').exists()
    restore(b, first['manifest'], tmp_path / 'old', scratch=work, all_files=True, min_free=0)
    assert (tmp_path / 'old/1.json').exists()
    assert json.loads((tmp_path / 'old/0.json').read_bytes())['v'] == 0
    from htx_trend.archive.transfer import compact_staging
    assert compact_staging(b, work, 'c')['released_staging_bytes'] > 0
    assert not (work / 'c/snapshot.sqlite3').exists()


def test_old_three_field_pending_inventory_does_not_inherit(tmp_path, monkeypatch):
    from htx_trend.archive.snapshot import prepare
    from htx_trend.archive.transfer import inherit_verified, canonical
    from htx_trend.archive.backend import file_hash, digest
    import gzip
    spec, _, _ = cas_fixture(tmp_path)
    b, work = MemoryBackend(), tmp_path / 'work'
    first = archive(b, work, 'a', spec, min_free=0)
    old = sqlite3.connect(work / 'a/snapshot.sqlite3')
    for path, value in old.execute('SELECT path,fingerprint FROM files').fetchall():
        old.execute('UPDATE files SET fingerprint=? WHERE path=?', (json.dumps(json.loads(value)[:3]), path))
    old.commit()
    old.close()
    oldcat = work / 'legacy.gz'
    oldcat.write_bytes(gzip.compress((work / 'a/snapshot.sqlite3').read_bytes()))
    key = 'catalogues/' + file_hash(oldcat) + '.sqlite3.gz'
    b.publish(key, oldcat)
    manifest = json.loads(b.objects[first['manifest']])
    manifest.update(catalogue=key, catalogue_sha256=file_hash(oldcat), catalogue_bytes=oldcat.stat().st_size)
    mkey = 'manifests/' + digest(canonical(manifest)) + '.json'
    b.objects[mkey] = canonical(manifest)
    db = prepare(work / 'pending', spec)
    for path, value in db.execute('SELECT path,fingerprint FROM files').fetchall():
        db.execute('UPDATE files SET fingerprint=? WHERE path=?', (json.dumps(json.loads(value)[:3]), path))
    db.commit()
    inherit_verified(b, mkey, db, spec, work, 0)
    assert db.execute('SELECT count(*) FROM files WHERE raw_hash IS NOT NULL').fetchone()[0] == 0
    db.close()


def test_cache_lru_evicts_only_derived_idle_entries(tmp_path):
    root = tmp_path / 'source'
    root.mkdir()
    for n in ('a', 'b'):
        (root / (n + '.json')).write_text(json.dumps({'v': n*10000}))
    spec = {'kind': 'tree', 'root': str(root), 'extensions': ['.json']}
    backend, work = MemoryBackend(), tmp_path / 'work'
    r = archive(backend, work, 'a', spec, min_free=0)
    cache = ReplayCache(backend, tmp_path / 'cache', max_bytes=30000, min_free=0)
    with cache.lease(r['manifest'], paths=['a.json']) as data:
        first = data.parent
        assert (data / 'a.json').exists()
    with cache.lease(r['manifest'], paths=['b.json']) as data:
        assert (data / 'b.json').exists()
        assert not first.exists()
    assert (root / 'a.json').exists() and (root / 'b.json').exists()
    assert sum(cache._size(p) for p in cache._entries()) <= 30000


def test_scheduler_rolls_over_delta_depth_and_cleans_derived_work(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    config = {'sources': {'s': spec}, 'schedule': {'enabled': True, 'interval_seconds': 900}}
    scheduler = Scheduler(backend, work, config, min_free=0)
    assert scheduler.tick(1000)['status'] == 'OK'
    path = scheduler.control / 'state.json'
    state = json.loads(path.read_bytes())
    state['sources']['s']['chain_depth'] = 32
    path.write_text(json.dumps(state))
    with FileEvidenceStore(Path(spec['root'])) as store:
        store.put('new', {'a': '2'})
    assert scheduler.tick(1900)['status'] == 'OK'
    assert json.loads(path.read_bytes())['sources']['s']['chain_depth'] == 0
    assert len(list(work.glob('scheduled-*'))) == 1
    assert not list(work.glob('scheduled-*/*.sqlite3'))
