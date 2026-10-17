"""Frozen ablation/sensitivity plans and append-only experiment results."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
import re
import random
from enum import StrEnum
from typing import Any, Callable, Sequence

from .contracts import Registry, StrategySpec
from .features import Performance
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


class ResearchConclusion(StrEnum):
    PASS = "PASS"
    PASS_PROFITABILITY_FREQUENCY_GAP = "PASS_PROFITABILITY_FREQUENCY_GAP"
    REJECT = "REJECT"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"


@dataclass(frozen=True, slots=True)
class EconomicGateResult:
    conclusion: ResearchConclusion
    reasons: tuple[str, ...]
    combined_trades_per_day: Decimal


def permutation_p_value(values: Sequence[Decimal], *, iterations: int = 2_000,
                        seed: int = 1) -> Decimal:
    """Two-sided deterministic sign permutation against a zero-expectancy null."""
    if not values or iterations < 100:
        raise ValueError("permutation requires observations and at least 100 iterations")
    observed = abs(sum(values, Decimal("0")) / Decimal(len(values)))
    rng, extreme = random.Random(seed), 0
    for _ in range(iterations):
        trial = sum((value if rng.getrandbits(1) else -value for value in values), Decimal("0"))
        if abs(trial / Decimal(len(values))) >= observed:
            extreme += 1
    return Decimal(extreme + 1) / Decimal(iterations + 1)


def block_bootstrap_expectancy_lower(
    values: Sequence[Decimal], *, block_size: int = 5, iterations: int = 2_000,
    seed: int = 1, quantile: Decimal = Decimal("0.05"),
) -> Decimal:
    """Deterministic moving-block bootstrap lower bound preserving local dependence."""
    if len(values) < block_size or block_size < 1 or iterations < 100:
        raise ValueError("bootstrap requires enough observations, a valid block and 100 iterations")
    if not Decimal("0") < quantile < Decimal("1"):
        raise ValueError("bootstrap quantile must be in (0,1)")
    rng = random.Random(seed)
    starts = range(0, len(values) - block_size + 1)
    means: list[Decimal] = []
    for _ in range(iterations):
        sample: list[Decimal] = []
        while len(sample) < len(values):
            start = rng.choice(starts)
            sample.extend(values[start:start + block_size])
        sample = sample[:len(values)]
        means.append(sum(sample, Decimal("0")) / Decimal(len(sample)))
    means.sort()
    index = min(len(means) - 1, int((Decimal(len(means)) * quantile).to_integral_value()))
    return means[index]


def walk_forward_bounds(size: int, *, train: int, validate: int, step: int) -> tuple[dict[str, tuple[int, int]], ...]:
    if min(size, train, validate, step) < 1 or train + validate > size:
        raise ValueError("invalid walk-forward window")
    windows = []
    start = 0
    while start + train + validate <= size:
        windows.append({"TRAIN": (start, start + train),
                        "VALIDATION": (start + train, start + train + validate)})
        start += step
    return tuple(windows)


def economic_gate(
    base: Performance,
    conservative: Performance,
    *,
    account_equity: Decimal,
    permutation_p: Decimal,
    bootstrap_expectancy_lower: Decimal,
    combined_trades_per_day: Decimal,
) -> EconomicGateResult:
    if base.trades < 30:
        return EconomicGateResult(ResearchConclusion.INSUFFICIENT_EVIDENCE,
                                  ("OOS completed trades below 30",), combined_trades_per_day)
    reasons: list[str] = []
    expectancy = base.net_profit / Decimal(base.trades)
    if base.net_profit <= 0:
        reasons.append("BASE net profit is not positive")
    if expectancy <= 0:
        reasons.append("BASE expectancy is not positive")
    if base.profit_factor is not None and base.profit_factor < Decimal("1.30"):
        reasons.append("BASE profit factor is below 1.30")
    if conservative.net_profit < 0:
        reasons.append("CONSERVATIVE net profit is negative")
    if account_equity <= 0 or base.max_drawdown > account_equity * Decimal("0.10"):
        reasons.append("OOS max drawdown exceeds 10% of account equity")
    if permutation_p >= Decimal("0.05"):
        reasons.append("permutation p-value is not below 0.05")
    if bootstrap_expectancy_lower <= 0:
        reasons.append("bootstrap 95% expectancy lower bound is not positive")
    if reasons:
        return EconomicGateResult(ResearchConclusion.REJECT, tuple(reasons), combined_trades_per_day)
    conclusion = (ResearchConclusion.PASS if combined_trades_per_day >= Decimal("5")
                  else ResearchConclusion.PASS_PROFITABILITY_FREQUENCY_GAP)
    return EconomicGateResult(conclusion, (), combined_trades_per_day)
