"""Sealed local A/B research: full BBO signal paths, checkpoints and account adapter.

Quote paths have no account allocation and make no IOC fill claim. The separate
account adapter runs the real worker/broker/manager with an explicit tape config.
No connector, credentials, service management or network client is constructed.
"""
import argparse
from collections import Counter, defaultdict, deque
from contextlib import contextmanager
from dataclasses import replace
from decimal import Decimal as D, ROUND_FLOOR, ROUND_CEILING
import fcntl
import gzip
from hashlib import sha256
import heapq
import json
import os
from pathlib import Path

from ..research.store import digest, primitive
from .feed import MarketEvent, EventOrder
from .plans import Parameters, create_engine
from .regime import CANDIDATE, regime_features
from .regime_bayes import Snapshot, fit, code_seal
from .indicator_union import EXITS
from .replay import Actor, decode_window, validate, metric
from ..market_data import ContractSpec


def file_hash(path):
    h=sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1024**2),b''):h.update(b)
    return h.hexdigest()


def implementation():
    root=Path(__file__).parents[1]
    return digest({str(p.relative_to(root)):file_hash(p) for p in sorted(root.rglob('*.py'))})


def read(path):
    with (gzip.open(path,'rt') if str(path).endswith('.gz') else open(path)) as f:return json.load(f)


def atomic(path,value):
    path=Path(path);tmp=path.with_name(path.name+'.tmp')
    with (gzip.open(tmp,'wt') if path.suffix=='.gz' else open(tmp,'w')) as f:
        json.dump(primitive(value),f,ensure_ascii=False,sort_keys=True,allow_nan=False,separators=(',',':'))
    with open(tmp,'rb') as f:os.fsync(f.fileno())
    os.replace(tmp,path)


@contextmanager
def lock(root):
    with open(root/'validator.lock','a+') as f:
        fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
        try:yield
        finally:fcntl.flock(f,fcntl.LOCK_UN)


class MemoryStore:
    def all(self,instance):return {}
    def save(self,*args):pass


def frame_input(event,ref):
    code=event['contract'];now=event['received_ms']
    windows={p:decode_window(code,p,event['windows'][p],now) for p in ('15min','60min')}
    windows={p:replace(w,evidence_refs=(ref,),evidence_status='VERIFIED') for p,w in windows.items()}
    spec=event['spec']
    if spec['contract_code']!=code:raise ValueError('frame/spec contract mismatch')
    return dict(contract=ContractSpec(code,D(str(spec['contract_size'])),D(str(spec['price_tick'])),spec['contract_status']),
                entry=windows['15min'],trend=windows['60min'],windows=windows,received_ms=now,evidence_failed=False)


def order_dump(order):
    return [[list(k),list(v),list(order.id_queues[k])] for k,v in order.seen.items()]


def order_load(order,rows):
    order.reset()
    for k,v,ids in rows:
        k=tuple(k);order.seen[k]=tuple(v);order.ids[k]=set(ids);order.id_queues[k]=deque(ids)


