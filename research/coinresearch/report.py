"""Descriptive reports of completed offline ledgers; never an admission gate."""
import csv
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
import json
import math
from pathlib import Path
import numpy as np
from .data import write_json
from .ledger import decimal
from .records import file_hashes, write_run_record

CI_METHOD = 'IID trade bootstrap percentile CI of mean; descriptive only, ignores serial/cross-coin dependence'


def _summary(values):
    total = sum(values, Decimal(0))
    return dict(count=len(values), net_bps_sum=str(total),
                net_bps_mean=str(total / len(values)) if values else None)


def build_report(rows, *, top_n=1, B=2000, ci_level=.90, seed=20261022):
    """Group by coin and UTC exit year. Decimal aggregates serialize as strings.

    Top-N ranks signed net bps (not absolute magnitude). Ratio is undefined for
    zero total and can be negative or >1 when losses offset winners. Best-year
    ties select the earliest year. Empty/singleton remainders have no CI.
    """
    if any(not isinstance(v, int) or isinstance(v, bool) or v < 1 for v in (top_n, B)):
        raise ValueError('top_n and B must be positive integers')
    if not 0 < ci_level < 1:
        raise ValueError('ci_level must be between 0 and 1')
    coins, years = defaultdict(list), defaultdict(list)
    observations = []
    for row in rows:
        if not isinstance(row['coin'], str) or not row['coin']:
            raise ValueError('coin required')
        entry, exit_ = decimal(row['entry_ts']), decimal(row['exit_ts'])
        if entry != entry.to_integral_value() or exit_ != exit_.to_integral_value() or exit_ <= entry:
            raise ValueError('completed ledger requires integer entry_ts < exit_ts')
        year = str(datetime.fromtimestamp(int(exit_), timezone.utc).year)
        value = decimal(row['net_bps'])
        if not math.isfinite(float(value)):
            raise ValueError('net_bps exceeds report numeric range')
        coins[row['coin']].append(value)
        years[year].append(value)
        observations.append(value)
    best = min(years, key=lambda y: (-sum(years[y]), y)) if years else None
    # Stable sort makes report independent of input row order, including RNG mapping.
    remaining = sorted(v for y, values in years.items() if y != best for v in values)
    ci = None
    status = 'INSUFFICIENT_OBSERVATIONS'
    if len(remaining) >= 2:
        values = np.asarray(remaining, dtype=float)
        rng = np.random.default_rng(seed)
        boot = [float(rng.choice(values, size=len(values), replace=True).mean()) for _ in range(B)]
        alpha = (1-ci_level)/2
        ci = [float(v) for v in np.quantile(boot, [alpha, 1-alpha])]
        status = 'DESCRIPTIVE_ONLY'
    total = sum(observations, Decimal(0))
    top = sum(sorted(observations, reverse=True)[:top_n], Decimal(0))
    return dict(scope='OFFLINE_DESCRIPTIVE_ONLY', year_basis='UTC exit_ts',
                overall=_summary(observations),
                by_coin={k: _summary(v) for k, v in sorted(coins.items())},
                by_year={k: _summary(v) for k, v in sorted(years.items())},
                top_trades=dict(requested_n=top_n, count=min(top_n, len(observations)),
                                net_bps_sum=str(top), contribution_ratio=str(top/total) if total else None,
                                denominator='signed total net_bps; null when zero'),
                without_best_year=dict(removed_year=best, **_summary(remaining),
                                       mean_ci_bps=ci, status=status, ci_method=CI_METHOD,
                                       ci_level=ci_level, B=B, seed=seed))


def write_report(input_path, out_dir, *, top_n=1, B=2000, ci_level=.90, seed=20261022):
    """One-shot CSV or JSON array/{ledger: [...]} -> report.json/md + provenance."""
    source = Path(input_path)
    if source.suffix.lower() == '.csv':
        with source.open(newline='') as f:
            rows = list(csv.DictReader(f))
    elif source.suffix.lower() == '.json':
        rows = json.loads(source.read_text(), parse_float=Decimal)
        if isinstance(rows, dict):
            rows = rows['ledger']
    else:
        raise ValueError('input must be CSV or JSON')
    if not isinstance(rows, list) or any(not isinstance(r, dict) for r in rows):
        raise ValueError('ledger must be a list of rows')
    params = dict(top_n=top_n, B=B, ci_level=ci_level, seed=seed)
    report = build_report(rows, **params)
    out = Path(out_dir)
    if source.resolve() in {(out / name).resolve() for name in ('report.json', 'report.md', 'run_record.json')}:
        raise ValueError('output must not overwrite input')
    inputs = file_hashes([source])
    out.mkdir(parents=True, exist_ok=True)
    write_json(out / 'report.json', report)
    # Include the complete schema in Markdown for review, without float rounding.
    md = '# Offline ledger report\n\nDescriptive research only. Year = UTC exit year.\n\n'
    for key, label in [('by_coin', 'By coin'), ('by_year', 'By year')]:
        md += f'## {label}\n\n| Group | Count | Net bps sum | Net bps mean |\n|---|---:|---:|---:|\n'
        for group, item in report[key].items():
            label_ = group.replace('|', '\\|').replace('\n', ' ')
            md += f"| {label_} | {item['count']} | {item['net_bps_sum']} | {item['net_bps_mean']} |\n"
        md += '\n'
    for key in ('top_trades', 'without_best_year'):
        md += f'## {key}\n\n```json\n{json.dumps(report[key], sort_keys=True, indent=2)}\n```\n\n'
    (out / 'report.md').write_text(md)
    write_run_record(out, 'report', inputs, params, seed)
    return report
