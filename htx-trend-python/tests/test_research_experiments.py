from dataclasses import replace

import pytest

from htx_trend.research.baseline import REGISTRY, baseline_strategy
from htx_trend.research.experiments import (ExperimentPlan, ExperimentRunner, ExperimentVariant,
                                            chronological_split)
from htx_trend.research.store import FileEvidenceStore


def plan(*variants):
    strategy = baseline_strategy()
    return ExperimentPlan("EXP-20261011-001", "a" * 64, strategy.spec_hash, tuple(variants))


def test_experiment_plan_enforces_split_costs_and_safety_boundary():
    strategy = baseline_strategy()
    assert chronological_split(100) == {"TRAIN": (0, 60), "VALIDATION": (60, 80), "TEST": (80, 100)}
    unsafe = plan(ExperimentVariant("no-lock", remove_conditions=("runtime.writer_guard",)))
    with pytest.raises(ValueError, match="safety condition"):
        unsafe.validate(REGISTRY, strategy)


def test_runner_records_failures_and_test_set_consumption(tmp_path):
    strategy = baseline_strategy()
    frozen = plan(ExperimentVariant("baseline"),
                  ExperimentVariant("no-volume", remove_conditions=("signal.volume_ratio",)))
    with FileEvidenceStore(tmp_path) as store:
        runner = ExperimentRunner(store, REGISTRY, strategy)

        def evaluate(variant, cost, split_name, bounds):
            if variant.variant_id == "no-volume" and cost.name == "CONSERVATIVE":
                raise ArithmeticError("bad run")
            return {"trades": bounds[1] - bounds[0]}

        result_ref = runner.run(frozen, observations=100, evaluator=evaluate, include_test=True)
        result = store.get(result_ref)["payload"]
        assert len(result["results"]) == 24
        assert any(item["status"] == "FAILED" for item in result["results"])
        other = replace(frozen, experiment_id="EXP-20261011-002")
        with pytest.raises(ValueError, match="already consumed"):
            runner.run(other, observations=100, evaluator=evaluate, include_test=True)
