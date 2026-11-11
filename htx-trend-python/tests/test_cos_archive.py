from contextlib import contextmanager
import gzip
from io import BytesIO
import json
from pathlib import Path
import sqlite3
from types import SimpleNamespace

import pytest

from htx_trend.archive.backend import ArchiveError, file_hash
from htx_trend.archive.snapshot import prepare, safe_relative
from htx_trend.archive.transfer import archive, exclusive, restore, open_catalogue
from htx_trend.research.store import FileEvidenceStore, canonical, digest, read_object


class MemoryBackend:
    identity = 'test-cos-namespace'

    def __init__(self):
        self.objects = {}
        self.puts = 0
        self.fail = False
        self.reads = []

    def publish(self, key, path):
        data = Path(path).read_bytes()
        if key in self.objects:
            assert self.objects[key] == data
        else:
            self.objects[key] = data
            self.puts += 1
        if self.fail:
            self.fail = False
            raise OSError('failure after upload before acknowledgement')
        return file_hash(path)

    @contextmanager
    def stream(self, key, start=None, length=None):
        self.reads.append((key, start))
        data = self.objects[key]
        yield BytesIO(data if start is None else data[start:start + length])


def cas_fixture(tmp_path):
    root = tmp_path / 'source'
    with FileEvidenceStore(root, compress=False) as store:
        a = store.put('raw_kline', {'rows': [1, 2, 3]})
    with FileEvidenceStore(root, compress=True) as store:
        b = store.put('decision_trace', {'bar': 123}, refs=(a,))
        store.checkpoint('progress', {'at': 5})
    return {'kind': 'cas', 'root': str(root)}, a, b


def test_small_archive_and_full_verify_with_less_than_max_member_headroom(tmp_path, monkeypatch):
    from htx_trend.archive import transfer
    floor = 5 * 1024**3
    monkeypatch.setattr(transfer.shutil, 'disk_usage', lambda _: SimpleNamespace(free=floor + 8 * 1024**2))
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'small', spec, min_free=floor)
    outcome = restore(backend, result['manifest'], tmp_path / 'verify', scratch=work,
                      all_files=True, verify_only=True, min_free=floor)
    assert outcome['restored'] == result['files']
    assert outcome['scope'] == 'verification_only'


def test_archive_checks_actual_pack_write_before_publishing(tmp_path, monkeypatch):
    from htx_trend.archive import transfer
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    original_guard = transfer.free_guard
    writes = []
    def guard(path, minimum, needed=0):
        if needed and needed < 1024**2:
            writes.append(needed)
            raise ArchiveError('archive workspace free-space guard')
        return original_guard(path, minimum, needed)
    monkeypatch.setattr(transfer, 'free_guard', guard)
    with pytest.raises(ArchiveError, match='free-space guard'):
        archive(backend, tmp_path / 'work', 'small', spec, min_free=0)
    assert writes
    assert backend.puts == 0


def test_full_verify_checks_space_during_pack_download(tmp_path, monkeypatch):
    from htx_trend.archive import transfer
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'small', spec, min_free=0)
    original_guard = transfer.free_guard
    pack_started = False
    original_stream = backend.stream
    @contextmanager
    def stream(key, start=None, length=None):
        nonlocal pack_started
        if key.startswith('packs/'):
            pack_started = True
        try:
            with original_stream(key, start, length) as source:
                yield source
        finally:
            pack_started = False
    def guard(path, minimum, needed=0):
        if pack_started and needed:
            raise ArchiveError('archive workspace free-space guard')
        return original_guard(path, minimum, needed)
    monkeypatch.setattr(backend, 'stream', stream)
    monkeypatch.setattr(transfer, 'free_guard', guard)
    target = tmp_path / 'verify'
    with pytest.raises(ArchiveError, match='free-space guard'):
        restore(backend, result['manifest'], target, scratch=work,
                all_files=True, verify_only=True, min_free=0)
    assert not list(target.glob('.cos-restore-complete-*'))


