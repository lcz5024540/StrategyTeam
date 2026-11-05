"""Immutable, verifiable ownership and effective parameters for an open holding.

No credentials, I/O side effects or environment reads. A release with changed
decision implementations must retain an adapter or refuse compatibility; a
version label alone is not proof that an old holding's rules are unchanged.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, fields, replace
from decimal import Decimal, DecimalException
from functools import lru_cache
from hashlib import sha256
import json
from pathlib import Path
from typing import Mapping

from .continuation import BASELINE, ContinuationConfig
from .exit_config import ExitConfig
from .exits import ProfitProtectionPolicy
from .java_entry_contract import JavaEntryConfig
from .timeframes import TimeframeProfile

SCHEMA = 'htx-holding-policy-v1'
IMPLEMENTATION = 'htx-exit-20261102-v1'
_SOURCES = ('exits.py', 'continuation.py', 'java_entry_contract.py', 'indicators.py', 'models.py',
            'timeframes.py', 'position_manager.py', 'runtime.py', 'realtime/ownership.py',
            'realtime/management.py', 'realtime/indicator_union.py', 'realtime/regime.py')
# Audited deployment baseline 44d3150b (also the 8e0a0cc4 management implementations).
# All pure calculations must still match. These two legacy orchestration sources
# are adapted by the policy-aware manager/runtime and covered by equivalence tests.
LEGACY_MANAGEMENT = {
    'position_manager.py': '741d825efe6e6af786e8a8b72cd4e17d29b7f479ef697d69018d6acdcceb6d58',
    'runtime.py': 'd320f77c0a846abd697dc2d7116bc7e9fdc6866569beedbdb662181272ae8345',
}


def supports_observed_implementation(hashes: dict) -> bool:
    current = implementation_hashes()
    from .realtime.legacy_implementations import LEGACY_IMPLEMENTATIONS
    from .realtime.union_compatibility import UNION_PREDECESSOR
    from .realtime.regime_compatibility import REGIME_PREDECESSOR
    return hashes == current or hashes in LEGACY_IMPLEMENTATIONS or hashes in (UNION_PREDECESSOR, REGIME_PREDECESSOR)


class HoldingPolicyError(ValueError):
    pass


def canonical(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False,
                      allow_nan=False, default=str).encode()


def digest(value) -> str:
    return sha256(canonical(value)).hexdigest()


@lru_cache(maxsize=1)
def implementation_hashes() -> dict[str, str]:
    root = Path(__file__).parent
    return {name: sha256((root / name).read_bytes()).hexdigest() for name in _SOURCES}


def _decode_config(cls, value):
    if not isinstance(value, dict) or set(value) != {f.name for f in fields(cls)}:
        raise HoldingPolicyError(f'incomplete or unknown {cls.__name__} parameters')
    defaults = cls()
    decoded = {}
    for name, item in value.items():
        default = getattr(defaults, name)
        if name == 'policy':
            decoded[name] = _decode_config(ProfitProtectionPolicy, item)
        elif isinstance(default, Decimal):
            if isinstance(item, bool) or not isinstance(item, (str, int, Decimal)):
                raise HoldingPolicyError(f'invalid decimal parameter {name}')
            decimal = Decimal(str(item))
            if not decimal.is_finite():
                raise HoldingPolicyError(f'non-finite parameter {name}')
            decoded[name] = decimal
        elif isinstance(default, bool):
            if type(item) is not bool:
                raise HoldingPolicyError(f'invalid boolean parameter {name}')
            decoded[name] = item
        elif isinstance(default, int) or name in ('timeout_bars', 'min_hold_bars'):
            if item is None and name in ('timeout_bars', 'min_hold_bars'):
                decoded[name] = None
            elif type(item) is not int:
                raise HoldingPolicyError(f'invalid integer parameter {name}')
            else:
                decoded[name] = item
        else:
            raise HoldingPolicyError(f'unsupported parameter {name}')
    return cls(**decoded)


@dataclass(frozen=True)
class HoldingPolicy:
    """Keep canonical JSON internally so callers cannot mutate a frozen policy."""
    encoded: bytes

    @classmethod
    def create(cls, *, instance_key: str, account_id: int | str, strategy_family: str,
               strategy_name: str, mode: str, profile: TimeframeProfile,
               entry_config: JavaEntryConfig, exit_config: ExitConfig,
               continuation_version: str = BASELINE) -> 'HoldingPolicy':
        ContinuationConfig(continuation_version).require_15m(profile is TimeframeProfile.INTRADAY_15M,
                                                           live=mode == 'LIVE')
        if mode not in ('LIVE', 'PAPER') or not instance_key or not strategy_name or not strategy_family:
            raise HoldingPolicyError('holding requires explicit runtime ownership')
        if isinstance(account_id, bool) or not isinstance(account_id, (int, str)) or not str(account_id):
            raise HoldingPolicyError('holding requires explicit account')
        payload = dict(schema=SCHEMA, implementation=IMPLEMENTATION,
            implementation_hashes=implementation_hashes(),
            owner=dict(instance_key=instance_key, account_id=str(account_id), strategy_family=strategy_family,
                       strategy_name=strategy_name, execution_mode=mode, timeframe_profile=profile.value),
            period=profile.contract.entry_period, entry=asdict(entry_config), exit=asdict(exit_config),
            continuation_version=continuation_version)
        normalized = json.loads(canonical(payload))
        return cls.load({**normalized, 'hash': digest(normalized)})

    @classmethod
    def load(cls, value: Mapping) -> 'HoldingPolicy':
        try:
            return cls._load(value)
        except HoldingPolicyError:
            raise
        except (ValueError, TypeError, KeyError, DecimalException) as error:
            raise HoldingPolicyError('invalid holding policy encoding') from error

    @classmethod
    def _load(cls, value: Mapping) -> 'HoldingPolicy':
        if not isinstance(value, Mapping):
            raise HoldingPolicyError('holding policy must be an object')
        value = dict(value)
        if set(value) != {'schema', 'implementation', 'implementation_hashes', 'owner', 'period',
                          'entry', 'exit', 'continuation_version', 'hash'}:
            raise HoldingPolicyError('incomplete holding policy')
        claimed = value.pop('hash')
        if claimed != digest(value):
            raise HoldingPolicyError('holding policy hash mismatch')
        if value['schema'] != SCHEMA or value['implementation'] != IMPLEMENTATION:
            raise HoldingPolicyError('unsupported holding policy implementation')
        if value['implementation_hashes'] != implementation_hashes():
            from .realtime.legacy_implementations import LEGACY_IMPLEMENTATIONS
            from .realtime import VERSION
            from .realtime.union_compatibility import UNION_PREDECESSOR
            from .realtime.regime_compatibility import REGIME_PREDECESSOR
            union_compatible = value['implementation_hashes'] in (UNION_PREDECESSOR, REGIME_PREDECESSOR)
            if (not union_compatible and (value['implementation_hashes'] not in LEGACY_IMPLEMENTATIONS
                    or value.get('owner', {}).get('strategy_name') == VERSION)):
                raise HoldingPolicyError('holding policy implementation changed; compatible adapter required')
        owner = value['owner']
        names = {'instance_key','account_id','strategy_family','strategy_name','execution_mode','timeframe_profile'}
        if not isinstance(owner, dict) or set(owner) != names or any(not isinstance(v,str) or not v for v in owner.values()):
            raise HoldingPolicyError('incomplete holding owner')
        if owner['execution_mode'] not in ('LIVE','PAPER'):
            raise HoldingPolicyError('unsupported holding mode')
        profile = TimeframeProfile(owner['timeframe_profile'])
        if value['period'] != profile.contract.entry_period:
            raise HoldingPolicyError('holding period/profile mismatch')
        ContinuationConfig(value['continuation_version']).require_15m(profile is TimeframeProfile.INTRADAY_15M,
                                                                    live=owner['execution_mode']=='LIVE')
        _decode_config(JavaEntryConfig, value['entry'])
        exit_config = _decode_config(ExitConfig, value['exit'])
        if (owner['execution_mode'] == 'PAPER' and owner['strategy_name'] != 'HTX_REALTIME_STRUCTURE_15M_V1'
                and exit_config != paper_exit_config()):
            raise HoldingPolicyError('unsupported PAPER exit semantics; compatible adapter required')
        return cls(canonical({**value, 'hash': claimed}))

    def to_dict(self) -> dict:
        return json.loads(self.encoded)

    @property
    def owner(self) -> dict:
        return self.to_dict()['owner']

    @property
    def entry(self) -> JavaEntryConfig:
        return _decode_config(JavaEntryConfig, self.to_dict()['entry'])

    @property
    def exit(self) -> ExitConfig:
        return _decode_config(ExitConfig, self.to_dict()['exit'])

    @property
    def continuation_version(self) -> str:
        return self.to_dict()['continuation_version']

    def require_owner(self, *, instance_key, account_id, strategy_family, strategy_name, mode, profile):
        expected = dict(instance_key=instance_key, account_id=str(account_id), strategy_family=strategy_family,
                        strategy_name=strategy_name, execution_mode=mode, timeframe_profile=profile.value)
        if self.owner != expected:
            raise HoldingPolicyError('holding policy ownership mismatch')


def paper_exit_config() -> ExitConfig:
    """Freeze PAPER's existing V4/default ExitInput semantics, not LIVE env."""
    return replace(ExitConfig(), engine_enabled=True, engine_live=True, flat_budget=Decimal(0),
                   flat_budget_rate=Decimal(0), timeout_bars=None, min_hold_bars=None)


def resolve_holding_policy(state: dict, *, required: bool = False) -> HoldingPolicy | None:
    value = state.get('holding_policy')
    if value is None:
        if required:
            raise HoldingPolicyError('legacy holding has no policy; verified adoption required')
        return None
    policy = HoldingPolicy.load(value)
    if state.get('continuation_version', BASELINE) != policy.continuation_version:
        raise HoldingPolicyError('holding continuation ownership changed')
    return policy
