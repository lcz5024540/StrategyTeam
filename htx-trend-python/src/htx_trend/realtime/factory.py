"""Explicit realtime construction; no strategy entry activation by installation."""

import json
import os
from dataclasses import asdict, replace
from datetime import UTC, datetime
from decimal import Decimal as D
from pathlib import Path

from ..continuation import LINKED, ContinuationConfig
from ..execution import ExecutionConfig
from ..exit_config import ExitConfig
from ..holding_policy import HoldingPolicy
from ..paper_worker import PaperFileLedger, PaperWorkerConfig
from ..runtime import PaperTrendRuntime
from ..strategy_profiles import profile_entry_config
from ..timeframes import TimeframeProfile
from . import VERSION
from .brokers import LiveBroker, PaperBroker, RealtimePaperExchange
from .feed import PublicStream
from .management import LiveManagement, PaperManagement
from .plans import ORIGINAL, Parameters, create_engine
from .storage import MySqlPlanStore, PaperPlanStore, SegmentRecorder
from .worker import FrameLoader, MarketView, RealtimeWorker


class IdleManagementStream:
    """A verified empty management inventory needs no public universe subscription."""
    connected = False
    epoch = "MANAGEMENT_EMPTY"

    def start(self):
        pass

    def drain(self):
        return ()

    def close(self):
        pass


class BreadthContext:
    """Use the existing breadth interpretation; anchors do not affect exit.opposed()."""

    def __init__(self, repository, config):
        self.repository, self.config = repository, config
        self.last_bar = None
        self.value = None

    def __call__(self, now_ms):
        from ..live_worker import LiveTrendWorker
        from ..strategy_context import MarketRegime

        bar = now_ms // 900000
        if self.last_bar != bar:
            stats = (
                self.repository.latest_breadth_stats(
                    datetime.fromtimestamp(
                        now_ms / 1000 - self.config.market_breadth_lookback_seconds, UTC
                    )
                )
                if self.config.adaptive_sizing_enabled
                else None
            )
            self.value = LiveTrendWorker.market_context_from_breadth(
                total_count=stats.total_count if stats else 0,
                long_context_count=stats.long_context_count if stats else 0,
                short_context_count=stats.short_context_count if stats else 0,
                anchor_regime=MarketRegime.MIXED,
                config=self.config,
            )
            self.last_bar = bar
        return self.value


def parameters():
    if os.environ.get("HTX_RT_CANDIDATE") == "REGIME_AB_V1":
        threshold = os.environ.get("HTX_REGIME_BAYES_THRESHOLD", "")
        return Parameters(min_net_r=D(0), candidate="REGIME_AB_V1",
            target_mode=os.environ.get("HTX_REGIME_EXIT", ""),
            regime_timing=os.environ.get("HTX_REGIME_TIMING", "WINDOW"),
            bayes_threshold=D(threshold) if threshold else None,
            bayes_snapshot_hash=os.environ.get("HTX_REGIME_SNAPSHOT_SHA256", ""),
            bayes_cost_id=os.environ.get("HTX_REGIME_COST_ID", ""))
    if os.environ.get("HTX_RT_CANDIDATE") == "INDICATOR_UNION_V1":
        return Parameters(min_net_r=D(0), candidate="INDICATOR_UNION_V1", target_mode=os.environ.get("HTX_UNION_EXIT", ""))
    return Parameters(
        D(os.environ.get("HTX_RT_BAND_ATR", ".10")),
        D(os.environ.get("HTX_RT_MIN_NET_R", "1.5")),
        candidate=os.environ.get("HTX_RT_CANDIDATE", ORIGINAL),
        target_mode=os.environ.get("HTX_RT_TARGET_MODE", "J1"),
    )