def test_full_verify_rechecks_destination_after_pack_download(tmp_path, monkeypatch):
    from htx_trend.archive import transfer
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'small', spec, min_free=0)
    original_guard, original_stream = transfer.free_guard, backend.stream
    downloaded = False
    target = tmp_path / 'verify'
    @contextmanager
    def stream(key, start=None, length=None):
        nonlocal downloaded
        with original_stream(key, start, length) as source:
            yield source
        if key.startswith('packs/'):
            downloaded = True
    def guard(path, minimum, needed=0):
        if downloaded and Path(path) == target:
            raise ArchiveError('archive workspace free-space guard')
        return original_guard(path, minimum, needed)
    monkeypatch.setattr(backend, 'stream', stream)
    monkeypatch.setattr(transfer, 'free_guard', guard)
    with pytest.raises(ArchiveError, match='free-space guard'):
        restore(backend, result['manifest'], target, scratch=work,
                all_files=True, min_free=0)
    assert not list(target.rglob('*.json'))


def test_full_verify_hashes_bytes_outside_catalogued_member(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'small', spec, min_free=0)
    for key in backend.objects:
        if key.startswith('packs/'):
            backend.objects[key] += b'uncatalogued-tail'
    with pytest.raises(ArchiveError, match='pack hash mismatch'):
        restore(backend, result['manifest'], tmp_path / 'verify', scratch=work,
                all_files=True, verify_only=True, min_free=0)


def test_archive_still_rejects_below_minimum_free_space(tmp_path, monkeypatch):
    from htx_trend.archive import transfer
    spec, _, _ = cas_fixture(tmp_path)
    floor = 5 * 1024**3
    monkeypatch.setattr(transfer.shutil, 'disk_usage', lambda _: SimpleNamespace(free=floor - 1))
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='free-space guard'):
        archive(backend, tmp_path / 'work', 'small', spec, min_free=floor)
    assert backend.puts == 0


def test_cas_full_restore_and_reference_range_restore(tmp_path):
    spec, a, b = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'one', spec, min_free=0, pack_bytes=2048)
    assert result['files'] == 3 and result['references'] == 1
    target = tmp_path / 'restored'
    outcome = restore(backend, result['manifest'], target, scratch=work, all_files=True, min_free=0)
    assert outcome['restored'] == 3
    assert read_object(target, b)['refs'] == [a]
    with FileEvidenceStore(target) as store:
        assert store.verify(b)['objects'] == 2
        assert store.checkpoint('progress') == {'at': 5}
    ranged = tmp_path / 'ranged'
    outcome = restore(backend, result['manifest'], ranged, scratch=work, cas=[b], min_free=0)
    assert outcome['restored'] == 2
    assert read_object(ranged, a)['payload']['rows'] == [1, 2, 3]
    assert any(start is not None for key, start in backend.reads if key.startswith('packs/'))
    assert not (ranged / 'index.sqlite3').exists()


