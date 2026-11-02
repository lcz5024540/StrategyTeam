"""Strict full-worker A/B/C/D replay. Missing observations never become prices.

Run with --tape <json[.gz]> --sha256 <frozen file digest> --out <directory>,
or --audit-ledger <json[.gz]> to inventory historical decision coverage.
No production constructors, credentials, private APIs or databases are opened.
"""
from __future__ import annotations

import argparse
import copy
import csv
import gzip
import hashlib
import json
from dataclasses import asdict, fields
from datetime import UTC, datetime
from decimal import Decimal as D
from enum import Enum
from pathlib import Path
from unittest.mock import patch

from . import paper_worker as worker_module
from .continuation import BASELINE, CONFIRM, LINKED, ContinuationConfig
from .execution import ExecutionConfig
from .java_entry_contract import JavaEntryConfig
from .maker_execution import MakerExecutionConfig
from .market_data import Candle, KlineWindow, ContractSpec, TopOfBook, FundingRate, period_seconds
from .paper_worker import PaperTrendWorker, PaperWorkerConfig, WorkerPaperExchange
from .runtime import PaperTrendRuntime
from .scan_replay import MemoryLedger
from .timeframes import TimeframeProfile
from .research.store import digest, primitive

GROUPS = {'A': ContinuationConfig(), 'B': ContinuationConfig(cooldown=True),
          'C': ContinuationConfig(CONFIRM), 'D': ContinuationConfig(LINKED)}


def time(value):
    result = datetime.fromisoformat(value)
    if result.tzinfo is None:
        raise ValueError('tape timestamps must have timezone')
    return result


def number(value):
    result = D(str(value))
    if not result.is_finite():
        raise ValueError('nonfinite market/config value')
    return result


def configuration(cls, values):
    default = cls()
    if set(values) != {f.name for f in fields(cls)}:
        raise ValueError(f'incomplete {cls.__name__} configuration')
    result = {}
    for f in fields(cls):
        example, value = getattr(default, f.name), values[f.name]
        if isinstance(example, D): value = number(value)
        elif isinstance(example, Enum): value = type(example)(value)
        elif isinstance(example, tuple): value = tuple(value)
        elif isinstance(example, bool):
            if type(value) is not bool: raise ValueError('invalid boolean config')
        elif isinstance(example, int) and type(value) is not int: raise ValueError('invalid integer config')
        result[f.name] = value
    return cls(**result)


class TapeMarket:
    """A frame is an exact observed pool evaluation, including marks for removed assets."""
    def set_frame(self, frame):
        self.frame = frame
        self.now = time(frame['at'])
        self.rows = frame['markets']

    def row(self, contract):
        if contract not in self.rows:
            raise ValueError(f'COUNTERFACTUAL_INPUT_GAP:{contract}')
        return self.rows[contract]

    def kline_window(self, contract, period, size, *, now_epoch):
        source = self.row(contract)['windows'][period]
        if time(source['observed_at']) > self.now:
            raise ValueError('future window revision')
        rows = tuple(Candle(**{k: (v if k in ('open_time', 'trade_count') or v is None else number(v))
                              for k, v in c.items()}) for c in source['candles'])
        if any(c.open_time + period_seconds(period) > now_epoch - 10 for c in rows):
            raise ValueError('forming/future candle in tape')
        w = KlineWindow(contract, period, rows, size)
        if not w.is_complete:
            raise ValueError('incomplete/duplicate/noncontiguous candle window')
        return w

    def top_of_book(self, contract):
        quote = self.row(contract)['book']
        age = (self.now - time(quote['observed_at'])).total_seconds()
        if not 0 <= age <= 60:
            raise ValueError('missing/future/stale executable quote')
        b = TopOfBook(*(number(quote[k]) for k in ('bid', 'ask', 'bid_size', 'ask_size')))
        if not 0 < b.bid <= b.ask or min(b.bid_size, b.ask_size) <= 0:
            raise ValueError('invalid two-sided executable book')
        return b

    def contract_spec(self, contract):
        spec = self.row(contract)['spec']
        if time(spec['observed_at']) > self.now:
            raise ValueError('future contract metadata')
        return ContractSpec(contract, number(spec['contract_size']), number(spec['price_tick']), spec['contract_status'])

    def funding_rate(self, contract):
        funding = self.row(contract).get('funding')
        if funding is None:
            raise ValueError('COUNTERFACTUAL_FUNDING_QUOTE_MISSING')
        if time(funding['observed_at']) > self.now:
            raise ValueError('future funding quote')
        return FundingRate(contract, number(funding['rate']), funding.get('funding_time'))


