"""Process entry point for an operator-registered Python strategy instance."""
from __future__ import annotations

import argparse
import json
import logging
import os
import time
import signal
from threading import Event
from contextlib import ExitStack
from dataclasses import replace
from pathlib import Path

from .account_facts import HtxAccountFactsAdapter, AccountFactConfig
from .credentials import materialize
from .execution import ExecutionConfig
from .execution_service import EntryExecutionConfig, HtxTrendEntryExecutor
from .htx_v5 import HtxV5Client
from .java_entry_contract import JavaEntryConfig
from .position_manager import LivePositionManager, ExitConfig
from .writer_lock import TrendWriterLock
from .live import HtxLiveExecutor, LiveAuthority
from .live_worker import (DEFAULT_TREND_CONTRACTS, LiveTrendWorker, LiveTrendWorkerConfig,
                          MultiTimeframeLiveTrendWorker)
from .market_data import HtxMarketData
from .mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect
from .strategy_platform import (DisabledStrategyWorker, ExecutionMode, MultiTimeframeObservationWorker,
                                 StrategySupervisor, TrendObservationWorker, TrendObserverConfig)
from .strategy_admission import StrategyAdmissionArtifact
from .strategy_decision import LEGACY_STRATEGY_VERSION
from .strategy_profiles import profile_for_version, profile_strategy
from .strategy_profiles import profile_entry_config
from .timeframes import TimeframeProfile
from .continuation import BASELINE, ContinuationConfig
from .live_approval import LegacyLiveWaiver, LiveRiskPolicy
from .research.store import FileEvidenceStore


def _account_client(repository: HtxTrendMySqlRepository, account_id: int, *, live: bool) -> HtxV5Client:
    record = repository.account_credentials(account_id)
    if record is None:
        raise RuntimeError(f"registered HTX account id={account_id} has no credentials")
    credentials = materialize(record, require_encrypted=live)
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


def _load_admission(strategy_version: str) -> StrategyAdmissionArtifact | None:
    if strategy_version == LEGACY_STRATEGY_VERSION:
        return None
    raw = os.environ.get("HTX_TREND_STRATEGY_ADMISSION_PATH", "").strip()
    if not raw:
        raise SystemExit(f"new strategy {strategy_version} requires HTX_TREND_STRATEGY_ADMISSION_PATH")
    from .admission import validate_admission
    path = Path(raw)
    if json.loads(path.read_text()).get("schema") == "htx-admission-g2":
        artifact = validate_admission(path, "OBSERVE")[1]
    else:
        artifact = StrategyAdmissionArtifact.load(path)
    if artifact.strategy_version != strategy_version:
        raise SystemExit("strategy runtime and admission artifact versions differ")
    return artifact


