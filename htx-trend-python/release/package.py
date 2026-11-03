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
from htx_trend.realtime import VERSION as REALTIME_VERSION

DISCLAIMER = '候选包 ≠ 可以热更 ≠ 可装现网'
# paper=True 时按 strategy card 选择实例。15m 路径保持 primary，不得改成 4h。
PAPER_INSTANCE_BY_STRATEGY = {
    'HTX_TREND_15M_V2': 'htx-trend-paper-primary',
    'HTX_TREND_4H_V2': 'htx-trend-paper-4h',
    REALTIME_VERSION: 'htx-realtime-paper-15m',
}
PAPER_4H_CARD = ROOT / 'release/artifacts/strategy_card.paper-4h.example.json'
LIVE_INSTANCE_KEY = 'htx-trend-live-primary'
LIVE_STRATEGY_VERSION = 'HTX_TREND_15M_V2'
LIVE_TIMEFRAME = TimeframeProfile.INTRADAY_15M
LIVE_VERSION_RE = re.compile(r'0\.1\.7-(?:realtime-)?live-[0-9a-f]{8}')
CANDIDATE_VERSION_RE = re.compile(
    r'0\.1\.(?:(?:(?:2-g3|3-paper)-candidate|[56]-paper-db|4-paper-fixa)-[0-9a-f]{8}|7-evidence-v2-[0-9a-f]{8}(?:-4h)?|7-realtime-candidate-[0-9a-f]{8})')


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


def build(out_root=Path('/tmp/g3-codex/frozen'), *, paper=False, paper_4h=False, compact_evidence=False, realtime=False):
    # paper_4h 仍是 PAPER 候选包，只切换 card / instance，不改 15m paper=True 默认。
    if realtime:
        if paper_4h:
            raise ValueError('realtime entry is 15m only')
        paper = compact_evidence = True
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
    if realtime:
        version = f'0.1.7-realtime-candidate-{short}'
    card, refs = load_artifacts(ROOT / 'release/artifacts', variant='.realtime' if realtime else '')
    if paper_4h:
        card, card_ref = load_strategy_card(PAPER_4H_CARD)
        feature_keys = refs['feature_dict']['feature_keys']
        experiment_ids = refs['experiment_ledger']['experiment_ids']
        if not set(card['feature_keys']) <= set(feature_keys) or not set(card['experiment_ids']) <= set(experiment_ids):
            raise ValueError('strategy card references unknown features/experiments')
        refs = {**refs, 'strategy_card': card_ref}
    spec = profile_strategy(TimeframeProfile(card['timeframe_profile']), read_env=False,
                            strategy_version=card['strategy_version'])
    if spec.strategy.version != card['strategy_version']:
        raise ValueError('card strategy/profile mismatch')
    # Explicit allowlist: no env, keys, SQL, tests, real approvals or state trees.
    paths = sorted((ROOT / 'src/htx_trend').rglob('*.py'))
    paths += [ROOT / 'pyproject.toml', ROOT / 'README.md',
              ROOT / 'release/package.py', ROOT / 'release/README.md',
              ROOT / 'release/EVIDENCE-RETENTION.md', ROOT / 'release/HOT-DEPLOY.md']
    paths += [ROOT / 'COS-ARCHIVE.md', ROOT / 'config/archive/cos-server-sources.json']
    paths += sorted((ROOT / 'release/cos').glob('*'))
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
    if realtime:
        config.update(entry_enabled=False, financial_admission='NOT_ELIGIBLE',
                      models=['RT_RANGE_REJECTION', 'RT_BREAKOUT_RETEST'],
                      runtime_env_required={'HTX_RT_ENTRY_ENABLED': 'false',
                          'HTX_PAPER_EVIDENCE_FORMAT': 'compact-v2'},
                      note='PAPER candidate; LIVE code present but separate admission/migration/approval required')
        for name in ('REALTIME-ENTRY.md', 'realtime.env.example'):
            payload['release/' + name] = (ROOT / 'release' / name).read_bytes()
        payload['release/migrations/2026-11-02__htx_realtime_structure_plan.sql'] = (
            ROOT.parent / 'sql/htx/2026-11-02__htx_realtime_structure_plan.sql').read_bytes()
        payload['release/migrations/2026-11-03__htx_realtime_live_trial.sql'] = (
            ROOT.parent / 'sql/htx/2026-11-03__htx_realtime_live_trial.sql').read_bytes()
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


