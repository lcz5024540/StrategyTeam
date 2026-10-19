import importlib.util
import io
import json
from pathlib import Path
import tarfile

import pytest
from htx_trend.admission import BINDINGS, validate_approval
from htx_trend.release_artifacts import KINDS, load_artifact, load_artifacts, validate_schema

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('g3_package', ROOT / 'release/package.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


@pytest.mark.parametrize('kind', KINDS)
def test_required_fields_and_types(kind):
    value, ref = load_artifact(ROOT / 'release/artifacts', kind)
    schema = json.loads((ROOT / f'release/artifacts/{kind}.schema.json').read_text())
    assert ref['sha256'] and value['synthetic'] is True
    for field in schema['required']:
        broken = {k: v for k, v in value.items() if k != field}
        with pytest.raises(ValueError, match='required'):
            validate_schema(broken, schema)
        with pytest.raises(ValueError):
            validate_schema({**value, field: None}, schema)
    with pytest.raises(ValueError):
        validate_schema({**value, 'synthetic': False}, schema)


def test_nested_type_rejected():
    root = ROOT / 'release/artifacts'
    value, _ = load_artifact(root, 'feature_dict')
    value['features'][0]['window'] = True
    with pytest.raises(ValueError, match='wrong type'):
        validate_schema(value, json.loads((root / 'feature_dict.schema.json').read_text()))


@pytest.fixture
def archive(tmp_path):
    return package.build(tmp_path / 'frozen')


def test_tar_members_hashes_and_bindings(archive):
    receipt = package.verify(archive)
    assert receipt['package_sha256'] == package.digest(archive.read_bytes())
    with tarfile.open(archive) as tar:
        manifest = json.load(tar.extractfile('MANIFEST.json'))
        assert set(tar.getnames()) == set(manifest['files'])
        assert len(tar.getnames()) == len(manifest['files'])
        for member in tar.getmembers():
            if member.name != 'MANIFEST.json':
                assert package.digest(tar.extractfile(member).read()) == manifest['files'][member.name]['sha256']
        assert 'src/htx_trend/worker.py' in tar.getnames()
        assert not any(n.endswith(('.env', '.pem', '.sql')) for n in tar.getnames())
    assert {'package_sha256', 'source_commit', 'config_fingerprint', 'config_hash'} <= manifest['bindings'].keys()
    assert set(manifest['artifacts']) == set(KINDS)
    assert receipt['candidate'] is True


def test_tar_bytes_corruption(archive):
    archive.write_bytes(archive.read_bytes() + b'corrupt')
    with pytest.raises(ValueError, match='SHA256'):
        package.verify(archive)


@pytest.mark.parametrize('change', ['extra', 'missing', 'tamper', 'duplicate'])
def test_tar_structural_corruption_even_with_updated_outer_sha(archive, change):
    with tarfile.open(archive) as tar:
        files = [(m.name, tar.extractfile(m).read()) for m in tar.getmembers()]
    if change == 'extra': files.append(('extra.txt', b'bad'))
    if change == 'missing': files = files[:-1]
    if change == 'tamper': files[-1] = (files[-1][0], b'bad')
    if change == 'duplicate': files.append(files[-1])
    with tarfile.open(archive, 'w:gz') as tar:
        for name, raw in files:
            info = tarfile.TarInfo(name)
            info.size = len(raw)
            tar.addfile(info, io.BytesIO(raw))
    archive.with_name(archive.name + '.sha256').write_text(f'{package.digest(archive.read_bytes())}  {archive.name}\n')
    with pytest.raises(ValueError):
        package.verify(archive)


def test_template_is_unsealed_synthetic_and_bound(archive, tmp_path):
    template = package.approval_template(archive)
    receipt = package.verify(archive)
    assert template['synthetic'] is True and template['seal_hash'] == ''
    assert set(BINDINGS) <= (template.keys() | template['approval'].keys())
    for field in ('source_commit', 'package_sha256', 'config_fingerprint', 'strategy_version', 'strategy_spec_hash'):
        assert template['approval'][field] == receipt['bindings'][field]
    path = tmp_path / 'template.json'
    path.write_bytes(package.encoded(template))
    with pytest.raises(ValueError, match='seal'):
        validate_approval(path, ROOT / 'tests/fixtures/g2/admission.json', {}, 'LIVE')


def test_freeze_refuses_overwrite(archive):
    with pytest.raises(FileExistsError):
        package.build(archive.parent.parent)


def test_receipt_tamper_rejected(archive):
    path = archive.parent / 'BUILD.json'
    receipt = json.loads(path.read_bytes())
    receipt['bindings']['source_commit'] = 'tampered'
    path.write_bytes(package.encoded(receipt))
    with pytest.raises(ValueError, match='receipt'):
        package.approval_template(archive)


def test_paper_db_candidate_version_and_template(tmp_path):
    import re
    archive = package.build(tmp_path / 'paper-frozen', paper=True)
    receipt = package.verify(archive)
    assert re.fullmatch(r'0\.1\.6-paper-db-[0-9a-f]{8}', receipt['version'])
    assert package.approval_template(archive)['label'] == 'SYNTHETIC'
    with tarfile.open(archive) as tar:
        assert 'src/htx_trend/paper_db_persist.py' in tar.getnames()