def test_repeat_and_incremental_reuse(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    first = archive(backend, work, 'one', spec, min_free=0)
    puts = backend.puts
    assert archive(backend, work, 'one', spec, min_free=0) == first
    assert backend.puts == puts
    old_packs = {k for k in backend.objects if k.startswith('packs/')}
    second = archive(backend, work, 'two', spec, min_free=0)
    assert second['files'] == first['files']
    assert old_packs == {k for k in backend.objects if k.startswith('packs/')}


def test_retry_after_remote_success_before_ack(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    backend.fail = True
    work = tmp_path / 'work'
    with pytest.raises(OSError):
        archive(backend, work, 'one', spec, min_free=0)
    assert not any(k.startswith('manifests/') for k in backend.objects)
    result = archive(backend, work, 'one', spec, min_free=0)
    assert result['files'] == 3
    assert (Path(spec['root']) / 'index.sqlite3').exists()


def test_unindexed_historical_objects_preserved(tmp_path):
    spec, a, b = cas_fixture(tmp_path)
    with sqlite3.connect(Path(spec['root']) / 'index.sqlite3') as db:
        db.execute('DELETE FROM evidence WHERE hash=?', (a,))
    result = archive(MemoryBackend(), tmp_path / 'work', 'one', spec, min_free=0)
    assert result['files'] == 3 and result['references'] == 1


def test_missing_reference_no_manifest(tmp_path):
    root = tmp_path / 'source'
    with FileEvidenceStore(root) as store:
        key = store.put('raw_kline', {'data': 1})
    value = {'schema': 'htx-python-evidence-v1', 'kind': 'decision_trace', 'payload': {}, 'refs': ['f' * 64]}
    invalid_key = digest(value)
    directory = root / 'objects' / invalid_key[:2]
    directory.mkdir(exist_ok=True)
    (directory / (invalid_key + '.json')).write_bytes(canonical(value))
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='missing CAS reference'):
        archive(backend, tmp_path / 'work', 'one', {'kind': 'cas', 'root': str(root)}, min_free=0)
    assert not any(k.startswith('manifests/') for k in backend.objects)


def test_corrupt_source(tmp_path):
    spec, a, _ = cas_fixture(tmp_path)
    (Path(spec['root']) / 'objects' / a[:2] / (a + '.json')).write_text('{}')
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='source hash mismatch'):
        archive(backend, tmp_path / 'work', 'one', spec, min_free=0)
    assert not any(k.startswith('manifests/') for k in backend.objects)


def test_capture_prefix_and_reader(tmp_path):
    root = tmp_path / 'capture'
    segments = root / 'segments'
    segments.mkdir(parents=True)
    value = {'schema': 'htx-realtime-segment-v1', 'events': [{'received_ms': 123, 'channel': 'depth'}]}
    key = digest(value)
    (segments / (key + '.json.gz')).write_bytes(gzip.compress(canonical(value), mtime=0))
    line = canonical({'hash': key, 'count': 1}) + b'\n'
    (segments / 'segments.jsonl').write_bytes(line + b'{"hash":"unfinished')
    (root / 'capture.json').write_text('{"contracts":["ETH-USDT"]}')
    work = tmp_path / 'work'
    backend = MemoryBackend()
    result = archive(backend, work, 'capture', {'kind': 'capture', 'root': str(root)}, min_free=0)
    dest = tmp_path / 'restored'
    restore(backend, result['manifest'], dest, scratch=work, all_files=True, min_free=0)
    assert (dest / 'segments/segments.jsonl').read_bytes() == line
    from htx_trend.realtime.storage import SegmentRecorder
    assert SegmentRecorder(dest / 'segments').verify() == value['events']


def test_namespace_and_job_conflict_and_lock(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    archive(backend, work, 'one', spec, min_free=0)
    with pytest.raises(ArchiveError, match='another source'):
        archive(backend, work, 'one', dict(spec, another=True), min_free=0)
    backend.identity = 'different-namespace'
    with pytest.raises(ArchiveError, match='another COS'):
        archive(backend, work, 'two', spec, min_free=0)
    with exclusive(work), pytest.raises(ArchiveError, match='another archive writer'):
        with exclusive(work):
            pass


@pytest.mark.parametrize('path', ['../x', '/x', 'a/../b', 'a//b', 'a\\b', './x'])
def test_unsafe_path(path):
    with pytest.raises(ArchiveError):
        safe_relative(path)


def test_tree_exclusions_and_restore_conflict(tmp_path):
    root = tmp_path / 'source'
    root.mkdir()
    (root / 'history.json').write_text('{"klines":[1]}')
    (root / 'secret.env').write_text('do not upload')
    (root / 'fragment.partial').write_text('do not upload')
    spec = {'kind': 'tree', 'root': str(root), 'extensions': ['.json']}
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'one', spec, min_free=0)
    assert result['files'] == 1
    dest = tmp_path / 'restored'
    dest.mkdir()
    (dest / 'history.json').write_text('existing different content')
    with pytest.raises(ArchiveError, match='conflicts'):
        restore(backend, result['manifest'], dest, scratch=work, paths=['history.json'], min_free=0)
    (dest / 'history.json').unlink()
    (dest / 'history.json').symlink_to(root / 'history.json')
    with pytest.raises(ArchiveError, match='symlink'):
        restore(backend, result['manifest'], dest, scratch=work, all_files=True, min_free=0)


