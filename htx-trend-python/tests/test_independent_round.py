from pathlib import Path

import pytest

from htx_trend.research.independent_round import (
    PERIODS,
    R3_CUTOFF,
    SYMBOLS,
    audit_independent_data,
    preregister_independent_round,
)
from htx_trend.research.independent_experiments import IndependentProfileStudy
from htx_trend.timeframes import TimeframeProfile
from htx_trend.market_data import period_anchor_seconds, period_seconds
from htx_trend.research.store import FileEvidenceStore


def _manifests(store: FileEvidenceStore, *, start: int = R3_CUTOFF,
               days: int = 7) -> tuple[str, str]:
    end = start + days * 86_400
    pages = []
    for symbol in SYMBOLS:
        for period in PERIODS:
            step = period_seconds(period)
            anchor = period_anchor_seconds(period)
            lower = ((start-anchor+step-1)//step)*step+anchor
            upper = ((end-anchor)//step)*step+anchor
            candles = [{"open_time": timestamp, "bar": {
                "open_time": timestamp, "open": "1", "high": "1", "low": "1", "close": "1",
                "amount": "1", "volume": "1", "turnover": "1", "trade_count": 1,
            }} for timestamp in range(lower, upper, step)]
            pages.append(store.put("kline_page", {
                "symbol": symbol, "period": period, "start": start,
                "end_exclusive": end, "integrity": "COMPLETE", "candles": candles,
            }))
    dataset = store.put("dataset_manifest", {
        "integrity": "COMPLETE", "symbols": SYMBOLS, "periods": PERIODS,
        "start": start, "end_exclusive": end, "pages": pages,
    }, refs=tuple(pages))
    facts = store.put("market_facts_manifest", {
        "integrity": "COMPLETE", "symbols": SYMBOLS, "start": start,
        "end_exclusive": end,
    })
    return dataset, facts


def test_r3_preregistration_and_short_accrual_are_fail_closed(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        plan = preregister_independent_round(store)
        dataset, facts = _manifests(store)
        result = audit_independent_data(store, plan, dataset, facts)
        payload = store.get(result)["payload"]
        assert payload["status"] == "ACCRUING"
        assert payload["conclusion"] == "INSUFFICIENT_EVIDENCE"
        assert payload["strategy_outcomes_read"] is False
        assert payload["test_segment"] == "NOT_READ"
        assert payload["profiles"]["INTRADAY_15M"]["observed_days"] == 7
        assert payload["profiles"]["SWING_4H"]["minimum_days"] == 180
        assert store.verify(result)["integrity"] == "VERIFIED"


def test_r3_rejects_any_dataset_overlapping_r2(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        plan = preregister_independent_round(store)
        dataset, facts = _manifests(store, start=R3_CUTOFF-86_400)
        with pytest.raises(ValueError, match="overlaps"):
            audit_independent_data(store, plan, dataset, facts)


def test_r3_plan_has_two_frozen_hypotheses_and_no_search_grid(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        plan = preregister_independent_round(store)
        payload = store.get(plan)["payload"]
        assert len(payload["hypotheses"]) == 2
        assert all(item["mutable_parameters"] == [] for item in payload["hypotheses"])
        assert payload["selection"] == "single-hypothesis paired ablation; no parameter search"


@pytest.mark.parametrize("profile", tuple(TimeframeProfile))
def test_r4_r5_studies_stop_before_reading_outcomes_when_time_gate_is_closed(
        tmp_path: Path, profile: TimeframeProfile) -> None:
    with FileEvidenceStore(tmp_path) as store:
        plan = preregister_independent_round(store)
        dataset, facts = _manifests(store)
        readiness = audit_independent_data(store, plan, dataset, facts)
        result = IndependentProfileStudy(store, readiness, profile).run()
        payload = store.get(result)["payload"]
        assert payload["stage"] == ("R4" if profile is TimeframeProfile.INTRADAY_15M else "R5")
        assert payload["conclusion"] == "INSUFFICIENT_EVIDENCE"
        assert payload["reason"] == "DATA_TIME_GATE_NOT_READY"
        assert payload["strategy_outcomes_read"] is False
        assert payload["test_segment"] == "NOT_READ"
        assert payload["lifecycle_result_ref"] is None
        assert store.verify(result)["integrity"] == "VERIFIED"


def test_r4_r5_reject_readiness_that_does_not_reference_frozen_hypothesis(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        plan = preregister_independent_round(store)
        dataset, facts = _manifests(store)
        readiness = audit_independent_data(store, plan, dataset, facts)
        readiness_payload = store.get(readiness)["payload"]
        wrong_plan = store.put("independent_research_plan", {
            **store.get(plan)["payload"], "hypotheses": (),
        })
        forged = store.put("independent_data_readiness", {
            **readiness_payload,
            "plan_ref": wrong_plan,
            "plan_hash": store.get(readiness)["payload"]["plan_hash"],
        }, refs=(wrong_plan, dataset, facts))
        with pytest.raises(ValueError, match="plan hash differs"):
            IndependentProfileStudy(
                store, forged, TimeframeProfile.INTRADAY_15M).run()
