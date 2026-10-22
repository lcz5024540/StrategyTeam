"""Fixed-seed acceptance: ≥5000 experiments, test-only B=500, no seed search."""
import hashlib
import numpy as np
import pytest
from coinresearch.guard import SealedDataset
from coinresearch.g2 import run_g2, block_indices
from coinresearch.g0 import mde


@pytest.fixture(scope='module')
def calibration(tmp_path_factory):
    path = tmp_path_factory.mktemp('calibration')/'prereg.json'
    path.write_text('{"split": {"test": "all synthetic replicates"}}')
    seal = dict(prereg_path=path, prereg_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    rng = np.random.default_rng(20261019)
    R, n, coins, rho = 5000, 1200, 4, .5
    # Shared volatility stays fixed for 10 days: squared returns cluster.
    # E(vol^2)=1; independent innovations imply zero return autocovariance.
    # Analytic cross-coin correlation=.5, portfolio variance=.625.
    variance = rho+(1-rho)/coins
    effect = mde(np.sqrt(variance), n)
    positives = power = coverage = 0
    for rep in range(R):
        vol = np.repeat(rng.choice([np.sqrt(.25), np.sqrt(1.75)], size=n//10), 10)
        x = vol[:, None]*(np.sqrt(rho)*rng.normal(size=(n,1)) + np.sqrt(1-rho)*rng.normal(size=(n,coins)))
        params = dict(**seal, neff=n, neff_threshold=4400, hard_floor=1000,
                      block_length=30, B=500, seed=10000+rep, allow_small_B_for_tests=True)
        result = run_g2(SealedDataset(x, np.arange(n)).seal(**seal), **params)
        lo, hi = result['ci']
        positives += lo > 0
        coverage += lo <= 0 <= hi
        shifted = run_g2(SealedDataset(x+effect, np.arange(n)).seal(**seal), **params)
        power += shifted['ci'][0] > 0
    return dict(repetitions=R, master_seed=20261019, false_positive=positives/R, power=power/R, coverage=coverage/R, effect=effect)


def test_nominal_5pct_one_sided_false_positive(calibration):
    print('CALIBRATION', calibration)
    assert calibration['repetitions'] >= 5000
    assert .035 <= calibration['false_positive'] <= .065, calibration


def test_g0_mde_nominal_80pct_power(calibration):
    assert .75 <= calibration['power'] <= .85, calibration


@pytest.mark.parametrize('block_length', [30, 60])
def test_independent_coin_resampling_underestimates_variance(block_length):
    rng = np.random.default_rng(3829)
    n, coins, rho, B = 2400, 8, .5, 2000
    x = np.sqrt(rho)*rng.normal(size=(n, 1)) + np.sqrt(1-rho)*rng.normal(size=(n, coins))
    joint = x.mean(1)[block_indices(n, block_length=block_length, B=B, seed=53)].mean(1)
    independent = sum(x[:,j][block_indices(n, block_length=block_length, B=B, seed=100+j)].mean(1) for j in range(coins))/coins
    truth = (rho+(1-rho)/coins)/n
    vjoint, vind = joint.var(ddof=1), independent.var(ddof=1)
    assert vjoint > 2*vind
    assert abs(vjoint-truth) < abs(vind-truth)
    print('BOOTSTRAP_VARIANCE', block_length, dict(joint=vjoint, per_coin=vind, truth=truth))
