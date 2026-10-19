"""Offline commands. All paths are explicit; no networking or runtime imports."""
import argparse
from dataclasses import asdict
import hashlib
import json
import os
from pathlib import Path
import tempfile
import numpy as np
import pandas as pd
from .config import Config
from .data import build_dataset, write_json
from .g0 import run_g0
from .g2 import run_g2
from .guard import SealedDataset
from .records import file_hashes, write_run_record


def hashes(directory):
    return {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(Path(directory).rglob('*')) if p.is_file()}


def regress(data_dir, reference_dir, out_dir, *, target_bps, cfg=Config()):
    out = Path(out_dir); out.mkdir(parents=True, exist_ok=True)
    targets = {'time_test': (286, 452.572, 66.528), 'coin_B_ge10': (309, 363.585, 51.419), 'full_ge10': (857, 390.475, 33.159)}
    missing = [str(p) for p in (Path(data_dir), Path(reference_dir)) if not p.is_dir()]
    if missing:
        report = dict(status='DATA_UNAVAILABLE', missing=missing, passed=False,
                      deterministic=None, comparisons=None, targets=targets)
        write_json(out / 'regression.json', report)
        md = '| case | target N | target sigma_a_bps | target mde_a | reproduced |\n|---|---:|---:|---:|---|\n'
        for case, (n, sigma, mde_) in targets.items():
            md += f'| {case} | {n} | {sigma} | {mde_} | DATA_UNAVAILABLE |\n'
        md += '\nMissing input directories: ' + ', '.join(missing) + '\n\nD1 comparisons and double-build verification: NOT RUN.\n'
        (out / 'regression.md').write_text(md)
        return report
    dataset = build_dataset(data_dir, cfg)
    dataset.write(out / 'd1')
    with tempfile.TemporaryDirectory(prefix='coinresearch-') as tmp:
        build_dataset(data_dir, cfg).write(tmp)
        deterministic = hashes(out / 'd1') == hashes(tmp)
    refdir = Path(reference_dir)
    comparisons = {}
    for name in ('period_eligible_counts.csv', 'period_coin_logret_bps.csv', 'period_coin_funding_bps.csv'):
        generated, baseline = out / 'd1' / name, refdir / name
        comparisons[name] = {'byte_identical': generated.read_bytes() == baseline.read_bytes(), 'row_equal': pd.read_csv(generated).equals(pd.read_csv(baseline))}
    result = run_g0(dataset, target_bps=target_bps, cfg=cfg)
    write_json(out / 'g0_results.json', result)
    write_run_record(out, 'g0', {f['path']: f['sha256'] for f in dataset.manifest['files']}, dict(config=asdict(cfg), target_bps=target_bps), cfg.seed)
    pd.DataFrame([dict(case=k, **v) for k,v in result.items() if k != 'split']).to_csv(out / 'g0_summary.csv', index=False)
    reference = json.loads((refdir / 'gate0_results.json').read_text())
    table = []
    for case, target in targets.items():
        for metric, expected in zip(('N', 'sigma_a_bps', 'mde_a'), target):
            actual = result[case].get(metric)
            if actual is not None:
                actual = int(actual) if metric == 'N' else float(actual)
            precise = reference[case][metric]
            error = None if actual is None else abs(actual/precise-1)*100
            target_error = None if actual is None else abs(actual/expected-1)*100
            passed = actual == precise == expected if metric == 'N' else error is not None and error <= 1 and target_error <= 1
            table.append(dict(case=case, metric=metric, reference=precise, target=expected, reproduced=actual, error_pct=error, target_error_pct=target_error, passed=bool(passed)))
    report = dict(comparisons=comparisons, deterministic=deterministic, table=table, passed=deterministic and all(v['row_equal'] for v in comparisons.values()) and all(r['passed'] for r in table), config=asdict(cfg))
    write_json(out / 'regression.json', report)
    md = '| case | metric | target | reference | reproduced | error % | target error % |\n|---|---|---:|---:|---:|---:|---:|\n'
    for r in table:
        md += f"| {r['case']} | {r['metric']} | {r['target']} | {r['reference']} | {r['reproduced']} | {r['error_pct']} | {r['target_error_pct']} |\n"
    md += f"\nD1 comparisons: `{json.dumps(comparisons, sort_keys=True)}`\n\nTwo independent builds byte-identical: {deterministic}\n"
    (out / 'regression.md').write_text(md)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    for cmd in ('d1', 'g0', 'regress'):
        sub = subs.add_parser(cmd)
        sub.add_argument('--data-dir', default=os.environ.get('RESEARCH_RING2_DIR'))
        sub.add_argument('--out-dir', required=True)
        sub.add_argument('--config', help='JSON object overriding Config fields')
        if cmd != 'd1': sub.add_argument('--target-bps', type=float, required=True)
        if cmd == 'regress': sub.add_argument('--reference-dir', default=os.environ.get('RESEARCH_BGATE0_DIR'))
    sub = subs.add_parser('g2')
    sub.add_argument('--observations', required=True, help='CSV: period_index followed by fixed coin observation columns')
    sub.add_argument('--prereg-path', required=True)
    sub.add_argument('--prereg-sha256', required=True)
    sub.add_argument('--seal', action='store_true', required=True, help='explicitly seal supplied observations against the prereg split')
    sub.add_argument('--neff', type=float, required=True)
    sub.add_argument('--neff-threshold', type=float, default=Config().neff_threshold)
    sub.add_argument('--hard-floor', type=float, default=Config().hard_floor)
    sub.add_argument('--nominal-floor', type=int, default=Config().nominal_floor)
    sub.add_argument('--block-length', type=int, default=30)
    sub.add_argument('--B', type=int, default=2000)
    sub.add_argument('--ci-level', type=float, default=.90)
    sub.add_argument('--seed', type=int, default=20261019)
    sub.add_argument('--moving', action='store_true')
    sub.add_argument('--out-dir', required=True)
    args = parser.parse_args(argv)
    try:
        out = Path(args.out_dir)
        if args.command == 'g2':
            # Verify before reading observations or constructing outputs.
            from .guard import verify_prereg
            verify_prereg(args.prereg_path, args.prereg_sha256)
            f = pd.read_csv(args.observations)
            dataset = SealedDataset(f.drop(columns='period_index').to_numpy(), f.period_index).seal(prereg_path=args.prereg_path, prereg_sha256=args.prereg_sha256)
            result = run_g2(dataset, prereg_path=args.prereg_path, prereg_sha256=args.prereg_sha256, neff=args.neff, neff_threshold=args.neff_threshold, hard_floor=args.hard_floor, nominal_floor=args.nominal_floor, block_length=args.block_length, B=args.B, ci_level=args.ci_level, seed=args.seed, circular=not args.moving)
            out.mkdir(parents=True, exist_ok=True)
            write_json(out / 'g2_results.json', result)
            parameters = {k:v for k,v in vars(args).items() if k not in ('out_dir', 'command')}
            parameters['allow_small_B_for_tests'] = False
            parameters['circular'] = not args.moving
            write_run_record(out, 'g2', file_hashes([args.observations, args.prereg_path]), parameters, args.seed)
        else:
            if not args.data_dir: raise ValueError('--data-dir or RESEARCH_RING2_DIR required')
            cfg = Config(**(json.loads(Path(args.config).read_text()) if args.config else {}))
            if args.command == 'regress':
                if not args.reference_dir: raise ValueError('--reference-dir or RESEARCH_BGATE0_DIR required')
                result = regress(args.data_dir, args.reference_dir, out, target_bps=args.target_bps, cfg=cfg)
                print((out / 'regression.md').read_text())
                return 0 if result['passed'] else 1
            dataset = build_dataset(args.data_dir, cfg)
            dataset.write(out)
            inputs = {f['path']: f['sha256'] for f in dataset.manifest['files']}
            if args.config:
                inputs.update(file_hashes([args.config]))
            write_run_record(out, 'd1', inputs, asdict(cfg), cfg.seed)
            if args.command == 'g0':
                result = run_g0(dataset, target_bps=args.target_bps, cfg=cfg)
                write_json(out / 'g0_results.json', result)
                write_run_record(out, 'g0', inputs, dict(config=asdict(cfg), target_bps=args.target_bps), cfg.seed)
                pd.DataFrame([dict(case=k, **v) for k,v in result.items() if k != 'split']).to_csv(out / 'g0_summary.csv', index=False)
    except (ValueError, OSError, KeyError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