def _live_payload_paths():
    paths = sorted((ROOT / 'src/htx_trend').rglob('*.py'))
    paths += [ROOT / 'pyproject.toml', ROOT / 'README.md',
              ROOT / 'release/package.py', ROOT / 'release/README.md',               ROOT / 'release/LIVE_PACKAGE.md', ROOT / 'release/seal_live.py',
              ROOT / 'release/seal_product_live_waiver.py', ROOT / 'release/EVIDENCE-RETENTION.md', ROOT / 'release/HOT-DEPLOY.md']
    paths += [ROOT / 'COS-ARCHIVE.md', ROOT / 'config/archive/cos-server-sources.json']
    paths += sorted((ROOT / 'release/cos').glob('*'))
    paths += sorted((ROOT / 'release/artifacts').glob('*.json'))
    paths += sorted(p for p in (ROOT / 'deployment').rglob('*') if p.is_file())
    return paths


def build_live(out_root=Path('/tmp/htx-live/frozen'), *, realtime=False):
    """Encode-only 15m LIVE tarball; realtime is explicitly entry-disabled."""
    source = git('rev-parse', 'HEAD')
    short = git('rev-parse', '--short=8', 'HEAD')
    if not re.fullmatch('[0-9a-f]{8}', short):
        raise ValueError('expected exactly eight hex characters')
    strategy_version = REALTIME_VERSION if realtime else LIVE_STRATEGY_VERSION
    version = f'0.1.7-{"realtime-" if realtime else ""}live-{short}'
    card, refs = load_artifacts(ROOT / 'release/artifacts', variant='.realtime' if realtime else '')
    if card['strategy_version'] != strategy_version or card['timeframe_profile'] != LIVE_TIMEFRAME.value:
        raise ValueError('strategy card must match selected 15m LIVE package')
    spec = profile_strategy(LIVE_TIMEFRAME, read_env=False, strategy_version=strategy_version)
    if spec.strategy.version != strategy_version:
        raise ValueError('card strategy/profile mismatch')
    paths = _live_payload_paths()
    if not realtime:
        paths = [p for p in paths if p.name != 'realtime-live.env.example']
    if realtime:
        # Exclude old strategy deployment templates and waiver tooling.
        paths = [p for p in paths if not p.is_relative_to(ROOT / 'deployment')
                 and p.name not in {'LIVE_PACKAGE.md', 'seal_live.py', 'seal_product_live_waiver.py'}]
        paths += [ROOT / 'release/REALTIME-LIVE-PACKAGE.md', ROOT / 'release/REALTIME-ENTRY.md',
                  ROOT / 'deployment/systemd/coinstrategy-python-strategy@.service',
                  ROOT / 'deployment/realtime-live.env.example', ROOT / 'release/TRIAL-CONTROLS.md']
    payload = {}
    for path in paths:
        if path.is_symlink() or any(p.is_symlink() for p in path.parents):
            raise ValueError(f'symlink payload forbidden: {path}')
        payload[path.relative_to(ROOT).as_posix()] = path.read_bytes()
    config = {'synthetic': True, 'candidate': False, 'live_package': True, 'mode': 'LIVE',
              'strategy_version': strategy_version, 'timeframe_profile': LIVE_TIMEFRAME.value,
              'instance_key': LIVE_INSTANCE_KEY, 'max_active_positions': 3,
              'account_equity_usdt': '300', 'max_total_margin_rate': '0.10',
              'contracts': ['BTC-USDT', 'ETH-USDT'],
              'note': 'LIVE encode package only; sealed admission/approval required before trading'}
    if realtime:
        config.update(entry_enabled=False, financial_admission='NOT_ELIGIBLE',
                      structural_candidate_selected=False, trial_controls_implemented=True,
                      entry_period='15min', trend_period='60min',
                      models=['RT_RANGE_REJECTION', 'RT_BREAKOUT_RETEST'],
                      runtime_env_required={'HTX_RT_ENTRY_ENABLED': 'false'},
                      release_status='ENCODE_ONLY_ENTRY_DISABLED',
                      note='Original realtime structure implementation; minute/trade target studies are research only. '
                           'This example is not a deployable account configuration or trial approval.')
        # Pool, capital and trial limits are not manufactured runtime settings.
        for field in ('contracts', 'account_equity_usdt', 'max_active_positions', 'max_total_margin_rate'):
            config.pop(field)
        payload['release/migrations/2026-11-02__htx_realtime_structure_plan.sql'] = (
            ROOT.parent / 'sql/htx/2026-11-02__htx_realtime_structure_plan.sql').read_bytes()
        payload['release/migrations/2026-11-03__htx_realtime_live_trial.sql'] = (
            ROOT.parent / 'sql/htx/2026-11-03__htx_realtime_live_trial.sql').read_bytes()
    payload['release/config.example.json'] = encoded(config)
    bindings = {'source_commit': source, 'config_fingerprint': digest(encoded(config)),
                'config_hash': digest(encoded(config)), 'config_path': 'release/config.example.json',
                'strategy_version': strategy_version, 'timeframe_profile': LIVE_TIMEFRAME.value,
                'strategy_spec_hash': spec.spec_hash, 'instance_key': LIVE_INSTANCE_KEY,
                'package_sha256': {'ref': 'BUILD.json#/package_sha256', 'scope': 'final tar.gz bytes'}}
    manifest = {'schema': 'htx-live-package-g3', 'candidate': False, 'live': True, 'mode': 'LIVE', 'synthetic': True,
                'disclaimer': 'LIVE encode tarball ≠ deployment authority ≠ start trading',
                'version': version, 'source_commit': source,
                'build': {'created_at': datetime.now(UTC).isoformat(),
                          'working_tree_dirty': bool(git('status', '--porcelain')),
                          'mode': 'LIVE', 'instance_key': LIVE_INSTANCE_KEY,
                          'version_note': 'realtime-live is distinct from old trend LIVE; all LIVE code packages require separate authority'},
                'bindings': bindings, 'artifacts': refs,
                'files': {name: {'sha256': digest(raw), 'hash_mode': 'raw'} for name, raw in payload.items()}}
    manifest['files']['MANIFEST.json'] = {'sha256': '', 'hash_mode': 'canonical-json-self-sha256-empty'}
    manifest['files']['MANIFEST.json']['sha256'] = manifest_hash(manifest)
    payload['MANIFEST.json'] = encoded(manifest)
    out = Path(out_root) / version
    out.mkdir(parents=True, exist_ok=False)
    archive = out / f'htx-trend-python-{version}.tar.gz'
    with archive.open('wb') as raw:
        with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as tar:
                for name, data in sorted(payload.items()):
                    entry = tarfile.TarInfo(name)
                    entry.size, entry.mode = len(data), 0o644
                    tar.addfile(entry, io.BytesIO(data))
    package_sha = digest(archive.read_bytes())
    receipt = {'schema': 'htx-live-build-g3', 'candidate': False, 'live': True, 'synthetic': True,
               'disclaimer': manifest['disclaimer'], 'frozen_path': str(out.resolve()),
               'archive_path': str(archive.resolve()), 'archive': archive.name,
               'package_sha256': package_sha, 'manifest_sha256': digest(payload['MANIFEST.json']),
               'version': version, 'bindings': {**bindings, 'package_sha256': package_sha}}
    (out / 'MANIFEST.json').write_bytes(payload['MANIFEST.json'])
    (out / 'BUILD.json').write_bytes(encoded(receipt))
    archive.with_name(archive.name + '.sha256').write_text(f'{package_sha}  {archive.name}\n')
    verify_live(archive)
    return archive