def test_remote_corruption_fails_before_file_publish(tmp_path):
    spec, a, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'one', spec, min_free=0)
    for key in backend.objects:
        if key.startswith('packs/'):
            backend.objects[key] = b'corrupted'
    dest = tmp_path / 'restored'
    with pytest.raises(ArchiveError):
        restore(backend, result['manifest'], dest, scratch=work, cas=[a], min_free=0)
    assert not list(dest.rglob('*.json'))


def test_no_disk_space_no_upload(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='free-space'):
        archive(backend, tmp_path / 'work', 'one', spec, min_free=10**30)
    assert backend.puts == 0


def test_missing_indexed_object(tmp_path):
    spec, a, _ = cas_fixture(tmp_path)
    (Path(spec['root']) / 'objects' / a[:2] / (a + '.json')).unlink()
    with pytest.raises(ArchiveError, match='indexed object absent'):
        prepare(tmp_path / 'job', spec)


def test_within_pack_duplicate_bytes_use_same_offset(tmp_path):
    root = tmp_path / 'tree'
    root.mkdir()
    for name in ('one.json', 'two.json'):
        (root / name).write_text('{"same":true}')
    backend = MemoryBackend()
    work = tmp_path / 'work'
    result = archive(backend, work, 'one', {'kind': 'tree', 'root': str(root), 'extensions': ['.json']}, min_free=0)
    with open_catalogue(backend, result['manifest'], work, 0) as (_, db):
        assert db.execute('SELECT count(distinct offset) FROM files').fetchone()[0] == 1
    outcome = restore(backend, result['manifest'], tmp_path / 'restored', scratch=work, all_files=True, min_free=0)
    assert outcome['restored'] == 2


def test_snapshot_resume_does_not_take_later_cas_append(tmp_path):
    spec, a, b = cas_fixture(tmp_path)
    work = tmp_path / 'work'
    work.mkdir()
    db = prepare(work / 'one', spec)
    db.close()
    with FileEvidenceStore(Path(spec['root'])) as store:
        store.put('raw_kline', {'later': True})
    result = archive(MemoryBackend(), work, 'one', spec, min_free=0)
    assert result['files'] == 3


def test_sqlite_backup_includes_committed_wal(tmp_path):
    root = tmp_path / 'live'
    with FileEvidenceStore(root) as store:
        key = store.put('raw_kline', {'live': True})
        db = prepare(tmp_path / 'job', {'kind': 'cas', 'root': str(root)})
        assert db.execute('SELECT count(*) FROM files').fetchone()[0] == 2
        db.close()
        with sqlite3.connect(tmp_path / 'job/index.sqlite3') as snapshot:
            assert snapshot.execute('SELECT hash FROM evidence').fetchone()[0] == key


def test_capture_invalid_count_no_publish(tmp_path):
    root = tmp_path / 'capture'
    (root / 'segments').mkdir(parents=True)
    value = {'schema': 'htx-realtime-segment-v1', 'events': []}
    key = digest(value)
    (root / f'segments/{key}.json.gz').write_bytes(gzip.compress(canonical(value)))
    (root / 'segments/segments.jsonl').write_bytes(canonical({'hash': key, 'count': 5})+b'\n')
    (root / 'capture.json').write_text('{"contracts":["ETH-USDT"]}')
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='invalid capture'):
        archive(backend, tmp_path / 'work', 'one', {'kind': 'capture', 'root': str(root)}, min_free=0)
    assert not any(k.startswith('manifests/') for k in backend.objects)