def _validate_live_approval(instance, account, admission, scan_configs) -> None:
    from .live_approval import validate_margin_config
    validate_margin_config(ExecutionConfig.from_env())
    approved_risk = LiveRiskPolicy()
    execution_risk = ExecutionConfig.from_env()
    entry_risk = EntryExecutionConfig.from_env()
    if execution_risk.max_risk_per_trade_rate > approved_risk.per_trade_equity_rate:
        raise SystemExit("LIVE per-trade stop risk exceeds approved 0.5% equity")
    if execution_risk.max_total_open_risk_rate > approved_risk.total_open_equity_rate:
        raise SystemExit("LIVE total open stop risk exceeds approved 1.5% equity")
    if entry_risk.max_active_positions > approved_risk.max_active_positions:
        raise SystemExit("LIVE active-position limit exceeds approved maximum of three")
    if any(item.max_active_positions > approved_risk.max_active_positions for item in scan_configs):
        raise SystemExit("LIVE scan capacity exceeds approved maximum of three")
    package_sha = os.environ.get("HTX_PYTHON_PACKAGE_SHA256", "").strip()
    source_commit = os.environ.get("HTX_PYTHON_SOURCE_COMMIT", "").strip()
    config_fingerprint = (instance.config_fingerprint or "").strip()
    profiles = tuple(item.timeframe_profile.value for item in scan_configs if item.entry_enabled)
    from .product_live_waiver import assert_no_dual_live_authorization
    from .admission import runtime_guard
    waiver_path = os.environ.get("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", "").strip()
    if waiver_path:
        assert_no_dual_live_authorization()
    approvals = [runtime_guard(instance, account, enabled_profile)[1]
                 for enabled_profile in profiles]
    if not profiles:
        # Management-only still needs package/account authority for every managed
        # profile. Disabling entries never removes authority checks for reductions.
        profiles=tuple(item.timeframe_profile.value for item in scan_configs)
        approvals=[runtime_guard(instance,account,profile)[1] for profile in profiles]
        if not profiles:
            raise ValueError('LIVE requires a managed profile')
    if instance.strategy_name == LEGACY_STRATEGY_VERSION:
        path = os.environ.get("HTX_TREND_LEGACY_LIVE_WAIVER_PATH", "").strip()
        if not path:
            raise SystemExit("legacy LIVE requires HTX_TREND_LEGACY_LIVE_WAIVER_PATH")
        waiver = LegacyLiveWaiver.load(Path(path))
        expected = {
            "source_commit": source_commit,
            "package_sha256": package_sha,
            "config_fingerprint": config_fingerprint,
            "account_id": account.account_id,
        }
        for name, value in expected.items():
            if getattr(waiver, name) != value:
                raise SystemExit(f"legacy LIVE waiver {name} mismatch")
        if set(profiles) - set(waiver.profiles):
            raise SystemExit("legacy LIVE waiver does not authorize every enabled profile")
        return
    if waiver_path:
        if admission is None:
            raise SystemExit("new strategy LIVE with ProductLiveWaiver still requires strategy admission artifact for decision surfaces")
        approval = approvals[0]
        profile = profile_for_version(instance.strategy_name)
        if profile is None or len(profiles) != 1 or profiles[0] != profile.value:
            raise SystemExit("new strategy LIVE instance must own exactly its versioned profile")
        spec = profile_strategy(profile, strategy_version=instance.strategy_name)
        expected = {
            "strategy_version": instance.strategy_name,
            "timeframe_profile": profile.value,
            "source_commit": source_commit,
            "package_sha256": package_sha,
            "config_fingerprint": config_fingerprint,
            "strategy_spec_hash": spec.spec_hash,
            "account_id": account.account_id,
        }
        for name, value in expected.items():
            if getattr(approval, name) != value:
                raise SystemExit(f"ProductLiveWaiver {name} mismatch")
        return
    path = os.environ.get("HTX_TREND_LIVE_APPROVAL_PATH", "").strip()
    if not path or admission is None:
        raise SystemExit("new strategy LIVE requires approval and sealed admission")
    approval = approvals[0]
    profile = profile_for_version(instance.strategy_name)
    if profile is None or len(profiles) != 1 or profiles[0] != profile.value:
        raise SystemExit("new strategy LIVE instance must own exactly its versioned profile")
    spec = profile_strategy(profile, strategy_version=instance.strategy_name)
    expected = {
        "strategy_version": instance.strategy_name,
        "timeframe_profile": profile.value,
        "source_commit": source_commit,
        "package_sha256": package_sha,
        "config_fingerprint": config_fingerprint,
        "strategy_spec_hash": spec.spec_hash,
        "admission_hash": admission.artifact_hash,
        "account_id": account.account_id,
    }
    for name, value in expected.items():
        if getattr(approval, name) != value:
            raise SystemExit(f"LIVE approval {name} mismatch")