def events(data, excluded=()):
    """Natural trade keys make UUID generation irrelevant to baseline comparison."""
    positions = data['positions']
    by_run = {p['strategy_run_id']: p for p in positions.values()}
    reasons = {(d['position_id'], d['run_id']): d['payload']['reason'] for d in data['decisions']
               if d['kind'] == 'EXIT_DECISION'}
    remaining = {pid:p['quantity'] for pid,p in positions.items()}
    for f in data['fills']:
        pid=data['orders'][f['order_id']].get('position_id')
        if pid is not None:remaining[pid]+=f['quantity']
    result = []
    for f in data['fills']:
        o = data['orders'][f['order_id']]
        exiting = o.get('position_id') is not None
        p = positions[o['position_id']] if exiting else by_run[o['strategy_run_id']]
        at = f['filled_at'] if isinstance(f['filled_at'], datetime) else time(f['filled_at'])
        opened = p['opened_at'] if isinstance(p['opened_at'], datetime) else time(p['opened_at'])
        closed = p.get('closed_at')
        if isinstance(closed, str): closed = time(closed)
        if exiting: remaining[p['position_id']] -= f['quantity']
        if f['fill_id'] in excluded: continue
        result.append(dict(at=at.isoformat(), contract=p['contract_code'], side=p['side'],
            opened_at=opened.isoformat(), action=('FULL_CLOSE' if remaining[p['position_id']] == 0 else 'PARTIAL_CLOSE') if exiting else 'OPEN',
            quantity=f['quantity'], price=number(f['price']), fee=number(f['fee']),
            model=data['contexts'][p['position_id']]['model'],
            reason=reasons.get((p['position_id'], o['strategy_run_id'])) if exiting else None))
    return primitive(result)


def net_equity(ledger, market, equity, funding):
    realized = sum((number(p['realized_pnl']) for p in ledger.data['positions'].values()), D(0))
    fees = sum((number(f['fee']) for f in ledger.data['fills']), D(0))
    exit_fees = sum((number(f['fee']) for f in ledger.data['fills']
                     if ledger.data['orders'][f['order_id']].get('position_id')), D(0))
    floating, close_cost = D(0), D(0)
    for p in ledger.active_positions('offline'):
        quote = market.top_of_book(p.contract_code)
        price = quote.bid if p.side == 'LONG' else quote.ask
        size = number(ledger.position_context(p.position_id)['contract_size'])
        floating += (price - p.entry_price) * p.quantity * size * (1 if p.side == 'LONG' else -1)
        close_cost += price * p.quantity * size * D('.0004')
    return equity + realized + exit_fees - fees - funding + floating - close_cost


