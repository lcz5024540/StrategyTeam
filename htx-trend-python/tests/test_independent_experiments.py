from dataclasses import asdict
from decimal import Decimal
from pathlib import Path

import pytest

from htx_trend.market_data import Candle
from htx_trend.research.features import performance
from htx_trend.research.independent_experiments import IndependentProfileStudy, PROFILE_STUDIES
from htx_trend.research.independent_round import preregister_independent_round
from htx_trend.research.store import FileEvidenceStore, digest
from htx_trend.timeframes import TimeframeProfile


def _ready_evidence(store: FileEvidenceStore, profile: TimeframeProfile) -> str:
    plan_ref = preregister_independent_round(store)
    plan = store.get(plan_ref)["payload"]
    period = "15min" if profile is TimeframeProfile.INTRADAY_15M else "4hour"
    step = 900 if profile is TimeframeProfile.INTRADAY_15M else 14_400
    pages = []
    for symbol in ("BTC-USDT", "ETH-USDT"):
        candles = [Candle(index*step, Decimal("100"), Decimal("101"), Decimal("99"),
                          Decimal("100"), Decimal("100"), Decimal("1000"),
                          Decimal("100000"), 10)
                   for index in range(100)]
        pages.append(store.put("kline_page", {
            "symbol": symbol, "period": period, "integrity": "COMPLETE",
            "candles": [{"open_time": item.open_time, "bar": item} for item in candles],
        }))
    manifest = store.put("dataset_manifest", {
        "integrity": "COMPLETE", "symbols": ("BTC-USDT", "ETH-USDT"),
        "periods": (period,), "start": 0, "end_exclusive": 100*step, "pages": pages,
    }, refs=tuple(pages))
    market = store.put("market_facts_manifest", {
        "integrity": "COMPLETE", "symbols": ("BTC-USDT", "ETH-USDT"),
        "start": 0, "end_exclusive": 100*step,
    })
    profiles = {
        item.value: {
            "period": "15min" if item is TimeframeProfile.INTRADAY_15M else "4hour",
            "observed_days": 60 if item is TimeframeProfile.INTRADAY_15M else 180,
            "minimum_days": 60 if item is TimeframeProfile.INTRADAY_15M else 180,
            "coverage_ready": item is profile,
            "complete_trade_sample": "NOT_EVALUATED_DATA_TIME_GATE",
            "status": "READY_FOR_BASELINE_SAMPLE_AUDIT" if item is profile else "INSUFFICIENT_EVIDENCE",
        } for item in TimeframeProfile
    }
    return store.put("independent_data_readiness", {
        "schema": "htx-r3-independent-data-readiness-v1",
        "plan_ref": plan_ref, "plan_hash": digest(plan),
        "dataset_manifest_ref": manifest, "market_facts_manifest_ref": market,
        "interval": {"start": 0, "end_exclusive": 100*step,
                     "days": profiles[profile.value]["observed_days"]},
        "profiles": profiles, "strategy_outcomes_read": False,
        "test_segment": "NOT_READ", "status": "READY_FOR_BASELINE_SAMPLE_AUDIT",
        "conclusion": "NOT_EVALUATED",
    }, refs=(plan_ref, manifest, market))