def test_stable_read_rejects_mutation(tmp_path, monkeypatch):
    from htx_trend.archive.snapshot import stable_read
    path = tmp_path / 'x.json'
    path.write_bytes(b'1')
    original = Path.read_bytes
    def read_and_change(self):
        data = original(self)
        self.write_bytes(b'222')
        return data
    monkeypatch.setattr(Path, 'read_bytes', read_and_change)
    with pytest.raises(ArchiveError, match='changed'):
        stable_read(path, tmp_path, 10)


def test_workspace_cannot_be_inside_source(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    with pytest.raises(ArchiveError, match='disjoint'):
        archive(MemoryBackend(), Path(spec['root']) / 'work', 'one', spec, min_free=0)


def test_tree_changed_after_inventory_is_rejected(tmp_path):
    root = tmp_path / 'tree'
    root.mkdir()
    (root / 'history.json').write_text('1')
    spec = {'kind': 'tree', 'root': str(root), 'extensions': ['.json']}
    work = tmp_path / 'work'
    work.mkdir()
    prepare(work / 'one', spec).close()
    (root / 'history.json').write_text('222')
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='changed since inventory'):
        archive(backend, work, 'one', spec, min_free=0)
    assert backend.puts == 0


def test_missing_cas_child_added_then_same_job_recovers(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    root = Path(spec['root'])
    child = {'schema': 'htx-python-evidence-v1', 'kind': 'raw', 'refs': [], 'payload': {'later': 3}}
    child_key = digest(child)
    parent = {'schema': 'htx-python-evidence-v1', 'kind': 'decision', 'refs': [child_key], 'payload': {}}
    parent_key = digest(parent)
    p = root / 'objects' / parent_key[:2] / (parent_key + '.json')
    p.parent.mkdir(exist_ok=True)
    p.write_bytes(canonical(parent))
    work = tmp_path / 'work'
    backend = MemoryBackend()
    with pytest.raises(ArchiveError, match='missing CAS reference'):
        archive(backend, work, 'one', spec, min_free=0)
    p = root / 'objects' / child_key[:2] / (child_key + '.json')
    p.parent.mkdir(exist_ok=True)
    p.write_bytes(canonical(child))
    result = archive(backend, work, 'one', spec, min_free=0)
    assert result['files'] == 5
    outcome = restore(backend, result['manifest'], tmp_path / 'restored', scratch=work, cas=[parent_key], min_free=0)
    assert outcome['restored'] == 2


def test_capture_missing_config_cannot_publish(tmp_path):
    root = tmp_path / 'capture'
    (root / 'segments').mkdir(parents=True)
    (root / 'segments/segments.jsonl').write_bytes(b'')
    with pytest.raises(ArchiveError, match='regular file'):
        archive(MemoryBackend(), tmp_path / 'work', 'one', {'kind': 'capture', 'root': str(root)}, min_free=0)


def test_capture_orphan_preserves_incomplete_coverage(tmp_path):
    root = tmp_path / 'capture'
    (root / 'segments').mkdir(parents=True)
    value = {'schema': 'htx-realtime-segment-v1', 'events': [{'received_ms': 1}]}
    key = digest(value)
    (root / f'segments/{key}.json.gz').write_bytes(gzip.compress(canonical(value)))
    (root / 'segments/segments.jsonl').write_bytes(b'')
    (root / 'capture.json').write_text('{"contracts":["ETH-USDT"]}')
    backend, work = MemoryBackend(), tmp_path / 'work'
    result = archive(backend, work, 'one', {'kind': 'capture', 'root': str(root)}, min_free=0)
    assert json.loads(result['snapshot']['unindexed_capture_segments']) == [key]
    dest = tmp_path / 'restored'
    restored = restore(backend, result['manifest'], dest, scratch=work, all_files=True, min_free=0)
    assert restored['status'] == 'COMPLETE'
    assert len(list(dest.glob('.cos-restore-complete-*'))) == 1
    from htx_trend.realtime.storage import SegmentRecorder
    with pytest.raises(ValueError, match='incomplete coverage'):
        SegmentRecorder(dest / 'segments').verify()


def test_grouped_capture_restores_original_order_and_references(tmp_path):
    root = tmp_path / 'capture'
    (root / 'segments').mkdir(parents=True)
    a, b, c = {'received_ms': 1}, {'received_ms': 2}, {'received_ms': 3}
    value = {'schema': 'htx-realtime-grouped-v1', 'groups': [[a,c],[b]], 'order': [0,1,0]}
    key = digest(value)
    (root / f'segments/{key}.json.gz').write_bytes(gzip.compress(canonical(value)))
    (root / 'segments/segments.jsonl').write_bytes(canonical({'hash': key, 'count': 3})+b'\n')
    (root / 'capture.json').write_text('{"contracts":["ETH-USDT"]}')
    backend, work = MemoryBackend(), tmp_path / 'work'
    result = archive(backend, work, 'one', {'kind': 'capture', 'root': str(root)}, min_free=0)
    dest = tmp_path / 'restored'
    restore(backend, result['manifest'], dest, scratch=work, all_files=True, min_free=0)
    from htx_trend.realtime.public_capture import iter_records, read_reference
    rows = list(iter_records(dest))
    assert [v for v, ref in rows] == [a,b,c]
    assert read_reference(dest, rows[1][1]) == b


@pytest.mark.parametrize('order', [[0,0,0], [1,0], [-1,0,0], [True,0,0], [0]])
def test_invalid_grouped_order_rejected(order):
    from htx_trend.realtime.segment_codec import segment_events
    with pytest.raises(ValueError):
        segment_events({'schema': 'htx-realtime-grouped-v1', 'groups': [[1,3],[2]], 'order': order})


def test_cached_remote_pack_corruption_prevents_new_manifest(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    archive(backend, work, 'one', spec, min_free=0)
    for key in backend.objects:
        if key.startswith('packs/'):
            backend.objects[key] = b'corrupted'
    with pytest.raises(ArchiveError, match='cached remote pack hash'):
        archive(backend, work, 'two', spec, min_free=0)
    assert len([k for k in backend.objects if k.startswith('manifests/')]) == 1


def test_completed_staging_compaction_is_recoverable_and_repeatable(tmp_path):
    from htx_trend.archive.transfer import compact_staging
    spec, a, b = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    receipt = archive(backend, work, 'one', spec, min_free=0)
    old_size = (work / 'one/snapshot.sqlite3').stat().st_size
    result = compact_staging(backend, work, 'one')
    assert result['released_staging_bytes'] == old_size
    assert (Path(spec['root'])/'index.sqlite3').exists()
    assert (work/'one/index.sqlite3').exists()
    assert (work/'one/catalogue.sqlite3.gz').exists()
    assert not (work/'one/snapshot.sqlite3').exists()
    assert archive(backend, work, 'one', spec, min_free=0) == receipt
    assert not (work/'one/snapshot.sqlite3').exists()
    assert compact_staging(backend, work, 'one')['released_staging_bytes'] == 0
    dest = tmp_path / 'restored'
    restore(backend, receipt['manifest'], dest, scratch=work, cas=[b], min_free=0)
    assert read_object(dest, a)['kind'] == 'raw_kline'


def test_staging_compaction_rejects_unfinished_or_corrupt_copy(tmp_path):
    from htx_trend.archive.transfer import compact_staging
    spec, _, _ = cas_fixture(tmp_path)
    backend, work = MemoryBackend(), tmp_path / 'work'
    archive(backend, work, 'one', spec, min_free=0)
    raw = work/'one/snapshot.sqlite3'
    with raw.open('ab') as f:
        f.write(b'changed')
    with pytest.raises(ArchiveError, match='differs'):
        compact_staging(backend, work, 'one')
    assert raw.exists()
    (work/'one/receipt.json').unlink()
    with pytest.raises(FileNotFoundError):
        compact_staging(backend, work, 'one')
    assert raw.exists()
