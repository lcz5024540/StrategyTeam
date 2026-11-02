"""Export old effective holding parameters without changing the old worker/DB."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

from ..continuation import BASELINE
from ..exit_config import ExitConfig
from ..holding_adoption import HoldingAdoption, holding_identity
from ..holding_policy import (HoldingPolicy, HoldingPolicyError, _decode_config,
                              implementation_hashes, paper_exit_config, supports_observed_implementation)
from ..java_entry_contract import JavaEntryConfig
from ..timeframes import TimeframeProfile


def from_observation(observed: dict) -> HoldingAdoption:
    if not supports_observed_implementation(observed['implementation_hashes']):
        raise HoldingPolicyError('old decision implementation is not supported by this package')
    holdings=[]
    for item in observed['holdings']:
        state=item['state']; identity=holding_identity(item['identity'],mode=observed['mode'])
        profile=TimeframeProfile(identity['timeframe_profile'] if observed['mode']=='LIVE' else item['profile'])
        if state.get('holding_policy') is not None:
            # A later export preserves the per-position policy, never replaces
            # it with whatever parameters happen to be in the current process.
            policy=HoldingPolicy.load(state['holding_policy'])
        else:
            effective=observed['profiles'][profile.value]
            policy=HoldingPolicy.create(instance_key=observed['instance_key'],account_id=observed['account_id'],
                strategy_family=observed['strategy_family'],
                strategy_name=identity.get('strategy_name',observed['strategy_name']),mode=observed['mode'],profile=profile,
                entry_config=_decode_config(JavaEntryConfig,effective['entry']),
                exit_config=(_decode_config(ExitConfig,effective['exit']) if observed['mode']=='LIVE' else paper_exit_config()),
                continuation_version=state.get('continuation_version',BASELINE))
        holdings.append(dict(identity=identity,policy=policy.to_dict()))
    return HoldingAdoption.create(instance_key=observed['instance_key'],account_id=observed['account_id'],
        mode=observed['mode'],observed_at=observed['observed_at'],source_commit=observed['source_commit'],
        source_package=observed['source_package'],holdings=holdings)


def _probe(package: Path, environment: dict, instance_key: str) -> dict:
    environment=dict(environment)
    environment.pop('PYTHONPATH',None)
    environment.pop('PYTHONHOME',None)
    result=subprocess.run([sys.executable,str(Path(__file__).with_name('holding_probe.py')),
        '--package',str(package),'--instance',instance_key],env=environment,cwd=package,
        capture_output=True,text=True,timeout=60)
    if result.returncode:
        # DB/credential libraries may include connection details in exception
        # strings. Do not echo child stderr from a credential-bearing process.
        raise HoldingPolicyError('read-only old-package probe failed; verify package, instance and database access')
    return json.loads(result.stdout)


def process_observation(pid: int, instance_key: str) -> dict:
    process=Path('/proc')/str(pid)
    # /proc stat starttime disambiguates PID reuse. Use the same record around
    # the probe and refuse an export spanning a process/package replacement.
    started=(process/'stat').read_text().rsplit(')',1)[1].split()[19]
    command=(process/'cmdline').read_bytes().decode().split('\0')
    verify_process_instance(command, instance_key)
    package=(process/'cwd').resolve(strict=True)
    environment=dict(item.split('=',1) for item in (process/'environ').read_text().split('\0') if '=' in item)
    observed=_probe(package,environment,instance_key)
    if ((process/'stat').read_text().rsplit(')',1)[1].split()[19]!=started
            or (process/'cwd').resolve(strict=True)!=package):
        raise HoldingPolicyError('old worker changed during export; repeat the read-only snapshot')
    return observed


def verify_process_instance(command: list[str], instance_key: str) -> None:
    worker = ('htx_trend.worker' in command or any(
        item.endswith('/htx_trend/worker.py') for item in command))
    bindings = [command[i+1] for i, value in enumerate(command[:-1]) if value == '--strategy-instance']
    bindings += [value.split('=',1)[1] for value in command if value.startswith('--strategy-instance=')]
    if not worker or bindings != [instance_key]:
        raise HoldingPolicyError('old PID is not the requested strategy worker instance')


def environment_observation(package: Path, env_file: Path, instance_key: str) -> dict:
    """Stopped legacy worker: use its explicit old env file, never new env defaults."""
    environment={key:os.environ[key] for key in ('PATH','HOME','LANG','LC_ALL') if key in os.environ}
    for line in env_file.read_text().splitlines():
        line=line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line=line[7:]
        key,separator,raw=line.partition('=')
        if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',key):
            raise HoldingPolicyError('old env must contain literal KEY=value assignments')
        parts=shlex.split(raw,comments=True,posix=True)
        if len(parts)>1 or '$' in raw or '`' in raw:
            raise HoldingPolicyError('old env expansion is unsupported; provide effective literal values')
        environment[key]=parts[0] if parts else ''
    return _probe(package.resolve(strict=True),environment,instance_key)


def main():
    parser=argparse.ArgumentParser(description='Read-only old-process holding policy export; no orders or DB writes')
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--pid',type=int)
    source.add_argument('--old-package',type=Path)
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--instance',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.old_package is not None and args.env_file is None:
        parser.error('--old-package requires the old --env-file')
    if args.pid is not None and args.env_file is not None:
        parser.error('--pid reads its effective environment; do not mix --env-file')
    observed=(process_observation(args.pid,args.instance) if args.pid is not None
              else environment_observation(args.old_package,args.env_file,args.instance))
    value=from_observation(observed).to_dict()
    # A new explicit artifact; never clobber a previous cutover record.
    descriptor=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'w') as stream:
        json.dump(value,stream,ensure_ascii=False,sort_keys=True,indent=2)
        stream.write('\n');stream.flush();os.fsync(stream.fileno())
    print(json.dumps(dict(path=str(args.output),holdings=len(value['holdings']),hash=value['hash'])))


if __name__=='__main__':
    main()