def segments(curve, fills, clock):
    """Carry state across 70/30. No interpolation at an unobserved split."""
    result = {}
    for scope, start in [('history', time(curve[0]['at'])), ('C_clock', time(clock))]:
        available = [p for p in curve if time(p['at']) >= start]
        if len(available) < 3:
            result[scope] = {'available': False}
            continue
        nominal = time(available[0]['at']) + (time(available[-1]['at'])-time(available[0]['at'])) * .7
        split = next((i for i,p in enumerate(available) if time(p['at']) >= nominal), len(available)-1)
        # If no interior observed boundary exists, leave the scope unqualified.
        if split == 0 or split == len(available)-1:
            result[scope] = {'available': False}
            continue
        result[scope] = {'available': True, 'requested_start': start.isoformat(),
                         'observed_start': available[0]['at'], 'split_at': available[split]['at']}
        for name, points in [('full', available), ('diagnostic', available[:split+1]), ('holdout', available[split:])]:
            peak = number(points[0]['equity']); drawdown = D(0)
            for point in points:
                value = number(point['equity']); peak = max(peak,value); drawdown = max(drawdown,peak-value)
            lo,hi = time(points[0]['at']),time(points[-1]['at'])
            scoped = [f for f in fills if lo < time(f['at']) <= hi]
            # Pair with the immediate previous complete exit, even across split.
            latest = {}; roundtrips=0
            for f in fills:
                if f['action']=='FULL_CLOSE': latest[f['contract']] = f
                elif f['action']=='OPEN':
                    previous = latest.pop(f['contract'],None)
                    if previous and previous['side']==f['side'] and lo < time(f['at']) <= hi:
                        roundtrips += 0 <= (time(f['at'])-time(previous['at'])).total_seconds() <= 3600
            result[scope][name] = dict(net=number(points[-1]['equity'])-number(points[0]['equity']),
                drawdown=drawdown, fees=sum((number(f['fee']) for f in scoped),D(0)), roundtrips=roundtrips,
                opening_equity=number(points[0]['equity']), closing_equity=number(points[-1]['equity']))
    return primitive(result)


def validate_tape(tape):
    if tape.get('schema') != 'htx-continuation-tape-v1': raise ValueError('unsupported tape schema')
    if not tape.get('frames'): raise ValueError('empty tape')
    if tape.get('provenance') not in ('OBSERVED','ASSUMPTION_ONLY'): raise ValueError('unknown provenance')
    last = time(tape['initial_at'])
    settlement_ids=set()
    for frame in tape['frames']:
        at = time(frame['at'])
        if at <= last: raise ValueError('frames must strictly increase after initial snapshot')
        for event in frame.get('settlements',[]):
            if event['id'] in settlement_ids or not last < time(event['at']) <= at:
                raise ValueError('duplicate or mis-timed funding settlement')
            # Only exact evaluation-time settlement is supported; do not guess
            # the exposure at an unobserved moment between evaluations.
            if time(event['at']) != at:
                raise ValueError('settlement requires an exact observed evaluation frame')
            settlement_ids.add(event['id'])
            if number(event['price']) <= 0:raise ValueError('invalid funding settlement mark')
            number(event['rate'])
        last = at
        config = tape['configurations'][frame['config_ref']]
        if digest(config) != frame['config_ref']: raise ValueError('configuration hash mismatch')
        worker = configuration(PaperWorkerConfig,config['worker'])
        execution = configuration(ExecutionConfig,config['execution'])
        configuration(JavaEntryConfig,config['entry']);configuration(MakerExecutionConfig,config['maker'])
        if (worker.equity != 300 or not 0 < worker.margin_rate <= D('.10')
                or not 0 < worker.leverage <= 10 or not 1 <= worker.max_active_positions <= 5):
            raise ValueError('invalid PAPER account limits')
        if (execution.account_equity_usdt != worker.equity or execution.max_total_margin_rate != worker.margin_rate
                or execution.max_active_positions != worker.max_active_positions
                or execution.max_same_direction_positions != worker.max_active_positions
                or not execution.hard_risk_limits_enabled):
            raise ValueError('execution configuration does not match PAPER worker limits')
        if len(set(worker.contracts))!=len(worker.contracts):raise ValueError('duplicate contract pool')
        for contract in worker.contracts:
            market=TapeMarket();market.set_frame(frame)
            for period in ('15min','60min'):market.kline_window(contract,period,160,now_epoch=int(at.timestamp()))
            market.top_of_book(contract);market.contract_spec(contract);market.funding_rate(contract)
    return []