def verify_live(archive: Path):
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
    if manifest.get('live') is not True or manifest.get('mode') != 'LIVE':
        raise ValueError('not a LIVE encode package')
    if manifest.get('candidate') is True or manifest.get('synthetic') is not True:
        raise ValueError('LIVE package metadata invalid')
    if not LIVE_VERSION_RE.fullmatch(manifest['version']):
        raise ValueError('not a LIVE package version')
    if manifest['bindings'].get('instance_key') != LIVE_INSTANCE_KEY:
        raise ValueError('LIVE instance binding mismatch')
    if set(manifest['files']) != set(names):
        raise ValueError('manifest membership mismatch')
    for name, entry in manifest['files'].items():
        mode = 'canonical-json-self-sha256-empty' if name == 'MANIFEST.json' else 'raw'
        hashed = manifest_hash(manifest) if name == 'MANIFEST.json' else digest(data[name])
        if entry != {'sha256': hashed, 'hash_mode': mode}:
            raise ValueError(f'member hash mismatch: {name}')
    if not any(n.startswith('deployment/') for n in names):
        raise ValueError('LIVE package must include deployment/')
    if (archive.parent / 'MANIFEST.json').read_bytes() != data['MANIFEST.json']:
        raise ValueError('external manifest mismatch')
    receipt = json.loads((archive.parent / 'BUILD.json').read_bytes())
    binding = {**manifest['bindings'], 'package_sha256': actual}
    if (receipt['package_sha256'] != actual or receipt['manifest_sha256'] != digest(data['MANIFEST.json'])
            or receipt['bindings'] != binding or receipt['archive'] != archive.name
            or receipt['version'] != manifest['version'] or receipt.get('live') is not True):
        raise ValueError('build receipt mismatch')
    config_path = binding['config_path']
    if digest(data[config_path]) != binding['config_fingerprint']:
        raise ValueError('config binding mismatch')
    cfg = json.loads(data[config_path])
    if cfg.get('mode') != 'LIVE' or cfg.get('instance_key') != LIVE_INSTANCE_KEY:
        raise ValueError('config LIVE binding mismatch')
    for ref in manifest['artifacts'].values():
        if digest(data[ref['path']]) != ref['sha256']:
            raise ValueError('artifact binding mismatch')
    realtime = manifest['version'].startswith('0.1.7-realtime-live-')
    expected_strategy = REALTIME_VERSION if realtime else LIVE_STRATEGY_VERSION
    card = json.loads(data[manifest['artifacts']['strategy_card']['path']])
    if any(value.get('strategy_version') != expected_strategy
           or value.get('timeframe_profile') != LIVE_TIMEFRAME.value
           for value in (binding, cfg, card)):
        raise ValueError('LIVE strategy/profile binding mismatch')
    if realtime:
        required = {'release/REALTIME-LIVE-PACKAGE.md', 'release/REALTIME-ENTRY.md',
                    'deployment/realtime-live.env.example',
                    'release/migrations/2026-11-02__htx_realtime_structure_plan.sql',
                    'release/migrations/2026-11-03__htx_realtime_live_trial.sql'}
        if (not required <= data.keys() or cfg.get('entry_enabled') is not False
                or cfg.get('financial_admission') != 'NOT_ELIGIBLE'
                or cfg.get('structural_candidate_selected') is not False
                or cfg.get('trial_controls_implemented') is not True
                or cfg.get('release_status') != 'ENCODE_ONLY_ENTRY_DISABLED'
                or cfg.get('runtime_env_required') != {'HTX_RT_ENTRY_ENABLED': 'false'}
                or cfg.get('entry_period') != '15min' or cfg.get('trend_period') != '60min'
                or cfg.get('models') != ['RT_RANGE_REJECTION', 'RT_BREAKOUT_RETEST']):
            raise ValueError('realtime LIVE encode-only binding mismatch')
        env = data['deployment/realtime-live.env.example'].decode().splitlines()
        switches = [line.strip() for line in env
                    if line.strip().startswith('HTX_RT_ENTRY_ENABLED=')]
        if switches != ['HTX_RT_ENTRY_ENABLED=false']:
            raise ValueError('realtime LIVE sample must disable entry')
    return receipt