class Paths:
    def __init__(self, protocol, snapshot=None):
        self.protocol=protocol;self.snapshot=snapshot;self.engines={}
        for name,candidate,timing in (('UNION','INDICATOR_UNION_V1','WINDOW'),('AB_FIRST',CANDIDATE,'FIRST_QUOTE'),('AB_WINDOW',CANDIDATE,'WINDOW')):
            self.engines[name]=create_engine(MemoryStore(),name,parameters=Parameters(min_net_r=D(0),candidate=candidate,target_mode='ATR_1_1',regime_timing=timing))
        self.frames={};self.rows=[];self.active=defaultdict(list);self.pending=defaultdict(list)
        self.quality=Counter();self.order=EventOrder();self.previous={};self.epoch=None
        self.frame_audit={}

    def gap(self,reason):
        for e in self.engines.values():e.disconnect()
        for row in [r for bucket in self.active.values() for r in bucket]+[r for bucket in self.pending.values() for r in bucket]:
            row.update(status='CENSORED',reason=reason)
        self.active.clear();self.pending.clear();self.previous.clear();self.order.reset()

    def step(self, wrapped):
        event=wrapped['event'];kind=event['kind'];at=event['received_ms'];code=event.get('contract')
        ref=wrapped['segment']+':'+str(wrapped['index']);self.quality[kind]+=1
        if kind=='FRAME':
            try:
                f=frame_input(event,ref)
                for engine in self.engines.values():engine.refresh_union_frame(f,now_ms=at)
                self.frames[code]=(event,ref)
                audit=self.engines['AB_WINDOW'].generation_audit[0]
                self.frame_audit[ref]={**audit,'received_ms':at,'source_ref':ref}
            except (ValueError,KeyError,TypeError):
                self.quality['INVALID_FRAME']+=1
                for engine in self.engines.values():engine.frames.pop(code,None)
                self.frames.pop(code,None)
            return
        if kind in ('GAP','COLLECTION_SESSION','RESTART'):
            self.gap(kind);return
        if kind!='BBO':return
        ev=MarketEvent.load(event)
        if self.epoch != ev.epoch:
            self.gap('CONNECTION_EPOCH_CHANGED');self.epoch=ev.epoch
        if not ev.fresh(at) or not self.order.accept(ev):
            self.quality['INVALID_OR_DUPLICATE_BBO']+=1;return
        previous=self.previous.get(code)
        if previous is not None and at-previous>2000:
            for row in self.active.pop(code,[])+self.pending.pop(code,[]):row.update(status='CENSORED',reason='QUOTE_COVERAGE_GAP')
        self.previous[code]=at
        for row in list(self.active[code]):
            if self.settle(row,ev,ref):
                self.active[code].remove(row)
        for row in list(self.pending[code]):
            if at>=row['trigger_ms']+self.protocol['latency_ms'] and at>row['trigger_ms']:
                row.update(entry_ms=at,entry_price=str(ev.ask if row['side']=='LONG' else ev.bid),entry_ref=ref,status='OPEN')
                if not self.settle(row,ev,ref):self.active[code].append(row)
                self.pending[code].remove(row)
        for name,engine in self.engines.items():
            try:triggers=engine.accept(ev,now_ms=at)
            except (ValueError,KeyError,TypeError):
                self.quality['STALE_OR_INVALID_FRAME']+=1;continue
            for trigger in triggers:
                engine.terminal(trigger.plan.plan_id,'NO_FILL',reason='QUOTE_PATH_ONLY_NO_ORDER')
                if at<self.protocol['start_ms']:continue
                source=engine.rows[trigger.plan.plan_id]
                for mode,(loss,profit) in EXITS.items():
                    sign=D(1) if trigger.side.value=='LONG' else D(-1);tick=trigger.plan.tick
                    rounding=ROUND_FLOOR if sign>0 else ROUND_CEILING
                    stop=((trigger.reference-sign*loss*trigger.plan.atr)/tick).to_integral_value(rounding=rounding)*tick
                    target=((trigger.reference+sign*profit*trigger.plan.atr)/tick).to_integral_value(rounding=rounding)*tick
                    base=dict(opportunity_id=trigger.plan.plan_id,contract=code,scenario=source.get('scenario','UNION'),
                        rule=CANDIDATE if name!='UNION' else 'INDICATOR_UNION_V1',timing=engine.parameters.regime_timing,
                        side=trigger.side.value,exit_profile=mode,trigger_ms=at,entry_ms=None,exit_ms=None,observed_ms=None,
                        entry_price=None,stop=str(stop),target=str(target),atr=str(trigger.plan.atr),
                        status='PENDING',reason=None,source_refs=[ref,*trigger.plan.evidence_refs],features=source['features'],
                        net_return=None,mfe=None,mae=None,cost_id=self.protocol['cost_id'],cost_status='ASSUMPTION',
                        exposure=self.protocol['exposure'],fee_rate=self.protocol['fee_rate'],funding_cost=None,ioc_fill_verified=False,
                        label_code_seal=code_seal(),label_protocol_hash=digest(self.protocol))
                    row={**base,'policy':name+'/'+mode};self.rows.append(row);self.pending[code].append(row)
                    if name=='AB_WINDOW':
                        for threshold in ('.80','.90','.95'):
                            p=replace(engine.parameters,target_mode=mode,bayes_threshold=D(threshold),
                                bayes_snapshot_hash=self.snapshot.hash if self.snapshot else '',bayes_cost_id=self.protocol['cost_id'])
                            assessment=self.snapshot.assess(source['scenario'],p,at) if self.snapshot else {'allowed':False,'reason':'SNAPSHOT_MISSING'}
                            gated={**base,'policy':'AB_GATE_'+threshold+'/'+mode,'assessment':assessment}
                            if not assessment['allowed']:gated.update(status='REJECTED',reason=assessment['reason'])
                            else:self.pending[code].append(gated)
                            self.rows.append(gated)

    def settle(self,row,event,ref):
        self.mark(row,event,ref)
        long=row['side']=='LONG';px=event.bid if long else event.ask
        stop=D(row['stop']);target=D(row['target'])
        reason='STOP' if (px<=stop if long else px>=stop) else 'TARGET' if (px>=target if long else px<=target) else None
        if reason:
            row.update(status='CLOSED',reason=reason,exit_ms=event.received_ms,
                       observed_ms=event.received_ms,exit_price=str(px),exit_ref=ref)
        return reason is not None

    def mark(self,row,event,ref):
        a=D(row['entry_price']);z=event.bid if row['side']=='LONG' else event.ask
        sign=D(1) if row['side']=='LONG' else D(-1);fee=D(self.protocol['fee_rate'])
        value=((z-a)*sign-fee*(a+z))/a
        row.update(net_return=str(value),last_price=str(z),last_ms=event.received_ms,last_ref=ref,
                   fee_return=str(fee*(a+z)/a),mfe=str(max(D(row['mfe']) if row['mfe'] is not None else value,value)),
                   mae=str(min(D(row['mae']) if row['mae'] is not None else value,value)))

    def dump(self):
        return primitive(dict(rows=self.rows,quality=dict(self.quality),frames=self.frames,previous=self.previous,epoch=self.epoch,frame_audit=self.frame_audit,
            order=order_dump(self.order),engines={k:dict(rows=e.rows,epoch=e.epoch,order=order_dump(e.order),
                history={c:[event.dump(),f] for c,(event,f) in e.history.items()},books={c:v.dump() for c,v in e.books.items()}) for k,e in self.engines.items()}))

    def restore(self,value):
        self.rows=value['rows'];self.quality=Counter(value['quality']);self.frames=value['frames'];self.previous=value['previous'];self.epoch=value['epoch']
        self.frame_audit=value.get('frame_audit',{})
        order_load(self.order,value['order'])
        for code,(event,ref) in self.frames.items():
            for engine in self.engines.values():engine.refresh_union_frame(frame_input(event,ref),now_ms=event['received_ms'])
        for k,v in value['engines'].items():
            e=self.engines[k];e.rows=v['rows'];e.epoch=v['epoch'];order_load(e.order,v['order'])
            e.books={c:MarketEvent.load(ev) for c,ev in v['books'].items()}
            e.history={c:(MarketEvent.load(ev),{n:D(x) if n not in ('bar','hour') and x is not None else x for n,x in f.items()}) for c,(ev,f) in v['history'].items()}
        for row in self.rows:
            if row['status']=='OPEN':self.active[row['contract']].append(row)
            if row['status']=='PENDING':self.pending[row['contract']].append(row)


