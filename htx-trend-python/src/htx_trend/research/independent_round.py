"""R3 pre-registration and independent-data readiness gate.

This module deliberately does not evaluate strategy returns.  It freezes the
hypotheses and proves that an unseen dataset is old enough before any research
implementation is allowed to inspect outcomes.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path

from ..market_data import period_anchor_seconds, period_seconds
from ..timeframes import TimeframeProfile
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

R3_CUTOFF = 1_791_676_800  # 2026-10-11T00:00:00Z
SYMBOLS = ("BTC-USDT", "ETH-USDT")
PERIODS = ("15min", "30min", "60min", "4hour", "1day")


@dataclass(frozen=True, slots=True)
class PreregisteredHypothesis:
    hypothesis_id: str
    profile: str
    model: str
    version: str
    formula: str
    rationale: str
    mutable_parameters: tuple[str, ...] = ()


HYPOTHESES = (
    PreregisteredHypothesis(
        "R3_15M_DONCHIAN_RETEST_RECLAIM",
        TimeframeProfile.INTRADAY_15M.value,
        "DONCHIAN_BREAKOUT",
        "r3-v1",
        "after a closed-bar breakout, enter only when one of the next 1-3 closed 15m bars "
        "touches the frozen breakout boundary without crossing the frozen invalidation and "
        "closes back on the breakout side; execution remains next-bar open",
        "R2 showed occasional Donchian improvement under 60m alignment but unstable false "
        "breakouts; this tests a different event sequence rather than another score threshold",
    ),
    PreregisteredHypothesis(
        "R3_4H_1D_TREND_PERSISTENCE_3",
        TimeframeProfile.SWING_4H.value,
        "EMA_VWAP_MOMENTUM_CONTINUATION",
        "r3-v1",
        "the latest three fully closed 1d bars must each have side-aligned EMA20/60/120 stack, "
        "OLS close slope20, ROC12 and ADX14>=16",
        "R2 4h EMA/VWAP was profitable in one validation segment but unstable across windows; "
        "persistence targets regime transitions without changing the entry or exit thresholds",
    ),
)

READINESS = {
    TimeframeProfile.INTRADAY_15M.value: {"period": "15min", "minimum_days": 60},
    TimeframeProfile.SWING_4H.value: {"period": "4hour", "minimum_days": 180},
}


def preregister_independent_round(store: FileEvidenceStore, *, cutoff: int = R3_CUTOFF) -> str:
    if cutoff != R3_CUTOFF:
        raise ValueError("R3 cutoff is frozen at 2026-10-11T00:00:00Z")
    plan = {
        "schema": "htx-r3-independent-research-plan-v1",
        "experiment_id": "EXP-20261018-R3-001",
        "cutoff": cutoff,
        "symbols": SYMBOLS,
        "periods": PERIODS,
        "hypotheses": tuple(asdict(item) for item in HYPOTHESES),
        "readiness": READINESS,
        "minimum_complete_trades_per_profile": 30,
        "selection": "single-hypothesis paired ablation; no parameter search",
        "evaluation": "four costs, five chronological windows, fixed-seed permutation and block bootstrap",
        "promotion": "positive BASE expectancy/net, PF>=1.30, conservative net>=0, p<0.05, "
                     "bootstrap expectancy lower>0 and >=4/5 positive windows",
        "old_test_policy": "R2 TEST remains NOT_READ and is forbidden as R3 input",
        "insufficient_data_policy": "record ACCRUING/INSUFFICIENT_EVIDENCE without running outcomes",
        "scope": "research only; no strategy mutation, TEST, PAPER, LIVE or admission",
    }
    return store.put("independent_research_plan", plan)


def audit_independent_data(store: FileEvidenceStore, plan_ref: str, manifest_ref: str,
                           market_facts_ref: str) -> str:
    store.verify(plan_ref)
    envelope = store.get(plan_ref)
    if envelope["kind"] != "independent_research_plan":
        raise ValueError("R3 readiness requires an independent research plan")
    plan = envelope["payload"]
    manifest = _complete_manifest(store, manifest_ref, "dataset_manifest")
    facts = _complete_manifest(store, market_facts_ref, "market_facts_manifest")
    if tuple(manifest["symbols"]) != tuple(plan["symbols"]):
        raise ValueError("R3 dataset symbols differ from the frozen plan")
    if tuple(manifest["periods"]) != tuple(plan["periods"]):
        raise ValueError("R3 dataset periods differ from the frozen plan")
    if tuple(facts["symbols"]) != tuple(plan["symbols"]):
        raise ValueError("R3 market facts symbols differ from the frozen plan")
    start, end = int(manifest["start"]), int(manifest["end_exclusive"])
    if start < int(plan["cutoff"]):
        raise ValueError("R3 dataset overlaps the prohibited R2 development interval")
    if int(facts["start"]) != start or int(facts["end_exclusive"]) != end:
        raise ValueError("R3 market facts interval differs from the dataset interval")
    duration_seconds = end-start
    if duration_seconds <= 0:
        raise ValueError("R3 dataset interval is empty")

    bar_counts = {}
    for symbol in plan["symbols"]:
        bar_counts[symbol] = {}
        for period in plan["periods"]:
            candles = load_manifest_candles(store, manifest_ref, symbol=symbol, period=period)
            step = period_seconds(period)
            anchor = period_anchor_seconds(period)
            lower = ((start-anchor+step-1)//step)*step+anchor
            upper = ((end-anchor)//step)*step+anchor
            expected = (upper-lower)//step
            if len(candles) != expected:
                raise ValueError(f"R3 bar count is incomplete: {symbol}:{period}")
            bar_counts[symbol][period] = len(candles)

    profiles = {}
    for profile, requirement in plan["readiness"].items():
        minimum_days = int(requirement["minimum_days"])
        observed_days = duration_seconds // 86_400
        coverage_ready = observed_days >= minimum_days
        profiles[profile] = {
            "period": requirement["period"],
            "observed_days": observed_days,
            "minimum_days": minimum_days,
            "coverage_ready": coverage_ready,
            "complete_trade_sample": "NOT_EVALUATED_DATA_TIME_GATE",
            "status": "READY_FOR_BASELINE_SAMPLE_AUDIT" if coverage_ready else "INSUFFICIENT_EVIDENCE",
        }
    ready = all(item["coverage_ready"] for item in profiles.values())
    result = {
        "schema": "htx-r3-independent-data-readiness-v1",
        "plan_ref": plan_ref,
        "plan_hash": digest(plan),
        "dataset_manifest_ref": manifest_ref,
        "market_facts_manifest_ref": market_facts_ref,
        "interval": {"start": start, "end_exclusive": end, "days": duration_seconds // 86_400},
        "bar_counts": bar_counts,
        "profiles": profiles,
        "strategy_outcomes_read": False,
        "test_segment": "NOT_READ",
        "status": "READY_FOR_BASELINE_SAMPLE_AUDIT" if ready else "ACCRUING",
        "conclusion": "NOT_EVALUATED" if ready else "INSUFFICIENT_EVIDENCE",
        "decision": "do not run a profile outcome experiment until that profile reaches its data-time gate",
    }
    return store.put("independent_data_readiness", result,
                     refs=(plan_ref, manifest_ref, market_facts_ref))


def _complete_manifest(store: FileEvidenceStore, ref: str, kind: str) -> dict:
    store.verify(ref)
    envelope = store.get(ref)
    if envelope["kind"] != kind or envelope["payload"].get("integrity") != "COMPLETE":
        raise ValueError(f"R3 requires a COMPLETE {kind}")
    return envelope["payload"]


def run_preregister_independent_round(evidence: Path) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        return {"plan_hash": preregister_independent_round(store)}


def run_independent_data_audit(evidence: Path, plan_hash: str, manifest_hash: str,
                               market_facts_manifest_hash: str) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        return {"result_hash": audit_independent_data(
            store, plan_hash, manifest_hash, market_facts_manifest_hash)}
