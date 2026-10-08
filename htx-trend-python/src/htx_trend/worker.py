"""Process entry point for an operator-registered Python strategy instance."""
from __future__ import annotations

import argparse
import os
import time

from .htx_v5 import HtxV5Client
from .market_data import HtxMarketData
from .mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect
from .strategy_platform import DisabledStrategyWorker, StrategySupervisor, TrendObservationWorker, TrendObserverConfig


def _contracts_from_env() -> tuple[str, ...]:
    return tuple(dict.fromkeys(item.strip().upper() for item in os.environ.get("COINSTRATEGY_CONTRACTS", "").split(",") if item.strip()))


def main() -> None:
    parser = argparse.ArgumentParser(description="CoinStrategy independently deployed Python strategy worker")
    parser.add_argument("--strategy-instance", required=True)
    parser.add_argument("--once", action="store_true", help="run one read-only heartbeat/scan cycle")
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
        if family == "TREND":
            contracts = _contracts_from_env()
            market_client = HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL", "https://api.huobi.pro"))
            worker = TrendObservationWorker(instance, repository, HtxMarketData(market_client), TrendObserverConfig(contracts))
        elif family in {"CARRY_SINGLE", "CARRY_CROSS"}:
            worker = DisabledStrategyWorker(instance, repository)
        else:
            raise SystemExit(f"unsupported strategy family: {instance.strategy_family}")
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
