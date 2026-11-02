"""Read-only deployment/rollback compatibility assessment; never starts a worker."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from ..holding_adoption import HoldingAdoption
from ..holding_policy import HoldingPolicyError
from ..runtime_checkpoint import RuntimeCheckpoint
from .holding_export import from_observation, process_observation, environment_observation


def assess(observed, *, instance_key, strategy_names, profiles, adoption=None):
    issues=[]
    if observed['instance_key']!=instance_key:
        issues.append('INSTANCE_MISMATCH')
    try:
        inferred=from_observation(observed)
    except (ValueError,KeyError) as error:
        return dict(ready=False,issues=['UNSUPPORTED_OLD_PARAMETERS:'+type(error).__name__])
    policies=[]
    for item in observed['holdings']:
        row,state=item['identity'],item['state']
        profile=row.get('timeframe_profile',item.get('profile'))
        strategy=row.get('strategy_name',observed['strategy_name'])
        if profile not in profiles:issues.append('UNMANAGED_PROFILE:'+str(profile))
        if strategy not in strategy_names:issues.append('UNMANAGED_STRATEGY:'+str(strategy))
        try:
            expected=inferred.policy_for(row,state,instance_key=instance_key,mode=observed['mode'])
            if state.get('holding_policy') is None:
                if adoption is None:
                    issues.append('ADOPTION_REQUIRED:'+str(row.get('id',row.get('position_id'))))
                else:
                    actual=adoption.policy_for(row,state,instance_key=instance_key,mode=observed['mode'])
                    if actual.to_dict()!=expected.to_dict():issues.append('OLD_PARAMETER_MISMATCH')
            policies.append(expected.to_dict())
        except (ValueError,KeyError) as error:
            issues.append('HOLDING_NOT_ACCEPTED:'+type(error).__name__)
    ids={str(item['identity'].get('id',item['identity'].get('position_id'))) for item in observed['holdings']}
    for order in observed.get('pending_orders',[]):
        if str(order['position_id']) not in ids:issues.append('PENDING_ORDER_WITHOUT_HOLDING')
        if order.get('strategy_name') not in strategy_names:issues.append('PENDING_OWNER_NOT_ACCEPTED')
        if order.get('timeframe_profile') not in profiles:issues.append('PENDING_PROFILE_NOT_ACCEPTED')
    return dict(ready=not issues,issues=list(dict.fromkeys(issues)),instance_key=instance_key,
        mode=observed['mode'],account_id=observed['account_id'],observed_at=observed['observed_at'],
        source_commit=observed['source_commit'],holdings=len(observed['holdings']),
        pending_orders=len(observed.get('pending_orders',[])),policies=policies)


def target_accepts(target: Path, policies: list, *, has_pending=False, checkpoint=None):
    """Load target validation code in a fresh interpreter, never import its worker."""
    root=target.resolve(strict=True)
    if not policies and not has_pending and checkpoint is None:
        return (root/'src/htx_trend/worker.py').is_file()
    if not (root/'src/htx_trend/holding_policy.py').is_file() or not (root/'src/htx_trend/runtime_checkpoint.py').is_file():
        return False
    script='''import json,sys
from pathlib import Path
sys.path.insert(0,str(Path(sys.argv[1])/'src'))
from htx_trend.holding_policy import HoldingPolicy
import htx_trend.holding_policy as policy_module
if not Path(policy_module.__file__).resolve().is_relative_to(Path(sys.argv[1]).resolve()):
    raise ValueError('target package import mismatch')
from htx_trend.runtime_checkpoint import SCHEMA, decode
if SCHEMA != 'htx-runtime-continuity-v1': raise ValueError('unsupported runtime protocol')
payload=json.load(sys.stdin)
for item in payload['policies']: HoldingPolicy.load(item)
checkpoint=payload.get('checkpoint')
if checkpoint:
    if checkpoint['schema']!=SCHEMA: raise ValueError('checkpoint protocol mismatch')
    for raw in checkpoint['profiles'].values():
        state=decode(raw)
        if 'last_scanned' in state:
            from htx_trend.live_worker import LiveTrendWorker
            LiveTrendWorker.validate_runtime_state(state)
        elif set(state) not in ({'ledger_path','failures'},{'completed','last_health'}):
            raise ValueError('unknown runtime state fields')
'''
    result=subprocess.run([sys.executable,'-c',script,str(root)],input=json.dumps(dict(policies=policies,checkpoint=checkpoint)),
        text=True,capture_output=True,timeout=30,cwd=root,
        env={k:v for k,v in os.environ.items() if k not in ('PYTHONPATH','PYTHONHOME')})
    return result.returncode==0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--pid',type=int)
    source.add_argument('--old-package',type=Path)
    source.add_argument('--observation',type=Path,help='offline saved observation; not current production acceptance')
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--instance',required=True)
    parser.add_argument('--strategy',action='append',required=True)
    parser.add_argument('--profile',action='append',required=True,choices=['INTRADAY_15M','SWING_4H'])
    parser.add_argument('--adoption',type=Path)
    parser.add_argument('--runtime-checkpoint',type=Path)
    parser.add_argument('--rollback-package',type=Path)
    args=parser.parse_args()
    if args.old_package and not args.env_file:parser.error('--old-package requires --env-file')
    observed=(json.loads(args.observation.read_text()) if args.observation else
        process_observation(args.pid,args.instance) if args.pid else
        environment_observation(args.old_package,args.env_file,args.instance))
    result=assess(observed,instance_key=args.instance,strategy_names=set(args.strategy),profiles=set(args.profile),
        adoption=HoldingAdoption.read(args.adoption) if args.adoption else None)
    data=None
    if args.runtime_checkpoint:
        data=RuntimeCheckpoint.read(args.runtime_checkpoint)
        if data['identity']!=dict(instance_key=args.instance,account_id=observed['account_id'],mode=observed['mode'],strategy_family=observed['strategy_family']):
            result['issues'].append('RUNTIME_IDENTITY_MISMATCH')
    if args.rollback_package and not target_accepts(args.rollback_package,result.get('policies',[]),has_pending=bool(result.get('pending_orders')),checkpoint=data):
        result['issues'].append('ROLLBACK_PACKAGE_CANNOT_MANAGE_CURRENT_HOLDINGS')
    result['ready']=not result['issues']
    result['offline_only']=args.observation is not None
    result.pop('policies',None)
    print(json.dumps(result,ensure_ascii=False,sort_keys=True))
    if not result['ready']:raise SystemExit(2)


if __name__=='__main__':main()
