"""R2 P3 condition hit, lift and stability audit over frozen P2 labels."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal
from hashlib import sha256
from pathlib import Path
import random
from typing import Any, Sequence

from ..models import ModelCode
from ..java_entry_contract import candidates as entry_candidates, evaluate
from ..strategy_profiles import profile_strategy
from ..timeframes import TimeframeProfile
from .conditions import CONDITION_SPECS, ResearchConditionResult, research_condition_results
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class ConditionAuditObservation:
    observation_id: str
    profile: str
    symbol: str
    model: str
    side: str
    signal_time: int
    window_hash: str
    decision_hash: str
    label_window_hash: str
    direction_correct: bool
    target_first: bool
    net_profitable: bool
    net_pnl: Decimal
    candidate_score: Decimal
    conditions: tuple[ResearchConditionResult, ...]


class ConditionEffectivenessAudit:
    """Audit pre-registered conditions without changing the strategy gate."""

    def __init__(self, store: FileEvidenceStore, candidate_label_index_hash: str,
                 *, seed: int = 23, bootstrap_iterations: int = 1_000,
                 chunk_size: int = 2_000) -> None:
        if bootstrap_iterations < 100 or chunk_size < 1:
            raise ValueError("condition audit requires >=100 bootstrap iterations and positive chunks")
        store.verify(candidate_label_index_hash)
        envelope = store.get(candidate_label_index_hash)
        if envelope["kind"] != "dual_profile_candidate_label_index":
            raise ValueError("P3 requires a dual-profile P2 candidate label index")
        self.store, self.index_hash = store, candidate_label_index_hash
        self.index = envelope["payload"]
        self.seed, self.bootstrap_iterations, self.chunk_size = seed, bootstrap_iterations, chunk_size

    def run(self) -> str:
        plan = {
            "schema": "htx-r2-condition-audit-plan-v1",
            "candidate_label_index_hash": self.index_hash,
            "conditions": CONDITION_SPECS,
            "seed": self.seed,
            "bootstrap_iterations": self.bootstrap_iterations,
            "confidence_method": "paired chronological 50-block bootstrap with replacement",
            "temporal_windows": 5,
            "promotion_rule": "rejects>0, selected>=30, expectancy and at least one accuracy lift CI95 lower>0, with both positive in >=4/5 windows",
            "scope": "development diagnostic only; conditions remain RECORD_ONLY",
        }
        plan_ref = self.store.put("condition_audit_plan", plan, refs=(self.index_hash,))
        profile_refs = {}
        for profile in (TimeframeProfile.INTRADAY_15M, TimeframeProfile.SWING_4H):
            profile_refs[profile.value] = self._run_profile(profile, plan_ref)
        return self.store.put("dual_profile_condition_audit_index", {
            "schema": "htx-r2-condition-audit-index-v1",
            "plan_hash": digest(plan), "plan_ref": plan_ref,
            "candidate_label_index_hash": self.index_hash,
            "dataset_role": "ALREADY_SEEN_DEVELOPMENT_DATA_NOT_NEW_OOS",
            "profile_results": profile_refs,
        }, refs=(plan_ref, *profile_refs.values()))

    def _run_profile(self, profile: TimeframeProfile, plan_ref: str) -> str:
        p2_ref = self.index["profile_results"][profile.value]
        p2 = self.store.get(p2_ref)["payload"]
        manifest_hash = p2["manifest_hash"]
        spec = profile_strategy(profile, read_env=False)
        if p2["spec_hash"] != spec.spec_hash:
            raise ValueError("P2 strategy spec differs from P3 audit runtime")
        source_rows: list[dict] = []
        for ref in p2["observation_chunk_refs"]:
            for row in self.store.get(ref)["payload"]["rows"]:
                if row["model_status"] == "CANDIDATE" and row["label"]["status"] == "KNOWN":
                    source_rows.append(row)
        grouped: dict[str, list[dict]] = defaultdict(list)
        for row in source_rows:
            grouped[row["symbol"]].append(row)
        audited: list[ConditionAuditObservation] = []
        for symbol, rows in grouped.items():
            candles = load_manifest_candles(self.store, manifest_hash, symbol=symbol,
                                            period=spec.entry_period)
            by_time = {item.open_time: index for index, item in enumerate(candles)}
            observations = defaultdict(list)
            for row in rows:
                observations[int(row["signal_time"])].append(row)
            for signal_time, matching in observations.items():
                index = by_time[signal_time]
                window = candles[max(0, index-p2["window"]+1):index+1]
                input_hash = sha256("\n".join(item.content_hash for item in window).encode()).hexdigest()
                if input_hash != matching[0]["window_hash"]:
                    raise ValueError(f"P2 window hash cannot be reconstructed: {symbol}:{signal_time}")
                signal = evaluate(window, spec.entry_config)
                resolved = entry_candidates(window, signal, spec.entry_config, spec.entry_period)
                factors = signal.indicators
                if factors is None:
                    raise ValueError("P2 candidate unexpectedly lacks an indicator snapshot")
                for row in matching:
                    candidates = [item for item in resolved if item.model.value == row["model"]]
                    if not candidates:
                        raise ValueError(f"P2 candidate cannot be reproduced: {row['observation_id']}")
                    candidate = max(candidates, key=lambda item: (item.score, item.expected_r,
                                                                  item.policy_confirmations or ""))
                    conditions = research_condition_results(
                        side=candidate.side, factors=factors, candles=window,
                        local_regime=signal.regime, candidate_score=candidate.score)
                    label = row["label"]
                    audited.append(ConditionAuditObservation(
                        row["observation_id"], profile.value, symbol, row["model"], row["side"],
                        signal_time, row["window_hash"], row["decision_hash"], label["label_window_hash"],
                        bool(label["direction_correct"]), bool(label["target_first"]),
                        bool(label["net_profitable"]), Decimal(label["net_pnl"]), candidate.score, conditions))
        chunk_refs = [self.store.put("condition_audit_observation_chunk", {
            "profile": profile.value, "offset": offset, "rows": audited[offset:offset+self.chunk_size]},
            refs=(p2_ref,)) for offset in range(0, len(audited), self.chunk_size)]
        summaries = []
        for condition in CONDITION_SPECS:
            overall = _effect(audited, condition.condition_id, self.seed,
                              self.bootstrap_iterations, legacy=condition.legacy)
            by_model = {model.value: _effect(
                [row for row in audited if row.model == model.value], condition.condition_id,
                self.seed, self.bootstrap_iterations, legacy=condition.legacy)
                for model in ModelCode}
            summaries.append({"condition": condition, "overall": overall, "by_model": by_model})
        return self.store.put("condition_effectiveness_audit", {
            "schema": "htx-r2-condition-effectiveness-v1",
            "profile": profile.value, "strategy_version": spec.strategy.version,
            "spec_hash": spec.spec_hash, "manifest_hash": manifest_hash,
            "market_facts_manifest_hash": p2["market_facts_manifest_hash"],
            "candidate_label_result_ref": p2_ref, "plan_ref": plan_ref,
            "candidate_count": len(audited), "observation_chunk_refs": chunk_refs,
            "summaries": summaries,
            "decision": "no condition promoted; P3 only marks P4 research eligibility",
            "trace_rule": "condition aggregates filter immutable audit rows which retain P2 observation and label hashes",
        }, refs=(plan_ref, p2_ref, *chunk_refs))


def _condition_pass(row: ConditionAuditObservation, condition_id: str) -> bool:
    return next(item.passed for item in row.conditions if item.condition_id == condition_id)


def _metrics(rows: Sequence[ConditionAuditObservation]) -> dict[str, Decimal | int]:
    return _metrics_from_raw(_raw(rows))


def _raw(rows: Sequence[ConditionAuditObservation]) -> tuple[int, int, int, int, Decimal]:
    return (len(rows), sum(row.direction_correct for row in rows),
            sum(row.target_first for row in rows), sum(row.net_profitable for row in rows),
            sum((row.net_pnl for row in rows), ZERO))


def _sum_raw(values: Sequence[tuple[int, int, int, int, Decimal]]) -> tuple[int, int, int, int, Decimal]:
    return tuple(sum((value[index] for value in values), ZERO if index == 4 else 0)
                 for index in range(5))


def _metrics_from_raw(raw: tuple[int, int, int, int, Decimal]) -> dict[str, Decimal | int]:
    count, direction, target, wins, net = raw
    if not count:
        return {"count": 0, "direction_accuracy": ZERO, "target_first_rate": ZERO,
                "net_win_rate": ZERO, "expectancy": ZERO, "net_profit": ZERO}
    denominator = Decimal(count)
    return {
        "count": count,
        "direction_accuracy": Decimal(direction)/denominator,
        "target_first_rate": Decimal(target)/denominator,
        "net_win_rate": Decimal(wins)/denominator,
        "expectancy": net/denominator,
        "net_profit": net,
    }


def _lifts(baseline: dict, selected: dict) -> dict[str, Decimal]:
    return {name+"_lift": selected[name]-baseline[name] for name in
            ("direction_accuracy", "target_first_rate", "net_win_rate", "expectancy")}


def _confidence(rows: Sequence[ConditionAuditObservation], condition_id: str,
                seed: int, iterations: int) -> dict[str, tuple[Decimal, Decimal] | None]:
    if len(rows) < 2:
        return {name: None for name in ("direction_accuracy_lift", "target_first_rate_lift",
                                        "net_win_rate_lift", "expectancy_lift")}
    rng = random.Random(seed + sum(condition_id.encode()))
    values: dict[str, list[Decimal]] = defaultdict(list)
    ordered_rows = sorted(rows, key=lambda row: (row.signal_time, row.symbol, row.model))
    block_count = min(50, len(ordered_rows))
    blocks = [ordered_rows[len(ordered_rows)*index//block_count:
                           len(ordered_rows)*(index+1)//block_count]
              for index in range(block_count)]
    raw_blocks = [_raw(block) for block in blocks]
    selected_blocks = [_raw([row for row in block if _condition_pass(row, condition_id)])
                       for block in blocks]
    for _ in range(iterations):
        indexes = [rng.randrange(block_count) for _ in range(block_count)]
        baseline_raw = _sum_raw([raw_blocks[index] for index in indexes])
        selected_raw = _sum_raw([selected_blocks[index] for index in indexes])
        if not selected_raw[0]:
            continue
        for name, value in _lifts(_metrics_from_raw(baseline_raw),
                                  _metrics_from_raw(selected_raw)).items():
            values[name].append(value)
    result = {}
    for name in ("direction_accuracy_lift", "target_first_rate_lift", "net_win_rate_lift", "expectancy_lift"):
        ordered = sorted(values[name])
        result[name] = None if not ordered else (
            ordered[int((len(ordered)-1)*Decimal("0.025"))],
            ordered[int((len(ordered)-1)*Decimal("0.975"))])
    return result


def _temporal_windows(rows: Sequence[ConditionAuditObservation], condition_id: str) -> list[dict[str, Any]]:
    times = sorted(set(row.signal_time for row in rows))
    windows = []
    for number in range(5):
        selected_times = set(times[len(times)*number//5:len(times)*(number+1)//5])
        baseline_rows = [row for row in rows if row.signal_time in selected_times]
        selected_rows = [row for row in baseline_rows if _condition_pass(row, condition_id)]
        baseline, selected = _metrics(baseline_rows), _metrics(selected_rows)
        windows.append({"window": number+1, "start": min(selected_times) if selected_times else None,
                        "end": max(selected_times) if selected_times else None,
                        "baseline": baseline, "selected": selected,
                        "lifts": _lifts(baseline, selected) if selected_rows else None})
    return windows


def _effect(rows: Sequence[ConditionAuditObservation], condition_id: str, seed: int,
            iterations: int, *, legacy: bool) -> dict[str, Any]:
    selected_rows = [row for row in rows if _condition_pass(row, condition_id)]
    baseline, selected = _metrics(rows), _metrics(selected_rows)
    lifts = _lifts(baseline, selected) if selected_rows else None
    confidence = _confidence(rows, condition_id, seed, iterations)
    windows = _temporal_windows(rows, condition_id)
    positive = sum(bool(item["lifts"] and item["lifts"]["expectancy_lift"] > ZERO) for item in windows)
    accuracy_names = ("direction_accuracy_lift", "target_first_rate_lift", "net_win_rate_lift")
    positive_accuracy = {name: sum(bool(item["lifts"] and item["lifts"][name] > ZERO)
                                   for item in windows) for name in accuracy_names}
    supported_accuracy = tuple(name for name in accuracy_names
                               if confidence[name] is not None and confidence[name][0] > ZERO
                               and positive_accuracy[name] >= 4)
    rejected = len(rows)-len(selected_rows)
    lower = confidence["expectancy_lift"] and confidence["expectancy_lift"][0]
    if legacy:
        conclusion = "DEPRECATED_DIAGNOSTIC_ONLY"
    elif rejected == 0:
        conclusion = "REDUNDANT_RECORD_ONLY"
    elif len(selected_rows) < 30:
        conclusion = "INSUFFICIENT_EVIDENCE_RECORD_ONLY"
    elif (lifts and lifts["expectancy_lift"] > ZERO and lower is not None and lower > ZERO
          and positive >= 4 and supported_accuracy):
        conclusion = "ELIGIBLE_FOR_P4_RESEARCH"
    else:
        conclusion = "NO_STABLE_GAIN_RECORD_ONLY"
    return {
        "baseline": baseline, "selected": selected, "rejected": rejected,
        "coverage": Decimal(len(selected_rows))/Decimal(len(rows)) if rows else ZERO,
        "lifts": lifts, "confidence_95": confidence, "temporal_windows": windows,
        "positive_expectancy_lift_windows": positive,
        "positive_accuracy_lift_windows": positive_accuracy,
        "supported_accuracy_metrics": supported_accuracy, "conclusion": conclusion,
    }


def run_condition_audit(evidence: Path, candidate_label_index_hash: str) -> dict[str, Any]:
    with FileEvidenceStore(evidence) as store:
        result = ConditionEffectivenessAudit(store, candidate_label_index_hash).run()
        payload = store.get(result)["payload"]
        return {"index_hash": result, "profile_results": payload["profile_results"]}
