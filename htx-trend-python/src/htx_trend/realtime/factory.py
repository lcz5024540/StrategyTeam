"""Explicit realtime construction; no strategy entry activation by installation."""
from dataclasses import asdict, replace
from decimal import Decimal as D
from pathlib import Path
import json
import os
from datetime import datetime, UTC

from ..continuation import ContinuationConfig, LINKED
from ..execution import ExecutionConfig
from ..exit_config import ExitConfig
from ..holding_policy import HoldingPolicy, paper_exit_config
from ..htx_v5 import HtxV5Client
from ..market_data import HtxMarketData
from ..paper_worker import PaperFileLedger, PaperWorkerConfig, WorkerPaperExchange
from ..runtime import PaperTrendRuntime
from ..strategy_profiles import profile_entry_config
from ..timeframes import TimeframeProfile
from . import VERSION
from .brokers import PaperBroker, LiveBroker, RealtimePaperExchange
from .feed import PublicStream
from .management import PaperManagement, LiveManagement
from .plans import Parameters, TriggerEngine
from .storage import PaperPlanStore, MySqlPlanStore, SegmentRecorder
from .worker import RealtimeWorker, FrameLoader, MarketView


class BreadthContext:
    """Use the existing breadth interpretation; anchors do not affect exit.opposed()."""
    def __init__(self,repository,config):
        self.repository,self.config=repository,config
        self.last_bar=None;self.value=None

    def __call__(self,now_ms):
        from ..live_worker import LiveTrendWorker
        from ..strategy_context import MarketRegime
        bar=now_ms//900000
        if self.last_bar!=bar:
            stats=(self.repository.latest_breadth_stats(datetime.fromtimestamp(
                now_ms/1000-self.config.market_breadth_lookback_seconds,UTC))
                if self.config.adaptive_sizing_enabled else None)
            self.value=LiveTrendWorker.market_context_from_breadth(
                total_count=stats.total_count if stats else 0,
                long_context_count=stats.long_context_count if stats else 0,
                short_context_count=stats.short_context_count if stats else 0,
                anchor_regime=MarketRegime.MIXED,config=self.config)
            self.last_bar=bar
        return self.value


def parameters():
    return Parameters(D(os.environ.get('HTX_RT_BAND_ATR', '.10')), D(os.environ.get('HTX_RT_MIN_NET_R', '1.5')))


def _common(instance, engine, broker, public, frames, contracts, evidence, *, execution,
            leverage, policy, entry_config, management, guard=lambda: None, admission_guard=lambda: None):
    root=Path(os.environ.get('HTX_RT_CAPTURE_DIR', '')).expanduser()
    if not root.is_absolute():
        raise ValueError('HTX_RT_CAPTURE_DIR must be an independent absolute persistent path')
    recorder=SegmentRecorder(root, max_bytes=int(os.environ.get('HTX_RT_CAPTURE_MAX_BYTES', str(2*1024**3))))
    loader=FrameLoader(public, contracts, frames)
    stream=PublicStream(contracts)
    worker=RealtimeWorker(instance, engine, broker, management, stream, loader, public, frames, recorder, evidence,
        parameters=parameters(),execution=execution,leverage=leverage,
        entry_enabled=os.environ.get('HTX_RT_ENTRY_ENABLED', '').lower()=='true',
        guard=guard,admission_guard=admission_guard,holding_parameters=policy.to_dict(),entry_config=entry_config)
    return worker


def paper(instance, public, state_root, evidence, *, compact=None, holding_adoption=None, context_provider=None):
    if instance.strategy_name!=VERSION or instance.execution_mode!='PAPER':
        raise ValueError('realtime PAPER ownership mismatch')
    config=PaperWorkerConfig.from_env()
    path=state_root/(instance.instance_key+'.json')
    identity=dict(instance=instance.instance_key,strategy=instance.strategy_name,
                  config=json.loads(json.dumps(asdict(config),default=str)))
    if path.exists():
        original=json.loads(path.read_text())['identity']
        aliases=json.loads(os.environ.get('HTX_MANAGED_PREVIOUS_STRATEGIES','[]'))
        if original['strategy'] not in (VERSION,*aliases):
            raise ValueError('previous PAPER strategy needs an explicit managed alias')
        identity['strategy']=original['strategy']
    ledger=PaperFileLedger(path,identity)
    entry_config=profile_entry_config(TimeframeProfile.INTRADAY_15M)
    execution=replace(ExecutionConfig.from_env(),account_equity_usdt=config.equity,
        max_total_margin_rate=config.margin_rate,max_active_positions=config.max_active_positions,
        max_same_direction_positions=config.max_active_positions,hard_risk_limits_enabled=True)
    policy=HoldingPolicy.create(instance_key=instance.instance_key,account_id='paper:'+instance.instance_key,
        strategy_family=instance.strategy_family,strategy_name=VERSION,mode='PAPER',
        profile=TimeframeProfile.INTRADAY_15M,entry_config=entry_config,exit_config=ExitConfig.from_env(),continuation_version=LINKED)
    engine=TriggerEngine(PaperPlanStore(ledger),instance.instance_key,parameters=parameters())
    broker=PaperBroker(ledger,engine,'paper:'+instance.instance_key,
        latency_ms=int(os.environ.get('HTX_RT_PAPER_LATENCY_MS','250')))
    runtime=PaperTrendRuntime(ledger,RealtimePaperExchange(ledger),account_id=broker.account,
        entry_config=entry_config,execution_config=execution,strategy_version=VERSION,
        continuation_config=ContinuationConfig(LINKED),holding_policy=policy,require_holding_policy=True,
        evidence_context={'instance':instance.instance_key,'timeframe_profile':'INTRADAY_15M'},
        evidence_store=evidence,compact=compact)
    frames={};view=MarketView(public,frames,engine.books)
    management=PaperManagement(runtime,broker,frames,view,exit_config=policy.exit,entry_config=entry_config,
                               evidence_store=evidence,holding_adoption=holding_adoption,context_provider=context_provider)
    contracts=tuple(dict.fromkeys((*config.contracts,*(p.contract_code for p in ledger.active_positions(broker.account)))))
    return _common(instance,engine,broker,public,frames,contracts,evidence,execution=execution,
                   leverage=config.leverage,policy=policy,entry_config=entry_config,management=management)


def live(instance, entry, manager, owners, public, evidence, *, contracts, execution, leverage, policy,
         guard, admission_guard, scan_config):
    if instance.strategy_name!=VERSION or instance.execution_mode!='LIVE':
        raise ValueError('realtime LIVE ownership mismatch')
    engine=TriggerEngine(MySqlPlanStore(entry._repository._connection),instance.instance_key,parameters=parameters())
    broker=LiveBroker(entry,engine,guard=guard)
    frames={};view=MarketView(public,frames,engine.books)
    manager.market=view
    management=LiveManagement(broker,manager,view,owners=owners,context_provider=BreadthContext(broker.repo,scan_config))
    current=tuple(row['contract_code'] for owner in owners for row in broker.repo.managed_positions(owner))
    return _common(instance,engine,broker,public,frames,tuple(dict.fromkeys((*contracts,*current))),evidence,
        execution=execution,leverage=leverage,policy=policy,entry_config=policy.entry,
        management=management,guard=guard,admission_guard=admission_guard)
