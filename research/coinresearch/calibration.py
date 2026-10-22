"""Reproducible synthetic C2–C5 evidence, never an admission decision.

The C1/C2 experiment and C3 variance comparison are shared with the original
acceptance tests. Production arithmetic remains in g0/g2/guard.
"""
import hashlib
import json
from pathlib import Path
import platform

import numpy as np

from .data import write_json
from .g0 import mde, deff_neff, m2_deff
from .g2 import run_g2, block_indices, permutation_test
from .guard import SealedDataset, BlindGuardError
from .records import write_run_record


def _validate_R(R, allow_small_R_for_tests):
    if type(R) is not int or R < 1 or (R < 5000 and not allow_small_R_for_tests):
        raise ValueError('R must be >=5000; small R requires allow_small_R_for_tests=True')


def power_calibration(seal, *, R=5000, master_seed=20261019,
                      allow_small_R_for_tests=False):
    """Original C1/C2 RNG order, including both null and MDE-shifted G2 calls."""
    _validate_R(R, allow_small_R_for_tests)
    rng = np.random.default_rng(master_seed)
    n, coins, rho = 1200, 4, .5
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
    return dict(repetitions=R, master_seed=master_seed, false_positive=positives/R, power=power/R, coverage=coverage/R, effect=effect)



def bootstrap_calibration(block_length, *, B=2000):
    """Original C3 fixed-seed experiment; independent coins are a negative control."""
    if type(B) is not int or B < 2000:
        raise ValueError('C3 B must be >=2000')
    if block_length not in (30, 60):
        raise ValueError('C3 block length must be 30 or 60')
    rng = np.random.default_rng(3829)
    n, coins, rho = 2400, 8, .5
    x = np.sqrt(rho)*rng.normal(size=(n, 1)) + np.sqrt(1-rho)*rng.normal(size=(n, coins))
    joint = x.mean(1)[block_indices(n, block_length=block_length, B=B, seed=53)].mean(1)
    independent = sum(x[:, j][block_indices(n, block_length=block_length, B=B, seed=100+j)].mean(1) for j in range(coins))/coins
    truth = (rho+(1-rho)/coins)/n
    vjoint, vind = float(joint.var(ddof=1)), float(independent.var(ddof=1))
    return dict(block_length=block_length, B=B, joint_var=vjoint, per_coin_var=vind,
                truth_var=truth, **{'pass': vjoint > 2*vind and abs(vjoint-truth) < abs(vind-truth)})


def deff_calibration():
    """Estimate ICC from random-effects observations, without supplying true ICC."""
    rng = np.random.default_rng(472)
    k, m, rho = 4000, 8, .4
    x = np.sqrt(rho)*rng.normal(size=(k, 1)) + np.sqrt(1-rho)*rng.normal(size=(k, m))
    ts = (np.arange(k)[:, None]*5*86400 + np.arange(m)*3600).ravel()
    estimate = deff_neff(x.ravel(), ts)
    truth = k*m/(1+(m-1)*rho)
    error = abs(estimate['neff']/truth-1)
    synthetic = dict(estimated_neff=estimate['neff'], true_neff=truth,
                     estimated_icc=estimate['icc'], true_rho=rho, true_m=m,
                     clusters=k, relative_error=error, tolerance=.10,
                     **{'pass': error <= .10})
    closed = m2_deff(6019, .5588, 14.35, 530)
    errors = {key: abs(closed[key]/expected-1)
              for key, expected in dict(deff=8.46, neff=712, mde=49).items()}
    closed.update(relative_errors=errors, tolerance=.01,
                  **{'pass': all(e <= .01 for e in errors.values())})
    return dict(synthetic=synthetic, m2=closed,
                **{'pass': synthetic['pass'] and closed['pass']})


def _rejects(exception, call):
    try:
        call()
    except exception:
        return True
    return False


def permutation_calibration(seal, *, B=5000, seed=20261019):
    """Exercise actual permutation output and actual seal/B rejection paths."""
    if type(B) is not int or B < 5000:
        raise ValueError('C5 B must be >=5000')
    sides = np.tile([-1, 1], 40)
    holding = np.repeat([1, 2], 40)
    costs = np.tile(np.repeat([12, 24], 20), 2)
    values = (sides*10.)[:, None]
    d = SealedDataset(values, np.arange(80)).seal(**seal)
    args = dict(**seal, sides=sides, holding_lengths=holding, costs=costs, seed=seed)
    result = permutation_test(d, **args, B=B)
    small_rejected = _rejects(ValueError, lambda: permutation_test(d, **args, B=4999))
    unsealed = SealedDataset(values, np.arange(80))
    unsealed_rejected = _rejects(BlindGuardError, lambda: permutation_test(unsealed, **args, B=B))
    missing = dict(seal, prereg_sha256=None)
    missing_rejected = _rejects(BlindGuardError, lambda: unsealed.directional_observations(**missing))
    wrong = dict(args, prereg_sha256='0'*64)
    wrong_rejected = _rejects(BlindGuardError, lambda: permutation_test(d, **wrong, B=B))
    no_admission_fields = not any('unlock' in key.lower() or 'admission' in key.lower() for key in result)
    expected = [dict(holding_length=h, cost=c, count=20, long_count=10)
                for h in (1, 2) for c in (12, 24)]
    structure = result['N'] == 80 and result['long_count'] == 40 and result['strata'] == expected
    guards = dict(small_B_rejected=small_rejected, unsealed_rejected=unsealed_rejected,
                  missing_sha_rejected=missing_rejected, wrong_sha_rejected=wrong_rejected)
    passed = (result['diagnostic_only'] is True and result['alternative'] == 'greater'
              and result['gross_edge_bps'] == 10 and result['pvalue'] <= .01
              and no_admission_fields and structure and all(guards.values()))
    return dict(**result, guards=guards, no_admission_fields=no_admission_fields,
                structure_preserved=structure, **{'pass': passed})


