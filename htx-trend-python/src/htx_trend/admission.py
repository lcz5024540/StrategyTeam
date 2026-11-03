"""G2 strict disk contracts built on the existing research and approval models.

Seals detect corruption, not signer identity. Trusted issuance remains external.
"""
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path

from .strategy_admission import _from_dict, ResearchStage, DecisionSurface, REQUIRED_STAGE
from .live_approval import LiveApproval, LiveRiskPolicy, _time


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def seal(value):
    value = {**value, 'seal_hash': ''}
    return {**value, 'seal_hash': sha256(canonical(value).encode()).hexdigest()}


def read_sealed(path, schema):
    raw = Path(path).read_bytes()
    value = json.loads(raw)
    if value.get('schema') != schema or type(value.get('synthetic')) is not bool:
        raise ValueError('invalid schema or missing synthetic declaration')
    if raw != canonical(value).encode() or value != seal(value):
        raise ValueError('seal hash or canonical bytes mismatch')
    return value


def window(value, now):
    issued, expires = _time(value['issued_at']), _time(value['expires_at'])
    if not issued <= now < expires:
        raise ValueError('artifact expired or not yet valid')
    return issued


def file_hash(root, ref, digest):
    path = (root / ref).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError('evidence path missing or outside artifact directory')
    if sha256(path.read_bytes()).hexdigest() != digest:
        raise ValueError('evidence/input hash mismatch')
    return path


def validate_admission(path, surface, *, now=None):
    now = now or datetime.now(UTC)
    requested = DecisionSurface(surface)
    value = read_sealed(path, 'htx-admission-g2')
    issued = window(value, now)
    artifact = _from_dict(value['artifact'])
    artifact.require(requested)
    if not artifact.artifact_hash:
        raise ValueError('unsealed research artifact')
    records = value['stage_records']
    if len(records) != 12 or {r['stage'] for r in records} != {int(s) for s in ResearchStage}:
        raise ValueError('all twelve stage records required')
    stages = {int(s.stage): s for s in artifact.stages}
    root = Path(path).parent
    for record in records:
        stage = stages.get(record['stage'])
        if stage is None or not record['signer'].strip() or not record['conclusion'].strip():
            raise ValueError('stage record missing')
        if not _time(record['recorded_at']) <= issued:
            raise ValueError('stage timestamp after issuance')
        if record['status'] in ('FAIL', 'UNKNOWN'):
            raise ValueError('stage FAIL/UNKNOWN')
        if record['status'] != stage.status.value or record['conclusion'] != stage.conclusion:
            raise ValueError('stage conclusion mismatch')
        if record['stage'] <= REQUIRED_STAGE[requested] and record['status'] != 'PASS':
            raise ValueError('stage FAIL/UNKNOWN')
        file_hash(root, record['input_ref'], record['input_sha256'])
        file_hash(root, stage.evidence_ref, stage.evidence_sha256)
    if REQUIRED_STAGE[requested] == ResearchStage.RESEARCH_REPORT:
        if value['synthetic']:
            raise ValueError('SYNTHETIC cannot authorize formal/LIVE actions')
        paper_stage = stages[int(ResearchStage.PAPER_EXECUTION)]
        paper = json.loads((root / paper_stage.evidence_ref).read_text())
        start, end = _time(paper['started_at']), _time(paper['ended_at'])
        if (paper.get('synthetic') is not False or paper.get('kind') != 'REAL_PAPER'
                or end - start < timedelta(days=14) or end > issued
                or end > _time(next(r['recorded_at'] for r in records if r['stage'] == 11))):
            raise ValueError('real PAPER >=14 days evidence required')
    return value, artifact


BINDINGS = ('strategy_version', 'timeframe_profile', 'source_commit', 'package_sha256',
            'config_fingerprint', 'strategy_spec_hash', 'account_id', 'risk', 'exit_version',
            'account_equity_usdt', 'max_total_margin_rate', 'exit_policy_hash', 'approved_by',
            'rollback_point', 'issued_at', 'expires_at')


