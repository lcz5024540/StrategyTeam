import importlib.util
import json
from pathlib import Path

import pytest

from test_cos_archive import MemoryBackend
from htx_trend.sealing.control import initialize, Control
from htx_trend.sealing.lifecycle import archive_sealed
from htx_trend.realtime.storage import SegmentRecorder

spec = importlib.util.spec_from_file_location('release_verified',
    Path(__file__).parents[1] / 'release/cos/release_verified.py')
maintenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(maintenance)


@pytest.fixture
def setup(tmp_path):
    root, work = tmp_path / 'capture', tmp_path / 'work'
    initialize(root, kind='capture', contract='capture-public', max_bytes=1)
    writer = SegmentRecorder(root, segmented=True, segment_bytes=1)
    backend = MemoryBackend()
    for n in range(3):
        writer.append({'kind': 'DEPTH', 'received_ms': n, 'data': [n]})
        writer.segment_boundary()
    archive_sealed(backend, root, work, min_free=0)
    policy = {'enabled': True, 'root': str(root), 'namespace': backend.identity,
              'authorization': 'test-user-approval'}
    yield writer, backend, policy, work
    writer.segments.close()


def test_release_keeps_active_pin_and_writer_continues(setup):
    writer, backend, policy, work = setup
    before = writer.used
    result = maintenance.tick(backend, policy, work, min_free=0)
    assert result['released_bytes'] > 0
    assert result['results'][-1]['status'] == 'PINNED'
    writer._refresh_released()
    assert writer.used < before
    assert writer.append({'kind': 'DEPTH', 'received_ms': 4, 'data': [4]})
    writer.segment_boundary()
    # Formerly pinned generation becomes eligible under a fresh plan.
    result = maintenance.tick(backend, policy, work, min_free=0)
    assert result['released_bytes'] > 0
    assert maintenance.tick(backend, policy, work, min_free=0)['released_bytes'] == 0


@pytest.mark.parametrize('change', ['disabled', 'namespace', 'root', 'contract'])
def test_authorization_boundaries(setup, change):
    writer, backend, policy, work = setup
    before = list(writer.root.glob('*.json.gz'))
    if change == 'disabled':
        policy['enabled'] = False
        assert maintenance.tick(backend, policy, work, min_free=0)['status'] == 'DISABLED'
    else:
        if change == 'namespace': policy['namespace'] = 'different'
        if change == 'root': policy['root'] = str(work)
        if change == 'contract':
            config = writer.segments.directory / 'config.json'
            value = json.loads(config.read_bytes()); value['contract'] = 'capture-runtime'
            config.write_text(json.dumps(value))
        with pytest.raises(ValueError): maintenance.tick(backend, policy, work, min_free=0)
    assert list(writer.root.glob('*.json.gz')) == before


def test_network_failure_never_deletes(setup, monkeypatch):
    writer, backend, policy, work = setup
    before = list(writer.root.glob('*.json.gz'))
    monkeypatch.setattr(backend, 'stream', lambda *a, **k: (_ for _ in ()).throw(OSError('network down')))
    with pytest.raises(OSError): maintenance.tick(backend, policy, work, min_free=0)
    assert list(writer.root.glob('*.json.gz')) == before


def test_interrupted_unlink_reuses_same_plan(setup, monkeypatch):
    import htx_trend.sealing.lifecycle as lifecycle
    writer, backend, policy, work = setup
    original = lifecycle.sync_directory
    monkeypatch.setattr(lifecycle, 'sync_directory', lambda _: (_ for _ in ()).throw(OSError('crash')))
    with pytest.raises(OSError): maintenance.tick(backend, policy, work, min_free=0)
    path = work / 'release-control/epoch-1.json'
    saved = path.read_bytes()
    assert writer.segments.db.execute("SELECT count(*) FROM deletes WHERE state='INTENT'").fetchone()[0] == 1
    monkeypatch.setattr(lifecycle, 'sync_directory', original)
    maintenance.tick(backend, policy, work, min_free=0)
    assert path.read_bytes() == saved
    assert writer.segments.db.execute("SELECT count(*) FROM deletes WHERE state='INTENT'").fetchone()[0] == 0


