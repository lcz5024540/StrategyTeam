#!/usr/bin/env python3
"""Seal LIVE admission/approval/context bindings from a verified LIVE encode package.

All outputs from fixture-backed commands stay ``synthetic:true`` and cannot
authorize formal LIVE. ``product-template`` only pre-fills operator metadata
(PRODUCT-DRAFT); it never emits ``synthetic:false``.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'src'))

from htx_trend.admission import BINDINGS, canonical, seal, validate_approval  # noqa: E402
from htx_trend.live_approval import LiveRiskPolicy, _hash  # noqa: E402
from htx_trend.strategy_admission import _from_dict  # noqa: E402

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location('release_package', ROOT / 'release/package.py')
_package = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_package)


def _policy_hash() -> str:
    return sha256(canonical({
        'per_trade_equity_rate': '0.005',
        'total_open_equity_rate': '0.015',
        'max_active_positions': 3,
    }).encode()).hexdigest()


def context_from_bindings(bindings: dict, *, account_id: int, approved_by: str,
                          issued_at: str, expires_at: str, rollback_point: str) -> dict:
    risk = LiveRiskPolicy()
    return {
        'strategy_version': bindings['strategy_version'],
        'timeframe_profile': bindings['timeframe_profile'],
        'source_commit': bindings['source_commit'],
        'package_sha256': bindings['package_sha256'],
        'config_fingerprint': bindings['config_fingerprint'],
        'strategy_spec_hash': bindings['strategy_spec_hash'],
        'account_id': account_id,
        'risk': {'per_trade_equity_rate': str(risk.per_trade_equity_rate),
                 'total_open_equity_rate': str(risk.total_open_equity_rate),
                 'max_active_positions': risk.max_active_positions},
        'exit_version': 'HTX_TREND_EXIT_V4',
        'account_equity_usdt': '300',
        'max_total_margin_rate': '0.10',
        'exit_policy_hash': _policy_hash(),
        'approved_by': approved_by,
        'rollback_point': rollback_point,
        'issued_at': issued_at,
        'expires_at': expires_at,
    }


DRAFT_DISCLAIMER = (
    'PRODUCT-DRAFT: fixture-backed binding scaffold only; '
    '≠ deployment authority; ≠ product-authorized seal; formal LIVE rejects this output'
)


def seal_demo_bundle(archive: Path, out_dir: Path, *, account_id: int = 7,
                     draft: bool = False, approved_by: str = 'SYNTHETIC',
                     issued_at: str | None = None, expires_at: str | None = None,
                     rollback_point: str = 'SYNTHETIC-NO-DEPLOY') -> dict:
    receipt = _package.verify_live(archive)
    bindings = {**receipt['bindings']}
    bindings['package_sha256'] = receipt['package_sha256']
    out_dir.mkdir(parents=True, exist_ok=True)
    fixture = ROOT / 'tests/fixtures/g2'
    shutil.copy2(fixture / 'evidence.json', out_dir / 'evidence.json')
    issued = issued_at or datetime(2026, 10, 18, tzinfo=UTC).isoformat().replace('+00:00', 'Z')
    expires = expires_at or datetime(2099, 1, 1, tzinfo=UTC).isoformat().replace('+00:00', 'Z')
    if draft:
        if not approved_by.strip() or approved_by == 'SYNTHETIC':
            raise ValueError('product-template requires a non-SYNTHETIC --approved-by for audit metadata only')
        if rollback_point.startswith('SYNTHETIC'):
            raise ValueError('product-template requires a non-SYNTHETIC --rollback-point label')
        if datetime.fromisoformat(expires.replace('Z', '+00:00')) <= datetime.fromisoformat(issued.replace('Z', '+00:00')):
            raise ValueError('product-template requires expires_at after issued_at')
    label = 'PRODUCT-DRAFT' if draft else 'SYNTHETIC'
    admission_raw = json.loads((fixture / 'admission.json').read_text())
    admission_raw['issued_at'] = issued
    admission_raw['expires_at'] = expires
    admission_raw['synthetic'] = True
    admission_raw['label'] = label
    artifact = admission_raw['artifact']
    artifact.update(source_commit=bindings['source_commit'],
                    config_hash=bindings['config_fingerprint'],
                    strategy_version=bindings['strategy_version'],
                    policy_hash=_policy_hash(),
                    artifact_hash='')
    admission_raw['artifact'] = json.loads(_from_dict(artifact).sealed().to_json())
    evidence_digest = sha256((out_dir / 'evidence.json').read_bytes()).hexdigest()
    for record in admission_raw['stage_records']:
        record['input_sha256'] = evidence_digest
    for stage in admission_raw['artifact']['stages']:
        stage['evidence_sha256'] = evidence_digest
    admission_doc = seal({**admission_raw, 'schema': 'htx-admission-g2'})
    (out_dir / 'admission.json').write_text(canonical(admission_doc))
    context = context_from_bindings(bindings, account_id=account_id, approved_by=approved_by,
                                    issued_at=issued, expires_at=expires, rollback_point=rollback_point)
    (out_dir / 'context.json').write_text(canonical(context))
    approval_inner = {k: context[k] for k in (
        'strategy_version', 'timeframe_profile', 'source_commit', 'package_sha256',
        'config_fingerprint', 'strategy_spec_hash', 'account_id', 'risk', 'exit_version',
        'issued_at', 'expires_at')}
    approval_inner['admission_hash'] = admission_doc['artifact']['artifact_hash']
    approval_inner['approval_hash'] = _hash(approval_inner, clear='approval_hash')
    approval_doc = seal({
        'schema': 'htx-live-approval-g2',
        'synthetic': True,
        'label': label,
        'note': DRAFT_DISCLAIMER if draft else 'demo seal only; not deployment authority',
        'approval': approval_inner,
        'admission_seal_hash': admission_doc['seal_hash'],
        'account_equity_usdt': context['account_equity_usdt'],
        'max_total_margin_rate': context['max_total_margin_rate'],
        'exit_policy_hash': context['exit_policy_hash'],
        'approved_by': approved_by,
        'rollback_point': rollback_point,
        'issued_at': issued,
        'expires_at': expires,
    })
    (out_dir / 'approval.json').write_text(canonical(approval_doc))
    manifest = {
        'archive': str(archive.resolve()),
        'package_sha256': receipt['package_sha256'],
        'instance_key': bindings.get('instance_key'),
        'synthetic': True,
        'label': label,
        'disclaimer': DRAFT_DISCLAIMER if draft else 'synthetic demo; formal LIVE validation must fail',
        'paths': {name: str(out_dir / name) for name in ('admission.json', 'approval.json', 'context.json', 'evidence.json')},
        'required_env': {
            'HTX_TREND_STRATEGY_ADMISSION_PATH': str(out_dir / 'admission.json'),
            'HTX_TREND_LIVE_APPROVAL_PATH': str(out_dir / 'approval.json'),
            'HTX_TREND_APPROVAL_CONTEXT_PATH': str(out_dir / 'context.json'),
            'HTX_PYTHON_PACKAGE_SHA256': receipt['package_sha256'],
            'HTX_PYTHON_SOURCE_COMMIT': bindings['source_commit'],
        },
    }
    (out_dir / 'SEAL_MANIFEST.json').write_text(canonical(manifest))
    return manifest


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    demo = sub.add_parser('demo', help='seal synthetic demo trio bound to a verified LIVE package')
    demo.add_argument('archive', type=Path, help='path to htx-trend-python-*-live-*.tar.gz')
    demo.add_argument('--output', type=Path, required=True)
    demo.add_argument('--account-id', type=int, default=7)
    product = sub.add_parser(
        'product-template',
        help='PRODUCT-DRAFT scaffold: synthetic:true only; operator fields are metadata, not authority',
    )
    product.add_argument('archive', type=Path)
    product.add_argument('--output', type=Path, required=True)
    product.add_argument('--account-id', type=int, required=True)
    product.add_argument('--approved-by', required=True)
    product.add_argument('--rollback-point', required=True)
    product.add_argument('--issued-at', required=True)
    product.add_argument('--expires-at', required=True)
    validate = sub.add_parser('validate', help='run validate_approval for a surface')
    validate.add_argument('--admission', type=Path, required=True)
    validate.add_argument('--approval', type=Path, required=True)
    validate.add_argument('--context', type=Path, required=True)
    validate.add_argument('--surface', default='LIVE')
    args = parser.parse_args(argv)
    if args.command == 'demo':
        manifest = seal_demo_bundle(args.archive, args.output, account_id=args.account_id)
        print(json.dumps({'status': 'SEALED', **manifest}, ensure_ascii=False))
        return
    if args.command == 'product-template':
        manifest = seal_demo_bundle(args.archive, args.output, account_id=args.account_id, draft=True,
                                    approved_by=args.approved_by, issued_at=args.issued_at,
                                    expires_at=args.expires_at, rollback_point=args.rollback_point)
        print(json.dumps({'status': 'SEALED', **manifest}, ensure_ascii=False))
        return
    current = json.loads(args.context.read_text())
    validate_approval(args.approval, args.admission, current, args.surface,
                      now=datetime.fromisoformat(current['issued_at'].replace('Z', '+00:00')) + timedelta(hours=1))
    print(json.dumps({'status': 'valid', 'surface': args.surface, 'bindings': list(BINDINGS)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