def track_positions(ledger, market, tracked, funding_by_id, start):
    for pid,p in ledger.data['positions'].items():
        opened=p['opened_at'] if isinstance(p['opened_at'],datetime) else time(p['opened_at'])
        closed=p.get('closed_at')
        if closed is not None and (closed if isinstance(closed,datetime) else time(closed)) <= start:
            continue
        context=ledger.position_context(pid)
        net=number(p['realized_pnl'])-number(context['entry_fee'])-funding_by_id.get(pid,D(0))
        if p['status']=='OPEN':
            book=market.top_of_book(p['contract_code']);price=book.bid if p['side']=='LONG' else book.ask
            size=number(context['contract_size']);quantity=p['quantity']
            net+=(price-number(p['entry_price']))*quantity*size*(1 if p['side']=='LONG' else -1)
            net-=price*quantity*size*D('.0004')
        row=tracked.setdefault(pid,dict(contract=p['contract_code'],side=p['side'],opened_at=opened.isoformat(),
            model=context['model'],peak_net=D(0),max_giveback=D(0)))
        row['peak_net']=max(row['peak_net'],net)
        if row['peak_net']>0:row['max_giveback']=max(row['max_giveback'],row['peak_net']-net)
        row.update(current_net=net,remaining=p['quantity'],status=p['status'],closed_at=closed)


def replay_group(tape, policy):
    ledger = MemoryLedger();ledger.data=copy.deepcopy(tape['initial_ledger'])
    # Frozen instance identity is remapped, not merged with any external account.
    accounts = {p['account_id'] for p in ledger.data['positions'].values()}
    if len(accounts)>1:raise ValueError('initial ledger must contain one independent instance')
    for collection in ('positions','orders'):
        for item in ledger.data[collection].values():item['account_id']='offline'
    for collection in ('fills','decisions'):
        for item in ledger.data[collection]:item['account_id']='offline'
    excluded={f['fill_id'] for f in ledger.data['fills']}
    market=TapeMarket();worker=PaperTrendWorker.__new__(PaperTrendWorker)
    worker.account='offline';worker.profile=TimeframeProfile.INTRADAY_15M
    worker.require_holding_policy=False;worker.holding_adoption=None
    from types import SimpleNamespace
    worker.instance=SimpleNamespace(instance_key='offline',strategy_family='TREND',strategy_name='HTX_TREND_15M_V2')
    worker.ledger=ledger;worker.market=market;worker.stopped=False;worker._checkpoint_failed=False;worker._failures={}
    worker.continuation=policy;worker.exchange=WorkerPaperExchange(ledger)
    curve=[];decisions=[];funding=D(0);errors=[];tracked={};funding_by_id={}
    class Clock(datetime):
        @classmethod
        def now(cls,tz=None):return market.now.astimezone(tz or UTC)
    class ObservedRuntime(PaperTrendRuntime):
        def scan(self,**kwargs):
            outcome=super().scan(**kwargs)
            decisions.append(dict(at=kwargs['at'],contract=kwargs['entry_window'].contract_code,
                                  candidates=outcome.candidates,gates=outcome.gates))
            return outcome
    with patch.object(worker_module,'datetime',Clock):
        for frame in tape['frames']:
            market.set_frame(frame)
            cfg=tape['configurations'][frame['config_ref']]
            worker.config=configuration(PaperWorkerConfig,cfg['worker'])
            ledger.data.setdefault('identity',{'config':cfg['worker']})
            worker.entry_config=configuration(JavaEntryConfig,cfg['entry'])
            execution=configuration(ExecutionConfig,cfg['execution'])
            maker=configuration(MakerExecutionConfig,cfg['maker'])
            worker.runtime=ObservedRuntime(ledger,worker.exchange,account_id='offline',entry_config=worker.entry_config,
                execution_config=execution,maker_config=maker,strategy_version='HTX_TREND_15M_V2',continuation_config=policy)
            # Funding schedules, when supplied, apply to exposure before this tick.
            for settlement in frame.get('settlements',[]):
                for p in ledger.active_positions('offline'):
                    if p.contract_code==settlement['contract']:
                        payment=p.quantity*number(ledger.position_context(p.position_id)['contract_size'])*number(settlement['price'])*number(settlement['rate'])*(1 if p.side=='LONG' else -1)
                        funding += payment
                        funding_by_id[p.position_id]=funding_by_id.get(p.position_id,D(0))+payment
            if not curve:
                if time(tape['initial_mark']['at'])!=time(tape['initial_at']):raise ValueError('initial mark time mismatch')
                initial_market=TapeMarket();initial_market.set_frame(tape['initial_mark'])
                curve.append(dict(at=tape['initial_at'],equity=net_equity(ledger,initial_market,worker.config.equity,D(0))))
            health=worker.tick()
            if health.status!='HEALTHY':errors.append(dict(at=frame['at'],detail=health.detail))
            try: value=net_equity(ledger,market,worker.config.equity,funding)
            except (ValueError,KeyError) as error:
                errors.append(dict(at=frame['at'],detail=str(error)));break
            curve.append(dict(at=frame['at'],equity=value))
            track_positions(ledger,market,tracked,funding_by_id,time(tape['initial_at']))
    fills=events(ledger.data,excluded)
    all_fills=events(ledger.data)
    return primitive(dict(curve=curve,fills=fills,decisions=decisions,errors=errors,
        position_summary=list(tracked.values()),positions=list(ledger.data['positions'].values()),contexts=ledger.data['contexts'],
        funding_paid=funding if tape.get('funding_status')=='VERIFIED_SCHEDULE' else None,
        modeled_funding_adjustment=funding,
        equity_cost_basis='VERIFIED_FUNDING_INCLUDED' if tape.get('funding_status')=='VERIFIED_SCHEDULE' else 'EXCLUDES_UNKNOWN_FUNDING',
        metrics=segments(curve,all_fills,tape['clock_start'])))