def statistics(rows):
    closed=[r for r in rows if r['status']=='CLOSED'];valued=[r for r in rows if r.get('net_return') is not None]
    n=len(closed);wins=sum(D(r['net_return'])>0 for r in closed)
    mean=lambda rr: str(sum((D(r['net_return']) for r in rr),D(0))/len(rr)) if rr else None
    winners=[r for r in closed if D(r['net_return'])>0];losers=[r for r in closed if D(r['net_return'])<0]
    gains=sum((D(r['net_return']) for r in winners),D(0));losses=-sum((D(r['net_return']) for r in losers),D(0))
    return dict(opportunities=len(rows),entries=len(valued),closed=n,wins=wins,losses=len(losers),zeros=n-wins-len(losers),
        historical_win_rate=wins/n if n else None,closed_mean_net_return=mean(closed),last_mark_mean_net_return=mean(valued),
        profit_factor=str(gains/losses) if losses else None,
        payoff_ratio=str((gains/len(winners))/(losses/len(losers))) if winners and losers else None,
        closed_mean_fee_return=str(sum((D(r['fee_return']) for r in closed),D(0))/n) if n else None,
        complete_label_fraction=n/len(rows) if rows else None,
        statuses=dict(Counter(r['status'] for r in rows)),reasons=dict(Counter(r['reason'] for r in rows if r['reason'])),
        max_observed_giveback=str(max((D(r['mfe'])-D(r['net_return']) for r in valued),default=D(0))),
        per_asset={c:mean([r for r in closed if r['contract']==c]) for c in sorted({r['contract'] for r in rows})})


