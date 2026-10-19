from dataclasses import asdict, dataclass
from pathlib import Path
import hashlib
import json
import numpy as np
import pandas as pd
from .config import Config
from .guard import SealedDataset


def iso(t):
    return pd.to_datetime(t, unit='s', utc=True).strftime('%Y-%m-%d %H:%M')


def json_scalar(value):
    """Encode supported NumPy scalars; keep unknown types as errors."""
    if isinstance(value, np.bool_):
        return bool(value)
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.floating):
        return float(value)
    raise TypeError(f'Object of type {type(value).__name__} is not JSON serializable')


def write_json(path, obj):
    Path(path).write_text(json.dumps(obj, sort_keys=True, indent=2, allow_nan=False, default=json_scalar) + '\n')


def read_csv(path):
    df = pd.read_csv(path).sort_values('ts').reset_index(drop=True)
    if df.empty or df.ts.duplicated().any():
        raise ValueError(f'empty or duplicate timestamps: {path}')
    if not np.isfinite(df.ts).all() or not (df.ts == df.ts.astype('int64')).all():
        raise ValueError(f'invalid timestamps: {path}')
    return df


def cadence_hours(f):
    intervals = f.ts.sort_values().diff().dropna() / 3600
    if intervals.empty:
        raise ValueError('at least two funding settlements required')
    return float(intervals.mode().iloc[0])


