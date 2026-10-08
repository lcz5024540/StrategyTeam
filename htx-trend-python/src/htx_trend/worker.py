"""Process entry point for an operator-registered Python strategy instance."""
from __future__ import annotations

import argparse
import os
import time
from dataclasses import replace

from .account_facts import HtxAccountFactsAdapter
from .credentials import materialize
from .execution_service import ConfirmedEntryFill, ConfirmedReductionFill, HtxTrendEntryExecutor
from .htx_v5 import HtxV5Client
from .live import HtxLiveExecutor, LiveAuthority
from .live_worker import LiveTrendWorker, LiveTrendWorkerConfig
from .market_data import HtxMarketData
from .mirror import ConfirmedPrimaryFill, ConfirmedPrimaryReduction, HtxTrendMirrorFollower
from .mysql_store import HtxTrendMySqlRepository, MySqlSettings, RuntimeInstance, connect
from .strategy_platform import DisabledStrategyWorker, ExecutionMode, StrategySupervisor, TrendObservationWorker, TrendObserverConfig


def _contracts_from_env() -> tuple[str, ...]:
    return tuple(dict.fromkeys(item.strip().upper() for item in os.environ.get("COINSTRATEGY_CONTRACTS", "").split(",") if item.strip()))


def _live_enabled() -> bool:
    return os.environ.get("HTX_PYTHON_LIVE_EXECUTION_ENABLED", "false").strip().lower() in {"1", "true", "yes"}


def _account_client(repository: HtxTrendMySqlRepository, account_id: int, *, live: bool) -> HtxV5Client:
    record = repository.account_credentials(account_id)
    if record is None:
        raise RuntimeError(f"registered HTX account id={account_id} has no credentials")
    credentials = materialize(record)
    return HtxV5Client(credentials.access_key, credentials.secret_key, base_url=credentials.base_url,
                       live_trading_enabled=live)


def _mirror_callbacks(repository: HtxTrendMySqlRepository, primary: RuntimeInstance, market: HtxMarketData):
    followers: list[HtxTrendMirrorFollower] = []
    for account in repository.enabled_trend_accounts():
        if account.role == "PRIMARY" or not account.mirror_enabled:
            continue
        try:
            client = _account_client(repository, account.account_id, live=True)
            mirror_instance = replace(primary, instance_key=f"{primary.instance_key}:mirror:{account.account_id}",
                                      account_id=account.account_id, account_label=account.account_label)
            followers.append(HtxTrendMirrorFollower(repository=repository, instance=mirror_instance, account=account,
                facts=HtxAccountFactsAdapter(client), executor=HtxLiveExecutor(client, LiveAuthority(True, str(account.account_id)), str(account.account_id))))
        except Exception:
            # Missing/decryption-failed mirror credentials do not stop the primary account.
            continue

    def entry(event: ConfirmedEntryFill) -> None:
        if not followers:
            return
        book, contract = market.order_book(event.contract_code), market.contract_spec(event.contract_code)
        primary_fill = ConfirmedPrimaryFill(event.position_id, event.position_key, event.run_id or "", event.contract_code,
            event.side, event.filled_contracts, event.fill_price, event.contract_size, event.model, event.factor_snapshot_id)
        for follower in followers:
            follower.sync_entry(primary_fill, book, contract)

    def reduction(event: ConfirmedReductionFill) -> None:
        if not followers:
            return
        book = market.order_book(event.contract_code)
        primary_fill = ConfirmedPrimaryReduction(event.position_id, event.run_id or "", event.contract_code, event.side,
            event.filled_contracts, event.model, event.factor_snapshot_id, event.full_close)
        for follower in followers:
            follower.sync_reduction(primary_fill, book)
    return entry, reduction


def main() -> None:
    parser = argparse.ArgumentParser(description="CoinStrategy independently deployed Python strategy worker")
    parser.add_argument("--strategy-instance", required=True)
    parser.add_argument("--once", action="store_true", help="run one scan cycle")
    parser.add_argument("--interval-seconds", type=int, default=45)
    args = parser.parse_args()
    repository = HtxTrendMySqlRepository(connect(MySqlSettings.from_env()))
    instance = None
    try:
        repository.validate_runtime_schema()
        instance = repository.runtime_instance(args.strategy_instance)
        if instance is None:
            raise SystemExit(f"strategy instance not found: {args.strategy_instance}")
        if not instance.enabled:
            raise SystemExit(f"strategy instance is disabled: {args.strategy_instance}")
        family = instance.strategy_family.upper()
        contracts = _contracts_from_env()
        if family == "TREND" and instance.execution_mode == ExecutionMode.OBSERVE:
            market_client = HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL", "https://api.huobi.pro"))
            worker = TrendObservationWorker(instance, repository, HtxMarketData(market_client), TrendObserverConfig(contracts))
        elif family == "TREND" and instance.execution_mode == ExecutionMode.LIVE:
            if not _live_enabled() or instance.account_id is None:
                raise SystemExit("LIVE requires HTX_PYTHON_LIVE_EXECUTION_ENABLED=true and an account-bound runtime instance")
            account = repository.trade_account(instance.account_id)
            if account is None or account.role != "PRIMARY":
                raise SystemExit("LIVE trend instance must bind an enabled PRIMARY HTX account")
            private_client = _account_client(repository, account.account_id, live=True)
            market = HtxMarketData(private_client)
            entry_callback, reduction_callback = _mirror_callbacks(repository, instance, market)
            live = HtxLiveExecutor(private_client, LiveAuthority(True, str(account.account_id)), str(account.account_id))
            entry = HtxTrendEntryExecutor(repository=repository, instance=instance, account=account,
                facts=HtxAccountFactsAdapter(private_client), executor=live,
                on_confirmed_entry=entry_callback, on_confirmed_reduction=reduction_callback)
            worker = LiveTrendWorker(instance, repository, market, entry, LiveTrendWorkerConfig(contracts))
        elif family in {"CARRY_SINGLE", "CARRY_CROSS"}:
            worker = DisabledStrategyWorker(instance, repository)
        else:
            raise SystemExit(f"unsupported strategy family/mode: {instance.strategy_family}/{instance.execution_mode}")
        repository.mark_runtime_started(instance.instance_key)
        supervisor = StrategySupervisor(repository, {instance.instance_key: worker})
        while True:
            supervisor.tick_all()
            if args.once:
                return
            time.sleep(max(5, args.interval_seconds))
    finally:
        if instance is not None:
            repository.mark_runtime_stopped(instance.instance_key)
        repository.close()


if __name__ == "__main__":
    main()