def report(paths):
    rows=paths.rows;policies=sorted(name+'/'+mode for name in ('UNION','AB_FIRST','AB_WINDOW','AB_GATE_.80','AB_GATE_.90','AB_GATE_.95') for mode in EXITS);comparison={}
    for key in policies:
        if not key.startswith('AB_GATE_'):continue
        gate={r['opportunity_id']:r for r in rows if r['policy']==key}
        base=[r for r in rows if r['policy']=='AB_WINDOW/'+key.split('/')[1]]
        rejected=[r for r in base if r['opportunity_id'] in gate and gate[r['opportunity_id']]['status']=='REJECTED']
        comparison[key]=dict(blocked_wins=sum(r['status']=='CLOSED' and D(r['net_return'])>0 for r in rejected),
            blocked_losses=sum(r['status']=='CLOSED' and D(r['net_return'])<0 for r in rejected),
            blocked_unknown=sum(r['status']!='CLOSED' for r in rejected))
    without_leader={}
    for key in policies:
        selected=[r for r in rows if r['policy']==key];totals=defaultdict(lambda:D(0))
        for r in selected:
            if r['status']=='CLOSED':totals[r['contract']]+=D(r['net_return'])
        leader=max(totals,key=lambda c:(totals[c],c)) if totals else None
        without_leader[key]={'removed_asset':leader,'selection_basis':'largest sum of completed unlevered returns; descriptive only',
                            'remaining':statistics([r for r in selected if r['contract']!=leader])}
    frame_counts=Counter(v['scenario'] for v in paths.frame_audit.values())
    return dict(schema='regime-replay-report-v1',quality=dict(paths.quality),policies={k:statistics([r for r in rows if r['policy']==k]) for k in policies},
        by_scenario={s:{k:statistics([r for r in rows if r['policy']==k and r['scenario']==s]) for k in policies} for s in ('A','B')},
        frame_conditions={'denominator':'valid observed FRAME evaluations, including visible revisions; not trades',
                          'evaluations':sum(frame_counts.values()),'counts':dict(frame_counts),
                          'match_rates':{k:v/sum(frame_counts.values()) for k,v in frame_counts.items()}},
        by_entry_hour={str(h):{k:statistics([r for r in rows if r['policy']==k and r['trigger_ms']//3600000==h]) for k in policies}
                       for h in sorted({r['trigger_ms']//3600000 for r in rows})},
        excluding_leading_asset=without_leader,
        filtering=comparison,account_result=None,account_status='EXPLICIT_ACCOUNT_CONFIGURATION_NOT_SUPPLIED',
        provenance='FULL_BBO_QUOTE_PATH_ASSUMPTION',financial_admission='NOT_ELIGIBLE',
        limitations=['not IOC fills or account profit','funding and additional slippage unknown','censored paths remain unknown','previously studied data are not independent validation'])


def freeze(source, root, *, start_ms, end_ms, fee_rate, latency_ms, cost_id, exposure, snapshot=None):
    source=Path(source).resolve();m=read(source)
    if not m.get('complete') or exposure not in ('SEEN','UNSEEN') or not cost_id.startswith('ASSUMPTION:'):
        raise ValueError('complete authorized manifest and explicit assumption cost experiment required')
    if not 0<=D(fee_rate)<1 or not 0<=latency_ms<=2000 or not start_ms<end_ms:raise ValueError('invalid study configuration')
    files=[]
    for row in m['files']:
        p=(source.parent/row['path']).resolve()
        if not p.is_relative_to(source.parent) or file_hash(p)!=row['sha256']:raise ValueError('source hash/path mismatch')
        files.append({'path':str(p),'sha256':row['sha256']})
    root=Path(root);root.mkdir(parents=True,exist_ok=False)
    snap=read(snapshot) if snapshot else None
    if snap:Snapshot(snap)
    p=dict(schema='regime-replay-protocol-v1',files=files,source_manifest=str(source),source_manifest_sha256=file_hash(source),
        code_seal=implementation(),start_ms=start_ms,end_ms=end_ms,fee_rate=str(fee_rate),latency_ms=latency_ms,
        cost_id=cost_id,exposure=exposure,snapshot=snap)
    atomic(root/'protocol.json',p)
    atomic(root/'protocol-seal.json',{'hash':digest(p)})


def events(files, offsets):
    def source(row):
        path=row['path']
        with gzip.open(path,'rt') as f:
            for i,line in enumerate(f):
                if i<offsets.get(path,0):continue
                yield json.loads(line),path,i+1
    def key(item):
        z=item[0];return z['event']['received_ms'],z['segment'],z['index']
    yield from heapq.merge(*(source(row) for row in files),key=key)


def run(root, *, max_events=None):
    root=Path(root)
    with lock(root):
        p=read(root/'protocol.json')
        if digest(p)!=read(root/'protocol-seal.json')['hash'] or implementation()!=p['code_seal']:
            raise ValueError('protocol/code changed; freeze a new study')
        for f in p['files']:
            if file_hash(f['path'])!=f['sha256']:raise ValueError('source changed')
        paths=Paths(p,Snapshot(p['snapshot']) if p['snapshot'] else None)
        offsets={};last=None;count=0
        state_path=root/'checkpoint.json.gz'
        if state_path.exists():
            state=read(state_path)
            if state['protocol_hash']!=digest(p):raise ValueError('checkpoint protocol mismatch')
            paths.restore(state['paths']);offsets=state['offsets'];last=state['last']
            if state['complete']:return read(root/'report.json')
        complete=True
        try:
            for z,path,offset in events(p['files'],offsets):
                key=[z['event']['received_ms'],z['segment'],z['index']]
                if last is not None and key<last:raise ValueError('unordered sealed stream')
                if key[0]>p['end_ms']:break
                paths.step(z);offsets[path]=offset;last=key;count+=1
                if count%100000==0:
                    if implementation()!=p['code_seal']:raise ValueError('code changed during replay')
                    atomic(state_path,dict(protocol_hash=digest(p),paths=paths.dump(),offsets=offsets,last=last,complete=False))
                    print(json.dumps({'processed':sum(offsets.values()),'received_ms':key[0],'opportunities':len(paths.rows)}),flush=True)
                if max_events and count>=max_events:complete=False;break
        except BaseException as error:
            atomic(root/'failure.json',{'error':type(error).__name__,'message':str(error),'last_committed_checkpoint':str(state_path)})
            raise
        if implementation()!=p['code_seal']:raise ValueError('code changed during replay')
        result=report(paths);result.update(complete=complete,processed=sum(offsets.values()),protocol_hash=digest(p))
        atomic(root/'records.json.gz',paths.rows);atomic(root/'report.json',result)
        atomic(root/'frame-audit.json.gz',paths.frame_audit)
        atomic(state_path,dict(protocol_hash=digest(p),paths=paths.dump(),offsets=offsets,last=last,complete=complete))
        return result


def account_replay(tape, parameters, snapshot=None):
    if any(key not in tape.get('configuration',{}).get('worker',{}) for key in ('equity','leverage','margin_rate','max_active_positions')):
        raise ValueError('explicit account allocation required')
    validate(tape,fixed_protocol=False)
    actor=Actor(tape,parameters)
    if snapshot is not None:actor.worker.engine.snapshot=Snapshot(snapshot)
    try:
        for event in tape['observations']:actor.step(event)
        return actor.result()
    finally:actor.close()


def account_matrix(tape, parameters, snapshot=None):
    """Same account and full opportunity stream, independently scheduled 18 ways."""
    seal=implementation();results={}
    for mode in EXITS:
        variants=[('UNION','INDICATOR_UNION_V1','WINDOW',None),
                  ('AB_FIRST',CANDIDATE,'FIRST_QUOTE',None),('AB_WINDOW',CANDIDATE,'WINDOW',None)]
        variants.extend(('AB_GATE_'+threshold,CANDIDATE,'WINDOW',D(threshold)) for threshold in ('.80','.90','.95'))
        for name,candidate,timing,threshold in variants:
            p=replace(parameters,candidate=candidate,target_mode=mode,regime_timing=timing,
                      bayes_threshold=threshold,bayes_snapshot_hash=snapshot['hash'] if threshold and snapshot else '',
                      bayes_cost_id=snapshot['cost_id'] if threshold and snapshot else '')
            result=account_replay(tape,p,snapshot if threshold else None)
            result['metrics']=metric(result,tape['start_ms'],tape['end_ms'])
            results[name+'/'+mode]=result
    if implementation()!=seal:raise ValueError('code changed during account replay')
    comparison={}
    for key,gated in results.items():
        if not key.startswith('AB_GATE_'):continue
        base=results['AB_WINDOW/'+key.split('/')[1]]
        # Plan identities are stable across probability thresholds. Compare actual
        # submitted/opened plans, not a post-hoc filter of baseline PnL.
        opened=lambda r:{p['plan']['plan_id'] for p in r['plans'].values()
                         if p.get('phase') in ('PARTIAL','FILLED')}
        comparison[key]={'newly_opened_plan_ids':sorted(opened(gated)-opened(base)),
                         'base_only_plan_ids':sorted(opened(base)-opened(gated))}
    return {'schema':'regime-account-matrix-v1','code_seal':seal,'configuration':tape['configuration'],
            'configuration_hash':tape['configuration_hash'],'tape_hash':digest(tape),'results':results,
            'rescheduling':comparison,'financial_admission':'NOT_ELIGIBLE'}


def extend(previous, source, root, *, end_ms):
    """Fork an append-only study; old protocol, checkpoint and reports stay intact."""
    previous=Path(previous);source=Path(source).resolve();root=Path(root)
    with lock(previous):
        old=read(previous/'protocol.json');state=read(previous/'checkpoint.json.gz')
        if digest(old)!=state['protocol_hash'] or implementation()!=old['code_seal']:
            raise ValueError('old study seal mismatch')
        if not state['complete'] or end_ms<=old['end_ms']:
            raise ValueError('finish old prefix before extension')
        manifest=read(source)
        if not manifest.get('complete'):raise ValueError('unsealed source manifest')
        files=[]
        for row in manifest['files']:
            path=(source.parent/row['path']).resolve()
            if not path.is_relative_to(source.parent) or file_hash(path)!=row['sha256']:
                raise ValueError('extended source hash/path mismatch')
            files.append({'path':str(path),'sha256':row['sha256']})
        if files[:len(old['files'])]!=old['files'] or len(files)<=len(old['files']):
            raise ValueError('extension must preserve exact source prefix')
        root.mkdir(parents=True,exist_ok=False)
        p={**old,'files':files,'source_manifest':str(source),'source_manifest_sha256':file_hash(source),
           'end_ms':end_ms,'parent_protocol_hash':digest(old)}
        atomic(root/'protocol.json',p);atomic(root/'protocol-seal.json',{'hash':digest(p)})
        atomic(root/'checkpoint.json.gz',{**state,'protocol_hash':digest(p),'complete':False})


def main():
    ap=argparse.ArgumentParser(description=__doc__);sub=ap.add_subparsers(dest='command',required=True)
    f=sub.add_parser('freeze');f.add_argument('--source',type=Path,required=True);f.add_argument('--root',type=Path,required=True)
    for name in ('start-ms','end-ms','latency-ms'):f.add_argument('--'+name,type=int,required=True)
    for name in ('fee-rate','cost-id'):f.add_argument('--'+name,required=True)
    f.add_argument('--exposure',choices=['SEEN','UNSEEN'],required=True);f.add_argument('--snapshot',type=Path)
    r=sub.add_parser('run');r.add_argument('--root',type=Path,required=True);r.add_argument('--max-events',type=int)
    b=sub.add_parser('fit');b.add_argument('--records',type=Path,required=True);b.add_argument('--out',type=Path,required=True)
    b.add_argument('--cutoff-ms',type=int,required=True);b.add_argument('--cost-id',required=True)
    x=sub.add_parser('extend');x.add_argument('--previous',type=Path,required=True);x.add_argument('--source',type=Path,required=True)
    x.add_argument('--root',type=Path,required=True);x.add_argument('--end-ms',type=int,required=True)
    a=sub.add_parser('account');a.add_argument('--tape',type=Path,required=True);a.add_argument('--parameters',type=Path,required=True);a.add_argument('--out',type=Path,required=True)
    a.add_argument('--snapshot',type=Path);a.add_argument('--matrix',action='store_true')
    args=ap.parse_args()
    if args.command=='freeze':freeze(args.source,args.root,start_ms=args.start_ms,end_ms=args.end_ms,fee_rate=args.fee_rate,latency_ms=args.latency_ms,cost_id=args.cost_id,exposure=args.exposure,snapshot=args.snapshot)
    elif args.command=='run':print(json.dumps(run(args.root,max_events=args.max_events),ensure_ascii=False))
    elif args.command=='extend':extend(args.previous,args.source,args.root,end_ms=args.end_ms)
    elif args.command=='fit':
        if args.out.exists():raise ValueError('refusing to overwrite posterior')
        rows=[r for r in read(args.records) if r['policy'].startswith('AB_WINDOW/')]
        atomic(args.out,fit(rows,cutoff_ms=args.cutoff_ms,cost_id=args.cost_id))
    else:
        if args.out.exists():raise ValueError('refusing to overwrite account result')
        value=read(args.parameters)
        for k in ('min_net_r','band_atr','bayes_threshold'):
            if value.get(k) is not None:value[k]=D(str(value[k]))
        runner=account_matrix if args.matrix else account_replay
        atomic(args.out,runner(read(args.tape),Parameters(**value),read(args.snapshot) if args.snapshot else None))


if __name__=='__main__':main()