def verify(archive: Path):
    archive = Path(archive)
    with tarfile.open(archive, 'r:gz') as tar:
        manifest = json.loads(tar.extractfile('MANIFEST.json').read())
    if manifest.get('live') is True:
        return verify_live(archive)
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
            or manifest.get('live') is True
            or not CANDIDATE_VERSION_RE.fullmatch(manifest['version'])):
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
    if '-realtime-candidate-' in manifest['version']:
        cfg = json.loads(data[binding['config_path']])
        card = json.loads(data[manifest['artifacts']['strategy_card']['path']])
        if (binding['strategy_version'] != REALTIME_VERSION or cfg.get('entry_enabled') is not False
                or cfg.get('financial_admission') != 'NOT_ELIGIBLE'
                or cfg['strategy_version'] != REALTIME_VERSION or card['strategy_version'] != REALTIME_VERSION
                or binding['timeframe_profile'] != 'INTRADAY_15M'):
            raise ValueError('realtime candidate binding mismatch')
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
    builder.add_argument('--realtime', action='store_true', help='15m realtime candidate; entry disabled; includes unapplied SQL')
    builder.add_argument('--compact-evidence', action='store_true', help='PAPER compact-v2 candidate; runtime switch still requires explicit env')
    builder.add_argument('--paper-4h', action='store_true',
                         help='PAPER 示例绑定 HTX_TREND_4H_V2 / htx-trend-paper-4h')
    live_builder = commands.add_parser('build-live', help='LIVE encode package for htx-trend-live-primary')
    live_builder.add_argument('--out-root', type=Path, default=Path('/tmp/htx-live/frozen'))
    live_builder.add_argument('--realtime', action='store_true', help='15m realtime LIVE code package; entry off; no trial authority')
    commands.add_parser('verify').add_argument('archive', type=Path)
    template = commands.add_parser('approval-template')
    template.add_argument('archive', type=Path)
    template.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'build':
        print(build(args.out_root, paper=args.paper, paper_4h=args.paper_4h, compact_evidence=args.compact_evidence, realtime=args.realtime))
    elif args.command == 'build-live':
        print(build_live(args.out_root, realtime=args.realtime))
    elif args.command == 'verify':
        print(json.dumps({'status': 'PASS', **verify(args.archive)}, ensure_ascii=False))
    else:
        value = approval_template(args.archive)
        with args.output.open('x') as output:
            output.write(canonical(value))
        print(args.output)


if __name__ == '__main__':
    main()
