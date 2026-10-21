"""Offline half-spread CI upper probe (Roll/CF); never writes PRD/strategy params."""
import csv
import hashlib
import io
import json
from pathlib import Path

import numpy as np


def _roll(y):
    x, z = y[..., :-1], y[..., 1:]
    cov = np.mean((x - x.mean(axis=-1, keepdims=True)) *
                  (z - z.mean(axis=-1, keepdims=True)), axis=-1)
    return np.sqrt(np.maximum(0, -cov))


def _cf(y, a):
    phi = np.mean(np.cos(a * y), axis=-1)
    phi2 = np.mean(np.cos(2 * a * y), axis=-1)
    d = 1 - phi2 / np.maximum(phi * phi, 1e-24)
    valid = (phi > 1e-6) & (phi2 >= 0) & (d >= 0) & (d <= 1)
    value = np.arctan(np.maximum(0, d) ** .25) / a
    return np.where(valid, value, _roll(y)), ~valid


def rolling_ci_upper(close, *, window=2881, step=2880, block_length=32,
                     bootstrap=2000, level=.95, seed=20261021,
                     frequency_scale=1.0):
    """Pointwise one-sided percentile block-bootstrap upper in price units.

    Y_t = P_t - P_{t-1}. CF frequency a = frequency_scale / median(|Y|)
    (window-local), keeping a·Y O(1) across assets. Also reports
    ci_upper_bp ≈ ci_upper / median(P) * 1e4 for readability.
    """
    p = np.asarray(close, dtype=float)
    if p.ndim != 1 or not np.all(np.isfinite(p)) or np.any(p <= 0):
        raise ValueError('close must be a finite positive one-dimensional series')
    for name, v in dict(window=window, step=step, block_length=block_length,
                        bootstrap=bootstrap, seed=seed).items():
        if isinstance(v, bool) or not isinstance(v, (int, np.integer)) or v < (0 if name == 'seed' else 1):
            raise ValueError(f'invalid {name}')
    if window < 5 or len(p) < window or block_length > window - 2:
        raise ValueError('insufficient window or invalid block length')
    if (not .5 < level < 1 or not np.isfinite(frequency_scale) or frequency_scale <= 0
            or bootstrap < 100):
        raise ValueError('invalid CI level, frequency_scale or bootstrap count (minimum 100)')
    rng = np.random.default_rng(seed)
    rows = []
    for end in range(window, len(p) + 1, step):
        seg = p[end - window:end]
        y = np.diff(seg)
        med_abs = float(np.median(np.abs(y)))
        a = frequency_scale / max(med_abs, 1e-12)
        med_px = float(np.median(seg))
        x, z = y[:-1], y[1:]
        n = len(x)
        roll_draws, cf_draws = [], []
        fallbacks = 0
        for start in range(0, bootstrap, 64):
            count = min(64, bootstrap - start)
            starts = rng.integers(0, n, size=(count, (n + block_length - 1) // block_length))
            idx = ((starts[..., None] + np.arange(block_length)) % n).reshape(count, -1)[:, :n]
            bx, bz = x[idx], z[idx]
            cov = np.mean((bx - bx.mean(axis=1, keepdims=True)) *
                          (bz - bz.mean(axis=1, keepdims=True)), axis=1)
            r = np.sqrt(np.maximum(0, -cov))
            cf, invalid = _cf(bx, a)
            cf = np.where(invalid, r, cf)
            fallbacks += int(invalid.sum())
            roll_draws.extend(r)
            cf_draws.extend(cf)
        ru = float(np.quantile(roll_draws, level, method='higher'))
        cu = float(np.quantile(cf_draws, level, method='higher'))
        upper = max(ru, cu)
        rows.append(dict(
            start_index=end - window, end_index=end - 1,
            ci_upper=upper,
            ci_upper_bp=upper / med_px * 1e4,
            roll_ci_upper=ru, roll_ci_upper_bp=ru / med_px * 1e4,
            cf_ci_upper=cu, cf_ci_upper_bp=cu / med_px * 1e4,
            cf_frequency=a, median_abs_diff=med_abs, median_close=med_px,
            cf_fallback_draws=fallbacks,
        ))
    return rows


def probe_csv(path, out_dir, **parameters):
    raw = Path(path).read_bytes()
    records = list(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))))
    close = [float(r['close']) for r in records]
    ts = [int(r['ts']) for r in records]
    if any(b <= a for a, b in zip(ts, ts[1:])):
        raise ValueError('ts must be strictly increasing, without duplicates')
    if len(set(np.diff(ts))) != 1:
        raise ValueError('irregular timestamps: do not bridge missing bars')
    defaults = dict(window=2881, step=2880, block_length=32, bootstrap=2000,
                    level=.95, seed=20261021, frequency_scale=1.0)
    # accept legacy --frequency as alias for frequency_scale if passed
    if 'frequency' in parameters:
        parameters = dict(parameters)
        parameters['frequency_scale'] = parameters.pop('frequency')
    defaults.update(parameters)
    rows = rolling_ci_upper(close, **defaults)
    digest = hashlib.sha256(raw).hexdigest()
    for row in rows:
        row.update(start_ts=ts[row['start_index']], end_ts=ts[row['end_index']],
                   input_sha256=digest, seed=defaults['seed'],
                   window=defaults['window'], level=defaults['level'])
    result = dict(
        input_sha256=digest, parameters=defaults,
        ci_convention='pointwise_one_sided_percentile_upper',
        units='close_price_half_spread',
        bp_note='ci_upper_bp = ci_upper / median_close * 1e4 (window-local); diagnostic only',
        aggregation='max_of_roll_and_cf_uppers',
        frequency_note='a = frequency_scale / median(|ΔP|) per window',
        diagnostic_only=True,
        point_estimate_written_to_prd=False,
        input_rows=len(records),
        trailing_rows_not_evaluated=len(records) - 1 - rows[-1]['end_index'],
        rolling_ci_upper=rows,
    )
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'rolling_ci_upper.json').write_text(
        json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + '\n')
    with (out / 'rolling_ci_upper.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return result
