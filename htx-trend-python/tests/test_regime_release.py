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
