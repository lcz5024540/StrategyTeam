#!/usr/bin/env python3
"""Offline candidate builder/verifier. Never installs or issues LIVE authority."""
from __future__ import annotations
import argparse
from copy import deepcopy
from datetime import UTC, datetime
import gzip
from hashlib import sha256
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'src'))
from htx_trend.admission import canonical
from htx_trend.release_artifacts import load_artifacts, validate_schema
from htx_trend.strategy_profiles import profile_strategy
from htx_trend.timeframes import TimeframeProfile

DISCLAIMER = '候选包 ≠ 可以热更 ≠ 可装现网'
# paper=True 时按 strategy card 选择实例。15m 路径保持 primary，不得改成 4h。
PAPER_INSTANCE_BY_STRATEGY = {
    'HTX_TREND_15M_V2': 'htx-trend-paper-primary',
    'HTX_TREND_4H_V2': 'htx-trend-paper-4h',
}
PAPER_4H_CARD = ROOT / 'release/artifacts/strategy_card.paper-4h.example.json'


def encoded(value):
    return canonical(value).encode()


def digest(raw):
    return sha256(raw).hexdigest()


def manifest_hash(manifest):
    value = deepcopy(manifest)
    value['files']['MANIFEST.json']['sha256'] = ''
    return digest(encoded(value))


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def paper_instance_key(strategy_version):
    """PAPER 示例实例：15M→primary，4H→htx-trend-paper-4h。"""
    try:
        return PAPER_INSTANCE_BY_STRATEGY[strategy_version]
    except KeyError as exc:
        raise ValueError(f'paper package has no instance for {strategy_version}') from exc


def load_strategy_card(path: Path):
    """读取并校验一张 strategy card，供 4h PAPER 示例替换默认 15m card。"""
    raw = path.read_bytes()
    value = json.loads(raw)
    schema = json.loads((ROOT / 'release/artifacts/strategy_card.schema.json').read_bytes())
    validate_schema(value, schema)
    return value, {
        'id': value['id'], 'version': value['version'],
        'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(raw),
        'schema_path': 'release/artifacts/strategy_card.schema.json',
    }