def _common(
    instance,
    engine,
    broker,
    public,
    frames,
    contracts,
    evidence,
    *,
    execution,
    leverage,
    policy,
    entry_config,
    management,
    guard=lambda: None,
    admission_guard=lambda: None,
):
    if engine.parameters.candidate == "REGIME_AB_V1":
        from .regime_bayes import bind_snapshot
        bind_snapshot(engine, os.environ.get("HTX_REGIME_SNAPSHOT", ""), live=isinstance(management, LiveManagement))
    root = Path(os.environ.get("HTX_RT_CAPTURE_DIR", "")).expanduser()
    if not root.is_absolute():
        raise ValueError(
            "HTX_RT_CAPTURE_DIR must be an independent absolute persistent path"
        )
    recorder = SegmentRecorder(
        root,
        max_bytes=int(os.environ.get("HTX_RT_CAPTURE_MAX_BYTES", str(2 * 1024**3))),
        segmented=os.environ.get("HTX_RT_CAPTURE_SEGMENTED", "").lower() == "true",
        segment_contract="capture-runtime",
    )
    if isinstance(management, LiveManagement):
        management.coverage_recorder = recorder
        management.coverage_evidence = evidence
    periods = ("15min", "60min") if engine.parameters.candidate == "REGIME_AB_V1" else ("15min", "60min", "4hour")
    loader = FrameLoader(public, contracts, frames, periods=periods)
    stream = PublicStream(contracts)
    if isinstance(management, LiveManagement):
        management.coverage_stream = stream
    worker = RealtimeWorker(
        instance,
        engine,
        broker,
        management,
        stream,
        loader,
        public,
        frames,
        recorder,
        evidence,
        parameters=parameters(),
        execution=execution,
        leverage=leverage,
        entry_enabled=os.environ.get("HTX_RT_ENTRY_ENABLED", "").lower() == "true",
        guard=guard,
        admission_guard=admission_guard,
        holding_parameters=policy.to_dict(),
        entry_config=entry_config,
    )
    if engine.parameters.candidate == "REGIME_AB_V1" and hasattr(broker, 'ledger'):
        from .cost_observation import CostObserver
        from .cost_sources import PaperCostReader
        worker.cost_observer = CostObserver(PaperCostReader(broker.ledger, broker.account), evidence, asynchronous=False)
    return worker


def paper(
    instance,
    public,
    state_root,
    evidence,
    *,
    compact=None,
    holding_adoption=None,
    context_provider=None,
):
    if instance.strategy_name != VERSION or instance.execution_mode != "PAPER":
        raise ValueError("realtime PAPER ownership mismatch")
    config = PaperWorkerConfig.from_env()
    path = state_root / (instance.instance_key + ".json")
    identity = dict(
        instance=instance.instance_key,
        strategy=instance.strategy_name,
        config=json.loads(json.dumps(asdict(config), default=str)),
    )
    if path.exists():
        original = json.loads(path.read_text())["identity"]
        aliases = json.loads(os.environ.get("HTX_MANAGED_PREVIOUS_STRATEGIES", "[]"))
        if original["strategy"] not in (VERSION, *aliases):
            raise ValueError("previous PAPER strategy needs an explicit managed alias")
        identity["strategy"] = original["strategy"]
    ledger = PaperFileLedger(path, identity)
    entry_config = profile_entry_config(TimeframeProfile.INTRADAY_15M)
    execution = replace(
        ExecutionConfig.from_env(),
        account_equity_usdt=config.equity,
        max_total_margin_rate=config.margin_rate,
        max_active_positions=config.max_active_positions,
        max_same_direction_positions=config.max_active_positions,
        hard_risk_limits_enabled=True,
    )
    policy = HoldingPolicy.create(
        instance_key=instance.instance_key,
        account_id="paper:" + instance.instance_key,
        strategy_family=instance.strategy_family,
        strategy_name=VERSION,
        mode="PAPER",
        profile=TimeframeProfile.INTRADAY_15M,
        entry_config=entry_config,
        exit_config=ExitConfig.from_env(),
        continuation_version=LINKED,
    )
    engine = create_engine(
        PaperPlanStore(ledger), instance.instance_key, parameters=parameters()
    )
    broker = PaperBroker(
        ledger,
        engine,
        "paper:" + instance.instance_key,
        latency_ms=int(os.environ.get("HTX_RT_PAPER_LATENCY_MS", "250")),
    )
    runtime = PaperTrendRuntime(
        ledger,
        RealtimePaperExchange(ledger),
        account_id=broker.account,
        entry_config=entry_config,
        execution_config=execution,
        strategy_version=VERSION,
        continuation_config=ContinuationConfig(LINKED),
        holding_policy=policy,
        require_holding_policy=True,
        evidence_context={
            "instance": instance.instance_key,
            "timeframe_profile": "INTRADAY_15M",
        },
        evidence_store=evidence,
        compact=compact,
    )
    frames = {}
    view = MarketView(public, frames, engine.books)
    management = PaperManagement(
        runtime,
        broker,
        frames,
        view,
        exit_config=policy.exit,
        entry_config=entry_config,
        evidence_store=evidence,
        holding_adoption=holding_adoption,
        context_provider=context_provider,
    )
    contracts = tuple(
        dict.fromkeys(
            (
                *config.contracts,
                *(p.contract_code for p in ledger.active_positions(broker.account)),
            )
        )
    )
    return _common(
        instance,
        engine,
        broker,
        public,
        frames,
        contracts,
        evidence,
        execution=execution,
        leverage=config.leverage,
        policy=policy,
        entry_config=entry_config,
        management=management,
    )


