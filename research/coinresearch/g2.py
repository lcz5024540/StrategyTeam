"""Joint calendar-block resampling of externally prepared, sealed observations."""
import numpy as np
from .config import Config
from .guard import SealedDataset, BlindGuardError


def block_indices(n, *, block_length, B, seed, circular=True):
    if n < 2 or block_length < 1 or block_length > n or B < 1:
        raise ValueError('invalid bootstrap dimensions')
    rng = np.random.default_rng(seed)
    starts = rng.integers(n if circular else n-block_length+1, size=(B, (n+block_length-1)//block_length))
    indices = (starts[..., None] + np.arange(block_length)).reshape(B, -1)[:, :n]
    return indices % n if circular else indices


def run_g2(dataset, *, prereg_path, prereg_sha256, neff, neff_threshold=Config().neff_threshold,
           hard_floor=Config().hard_floor, nominal_floor=Config().nominal_floor, block_length=30, B=2000, ci_level=.90, seed=20261019,
           circular=True, allow_small_B_for_tests=False):
    if not isinstance(dataset, SealedDataset):
        raise BlindGuardError('G2 accepts only a sealed dataset')
    dataset.require_sealed(prereg_path=prereg_path, prereg_sha256=prereg_sha256)
    if B < 1 or (B < 2000 and not allow_small_B_for_tests):
        raise ValueError('B must be >=2000')
    if not 0 < ci_level < 1 or not all(np.isfinite(v) for v in (neff, neff_threshold, hard_floor)) or not 0 < hard_floor <= neff_threshold or neff <= 0:
        raise ValueError('invalid CI or effective sample thresholds')
    if not isinstance(nominal_floor, int) or nominal_floor < 1:
        raise ValueError('nominal_floor must be a positive integer')
    metadata = dict(N=dataset.nominal_n, neff=float(neff), neff_threshold=float(neff_threshold), hard_floor=float(hard_floor), nominal_floor=nominal_floor)
    if neff < hard_floor or dataset.nominal_n < nominal_floor:
        return dict(**metadata, status='REFUSED_BELOW_FLOOR', statistical_decision=None, ci=None, mean=None, unlock_g3_request=False)
    values = dataset.directional_observations(prereg_path=prereg_path, prereg_sha256=prereg_sha256)
    # Equal coin weights are fixed before resampling. Linearity makes this
    # identical to applying the same indices to the entire coin matrix.
    period_mean = values.mean(axis=1)
    indices = block_indices(len(values), block_length=block_length, B=B, seed=seed, circular=circular)
    boot = period_mean[indices].mean(axis=1)
    alpha = (1-ci_level)/2
    lo, hi = np.quantile(boot, [alpha, 1-alpha])
    status = ('PASS' if neff >= neff_threshold else 'SMALL_SAMPLE_PASS') if lo > 0 else 'FAIL/NO_EDGE'
    return dict(N=dataset.nominal_n, nominal_floor=nominal_floor, statistical_decision='H1' if lo > 0 else 'NO_EDGE', status=status, mean=float(period_mean.mean()), ci=[float(lo), float(hi)], ci_level=ci_level, neff=float(neff), neff_threshold=float(neff_threshold), hard_floor=float(hard_floor), B=B, block_length=block_length, seed=seed, circular=circular, prereg_sha256=prereg_sha256, unlock_g3_request=status == 'PASS')


def permutation_test(dataset, *, sides, holding_lengths, costs,
                     prereg_path, prereg_sha256, B=5000, seed=20261019):
    """One-sided gross-edge diagnostic only, under within-stratum exchangeability.

    One row per trade, one column of unsigned price return bps. Permute side
    labels within (holding length, round-trip cost) strata. This retains every
    trade, exact long/short counts per stratum, durations and cost assignments.
    It is not a calendar-dependence-robust primary gate.
    """
    if not isinstance(dataset, SealedDataset):
        raise BlindGuardError('permutation accepts only sealed observations')
    values = dataset.directional_observations(prereg_path=prereg_path, prereg_sha256=prereg_sha256)
    sides, holding, costs = np.asarray(sides), np.asarray(holding_lengths), np.asarray(costs)
    n = len(values)
    if values.shape != (n, 1) or any(a.shape != (n,) for a in (sides, holding, costs)):
        raise ValueError('one price return and metadata per trade required')
    if B < 5000 or not np.isin(sides, [-1, 1]).all() or not np.isfinite(holding).all() or not np.isfinite(costs).all() or (holding <= 0).any() or (costs < 0).any():
        raise ValueError('invalid permutation parameters; B >=5000 required')
    strata = {}
    for i, key in enumerate(zip(holding.tolist(), costs.tolist())):
        strata.setdefault(key, []).append(i)
    rng = np.random.default_rng(seed)
    null = np.zeros(B)
    for indices in strata.values():
        idx = np.asarray(indices)
        for b in range(B):
            null[b] += np.dot(values[idx, 0], rng.permutation(sides[idx])) / n
    observed = float(np.mean(values[:, 0]*sides))
    return dict(diagnostic_only=True, metric='gross_edge_bps', alternative='greater',
                gross_edge_bps=observed, pvalue=float((1+np.sum(null >= observed))/(B+1)),
                B=B, seed=seed, N=n, long_count=int(np.sum(sides == 1)),
                strata=[dict(holding_length=k[0], cost=k[1], count=len(v),
                             long_count=int(np.sum(sides[v] == 1))) for k,v in strata.items()])
