import importlib.util
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from htx_trend.admission import validate_approval

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_package', ROOT / 'release/package.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)
seal_spec = importlib.util.spec_from_file_location('seal_live', ROOT / 'release/seal_live.py')
seal_live = importlib.util.module_from_spec(seal_spec)
seal_spec.loader.exec_module(seal_live)


@pytest.fixture
def live_archive(tmp_path):
    return package.build_live(tmp_path / 'live-frozen')


def test_live_package_verify_passes(live_archive):
    receipt = package.verify(live_archive)
    assert receipt['live'] is True
    assert receipt['bindings']['instance_key'] == package.LIVE_INSTANCE_KEY
    assert live_archive.name.startswith('htx-trend-python-0.1.7-live-')


def test_live_package_includes_deployment(live_archive):
    import tarfile
    with tarfile.open(live_archive) as tar:
        names = tar.getnames()
    assert any(n.startswith('deployment/') for n in names)
    assert 'release/seal_live.py' in names


def test_paper_build_not_live(tmp_path):
    paper = package.build(tmp_path / 'paper', paper=True)
    receipt = package.verify(paper)
    assert receipt['candidate'] is True and receipt.get('live') is not True
    import tarfile
    with tarfile.open(paper) as tar:
        manifest = json.load(tar.extractfile('MANIFEST.json'))
    assert manifest.get('live') is not True
    assert manifest['bindings']['instance_key'] == 'htx-trend-paper-primary'
    assert manifest['bindings']['instance_key'] != package.LIVE_INSTANCE_KEY


def test_synthetic_demo_seal_fails_formal_live(live_archive, tmp_path):
    out = tmp_path / 'seal'
    seal_live.seal_demo_bundle(live_archive, out)
    context = json.loads((out / 'context.json').read_text())
    now = datetime.fromisoformat(context['issued_at'].replace('Z', '+00:00')) + timedelta(hours=1)
    with pytest.raises(ValueError, match='SYNTHETIC'):
        validate_approval(out / 'approval.json', out / 'admission.json', context, 'LIVE', now=now)


def test_matching_bindings_validate_paper_surface(live_archive, tmp_path):
    out = tmp_path / 'seal'
    seal_live.seal_demo_bundle(live_archive, out, account_id=7)
    context = json.loads((out / 'context.json').read_text())
    now = datetime.fromisoformat(context['issued_at'].replace('Z', '+00:00')) + timedelta(hours=1)
    validate_approval(out / 'approval.json', out / 'admission.json', context, 'PAPER', now=now)


def test_package_sha_mismatch_fails(live_archive, tmp_path):
    out = tmp_path / 'seal'
    seal_live.seal_demo_bundle(live_archive, out)
    context = json.loads((out / 'context.json').read_text())
    context['package_sha256'] = 'b' * 64
    (out / 'context.json').write_text(json.dumps(context))
    now = datetime.fromisoformat(context['issued_at'].replace('Z', '+00:00')) + timedelta(hours=1)
    with pytest.raises(ValueError, match='binding'):
        validate_approval(out / 'approval.json', out / 'admission.json', context, 'PAPER', now=now)
