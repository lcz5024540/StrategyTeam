"""Auditable grouped Bayesian inference; no online training or order submission.

Beta-Bernoulli posterior describes completed labels under an iid approximation.
Dirichlet weights over synchronized time blocks expose dependence sensitivity.
Neither model repairs outcome censoring, selection bias or unseen market tails.
Numpy is loaded only by the offline fitting function, never by the runtime gate.
"""
from collections import Counter, defaultdict
from functools import lru_cache
from hashlib import sha256
import json
from pathlib import Path
from decimal import Decimal as D

from ..research.store import digest

SCHEMA = "regime-group-posterior-v1"
METHOD = "beta11-block-dirichlet-v1"


@lru_cache(maxsize=1)
def code_seal():
    root = Path(__file__).parent
    return digest({n:sha256((root/n).read_bytes()).hexdigest()
                   for n in ("regime.py", "indicator_union.py", "regime_bayes.py")})


def group_key(scenario, timing, exit_profile, cost_id):
    return json.dumps([scenario, "REGIME_AB_V1/"+timing, exit_profile, cost_id], separators=(",", ":"))


def fit(records, *, cutoff_ms, cost_id, seed=20261105):
    import numpy as np
    from .indicator_union import EXITS

    if type(cutoff_ms) is not int or not cost_id:
        raise ValueError("explicit cutoff and cost identity required")
    unique = {}
    excluded = Counter()
    all_ids = []
    for raw in records:
        r = dict(raw)
        if r.get("exposure") in ("PROTECTED", "UNSEEN"):
            raise ValueError("training cannot consume protected or unseen labels")
        if r.get("exposure") != "SEEN":
            raise ValueError("explicit training exposure required")
        if (r.get("scenario") not in ("A","B") or r.get("timing") not in ("WINDOW","FIRST_QUOTE")
                or r.get("exit_profile") not in EXITS or r.get("rule") != "REGIME_AB_V1"):
            raise ValueError("label rule identity mismatch")
        if not r.get("opportunity_id") or not r.get("contract") or not r.get("source_refs"):
            raise ValueError("label identity/provenance missing")
        if r.get("cost_id") != cost_id:
            raise ValueError("mixed cost experiments")
        key = (group_key(r['scenario'],r['timing'],r['exit_profile'],cost_id), r['opportunity_id'])
        if key in unique:
            if unique[key] != r:
                raise ValueError("conflicting duplicate label")
            excluded['duplicate'] += 1
            continue
        unique[key] = r
    groups = defaultdict(list)
    for (key, ident), r in sorted(unique.items(), key=lambda item:(item[1].get('entry_ms') if item[1].get('entry_ms') is not None else item[1].get('trigger_ms',0),item[0])):
        all_ids.append(digest(r))
        if r.get('status') != 'CLOSED':
            excluded[r.get('status','UNKNOWN')] += 1
            continue
        if r.get('cost_status') not in ('KNOWN','ASSUMPTION'):
            excluded['UNKNOWN_COST'] += 1
            continue
        if r['cost_status']=='ASSUMPTION' and not cost_id.startswith('ASSUMPTION:'):
            raise ValueError('assumed costs require a named assumption experiment')
        if any(type(r.get(k)) is not int for k in ('entry_ms','exit_ms','observed_ms')):
            raise ValueError('label times must be integer milliseconds')
        if not r['entry_ms'] <= r['exit_ms'] <= r['observed_ms']:
            raise ValueError('outcome precedes entry or observation')
        if r['observed_ms'] >= cutoff_ms:
            excluded['IMMATURE_AT_CUTOFF'] += 1
            continue
        value = D(str(r['net_return']))
        if not value.is_finite():
            raise ValueError('nonfinite label')
        groups[key].append(r)
    result = {}
    for scenario in ('A','B'):
        for timing in ('FIRST_QUOTE','WINDOW'):
            for exit_profile in EXITS:
                key = group_key(scenario,timing,exit_profile,cost_id)
                end_by_contract = {}
                rows = []
                for r in groups[key]:
                    if r['entry_ms'] < end_by_contract.get(r['contract'], -1):
                        excluded['OVERLAPPING_SAME_CONTRACT'] += 1
                        continue
                    rows.append(r)
                    end_by_contract[r['contract']] = r['exit_ms']
                n = len(rows)
                w = sum(D(str(r['net_return'])) > 0 for r in rows)
                local_seed = seed + int(sha256(key.encode()).hexdigest()[:8],16)
                rng = np.random.default_rng(local_seed)
                probabilities = rng.beta(w+1,n-w+1,50000)
                blocks = {}
                for minutes in (30,60,120):
                    by_block = defaultdict(list)
                    for r in rows:
                        by_block[r['entry_ms']//(minutes*60000)].append(float(r['net_return']))
                    k = len(by_block)
                    b = {'count':k,'draws':10000,'available':k>=2}
                    if k>=2:
                        vals = list(by_block.values())
                        counts = np.array([len(v) for v in vals])
                        sums = np.array([sum(v) for v in vals])
                        means = []
                        for start in range(0,10000,256):
                            weights = rng.dirichlet(np.ones(k),min(256,10000-start))
                            means.extend(((weights@sums)/(weights@counts)).tolist())
                        a = np.asarray(means)
                        b.update(mean_ci95=np.quantile(a,[.025,.975]).tolist(),
                                 positive_mean_mass=float(np.mean(a>0)))
                    blocks[str(minutes)] = b
                result[key] = {'n':n, 'wins':w, 'nonpositive':n-w,
                    'zero':sum(D(str(r['net_return']))==0 for r in rows),
                    'prior':[1,1], 'posterior':[1+w,1+n-w],
                    'posterior_mean':(w+1)/(n+2) if n else None,
                    'posterior_ci95':np.quantile(probabilities,[.025,.975]).tolist() if n else None,
                    'historical_win_rate':w/n if n else None,
                    'mean_net_return':sum(float(r['net_return']) for r in rows)/n if n else None,
                    'blocks':blocks,'seed':local_seed,'draws':50000,
                    'label_ids':[r['opportunity_id'] for r in rows],
                    'eligible':n>=20 and blocks['60']['count']>=10,
                    'status':'ESTIMATED' if n>=20 and blocks['60']['count']>=10 else 'INSUFFICIENT_EVIDENCE',
                    'limitations':['selected completed subset','iid approximation for Beta',
                        'time-block dependence remains','not calibrated future probabilities',
                        *(['observed support contains no loss'] if n and w==n else [])]}
    payload = {'schema':SCHEMA,'method':METHOD,'code_seal':code_seal(),'cost_id':cost_id,
        'cost_mode':'ASSUMPTION' if cost_id.startswith('ASSUMPTION:') else 'KNOWN',
        'cutoff_ms':cutoff_ms,'seed':seed,'groups':result,'excluded':dict(excluded),
        'input_digest':digest(sorted(all_ids)), 'financial_admission':'NOT_ELIGIBLE'}
    return {**payload,'hash':digest(payload)}


class Snapshot:
    def __init__(self, value):
        value = json.loads(json.dumps(value,allow_nan=False))
        claimed = value.pop('hash')
        if digest(value) != claimed:
            raise ValueError('snapshot hash mismatch')
        if (value.get('schema') != SCHEMA or value.get('method') != METHOD
                or value.get('code_seal') != code_seal()):
            raise ValueError('snapshot method/code changed')
        self.value, self.hash = value, claimed

    @classmethod
    def load(cls,path):
        return cls(json.loads(Path(path).read_text()))

    def assess(self, scenario, parameters, now_ms):
        v = self.value
        base = {'allowed':False,'snapshot_hash':self.hash,'cutoff_ms':v['cutoff_ms']}
        if self.hash != parameters.bayes_snapshot_hash or v['cost_id'] != parameters.bayes_cost_id:
            return {**base,'reason':'SNAPSHOT_BINDING_MISMATCH'}
        if v['cutoff_ms'] > now_ms:
            return {**base,'reason':'FUTURE_TRAINING_CUTOFF'}
        group = v['groups'].get(group_key(scenario,parameters.regime_timing,parameters.target_mode,v['cost_id']))
        if not group or group['n']<20 or group['blocks']['60']['count']<10:
            return {**base,'reason':'INSUFFICIENT_EVIDENCE'}
        mass = D(str(group['blocks']['60']['positive_mean_mass']))
        if not mass.is_finite() or not 0 <= mass <= 1:
            raise ValueError('invalid posterior mass')
        allowed = mass >= parameters.bayes_threshold
        return {**base,'allowed':allowed,'reason':'PASS' if allowed else 'NET_EXPECTATION_UNCERTAIN',
                'positive_mean_mass':str(mass),'posterior_mean':group['posterior_mean'],
                'posterior_ci95':group['posterior_ci95'],'n':group['n'],
                'hour_blocks':group['blocks']['60']['count'],'cost_mode':v['cost_mode']}


def bind_snapshot(engine, path, *, live=False):
    if engine.parameters.bayes_threshold is None:
        return
    try:
        snapshot = Snapshot.load(path)
        if live and snapshot.value['cost_mode'] != 'KNOWN':
            raise ValueError('assumption-only posterior cannot authorize LIVE')
        engine.snapshot = snapshot
    except (OSError, ValueError, KeyError, TypeError) as error:
        engine.snapshot = None
        engine.snapshot_error = 'SNAPSHOT_UNAVAILABLE:' + type(error).__name__
