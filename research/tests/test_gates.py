import ast
from pathlib import Path
import numpy as np
import pytest
from coinresearch.guard import SealedDataset, BlindGuardError
from coinresearch.g2 import run_g2, block_indices
from coinresearch.g0 import run_g0, random_ls, random_events, deff_neff, acf_neff, kof, mde
from coinresearch.data import build_dataset
from coinresearch.cli import main


def ds(values, prereg=None):
    values = np.asarray(values).reshape(-1, 1)
    d = SealedDataset(values, np.arange(len(values)))
    return d.seal(**prereg) if prereg else d


def test_seal_switch_and_g2_sha(prereg):
    d = ds(np.ones(40))
    with pytest.raises(BlindGuardError): d.directional_observations(**prereg)
    with pytest.raises(BlindGuardError): run_g2(d, **prereg, neff=40, neff_threshold=30, hard_floor=10, nominal_floor=1)
    wrong = dict(prereg, prereg_sha256='0'*64)
    with pytest.raises(BlindGuardError): d.seal(**wrong)
    d.seal(**prereg)
    with pytest.raises(BlindGuardError): d.directional_observations(**wrong)
    with pytest.raises(BlindGuardError): run_g2(d, **wrong, neff=40, neff_threshold=30, hard_floor=10, nominal_floor=1)
    with pytest.raises(TypeError): run_g2(d, neff=40, neff_threshold=30, hard_floor=10, nominal_floor=1)
    assert d.directional_observations(**prereg).shape == (40, 1)
    prereg['prereg_path'].write_text('{}')
    with pytest.raises(BlindGuardError): d.directional_observations(**prereg)


def test_split_required(prereg):
    import hashlib
    prereg['prereg_path'].write_text('{}')
    prereg['prereg_sha256'] = hashlib.sha256(b'{}').hexdigest()
    with pytest.raises(BlindGuardError): ds(np.ones(40), prereg)


@pytest.mark.parametrize('value,neff,status,unlock', [(1,40,'PASS',True), (1,20,'SMALL_SAMPLE_PASS',False), (-1,40,'FAIL/NO_EDGE',False), (1,5,'REFUSED_BELOW_FLOOR',False)])
def test_g2_states(prereg, value, neff, status, unlock):
    r = run_g2(ds(np.full(40,value), prereg), **prereg, neff=neff, neff_threshold=30, hard_floor=10, nominal_floor=1)
    assert r['status'] == status and r['unlock_g3_request'] == unlock
    if neff < 10: assert r['ci'] is None and r['mean'] is None


def test_bootstrap_joint_calendar_blocks(prereg):
    x = np.column_stack([np.arange(60), -np.arange(60)])
    d = SealedDataset(x, np.arange(60)).seal(**prereg)
    r = run_g2(d, **prereg, neff=50, neff_threshold=30, hard_floor=10, nominal_floor=1, block_length=7)
    assert r['ci'] == [0., 0.]
    for circular in (True,False):
        i = block_indices(60, block_length=7, B=2000, seed=10, circular=circular)
        assert i.shape == (2000,60) and i.min() >= 0 and i.max() < 60
        np.testing.assert_array_equal(np.diff(i[:,:7], axis=1)%60, 1)
    with pytest.raises(ValueError): SealedDataset(x, np.arange(60)*2)
    with pytest.raises(ValueError): run_g2(d, **prereg, neff=50, neff_threshold=30, hard_floor=10, nominal_floor=1, B=100)
    with pytest.raises(ValueError): run_g2(d, **prereg, neff=50, neff_threshold=30, hard_floor=10, nominal_floor=1, block_length=61)


def test_g2_cli_missing_wrong_sha(prereg, tmp_path):
    base = ['g2','--observations',str(tmp_path/'missing.csv'),'--prereg-path',str(prereg['prereg_path']), '--seal','--neff','40','--neff-threshold','30','--hard-floor','10','--nominal-floor','1','--out-dir',str(tmp_path/'out')]
    with pytest.raises(SystemExit) as e: main(base)
    assert e.value.code != 0
    with pytest.raises(SystemExit) as e: main(base+['--prereg-sha256','wrong'])
    assert e.value.code != 0 and not (tmp_path/'out').exists()


