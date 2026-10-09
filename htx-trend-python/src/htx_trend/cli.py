from __future__ import annotations

import argparse
import json
from pathlib import Path

from .ledger import TrendLedger
from .datasets import replay_frozen_contract
from .replay import load_snapshots
from .validation import compare_json, replay_report, save_report, to_json


def main() -> None:
    parser = argparse.ArgumentParser(description="HTX Python trend research and paper tools")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init-ledger", help="explicitly initialize a local SQLite ledger")
    init.add_argument("--database", required=True, type=Path); init.add_argument("--schema", required=True, type=Path)
    replay = commands.add_parser("replay", help="summarize immutable runtime K-line evidence")
    replay.add_argument("--evidence", required=True, type=Path); replay.add_argument("--output", required=True, type=Path)
    compare = commands.add_parser("compare", help="compare exported Java and Python decisions")
    compare.add_argument("--java", required=True, type=Path); compare.add_argument("--python", required=True, type=Path)
    dataset = commands.add_parser("dataset-replay", help="replay an existing frozen HTX public K-line dataset")
    dataset.add_argument("--data-dir", required=True, type=Path); dataset.add_argument("--contract", required=True)
    dataset.add_argument("--entry-period", default="15min"); dataset.add_argument("--trend-period", default="60min")
    args = parser.parse_args()
    if args.command == "init-ledger":
        TrendLedger.initialize(args.database, args.schema); print(json.dumps({"status": "initialized", "database": str(args.database)})); return
    if args.command == "replay":
        report = replay_report(load_snapshots(args.evidence)); save_report(args.output, report)
        print(json.dumps({"status": "written", "output": str(args.output), "snapshots": report.snapshots})); return
    if args.command == "dataset-replay":
        print(json.dumps(to_json(replay_frozen_contract(args.data_dir, args.contract, entry_period=args.entry_period, trend_period=args.trend_period)), ensure_ascii=False)); return
    java, python = json.loads(args.java.read_text()), json.loads(args.python.read_text())
    differences = compare_json(java, python)
    print(json.dumps({"schema": "htx-java-python-parity-v1", "differences": differences}, ensure_ascii=False, indent=2))
    if differences:
        raise SystemExit(1)

if __name__ == "__main__": main()
