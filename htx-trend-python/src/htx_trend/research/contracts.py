"""Versioned features, conditions, strategies and complete decision traces."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Iterable

from .store import digest, primitive


class ConditionStage(StrEnum):
    DATA = "DATA"
    MARKET = "MARKET"
    CANDIDATE = "CANDIDATE"
    CONFIRMATION = "CONFIRMATION"
    ENTRY_GATE = "ENTRY_GATE"
    SIZING = "SIZING"
    HOLDING = "HOLDING"
    EXIT = "EXIT"
    EXECUTION_SAFETY = "EXECUTION_SAFETY"


class ConditionUsage(StrEnum):
    RECORD = "RECORD"
    SCORE = "SCORE"
    STRATEGY_GATE = "STRATEGY_GATE"
    RISK_CONSTRAINT = "RISK_CONSTRAINT"
    ACTION_TRIGGER = "ACTION_TRIGGER"


class ConditionStatus(StrEnum):
    PASS = "PASS"
    FAIL = "FAIL"
    UNKNOWN = "UNKNOWN"
    SKIPPED = "SKIPPED"


@dataclass(frozen=True, slots=True)
class FeatureSpec:
    feature_id: str
    version: str
    formula: str
    inputs: tuple[str, ...]
    parameters: dict[str, Any]
    warmup_bars: int
    lag_bars: int
    unit: str
    missing_policy: str
    purpose: str

    @property
    def spec_hash(self) -> str:
        return digest(self)


@dataclass(frozen=True, slots=True)
class ConditionSpec:
    condition_id: str
    version: str
    stage: ConditionStage
    usage: ConditionUsage
    feature_ids: tuple[str, ...]
    parameters: dict[str, Any]
    description: str
    safety_invariant: bool = False

    @property
    def spec_hash(self) -> str:
        return digest(self)


@dataclass(frozen=True, slots=True)
class ConditionResult:
    condition_id: str
    condition_version: str
    stage: ConditionStage
    usage: ConditionUsage
    status: ConditionStatus
    actual: Any = None
    threshold: Any = None
    reason: str = ""
    evidence_refs: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class StrategySpec:
    strategy_id: str
    version: str
    feature_versions: dict[str, str]
    condition_versions: dict[str, str]
    parameters: dict[str, Any]
    transitions: tuple[dict[str, Any], ...]
    failure_conditions: tuple[str, ...]
    pause_conditions: tuple[str, ...]

    @property
    def spec_hash(self) -> str:
        return digest(self)


@dataclass(frozen=True, slots=True)
class DecisionContext:
    decision_id: str
    strategy_version: str
    surface: str
    evaluated_at: str
    market: dict[str, Any]
    account: dict[str, Any]
    position: dict[str, Any]
    evidence_refs: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class DecisionTrace:
    context: DecisionContext
    strategy_spec_hash: str
    condition_results: tuple[ConditionResult, ...]
    state_before: dict[str, Any]
    state_after: dict[str, Any]
    action: str
    reason: str
    event_refs: tuple[str, ...] = ()

    @property
    def trace_hash(self) -> str:
        return digest(self)


class Registry:
    def __init__(self, features: Iterable[FeatureSpec] = (), conditions: Iterable[ConditionSpec] = ()):
        self.features: dict[tuple[str, str], FeatureSpec] = {}
        self.conditions: dict[tuple[str, str], ConditionSpec] = {}
        for value in features:
            self.add_feature(value)
        for value in conditions:
            self.add_condition(value)

    def add_feature(self, spec: FeatureSpec):
        key = (spec.feature_id, spec.version)
        if key in self.features and self.features[key] != spec:
            raise ValueError(f"feature version collision: {key}")
        if spec.warmup_bars < 0 or spec.lag_bars < 0 or not spec.formula:
            raise ValueError(f"invalid feature spec: {key}")
        self.features[key] = spec

    def add_condition(self, spec: ConditionSpec):
        key = (spec.condition_id, spec.version)
        if key in self.conditions and self.conditions[key] != spec:
            raise ValueError(f"condition version collision: {key}")
        missing = [value for value in spec.feature_ids if not any(item[0] == value for item in self.features)]
        if missing:
            raise ValueError(f"condition {key} references unknown features: {missing}")
        self.conditions[key] = spec

    def validate_strategy(self, spec: StrategySpec):
        for key, version in spec.feature_versions.items():
            if (key, version) not in self.features:
                raise ValueError(f"unknown strategy feature {key}:{version}")
        for key, version in spec.condition_versions.items():
            if (key, version) not in self.conditions:
                raise ValueError(f"unknown strategy condition {key}:{version}")
        return spec.spec_hash

    def apply_experiment(self, base: StrategySpec, *, experiment_id: str,
                         condition_versions: dict[str, str] | None = None,
                         parameters: dict[str, Any] | None = None) -> StrategySpec:
        if not experiment_id.startswith("EXP-"):
            raise ValueError("versioned experiment id required")
        changed = dict(base.condition_versions)
        for condition_id, version in (condition_versions or {}).items():
            current = self.conditions.get((condition_id, changed.get(condition_id, version)))
            replacement = self.conditions.get((condition_id, version))
            if replacement is None:
                raise ValueError(f"unknown experiment condition {condition_id}:{version}")
            if (current and current.safety_invariant) or replacement.safety_invariant:
                raise ValueError(f"safety condition is not experiment-configurable: {condition_id}")
            changed[condition_id] = version
        result = StrategySpec(base.strategy_id, f"{base.version}+{experiment_id}", dict(base.feature_versions),
                              changed, {**base.parameters, **(parameters or {})}, base.transitions,
                              base.failure_conditions, base.pause_conditions)
        self.validate_strategy(result)
        return result


class TraceBuilder:
    def __init__(self, registry: Registry, strategy: StrategySpec, context: DecisionContext,
                 *, state_before: dict[str, Any] | None = None):
        registry.validate_strategy(strategy)
        self.registry, self.strategy, self.context = registry, strategy, context
        self.state_before = state_before or {}
        self.results: dict[str, ConditionResult] = {}

    def record(self, condition_id: str, status: ConditionStatus, *, actual: Any = None,
               threshold: Any = None, reason: str = "", evidence_refs: tuple[str, ...] = ()):
        if condition_id in self.results:
            raise ValueError(f"condition already recorded: {condition_id}")
        version = self.strategy.condition_versions[condition_id]
        spec = self.registry.conditions[(condition_id, version)]
        self.results[condition_id] = ConditionResult(condition_id, version, spec.stage, spec.usage,
            status, primitive(actual), primitive(threshold), reason, evidence_refs)

    def finish(self, *, action: str, reason: str, state_after: dict[str, Any] | None = None,
               event_refs: tuple[str, ...] = ()) -> DecisionTrace:
        for condition_id, version in self.strategy.condition_versions.items():
            if condition_id not in self.results:
                spec = self.registry.conditions[(condition_id, version)]
                self.results[condition_id] = ConditionResult(condition_id, version, spec.stage, spec.usage,
                    ConditionStatus.SKIPPED, reason="not evaluated on selected state-machine path")
        ordered = tuple(self.results[key] for key in sorted(self.results))
        return DecisionTrace(self.context, self.strategy.spec_hash, ordered, primitive(self.state_before),
                             primitive(state_after or {}), action, reason, event_refs)
