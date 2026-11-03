import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rt_live_package', ROOT / 'release/package.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


@pytest.fixture
def archive(tmp_path):
    return package.build_live(tmp_path / 'rt', realtime=True)


def payload(archive):
    with tarfile.open(archive) as tar:
        return {m.name: tar.extractfile(m).read() for m in tar.getmembers()}


def test_realtime_live_is_distinct_bound_and_entry_disabled(archive):
    receipt = package.verify(archive)
    data = payload(archive)
    cfg = json.loads(data['release/config.example.json'])
    assert receipt['version'].startswith('0.1.7-realtime-live-')
    assert receipt['live'] is True and receipt['synthetic'] is True
    assert receipt['bindings']['strategy_version'] == package.REALTIME_VERSION
    assert receipt['bindings']['instance_key'] == 'htx-trend-live-primary'
    assert cfg['mode'] == 'LIVE' and cfg['entry_enabled'] is False
    assert cfg['financial_admission'] == 'NOT_ELIGIBLE'
    assert cfg['structural_candidate_selected'] is False
    assert cfg['trial_controls_implemented'] is False
    assert (cfg['entry_period'], cfg['trend_period']) == ('15min', '60min')
    assert 'release/migrations/2026-11-02__htx_realtime_structure_plan.sql' in data
    assert 'release/REALTIME-LIVE-PACKAGE.md' in data
    assert b'HTX_RT_ENTRY_ENABLED=false' in data['deployment/realtime-live.env.example']
    assert not any(name.startswith('deployment/paper-4h/') for name in data)
    assert 'deployment/htx-python.env.example' not in data
    assert 'release/seal_product_live_waiver.py' not in data
    assert 'release/seal_live.py' not in data
    assert not any(name.endswith(('.pem', '.env', '.key')) for name in data)


def repack(archive, change):
    """Recompute all integrity hashes; semantic guards must still reject tampering."""
    data = payload(archive)
    manifest = json.loads(data['MANIFEST.json'])
    cfg = json.loads(data['release/config.example.json'])
    change(cfg, manifest, data)
    data['release/config.example.json'] = package.encoded(cfg)
    cfg_hash = package.digest(data['release/config.example.json'])
    manifest['bindings'].update(config_hash=cfg_hash, config_fingerprint=cfg_hash)
    for name in data:
        if name != 'MANIFEST.json':
            manifest['files'][name]['sha256'] = package.digest(data[name])
    manifest['files']['MANIFEST.json']['sha256'] = package.manifest_hash(manifest)
    data['MANIFEST.json'] = package.encoded(manifest)
    with tarfile.open(archive, 'w:gz') as tar:
        for name, raw in data.items():
            info = tarfile.TarInfo(name)
            info.size = len(raw)
            tar.addfile(info, io.BytesIO(raw))
    sha = package.digest(archive.read_bytes())
    archive.with_name(archive.name + '.sha256').write_text(f'{sha}  {archive.name}\n')
    (archive.parent / 'MANIFEST.json').write_bytes(data['MANIFEST.json'])
    path = archive.parent / 'BUILD.json'
    receipt = json.loads(path.read_bytes())
    receipt.update(package_sha256=sha, manifest_sha256=package.digest(data['MANIFEST.json']),
                   bindings={**manifest['bindings'], 'package_sha256': sha},
                   version=manifest['version'])
    path.write_bytes(package.encoded(receipt))


@pytest.mark.parametrize('field,value', [
    ('entry_enabled', True), ('financial_admission', 'PASS'),
    ('trial_controls_implemented', True), ('structural_candidate_selected', True),
    ('trend_period', '1day'), ('runtime_env_required', {'HTX_RT_ENTRY_ENABLED': 'true'}),
])
def test_live_semantics_cannot_be_relabelled_with_rehashed_files(archive, field, value):
    repack(archive, lambda cfg, manifest, data: cfg.update({field: value}))
    with pytest.raises(ValueError, match='encode-only'):
        package.verify(archive)


def test_realtime_cannot_masquerade_as_old_live_version(archive):
    repack(archive, lambda cfg, manifest, data: manifest.update(
        version='0.1.7-live-' + manifest['version'][-8:]))
    with pytest.raises(ValueError, match='strategy/profile'):
        package.verify(archive)


def test_enabled_deployment_example_rejected_even_with_fresh_hashes(archive):
    def change(cfg, manifest, data):
        key = 'deployment/realtime-live.env.example'
        data[key] = data[key].replace(b'HTX_RT_ENTRY_ENABLED=false', b'HTX_RT_ENTRY_ENABLED=true')
    repack(archive, change)
    with pytest.raises(ValueError, match='sample must disable'):
        package.verify(archive)


def test_old_live_waiver_cannot_authorize_realtime(archive, tmp_path):
    spec = importlib.util.spec_from_file_location('old_waiver', ROOT / 'release/seal_product_live_waiver.py')
    waiver = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(waiver)
    stamp = tmp_path / 'synthetic-stamp.txt'
    stamp.write_text('TEST ONLY, NOT USER AUTHORITY')
    with pytest.raises(ValueError, match='only authorizes HTX_TREND_15M_V2'):
        waiver.seal_waiver(archive, account_id=7, approved_by='test',
                          rollback_point='TEST', product_stamp=stamp)


def test_cli_build_and_verify(tmp_path):
    result = subprocess.run([sys.executable, str(ROOT / 'release/package.py'),
                             'build-live', '--realtime', '--out-root', str(tmp_path / 'cli')],
                            check=True, text=True, capture_output=True)
    built = Path(result.stdout.strip())
    verified = subprocess.run([sys.executable, str(ROOT / 'release/package.py'), 'verify', str(built)],
                              check=True, text=True, capture_output=True)
    assert json.loads(verified.stdout)['bindings']['strategy_version'] == package.REALTIME_VERSION


def test_old_waiver_cli_does_not_write_an_invalid_realtime_authority(archive, tmp_path):
    stamp = tmp_path / 'synthetic-stamp.txt'
    stamp.write_text('TEST ONLY, NOT USER AUTHORITY')
    output = tmp_path / 'must-not-be-created.json'
    result = subprocess.run([
        sys.executable, str(ROOT / 'release/seal_product_live_waiver.py'), 'seal',
        str(archive), '--output', str(output), '--approved-by', 'test',
        '--rollback-point', 'TEST', '--product-stamp', str(stamp),
    ], text=True, capture_output=True)
    assert result.returncode != 0
    assert 'only authorizes HTX_TREND_15M_V2' in result.stderr
    assert not output.exists()
