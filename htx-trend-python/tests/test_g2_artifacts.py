import json
import shutil
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
from htx_trend.admission import (BINDINGS, canonical, seal, validate_admission, validate_approval, runtime_guard)
from htx_trend.admission_self_test import run

NOW = datetime(2026, 10, 19, tzinfo=UTC)


@pytest.fixture
def bundle(tmp_path):
    shutil.copytree(Path(__file__).parent / 'fixtures/g2', tmp_path, dirs_exist_ok=True)
    return tmp_path


def mutate(path, change, reseal=False):
    value = json.loads(path.read_text())
    change(value)
    path.write_text(canonical(seal(value) if reseal else value))


def check(root, surface='OBSERVE'):
    return validate_admission(root/'admission.json', surface, now=NOW)


def approve(root, surface='PAPER'):
    return validate_approval(root/'approval.json', root/'admission.json',
                             json.loads((root/'context.json').read_text()), surface, now=NOW)


def test_g2_1_complete_schema_fixture(bundle):
    value, artifact = check(bundle)
    assert len(value['stage_records']) == len(artifact.stages) == 12
    assert value['synthetic'] and value['label'] == 'SYNTHETIC'


@pytest.mark.parametrize('target', ['admission.json', 'evidence.json'])
def test_g2_1_any_byte_mutation(bundle, target):
    path = bundle/target
    path.write_bytes(path.read_bytes()+b' ')
    with pytest.raises(ValueError): check(bundle)


def test_g2_1_missing_stage(bundle):
    mutate(bundle/'admission.json', lambda v:v['stage_records'].pop(), True)
    with pytest.raises(ValueError, match='twelve'): check(bundle)


@pytest.mark.parametrize('surface', ['OBSERVE', 'BACKTEST', 'SHADOW', 'PAPER'])
def test_g2_2_all_pass_research(bundle, surface):
    check(bundle, surface)


@pytest.mark.parametrize('field,value', [('input_sha256','0'*64), ('signer',''),
    ('status','FAIL'), ('status','UNKNOWN'), ('recorded_at','2098-01-01T00:00:00Z')])
def test_g2_2_stage_invalid(bundle, field, value):
    mutate(bundle/'admission.json',lambda v:v['stage_records'][0].update({field:value}),True)
    with pytest.raises(ValueError): check(bundle)


@pytest.mark.parametrize('surface', ['LIVE','RANKING','GATE','SIZING','EXIT','BOGUS'])
def test_g2_2_synthetic_or_unknown_surface_rejected(bundle, surface):
    with pytest.raises(ValueError): check(bundle,surface)


def test_g2_2_expired(bundle):
    with pytest.raises(ValueError,match='expired'):
        validate_admission(bundle/'admission.json','OBSERVE',now=datetime(2100,1,1,tzinfo=UTC))


def test_g2_2_no_real_paper(bundle):
    mutate(bundle/'admission.json',lambda v:v.update(synthetic=False),True)
    with pytest.raises(ValueError,match='real PAPER'): check(bundle,'LIVE')


def test_g2_2_surface_overreach(bundle):
    from htx_trend.strategy_admission import _from_dict
    def narrow(v):
        v['artifact']['allowed_surfaces']=['OBSERVE']
        v['artifact']['artifact_hash']=''
        v['artifact']=json.loads(_from_dict(v['artifact']).sealed().to_json())
    mutate(bundle/'admission.json',narrow,True)
    with pytest.raises(ValueError,match='not admitted'): check(bundle,'PAPER')


def test_g2_3_synthetic_approval_structural_pass(bundle):
    assert approve(bundle)[0]['synthetic']


@pytest.mark.parametrize('field', BINDINGS)
@pytest.mark.parametrize('missing', [False,True])
def test_g2_3_each_binding_missing_or_modified(bundle,field,missing):
    def change(v):
        owner=v['approval'] if field in v['approval'] else v
        if missing: owner.pop(field)
        else: owner[field]='tampered'
    mutate(bundle/'approval.json',change,True)
    with pytest.raises((ValueError,KeyError)): approve(bundle)


@pytest.mark.parametrize('field',BINDINGS)
def test_g2_4_current_binding_mismatch(bundle,field):
    mutate(bundle/'context.json',lambda v:v.update({field:'different'}))
    with pytest.raises(ValueError,match='binding'): approve(bundle)


def test_g2_4_admission_seal_mismatch(bundle):
    mutate(bundle/'approval.json',lambda v:v.update(admission_seal_hash='0'*64),True)
    with pytest.raises(ValueError,match='cross binding'): approve(bundle)