def build(out_root=Path('/tmp/g3-codex/frozen'), *, paper=False, paper_4h=False, compact_evidence=False):
    # paper_4h 仍是 PAPER 候选包，只切换 card / instance，不改 15m paper=True 默认。
    if paper_4h:
        paper = True
    if compact_evidence and not paper:
        raise ValueError('compact evidence packages are PAPER only')
    source = git('rev-parse', 'HEAD')
    short = git('rev-parse', '--short=8', 'HEAD')
    if not re.fullmatch('[0-9a-f]{8}', short):
        raise ValueError('expected exactly eight hex characters')
    version = f'0.1.6-paper-db-{short}' if paper else f'0.1.2-g3-candidate-{short}'
    if compact_evidence:
        version = f'0.1.7-evidence-v2-{short}' + ('-4h' if paper_4h else '')
    card, refs = load_artifacts(ROOT / 'release/artifacts')
    if paper_4h:
        card, card_ref = load_strategy_card(PAPER_4H_CARD)
        feature_keys = refs['feature_dict']['feature_keys']
        experiment_ids = refs['experiment_ledger']['experiment_ids']
        if not set(card['feature_keys']) <= set(feature_keys) or not set(card['experiment_ids']) <= set(experiment_ids):
            raise ValueError('strategy card references unknown features/experiments')
        refs = {**refs, 'strategy_card': card_ref}
    spec = profile_strategy(TimeframeProfile(card['timeframe_profile']), read_env=False)
    if spec.strategy.version != card['strategy_version']:
        raise ValueError('card strategy/profile mismatch')
    # Explicit allowlist: no env, keys, SQL, tests, real approvals or state trees.
    paths = sorted((ROOT / 'src/htx_trend').rglob('*.py'))
    paths += [ROOT / 'pyproject.toml', ROOT / 'README.md',
              ROOT / 'release/package.py', ROOT / 'release/README.md',
              ROOT / 'release/EVIDENCE-RETENTION.md']
    paths += sorted((ROOT / 'release/artifacts').glob('*.json'))
    payload = {}
    for path in paths:
        if path.is_symlink() or any(p.is_symlink() for p in path.parents):
            raise ValueError(f'symlink payload forbidden: {path}')
        payload[path.relative_to(ROOT).as_posix()] = path.read_bytes()
    config = {'synthetic': True, 'candidate': True, 'mode': 'PAPER' if paper else 'OBSERVE',
              'strategy_version': card['strategy_version'], 'timeframe_profile': card['timeframe_profile'],
              'note': 'Packaging example only; not a runtime configuration or account binding'}
    instance_key = None
    if paper:
        instance_key = paper_instance_key(card['strategy_version'])
        config.update(instance_key=instance_key, max_active_positions=5,
                      account_equity_usdt='300', max_total_margin_rate='0.10',
                      contracts=['BTC-USDT', 'ETH-USDT'])
    if compact_evidence:
        config['evidence_format'] = 'compact-v2'
        config['runtime_env_required'] = {'HTX_PAPER_EVIDENCE_FORMAT': 'compact-v2'}
    payload['release/config.example.json'] = encoded(config)
    bindings = {'source_commit': source, 'config_fingerprint': digest(encoded(config)),
                'config_hash': digest(encoded(config)), 'config_path': 'release/config.example.json',
                'strategy_version': card['strategy_version'], 'timeframe_profile': card['timeframe_profile'],
                'strategy_spec_hash': spec.spec_hash,
                'package_sha256': {'ref': 'BUILD.json#/package_sha256', 'scope': 'final tar.gz bytes'}}
    if instance_key is not None:
        bindings['instance_key'] = instance_key
    manifest = {'schema': 'htx-candidate-package-g3', 'candidate': True, 'synthetic': True,
                'disclaimer': DISCLAIMER, 'version': version, 'source_commit': source,
                'build': {'created_at': datetime.now(UTC).isoformat(),
                          'working_tree_dirty': bool(git('status', '--porcelain')),
                          'source_note': 'HEAD plus working-tree payload; per-file hashes bind actual bytes'},
                'bindings': bindings, 'artifacts': refs,
                'files': {name: {'sha256': digest(raw), 'hash_mode': 'raw'} for name, raw in payload.items()}}
    manifest['files']['MANIFEST.json'] = {'sha256': '', 'hash_mode': 'canonical-json-self-sha256-empty'}
    manifest['files']['MANIFEST.json']['sha256'] = manifest_hash(manifest)
    payload['MANIFEST.json'] = encoded(manifest)
    out = Path(out_root) / version
    out.mkdir(parents=True, exist_ok=False)  # frozen output must never be silently replaced
    archive = out / f'htx-trend-python-{version}.tar.gz'
    with archive.open('wb') as raw:
        with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as tar:
                for name, data in sorted(payload.items()):
                    entry = tarfile.TarInfo(name)
                    entry.size, entry.mode = len(data), 0o644
                    tar.addfile(entry, io.BytesIO(data))
    package_sha = digest(archive.read_bytes())
    receipt = {'schema': 'htx-candidate-build-g3', 'candidate': True, 'synthetic': True,
               'disclaimer': DISCLAIMER, 'frozen_path': str(out.resolve()), 'archive_path': str(archive.resolve()),
               'archive': archive.name, 'package_sha256': package_sha,
               'manifest_sha256': digest(payload['MANIFEST.json']), 'version': version,
               'bindings': {**bindings, 'package_sha256': package_sha}}
    if paper:
        receipt['build'] = {**manifest['build'],
                            'version_note': 'tip8 is based on HEAD; for an uncommitted working tree, '
                                            'the executor may rebuild after commit to align tip8'}
    (out / 'MANIFEST.json').write_bytes(payload['MANIFEST.json'])
    (out / 'BUILD.json').write_bytes(encoded(receipt))
    archive.with_name(archive.name + '.sha256').write_text(f'{package_sha}  {archive.name}\n')
    verify(archive)
    return archive


