"""python -m coinresearch.cost_ceiling_cli --input close.csv --out-dir output"""
import argparse
from .cost_ceiling import probe_csv


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input', required=True)
    p.add_argument('--out-dir', required=True)
    for name, default in [('window', 2881), ('step', 2880), ('block-length', 32),
                          ('bootstrap', 2000), ('seed', 20261021)]:
        p.add_argument('--' + name, type=int, default=default)
    p.add_argument('--level', type=float, default=.95)
    p.add_argument('--frequency-scale', type=float, default=1.0,
                   help='CF a = frequency_scale / median(|ΔP|) per window')
    args = vars(p.parse_args(argv))
    path, out = args.pop('input'), args.pop('out_dir')
    try:
        probe_csv(path, out, **args)
    except (ValueError, KeyError, OSError) as exc:
        p.error(str(exc))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
