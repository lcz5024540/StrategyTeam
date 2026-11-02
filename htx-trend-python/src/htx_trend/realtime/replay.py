"""Offline full-opportunity replay with a sealed six-choice diagnostic/holdout protocol.

Inputs are point-in-time observations, never an order-only CSV. No network, private
client or production database is constructed. Missing observations remain errors.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import asdict, replace
from datetime import UTC, datetime
from decimal import Decimal as D
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import gzip
import json

from ..continuation import ContinuationConfig, LINKED
from ..continuation_replay import configuration, events as fill_events, segments
from ..execution import ExecutionConfig
from ..exit_config import ExitConfig
from ..holding_policy import HoldingPolicy, _decode_config
from ..java_entry_contract import JavaEntryConfig
from ..maker_execution import MakerExecutionConfig
from ..market_data import Candle, ContractSpec, FundingRate, KlineWindow, OrderBook, period_seconds
from ..paper_worker import PaperTrendWorker, PaperWorkerConfig, WorkerPaperExchange
from ..research.store import primitive, digest, read_object
from ..runtime import PaperTrendRuntime
from ..scan_replay import MemoryLedger
from ..strategy_context import MarketContext
from ..timeframes import TimeframeProfile
from . import VERSION
from .brokers import PaperBroker, RealtimePaperExchange
from .feed import MarketEvent
from .management import PaperManagement
from .plans import GRID, Parameters, TriggerEngine
from .storage import PlanStore
from .worker import RealtimeWorker, MarketView

SCHEMA='htx-realtime-opportunity-tape-v1'
PROTOCOL={'grid':[asdict(p) for p in GRID],'diagnostic_fraction':'0.70',
    'selection_order':['net_desc','drawdown_asc','roundtrips_asc','band_desc','net_r_desc'],
    'holdout_reselection':False,'case_only_dates_cst':['2026-10-30','2026-10-31'],
    'roundtrip_definition':'same-side opening within 3600 seconds of prior full exit; includes profitable cycles',
    'initial_inventory':'flat; non-flat studies need a verified starting ledger adapter',
    'paper_ioc':'subsequent observed BBO after frozen latency; L1 conservative capacity'}


def iso(ms):return datetime.fromtimestamp(ms/1000,UTC).isoformat()


def decode_window(code,period,source,now_ms):
    if source['received_ms']>now_ms:raise ValueError('future candle publication')
    bars=tuple(Candle(**{k:(v if k in ('open_time','trade_count') or v is None else D(str(v)))
                        for k,v in row.items()}) for row in source['candles'])
    if any(not all(v.is_finite() and v>0 for v in (c.open,c.high,c.low,c.close))
           or c.low>min(c.open,c.close) or c.high<max(c.open,c.close) for c in bars):
        raise ValueError('invalid historical OHLC')
    if any((c.open_time+period_seconds(period))*1000>now_ms for c in bars):
        raise ValueError('forming/future candle in replay')
    window=KlineWindow(code,period,bars,160)
    if len(bars)<160 or not window.is_complete:raise ValueError('incomplete historical candle window')
    return window


def validate(tape):
    if tape.get('schema')!=SCHEMA:raise ValueError('unsupported realtime tape')
    if not tape.get('observations'):raise ValueError('no full-opportunity observations')
    if tape.get('provenance') not in ('OBSERVED','ASSUMPTION_ONLY'):raise ValueError('unknown provenance')
    config=tape['configuration']
    if digest(config)!=tape['configuration_hash']:raise ValueError('configuration hash mismatch')
    worker=configuration(PaperWorkerConfig,config['worker'])
    execution=configuration(ExecutionConfig,config['execution'])
    configuration(JavaEntryConfig,config['entry']);configuration(MakerExecutionConfig,config['maker'])
    _decode_config(ExitConfig,config['exit'])
    if worker.equity!=300 or not 0<worker.margin_rate<=D('.10') or not 0<worker.leverage<=10:
        raise ValueError('unapproved account budget')
    if not 1<=worker.max_active_positions<=5 or len(set(worker.contracts))!=len(worker.contracts):
        raise ValueError('invalid portfolio capacity or pool')
    if (execution.account_equity_usdt!=worker.equity or execution.max_active_positions!=worker.max_active_positions
            or execution.max_total_margin_rate!=worker.margin_rate or not execution.hard_risk_limits_enabled):
        raise ValueError('baseline/candidate account budget mismatch')
    latency=tape['latency']
    if type(latency['milliseconds']) is not int or not 0<=latency['milliseconds']<=2000:
        raise ValueError('invalid frozen execution latency')
    last=tape['start_ms'];seen=set();kinds=set()
    for observation in tape['observations']:
        at=observation['received_ms'];kind=observation['kind'];kinds.add(kind)
        if type(at) is not int or not last<=at<=tape['end_ms']:raise ValueError('unordered/out-of-range observations')
        last=at
        if kind in ('BBO','TRADE'):
            event=MarketEvent.load(observation)
            if not 0<=event.received_ms-event.exchange_ms:raise ValueError('exchange clock ahead of reception')
        elif kind=='FRAME':
            code=observation['contract']
            for period in ('15min','60min','4hour'):decode_window(code,period,observation['windows'][period],at)
            if observation['spec']['contract_code']!=code:raise ValueError('contract metadata mismatch')
        elif kind=='DEPTH':
            if observation['requested_ms']>at:raise ValueError('depth response precedes request')
            for side in ('bids','asks'):
                if not observation[side]:raise ValueError('empty depth')
                if any(not D(str(v)).is_finite() or D(str(v))<=0 for level in observation[side] for v in level):
                    raise ValueError('invalid depth')
        elif kind=='FUNDING_SETTLEMENT':
            if observation['event_id'] in seen:raise ValueError('duplicate settlement')
            seen.add(observation['event_id'])
            if int(observation['exchange_ms'])!=at:raise ValueError('settlement exposure time not observed')
            if D(str(observation['price']))<=0:raise ValueError('invalid settlement mark')
        elif kind not in ('SCAN','FUNDING','CONTEXT','GAP','RESTART'):
            raise ValueError('unsupported observation kind')
    if not {'FRAME','SCAN','BBO','TRADE','DEPTH','FUNDING'}<=kinds:
        raise ValueError('missing candles, actual scans, ticks, depth or funding quotes')
    return worker,execution


class FrozenMarket:
    def __init__(self):
        self.now_ms=0;self.frames={};self.depth={};self.funding={};self.books={};self.context=None

    def observe(self,observation):
        self.now_ms=observation['received_ms'];kind=observation['kind'];code=observation.get('contract')
        if kind=='FRAME':
            windows={p:decode_window(code,p,v,self.now_ms) for p,v in observation['windows'].items()}
            spec=observation['spec']
            self.frames[code]={'entry':windows['15min'],'trend':windows['60min'],'windows':windows,
                'contract':ContractSpec(code,D(spec['contract_size']),D(spec['price_tick']),spec['contract_status']),
                'received_ms':self.now_ms,'evidence_failed':False}
        elif kind=='DEPTH':self.depth[code]=observation
        elif kind=='FUNDING':self.funding[code]=observation
        elif kind=='BBO':self.books[code]=MarketEvent.load(observation)
        elif kind=='CONTEXT':
            from ..models import TrendSide
            from ..strategy_context import MarketRegime
            values=dict(observation['value'])
            for name in ('regime','anchor_regime'):
                if name in values:values[name]=MarketRegime(values[name])
            for name in ('breadth_side','btc_anchor','eth_anchor'):
                if name in values:values[name]=TrendSide(values[name])
            if 'breadth_ratio' in values:values['breadth_ratio']=D(values['breadth_ratio'])
            self.context=MarketContext(**values)

    def kline_window(self,code,period,size,**_):
        return self.frames[code]['windows'][period]

    def contract_spec(self,code):return self.frames[code]['contract']

    def order_book(self,code):
        row=self.depth.get(code)
        if row is None or not 0<=self.now_ms-row['received_ms']<=2000:
            raise ValueError('COUNTERFACTUAL_DEPTH_MISSING_OR_STALE')
        return OrderBook(tuple((D(p),D(q)) for p,q in row['bids']),tuple((D(p),D(q)) for p,q in row['asks']))

    def top_of_book(self,code):
        event=self.books.get(code)
        if event is None or not event.fresh(self.now_ms):raise ValueError('MARK_MISSING_OR_STALE')
        return OrderBook(((event.bid,event.bid_size),),((event.ask,event.ask_size),)).top

    def funding_rate(self,code):
        row=self.funding.get(code)
        if row is None:raise ValueError('FUNDING_QUOTE_MISSING')
        return FundingRate(code,D(row['rate']),row.get('funding_time'))


class ReplayEvidence:
    def __init__(self):self.objects={}
    def put(self,kind,payload,*,refs=()):
        key=digest({'kind':kind,'payload':payload,'refs':refs});self.objects[key]={'kind':kind,'payload':primitive(payload),'refs':refs}
        return key


class TapeReference:
    """The supplied tape is already immutable input; do not duplicate its ticks six times."""
    failure=None
    def __init__(self,reference):self.reference=reference
    def append(self,_):return True
    def flush(self):return self.reference


class Actor:
    def __init__(self,tape,parameters=None):
        self.tape=tape;self.parameters=parameters;self.market=FrozenMarket();self.evidence=ReplayEvidence()
        config=tape['configuration'];self.config=configuration(PaperWorkerConfig,config['worker'])
        self.execution=configuration(ExecutionConfig,config['execution']);entry=configuration(JavaEntryConfig,config['entry'])
        self.ledger=MemoryLedger();self.ledger.data['identity']={'instance':'offline','strategy':VERSION if parameters else 'HTX_TREND_15M_V2','config':config['worker']}
        self.account='offline';self.errors=[];self.curve=[{'at':iso(tape['start_ms']),'equity':str(self.config.equity)}]
        self.funding=D(0);self.funding_by_position={};self.tracked={};self.decisions=[]
        if parameters is None:
            worker=PaperTrendWorker.__new__(PaperTrendWorker)
            worker.instance=SimpleNamespace(instance_key='offline',strategy_family='TREND',strategy_name='HTX_TREND_15M_V2')
            worker.account='offline';worker.profile=TimeframeProfile.INTRADAY_15M;worker.config=self.config
            worker.entry_config=entry;worker.continuation=ContinuationConfig(config['baseline_continuation'])
            worker.require_holding_policy=False;worker.holding_adoption=None
            worker.ledger=self.ledger;worker.market=self.market;worker.stopped=False;worker._checkpoint_failed=False;worker._failures={}
            worker.exchange=WorkerPaperExchange(self.ledger)
            decisions=self.decisions
            class ObservedRuntime(PaperTrendRuntime):
                def scan(self,**kwargs):
                    result=super().scan(**kwargs)
                    decisions.append(primitive({'at':kwargs['at'],'contract':kwargs['entry_window'].contract_code,
                                                'candidates':result.candidates,'gates':result.gates}))
                    return result
            worker.runtime=ObservedRuntime(self.ledger,worker.exchange,account_id='offline',entry_config=entry,
                execution_config=self.execution,maker_config=configuration(MakerExecutionConfig,config['maker']),
                strategy_version='HTX_TREND_15M_V2',continuation_config=worker.continuation)
            self.worker=worker
        else:
            self.store=PlanStore(':memory:');engine=TriggerEngine(self.store,'offline',parameters=parameters)
            broker=PaperBroker(self.ledger,engine,'offline',latency_ms=tape['latency']['milliseconds'])
            policy=HoldingPolicy.create(instance_key='offline',account_id='offline',strategy_family='TREND',strategy_name=VERSION,
                mode='PAPER',profile=TimeframeProfile.INTRADAY_15M,entry_config=entry,
                exit_config=_decode_config(ExitConfig,config['exit']),continuation_version=LINKED)
            runtime=PaperTrendRuntime(self.ledger,RealtimePaperExchange(self.ledger),account_id='offline',entry_config=entry,
                execution_config=self.execution,strategy_version=VERSION,continuation_config=ContinuationConfig(LINKED),
                holding_policy=policy,require_holding_policy=True,evidence_context={'instance':'offline','timeframe_profile':'INTRADAY_15M'})
            view=MarketView(self.market,self.market.frames,engine.books,clock=lambda:self.market.now_ms/1000)
            manager=PaperManagement(runtime,broker,self.market.frames,view,exit_config=policy.exit,entry_config=entry,
                context_provider=lambda _:self._context())
            queue=[];self.queue=queue
            def drain():
                values=list(queue);queue.clear();return values
            stream=SimpleNamespace(connected=True,epoch='replay',drain=drain,start=lambda:None,close=lambda:None)
            loader=SimpleNamespace(errors={},start=lambda:None,close=lambda:None)
            self.worker=RealtimeWorker(SimpleNamespace(instance_key='offline',strategy_name=VERSION,execution_mode='PAPER'),
                engine,broker,manager,stream,loader,self.market,self.market.frames,TapeReference(digest(tape)),self.evidence,
                parameters=parameters,execution=self.execution,leverage=self.config.leverage,entry_enabled=True,
                clock=lambda:self.market.now_ms/1000,holding_parameters=policy.to_dict(),entry_config=entry)
            self.worker.started=True;self.worker.last_epoch='replay'

    def _context(self):
        if self.market.context is None:raise ValueError('MARKET_CONTEXT_MISSING')
        return self.market.context

    def step(self,observation):
        self.market.observe(observation);kind=observation['kind'];now=self.market.now_ms
        if kind=='FUNDING_SETTLEMENT':
            for position in self.ledger.active_positions(self.account):
                if position.contract_code==observation['contract']:
                    payment=position.quantity*D(self.ledger.position_context(position.position_id)['contract_size'])*D(observation['price'])*D(observation['rate'])*(1 if position.side=='LONG' else -1)
                    self.funding+=payment
                    self.funding_by_position[position.position_id]=self.funding_by_position.get(position.position_id,D(0))+payment
        self.ledger.data['cash_adjustment']=-self.funding
        try:
            if self.parameters is None:
                if kind=='SCAN':
                    self.worker.config=replace(self.config,equity=self.config.equity-self.funding)
                    self.worker.runtime._execution_config=replace(self.execution,account_equity_usdt=self.config.equity-self.funding)
                    from .. import paper_worker
                    class Clock(datetime):
                        @classmethod
                        def now(cls,tz=None):return datetime.fromtimestamp(now/1000,tz or UTC)
                    with patch.object(paper_worker,'datetime',Clock):health=self.worker.tick()
                else:health=None
            else:
                if kind in ('TRADE','BBO'):
                    event=MarketEvent.load(observation)
                    self.worker.stream.epoch=event.epoch;self.queue.append(event)
                if kind in ('GAP','RESTART'):
                    self.worker.engine.disconnect();self.worker.management.gap()
                    if kind=='RESTART':self.worker.broker.recover()
                health=self.worker.tick()
            if health is not None and health.status not in ('HEALTHY','STOPPED'):
                self.errors.append({'at':iso(now),'detail':health.detail})
        except Exception as error:self.errors.append({'at':iso(now),'detail':type(error).__name__+':'+str(error)})
        self.mark(now)

    def mark(self,now):
        realized=sum((D(str(p['realized_pnl'])) for p in self.ledger.data['positions'].values()),D(0))
        entry_fees=sum((D(str(f['fee'])) for f in self.ledger.data['fills'] if self.ledger.data['orders'][f['order_id']].get('position_id') is None),D(0))
        value=self.config.equity+realized-entry_fees-self.funding
        try:
            for position in self.ledger.active_positions(self.account):
                book=self.market.top_of_book(position.contract_code);price=book.bid if position.side=='LONG' else book.ask
                context=self.ledger.position_context(position.position_id);size=D(context['contract_size'])
                fee=D(context.get('holding_policy',{}).get('exit',{}).get('fee_rate','.0004'))
                value+=(price-position.entry_price)*position.quantity*size*(1 if position.side=='LONG' else -1)-price*position.quantity*size*fee
            self.curve.append({'at':iso(now),'equity':str(value)})
        except ValueError as error:self.errors.append({'at':iso(now),'detail':str(error)})
        for ident,row in self.ledger.data['positions'].items():
            position=self.ledger.position(ident);context=self.ledger.position_context(ident)
            net=position.realized_pnl-D(context['entry_fee'])-self.funding_by_position.get(ident,D(0))
            if position.status=='OPEN':
                try:book=self.market.top_of_book(position.contract_code)
                except ValueError:continue
                price=book.bid if position.side=='LONG' else book.ask;size=D(context['contract_size'])
                fee=D(context.get('holding_policy',{}).get('exit',{}).get('fee_rate','.0004'))
                net+=(price-position.entry_price)*position.quantity*size*(1 if position.side=='LONG' else -1)-price*position.quantity*size*fee
            record=self.tracked.setdefault(ident,{'position_id':ident,'contract':position.contract_code,'side':position.side,
                'model':context['model'],'opened_at':position.opened_at.isoformat(),'peak_net':D(0),'max_giveback':D(0),
                'entry_price':position.entry_price,'planned_net_r':context.get('entry_net_r'),
                'entry_offset_atr':abs(position.entry_price-D(context['structure_line']))/D(context['structure_atr']) if context.get('structure_atr') else None})
            record['peak_net']=max(record['peak_net'],net);record['max_giveback']=max(record['max_giveback'],record['peak_net']-net)
            denominator=(D(context['entry_net_risk_per_unit'])*int(context['original_quantity'])*D(context['contract_size'])
                         if context.get('entry_net_risk_per_unit') else None)
            record.update(net=net,remaining=position.quantity,status=position.status,closed_at=position.closed_at,
                realized_net_r=net/denominator if denominator and position.status=='CLOSED' else None,
                holding_seconds=(position.closed_at-position.opened_at).total_seconds() if position.closed_at else None,
                net_basis='FUNDING_INCLUDED' if self.tape.get('funding_status')=='VERIFIED_SCHEDULE' else 'FUNDING_UNKNOWN')

    def result(self):
        fills=fill_events(self.ledger.data)
        return primitive({'curve':self.curve,'fills':fills,'decisions':self.decisions,'errors':self.errors,
            'positions':list(self.tracked.values()),'plans':self.worker.engine.rows if self.parameters else {},
            'evidence':self.evidence.objects,'funding_paid':self.funding if self.tape.get('funding_status')=='VERIFIED_SCHEDULE' else None,
            'modeled_funding':self.funding,'parameters':asdict(self.parameters) if self.parameters else None})

    def close(self):
        if hasattr(self,'store'):self.store.close()


def metric(result,lo,hi):
    points=[p for p in result['curve'] if lo<=datetime.fromisoformat(p['at']).timestamp()*1000<=hi]
    if len(points)<2 or datetime.fromisoformat(points[-1]['at']).timestamp()*1000<hi:return None
    peak=D(points[0]['equity']);drawdown=D(0)
    for point in points:
        value=D(point['equity']);peak=max(peak,value);drawdown=max(drawdown,peak-value)
    latest={};roundtrips=0;fees=D(0);opens=0;closed=0
    for fill in result['fills']:
        at=datetime.fromisoformat(fill['at']).timestamp()*1000
        scoped=lo<at<=hi
        if scoped:fees+=D(fill['fee'])
        if fill['action']=='FULL_CLOSE':latest[fill['contract']]=fill;closed+=int(scoped)
        elif fill['action']=='OPEN':
            opens+=int(scoped);previous=latest.pop(fill['contract'],None)
            if previous and previous['side']==fill['side'] and scoped:
                roundtrips+=0<=(datetime.fromisoformat(fill['at'])-datetime.fromisoformat(previous['at'])).total_seconds()<=3600
    return {'net':D(points[-1]['equity'])-D(points[0]['equity']),'drawdown':drawdown,'fees':fees,
            'roundtrips':roundtrips,'opens':opens,'closes':closed}


def comparison(candidate,baseline):
    if candidate is None or baseline is None:return False
    return (candidate['net']>=baseline['net'] and candidate['drawdown']<=baseline['drawdown']
            and candidate['fees']<=baseline['fees'] and candidate['roundtrips']<=baseline['roundtrips'])


def evidence_blockers(tape,evidence_root=None):
    reasons=[]
    if tape['provenance']!='OBSERVED':reasons.append('ASSUMPTION_ONLY')
    if tape.get('funding_status')!='VERIFIED_SCHEDULE':reasons.append('FUNDING_UNKNOWN')
    if tape['latency'].get('source')!='OBSERVED_ORDER_LOG':reasons.append('LATENCY_ASSUMED')
    if any(o['kind'] in ('GAP','RESTART') for o in tape['observations']):reasons.append('CAPTURE_GAP')
    for key,kind in (('coverage_ref','realtime_replay_coverage'),('reference_ref','realtime_baseline_reference'),('latency_ref','realtime_latency_evidence')):
        try:
            obj=read_object(evidence_root,tape[key]) if evidence_root is not None else None
            if obj is None or obj['kind']!=kind:raise ValueError('missing source')
            if key=='coverage_ref' and obj['payload']!={'start_ms':tape['start_ms'],'end_ms':tape['end_ms'],
                    'observations_hash':digest(tape['observations']),'configuration_hash':tape['configuration_hash']}:
                raise ValueError('coverage mismatch')
            if key=='reference_ref' and obj['payload']!={'fills':tape.get('reference_fills'),'decisions':tape.get('reference_decisions')}:raise ValueError('reference mismatch')
            if key=='latency_ref' and obj['payload']!=tape['latency']:raise ValueError('latency mismatch')
        except (ValueError,KeyError,FileNotFoundError,TypeError):reasons.append(key.upper()+'_UNVERIFIED')
    try:
        references=tape['observation_refs']
        if len(references)!=len(tape['observations']) or evidence_root is None:raise ValueError('observation closure missing')
        for reference,observation in zip(references,tape['observations']):
            obj=read_object(evidence_root,reference)
            if obj['kind']!='realtime_observation' or obj['payload']!=observation:raise ValueError('observation receipt mismatch')
        rate=read_object(evidence_root,tape['fee_ref'])
        if rate['kind']!='realtime_account_fee' or rate['payload']['taker_fee_rate']!=tape['configuration']['execution']['fee_rate']:
            raise ValueError('fee evidence mismatch')
    except (ValueError,KeyError,FileNotFoundError,TypeError):reasons.append('RAW_OBSERVATION_OR_FEE_CLOSURE_UNVERIFIED')
    samples=tape['latency'].get('samples',[])
    if not samples or any(type(x.get('first_fill_ms')) is not int or type(x.get('sent_ms')) is not int or
                          x['first_fill_ms']<x['sent_ms'] for x in samples):
        reasons.append('EXACT_EXECUTION_TIME_UNVERIFIED')
    from datetime import timedelta
    for observation in tape['observations']:
        date=(datetime.fromtimestamp(observation['received_ms']/1000,UTC)+timedelta(hours=8)).date().isoformat()
        if date in PROTOCOL['case_only_dates_cst']:
            reasons.append('HYPOTHESIS_CASE_ONLY');break
    return reasons


def replay(tape,*,evidence_root=None):
    validate(tape)
    times=sorted({o['received_ms'] for o in tape['observations']})
    split=next(t for t in times if t>=tape['start_ms']+(tape['end_ms']-tape['start_ms'])*.7)
    if not tape['start_ms']<split<tape['end_ms']:raise ValueError('no interior observed 70/30 boundary')
    actors={'A':Actor(tape)}
    actors.update({p.fingerprint:Actor(tape,p) for p in GRID})
    baseline=actors['A'];selected=None;diagnostic={};results={};blockers=evidence_blockers(tape,evidence_root)
    try:
        for observation in tape['observations']:
            if observation['received_ms']>split:break
            for actor in actors.values():actor.step(observation)
        base=baseline.result();a=metric(base,tape['start_ms'],split)
        eligible=[]
        for p in GRID:
            group=actors[p.fingerprint].result();m=metric(group,tape['start_ms'],split)
            diagnostic[p.fingerprint]={'parameters':asdict(p),'metrics':m,'errors':group['errors']}
            if comparison(m,a) and not group['errors'] and not base['errors']:
                eligible.append((p,m))
            results[p.fingerprint]=group
        if eligible:
            selected=sorted(eligible,key=lambda pair:(-pair[1]['net'],pair[1]['drawdown'],pair[1]['roundtrips'],
                                                      -pair[0].band_atr,-pair[0].min_net_r))[0][0]
        else:blockers.append('NO_DIAGNOSTIC_COMBINATION_PASSED')
        selection={'protocol_hash':digest(PROTOCOL),'split_ms':split,'diagnostic':diagnostic,
                   'selected':selected.fingerprint if selected else None,'uses_holdout':False}
        selection_hash=digest(selection)
        # This is the only transition into holdout. Other five actors never see it.
        for observation in tape['observations']:
            if observation['received_ms']<=split:continue
            baseline.step(observation)
            if selected is not None:actors[selected.fingerprint].step(observation)
        results['A']=baseline.result()
        if (tape.get('reference_fills')!=results['A']['fills'] or
                tape.get('reference_decisions')!=results['A']['decisions']):blockers.append('BASELINE_NOT_REPRODUCED')
        if results['A']['errors']:blockers.append('BASELINE_INPUT_GAPS')
        passed=False
        if selected:
            chosen=actors[selected.fingerprint].result();results[selected.fingerprint]=chosen
            hold=metric(chosen,split,tape['end_ms']);a_hold=metric(results['A'],split,tape['end_ms'])
            passed=comparison(hold,a_hold) and not chosen['errors']
            if not passed:blockers.append('HOLDOUT_FAILED_NO_RESELECTION')
            if not hold or not hold['opens'] or not diagnostic[selected.fingerprint]['metrics']['opens']:
                blockers.append('NO_EXECUTED_OPPORTUNITY_IN_BOTH_SEGMENTS')
        report={'schema':'htx-realtime-replay-report-v1','eligible':bool(selected and passed and not blockers),
            'status':'ELIGIBLE' if selected and passed and not blockers else 'NOT_ELIGIBLE','blockers':sorted(set(blockers)),
            'tape_hash':digest(tape),'protocol':PROTOCOL,'selection':selection,'selection_hash':selection_hash,
            'groups':results,'selected':selected.fingerprint if selected else None,
            'metrics':{key:{'history':metric(value,tape['start_ms'],tape['end_ms']),
                            'diagnostic':metric(value,tape['start_ms'],split),'holdout':metric(value,split,tape['end_ms']),
                            'C_clock':metric(value,max(tape['start_ms'],tape.get('clock_start_ms',tape['start_ms'])),tape['end_ms'])}
                       for key,value in results.items()},'production_changes':False}
        report['models']={key:model_statistics(value,tape) for key,value in results.items() if key!='A'}
        return primitive(report)
    finally:
        for actor in actors.values():actor.close()


def model_statistics(result,tape):
    from . import MODELS
    output={}
    last_observed=int(datetime.fromisoformat(result['curve'][-1]['at']).timestamp()*1000)
    for model in sorted(MODELS):
        plans=[r for r in result['plans'].values() if r['plan']['model']==model]
        triggered=[r for r in plans if r.get('trigger')]
        submitted=[r for r in plans if r.get('client_order_id')]
        fills=[r for r in plans if r.get('filled_contracts',0)>0]
        positions=[r for r in result['positions'] if r['model']==model]
        failed=0;unresolved=0
        for row in triggered:
            trigger=row['trigger'];plan=row['plan'];long=plan['side']=='LONG';outcome=None
            for event in tape['observations']:
                if (event['kind']!='TRADE' or event['contract']!=plan['contract']
                        or not trigger['trigger_ms']<event['received_ms']<=last_observed):continue
                px=D(event['price']);target=D(plan['target']);line=D(plan['line']);band=D(plan['band'])
                if px>=target if long else px<=target:outcome='TARGET';break
                if px<line-band if long else px>line+band:outcome='RECLAIMED';break
            failed+=outcome=='RECLAIMED';unresolved+=outcome is None
        output[model]={'plans':len(plans),'triggers':len(triggered),'submitted':len(submitted),'filled':len(fills),
            'trigger_rate':D(len(triggered))/len(plans) if plans else None,
            'fill_rate':D(len(fills))/len(submitted) if submitted else None,
            'structure_reclaimed_before_target':failed,'unresolved_trigger_outcome':unresolved,
            'positions':positions,'max_profit_giveback':max((D(r['max_giveback']) for r in positions),default=None),
            'rejections':{reason:sum(r.get('reason')==reason for r in plans) for reason in sorted({r['reason'] for r in plans if r.get('reason')})}}
    return output


def audit_capture(path):
    from .storage import SegmentRecorder
    path=Path(path)
    if not path.is_dir():raise ValueError('capture directory missing')
    recorder=SegmentRecorder(path);observations=recorder.verify()
    channels={};times=[];epochs=set()
    for row in observations:
        if row.get('kind') in ('TRADE','BBO'):
            times.append(row['received_ms']);epochs.add(row['epoch'])
            key=row['contract']+':'+row['kind'];channels[key]=channels.get(key,0)+1
    return {'schema':'htx-realtime-input-audit-v1','status':'NOT_ELIGIBLE','capture_path':str(path.resolve()),
        'verified_market_events':len(times),'channels':channels,'epochs':len(epochs),'bytes':recorder.used,
        'first_received_ms':min(times) if times else None,'last_received_ms':max(times) if times else None,
        'blockers':['NO_VERIFIED_FULL_OPPORTUNITY_TAPE','NO_POINT_IN_TIME_CANDLE_AND_CONFIG_CLOSURE',
            'NO_ALL_OPPORTUNITY_DEPTH_COVERAGE','NO_REFERENCE_BASELINE_LEDGER_AND_DECISIONS',
            'NO_VERIFIED_FILL_LATENCY','FUNDING_SCHEDULE_UNKNOWN','NO_INDEPENDENT_DIAGNOSTIC_HOLDOUT_INTERVAL'],
        'grid':[{'parameters':primitive(asdict(p)),'status':'NOT_RUN_DATA_INSUFFICIENT',
                 'net':None,'drawdown':None,'fees':None,'roundtrips':None} for p in GRID],
        'production_changes':False}


def write_report(report,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    summary={**report}
    if 'groups' in report:
        summary['groups']={name:{'artifact':name+'.json.gz','fills':len(value['fills']),'errors':len(value['errors'])}
                           for name,value in report['groups'].items()}
    (output/'report.json').write_text(json.dumps(primitive(summary),ensure_ascii=False,indent=2)+'\n')
    if 'groups' in report:
        # Separate the large per-opportunity evidence from the user-facing summary.
        for name,result in report['groups'].items():
            with gzip.open(output/(name+'.json.gz'),'wt',encoding='utf-8') as stream:
                json.dump(result,stream,ensure_ascii=False)
    lines=['# HTX 15m 实时结构回放','',f"结果：**{report['status']}**。未修改生产。",'',
           '本报告只将具有完整原始输入、基线复现、费用及时间证据的独立区间用于准入。','',
           '限制：']+['- '+reason for reason in report['blockers']]
    if 'selected' in report:lines+=['', '诊断段选中：'+str(report['selected']), '留出段失败时不重新选参。']
    (output/'report.md').write_text('\n'.join(lines)+'\n')


def load(path,expected_sha256):
    from hashlib import sha256
    raw=Path(path).read_bytes()
    if sha256(raw).hexdigest()!=expected_sha256:raise ValueError('frozen tape file digest mismatch')
    return json.loads(gzip.decompress(raw) if raw.startswith(b'\x1f\x8b') else raw)