def validate_approval(path, admission_path, current, surface='LIVE', *, now=None):
    now = now or datetime.now(UTC)
    value = read_sealed(path, 'htx-live-approval-g2')
    window(value, now)
    admission, artifact = validate_admission(admission_path, surface, now=now)
    formal = REQUIRED_STAGE[DecisionSurface(surface)] == ResearchStage.RESEARCH_REPORT
    if formal and value['synthetic']:
        raise ValueError('SYNTHETIC approval cannot authorize LIVE')
    payload = value['approval']
    for name in BINDINGS:
        actual = payload.get(name) if name in LiveApproval.__dataclass_fields__ else value.get(name)
        if actual is None or actual == '' or name not in current or actual != current[name]:
            raise ValueError(f'approval binding mismatch: {name}')
    approval = LiveApproval(**{**payload, 'risk': LiveRiskPolicy(**{
        k: Decimal(str(v)) if k.endswith('rate') else v for k, v in payload['risk'].items()})})
    approval.validate(now=now)
    if (value['admission_seal_hash'] != admission['seal_hash'] or approval.admission_hash != artifact.artifact_hash
            or approval.strategy_version != artifact.strategy_version
            or approval.source_commit != artifact.source_commit
            or approval.config_fingerprint != artifact.config_hash
            or approval.exit_version != 'HTX_TREND_EXIT_V4'):
        raise ValueError('admission cross binding mismatch')
    equity, margin = Decimal(value['account_equity_usdt']), Decimal(value['max_total_margin_rate'])
    if not equity.is_finite() or equity != 300 or not margin.is_finite() or not 0 < margin <= Decimal('.10'):
        raise ValueError('approval requires 300U and margin <=10%')
    for key in ('exit_policy_hash',):
        if len(value[key]) != 64 or any(c not in '0123456789abcdef' for c in value[key]):
            raise ValueError('invalid exit policy hash')
    if value['issued_at'] != approval.issued_at or value['expires_at'] != approval.expires_at:
        raise ValueError('approval validity mismatch')
    return value, approval


def runtime_guard(instance, account, profile):
    """Reload disk evidence on each entry; no cached approval or waiver bypass."""
    if getattr(instance, "execution_mode", None) == "PAPER":
        return None, None  # Simulation is not LIVE authorization.
    import os
    from .product_live_waiver import (ProductLiveWaiver, assert_no_dual_live_authorization,
                                      product_waiver_path_configured)
    from .execution import ExecutionConfig
    from .strategy_profiles import profile_strategy
    from .timeframes import TimeframeProfile
    waiver_path = os.environ.get('HTX_TREND_PRODUCT_LIVE_WAIVER_PATH', '').strip()
    if waiver_path:
        assert_no_dual_live_authorization()
        waiver = ProductLiveWaiver.load(Path(waiver_path))
        waiver.validate_runtime(instance, account, profile)
        return None, waiver.to_approval_view()
    admission = os.environ.get('HTX_TREND_STRATEGY_ADMISSION_PATH', '')
    approval = os.environ.get('HTX_TREND_LIVE_APPROVAL_PATH', '')
    context_path = os.environ.get('HTX_TREND_APPROVAL_CONTEXT_PATH', '')
    if product_waiver_path_configured():
        assert_no_dual_live_authorization()
    if not admission or not approval or not context_path:
        raise ValueError('LIVE requires G2 admission, approval and current context, or ProductLiveWaiver')
    current = json.loads(Path(context_path).read_text())
    config = ExecutionConfig.from_env()
    current.update(strategy_version=instance.strategy_name, timeframe_profile=profile,
        source_commit=os.environ.get('HTX_PYTHON_SOURCE_COMMIT', ''),
        package_sha256=os.environ.get('HTX_PYTHON_PACKAGE_SHA256', ''),
        config_fingerprint=instance.config_fingerprint, account_id=account.account_id,
        strategy_spec_hash=profile_strategy(TimeframeProfile(profile), strategy_version=instance.strategy_name).spec_hash,
        account_equity_usdt=str(config.account_equity_usdt), max_total_margin_rate=str(config.max_total_margin_rate))
    return validate_approval(Path(approval), Path(admission), current)