def test_g2_4_admission_surface_insufficient(bundle):
    test_g2_2_surface_overreach(bundle)
    with pytest.raises(ValueError,match='not admitted'): approve(bundle)


def test_g2_4_synthetic_live_rejected(bundle):
    with pytest.raises(ValueError,match='SYNTHETIC'): approve(bundle,'LIVE')


def test_g2_4_approval_expired(bundle):
    mutate(bundle/'approval.json',lambda v:v.update(expires_at='2026-10-18T00:01:00Z'),True)
    with pytest.raises(ValueError,match='expired'): approve(bundle)


def test_g2_5_live_missing_artifacts(monkeypatch):
    for key in ('HTX_TREND_STRATEGY_ADMISSION_PATH','HTX_TREND_LIVE_APPROVAL_PATH','HTX_TREND_APPROVAL_CONTEXT_PATH'):
        monkeypatch.delenv(key,raising=False)
    with pytest.raises(ValueError,match='LIVE requires'):
        runtime_guard(None,None,'INTRADAY_15M')


def test_g2_5_entry_rejects_before_side_effects(monkeypatch):
    from test_execution_service import Repository, Executor, executor, candidate
    from htx_trend.market_data import ContractSpec
    from htx_trend.strategy_context import MarketContext
    from decimal import Decimal as D
    monkeypatch.delenv('HTX_TREND_LIVE_APPROVAL_PATH',raising=False)
    repo, live = Repository(), Executor()
    result=executor(repo,live).submit(run_id='g2',candidate=candidate(),period='15min',atr=D(2),
        ema_fast=D(101),ema_slow=D(99),adx=D(30),recent_candles=(),book=None,
        contract=ContractSpec('BTC-USDT',D('.01'),D('.1'),1),funding=None,market_context=MarketContext())
    assert not result.accepted and result.gate.stage=='LIVE_APPROVAL'
    assert not repo.calls and not live.calls


def test_g2_6_self_test():
    assert run()=={'status':'PASS','checks':2,'synthetic':True}


def test_g2_5_startup_missing_artifacts(monkeypatch):
    from htx_trend.worker import _validate_live_approval
    monkeypatch.setenv('HTX_TREND_MAX_ACTIVE_POSITIONS','3')
    from htx_trend.timeframes import TimeframeProfile
    monkeypatch.delenv('HTX_TREND_LIVE_APPROVAL_PATH',raising=False)
    instance=SimpleNamespace(strategy_name='HTX_TREND_15M_V2',config_fingerprint='a'*64)
    scan=SimpleNamespace(max_active_positions=3,entry_enabled=True,timeframe_profile=TimeframeProfile.INTRADAY_15M)
    with pytest.raises(ValueError,match='LIVE requires'):
        _validate_live_approval(instance,SimpleNamespace(account_id=7),None,(scan,))


def test_g2_5_observe_and_paper_unaffected(bundle,monkeypatch):
    from htx_trend.worker import _load_admission
    monkeypatch.setenv('HTX_TREND_STRATEGY_ADMISSION_PATH',str(bundle/'admission.json'))
    assert _load_admission('HTX_TREND_15M_V2').strategy_version=='HTX_TREND_15M_V2'
    check(bundle,'PAPER')


def test_g2_5_legacy_default_closed():
    from htx_trend.execution_service import EntryExecutionConfig
    assert EntryExecutionConfig().allow_legacy_swing_live is False


def test_g2_4_full_pass_with_mocked_real_evidence_reader(bundle,monkeypatch):
    # Only mock evidence provenance at the admission-reader boundary. Never
    # persist a fixture that purports to be a real LIVE approval.
    import htx_trend.admission as module
    value, artifact=check(bundle,'PAPER')
    monkeypatch.setattr(module,'validate_admission',lambda *a,**kw:(value,artifact))
    mutate(bundle/'approval.json',lambda v:v.update(synthetic=False),True)
    assert approve(bundle,'LIVE')[1].admission_hash==artifact.artifact_hash