def live(
    instance,
    entry,
    manager,
    owners,
    public,
    evidence,
    *,
    contracts,
    execution,
    leverage,
    policy,
    guard,
    admission_guard,
    scan_config,
):
    if instance.strategy_name != VERSION or instance.execution_mode != "LIVE":
        raise ValueError("realtime LIVE ownership mismatch")
    from .trial_admission import BINDINGS, configured, runtime_authority

    trial_approval = None
    if configured():
        trial_approval, trial_limits = runtime_authority(
            instance,
            entry._account,
            policy=policy,
            contracts=contracts,
            parameters=parameters(),
            entry=os.environ.get("HTX_RT_ENTRY_ENABLED", "").lower() == "true",
        )
        admission_guard = lambda: runtime_authority(
            instance,
            entry._account,
            policy=policy,
            contracts=contracts,
            parameters=parameters(),
            entry=True,
        )
    engine = create_engine(
        MySqlPlanStore(entry._repository._connection),
        instance.instance_key,
        parameters=parameters(),
    )
    broker = LiveBroker(entry, engine, guard=guard)
    frames = {}
    view = MarketView(public, frames, engine.books)
    manager.market = view
    management = LiveManagement(
        broker,
        manager,
        view,
        owners=owners,
        context_provider=BreadthContext(broker.repo, scan_config),
    )
    holdings = tuple(
        row for owner in owners for row in broker.repo.managed_positions(owner)
    )
    if any(
        TimeframeProfile(row.get("timeframe_profile") or "INTRADAY_15M")
        is not TimeframeProfile.INTRADAY_15M
        for row in holdings
    ):
        raise ValueError(
            "realtime 15m cannot adopt a holding with an unmanaged timeframe"
        )
    current = tuple(row["contract_code"] for row in holdings)
    worker = _common(
        instance,
        engine,
        broker,
        public,
        frames,
        tuple(dict.fromkeys((*contracts, *current))),
        evidence,
        execution=execution,
        leverage=leverage,
        policy=policy,
        entry_config=policy.entry,
        management=management,
        guard=guard,
        admission_guard=admission_guard,
    )

    if trial_approval is not None:
        from ..mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect
        from ..worker import _account_client
        from .trial import MySqlTrialStore, Trial
        from .trial_facts import BackgroundTrialMonitor, LiveTrialReader

        trial_db = connect(MySqlSettings.from_env())
        worker.trial = Trial(
            MySqlTrialStore(trial_db),
            trial_approval["trial_id"],
            {k: trial_approval[k] for k in BINDINGS},
            trial_limits,
        )
        broker.trial = worker.trial
        worker.trial_monitor = BackgroundTrialMonitor(
            worker.trial,
            LiveTrialReader(
                lambda: HtxTrendMySqlRepository(connect(MySqlSettings.from_env())),
                lambda repo: _account_client(
                    repo, entry._account.account_id, live=False
                ),
                evidence,
            ),
            engine.books,
        )
        worker.management_only = trial_approval["permission"] == "MANAGEMENT"
        if worker.management_only:
            worker.loader.contracts = current
            worker.stream = PublicStream(current) if current else IdleManagementStream()
            management.coverage_stream = worker.stream
        worker.trial_connection = trial_db
    if engine.parameters.candidate == 'REGIME_AB_V1':
        from ..mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect
        from ..worker import _account_client
        from .cost_observation import CostObserver
        from .cost_sources import LiveCostReader
        worker.cost_observer = CostObserver(LiveCostReader(
            lambda: HtxTrendMySqlRepository(connect(MySqlSettings.from_env())),
            lambda repo: _account_client(repo, entry._account.account_id, live=False),
            entry._account.account_id, instance.instance_key), evidence)
    return worker