def canonical_sha256(value):
    """SHA of UTF-8 JSON, sorted keys, compact separators, no NaN or newline."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     allow_nan=False).encode()).hexdigest()


def calibrate_c2c5(out_dir, *, tip_sha='UNSPECIFIED', master_seed=20261019,
                   R=5000, c3_B=2000, c5_B=5000, allow_small_R_for_tests=False):
    """Write sealed synthetic protocol, evidence and stable provenance.

    The small-R escape hatch is Python-test-only and cannot yield all_pass.
    Named legacy seeds are fixed to preserve the original C3/C4 experiments.
    """
    _validate_R(R, allow_small_R_for_tests)
    if type(c3_B) is not int or c3_B < 2000 or type(c5_B) is not int or c5_B < 5000:
        raise ValueError('C3 B >=2000 and C5 B >=5000 required')
    parameters = dict(
        master_seed=master_seed, R=R, c3_B=c3_B, c5_B=c5_B,
        allow_small_R_for_tests=allow_small_R_for_tests,
        c2=dict(n=1200, coins=4, rho=.5, volatility_squared=[.25, 1.75],
                volatility_block=10, z=2.486, neff=1200, neff_threshold=4400,
                hard_floor=1000, nominal_floor=1000, block_length=30, B=500,
                ci_level=.90, circular=True, bootstrap_seed_base=10000,
                allow_small_B_for_tests=True, power_window=[.75, .85]),
        c3=dict(n=2400, coins=8, rho=.5, block_lengths=[30, 60],
                data_seed=3829, joint_seed=53, per_coin_seed_base=100, circular=True),
        c4=dict(seed=472, clusters=4000, m=8, rho=.4, block_days=5,
                event_spacing_seconds=3600, tolerance=.10,
                m2=dict(N_nom=6019, rho=.5588, m=14.35, sd=530, Z=2.486, tolerance=.01)),
        c5=dict(seed=master_seed, N=80, holding_lengths=[1, 2], costs=[12, 24],
                per_stratum_count=20, per_stratum_long_count=10, signed_gross=10,
                alternative='greater'))
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out/'prereg.json'
    write_json(path, dict(protocol='D2-C2C5-synthetic-v1',
                         split={'test': 'all synthetic replicates; no selection or tuning'},
                         parameters=parameters, purpose='synthetic calibration only'))
    sha = hashlib.sha256(path.read_bytes()).hexdigest()
    seal = dict(prereg_path=path, prereg_sha256=sha)
    power = power_calibration(seal, R=R, master_seed=master_seed,
                              allow_small_R_for_tests=allow_small_R_for_tests)
    c2 = dict(**power, R=R, power_window=[.75, .85],
              effect_setting='true effect = prespecified MDE = 2.486*sqrt(0.625/1200)',
              **{'pass': R >= 5000 and .75 <= power['power'] <= .85})
    cases = [bootstrap_calibration(length, B=c3_B) for length in (30, 60)]
    c3 = dict(B=c3_B, block_lengths=[30, 60], cases=cases,
              **{'pass': all(case['pass'] for case in cases)})
    c4 = deff_calibration()
    c5 = permutation_calibration(seal, B=c5_B, seed=master_seed)
    result = dict(schema_version=1, tip_sha=tip_sha, master_seed=master_seed,
                  purpose='synthetic calibration only; no admission or live integration',
                  parameters=parameters, parameters_sha256=canonical_sha256(parameters),
                  input_sha256={'prereg.json': sha}, prereg_sha256=sha,
                  environment=dict(python=platform.python_version(), numpy=np.__version__),
                  C2=c2, C3=c3, C4=c4, C5=c5,
                  all_pass=all(c['pass'] for c in (c2, c3, c4, c5)))
    write_json(out/'c2c5_calibration.json', result)
    write_run_record(out, 'calibrate-c2c5', result['input_sha256'],
                     dict(parameters, tip_sha=tip_sha), master_seed)
    return result
