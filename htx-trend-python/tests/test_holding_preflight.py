from dataclasses import asdict
from pathlib import Path
import json

from htx_trend.holding_policy import canonical, implementation_hashes, ExitConfig, JavaEntryConfig
from htx_trend.tools.holding_export import from_observation
from htx_trend.tools.holding_preflight import assess, target_accepts


def observation():
    return json.loads(canonical(dict(instance_key='p',account_id='7',mode='LIVE',strategy_family='TREND',
        strategy_name='HTX_TREND_V1',observed_at='2026-11-02T10:00:00+00:00',source_commit='a'*40,
        source_package='old',implementation_hashes=implementation_hashes(),
        profiles={'INTRADAY_15M':dict(entry=asdict(JavaEntryConfig()),exit=asdict(ExitConfig()))},
        holdings=[dict(identity=dict(id=1,position_key='p1',account_id=7,strategy_family='TREND',
            strategy_name='HTX_TREND_V1',contract_code='BTC-USDT',side='LONG',timeframe_profile='INTRADAY_15M'),state={})],
        pending_orders=[])))


def check(value,**kw):
    return assess(value,instance_key='p',strategy_names={'HTX_TREND_V1'},profiles={'INTRADAY_15M'},**kw)


def test_legacy_requires_complete_current_adoption():
    value=observation()
    assert not check(value)['ready']
    manifest=from_observation(value)
    assert check(value,adoption=manifest)['ready']
    value['holdings'][0]['identity']['id']=2
    assert not check(value,adoption=manifest)['ready']


def test_previous_parameter_mismatch_and_unknown_pending_are_blocked():
    value=observation();manifest=from_observation(value)
    value['profiles']['INTRADAY_15M']['exit']['flat_bars']=99
    assert 'OLD_PARAMETER_MISMATCH' in check(value,adoption=manifest)['issues']
    value=observation();value['pending_orders']=[dict(position_id=99,strategy_name='OTHER',timeframe_profile='SWING_4H')]
    report=check(value,adoption=manifest)
    assert not report['ready'] and 'PENDING_ORDER_WITHOUT_HOLDING' in report['issues']


def test_missing_alias_or_profile_blocks_acceptance():
    value=observation();manifest=from_observation(value)
    result=assess(value,instance_key='p',strategy_names={'NEW'},profiles={'SWING_4H'},adoption=manifest)
    assert not result['ready']
    assert 'UNMANAGED_PROFILE:INTRADAY_15M' in result['issues']
    assert 'UNMANAGED_STRATEGY:HTX_TREND_V1' in result['issues']


def test_rollback_must_load_its_own_compatible_adapter(tmp_path):
    value=observation();policies=check(value,adoption=from_observation(value))['policies']
    assert target_accepts(Path(__file__).parents[1],policies)
    # Parent PYTHONPATH/installations must not supply a missing target adapter.
    assert not target_accepts(tmp_path,policies)
    changed=json.loads(json.dumps(policies));changed[0]['hash']='invalid'
    assert not target_accepts(Path(__file__).parents[1],changed)


def test_target_must_decode_actual_checkpoint_fields():
    from htx_trend.runtime_checkpoint import encode,SCHEMA
    value=observation();policies=check(value,adoption=from_observation(value))['policies']
    checkpoint=dict(schema=SCHEMA,profiles={'INTRADAY_15M':encode({'last_scanned':1,'pending':{}})})
    assert not target_accepts(Path(__file__).parents[1],policies,checkpoint=checkpoint)
