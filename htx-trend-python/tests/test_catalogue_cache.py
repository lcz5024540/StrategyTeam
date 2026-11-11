from contextlib import contextmanager
import json

import pytest

from htx_trend.archive.backend import ArchiveError
from htx_trend.archive.catalogue_cache import catalogue_cache
from htx_trend.archive.transfer import archive, exclusive
from test_cos_archive import MemoryBackend, cas_fixture


def fixture(tmp_path):
    spec, _, _ = cas_fixture(tmp_path)
    work = tmp_path / 'work'
    backend = MemoryBackend()
    receipt = archive(backend, work, 'one', spec, min_free=0)
    return backend, work, receipt


def test_inspect_evict_retry_restore_no_source_or_metadata_loss(tmp_path):
    backend, work, receipt = fixture(tmp_path)
    job = work / 'one'
    path = job / 'catalogue.sqlite3.gz'
    expected = path.read_bytes()
    retained = {p: p.read_bytes() for p in job.iterdir() if p != path}
    puts = backend.puts
    assert catalogue_cache(backend, work, 'one')['status'] == 'VERIFIED_PRESENT'
    assert path.exists()
    result = catalogue_cache(backend, work, 'one', action='evict')
    assert result['released_bytes'] == len(expected) and not path.exists()
    assert catalogue_cache(backend, work, 'one', action='evict')['released_bytes'] == 0
    assert all(p.read_bytes() == data for p, data in retained.items())
    assert catalogue_cache(backend, work, 'one', action='restore', min_free=0)['status'] == 'RESTORED'
    assert path.read_bytes() == expected and backend.puts == puts
    assert catalogue_cache(backend, work, 'one', action='restore')['status'] == 'VERIFIED_PRESENT'
    assert (tmp_path / 'source' / 'index.sqlite3').exists()


@pytest.mark.parametrize('failure', ['remote_corrupt', 'remote_missing', 'local_corrupt',
                                     'namespace', 'receipt', 'symlink'])
def test_failure_never_evicts(tmp_path, failure):
    backend, work, receipt = fixture(tmp_path)
    path = work / 'one' / 'catalogue.sqlite3.gz'
    if failure == 'remote_corrupt':
        backend.objects[receipt['catalogue']] = b'bad'
    elif failure == 'remote_missing':
        del backend.objects[receipt['catalogue']]
    elif failure == 'local_corrupt':
        path.write_bytes(b'bad')
    elif failure == 'namespace':
        backend.identity = 'wrong'
    elif failure == 'receipt':
        p = work / 'one' / 'receipt.json'
        value = json.loads(p.read_bytes())
        value['manifest'] = 'wrong'
        p.write_text(json.dumps(value))
    elif failure == 'symlink':
        target = tmp_path / 'outside'
        path.rename(target)
        path.symlink_to(target)
    with pytest.raises((ArchiveError, KeyError)):
        catalogue_cache(backend, work, 'one', action='evict')
    assert path.exists()


def test_workspace_lock_and_path_escape(tmp_path):
    backend, work, _ = fixture(tmp_path)
    with exclusive(work), pytest.raises(ArchiveError, match='writer'):
        catalogue_cache(backend, work, 'one', action='evict')
    for name in ('../one', '/one', 'one/sub'):
        with pytest.raises(ArchiveError):
            catalogue_cache(backend, work, name, action='evict')


def test_changed_inode_after_remote_verification_is_not_deleted(tmp_path):
    backend, work, receipt = fixture(tmp_path)
    path = work / 'one' / 'catalogue.sqlite3.gz'
    original_stream = backend.stream

    @contextmanager
    def replace_during_get(key):
        with original_stream(key) as stream:
            yield stream
        if key == receipt['catalogue']:
            replacement = work / 'replacement'
            replacement.write_bytes(path.read_bytes())
            replacement.replace(path)

    backend.stream = replace_during_get
    with pytest.raises(ArchiveError, match='changed'):
        catalogue_cache(backend, work, 'one', action='evict')
    assert path.exists()


def test_failed_restore_keeps_absent_cache_and_no_partial_file(tmp_path):
    backend, work, receipt = fixture(tmp_path)
    catalogue_cache(backend, work, 'one', action='evict')
    backend.objects[receipt['catalogue']] = b'corrupt'
    with pytest.raises(ArchiveError):
        catalogue_cache(backend, work, 'one', action='restore', min_free=0)
    assert not (work / 'one' / 'catalogue.sqlite3.gz').exists()
    assert not list((work / 'one').glob('.catalogue-*'))


def test_crash_after_unlink_is_recoverable_from_preserved_manifest(tmp_path, monkeypatch):
    import htx_trend.archive.catalogue_cache as cache_module
    backend, work, _ = fixture(tmp_path)
    path = work / 'one' / 'catalogue.sqlite3.gz'
    expected = path.read_bytes()
    original_atomic = cache_module.atomic

    def fail_done(target, data):
        if json.loads(data).get('status') == 'EVICTED':
            raise OSError('injected crash before DONE')
        original_atomic(target, data)

    monkeypatch.setattr(cache_module, 'atomic', fail_done)
    with pytest.raises(OSError):
        catalogue_cache(backend, work, 'one', action='evict')
    assert not path.exists()
    assert catalogue_cache(backend, work, 'one', action='evict')['released_bytes'] == 0
    catalogue_cache(backend, work, 'one', action='restore', min_free=0)
    assert path.read_bytes() == expected
