"""R3 R4/R5 profile studies guarded by the independent-data readiness artifact."""
from __future__ import annotations

from dataclasses import asdict
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable, Sequence

from ..models import ModelCode
from ..timeframes import TimeframeProfile
from .experiments import block_bootstrap_expectancy_lower, permutation_p_value
from .model_optimization import _temporal_windows
from .profile_backtest import ProfileLifecycleResearch, ResearchVariant
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

ZERO = Decimal("0")
ONE = Decimal("1")
R3_SEED = 83

PROFILE_STUDIES = {
    TimeframeProfile.INTRADAY_15M: {
        "stage": "R4",
        "baseline": ResearchVariant("R3_15M_DONCHIAN_BASE", (ModelCode.DONCHIAN_BREAKOUT,)),
        "condition": ResearchVariant(
            "R3_15M_DONCHIAN_RETEST_RECLAIM",
            (ModelCode.DONCHIAN_BREAKOUT,),
            structural_condition="R3_15M_DONCHIAN_RETEST_RECLAIM"),
    },
    TimeframeProfile.SWING_4H: {
        "stage": "R5",
        "baseline": ResearchVariant(
            "R3_4H_EMA_BASE", (ModelCode.EMA_MOMENTUM_CONTINUATION,)),
        "condition": ResearchVariant(
            "R3_4H_1D_TREND_PERSISTENCE_3",
            (ModelCode.EMA_MOMENTUM_CONTINUATION,),
            structural_condition="R3_4H_1D_TREND_PERSISTENCE_3"),
    },
}


