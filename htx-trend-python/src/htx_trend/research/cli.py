"""CLI 命令行接口"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

from .data import DataCollector, DatasetManager, DataStitcher
from .ledger import Approval, ApprovalDecision, ApprovalGate, LedgerManager
from .ops import HeartbeatManager, get_status
from .ops.heartbeat import check_health as check_system_health
from .ops.status import StatusInterface, set_status_file_path
from .runner import BacktestRunner
from .version_info import get_version_info


def cmd_collect(args: argparse.Namespace) -> None:
    workspace = Path(args.workspace)
    collector = DataCollector(workspace / "data" / "raw")
    
    contracts = args.contracts.split(",")
    
    for contract in contracts:
        print(f"Collecting {contract}...")
        
        htx_4h = collector.collect_htx_klines(contract, "4hour", args.lookback_days)
        htx_1d = collector.collect_htx_klines(contract, "1day", args.lookback_days)
        
        collector.collect_funding_history(contract)
        collector.collect_contract_spec(contract)
        
        if args.backfill_binance:
            end_date = datetime.now(UTC)
            start_date = end_date - timedelta(days=args.lookback_days)
            
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

    stitcher = DataStitcher(close_deviation_threshold=args.overlap_threshold or 0.003)
    overlap_deviation: dict = {}
    
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
            combined, stats = stitcher.stitch_daily_from_4h(
                htx_4h, binance_4h, htx_1d=htx_df, daily_cut=args.daily_cut or "UTC00"
            )
            print(f"{contract} canonical 1d (daily_cut={args.daily_cut or 'UTC00'}): {stats}")
            overlap_deviation[contract] = {"1day": stats.get("overlap_deviation", {})}
            data[contract] = combined
        elif args.backfill_binance:
            binance_symbol = contract.replace("-", "")
            binance_file = raw_dir / f"{binance_symbol}_{timeframe.replace('hour', 'h')}_binance.parquet"
            
            if binance_file.exists():
                binance_df = pd.read_parquet(binance_file)
                combined, stats = stitcher.stitch_klines(htx_df, binance_df)
                print(f"{contract}: {stats}")
                overlap_deviation[contract] = {timeframe: stats.get("overlap_deviation", {})}
                data[contract] = combined
            else:
                data[contract] = htx_df
        else:
            data[contract] = htx_df
    
    if not data:
        print("No data to freeze")
        return
    
    dataset_mgr = DatasetManager(workspace / "data" / "datasets")
    
    frozen = dataset_mgr.freeze_dataset(
        data,
        timeframe=timeframe,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
    )
    dataset_dir = workspace / "data" / "datasets" / frozen.dataset_id
    if overlap_deviation:
        (dataset_dir / "overlap_deviation.json").write_text(
            json.dumps(overlap_deviation, indent=2)
        )
    from .data.manifest import DatasetManifest
    batch_path = workspace / "data" / "datasets" / "manifests" / f"{frozen.dataset_id}.json"
    if batch_path.exists():
        batch = DatasetManifest.load(batch_path)
        batch.metadata.update({
            "daily_cut": args.daily_cut or ("UTC00" if timeframe == "1day" else None),
            "overlap_deviation": overlap_deviation,
        })
        batch.save(batch_path)
        print(f"manifest_hash: {batch.sha256()}")
    
    print(f"Dataset frozen: {frozen.dataset_id}")
    print(f"Total bars: {frozen.total_bars}")
    print(f"Train: {frozen.train_end_idx}")
    print(f"Val: {frozen.val_end_idx - frozen.train_end_idx}")
    print(f"Test: {frozen.total_bars - frozen.val_end_idx}")


def cmd_run(args: argparse.Namespace) -> None:
    config_path = Path(args.config)
    workspace = Path(args.workspace)
    
    runner = BacktestRunner(config_path, workspace)
    results = runner.run()
    
    print(f"Backtest complete: {runner.run_id}")
    print(f"Final equity: ${results['final_equity']:.2f}")
    print(f"Total trades: {len(results['fills'])}")


def cmd_health(args: argparse.Namespace) -> None:
    workspace = Path(args.workspace)
    heartbeat_dir = workspace / "heartbeats"
    state_file = Path(args.collector_state) if getattr(args, "collector_state", None) else workspace / "collector_state.json"

    hb_dir = heartbeat_dir if heartbeat_dir.exists() else None
    state_path = state_file if state_file.exists() else None
    if hb_dir is None and state_path is None:
        print("No heartbeat files found")
        sys.exit(1)

    is_healthy, details = check_system_health(
        heartbeat_dir=hb_dir,
        collector_state_path=state_path,
        max_heartbeat_age_seconds=args.max_age,
        bar_staleness_threshold_minutes=getattr(args, "threshold_minutes", 10),
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
    collect_parser.add_argument("--lookback-days", type=int, default=1095)
    collect_parser.add_argument("--backfill-binance", action="store_true")
    
    freeze_parser = subparsers.add_parser("freeze", help="Freeze dataset")
    freeze_parser.add_argument("--contracts", required=True)
    freeze_parser.add_argument("--timeframe", required=True, choices=["4hour", "1day"])
    freeze_parser.add_argument("--train-ratio", type=float, default=0.6)
    freeze_parser.add_argument("--val-ratio", type=float, default=0.2)
    freeze_parser.add_argument("--backfill-binance", action="store_true")
    freeze_parser.add_argument("--overlap-threshold", type=float)
    freeze_parser.add_argument("--daily-cut", choices=["UTC00", "UTC16"])
    
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
