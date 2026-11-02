"""Explicit legacy holding adoption, bound to an observed inventory and policy.

The exporter supplies the OLD process's effective parameters. This module never
infers them from the new process environment. Quantity/protection counters are
not imported: those remain authoritative in the live ledger at adoption time.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path

from .continuation import BASELINE
from .holding_policy import HoldingPolicy, HoldingPolicyError, canonical, digest

SCHEMA = 'htx-holding-adoption-v1'


def holding_identity(row: dict, *, mode: str) -> dict:
    if mode == 'LIVE':
        fields = ('id','position_key','account_id','strategy_family','strategy_name',
                  'contract_code','side','timeframe_profile')
    elif mode == 'PAPER':
        fields = ('position_id','account_id','contract_code','side','opened_at','strategy_run_id')
    else:
        raise HoldingPolicyError('unknown adoption mode')
    if any(row.get(key) is None or str(row[key]) == '' for key in fields):
        raise HoldingPolicyError('legacy holding identity incomplete')
    return {key: str(row[key]) for key in fields}


@dataclass(frozen=True)
class HoldingAdoption:
    encoded: bytes

    @classmethod
    def create(cls, *, instance_key: str, account_id, mode: str, observed_at: str,
               source_commit: str, source_package: str, holdings: list[dict]) -> 'HoldingAdoption':
        value = dict(schema=SCHEMA, instance_key=instance_key, account_id=str(account_id), mode=mode,
                     observed_at=observed_at, source_commit=source_commit, source_package=source_package,
                     holdings=holdings)
        return cls.load({**value, 'hash': digest(value)})

    @classmethod
    def load(cls, value: dict) -> 'HoldingAdoption':
        if not isinstance(value, dict) or set(value) != {'schema','instance_key','account_id','mode',
                'observed_at','source_commit','source_package','holdings','hash'}:
            raise HoldingPolicyError('incomplete adoption manifest')
        payload = {k:v for k,v in value.items() if k != 'hash'}
        if value['hash'] != digest(payload) or value['schema'] != SCHEMA:
            raise HoldingPolicyError('adoption manifest hash/schema mismatch')
        if any(not isinstance(value[key],str) or not value[key] for key in
               ('instance_key','account_id','observed_at','source_commit','source_package')):
            raise HoldingPolicyError('adoption source provenance required')
        if value['mode'] not in ('LIVE','PAPER') or not isinstance(value['holdings'],list):
            raise HoldingPolicyError('invalid adoption mode/inventory')
        seen=set()
        for item in value['holdings']:
            if not isinstance(item,dict) or set(item) != {'identity','policy'}:
                raise HoldingPolicyError('invalid adoption holding')
            identity=holding_identity(item['identity'],mode=value['mode'])
            if identity != item['identity']:
                raise HoldingPolicyError('adoption identity must be canonical')
            key=identity['id' if value['mode']=='LIVE' else 'position_id']
            if key in seen:
                raise HoldingPolicyError('duplicate adoption holding')
            seen.add(key)
            policy=HoldingPolicy.load(item['policy'])
            owner=policy.owner
            if (owner['instance_key'] != value['instance_key'] or owner['account_id'] != value['account_id']
                    or owner['execution_mode'] != value['mode'] or identity['account_id'] != value['account_id']):
                raise HoldingPolicyError('adoption ownership mismatch')
            if value['mode']=='LIVE' and any(identity[k]!=owner[k] for k in
                    ('strategy_family','strategy_name','timeframe_profile')):
                raise HoldingPolicyError('adoption strategy/profile mismatch')
        return cls(canonical(value))

    @classmethod
    def read(cls, path: Path) -> 'HoldingAdoption':
        return cls.load(json.loads(path.read_text()))

    def to_dict(self):
        return json.loads(self.encoded)

    def policy_for(self, row: dict, state: dict, *, instance_key: str, mode: str) -> HoldingPolicy:
        data=self.to_dict()
        if data['mode'] != mode or data['instance_key'] != instance_key:
            raise HoldingPolicyError('adoption instance/mode mismatch')
        identity=holding_identity(row,mode=mode)
        match=next((item for item in data['holdings'] if item['identity']==identity),None)
        if match is None:
            raise HoldingPolicyError('holding is absent from verified adoption inventory')
        policy=HoldingPolicy.load(match['policy'])
        if policy.continuation_version != state.get('continuation_version',BASELINE):
            raise HoldingPolicyError('legacy continuation version differs from adoption')
        return policy


def adopted_state(state: dict, policy: HoldingPolicy) -> dict:
    """Pure merge: never replace protection, quantities, timestamps or intents."""
    if state.get('holding_policy') is not None:
        existing=HoldingPolicy.load(state['holding_policy'])
        if existing.encoded != policy.encoded:
            raise HoldingPolicyError('immutable holding policy already exists')
        return dict(state)
    if state.get('continuation_version',BASELINE) != policy.continuation_version:
        raise HoldingPolicyError('adoption cannot change continuation version')
    return {**state, 'holding_policy':policy.to_dict()}