class IndependentProfileStudy:
    """Execute one frozen R3 hypothesis without ever reading the TEST segment."""

    def __init__(self, store: FileEvidenceStore, readiness_ref: str,
                 profile: TimeframeProfile, *,
                 runner_factory: Callable[..., ProfileLifecycleResearch] = ProfileLifecycleResearch) -> None:
        self.store, self.readiness_ref, self.profile = store, readiness_ref, profile
        self.study = PROFILE_STUDIES[profile]
        self.runner_factory = runner_factory

    def run(self, symbols: Sequence[str] = ("BTC-USDT", "ETH-USDT")) -> str:
        readiness = self._readiness()
        manifest_ref = readiness["dataset_manifest_ref"]
        market_ref = readiness["market_facts_manifest_ref"]
        plan = {
            "schema": "htx-r3-independent-profile-study-plan-v1",
            "experiment_id": f"EXP-20261018-R3-{self.study['stage']}",
            "stage": self.study["stage"],
            "profile": self.profile.value,
            "readiness_ref": self.readiness_ref,
            "manifest_ref": manifest_ref,
            "market_facts_manifest_ref": market_ref,
            "symbols": tuple(symbols),
            "baseline": asdict(self.study["baseline"]),
            "condition": asdict(self.study["condition"]),
            "split": {"TRAIN": (0, 60), "VALIDATION": (60, 80), "TEST": "NOT_READ"},
            "costs": ("ZERO", "OPTIMISTIC", "BASE", "CONSERVATIVE"),
            "seed": R3_SEED,
            "minimum_baseline_development_trades": 30,
            "selection": "one frozen condition paired against one model baseline; no parameter search",
            "promotion": "validation trades>=30, BASE net/expectancy>0, PF>=1.30, "
                         "CONSERVATIVE net>=0, permutation p<0.05, bootstrap lower>0, "
                         "and >=4/5 positive chronological windows",
            "scope": "research only; TEST, PAPER, LIVE and admission forbidden",
        }
        plan_ref = self.store.put("independent_profile_study_plan", plan,
                                  refs=(self.readiness_ref, manifest_ref, market_ref))
        profile_gate = readiness["profiles"][self.profile.value]
        if not profile_gate["coverage_ready"]:
            return self._blocked(plan_ref, readiness, "DATA_TIME_GATE_NOT_READY")

        runner = self.runner_factory(
            self.store, manifest_ref, market_ref, self.profile,
            window=200 if self.profile is TimeframeProfile.SWING_4H else 160)
        baseline_ref = runner.run(symbols=symbols, variants=(self.study["baseline"],),
                                  include_test=False, seed=R3_SEED)
        baseline_payload = self._lifecycle_payload(
            baseline_ref, manifest_ref, market_ref, (self.study["baseline"],))
        development_trades = sum(
            int(row["metrics"]["trades"])
            for row in baseline_payload["results"]
            if row["variant_id"] == self.study["baseline"].variant_id
            and row["cost"] == "BASE" and row["split"] in {"TRAIN", "VALIDATION"})
        if development_trades < 30:
            return self._blocked(
                plan_ref, readiness, "BASELINE_COMPLETE_TRADES_BELOW_30",
                baseline_ref=baseline_ref, development_trades=development_trades,
                strategy_outcomes_read=True)

        lifecycle_ref = runner.run(
            symbols=symbols,
            variants=(self.study["baseline"], self.study["condition"]),
            include_test=False,
            seed=R3_SEED)
        lifecycle = self._lifecycle_payload(
            lifecycle_ref, manifest_ref, market_ref,
            (self.study["baseline"], self.study["condition"]))
        analysis = self._analyze(lifecycle, symbols)
        eligible = analysis["conclusion"] == "ELIGIBLE_FOR_P7"
        return self.store.put("independent_profile_study_result", {
            "schema": "htx-r3-independent-profile-study-result-v1",
            "stage": self.study["stage"],
            "profile": self.profile.value,
            "plan_ref": plan_ref,
            "readiness_ref": self.readiness_ref,
            "baseline_sample_result_ref": baseline_ref,
            "lifecycle_result_ref": lifecycle_ref,
            "baseline_development_trades": development_trades,
            "strategy_outcomes_read": True,
            "test_segment": "NOT_READ",
            "analysis": analysis,
            "conclusion": analysis["conclusion"],
            "p7_nominee": self.study["condition"].variant_id if eligible else None,
            "decision": ("condition may be presented to P7 eligibility gate"
                         if eligible else "freeze rejection; do not widen the search"),
        }, refs=(plan_ref, self.readiness_ref, baseline_ref, lifecycle_ref))

    def _readiness(self) -> dict[str, Any]:
        self.store.verify(self.readiness_ref)
        envelope = self.store.get(self.readiness_ref)
        if envelope["kind"] != "independent_data_readiness":
            raise ValueError("R4/R5 requires an independent_data_readiness artifact")
        payload = envelope["payload"]
        if payload.get("test_segment") != "NOT_READ" or payload.get("strategy_outcomes_read") is not False:
            raise ValueError("R4/R5 readiness must prove TEST and strategy outcomes were not read")
        if self.profile.value not in payload.get("profiles", {}):
            raise ValueError("readiness artifact does not contain the requested profile")
        plan_envelope = self.store.get(payload["plan_ref"])
        if plan_envelope["kind"] != "independent_research_plan":
            raise ValueError("readiness does not reference the frozen R3 plan")
        frozen_plan = plan_envelope["payload"]
        if digest(frozen_plan) != payload["plan_hash"]:
            raise ValueError("readiness plan hash differs from the frozen R3 plan")
        condition_id = self.study["condition"].structural_condition
        hypotheses = {item["hypothesis_id"]: item for item in frozen_plan["hypotheses"]}
        hypothesis = hypotheses.get(condition_id)
        if hypothesis is None or hypothesis["profile"] != self.profile.value:
            raise ValueError("requested R4/R5 condition is absent from the frozen R3 plan")
        if hypothesis.get("mutable_parameters"):
            raise ValueError("R3 profile study cannot execute a parameter search grid")
        return payload

    def _lifecycle_payload(self, ref: str, manifest_ref: str, market_ref: str,
                           variants: Sequence[ResearchVariant]) -> dict[str, Any]:
        self.store.verify(ref)
        envelope = self.store.get(ref)
        if envelope["kind"] != "profile_lifecycle_research":
            raise ValueError("R4/R5 runner returned the wrong lifecycle artifact kind")
        payload = envelope["payload"]
        if payload.get("result_kind") != "STRATEGY_SIMULATION":
            raise ValueError("R4/R5 requires a strategy simulation result")
        if payload.get("profile") != self.profile.value:
            raise ValueError("R4/R5 lifecycle result profile differs from its plan")
        if payload.get("manifest_hash") != manifest_ref or payload.get("market_facts_manifest_hash") != market_ref:
            raise ValueError("R4/R5 lifecycle result evidence differs from its plan")
        if payload.get("test_segment") != "NOT_READ":
            raise ValueError("R4/R5 lifecycle result must keep TEST unread")
        if digest(payload.get("variants")) != digest([asdict(item) for item in variants]):
            raise ValueError("R4/R5 lifecycle result variants differ from the frozen plan")
        expected = {(variant.variant_id, cost, split)
                    for variant in variants
                    for cost in ("ZERO", "OPTIMISTIC", "BASE", "CONSERVATIVE")
                    for split in ("TRAIN", "VALIDATION")}
        actual = {(row.get("variant_id"), row.get("cost"), row.get("split"))
                  for row in payload.get("results", ())}
        if actual != expected or len(payload.get("results", ())) != len(expected):
            raise ValueError("R4/R5 lifecycle result does not contain the frozen four-cost development matrix")
        for row in payload["results"]:
            metrics = row.get("metrics", {})
            ledger = row.get("trade_ledger", ())
            if int(metrics.get("trades", -1)) != len(ledger):
                raise ValueError("R4/R5 lifecycle trade ledger differs from its metrics")
            diagnostics = row.get("candidate_diagnostics", {})
            available = int(diagnostics.get("available", -1))
            passed = int(diagnostics.get("passed", -1))
            rejected = int(diagnostics.get("rejected", -1))
            unknown = int(diagnostics.get("unknown", -1))
            failed = int(diagnostics.get("failed", -1))
            if available < 0 or available != passed+rejected or rejected != unknown+failed:
                raise ValueError("R4/R5 candidate diagnostics are internally inconsistent")
        structural = tuple(item for item in variants if item.structural_condition)
        if structural:
            traces = payload.get("structural_condition_traces", {})
            for variant in structural:
                if not any(key.endswith(f":{variant.variant_id}") for key in traces):
                    raise ValueError("R4/R5 lifecycle result lacks structural condition traces")
            allowed = {"PASS", "FAIL", "UNKNOWN", "WAITING", "INVALIDATED", "EXPIRED"}
            if any(str(row.get("status")) not in allowed
                   for rows in traces.values() for row in rows):
                raise ValueError("R4/R5 lifecycle result contains an invalid condition status")
        return payload

    def _blocked(self, plan_ref: str, readiness: dict[str, Any], reason: str, *,
                 baseline_ref: str | None = None, development_trades: int | None = None,
                 strategy_outcomes_read: bool = False) -> str:
        refs = [plan_ref, self.readiness_ref]
        if baseline_ref:
            refs.append(baseline_ref)
        return self.store.put("independent_profile_study_result", {
            "schema": "htx-r3-independent-profile-study-result-v1",
            "stage": self.study["stage"],
            "profile": self.profile.value,
            "plan_ref": plan_ref,
            "readiness_ref": self.readiness_ref,
            "baseline_sample_result_ref": baseline_ref,
            "lifecycle_result_ref": None,
            "baseline_development_trades": development_trades,
            "observed_days": readiness["profiles"][self.profile.value]["observed_days"],
            "minimum_days": readiness["profiles"][self.profile.value]["minimum_days"],
            "strategy_outcomes_read": strategy_outcomes_read,
            "test_segment": "NOT_READ",
            "analysis": None,
            "conclusion": "INSUFFICIENT_EVIDENCE",
            "p7_nominee": None,
            "reason": reason,
            "decision": "do not run the frozen condition until the prerequisite gate passes",
        }, refs=tuple(refs))

    def _analyze(self, lifecycle: dict[str, Any], symbols: Sequence[str]) -> dict[str, Any]:
        rows = {(row["variant_id"], row["cost"], row["split"]): row
                for row in lifecycle["results"]}
        baseline_id = self.study["baseline"].variant_id
        condition_id = self.study["condition"].variant_id
        baseline = rows[(baseline_id, "BASE", "VALIDATION")]
        selected = rows[(condition_id, "BASE", "VALIDATION")]
        conservative = rows[(condition_id, "CONSERVATIVE", "VALIDATION")]
        metrics = selected["metrics"]
        values = [Decimal(str(item["net_pnl"])) for item in selected["trade_ledger"]]
        if len(values) >= 5:
            p_value = permutation_p_value(values, seed=R3_SEED)
            bootstrap = block_bootstrap_expectancy_lower(
                values, block_size=min(5, len(values)), seed=R3_SEED)
        else:
            p_value, bootstrap = ONE, ZERO
        times = self._validation_times(symbols)
        step = 900 if self.profile is TimeframeProfile.INTRADAY_15M else 14_400
        windows = _temporal_windows(
            baseline["trade_ledger"], selected["trade_ledger"], times, step_seconds=step)
        positive_windows = sum(item["selected_net_profit"] > ZERO for item in windows)
        trades = int(metrics["trades"])
        expectancy = Decimal(metrics["net_profit"])/Decimal(trades) if trades else ZERO
        checks = (
            (trades >= 30, "VALIDATION completed trades below 30"),
            (Decimal(metrics["net_profit"]) > ZERO, "VALIDATION BASE net profit is not positive"),
            (expectancy > ZERO, "VALIDATION BASE expectancy is not positive"),
            (metrics["profit_factor"] is not None
             and Decimal(metrics["profit_factor"]) >= Decimal("1.30"),
             "VALIDATION BASE PF below 1.30"),
            (Decimal(conservative["metrics"]["net_profit"]) >= ZERO,
             "VALIDATION CONSERVATIVE net profit is negative"),
            (p_value < Decimal("0.05"), "permutation p-value is not below 0.05"),
            (bootstrap > ZERO, "bootstrap expectancy lower bound is not positive"),
            (positive_windows >= 4, "fewer than 4/5 windows have positive net profit"),
        )
        reasons = tuple(message for passed, message in checks if not passed)
        conclusion = ("ELIGIBLE_FOR_P7" if not reasons else
                      "INSUFFICIENT_EVIDENCE" if trades < 30 else "REJECT")
        return {
            "baseline_validation": baseline["metrics"],
            "condition_validation": metrics,
            "condition_conservative_validation": conservative["metrics"],
            "candidate_diagnostics": selected["candidate_diagnostics"],
            "structural_condition_summary": _trace_summary(
                lifecycle.get("structural_condition_traces", {}), condition_id),
            "validation_expectancy": expectancy,
            "permutation_p": p_value,
            "bootstrap_expectancy_lower": bootstrap,
            "temporal_windows": windows,
            "positive_net_windows": positive_windows,
            "conclusion": conclusion,
            "reasons": reasons,
        }

    def _validation_times(self, symbols: Sequence[str]) -> tuple[int, ...]:
        manifest = self.store.get(self.readiness_ref)["payload"]["dataset_manifest_ref"]
        period = "15min" if self.profile is TimeframeProfile.INTRADAY_15M else "4hour"
        times = set()
        for symbol in symbols:
            candles = load_manifest_candles(self.store, manifest, symbol=symbol, period=period)
            start, end = len(candles)*60//100, len(candles)*80//100
            times.update(item.open_time for item in candles[start:end])
        return tuple(sorted(times))


def run_independent_profile_study(evidence: Path, readiness_hash: str,
                                  profile: TimeframeProfile) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        result = IndependentProfileStudy(store, readiness_hash, profile).run()
        return {"result_hash": result}


def _trace_summary(traces: dict[str, list[dict]], variant_id: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for key, rows in traces.items():
        if not key.endswith(f":{variant_id}"):
            continue
        for row in rows:
            status = str(row["status"])
            counts[status] = counts.get(status, 0)+1
    return counts
