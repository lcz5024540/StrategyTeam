"""Versioned, side-effect-free contract for trend exit v5."""
from __future__ import annotations

from dataclasses import asdict, dataclass, is_dataclass
from decimal import Decimal
from enum import StrEnum
from hashlib import sha256
import json
from typing import Any

ZERO = Decimal("0")


class Side(StrEnum):
    LONG = "LONG"
    SHORT = "SHORT"


class EventKind(StrEnum):
    TICK = "TICK"
    CLOSED_BAR = "CLOSED_BAR"


class ModelState(StrEnum):
    VALID = "VALID"
    DETERIORATING = "DETERIORATING"
    INVALID = "INVALID"
    UNKNOWN = "UNKNOWN"


class ProfitPhase(StrEnum):
    BUILDING = "BUILDING"
    ESTABLISHED = "ESTABLISHED"
    HARVEST = "HARVEST"
    RUNNER = "RUNNER"
    CLOSED = "CLOSED"


class ExecutionState(StrEnum):
    IDLE = "IDLE"
    INTENT = "INTENT"
    SENT = "SENT"
    PARTIAL = "PARTIAL"
    CONFIRMED = "CONFIRMED"
    UNKNOWN = "UNKNOWN"


class ExitActionV5(StrEnum):
    HOLD = "HOLD"
    UPDATE_SOFT_BOUNDARY = "UPDATE_SOFT_BOUNDARY"
    PARTIAL_CLOSE = "PARTIAL_CLOSE"
    FULL_CLOSE = "FULL_CLOSE"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"


@dataclass(frozen=True, slots=True)
class EntryThesisV5:
    account_id: int
    strategy_instance: str
    position_epoch: int
    model_id: str
    structure_id: str
    side: Side
    timeframe_seconds: int
    first_fill_time_ms: int
    entry_price: Decimal
    filled_contracts: int
    contract_size: Decimal
    immutable_initial_stop: Decimal
    failure_zone: Decimal
    target_price: Decimal | None
    atr_at_entry: Decimal
    entry_fee: Decimal
    engine_version: str = "trend-exit-v5"
    contract_schema: str = "trend-entry-thesis-v5"
    model_adapter_version: str = "trend-model-holding-v5"
    policy_hash: str = ""

    def contract_hash(self) -> str:
        return canonical_hash(asdict(self))


@dataclass(frozen=True, slots=True)
class ExitPolicyV5:
    maturity_bars: int = 2
    structure_failure_confirm_bars: int = 2
    soft_breach_confirm_bars: int = 2
    arm_atr_multiplier: Decimal = Decimal("1.0")
    arm_cost_distance_multiple: Decimal = Decimal("2.0")
    arm_net_cost_multiple: Decimal = Decimal("2.0")
    noise_atr_multiplier: Decimal = Decimal("1.0")
    noise_min_atr: Decimal = Decimal("0.5")
    partial_fraction: Decimal = Decimal("0.333333333333333333")
    partial_net_cost_multiple: Decimal = Decimal("2.0")
    max_partial_stages: int = 1
    no_extension_horizon_bars: int = 16
    close_grace_ms: int = 0


@dataclass(frozen=True, slots=True)
class ExitStateV5:
    revision: int = 0
    phase: ProfitPhase = ProfitPhase.BUILDING
    execution_state: ExecutionState = ExecutionState.IDLE
    last_bar_end_ms: int | None = None
    eligible_bars: int = 0
    structure_failure_bars: int = 0
    best_close: Decimal | None = None
    active_soft_boundary: Decimal | None = None
    active_boundary_id: str | None = None
    pending_soft_boundary: Decimal | None = None
    pending_boundary_id: str | None = None
    soft_breach_bars: int = 0
    armed_bar_end_ms: int | None = None
    partial_stage: int = 0
    extension_achieved: bool = False


@dataclass(frozen=True, slots=True)
class ExitEventV5:
    event_key: str
    kind: EventKind
    event_time_ms: int
    reference_price: Decimal
    confirmed_remaining_contracts: int
    data_quality: str = "COMPLETE"
    ownership_confirmed: bool = True
    bar_open_ms: int | None = None
    bar_end_ms: int | None = None
    close_price: Decimal | None = None
    atr_at_close: Decimal | None = None
    current_net_pnl: Decimal = ZERO
    round_trip_cost: Decimal = ZERO
    model_state: ModelState = ModelState.UNKNOWN
    evidence_groups: tuple[str, ...] = ()
    target_reached: bool = False
    partial_net_pnl: Decimal | None = None


@dataclass(frozen=True, slots=True)
class ExitDecisionV5:
    action: ExitActionV5
    phase: ProfitPhase
    target_remaining_contracts: int
    reason_codes: tuple[str, ...]
    evidence_groups: tuple[str, ...]
    input_hash: str
    active_soft_boundary: Decimal | None
    pending_soft_boundary: Decimal | None
    eligible_bars: int
    soft_breach_bars: int


@dataclass(frozen=True, slots=True)
class ExitTransitionV5:
    decision: ExitDecisionV5
    state: ExitStateV5


def canonical_json(value: Any) -> str:
    def normalize(item: Any) -> Any:
        if is_dataclass(item):
            return normalize(asdict(item))
        if isinstance(item, Decimal):
            return format(item, "f")
        if isinstance(item, StrEnum):
            return item.value
        if isinstance(item, dict):
            return {str(key): normalize(item[key]) for key in sorted(item)}
        if isinstance(item, (tuple, list)):
            return [normalize(child) for child in item]
        return item
    return json.dumps(normalize(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def canonical_hash(value: Any) -> str:
    return sha256(canonical_json(value).encode("utf-8")).hexdigest()