def test_pinned_old_epoch_does_not_starve_newer(setup):
    writer, backend, policy, work = setup
    first = writer.segments.db.execute('SELECT key FROM objects ORDER BY rowid LIMIT 1').fetchone()[0]
    writer.segments.external_pin('external:replay', [first], reason='test')
    one = maintenance.tick(backend, policy, work, max_generations=1, min_free=0)
    two = maintenance.tick(backend, policy, work, max_generations=1, min_free=0)
    assert one['results'][0]['status'] == 'PINNED'
    assert two['released_bytes'] > 0


def test_handoff_busy_keeps_files_then_retries(setup):
    writer, backend, policy, work = setup
    writer.segments.begin('recorder')
    assert maintenance.tick(backend, policy, work, retries=1, min_free=0)['released_bytes'] == 0
    writer.segment_boundary()
    assert maintenance.tick(backend, policy, work, min_free=0)['released_bytes'] > 0


def test_saved_plan_tamper_rejected(setup, monkeypatch):
    writer, backend, policy, work = setup
    original = maintenance.apply_release
    monkeypatch.setattr(maintenance, 'apply_release', lambda *a, **k: (_ for _ in ()).throw(OSError('before unlink')))
    with pytest.raises(OSError): maintenance.tick(backend, policy, work, min_free=0)
    descriptor = json.loads((work / 'release-control/epoch-1.json').read_bytes())
    with Path(descriptor['plan']).open('ab') as f: f.write(b'tamper')
    monkeypatch.setattr(maintenance, 'apply_release', original)
    with pytest.raises(ValueError, match='hash mismatch'):
        maintenance.tick(backend, policy, work, min_free=0)


def test_corrupt_cos_payload_keeps_local_data(setup):
    writer, backend, policy, work = setup
    before = list(writer.root.glob('*.json.gz'))
    for key in backend.objects:
        if key.startswith('packs/'):
            backend.objects[key] = b'corrupt'
    with pytest.raises((ValueError, OSError)):
        maintenance.tick(backend, policy, work, min_free=0)
    assert list(writer.root.glob('*.json.gz')) == before


def test_workspace_lock_prevents_concurrent_release(setup):
    from htx_trend.archive.transfer import exclusive
    writer, backend, policy, work = setup
    with exclusive(work / 'release-control'):
        with pytest.raises(ValueError, match='another archive writer'):
            maintenance.tick(backend, policy, work, min_free=0)


def test_workspace_cannot_silently_change_authorization(setup):
    writer, backend, policy, work = setup
    maintenance.tick(backend, policy, work, min_free=0)
    policy['authorization'] = 'different-scope'
    with pytest.raises(ValueError, match='another authorization'):
        maintenance.tick(backend, policy, work, min_free=0)


@pytest.mark.parametrize('field,value', [('root', '/another/capture'), ('epoch', 2),
                                      ('namespace', 'other-cos'), ('config', {'contract': 'capture-runtime'})])
def test_rehashed_foreign_plan_cannot_escape_policy(setup, monkeypatch, field, value):
    import sqlite3
    writer, backend, policy, work = setup
    original = maintenance.apply_release
    monkeypatch.setattr(maintenance, 'apply_release', lambda *a, **k: (_ for _ in ()).throw(OSError('before unlink')))
    with pytest.raises(OSError): maintenance.tick(backend, policy, work, min_free=0)
    descriptor_path = work / 'release-control/epoch-1.json'
    descriptor = json.loads(descriptor_path.read_bytes())
    with sqlite3.connect(descriptor['plan']) as db:
        db.execute('UPDATE meta SET value=? WHERE name=?', (json.dumps(value), field))
    descriptor['approval_sha256'] = maintenance.digest_file(descriptor['plan'])
    descriptor_path.write_text(json.dumps(descriptor))
    (work / 'release-control/cursor.json').write_text('0')
    monkeypatch.setattr(maintenance, 'apply_release', original)
    before = list(writer.root.glob('*.json.gz'))
    with pytest.raises(ValueError, match='outside authorized'):
        maintenance.tick(backend, policy, work, min_free=0)
    assert list(writer.root.glob('*.json.gz')) == before