def verify_receipts(tape, evidence_root):
    from .research.store import read_object
    if evidence_root is None:
        return False
    # A manifest flag is not proof. Every complete observed frame must have a
    # hash-checked original CAS object, including the initial snapshot/marks.
    try:
        initial = read_object(evidence_root, tape['initial_source_ref'])
        if initial['kind'] != 'paper_replay_initial' or initial['payload'] != {
                'at': tape['initial_at'], 'ledger': tape['initial_ledger'], 'mark': tape['initial_mark']}:
            return False
        inventory = read_object(evidence_root,tape['inventory_source_ref'])
        if inventory['kind'] != 'paper_replay_inventory' or inventory['payload'] != {
                'start':tape['initial_at'], 'end':tape['frames'][-1]['at'],
                'frames':[{'at':f['at'],'source_ref':f['source_ref']} for f in tape['frames']]}:
            return False
        reference = read_object(evidence_root,tape['reference_source_ref'])
        if reference['kind'] != 'paper_replay_reference_ledger' or reference['payload']['at'] != tape['frames'][-1]['at']:
            return False
        excluded={f['fill_id'] for f in tape['initial_ledger']['fills']}
        if events(reference['payload']['ledger'],excluded) != tape['reference_fills']:
            return False
        for frame in tape['frames']:
            raw = read_object(evidence_root, frame['source_ref'])
            expected = {k:v for k,v in frame.items() if k != 'source_ref'}
            if raw['kind'] != 'paper_replay_frame' or raw['payload'] != expected:
                return False
        if tape.get('funding_status') == 'VERIFIED_SCHEDULE':
            schedule = read_object(evidence_root,tape['funding_source_ref'])
            if schedule['kind'] != 'paper_replay_funding_schedule' or schedule['payload'] != {
                    'start':tape['initial_at'], 'end':tape['frames'][-1]['at'],
                    'settlements':[{ 'at':f['at'], 'events':f.get('settlements',[])} for f in tape['frames']]}:
                return False
    except (KeyError,ValueError,OSError):
        return False
    return True


