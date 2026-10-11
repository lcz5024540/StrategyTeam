"""Frozen ablation/sensitivity plans and append-only experiment results."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
import re
from typing import Any, Callable, Sequence

from .contracts import Registry, StrategySpec
from .store import FileEvidenceStore, digest


@dataclass(frozen=True, slots=True)
class CostScenario:
    name: str
    fee_rate: Decimal
    slippage_rate: Decimal
    funding_rate: Decimal


DEFAULT_COST_SCENARIOS = (
    CostScenario("ZERO", Decimal("0"), Decimal("0"), Decimal("0")),
    CostScenario("OPTIMISTIC", Decimal("0.0003"), Decimal("0.0001"), Decimal("0")),
    CostScenario("BASE", Decimal("0.0004"), Decimal("0.0003"), Decimal("0.0001")),
    CostScenario("CONSERVATIVE", Decimal("0.0006"), Decimal("0.0010"), Decimal("0.0003")),
)


@dataclass(frozen=True, slots=True)
class ExperimentVariant:
    variant_id: str
    remove_conditions: tuple[str, ...] = ()
    add_condition_versions: tuple[tuple[str, str], ...] = ()
    parameters: tuple[tuple[str, Any], ...] = ()


@dataclass(frozen=True, slots=True)
class ExperimentPlan:
    experiment_id: str
    dataset_manifest_hash: str
    base_strategy_hash: str
    variants: tuple[ExperimentVariant, ...]
    costs: tuple[CostScenario, ...] = DEFAULT_COST_SCENARIOS
    split: tuple[int, int, int] = (60, 20, 20)
    execution_assumption: str = "signal at close; fill at next tradable event"

    @property
    def plan_hash(self) -> str:
        return digest(self)

    def validate(self, registry: Registry, strategy: StrategySpec) -> None:
        if not re.fullmatch(r"EXP-\d{8}-\d{3}", self.experiment_id):
            raise ValueError("experiment id must match EXP-YYYYMMDD-NNN")
        if self.base_strategy_hash != strategy.spec_hash or len(self.dataset_manifest_hash) != 64:
            raise ValueError("experiment inputs do not match frozen strategy/data")
        if self.split != (60, 20, 20) or len({item.variant_id for item in self.variants}) != len(self.variants):
            raise ValueError("experiment requires unique variants and frozen 60/20/20 split")
        if {item.name for item in self.costs} != {"ZERO", "OPTIMISTIC", "BASE", "CONSERVATIVE"}:
            raise ValueError("all four cost scenarios are required")
        for variant in self.variants:
            for condition_id in variant.remove_conditions:
                version = strategy.condition_versions.get(condition_id)
                condition = registry.conditions.get((condition_id, version))
                if condition is None:
                    raise ValueError(f"unknown removable condition: {condition_id}")
                if condition.safety_invariant:
                    raise ValueError(f"safety condition is not experiment-configurable: {condition_id}")
            registry.apply_experiment(strategy, experiment_id=self.experiment_id,
                                      condition_versions=dict(variant.add_condition_versions),
                                      parameters=dict(variant.parameters))


def chronological_split(size: int) -> dict[str, tuple[int, int]]:
    if size < 5:
        raise ValueError("at least five observations required")
    train = size * 60 // 100
    validation = size * 80 // 100
    return {"TRAIN": (0, train), "VALIDATION": (train, validation), "TEST": (validation, size)}


class ExperimentRunner:
    """Evaluator receives (variant, cost, split_name, bounds) and returns metrics."""
    def __init__(self, store: FileEvidenceStore, registry: Registry, strategy: StrategySpec):
        self.store, self.registry, self.strategy = store, registry, strategy

    def run(self, plan: ExperimentPlan, *, observations: int,
            evaluator: Callable[[ExperimentVariant, CostScenario, str, tuple[int, int]], dict],
            include_test: bool = False) -> str:
        plan.validate(self.registry, self.strategy)
        bounds = chronological_split(observations)
        plan_ref = self.store.put("experiment_plan", plan)
        if include_test:
            checkpoint = f"test-consumed:{plan.dataset_manifest_hash}"
            if self.store.checkpoint(checkpoint) not in (None, {"plan_hash": plan.plan_hash}):
                raise ValueError("test dataset was already consumed by another plan")
            self.store.checkpoint(checkpoint, {"plan_hash": plan.plan_hash})
        splits = ("TRAIN", "VALIDATION", "TEST") if include_test else ("TRAIN", "VALIDATION")
        results = []
        for variant in plan.variants:
            for cost in plan.costs:
                for split_name in splits:
                    try:
                        metrics = evaluator(variant, cost, split_name, bounds[split_name])
                        results.append({"variant_id": variant.variant_id, "cost": cost.name,
                                        "split": split_name, "status": "COMPLETE", "metrics": metrics})
                    except Exception as error:
                        results.append({"variant_id": variant.variant_id, "cost": cost.name,
                                        "split": split_name, "status": "FAILED",
                                        "error_type": type(error).__name__})
        return self.store.put("experiment_result", {"plan_hash": plan.plan_hash,
            "observations": observations, "split_bounds": bounds, "results": results}, refs=(plan_ref,))
