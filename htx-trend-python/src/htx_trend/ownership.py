"""Explicit previous strategy owners within one stable instance/account.

An alias never changes the instance key: order accounting must release the
original reservation and retain the original strategy in every ledger event.
"""
from __future__ import annotations

from copy import copy
from dataclasses import replace
import json
import os
import re

from .mysql_store import RuntimeInstance
from .holding_policy import HoldingPolicyError


def managed_instances(instance: RuntimeInstance, names: tuple[str, ...] = ()) -> tuple[RuntimeInstance, ...]:
    if instance.execution_mode != 'LIVE' or instance.account_id is None:
        raise HoldingPolicyError('managed aliases require a LIVE account-bound instance')
    if any(not isinstance(name, str) or not re.fullmatch(r'[A-Z][A-Z0-9_]{0,95}', name) for name in names):
        raise HoldingPolicyError('previous strategy names must be explicit identifiers')
    return tuple(replace(instance, strategy_name=name) for name in dict.fromkeys((instance.strategy_name, *names)))


def owners_from_env(instance: RuntimeInstance) -> tuple[RuntimeInstance, ...]:
    names = json.loads(os.environ.get('HTX_MANAGED_PREVIOUS_STRATEGIES', '[]'))
    if not isinstance(names, list):
        raise HoldingPolicyError('HTX_MANAGED_PREVIOUS_STRATEGIES must be a JSON array')
    return managed_instances(instance, tuple(names))


class OwnedPositionManagers:
    """One profile, one manager per original owner; never broaden SQL filters."""
    def __init__(self, manager, owners, allowed_profiles=None):
        self.allowed_profiles=set(allowed_profiles or (getattr(manager,'timeframe_profile',None),))
        self.managers = []
        for owner in owners:
            owned = copy(manager)
            owned.instance = owner
            self.managers.append(owned)

    def manage(self, now_epoch, context):
        failures = []
        for manager in self.managers:
            if hasattr(manager,'repo'):
                from .timeframes import TimeframeProfile
                for row in manager.repo.managed_positions(manager.instance):
                    profile=TimeframeProfile(row.get('timeframe_profile') or 'INTRADAY_15M')
                    if profile not in self.allowed_profiles:
                        failures.append(f'position={row["id"]}:profile has no configured management worker')
            failures.extend(manager.manage(now_epoch, context))
        return failures
