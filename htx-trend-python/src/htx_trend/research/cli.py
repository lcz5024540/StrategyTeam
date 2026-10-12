"""CLI 命令行接口"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

from ..arrow_runtime import configure_arrow
from .data import DataCollector, DatasetManager, DataStitcher
from .data.dataset import load_split_from_manifest
from .data.collector import (
    BINANCE_VISION_START,
    hash_funding_files,
    hash_spec_files,
    parse_lookback_days,
    funding_source_timeline,
    summarize_funding_sources,
)
from .data.quality import (
    DEFAULT_STITCH_POINT,
    KNOWN_CANDIDATES,
    STITCH_WINDOW_BARS,
    apply_bad_ticks,
    bad_ticks_hash,
    load_bad_ticks,
    load_stitch_points,
    stitch_point_for,
    write_quality_audit,
)
from .features.daily_resample import daily_resample_manifest_fields
from .ledger import Approval, ApprovalDecision, ApprovalGate, LedgerManager
from .ops import HeartbeatManager, get_status
from .ops.heartbeat import check_health as check_system_health
from .ops.runtime_paths import (
    collector_state_file,
    heartbeat_file,
    heartbeats_dir,
    status_file,
)
from .ops.status import StatusInterface, set_status_file_path
from .runner import BacktestRunner
from .version_info import get_version_info


def packaged_bad_ticks_path() -> Path:
    return Path(__file__).resolve().parents[3] / "data" / "quality" / "bad_ticks.yaml"


def resolve_bad_ticks_file(workspace: Path, bad_ticks_path: str | None) -> Path:
    if bad_ticks_path:
        ticks_file = Path(bad_ticks_path)
        if not ticks_file.is_file():
            raise SystemExit(f"bad_ticks file not found: {ticks_file}")
        return ticks_file
    candidates = [
        Path(workspace) / "data" / "quality" / "bad_ticks.yaml",
        packaged_bad_ticks_path(),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise SystemExit(
        "bad_ticks.yaml not found. Pass --bad-ticks PATH or include "
        "data/quality/bad_ticks.yaml in the package."
    )


def resolve_freeze_split_args(args: argparse.Namespace) -> tuple[str, str | None, str | None]:
    """Return (cutoff, validation_start, test_start)."""
    split_from = getattr(args, "split_from_manifest", None)
    cutoff = getattr(args, "cutoff", None)
    validation_start = getattr(args, "validation_start", None)
    test_start = getattr(args, "test_start", None)
    if split_from:
        if cutoff or validation_start or test_start:
            raise SystemExit(
                "--split-from-manifest cannot be combined with "
                "--cutoff/--validation-start/--test-start"
            )
        val_iso, test_iso, cutoff_iso = load_split_from_manifest(split_from)
        return cutoff_iso, val_iso, test_iso
    if (validation_start is None) ^ (test_start is None):
        raise SystemExit(
            "--validation-start and --test-start must be passed together"
        )
    if not cutoff:
        raise SystemExit("--cutoff is required (or use --split-from-manifest)")
    return cutoff, validation_start, test_start


def cmd_collect(args: argparse.Namespace) -> None:
    workspace = Path(args.workspace)
    collector = DataCollector(workspace / "data" / "raw")
    
    contracts = args.contracts.split(",")
    
    for contract in contracts:
        print(f"Collecting {contract}...")
        
        htx_4h = collector.collect_htx_klines(contract, "4hour", args.lookback_days)
        htx_1d = collector.collect_htx_klines(contract, "1day", args.lookback_days)
        
        stitch = stitch_point_for(contract) if args.backfill_binance else None
        collector.collect_funding_history(contract, stitch_point=stitch)
        collector.collect_contract_spec(contract)
        
        if args.backfill_binance:
            end_date = datetime.now(UTC)
            if args.lookback_days == "max":
                start_date = BINANCE_VISION_START
            else:
                start_date = end_date - timedelta(days=int(args.lookback_days))
            
            binance_symbol = contract.replace("-", "")
            
            collector.collect_binance_klines(
                binance_symbol, "4h", start_date, end_date
            )
            collector.collect_binance_klines(
                binance_symbol, "1d", start_date, end_date
            )
    
    print("Data collection complete")


def cmd_freeze(args: argparse.Namespace) -> None:
    workspace = Path(args.workspace)
    raw_dir = workspace / "data" / "raw"
    
    contracts = args.contracts.split(",")
    timeframe = args.timeframe
    
    data = {}
    
    if args.backfill_binance and args.overlap_threshold is None:
        raise SystemExit("--overlap-threshold is required with --backfill-binance")
    if args.timeframe == "1day" and not args.daily_cut:
        raise SystemExit("--daily-cut is required when freezing 1day (UTC00 or UTC16)")

    stitch_override = getattr(args, "stitch_point", None)
    stitch_config = getattr(args, "stitch_config", None)
    window_bars = int(getattr(args, "non_comparable_window", None) or STITCH_WINDOW_BARS)
    ticks_file = resolve_bad_ticks_file(workspace, getattr(args, "bad_ticks", None))
    stitch_file = Path(stitch_config) if stitch_config else None
    stitch_points = load_stitch_points(stitch_file, override=stitch_override)
    ticks = load_bad_ticks(ticks_file)
    ticks_digest = bad_ticks_hash(ticks_file, ticks)

    stitcher = DataStitcher(
        close_deviation_threshold=args.overlap_threshold or 0.003,
        window_bars=window_bars,
    )
    overlap_deviation: dict = {}
    source_counts: dict = {}
    overlap_audit: list[dict] = []
    used_stitch_points: dict[str, str] = {}

    for contract in contracts:
        htx_file = raw_dir / f"{contract}_{timeframe}_htx.parquet"
        
        if not htx_file.exists():
            print(f"Warning: HTX data not found for {contract}, skipping")
            continue
        
        import pandas as pd
        htx_df = pd.read_parquet(htx_file)
        
        if timeframe == "1day":
            htx_4h_file = raw_dir / f"{contract}_4hour_htx.parquet"
            if not htx_4h_file.exists():
                print(f"Warning: HTX 4h data not found for {contract}, cannot build canonical 1d")
                continue
            htx_4h = pd.read_parquet(htx_4h_file)
            binance_4h = pd.DataFrame()
            if args.backfill_binance:
                binance_symbol = contract.replace("-", "")
                bn_4h_file = raw_dir / f"{binance_symbol}_4h_binance.parquet"
                if bn_4h_file.exists():
                    binance_4h = pd.read_parquet(bn_4h_file)
            point = stitch_point_for(contract, stitch_points, override=stitch_override)
            used_stitch_points[contract] = point.isoformat()
            combined, stats = stitcher.stitch_daily_from_4h(
                htx_4h,
                binance_4h,
                htx_1d=htx_df,
                daily_cut=args.daily_cut or "UTC00",
                stitch_point=point,
                symbol=contract,
                window_bars=window_bars,
            )
            print(f"{contract} canonical 1d (daily_cut={args.daily_cut or 'UTC00'}): {stats}")
            overlap_deviation[contract] = {"1day": stats.get("overlap_deviation", {})}
            overlap_audit.extend(stats.get("overlap_audit") or [])
            source_counts[contract] = {
                "htx_bars": stats.get("htx_bars", 0),
                "binance_bars": stats.get("binance_bars", 0),
                "binance_backfill_bars": stats.get("binance_backfill_bars", 0),
                "overlap_bars": stats.get("overlap_bars", 0),
                "non_comparable_bars": stats.get("non_comparable_bars", 0),
                "total_bars": stats.get("total_bars", 0),
                "stitch_point": stats.get("stitch_point", point.isoformat()),
            }
            if combined.empty:
                print(f"{contract}: no complete daily bars after resample, skipping")
                continue
            data[contract] = apply_bad_ticks(combined, contract, ticks)
        elif args.backfill_binance:
            binance_symbol = contract.replace("-", "")
            binance_file = raw_dir / f"{binance_symbol}_{timeframe.replace('hour', 'h')}_binance.parquet"
            point = stitch_point_for(contract, stitch_points, override=stitch_override)
            used_stitch_points[contract] = point.isoformat()
            if binance_file.exists():
                binance_df = pd.read_parquet(binance_file)
                combined, stats = stitcher.stitch_klines(
                    htx_df,
                    binance_df,
                    stitch_point=point,
                    symbol=contract,
                    window_bars=window_bars,
                )
                print(f"{contract}: {stats}")
                overlap_deviation[contract] = {timeframe: stats.get("overlap_deviation", {})}
                overlap_audit.extend(stats.get("overlap_audit") or [])
                source_counts[contract] = {
                    "htx_bars": stats.get("htx_bars", 0),
                    "binance_bars": stats.get("binance_bars", 0),
                    "binance_backfill_bars": stats.get("binance_backfill_bars", 0),
                    "overlap_bars": stats.get("overlap_bars", 0),
                    "non_comparable_bars": stats.get("non_comparable_bars", 0),
                    "total_bars": stats.get("total_bars", 0),
                    "stitch_point": stats.get("stitch_point", point.isoformat()),
                }
                data[contract] = apply_bad_ticks(combined, contract, ticks)
            else:
                data[contract] = apply_bad_ticks(htx_df, contract, ticks)
        else:
            data[contract] = apply_bad_ticks(htx_df, contract, ticks)
    
    if not data:
        print("No data to freeze")
        return
    
    dataset_mgr = DatasetManager(workspace / "data" / "datasets")
    
    extra_metadata = {
        "stitch_points": used_stitch_points or {
            contract: stitch_point_for(contract, stitch_points, override=stitch_override).isoformat()
            for contract in data
        },
        "non_comparable_window_bars": window_bars,
        "bad_ticks_hash": ticks_digest,
        "default_stitch_point": DEFAULT_STITCH_POINT.isoformat(),
    }
    funding_sources = summarize_funding_sources(raw_dir, contracts)
    if funding_sources:
        extra_metadata["funding_sources"] = funding_sources
    funding_files = hash_funding_files(raw_dir, contracts)
    if funding_files:
        extra_metadata["funding_files"] = funding_files
    spec_files = hash_spec_files(raw_dir, contracts)
    if spec_files:
        extra_metadata["spec_files"] = spec_files
    if timeframe == "1day":
        extra_metadata.update(
            daily_resample_manifest_fields(daily_cut=args.daily_cut or "UTC00")
        )
    else:
        extra_metadata["daily_cut"] = args.daily_cut

    cutoff, validation_start, test_start = resolve_freeze_split_args(args)
    frozen = dataset_mgr.freeze_dataset(
        data,
        timeframe=timeframe,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        extra_metadata=extra_metadata,
        data_cutoff_close_utc=cutoff,
        validation_start_utc=validation_start,
        test_start_utc=test_start,
        bad_ticks=ticks,
    )
    dataset_dir = workspace / "data" / "datasets" / frozen.dataset_id
    if source_counts:
        (dataset_dir / "source_counts.json").write_text(
            json.dumps(source_counts, indent=2)
        )
        print(f"source_counts: {json.dumps(source_counts)}")
    timeline = funding_source_timeline(raw_dir, contracts)
    if timeline:
        (dataset_dir / "funding_source_timeline.json").write_text(
            json.dumps(timeline, indent=2)
        )
        print(f"funding_source_timeline: {json.dumps(timeline)}")
    if overlap_deviation:
        (dataset_dir / "overlap_deviation.json").write_text(
            json.dumps(overlap_deviation, indent=2)
        )
    write_quality_audit(
        dataset_dir / "data_quality_audit.json",
        {
            "stitch_points": extra_metadata["stitch_points"],
            "non_comparable_window_bars": window_bars,
            "bad_ticks_hash": ticks_digest,
            "overlap_audit": overlap_audit,
            "known_candidates": KNOWN_CANDIDATES,
        },
    )
    from .data.manifest import DatasetManifest
    batch_path = workspace / "data" / "datasets" / "manifests" / f"{frozen.dataset_id}.json"
    if batch_path.exists():
        batch = DatasetManifest.load(batch_path)
        aux = dict(batch.metadata.get("aux_files") or {})
        dataset_manifest = dataset_dir / "manifest.json"
        if dataset_manifest.exists():
            aux["dataset_manifest"] = {
                "path": "manifest.json",
                "sha256": f"sha256:{hashlib.sha256(dataset_manifest.read_bytes()).hexdigest()}",
            }
        overlap_path = dataset_dir / "overlap_deviation.json"
        if overlap_path.exists():
            aux["overlap_deviation"] = {
                "path": "overlap_deviation.json",
                "sha256": f"sha256:{hashlib.sha256(overlap_path.read_bytes()).hexdigest()}",
            }
        if aux:
            batch.metadata["aux_files"] = aux
            batch.save(batch_path)
        print(f"manifest_hash: {batch.sha256()}")
    
    print(f"Dataset frozen: {frozen.dataset_id}")
    print(f"Total bars: {frozen.total_bars}")
    print("Segment counts:")
    for symbol, counts in (frozen.segment_counts or {}).items():
        print(
            f"  {symbol}: train={counts['train']} val={counts['val']} test={counts['test']}"
        )
    if frozen.data_cutoff_close_utc is not None:
        print(f"Cutoff: {frozen.data_cutoff_close_utc.strftime('%Y-%m-%dT%H:%M:%SZ')}")
    if frozen.validation_start_utc is not None:
        print(f"validation_start_utc: {frozen.validation_start_utc.strftime('%Y-%m-%dT%H:%M:%SZ')}")
    if frozen.test_start_utc is not None:
        print(f"test_start_utc: {frozen.test_start_utc.strftime('%Y-%m-%dT%H:%M:%SZ')}")


def cmd_run(args: argparse.Namespace) -> None:
    config_path = Path(args.config)
    workspace = Path(args.workspace)
    
    runner = BacktestRunner(config_path, workspace)
    try:
        results = runner.run()
    finally:
        runner.close()
    
    print(f"Backtest complete: {runner.run_id}")
    print(f"Final equity: ${results['final_equity']:.2f}")
    print(f"Total trades: {len(results['fills'])}")


def cmd_health(args: argparse.Namespace) -> None:
    workspace = Path(args.workspace)
    heartbeat_dir = heartbeats_dir(workspace)
    hb_txt = heartbeat_file(workspace)
    state_file = (
        Path(args.collector_state)
        if getattr(args, "collector_state", None)
        else collector_state_file(workspace)
    )

    hb_dir = heartbeat_dir if heartbeat_dir.exists() else None
    hb_file = hb_txt if hb_txt.exists() else None
    state_path = state_file if state_file.exists() else None
    if hb_dir is None and hb_file is None and state_path is None:
        print("No heartbeat files found")
        sys.exit(1)

    is_healthy, details = check_system_health(
        heartbeat_dir=hb_dir,
        collector_state_path=state_path,
        max_heartbeat_age_seconds=args.max_age,
        bar_staleness_threshold_minutes=getattr(args, "threshold_minutes", 10),
        heartbeat_file=hb_file,
    )

    version_info = get_version_info()
    print(f"HTX Trend Research System {version_info}")
    if is_healthy:
        print("✓ Healthy")
        sys.exit(0)
    print("✗ Unhealthy")
    print(json.dumps(details, indent=2, default=str))
    sys.exit(1)


def cmd_status(args: argparse.Namespace) -> None:
    if getattr(args, "status_file", None):
        set_status_file_path(args.status_file)
    elif getattr(args, "workspace", None):
        set_status_file_path(status_file(args.workspace))
    version_info = get_version_info()
    status = get_status()
    
    if status is None:
        print("No status available")
        sys.exit(1)
    
    if args.json:
        status_dict = status.to_dict()
        status_dict["version_info"] = version_info.to_dict()
        print(json.dumps(status_dict, indent=2, default=str))
    else:
        print(f"HTX Trend Research System {version_info}")
        print()
        print(f"Run ID: {status.run_id}")
        print(f"Component: {status.component}")
        print(f"Health: {status.health.value}")
        print(f"Timestamp: {status.timestamp}")
        print(f"\nPositions: {len(status.positions)}")
        
        strategy_positions = [p for p in status.positions if p.is_strategy_position]
        non_strategy_positions = [p for p in status.positions if not p.is_strategy_position]
        
        if strategy_positions:
            print(f"  Strategy positions ({len(strategy_positions)}):")
            for pos in strategy_positions:
                print(f"    - {pos.symbol} {pos.side} {pos.size} @ {pos.entry_price:.2f}")
        
        if non_strategy_positions:
            print(f"  Non-strategy positions ({len(non_strategy_positions)}):")
            for pos in non_strategy_positions:
                print(f"    - {pos.symbol} {pos.side} {pos.size} @ {pos.entry_price:.2f} [EXTERNAL]")
        
        print(f"\nTotal PnL: ${status.total_pnl:.2f}")
        print(f"Account Equity: ${status.account_equity:.2f}")
        
        if status.account_liquidation_distance_pct is not None:
            print(f"Liquidation Distance: {status.account_liquidation_distance_pct*100:.2f}%")
        
        if status.fee_rates:
            print(f"\nFee Rates: {status.fee_rates}")
        
        if status.approvals:
            print(f"\nApprovals:")
            for gate, approval in status.approvals.items():
                print(f"  {gate}: {approval.decision} by {approval.approver}")
        
        if status.circuit_breaker.active:
            print(f"\n⚠️  Circuit Breaker ACTIVE: {status.circuit_breaker.reason}")


def cmd_approve(args: argparse.Namespace) -> None:
    """创建审批记录"""
    workspace = Path(args.workspace)
    ledger = LedgerManager(workspace / "ledger.db")
    
    gate = ApprovalGate(args.gate)
    decision = ApprovalDecision(args.decision)
    
    approval = Approval(
        approval_id=None,
        gate=gate,
        run_id=args.run_id,
        strategy_version=args.strategy_version,
        approver=args.approver,
        decision=decision,
        rationale=args.rationale,
        created_at=datetime.now(UTC),
    )
    
    approval_id = ledger.create_approval(approval)
    
    print(f"Approval created: ID {approval_id}")
    print(f"  Gate: {gate.value}")
    print(f"  Run: {args.run_id}")
    print(f"  Decision: {decision.value}")
    print(f"  Approver: {args.approver}")


def cmd_update_conclusion(args: argparse.Namespace) -> None:
    workspace = Path(args.workspace)
    ledger = LedgerManager(workspace / "ledger.db")
    ledger.append_conclusion(
        run_id=args.run_id,
        conclusion=args.conclusion,
        decision=args.decision,
        created_at=datetime.now(UTC),
    )
    print(f"✓ Updated conclusion for {args.run_id}")


def cmd_export_trials(args: argparse.Namespace) -> None:
    """导出试验记录供 DSR/PBO 计算。"""
    workspace = Path(args.workspace)
    ledger = LedgerManager(workspace / "ledger.db")
    text = ledger.export_trials(family_id=args.family_id, fmt=args.format)
    if args.output:
        Path(args.output).write_text(text)
        print(f"Exported {args.format} to {args.output}")
    else:
        print(text)


def cmd_check_approval(args: argparse.Namespace) -> None:
    """检查审批门禁"""
    workspace = Path(args.workspace)
    ledger = LedgerManager(workspace / "ledger.db")
    
    gate = ApprovalGate(args.gate)
    
    approved, message = ledger.check_approval_gate(
        gate, args.run_id, args.strategy_version
    )
    
    print(f"Gate: {gate.value}")
    print(f"Run: {args.run_id}")
    print(f"Version: {args.strategy_version}")
    print(f"\nResult: {'✓ APPROVED' if approved else '✗ REJECTED'}")
    print(f"Message: {message}")
    
    sys.exit(0 if approved else 1)


def main() -> None:
    configure_arrow()
    parser = argparse.ArgumentParser(
        description="HTX Trend Research System MVP-1"
    )
    
    parser.add_argument(
        "--workspace",
        type=str,
        default=".",
        help="Workspace directory (default: current directory)",
    )
    
    subparsers = parser.add_subparsers(dest="command", required=True)
    
    collect_parser = subparsers.add_parser("collect", help="Collect market data")
    collect_parser.add_argument("--contracts", required=True, help="Comma-separated contract codes")
    collect_parser.add_argument(
        "--lookback-days",
        type=parse_lookback_days,
        required=True,
        help="History window in days, or 'max' (HTX paginate + Binance from 2020-01-01)",
    )
    collect_parser.add_argument("--backfill-binance", action="store_true")
    
    freeze_parser = subparsers.add_parser("freeze", help="Freeze dataset")
    freeze_parser.add_argument("--contracts", required=True)
    freeze_parser.add_argument("--timeframe", required=True, choices=["4hour", "1day"])
    freeze_parser.add_argument("--train-ratio", type=float, default=0.6)
    freeze_parser.add_argument("--val-ratio", type=float, default=0.2)
    freeze_parser.add_argument(
        "--cutoff",
        default=None,
        help="Required unless --split-from-manifest is set. Include bars whose close "
        "is <= this UTC instant (v0.1.0: 2026-10-12T00:00:00Z)",
    )
    freeze_parser.add_argument(
        "--validation-start",
        default=None,
        dest="validation_start",
        help="UTC 00:00 validation start; must be passed together with --test-start",
    )
    freeze_parser.add_argument(
        "--test-start",
        default=None,
        dest="test_start",
        help="UTC 00:00 test start; must be passed together with --validation-start",
    )
    freeze_parser.add_argument(
        "--split-from-manifest",
        default=None,
        dest="split_from_manifest",
        help="Copy validation_start_utc, test_start_utc, and data_cutoff_close_utc "
        "from a frozen 4h manifest.json",
    )
    freeze_parser.add_argument("--backfill-binance", action="store_true")
    freeze_parser.add_argument("--overlap-threshold", type=float)
    freeze_parser.add_argument("--daily-cut", choices=["UTC00", "UTC16"])
    freeze_parser.add_argument(
        "--stitch-point",
        default=None,
        help="ISO UTC stitch instant (default 2020-11-01T00:00:00Z for BTC/ETH)",
    )
    freeze_parser.add_argument("--stitch-config", default=None, help="YAML map of symbol -> stitch point")
    freeze_parser.add_argument(
        "--non-comparable-window",
        type=int,
        default=STITCH_WINDOW_BARS,
        help="Bars each side of the stitch point marked non_comparable (default 30)",
    )
    freeze_parser.add_argument(
        "--bad-ticks",
        default=None,
        help="Versioned bad_ticks.yaml (confirmed rows excluded from signals)",
    )
    
    run_parser = subparsers.add_parser("run", help="Run backtest")
    run_parser.add_argument("config", help="Path to run config YAML")
    
    health_parser = subparsers.add_parser("health", help="Check component health")
    health_parser.add_argument("--max-age", type=int, default=300)
    health_parser.add_argument("--threshold-minutes", type=int, default=10)
    health_parser.add_argument("--collector-state", help="Collector state JSON path")
    
    status_parser = subparsers.add_parser("status", help="Get system status")
    status_parser.add_argument("--json", action="store_true")
    status_parser.add_argument("--status-file", help="Path to status.json written by a running process")
    
    approve_parser = subparsers.add_parser("approve", help="Create approval record")
    approve_parser.add_argument("--gate", required=True, choices=["PRE_PAPER", "PRE_LIVE", "BREAKER_RESTART", "STAGE_ACCEPTANCE"])
    approve_parser.add_argument("--run-id", required=True)
    approve_parser.add_argument("--strategy-version", required=True)
    approve_parser.add_argument("--approver", required=True, help="Name or email of approver")
    approve_parser.add_argument("--decision", required=True, choices=["APPROVED", "REJECTED", "PENDING"])
    approve_parser.add_argument("--rationale", required=True, help="Reason for decision")
    
    check_parser = subparsers.add_parser("check-approval", help="Check approval gate")
    check_parser.add_argument("--gate", required=True, choices=["PRE_PAPER", "PRE_LIVE", "BREAKER_RESTART", "STAGE_ACCEPTANCE"])
    check_parser.add_argument("--run-id", required=True)
    check_parser.add_argument("--strategy-version", required=True)

    export_parser = subparsers.add_parser("export-trials", help="Export trial records for DSR/PBO")
    export_parser.add_argument("--family-id", help="Filter by family_id")
    export_parser.add_argument("--format", choices=["csv", "json"], default="csv")
    export_parser.add_argument("--output", help="Output file path")

    conclusion_parser = subparsers.add_parser("update-conclusion", help="Append conclusion and decision")
    conclusion_parser.add_argument("--run-id", required=True)
    conclusion_parser.add_argument("--conclusion", required=True)
    conclusion_parser.add_argument(
        "--decision", required=True, choices=["KEEP", "DROP", "INCONCLUSIVE"]
    )
    
    args = parser.parse_args()
    
    if args.command == "collect":
        cmd_collect(args)
    elif args.command == "freeze":
        cmd_freeze(args)
    elif args.command == "run":
        cmd_run(args)
    elif args.command == "health":
        cmd_health(args)
    elif args.command == "status":
        cmd_status(args)
    elif args.command == "approve":
        cmd_approve(args)
    elif args.command == "check-approval":
        cmd_check_approval(args)
    elif args.command == "export-trials":
        cmd_export_trials(args)
    elif args.command == "update-conclusion":
        cmd_update_conclusion(args)


if __name__ == "__main__":
    main()