def main() -> None:
    parser = argparse.ArgumentParser(description="CoinStrategy independently deployed Python strategy worker")
    parser.add_argument("--strategy-instance", required=True)
    parser.add_argument("--once", action="store_true", help="run one scan cycle")
    parser.add_argument("--interval-seconds", type=int, default=45)
    args = parser.parse_args()
    repository = HtxTrendMySqlRepository(connect(MySqlSettings.from_env()))
    instance = None
    writer_lock = None
    evidence_store = None
    started = False
    paper_worker = None
    paper_client = None
    backfill = None
    continuity = None
    persistent_worker = None
    stop_event = Event()
    old_handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGTERM, signal.SIGINT)}
    for sig in old_handlers:
        signal.signal(sig, lambda _sig, _frame: stop_event.set())
    try:
        repository.validate_runtime_schema()
        instance = repository.runtime_instance(args.strategy_instance)
        if instance is None:
            raise SystemExit(f"strategy instance not found: {args.strategy_instance}")
        if not instance.enabled:
            raise SystemExit(f"strategy instance is disabled: {args.strategy_instance}")
        family = instance.strategy_family.upper()
        admission = _load_admission(instance.strategy_name) if family == "TREND" and instance.execution_mode != ExecutionMode.PAPER else None
        compact = None
        from .realtime import VERSION as RT_VERSION
        if instance.strategy_name == RT_VERSION and instance.execution_mode == ExecutionMode.OBSERVE:
            raise SystemExit("realtime shadow uses PAPER with HTX_RT_ENTRY_ENABLED=false; candle OBSERVE is unsupported")
        if family == "TREND":
            paper_format = os.environ.get("HTX_PAPER_EVIDENCE_FORMAT", "legacy-v1") if instance.execution_mode == ExecutionMode.PAPER else "legacy-v1"
            if instance.strategy_name == RT_VERSION:
                paper_format = "compact-v2"
            if paper_format not in {"legacy-v1", "compact-v2"}:
                raise SystemExit("unknown PAPER evidence format")
            evidence_store = FileEvidenceStore(Path(os.environ.get(
                "HTX_PYTHON_EVIDENCE_DIR", "./research-data/evidence")), compress=paper_format == "compact-v2")
            if paper_format == "compact-v2":
                from .research.compact import CompactEvidence
                compact = CompactEvidence(evidence_store)
        if family == "TREND" and instance.execution_mode == ExecutionMode.OBSERVE:
            contracts = tuple(dict.fromkeys(item.strip().upper() for item in os.environ.get(
                "HTX_TREND_CONTRACTS", ",".join(DEFAULT_TREND_CONTRACTS)).split(",") if item.strip()))
            market_client = HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL", "https://api.hbdm.com"))
            market = HtxMarketData(market_client, evidence_store=evidence_store)
            observer_configs = TrendObserverConfig.profiles_from_env(contracts)
            observe_profile = profile_for_version(instance.strategy_name)
            if observe_profile is not None:
                observer_configs = tuple(item for item in observer_configs
                                         if TimeframeProfile.for_period(item.entry_period) is observe_profile)
                if len(observer_configs) != 1:
                    raise SystemExit(f"{instance.strategy_name} OBSERVE must configure exactly {observe_profile.value}")
            observer_workers = tuple(
                TrendObservationWorker(
                    instance, repository, market, config,
                    entry_config=(profile_entry_config(TimeframeProfile.for_period(config.entry_period))
                                  if profile_for_version(instance.strategy_name) is not None
                                  else JavaEntryConfig.from_env()),
                    admission=admission,
                )
                for config in observer_configs)
            worker = (observer_workers[0] if len(observer_workers) == 1
                      else MultiTimeframeObservationWorker(observer_workers))
        elif family == "TREND" and instance.execution_mode == ExecutionMode.PAPER:
            from .paper_worker import PaperTrendWorker, PaperWorkerConfig
            paper_client = HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL", "https://api.hbdm.com"))
            from .holding_adoption import HoldingAdoption
            adoption_path = os.environ.get('HTX_HOLDING_ADOPTION_PATH', '').strip()
            holding_adoption = HoldingAdoption.read(Path(adoption_path)) if adoption_path else None
            if instance.strategy_name == RT_VERSION:
                from .realtime.factory import paper as build_realtime_paper, BreadthContext
                worker = paper_worker = build_realtime_paper(instance,
                    HtxMarketData(paper_client,evidence_store=evidence_store,compact=compact),
                    Path(os.environ.get("HTX_PAPER_STATE_DIR", "./paper-state")),evidence_store,
                    compact=compact,holding_adoption=holding_adoption,
                    context_provider=BreadthContext(repository,LiveTrendWorkerConfig.profiles_from_env(strategy_version=RT_VERSION)[0]))
            else:
                worker = paper_worker = PaperTrendWorker(instance,
                    HtxMarketData(paper_client, evidence_store=evidence_store, compact=compact),
                    Path(os.environ.get("HTX_PAPER_STATE_DIR", "./paper-state")), PaperWorkerConfig.from_env(),
                    evidence_store=evidence_store, holding_adoption=holding_adoption, require_holding_policy=True)
                if compact is not None:
                    from .research.compact import CompactBackfill
                    periods = worker.profile.contract
                    backfill = CompactBackfill(compact,
                        HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL", "https://api.hbdm.com")),
                        worker.config.contracts, (periods.entry_period, periods.trend_period))
                    backfill.start()
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
            scan_configs = LiveTrendWorkerConfig.profiles_from_env(strategy_version=instance.strategy_name)
            live_continuation = None
            if instance.strategy_name in ('HTX_TREND_15M_V2',RT_VERSION):
                live_continuation = (ContinuationConfig('linked-v1') if instance.strategy_name == RT_VERSION
                                     else ContinuationConfig.from_live_env())
                for scan_config in scan_configs:
                    if not scan_config.entry_enabled:
                        continue
                    live_continuation.require_15m(
                        scan_config.timeframe_profile is TimeframeProfile.INTRADAY_15M, live=True)
            _validate_live_approval(instance, account, admission, scan_configs)
            writer_lock = TrendWriterLock(connect(MySqlSettings.from_env()))
            writer_lock.acquire()
            private_client = _account_client(repository, account.account_id, live=True)
            market = HtxMarketData(private_client, evidence_store=evidence_store)
            live = HtxLiveExecutor(private_client, LiveAuthority(True, str(account.account_id)), str(account.account_id), writer_guard=writer_lock.assert_owned)
            # A LIVE worker cannot opt out of the account-level guardrails.
            # Research/PAPER callers may keep the Java-compatible relaxed
            # sizing path, while real execution enforces per-trade risk and
            # position-count boundaries, plus the formal 10% margin cap.
            entry_execution = replace(ExecutionConfig.from_env(), hard_risk_limits_enabled=True)
            entry_config = EntryExecutionConfig.from_env()
            logging.getLogger(__name__).warning(
                "LIVE entry configuration instance=%s account_id=%s strategy=%s order_type=%s "
                "entry_plan=%s continuation=%s maker_timeout=%s wait_bars=%s",
                instance.instance_key, account.account_id, instance.strategy_name,
                entry_config.entry_order_type, entry_execution.entry_plan_version,
                live_continuation.version if live_continuation else BASELINE, entry_config.maker_config.timeout_action.value,
                entry_config.maker_config.max_wait_bars)

            from .holding_policy import HoldingPolicy
            from .holding_adoption import HoldingAdoption
            adoption_path = os.environ.get('HTX_HOLDING_ADOPTION_PATH', '').strip()
            holding_adoption = HoldingAdoption.read(Path(adoption_path)) if adoption_path else None
            exit_config = ExitConfig.from_env()
            holding_policies = {config.timeframe_profile.value: HoldingPolicy.create(
                instance_key=instance.instance_key, account_id=account.account_id,
                strategy_family=instance.strategy_family, strategy_name=instance.strategy_name,
                mode='LIVE', profile=config.timeframe_profile, entry_config=config.java_entry,
                exit_config=exit_config,
                continuation_version=(live_continuation.version if live_continuation is not None
                                      and config.timeframe_profile is TimeframeProfile.INTRADAY_15M else BASELINE))
                for config in scan_configs}
            entry = HtxTrendEntryExecutor(repository=repository, instance=instance, account=account,
                facts=HtxAccountFactsAdapter(private_client, AccountFactConfig(leverage=entry_config.leverage)), executor=live, execution=entry_execution,
                config=entry_config, admission=admission, evidence_store=evidence_store, holding_policies=holding_policies)
            from .ownership import owners_from_env, OwnedPositionManagers
            owners = owners_from_env(instance)
            entry._managed_instances = owners
            profile_workers = []
            for scan_config in scan_configs:
                manager = LivePositionManager(repository, instance, account, market, private_client, live,
                    period=scan_config.entry_period, timeframe_profile=scan_config.timeframe_profile,
                    java_config=scan_config.java_entry, config=exit_config, evidence_store=evidence_store,
                    require_holding_policy=True, holding_adoption=holding_adoption,
                    continuation_config=(live_continuation if scan_config.timeframe_profile is TimeframeProfile.INTRADAY_15M
                                         else ContinuationConfig(BASELINE)))
                if instance.strategy_name == RT_VERSION:
                    from .realtime.factory import live as build_realtime_live
                    from .admission import runtime_guard
                    paper_client=HtxV5Client(base_url=os.environ.get("HTX_PUBLIC_BASE_URL","https://api.hbdm.com"))
                    public=HtxMarketData(paper_client,evidence_store=evidence_store,compact=compact)
                    worker=build_realtime_live(instance,entry,manager,owners,public,evidence_store,
                        contracts=scan_config.contracts,execution=entry_execution,leverage=entry_config.leverage,
                        policy=holding_policies[scan_config.timeframe_profile.value],guard=writer_lock.assert_owned,
                        admission_guard=lambda: runtime_guard(instance,account,'INTRADAY_15M'),scan_config=scan_config)
                    break
                profile_workers.append(LiveTrendWorker(instance, repository, market, entry, scan_config,
                    position_manager=OwnedPositionManagers(manager, owners, {c.timeframe_profile for c in scan_configs}), writer_guard=writer_lock.assert_owned, admission=admission))
            for profile_worker in profile_workers:
                profile_worker._owned_strategy_names=tuple(owner.strategy_name for owner in owners)
            if holding_adoption is not None:
                adopted_owners={h['policy']['owner']['strategy_name'] for h in holding_adoption.to_dict()['holdings']}
                if adopted_owners-set(owner.strategy_name for owner in owners):
                    raise SystemExit('adopted old strategy requires an explicit managed alias')
            if instance.strategy_name != RT_VERSION:
                worker = (profile_workers[0] if len(profile_workers) == 1
                          else MultiTimeframeLiveTrendWorker(tuple(profile_workers)))
        elif family in {"CARRY_SINGLE", "CARRY_CROSS"}:
            worker = DisabledStrategyWorker(instance, repository)
        else:
            raise SystemExit(f"unsupported strategy family/mode: {instance.strategy_family}/{instance.execution_mode}")
        if instance.strategy_name == RT_VERSION and compact is not None:
            from .research.compact import CompactBackfill
            backfill = CompactBackfill(compact,
                HtxV5Client(base_url=os.environ.get('HTX_PUBLIC_BASE_URL', 'https://api.hbdm.com')),
                worker.loader.contracts, ('15min', '60min', '4hour'))
            backfill.start()
        if family == 'TREND':
            from .runtime_checkpoint import RuntimeCheckpoint, PersistentWorker
            state_dir = os.environ.get('HTX_RUNTIME_STATE_DIR', '').strip()
            if not state_dir:
                raise SystemExit('HTX_RUNTIME_STATE_DIR must name a stable absolute directory outside releases')
            if Path(state_dir).resolve().is_relative_to(Path(__file__).resolve().parents[2]):
                raise SystemExit('runtime state directory cannot be inside a release package')
            if instance.execution_mode in ('LIVE','PAPER') and holding_adoption is not None:
                header=holding_adoption.to_dict()
                expected_account='paper:'+instance.instance_key if instance.execution_mode=='PAPER' else str(instance.account_id)
                if (header['instance_key']!=instance.instance_key or header['account_id']!=expected_account
                        or header['mode']!=instance.execution_mode):
                    raise SystemExit('adoption manifest instance/account/mode mismatch')
            initialize=os.environ.get('HTX_CONTINUITY_INITIALIZE','').lower() == 'true'
            if initialize and instance.execution_mode in ('LIVE','PAPER') and holding_adoption is None:
                raise SystemExit('first continuity initialization requires an old-instance adoption manifest, including an empty inventory')
            continuity = RuntimeCheckpoint(Path(state_dir), dict(instance_key=instance.instance_key,
                account_id=('paper:'+instance.instance_key if instance.execution_mode == 'PAPER'
                            else str(getattr(instance,'account_id',None))), mode=instance.execution_mode,
                strategy_family=instance.strategy_family),
                initialize=initialize)
            persistent_worker = PersistentWorker(worker, continuity, stop_event)
            worker = persistent_worker
        repository.mark_runtime_started(instance.instance_key)
        started = True
        supervisor = StrategySupervisor(repository, {instance.instance_key: worker})
        while not stop_event.is_set():
            supervisor.tick_all()
            if args.once:
                return
            stop_event.wait(.05 if instance.strategy_name == RT_VERSION else max(5, args.interval_seconds))
    finally:
        try:
            if persistent_worker is not None:
                persistent_worker.stop()
            if started:
                repository.mark_runtime_stopped(instance.instance_key)
        finally:
            # Every resource is closed even if an earlier close/checkpoint fails.
            # Writer ownership is released after the trading resources stop.
            with ExitStack() as cleanup:
                for sig, handler in old_handlers.items():
                    cleanup.callback(signal.signal, sig, handler)
                if continuity is not None:
                    cleanup.callback(continuity.close)
                if writer_lock is not None:
                    cleanup.callback(writer_lock.close)
                cleanup.callback(repository.close)
                if evidence_store is not None:
                    cleanup.callback(evidence_store.close)
                if paper_client is not None:
                    cleanup.callback(paper_client.close)
                if paper_worker is not None:
                    cleanup.callback(paper_worker.stop)
                if backfill is not None:
                    cleanup.callback(backfill.close)


if __name__ == "__main__":
    main()