def verify_native_origins(tape, evidence_root):
    """A self-consistent replay wrapper is not proof of an observed market.

    Require original decision/window/raw/version closures, with field-by-field
    normalization checks. Old exit-only observations fail, rather than inventing
    their missing counterfactual book or funding input.
    """
    if evidence_root is None: return False
    from .research.store import read_object
    from .research.compact import load_window
    class ReadOnly:
        def __init__(self): self.cache={}
        def get(self,key):
            if key not in self.cache:self.cache[key]=read_object(evidence_root,key)
            return self.cache[key]
        def verify(self,key):
            pending=[key];seen=set()
            while pending:
                k=pending.pop()
                if k in seen:continue
                seen.add(k);pending.extend(self.get(k).get('refs',[]))
    store=ReadOnly()
    try:
        for frame in tape['frames']:
            config=tape['configurations'][frame['config_ref']]
            for contract,row in frame['markets'].items():
                key=frame['origin_refs'][contract];store.verify(key);obj=store.get(key);p=obj['payload']
                if obj['kind'] not in ('compact_entry_decision','compact_exit_decision') or time(p['at'])!=time(frame['at']):return False
                snapshot=p if obj['kind']=='compact_entry_decision' else p['market_snapshot']
                if primitive(snapshot['book']) != {k:row['book'][k] for k in ('bid','ask','bid_size','ask_size')}:return False
                spec={k:row['spec'][k] for k in ('contract_size','price_tick','contract_status')}
                if primitive(snapshot['contract']) != dict(contract_code=contract,**spec):return False
                quote=snapshot['funding_quote']
                if quote is None or number(quote['rate'])!=number(row['funding']['rate']) or quote['contract_code']!=contract:return False
                if quote.get('funding_time')!=row['funding'].get('funding_time'):return False
                refs=[store.get(r) for r in obj['refs']]
                versions=[r['payload']['config'] for r in refs if r['kind']=='compact_strategy_version']
                if len(versions)!=1 or any(versions[0].get(k)!=config[k] for k in ('entry','execution','maker')):return False
                if versions[0].get('paper_worker')!=config['worker']:return False
                windows={}
                for r in obj['refs']:
                    if store.get(r)['kind']=='compact_kline_window':
                        w=load_window(store,r);windows[w.period]=w
                raw=[r['payload'] for r in refs if r['kind']=='compact_decision_raw']
                for period in ('15min','60min'):
                    if period not in windows or primitive(windows[period].candles)!=row['windows'][period]['candles']:return False
                    observations=[r for r in raw if r.get('period')==period and r.get('contract_code')==contract]
                    if len(observations)!=1:return False
                    observed=observations[0]['observed_at']
                    if observed>time(frame['at']).timestamp() or int(time(row['windows'][period]['observed_at']).timestamp())!=observed:return False
    except (KeyError,ValueError,TypeError,OSError):return False
    return True


def replay(tape, evidence_root=None):
    validate_tape(tape)
    groups={name:replay_group(tape,policy) for name,policy in GROUPS.items()}
    blockers=[]
    if tape['provenance']!='OBSERVED':blockers.append('ASSUMPTION_ONLY')
    if tape.get('funding_status')!='VERIFIED_SCHEDULE':blockers.append('FUNDING_SETTLEMENT_UNKNOWN')
    if not verify_receipts(tape,evidence_root):blockers.append('SOURCE_RECEIPTS_UNVERIFIED')
    if not verify_native_origins(tape,evidence_root):blockers.append('SOURCE_ORIGIN_UNVERIFIED')
    reference=tape.get('reference_fills')
    alignment=[]
    if reference is None:blockers.append('BASELINE_REFERENCE_MISSING')
    else:
        actual=groups['A']['fills']
        for i in range(max(len(actual),len(reference))):
            a=actual[i] if i<len(actual) else None;r=reference[i] if i<len(reference) else None
            if a!=r:alignment.append(dict(index=i,actual=a,reference=r))
        if alignment:blockers.append('BASELINE_MISMATCH')
    if any(g['errors'] for g in groups.values()):blockers.append('WORKER_OR_MARK_COVERAGE_FAILURE')
    checks={}
    for scope in ('history','C_clock'):
        a=groups['A']['metrics'][scope];d=groups['D']['metrics'][scope]
        if not a['available'] or not d['available']:
            blockers.append('INSUFFICIENT_SEGMENT_COVERAGE:'+scope);continue
        for part in ('full','diagnostic','holdout'):
            checks[scope+'/'+part]=dict(net=number(d[part]['net'])>=number(a[part]['net']),
                drawdown=number(d[part]['drawdown'])<=number(a[part]['drawdown']),
                fees=number(d[part]['fees'])<number(a[part]['fees']),roundtrips=d[part]['roundtrips']<a[part]['roundtrips'])
    if not checks or not all(all(v.values()) for v in checks.values()):blockers.append('PERFORMANCE_GATE_NOT_MET')
    return dict(schema='htx-continuation-comparison-v1',eligible=not blockers,blockers=blockers,
        provenance=tape['provenance'],initial_at=tape['initial_at'],end_at=tape['frames'][-1]['at'],clock_start=tape['clock_start'],
        source_sha256={name:hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                       for name in ('continuation.py','continuation_replay.py','paper_worker.py','runtime.py','exits.py')},
        alignment=alignment,checks=checks,groups=groups,
        notes=['70/30 split uses first observed point at/after time boundary; positions persist across split.',
               'Funding is an economic PnL adjustment; original PAPER execution budget semantics are retained.',
               'No parameter tuning on holdout; synthetic prices cannot qualify a candidate.'])


