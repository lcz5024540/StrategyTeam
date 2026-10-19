from dataclasses import replace
import numpy as np
import pandas as pd
import pytest
from coinresearch.config import Config
from coinresearch.data import build_dataset, eligibility_at_bars, funding_covered, normalize_funding_8h
from coinresearch.cli import hashes, main


def bars(n=550):
    return pd.DataFrame(dict(ts=np.arange(n)*14400, open=100., high=101., low=99., close=100., vol=1., turnover=5e6/6))


def test_byte_determinism(synthetic, tmp_path):
    root, cfg = synthetic
    for name in ('a', 'b'):
        build_dataset(root, cfg).write(tmp_path/name)
    assert hashes(tmp_path/'a') == hashes(tmp_path/'b')
    assert len(hashes(tmp_path/'a')) > 8


def test_adv_listing_gap_and_current_bar_boundaries():
    b = bars(1100)
    g = eligibility_at_bars(b)
    assert not g.eligible.iloc[1078]
    assert g.eligible.iloc[1079]  # inclusive bar reaches 180 days
    assert g.adv.iloc[-1] == pytest.approx(5e6)
    b.turnover *= .999
    assert not eligibility_at_bars(b).eligible.iloc[-1]
    cfg = replace(Config(), list_days=0, win90=100, adv_days=1, turn_min=1, gap_max=.01)
    b = bars(105)
    assert eligibility_at_bars(b, cfg).eligible.iloc[-1]
    missing = b.drop(index=50)
    assert not eligibility_at_bars(missing, cfg).eligible.iloc[-1]  # exactly 1% fails
    cfg = replace(cfg, gap_max=.010001)
    assert eligibility_at_bars(missing, cfg).eligible.iloc[-1]
    g = eligibility_at_bars(b.drop(index=103), cfg)
    assert not g.loc[103*14400, 'present']
    assert not g.loc[103*14400, 'eligible']
    assert np.isnan(g.adv.iloc[98])


def test_causal_bar_eligibility():
    cfg = replace(Config(), list_days=0)
    b = bars(700); t = b.ts.iloc[600]
    before = eligibility_at_bars(b, cfg).loc[:t].copy()
    b.loc[b.ts > t, ['close', 'turnover']] = [0.1, 0.]
    after = eligibility_at_bars(b, cfg).loc[:t]
    pd.testing.assert_frame_equal(before, after)


@pytest.mark.parametrize('first,last,gaps,expected', [
    (0, 48*3600-9*3600, [], True),
    (0, 48*3600-9*3600-1, [], False),
    (-14400+1, 48*3600, [], False),
    (0, 48*3600, [(0, 10*3600)], False),
    (0, 48*3600, [(48*3600, 60*3600)], True),
])
def test_funding_coverage_boundaries(first, last, gaps, expected):
    # Entry 0 and rebalance -4h: offset all funding arguments to cover rebalance.
    first = first if first else -14400
    assert funding_covered(-14400, 0, 48*3600, first, last, gaps) == expected


def test_funding_rebalance_gap_and_grace():
    assert not funding_covered(10, 20, 30, 0, 100, [(5, 15)])
    assert funding_covered(10, 20, 30, 0, 100, [(0, 10)])
    cfg = replace(Config(), rebalance_grace_hours=0, funding_gap_hours=100)
    assert not funding_covered(10, 20, 30, 0, 9, [], cfg)


def test_normalize_hourly_cap_preserved():
    f = pd.DataFrame({'ts': np.arange(1,17)*3600, 'funding_rate': [.001]*16})
    result = normalize_funding_8h(f)
    assert len(result) == 2
    np.testing.assert_allclose(result.rate_8h, .008)
    assert result.cap_hit.all() and result.complete.all()
    assert not normalize_funding_8h(f, cap=.002).cap_hit.any()
    assert not normalize_funding_8h(f.iloc[1:]).complete.iloc[0]


@pytest.mark.parametrize('hours', [4, 6, 8])
def test_other_funding_cadences(hours):
    f = pd.DataFrame({'ts': np.arange(4)*hours*3600, 'funding_rate': [.001,.002,.003,.001]})
    result = normalize_funding_8h(f)
    np.testing.assert_allclose(result.rate_8h, f.funding_rate*8/hours)
    assert not result.cap_hit.any()  # unique maximum is not inferred cap


def test_d1_cli(synthetic, tmp_path):
    root, _ = synthetic
    assert main(['d1', '--data-dir', str(root), '--out-dir', str(tmp_path/'out')]) == 0
    assert (tmp_path/'out/data_manifest.json').exists()


def test_manifest_gap_inventory_and_funding_endpoints(synthetic):
    import hashlib
    root, cfg = synthetic
    d = build_dataset(root, cfg)
    for item in d.manifest['files']:
        assert not item['path'].startswith('/')
        assert item['sha256'] == hashlib.sha256((root/item['path']).read_bytes()).hexdigest()
        if item['rows'] is not None:
            assert item['first_ts'] <= item['last_ts']
            assert item['first_utc'].endswith('+00:00')
    i, j = np.argwhere(d.EL)[0]
    e = d.entries[i]
    f = pd.read_csv(root/f'funding/{d.coins[j]}.csv')
    expected = f.loc[(f.ts > e) & (f.ts <= e+cfg.per), 'funding_rate'].sum()
    assert d.F[i,j] == expected
    # A newly observed >9h funding gap must be reported and exclude its window,
    # even if the supplied manifest omitted that gap.
    f = f.loc[f.ts != e+28800]
    f.to_csv(root/f'funding/{d.coins[j]}.csv', index=False)
    broken = build_dataset(root, cfg)
    assert not broken.EL[i,j]
    assert any(g['kind'] == 'funding' for g in broken.gaps)


def test_warmup_strictly_greater_and_d1_seal(synthetic, prereg):
    from coinresearch.guard import BlindGuardError
    root, cfg = synthetic
    d = build_dataset(root, cfg)
    b = bars(30)
    g = eligibility_at_bars(b, replace(cfg, warm=20))
    assert not g.warm.iloc[19] and g.warm.iloc[20]
    guarded = d.bind_observations(np.zeros_like(d.R))
    with pytest.raises(BlindGuardError): guarded.directional_observations(**prereg)
    guarded.seal(**prereg)
    with pytest.raises(BlindGuardError):
        guarded.directional_observations(**dict(prereg, prereg_sha256='wrong'))
    assert guarded.directional_observations(**prereg).shape == d.R.shape