def _ledger(values: list[Decimal], *, step: int) -> list[dict]:
    validation_times = [index*step for index in range(60, 80)]
    return [{"entry_time": validation_times[min(index*20//max(1, len(values)), 19)],
             "net_pnl": value, "fees": Decimal("0.1"),
             "mfe": max(value, Decimal("0")), "mae": max(-value, Decimal("0"))}
            for index, value in enumerate(values)]


class _SyntheticLifecycleRunner:
    calls: list[tuple[str, ...]] = []
    baseline_development_trades = 32
    profitable = True
    omit_matrix_row = False

    def __init__(self, store: FileEvidenceStore, manifest_hash: str,
                 market_facts_manifest_hash: str, profile: TimeframeProfile, **_: object) -> None:
        self.store, self.manifest_hash = store, manifest_hash
        self.market_hash, self.profile = market_facts_manifest_hash, profile

    def run(self, *, symbols, variants, include_test, seed):
        assert tuple(symbols) == ("BTC-USDT", "ETH-USDT")
        assert include_test is False and seed == 83
        self.calls.append(tuple(item.variant_id for item in variants))
        step = 900 if self.profile is TimeframeProfile.INTRADAY_15M else 14_400
        rows = []
        for variant in variants:
            for cost in ("ZERO", "OPTIMISTIC", "BASE", "CONSERVATIVE"):
                for split in ("TRAIN", "VALIDATION"):
                    if len(variants) == 1:
                        count = self.baseline_development_trades//2
                        values = [Decimal("1")]*count
                    elif variant.variant_id == PROFILE_STUDIES[self.profile]["condition"].variant_id:
                        values = (([Decimal("2")]*5+[Decimal("-1")])*5
                                  if self.profitable else [Decimal("-1")]*30)
                    else:
                        values = ([Decimal("1")]*5+[Decimal("-1")])*5
                    ledger = _ledger(values, step=step)
                    rows.append({
                        "variant_id": variant.variant_id, "cost": cost, "split": split,
                        "metrics": asdict(performance(ledger)), "trades": len(ledger),
                        "candidate_diagnostics": {
                            "available": 40, "passed": len(ledger),
                            "rejected": 40-len(ledger), "unknown": 0,
                            "failed": 40-len(ledger),
                        },
                        "trade_ledger": ledger,
                    })
        if self.omit_matrix_row:
            rows.pop()
        condition_id = PROFILE_STUDIES[self.profile]["condition"].variant_id
        return self.store.put("profile_lifecycle_research", {
            "result_kind": "STRATEGY_SIMULATION", "profile": self.profile.value,
            "manifest_hash": self.manifest_hash,
            "market_facts_manifest_hash": self.market_hash,
            "test_segment": "NOT_READ", "results": rows,
            "variants": [asdict(item) for item in variants],
            "structural_condition_traces": {
                f"BTC-USDT:{condition_id}": [{"status": "PASS"}, {"status": "FAIL"}],
            },
        }, refs=(self.manifest_hash, self.market_hash))


@pytest.fixture(autouse=True)
def _reset_runner() -> None:
    _SyntheticLifecycleRunner.calls = []
    _SyntheticLifecycleRunner.baseline_development_trades = 32
    _SyntheticLifecycleRunner.profitable = True
    _SyntheticLifecycleRunner.omit_matrix_row = False


@pytest.mark.parametrize("profile", tuple(TimeframeProfile))
def test_ready_profile_runs_four_cost_development_matrix_and_can_nominate_p7(
        tmp_path: Path, profile: TimeframeProfile) -> None:
    with FileEvidenceStore(tmp_path) as store:
        readiness = _ready_evidence(store, profile)
        first = IndependentProfileStudy(
            store, readiness, profile, runner_factory=_SyntheticLifecycleRunner).run()
        second = IndependentProfileStudy(
            store, readiness, profile, runner_factory=_SyntheticLifecycleRunner).run()
        assert first == second
        payload = store.get(first)["payload"]
        assert payload["conclusion"] == "ELIGIBLE_FOR_P7"
        assert payload["p7_nominee"] == PROFILE_STUDIES[profile]["condition"].variant_id
        assert payload["test_segment"] == "NOT_READ"
        assert payload["analysis"]["positive_net_windows"] == 5
        assert Decimal(payload["analysis"]["permutation_p"]) < Decimal("0.05")
        assert Decimal(payload["analysis"]["bootstrap_expectancy_lower"]) > 0
        assert payload["analysis"]["structural_condition_summary"] == {"PASS": 1, "FAIL": 1}
        assert store.verify(first)["integrity"] == "VERIFIED"


def test_baseline_sample_gate_stops_before_condition_lifecycle(tmp_path: Path) -> None:
    _SyntheticLifecycleRunner.baseline_development_trades = 28
    with FileEvidenceStore(tmp_path) as store:
        readiness = _ready_evidence(store, TimeframeProfile.INTRADAY_15M)
        result = IndependentProfileStudy(
            store, readiness, TimeframeProfile.INTRADAY_15M,
            runner_factory=_SyntheticLifecycleRunner).run()
        payload = store.get(result)["payload"]
        assert payload["reason"] == "BASELINE_COMPLETE_TRADES_BELOW_30"
        assert payload["baseline_development_trades"] == 28
        assert payload["strategy_outcomes_read"] is True
        assert payload["lifecycle_result_ref"] is None
        assert len(_SyntheticLifecycleRunner.calls) == 1


def test_unprofitable_condition_is_rejected_without_nominee(tmp_path: Path) -> None:
    _SyntheticLifecycleRunner.profitable = False
    with FileEvidenceStore(tmp_path) as store:
        readiness = _ready_evidence(store, TimeframeProfile.SWING_4H)
        result = IndependentProfileStudy(
            store, readiness, TimeframeProfile.SWING_4H,
            runner_factory=_SyntheticLifecycleRunner).run()
        payload = store.get(result)["payload"]
        assert payload["conclusion"] == "REJECT"
        assert payload["p7_nominee"] is None
        assert "VALIDATION BASE net profit is not positive" in payload["analysis"]["reasons"]


def test_incomplete_four_cost_matrix_fails_closed(tmp_path: Path) -> None:
    _SyntheticLifecycleRunner.omit_matrix_row = True
    with FileEvidenceStore(tmp_path) as store:
        readiness = _ready_evidence(store, TimeframeProfile.INTRADAY_15M)
        with pytest.raises(ValueError, match="four-cost development matrix"):
            IndependentProfileStudy(
                store, readiness, TimeframeProfile.INTRADAY_15M,
                runner_factory=_SyntheticLifecycleRunner).run()