def read_json(path):
    raw=Path(path).read_bytes()
    return json.loads(gzip.decompress(raw) if str(path).endswith('.gz') else raw)


def audit_ledger(path):
    data=read_json(path);counts={}
    for kind in ('ENTRY_SCAN','EXIT_DECISION','EVIDENCE_RESULT'):
        rows=[d for d in data['decisions'] if d['kind']==kind]
        counts[kind]=dict(total=len(rows),with_ref=sum(bool(d['payload'].get('evidence_ref')) for d in rows))
    return dict(eligible=False,status='NOT_ELIGIBLE',ledger_sha256=hashlib.sha256(gzip.decompress(Path(path).read_bytes()) if str(path).endswith('.gz') else Path(path).read_bytes()).hexdigest(),canonical_ledger_sha256=digest(data),counts=counts,
        blockers=['LEGACY_DECISION_INPUT_MISSING','MISSING_CAS_CLOSURE','COUNTERFACTUAL_INPUT_GAP',
                  'POOL_HISTORY_MISSING','FUNDING_SETTLEMENT_UNKNOWN'],
        details=['Ledger summaries do not contain complete point-in-time windows/book/spec/configuration.',
                 'Historical exit evidence has a one-sided reference price, not a two-sided counterfactual book.',
                 'Current pool/configuration does not establish historical pool order or revisions.',
                 'A rate quote is not proof of paid funding. No historical return is manufactured.'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    choice=parser.add_mutually_exclusive_group(required=True)
    choice.add_argument('--tape',type=Path);choice.add_argument('--audit-ledger',type=Path)
    parser.add_argument('--sha256');parser.add_argument('--evidence-root',type=Path);parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args()
    if args.tape:
        if not args.sha256 or hashlib.sha256(args.tape.read_bytes()).hexdigest()!=args.sha256:
            parser.error('frozen tape SHA256 is required and must match')
        try:result=replay(read_json(args.tape),args.evidence_root)
        except (ValueError,KeyError,TypeError) as error:
            result=dict(eligible=False,status='INPUT_REJECTED',blockers=['INVALID_OR_INCOMPLETE_TAPE'],detail=str(error))
    else:result=audit_ledger(args.audit_ledger)
    args.out.mkdir(parents=True,exist_ok=True)
    (args.out/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    lines=['# 15m continuation replay','',f"Eligible: {result['eligible']}",'', 'Blockers: '+', '.join(result['blockers'])]
    for name,g in result.get('groups',{}).items():
        for label,rows in [('equity',g['curve']),('fills',g['fills']),('positions',g['position_summary'])]:
            if rows:
                with (args.out/(name+'-'+label+'.csv')).open('w',newline='') as stream:
                    writer=csv.DictWriter(stream,fieldnames=list(rows[0]),lineterminator='\n');writer.writeheader();writer.writerows(rows)
        lines+=['',f'## {name}',f"Fills: {len(g['fills'])}; evaluated scans: {len(g['decisions'])}",json.dumps(g['metrics'],ensure_ascii=False)]
    (args.out/'report.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps({'eligible':result['eligible'],'blockers':result['blockers']},ensure_ascii=False))


if __name__=='__main__':main()
