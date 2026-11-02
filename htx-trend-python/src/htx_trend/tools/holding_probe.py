"""Read-only probe executed with an OLD package on sys.path and its process env.

Run as a script, not -m: importing the new package would use new defaults. The
parent exporter validates the output; credentials are used only for the read-only
DB connection and never included in the explicitly selected output fields.
"""
import argparse
from dataclasses import asdict
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import sys


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--package',required=True)
    parser.add_argument('--instance',required=True)
    args=parser.parse_args()
    root=Path(args.package).resolve()
    sys.path.insert(0,str(root/'src'))
    from htx_trend.mysql_store import HtxTrendMySqlRepository, MySqlSettings, connect
    from htx_trend.position_manager import ExitConfig
    from htx_trend.live_worker import LiveTrendWorkerConfig
    from htx_trend.java_entry_contract import JavaEntryConfig
    from htx_trend.strategy_profiles import profile_for_version, profile_entry_config
    from htx_trend.strategy_decision import LEGACY_STRATEGY_VERSION
    from htx_trend.timeframes import TimeframeProfile
    # Explicit source validation prevents the interpreter's installed editable
    # package from silently supplying a different release.
    import htx_trend.position_manager as pm
    if not Path(pm.__file__).resolve().is_relative_to(root):
        raise RuntimeError('probe did not import the specified old package')
    manifest=json.loads((root/'MANIFEST.json').read_text())
    source=manifest.get('source_commit')
    if not source:
        raise RuntimeError('old package manifest source_commit required')
    files=('exits.py','continuation.py','java_entry_contract.py','indicators.py','models.py',
           'timeframes.py','position_manager.py','runtime.py')
    hashes={name:sha256((root/'src'/'htx_trend'/name).read_bytes()).hexdigest() for name in files}
    connection=connect(MySqlSettings.from_env())
    repo=HtxTrendMySqlRepository(connection)
    try:
        with connection.cursor() as cursor:
            cursor.execute('START TRANSACTION READ ONLY')
        instance=repo.runtime_instance(args.instance)
        if instance is None or instance.strategy_family!='TREND' or instance.execution_mode not in ('LIVE','PAPER'):
            raise RuntimeError('registered trend LIVE/PAPER instance required')
        out=dict(instance_key=instance.instance_key,account_id=str(instance.account_id),mode=instance.execution_mode,
            strategy_family=instance.strategy_family,strategy_name=instance.strategy_name,
            source_commit=source,source_package=str(root),implementation_hashes=hashes,
            observed_at=datetime.now(timezone.utc).isoformat(),holdings=[],profiles={})
        if instance.execution_mode=='LIVE':
            configs=LiveTrendWorkerConfig.profiles_from_env(strategy_version=instance.strategy_name)
            out['profiles']={c.timeframe_profile.value:dict(entry=asdict(c.java_entry),exit=asdict(ExitConfig.from_env()))
                             for c in configs}
            for row in repo.managed_positions(instance):
                identity={k:row[k] for k in ('id','position_key','account_id','strategy_family','strategy_name',
                                             'contract_code','side','timeframe_profile')}
                out['holdings'].append(dict(identity=identity,state=repo.exit_state(row['id'])))
        else:
            profile=profile_for_version(instance.strategy_name) or TimeframeProfile.INTRADAY_15M
            config=JavaEntryConfig.from_env() if instance.strategy_name==LEGACY_STRATEGY_VERSION else profile_entry_config(profile)
            out['account_id']='paper:'+instance.instance_key
            out['profiles']={profile.value:dict(entry=asdict(config))}
            state_root=Path(os.environ.get('HTX_PAPER_STATE_DIR','./paper-state'))
            path=state_root/(instance.instance_key+'.json')
            ledger=json.loads(path.read_text())
            if ledger['identity']['instance']!=instance.instance_key or ledger['identity']['strategy']!=instance.strategy_name:
                raise RuntimeError('old PAPER state ownership mismatch')
            for key,row in ledger['positions'].items():
                if row['status']=='OPEN':
                    identity={k:row[k] for k in ('position_id','account_id','contract_code','side','opened_at','strategy_run_id')}
                    out['holdings'].append(dict(identity=identity,state=ledger['contexts'][key],profile=profile.value))
    finally:
        connection.rollback();connection.close()
    print(json.dumps(out,default=str,sort_keys=True))


if __name__=='__main__':
    main()