def verify(archive: Path):
    archive = Path(archive)
    expected = archive.with_name(archive.name + '.sha256').read_text().split()
    actual = digest(archive.read_bytes())
    if expected != [actual, archive.name]:
        raise ValueError('tar SHA256 mismatch')
    with tarfile.open(archive, 'r:gz') as tar:
        members = tar.getmembers()
        names = [m.name for m in members]
        if len(names) != len(set(names)):
            raise ValueError('duplicate tar members')
        if any(not m.isfile() or PurePosixPath(m.name).is_absolute() or '..' in PurePosixPath(m.name).parts for m in members):
            raise ValueError('unsafe tar member')
        data = {m.name: tar.extractfile(m).read() for m in members}
    manifest = json.loads(data['MANIFEST.json'])
    if set(manifest['files']) != set(names):
        raise ValueError('manifest membership mismatch')
    for name, entry in manifest['files'].items():
        mode = 'canonical-json-self-sha256-empty' if name == 'MANIFEST.json' else 'raw'
        hashed = manifest_hash(manifest) if name == 'MANIFEST.json' else digest(data[name])
        if entry != {'sha256': hashed, 'hash_mode': mode}:
            raise ValueError(f'member hash mismatch: {name}')
    if (manifest.get('candidate') is not True or manifest.get('synthetic') is not True
            or not re.fullmatch(r'0\.1\.(?:(?:(?:2-g3|3-paper)-candidate|[56]-paper-db|4-paper-fixa)-[0-9a-f]{8}|7-evidence-v2-[0-9a-f]{8}(?:-4h)?)', manifest['version'])):
        raise ValueError('not a candidate package')
    if (archive.parent / 'MANIFEST.json').read_bytes() != data['MANIFEST.json']:
        raise ValueError('external manifest mismatch')
    receipt = json.loads((archive.parent / 'BUILD.json').read_bytes())
    binding = {**manifest['bindings'], 'package_sha256': actual}
    if (receipt['package_sha256'] != actual or receipt['manifest_sha256'] != digest(data['MANIFEST.json'])
            or receipt['bindings'] != binding or receipt['archive'] != archive.name
            or receipt['version'] != manifest['version'] or receipt['candidate'] is not True
            or receipt['synthetic'] is not True):
        raise ValueError('build receipt mismatch')
    if digest(data[binding['config_path']]) != binding['config_fingerprint'] or binding['config_hash'] != binding['config_fingerprint']:
        raise ValueError('config binding mismatch')
    for ref in manifest['artifacts'].values():
        if digest(data[ref['path']]) != ref['sha256']:
            raise ValueError('artifact binding mismatch')
    return receipt


def approval_template(archive: Path):
    """Unsealed, deliberately incomplete G2 envelope; cannot authorize any LIVE action."""
    receipt = verify(archive)
    bindings = receipt['bindings']
    inner_names = ('strategy_version', 'timeframe_profile', 'source_commit', 'package_sha256',
                   'config_fingerprint', 'strategy_spec_hash')
    inner = {name: bindings[name] for name in inner_names}
    inner.update(admission_hash='', account_id=0,
                 risk={'per_trade_equity_rate': '0.005', 'total_open_equity_rate': '0.015', 'max_active_positions': 3},
                 exit_version='HTX_TREND_EXIT_V4', issued_at='', expires_at='', approval_hash='')
    value = {'schema': 'htx-live-approval-g2', 'synthetic': True, 'label': 'SYNTHETIC', 'note': '非真授权; template cannot authorize LIVE or deployment',
             'approval': inner, 'admission_seal_hash': '', 'account_equity_usdt': '300',
             'max_total_margin_rate': '0.10', 'exit_policy_hash': '', 'approved_by': 'SYNTHETIC',
             'rollback_point': 'SYNTHETIC-NO-DEPLOY', 'issued_at': '', 'expires_at': '', 'seal_hash': ''}
    # No seal, times, account or admission supplied. Never manufactures research authority.
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    builder = commands.add_parser('build')
    builder.add_argument('--out-root', type=Path, default=Path('/tmp/g3-codex/frozen'))
    builder.add_argument('--paper', action='store_true')
    builder.add_argument('--compact-evidence', action='store_true', help='PAPER compact-v2 candidate; runtime switch still requires explicit env')
    builder.add_argument('--paper-4h', action='store_true',
                         help='PAPER 示例绑定 HTX_TREND_4H_V2 / htx-trend-paper-4h')
    commands.add_parser('verify').add_argument('archive', type=Path)
    template = commands.add_parser('approval-template')
    template.add_argument('archive', type=Path)
    template.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'build':
        print(build(args.out_root, paper=args.paper, paper_4h=args.paper_4h, compact_evidence=args.compact_evidence))
    elif args.command == 'verify':
        print(json.dumps({'status': 'PASS', **verify(args.archive)}, ensure_ascii=False))
    else:
        value = approval_template(args.archive)
        with args.output.open('x') as output:
            output.write(canonical(value))
        print(args.output)


if __name__ == '__main__':
    main()
