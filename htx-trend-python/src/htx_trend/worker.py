"""Process entry point for an operator-registered Python strategy instance."""
from __future__ import annotations

import argparse
import os
import time

from .account_facts import HtxAccountFactsAdapter
from .credentials import materialize
from .execution import ExecutionConfig
from .execution_service import EntryExecutionConfig, HtxTrendEntryExecutor
from .htx_v5 import HtxV5Client
from .position_manager import LivePositionManager, ExitConfig
from .writer_lock import TrendWriterLock
from .live import HtxLiveExecutor, LiveAuthority
from .live_worker import LiveTrendWorker, LiveTrendWorkerConfig
from .market_data import HtxMarketData
from .mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect
from .strategy_platform import DisabledStrategyWorker, ExecutionMode, StrategySupervisor, TrendObservationWorker, TrendObserverConfig


def _account_client(repository: HtxTrendMySqlRepository, account_id: int, *, live: bool) -> HtxV5Client:
    record = repository.account_credentials(account_id)
    if record is None:
        raise RuntimeError(f"registered HTX account id={account_id} has no credentials")
    credentials = materialize(record)
    return HtxV5Client(credentials.access_key, credentials.secret_key, base_url=credentials.base_url,
                       live_trading_enabled=live)


def _validate_first_live_cutover() -> None:
    """Enforce the exact, reviewable scope of the first Python LIVE cutover."""
    if os.environ.get("HTX_PYTHON_EXIT_PARITY_APPROVED", "false").lower() != "true":
        raise SystemExit("LIVE requires completed exit parity acceptance: HTX_PYTHON_EXIT_PARITY_APPROVED=true")
    if os.environ.get("HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE", "").lower() != "false":
        raise SystemExit(
            "first Python cutover requires explicit HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="CoinStrategy independently deployed Python strategy worker")
    parser.add_argument("--strategy-instance", required=True)
    parser.add_argument("--once", action="store_true", help="run one scan cycle")
    parser.add_argument("--interval-seconds", type=int, default=45)
    args = parser.parse_args()
    repository = HtxTrendMySqlRepository(connect(MySqlSettings.from_env()))
    instance = None
    writer_lock = None
    started = False
    try:
        repository.validate_runtime_schema()
        instance = repository.runtime_instance(args.strategy_instance)
        if instance is None:
            raise SystemExit(f"strategy instance not found: {args.strategy_instance}")
        if not instance.enabled:
            raise SystemExit(f"strategy instance is disabled: {args.strategy_instance}")
        family = instance.strategy_family.upper()
        if family == "TREND" and instance.execution_mode == ExecutionMode.OBSERVE:
            contracts = tuple(dict.fromkeys(item.strip().upper() for item in os.environ.get("HTX_TREND_CONTRACTS", "").split(",") if item.strip()))
            market_client = HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL", "https://api.hbdm.com"))
            worker = TrendObservationWorker(instance, repository, HtxMarketData(market_client), TrendObserverConfig(contracts))
        elif family == "TREND" and instance.execution_mode == ExecutionMode.LIVE:
            if instance.account_id is None:
                raise SystemExit("LIVE trend instance requires an account-bound runtime instance")
            account = repository.trade_account(instance.account_id)
            if account is None or account.role != "PRIMARY":
                raise SystemExit("LIVE trend instance must bind an enabled PRIMARY HTX account")
            # Certification and the approved MIXED policy are deployment gates,
            # not alternative strategy thresholds.
            _validate_first_live_cutover()
            if any(a.role != "PRIMARY" and a.mirror_enabled for a in repository.enabled_trend_accounts()):
                raise SystemExit("first Python cutover is primary-only; mirror recovery acceptance is still required")
            writer_lock = TrendWriterLock(connect(MySqlSettings.from_env()))
            writer_lock.acquire()
            private_client = _account_client(repository, account.account_id, live=True)
            market = HtxMarketData(private_client)
            live = HtxLiveExecutor(private_client, LiveAuthority(True, str(account.account_id)), str(account.account_id), writer_guard=writer_lock.assert_owned)
            entry_config = EntryExecutionConfig.from_env()
            entry = HtxTrendEntryExecutor(repository=repository, instance=instance, account=account,
                facts=HtxAccountFactsAdapter(private_client), executor=live, execution=ExecutionConfig.from_env(),
                config=entry_config)
            scan_config = LiveTrendWorkerConfig.from_env()
            manager = LivePositionManager(repository, instance, account, market, private_client, live,
                period=scan_config.entry_period, java_config=scan_config.java_entry, config=ExitConfig.from_env())
            worker = LiveTrendWorker(instance, repository, market, entry, scan_config,
                position_manager=manager, writer_guard=writer_lock.assert_owned)
        elif family in {"CARRY_SINGLE", "CARRY_CROSS"}:
            worker = DisabledStrategyWorker(instance, repository)
        else:
            raise SystemExit(f"unsupported strategy family/mode: {instance.strategy_family}/{instance.execution_mode}")
        repository.mark_runtime_started(instance.instance_key)
        started = True
        supervisor = StrategySupervisor(repository, {instance.instance_key: worker})
        while True:
            supervisor.tick_all()
            if args.once:
                return
            time.sleep(max(5, args.interval_seconds))
    finally:
        try:
            if started:
                repository.mark_runtime_stopped(instance.instance_key)
        finally:
            repository.close()
            if writer_lock is not None:
                writer_lock.close()


if __name__ == "__main__":
    main()