def test_g0_random_only_and_explicit_target(synthetic):
    root, cfg = synthetic; d = build_dataset(root, cfg)
    a = run_g0(d, target_bps=20, cfg=cfg)
    assert a == run_g0(d, target_bps=20, cfg=cfg)
    assert a['full_ge10']['cost_full_bps'] == 24
    assert a['full_ge10']['N'] == np.sum(d.EL.sum(1)>=10)
    assert set(a['split']['coin_A']).isdisjoint(a['split']['coin_B'])
    assert len(a['split']['time_explore']) == int(a['full_ge10']['N']*2/3)
    with pytest.raises(TypeError): run_g0(d)
    with pytest.raises(ValueError): run_g0(d, target_bps=-1, cfg=cfg)
    rows = np.flatnonzero(d.EL.sum(1)>=10)
    p = random_ls(d, rows, d.EL, .2, 20, cfg.seed)
    assert p['port'].shape == (20,len(rows)) and np.all(p['dw'][:,0] == 4)
    assert random_events(d, rows, n_events=50)['sigma_event_bps'] > 0
    assert kof(17,.2) == 3 and kof(18,.2) == 4 and kof(5,.2) == 2
    assert mde(10,100) == pytest.approx(2.486)


def test_known_icc_deff_calendar_clusters():
    rng = np.random.default_rng(472)
    k, m, rho = 4000, 8, .4
    x = np.sqrt(rho)*rng.normal(size=(k,1)) + np.sqrt(1-rho)*rng.normal(size=(k,m))
    ts = (np.arange(k)[:,None]*5*86400 + np.arange(m)*3600).ravel()
    estimate = deff_neff(x.ravel(), ts)
    assert estimate['icc'] == pytest.approx(rho, abs=.025)
    exact = deff_neff(x.ravel(), ts, icc=rho)
    assert exact['neff'] == pytest.approx(k*m/(1+(m-1)*rho))
    assert estimate['neff'] == pytest.approx(exact['neff'], rel=.06)
    print('DEFF_ESTIMATE', dict(estimated=estimate['neff'], exact=exact['neff'], relative_error=abs(estimate['neff']/exact['neff']-1)))
    assert acf_neff(rng.normal(size=1000)) <= 1000


def test_no_runtime_imports_or_signal_rank_interfaces():
    root = Path(__file__).parents[1]/'coinresearch'
    forbidden = 'htx' + '_trend'
    for file in root.glob('*.py'):
        tree = ast.parse(file.read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                assert all(not a.name.startswith(forbidden) for a in node.names)
            if isinstance(node, ast.ImportFrom):
                assert not (node.module or '').startswith(forbidden)
            if isinstance(node, (ast.FunctionDef,ast.AsyncFunctionDef)):
                assert not any(s in node.name for s in ('momentum_pnl', 'signal_pnl', 'rank_pnl', 'long_strong'))
        # Ranking operations in G0 must be random allocations or funding bounds.
        if file.name == 'g0.py':
            for line in file.read_text().splitlines():
                if 'argsort(' in line: assert 'rng.random' in line


def test_g2_cli_success(prereg, tmp_path):
    import pandas as pd
    import json
    path = tmp_path/'observations.csv'
    pd.DataFrame({'period_index': np.arange(40), 'synthetic': np.ones(40)}).to_csv(path, index=False)
    assert main(['g2','--observations',str(path),'--prereg-path',str(prereg['prereg_path']), '--prereg-sha256',prereg['prereg_sha256'],'--seal','--neff','40','--neff-threshold','30','--hard-floor','10','--nominal-floor','1','--out-dir',str(tmp_path/'out')]) == 0
    result = json.loads((tmp_path/'out/g2_results.json').read_text())
    assert result['status'] == 'PASS' and result['B'] == 2000


def test_g0_reference_arithmetic(synthetic):
    from coinresearch.g0 import summarize
    root, cfg = synthetic
    d = build_dataset(root, cfg)
    rows = np.flatnonzero(d.EL.sum(1)>=10)
    r = summarize(d, rows, d.EL, target_bps=20, cfg=cfg)
    o = random_ls(d, rows, d.EL, cfg.frac, cfg.ndraw, cfg.seed)
    assert r['sigma_a_bps'] == np.median(o['port'].std(1,ddof=1))*1e4
    assert r['cost_random_bps'] == cfg.rt_cost/2*o['dw'].mean()
    assert r['fund_drag_mean_bps'] == np.median((o['fdrag']*1e4).mean(1))
    assert r['mde_a'] == mde(r['sigma_a_bps'],r['neff'],cfg.z)
    assert 0 < r['neff_deff'] <= r['N']
    # Identical funding on all coins cancels under any randomized split.
    d.F[d.EL] = .001
    o = random_ls(d, rows, d.EL, cfg.frac, cfg.ndraw, cfg.seed)
    np.testing.assert_allclose(o['fdrag'], 0, atol=1e-18)
