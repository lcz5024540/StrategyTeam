from __future__ import annotations

import argparse
from datetime import UTC, datetime
import json
from pathlib import Path

from .arrow_runtime import configure_arrow
from .ledger import TrendLedger
from .datasets import replay_frozen_contract
from .replay import load_snapshots
from .validation import compare_json, replay_report, save_report, to_json
from .strategy_admission import StrategyAdmissionArtifact
from .strategy_decision import LEGACY_STRATEGY_VERSION
from .htx_v5 import HtxV5Client
from .research.collector import ContinuousCollector, PERIODS
from .research.store import FileEvidenceStore
from .research.replay import replay_entries


def _epoch(value: str) -> int:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise argparse.ArgumentTypeError("expected ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("timestamp must include a timezone")
    return int(parsed.astimezone(UTC).timestamp())


def main() -> None:
    configure_arrow()
    parser = argparse.ArgumentParser(description="HTX Python trend research and paper tools")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init-ledger", help="explicitly initialize a local SQLite ledger")
    init.add_argument("--database", required=True, type=Path); init.add_argument("--schema", required=True, type=Path)
    replay = commands.add_parser("replay", help="summarize immutable runtime K-line evidence")
    replay.add_argument("--evidence", required=True, type=Path); replay.add_argument("--output", required=True, type=Path)
    replay.add_argument("--strategy-version", default=LEGACY_STRATEGY_VERSION)
    replay.add_argument("--admission", type=Path)
    compare = commands.add_parser("compare", help="compare exported Java and Python decisions")
    compare.add_argument("--java", required=True, type=Path); compare.add_argument("--python", required=True, type=Path)
    dataset = commands.add_parser("dataset-replay", help="replay an existing frozen HTX public K-line dataset")
    dataset.add_argument("--data-dir", required=True, type=Path); dataset.add_argument("--contract", required=True)
    dataset.add_argument("--manifest", required=True, type=Path)
    dataset.add_argument("--entry-period", default="15min"); dataset.add_argument("--trend-period", default="60min")
    dataset.add_argument("--strategy-version", default=LEGACY_STRATEGY_VERSION)
    dataset.add_argument("--admission", type=Path)
    admission = commands.add_parser("validate-admission", help="validate a sealed strategy admission artifact")
    admission.add_argument("--artifact", required=True, type=Path)
    history = commands.add_parser("collect-history", help="freeze immutable public HTX K-lines")
    history.add_argument("--evidence", required=True, type=Path)
    history.add_argument("--symbols", required=True, nargs="+")
    history.add_argument("--periods", nargs="+", default=list(PERIODS), choices=PERIODS)
    history.add_argument("--start", required=True, type=_epoch); history.add_argument("--end", required=True, type=_epoch)
    tick = commands.add_parser("collect-tick", help="run one resumable continuous collection tick")
    tick.add_argument("--evidence", required=True, type=Path)
    tick.add_argument("--symbols", required=True, nargs="+")
    tick.add_argument("--periods", nargs="+", default=list(PERIODS), choices=PERIODS)
    verify = commands.add_parser("verify-evidence", help="verify an evidence reference closure")
    verify.add_argument("--evidence", required=True, type=Path); verify.add_argument("--hash", required=True)
    entry_replay = commands.add_parser("evidence-entry-replay", help="replay a frozen CAS dataset through the shared entry core")
    entry_replay.add_argument("--evidence", required=True, type=Path); entry_replay.add_argument("--manifest-hash", required=True)
    entry_replay.add_argument("--symbol", required=True); entry_replay.add_argument("--period", default="15min", choices=PERIODS)
    entry_replay.add_argument("--window", type=int, default=160)
    health = commands.add_parser("health", help="Check research component health")
    health.add_argument("--workspace", type=Path, default=Path("."))
    health.add_argument("--max-age", type=int, default=300)
    health.add_argument("--threshold-minutes", type=int, default=10)
    health.add_argument("--collector-state", help="Collector state JSON path")
    status = commands.add_parser("status", help="Get research system status")
    status.add_argument("--json", action="store_true")
    status.add_argument("--status-file", help="Path to status.json written by a running process")
    status.add_argument("--workspace", type=Path, default=Path("."))
    args = parser.parse_args()
    if args.command == "health":
        from .research.cli import cmd_health
        cmd_health(args)
        return
    if args.command == "status":
        from .research.cli import cmd_status
        cmd_status(args)
        return
    if args.command == "init-ledger":
        TrendLedger.initialize(args.database, args.schema); print(json.dumps({"status": "initialized", "database": str(args.database)})); return
    if args.command == "replay":
        admitted = StrategyAdmissionArtifact.load(args.admission) if args.admission else None
        report = replay_report(load_snapshots(args.evidence), strategy_version=args.strategy_version, admission=admitted)
        save_report(args.output, report)
        print(json.dumps({"status": "written", "output": str(args.output), "snapshots": report.snapshots})); return
    if args.command == "validate-admission":
        artifact = StrategyAdmissionArtifact.load(args.artifact)
        print(json.dumps({"status": "valid", "strategy_version": artifact.strategy_version,
                          "artifact_hash": artifact.artifact_hash,
                          "allowed_surfaces": [item.value for item in artifact.allowed_surfaces]})); return
    if args.command == "verify-evidence":
        with FileEvidenceStore(args.evidence) as store:
            print(json.dumps(store.verify(args.hash), ensure_ascii=False)); return
    if args.command == "evidence-entry-replay":
        with FileEvidenceStore(args.evidence) as store:
            result = replay_entries(store, args.manifest_hash, symbol=args.symbol,
                                    period=args.period, window=args.window)
            payload = store.get(result)["payload"]
            print(json.dumps({"status": "COMPLETE", "result_hash": result,
                              "decisions": len(payload["decisions"])}, ensure_ascii=False)); return
    if args.command in {"collect-history", "collect-tick"}:
        with FileEvidenceStore(args.evidence) as store, HtxV5Client() as client:
            collector = ContinuousCollector(client, store)
            if args.command == "collect-history":
                manifest = collector.collect(tuple(args.symbols), start=args.start, end=args.end,
                                             periods=tuple(args.periods))
                print(json.dumps({"status": store.get(manifest)["payload"]["integrity"],
                                  "manifest_hash": manifest}, ensure_ascii=False))
            else:
                print(json.dumps(collector.tick(tuple(args.symbols), periods=tuple(args.periods)),
                                 ensure_ascii=False))
            return
    if args.command == "dataset-replay":
        admitted = StrategyAdmissionArtifact.load(args.admission) if args.admission else None
        print(json.dumps(to_json(replay_frozen_contract(args.data_dir, args.contract, manifest_path=args.manifest,
            entry_period=args.entry_period, trend_period=args.trend_period, strategy_version=args.strategy_version,
            admission=admitted)), ensure_ascii=False)); return
    java, python = json.loads(args.java.read_text()), json.loads(args.python.read_text())
    differences = compare_json(java, python)
    print(json.dumps({"schema": "htx-java-python-parity-v1", "differences": differences}, ensure_ascii=False, indent=2))
    if differences:
        raise SystemExit(1)

if __name__ == "__main__": main()
