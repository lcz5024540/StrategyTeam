"""只读执行成本账命令。默认不写交易库。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .cost import analyze_bundle, load_ledger_pnl_export, write_report


def main(argv=None):
    parser = argparse.ArgumentParser(description='只读执行成本账，仅用于校准回测成本')
    parser.add_argument('--bundle')
    parser.add_argument('--ledger-pnl-dir')
    parser.add_argument('--out', required=True)
    args = parser.parse_args(argv)
    if bool(args.bundle) == bool(args.ledger_pnl_dir):
        return 2
    if args.bundle:
        report = analyze_bundle(json.loads(Path(args.bundle).read_text(encoding='utf-8')))
    else:
        report = load_ledger_pnl_export(args.ledger_pnl_dir)
    write_report(report, args.out)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