@pytest.mark.parametrize('days,synthetic,kind',[(13,False,'REAL_PAPER'),(14,True,'REAL_PAPER'),(14,False,'SIMULATED_PAPER')])
def test_g2_2_paper_duration_and_provenance(bundle,days,synthetic,kind):
    # Exercise provenance parsing in memory; all persisted fixtures remain SYNTHETIC.
    from hashlib import sha256
    from htx_trend.strategy_admission import _from_dict
    from datetime import timedelta
    evidence=json.loads((bundle/'evidence.json').read_text())
    evidence.update(synthetic=synthetic,kind=kind,ended_at=(datetime(2026,9,1,tzinfo=UTC)+timedelta(days=days)).isoformat())
    raw=canonical(evidence)
    (bundle/'evidence.json').write_text(raw)
    digest=sha256(raw.encode()).hexdigest()
    def update(v):
        v['synthetic']=False
        for record in v['stage_records']: record['input_sha256']=digest
        for stage in v['artifact']['stages']: stage['evidence_sha256']=digest
        v['artifact']['artifact_hash']=''
        v['artifact']=json.loads(_from_dict(v['artifact']).sealed().to_json())
    mutate(bundle/'admission.json',update,True)
    with pytest.raises(ValueError,match='real PAPER'): check(bundle,'LIVE')


def test_g2_6_cli_commands(bundle,monkeypatch,capsys):
    from htx_trend.cli import main
    for argv in [ ['validate-admission','--artifact',str(bundle/'admission.json'),'--surface','PAPER'],
        ['validate-approval','--artifact',str(bundle/'approval.json'),'--admission',str(bundle/'admission.json'),
         '--context',str(bundle/'context.json'),'--surface','PAPER'],['self-test'] ]:
        monkeypatch.setattr('sys.argv',['htx-trend',*argv])
        main()
        assert json.loads(capsys.readouterr().out)['status'] in ('valid','PASS')


def test_g2_2_and_g2_4_full_contract_pass_in_isolated_test(bundle):
    # Simulate trusted issuance only in pytest's disposable directory.
    # Repository fixtures always remain SYNTHETIC, never research evidence.
    from hashlib import sha256
    from htx_trend.strategy_admission import _from_dict
    from htx_trend.live_approval import _hash
    evidence=json.loads((bundle/'evidence.json').read_text())
    evidence.update(synthetic=False,kind='REAL_PAPER')
    raw=canonical(evidence)
    (bundle/'evidence.json').write_text(raw)
    digest=sha256(raw.encode()).hexdigest()
    def update(v):
        v['synthetic']=False
        for record in v['stage_records']: record['input_sha256']=digest
        for stage in v['artifact']['stages']: stage['evidence_sha256']=digest
        v['artifact']['artifact_hash']=''
        v['artifact']=json.loads(_from_dict(v['artifact']).sealed().to_json())
    mutate(bundle/'admission.json',update,True)
    admission, artifact=check(bundle,'LIVE')
    def update_approval(v):
        v['synthetic']=False
        v['admission_seal_hash']=admission['seal_hash']
        v['approval']['admission_hash']=artifact.artifact_hash
        v['approval']['approval_hash']=_hash(v['approval'],clear='approval_hash')
    mutate(bundle/'approval.json',update_approval,True)
    assert approve(bundle,'LIVE')[1].admission_hash==artifact.artifact_hash
    (bundle/'evidence.json').write_text(raw+' ')
    with pytest.raises(ValueError,match='hash'): approve(bundle,'LIVE')


def test_g2_5_entry_reloads_invalidated_artifacts(bundle,monkeypatch):
    from test_execution_service import Repository, Executor, executor, candidate
    from htx_trend.market_data import ContractSpec
    from htx_trend.strategy_context import MarketContext
    from decimal import Decimal as D
    approve(bundle)
    monkeypatch.setenv('HTX_TREND_STRATEGY_ADMISSION_PATH',str(bundle/'admission.json'))
    monkeypatch.setenv('HTX_TREND_LIVE_APPROVAL_PATH',str(bundle/'approval.json'))
    monkeypatch.setenv('HTX_TREND_APPROVAL_CONTEXT_PATH',str(bundle/'context.json'))
    mutate(bundle/'approval.json',lambda v:v.update(expires_at='2026-10-18T00:01:00Z'),True)
    repo, live=Repository(),Executor()
    result=executor(repo,live).submit(run_id='g2',candidate=candidate(),period='15min',atr=D(2),
        ema_fast=D(101),ema_slow=D(99),adx=D(30),recent_candles=(),book=None,
        contract=ContractSpec('BTC-USDT',D('.01'),D('.1'),1),funding=None,market_context=MarketContext())
    assert not result.accepted and result.gate.stage=='LIVE_APPROVAL'
    assert 'expired' in result.reason
    assert not repo.calls and not live.calls
