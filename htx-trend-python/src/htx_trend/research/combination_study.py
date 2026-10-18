"""R2 P7 eligibility gate for condition combinations and nested walk-forward."""
from __future__ import annotations

from pathlib import Path
from typing import Sequence

from ..timeframes import TimeframeProfile
from .store import FileEvidenceStore, digest

ELIGIBLE = "ELIGIBLE_FOR_P7"
ALLOWED_KINDS = {
    "intraday_model_optimization_result",
    "swing_model_optimization_result",
    "exit_optimization_result",
}


class CombinationStudy:
    """Freeze P7 inputs and fail closed before any combination search."""

    def __init__(self, store: FileEvidenceStore, profile: TimeframeProfile,
                 upstream_result_hashes: Sequence[str]) -> None:
        if len(upstream_result_hashes) < 2:
            raise ValueError("P7 requires entry and exit upstream results")
        self.store, self.profile = store, profile
        self.upstream_result_hashes = tuple(upstream_result_hashes)

    def run(self) -> str:
        sources = []
        matrix = []
        eligible = []
        for ref in self.upstream_result_hashes:
            envelope = self.store.get(ref)
            if envelope["kind"] not in ALLOWED_KINDS:
                raise ValueError(f"unsupported P7 upstream artifact: {envelope['kind']}")
            payload = envelope["payload"]
            if payload.get("profile") != self.profile.value:
                raise ValueError("P7 upstream profile mismatch")
            if payload.get("test_segment") != "NOT_READ":
                raise ValueError("P7 cannot consume an upstream result that read TEST")
            stage = "EXIT" if envelope["kind"] == "exit_optimization_result" else "ENTRY"
            sources.append({"ref": ref, "kind": envelope["kind"], "stage": stage,
                            "conclusion": payload.get("conclusion"),
                            "test_segment": payload.get("test_segment")})
            for item in payload.get("model_results", ()):
                nominee = item.get("selected_by_train")
                row = {"source_ref": ref, "stage": stage, "model": item.get("model"),
                       "conclusion": item.get("conclusion"), "nominee": nominee,
                       "eligible": item.get("conclusion") == ELIGIBLE and nominee is not None}
                matrix.append(row)
                if row["eligible"]:
                    eligible.append(row)
        plan = {
            "schema": "htx-r2-p7-combination-plan-v1",
            "experiment_id": f"EXP-20261018-007-{self.profile.value}",
            "profile": self.profile.value,
            "upstream_results": sources,
            "input_rule": "only model results concluded ELIGIBLE_FOR_P7 with a frozen nominee",
            "combination_rule": "combine eligible entry and exit nominees only within the same profile and model",
            "selection": "nested walk-forward inner windows only",
            "evaluation": "outer windows only; four costs, paired ablation, permutation and block bootstrap",
            "test_segment": "NOT_READ",
            "search_budget": {"eligible_nominees": len(eligible), "combined_variants": 0},
            "scope": "research only; no TEST, PAPER, LIVE, admission or runtime mutation",
        }
        plan_ref = self.store.put("combination_study_plan", plan,
                                  refs=self.upstream_result_hashes)
        if eligible:
            # The current frozen P4-P6 chain has no eligible nominee.  Keeping
            # this guard explicit prevents a future caller from silently using
            # a terminal gate artifact as a real nested walk-forward result.
            raise ValueError("eligible P7 inputs require a separately frozen combination grid")
        result = {
            "schema": "htx-r2-p7-combination-result-v1",
            "profile": self.profile.value,
            "plan_ref": plan_ref,
            "plan_hash": digest(plan),
            "upstream_results": sources,
            "eligibility_matrix": matrix,
            "eligible_nominees": [],
            "combination_variants": [],
            "nested_walk_forward": {
                "status": "NOT_RUN_GATE_FAILED",
                "inner_windows": 0,
                "outer_windows": 0,
                "reason": "no P4/P5/P6 model result concluded ELIGIBLE_FOR_P7",
            },
            "ablation": {"status": "NOT_RUN_GATE_FAILED", "variants": 0},
            "cost_scenarios": {"status": "NOT_RUN_GATE_FAILED", "count": 0},
            "test_segment": "NOT_READ",
            "status": "NO_ELIGIBLE_INPUTS",
            "conclusion": "REJECT",
            "decision": "do not enter P8; preserve TEST and wait for new independent data or preregistered hypotheses",
        }
        return self.store.put("combination_study_result", result,
                              refs=(plan_ref, *self.upstream_result_hashes))


def run_combination_study(evidence: Path, profile: TimeframeProfile,
                          upstream_result_hashes: Sequence[str]) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        result = CombinationStudy(store, profile, upstream_result_hashes).run()
        return {"result_hash": result}