def normalize_funding_8h(f, cap=None):
    """1h: right-closed UTC 8h sums; other cadences: rate * 8/cadence.

    Sparse 1h windows remain present, with complete=False. Cap marks propagate
    by any(); no settlements are discarded. Inference uses the full history.
    """
    f = f.sort_values('ts').copy()
    cadence = cadence_hours(f)
    rates = f.funding_rate.astype(float)
    if cap is not None and (not np.isfinite(cap) or cap <= 0):
        raise ValueError('cap must be positive')
    maximum = rates.abs().max()
    inferred = maximum if maximum > 0 and (rates.abs() == maximum).sum() >= 2 else None
    threshold = cap if cap is not None else inferred
    f['cap_hit'] = False if threshold is None else rates.abs() >= threshold
    if cadence == 1:
        f['window_end'] = ((f.ts + 28800 - 1) // 28800) * 28800
        out = f.groupby('window_end', sort=True).agg(rate_8h=('funding_rate', 'sum'), cap_hit=('cap_hit', 'any'), n=('ts', 'size')).reset_index().rename(columns={'window_end': 'ts'})
        out['complete'] = out.n == 8
    else:
        out = pd.DataFrame({'ts': f.ts, 'rate_8h': rates * 8 / cadence, 'cap_hit': f.cap_hit, 'n': 1, 'complete': True})
    out['cadence_h'] = cadence
    return out.reset_index(drop=True)


def eligibility_at_bars(df, cfg=Config()):
    """Causal eligibility only. Index is the opening time of the completed bar."""
    df = df.set_index('ts')
    full = pd.RangeIndex(int(df.index.min()), int(df.index.max()) + cfg.h4, cfg.h4)
    g = df.reindex(full)
    present = g.close.notna()
    g['adv'] = g.turnover.fillna(0).rolling(cfg.win90, min_periods=cfg.win90).sum() / cfg.adv_days
    g['gap_rate'] = 1 - present.astype(float).rolling(cfg.win90, min_periods=cfg.win90).mean()
    g['listed'] = (full - full[0] + cfg.h4) >= cfg.list_days * 86400
    g['present'] = present
    g['eligible'] = (g.adv >= cfg.turn_min) & (g.gap_rate < cfg.gap_max) & g.listed & present
    g['warm'] = present.cumsum() > cfg.warm
    return g


def funding_covered(reb, entry, exit_, first, last, gaps, cfg=Config()):
    bad = reb < first or reb > last + cfg.rebalance_grace_hours * 3600 or any(a < reb < b for a, b in gaps)
    bad |= last < exit_ - cfg.funding_gap_hours * 3600 or first > entry or any(a < exit_ and b > entry for a, b in gaps)
    return not bad


@dataclass
class Dataset:
    coins: list
    entries: np.ndarray
    R: np.ndarray
    F: np.ndarray
    EL: np.ndarray
    ELW: np.ndarray
    reasons: dict
    manifest: dict
    gaps: list
    coverage: list
    normalized: dict

    def bind_observations(self, values):
        """Bind externally prepared observations to this grid, initially unsealed.

        This does not derive positions or directional returns from raw prices.
        Missing observations must be resolved in the preregistered protocol;
        silently compressing the calendar grid is forbidden.
        """
        if np.shape(values) != self.R.shape:
            raise ValueError('observations must match the D1 period-by-coin grid')
        return SealedDataset(values, np.arange(len(self.entries)))

    def write(self, out_dir):
        out = Path(out_dir); out.mkdir(parents=True, exist_ok=True)
        from .records import write_run_record
        write_run_record(out, 'd1', {f['path']: f['sha256'] for f in self.manifest['files']}, self.manifest['config'], self.manifest['config']['seed'])
        dates = [iso(t) for t in self.entries]
        for name, values, decimals in [('period_coin_logret_bps', self.R * 1e4, 3), ('period_coin_funding_bps', self.F * 1e4, 4), ('period_coin_eligible', self.EL.astype(int), 0), ('period_coin_eligible_with_warmup', self.ELW.astype(int), 0)]:
            pd.DataFrame(values, columns=self.coins).assign(entry_open_utc=dates).set_index('entry_open_utc').round(decimals).to_csv(out / (name + '.csv'))
        pd.DataFrame({'entry_open_utc': dates, 'n_elig': self.EL.sum(1), 'n_elig_with_warmup': (self.EL & self.ELW).sum(1)}).to_csv(out / 'period_eligible_counts.csv', index=False)
        write_json(out / 'data_manifest.json', self.manifest)
        write_json(out / 'exclusion_reasons.json', self.reasons)
        pd.DataFrame(self.gaps, columns=['coin', 'kind', 'start_ts', 'end_ts', 'missing_bars']).to_csv(out / 'gaps.csv', index=False)
        pd.DataFrame(self.coverage).to_csv(out / 'coverage.csv', index=False)
        for sym, f in self.normalized.items():
            d = out / 'normalized_funding'; d.mkdir(exist_ok=True)
            f.to_csv(d / f'{sym}.csv', index=False)


def build_dataset(data_dir, cfg=Config()):
    root = Path(data_dir)
    man = json.loads((root / 'manifest.json').read_text())
    coins = sorted(man['included']); infos = {c['sym']: c for c in man['coins']}
    bars, funding, gapmap, normalized = {}, {}, {}, {}
    inventory, gaps, coverage = [], [], []
    for relative in ['manifest.json'] + (['summary.json'] if (root / 'summary.json').exists() else []) + [f'{kind}/{s}.csv' for s in coins for kind in ('klines_4h', 'funding')]:
        path = root / relative
        item = {'path': relative, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        if path.suffix == '.csv':
            df = read_csv(path)
            item.update(rows=len(df), first_ts=int(df.ts.min()), last_ts=int(df.ts.max()), first_utc=pd.to_datetime(df.ts.min(), unit='s', utc=True).isoformat(), last_utc=pd.to_datetime(df.ts.max(), unit='s', utc=True).isoformat())
        else:
            item.update(rows=None, first_ts=None, last_ts=None, first_utc=None, last_utc=None)
        inventory.append(item)
    for s in coins:
        b = read_csv(root / f'klines_4h/{s}.csv'); f = read_csv(root / f'funding/{s}.csv')
        if ((b.ts - b.ts.iloc[0]) % cfg.h4 != 0).any():
            raise ValueError('bars are not on the configured grid')
        bars[s] = eligibility_at_bars(b, cfg); funding[s] = f
        observed = [(int(a), int(z)) for a, z in zip(f.ts[:-1], f.ts[1:]) if z-a > cfg.funding_gap_hours*3600]
        gapmap[s] = sorted(set(tuple(x) for x in infos[s].get('f_gaps', [])) | set(observed))
        for a, z in zip(b.ts[:-1], b.ts[1:]):
            if z-a > cfg.h4:
                gaps.append(dict(coin=s, kind='kline', start_ts=int(a), end_ts=int(z), missing_bars=int((z-a)//cfg.h4-1)))
        for a, z in gapmap[s]:
            gaps.append(dict(coin=s, kind='funding', start_ts=a, end_ts=z, missing_bars=None))
        coverage.append(dict(coin=s, kline_rows=len(b), kline_first=int(b.ts.iloc[0]), kline_last=int(b.ts.iloc[-1]), funding_rows=len(f), funding_first=int(f.ts.iloc[0]), funding_last=int(f.ts.iloc[-1]), cadence_h=cadence_hours(f), manifest_cadence_h=infos[s].get('cadence_h')))
        normalized[s] = normalize_funding_8h(f, cfg.cap)
    t0 = min(int(g.index[0]) for g in bars.values())
    e0 = (t0 // cfg.per + 1) * cfg.per
    entries = np.arange(e0, cfg.last_open-cfg.per+1, cfg.per)
    shape = (len(entries), len(coins))
    R, F = np.full(shape, np.nan), np.full(shape, np.nan)
    EL, ELW = np.zeros(shape, bool), np.zeros(shape, bool)
    reasons = {}
    def count(s, reason):
        key = s + '|' + reason
        reasons[key] = reasons.get(key, 0) + 1
    for j, s in enumerate(coins):
        g, f = bars[s], funding[s]
        for i, e in enumerate(entries):
            reb, x = e-cfg.h4, e+cfg.per
            if reb not in g.index:
                count(s, 'outside_bar_history'); continue
            row = g.loc[reb]
            if not row.eligible:
                for name, passed in [('adv', row.adv >= cfg.turn_min), ('gap_rate', row.gap_rate < cfg.gap_max), ('listing', row.listed), ('current_bar', row.present)]:
                    if not passed: count(s, name)
                continue
            oe, ox = g.open.get(e, np.nan), g.open.get(x, np.nan)
            if not (np.isfinite(oe) and np.isfinite(ox)):
                count(s, 'missing_open'); continue
            if not funding_covered(reb, e, x, int(f.ts.iloc[0]), int(f.ts.iloc[-1]), gapmap[s], cfg):
                count(s, 'funding'); continue
            if oe <= 0 or ox <= 0: raise ValueError('nonpositive open')
            R[i, j] = np.log(ox/oe)
            F[i, j] = f.loc[(f.ts > e) & (f.ts <= x), 'funding_rate'].sum()
            EL[i, j], ELW[i, j] = True, bool(row.warm)
    return Dataset(coins, entries, R, F, EL, ELW, reasons, {'files': inventory, 'included': coins, 'config': asdict(cfg)}, gaps, coverage, normalized)
