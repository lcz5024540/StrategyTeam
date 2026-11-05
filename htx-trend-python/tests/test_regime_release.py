import importlib.util
import json
from pathlib import Path
import tarfile
import pytest

from htx_trend.realtime.regime_bayes import fit
from test_regime_bayes import labels, COST
from test_regime import NOW


def package_module():
    spec=importlib.util.spec_from_file_location('regime_release_test',Path(__file__).parents[1]/'release/package.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize('mode',['ATR_1_1','ATR_2_1','ATR_2_2'])
@pytest.mark.parametrize('live',[False,True])
def test_disabled_packages_bind_actual_model_and_exit(tmp_path,mode,live):
    m=package_module();archive=(m.build_live if live else m.build)(tmp_path,regime_exit=mode)
    m.verify(archive)
    with tarfile.open(archive) as tar:c=json.load(tar.extractfile('release/config.example.json'))
    assert c['models']==['RT_REGIME_AB'] and c['selection_id']=='REGIME_AB_V1'
    assert c['runtime_env_required']['HTX_REGIME_EXIT']==mode
    assert c['exit_policy']=='INDICATOR_BRACKET_V1/'+mode
    assert c['entry_enabled'] is False and c['financial_admission']=='NOT_ELIGIBLE'
    assert 'account_equity_usdt' not in c


def test_posterior_package_binds_snapshot(tmp_path):
    snapshot=tmp_path/'snapshot.json';snapshot.write_text(json.dumps(fit(labels(),cutoff_ms=NOW,cost_id=COST)))
    m=package_module();archive=m.build(tmp_path/'package',regime_exit='ATR_1_1',regime_snapshot=snapshot,regime_threshold='.90')
    m.verify(archive)
    with tarfile.open(archive) as tar:
        c=json.load(tar.extractfile('release/config.example.json'));s=json.load(tar.extractfile('release/regime-posterior.json'))
    assert c['parameters']['bayes_snapshot_hash']==s['hash']
    with pytest.raises(ValueError):m.build(tmp_path/'bad',regime_exit='ATR_1_1',union_exit='ATR_1_1')


@pytest.mark.parametrize('live', [False, True])
@pytest.mark.parametrize('mode', ['ATR_1_1', 'ATR_2_1', 'ATR_2_2', 'BAYES_NET_V1'])
def test_explicit_entry_enabled_package_binds_switch_without_issuing_authority(tmp_path, live, mode):
    m = package_module()
    options = {}
    if mode == 'BAYES_NET_V1':
        snapshot = tmp_path/'snapshot.json'
        snapshot.write_text(json.dumps(fit(labels(), cutoff_ms=NOW, cost_id=COST)))
        options = dict(regime_snapshot=snapshot, regime_threshold='.90')
    archive = (m.build_live if live else m.build)(tmp_path/'pkg', regime_exit=mode,
                                                enable_regime_entry=True, **options)
    m.verify(archive)
    with tarfile.open(archive) as tar:
        data = {p.name: tar.extractfile(p).read() for p in tar.getmembers()}
    cfg = json.loads(data['release/config.example.json'])
    assert cfg['entry_enabled'] is True
    assert cfg['runtime_env_required']['HTX_RT_ENTRY_ENABLED'] == 'true'
    assert cfg['financial_admission'] == 'NOT_ELIGIBLE'
    assert 'approval_hash' not in cfg and 'account_id' not in cfg
    binding = json.loads(data['MANIFEST.json'])['bindings']
    # Even a hash-rebuilt config cannot claim entry enabled with a disabled env.
    path = 'deployment/realtime-live.env.example' if live else 'release/realtime.env.example'
    data[path] = data[path].replace(b'HTX_RT_ENTRY_ENABLED=true', b'HTX_RT_ENTRY_ENABLED=false')
    with pytest.raises(ValueError, match='runtime env'):
        m.verify_union(cfg, binding, data)


@pytest.mark.parametrize('live', [False, True])
def test_entry_flag_cannot_enable_unselected_or_other_candidates(tmp_path, live):
    m = package_module()
    build = m.build_live if live else m.build
    for options in ({}, {'realtime': True}, {'union_exit': 'ATR_1_1'}):
        with pytest.raises(ValueError, match='requires --regime-exit'):
            build(tmp_path, enable_regime_entry=True, **options)


def test_enabled_package_env_reaches_factory_and_executes_paper_round_trip(tmp_path, monkeypatch):
    from types import SimpleNamespace
    from htx_trend.realtime import factory
    from htx_trend.realtime.replay import Actor
    from test_regime_exit_selection import account_tape, snapshot_for, params

    value = snapshot_for()
    snapshot = tmp_path/'snapshot.json'
    snapshot.write_text(json.dumps(value))
    archive = package_module().build(tmp_path/'pkg', regime_exit='BAYES_NET_V1',
        regime_snapshot=snapshot, regime_threshold='.90', enable_regime_entry=True)
    with tarfile.open(archive) as tar:
        for line in tar.extractfile('release/realtime.env.example').read().decode().splitlines():
            if line and not line.startswith('#'):
                key, val = line.split('=', 1)
                monkeypatch.setenv(key, val)
    monkeypatch.setenv('HTX_REGIME_SNAPSHOT', str(snapshot))
    tape_value = account_tape()
    actor = Actor(tape_value, params(value))
    old = actor.worker
    monkeypatch.setattr(factory, 'PublicStream', lambda *_: old.stream)
    monkeypatch.setattr(factory, 'FrameLoader', lambda *a, **kw: old.loader)
    monkeypatch.setattr(factory, 'SegmentRecorder', lambda *a, **kw: old.recorder)
    actor.worker = factory._common(old.instance, old.engine, old.broker, old.market,
        old.frames, ['ETH-USDT'], old.evidence, execution=old.execution, leverage=old.leverage,
        policy=SimpleNamespace(to_dict=lambda: old.holding_parameters), entry_config=old.entry_config,
        management=old.management)
    actor.worker.clock = old.clock
    actor.worker.started = True
    actor.worker.last_epoch = 'replay'
    try:
        assert actor.worker.entry_enabled and actor.worker.cost_observer is not None
        assert actor.worker.parameters.fingerprint == old.parameters.fingerprint
        for event in tape_value['observations']: actor.step(event)
        result = actor.result()
        assert not result['errors'] and len(result['fills']) == 2
        assert result['positions'][0]['status'] == 'CLOSED'
        assert result['cost_observations']
    finally:
        actor.close()
