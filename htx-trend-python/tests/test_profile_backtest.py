from decimal import Decimal
from dataclasses import replace

import pytest

from htx_trend.market_data import Candle
from htx_trend.models import ModelCode
from htx_trend.research.profile_backtest import (DEFAULT_VARIANTS, REDUNDANT_RECORD_ONLY_VARIANTS,
                                                 ProfileLifecycleResearch, ResearchVariant,
                                                 provisional_backtest_admission)
from htx_trend.research.store import FileEvidenceStore
from htx_trend.strategy_admission import DecisionSurface
from htx_trend.strategy_profiles import profile_strategy
from htx_trend.timeframes import TimeframeProfile


def _candles(step: int, count: int, *, offset: int = 0):
    rows = []
    for i in range(count):
        # Alternating trend blocks exercise long, short and flat decisions.
        block, within = divmod(i, 45)
        center = Decimal("100") + Decimal(block * 3) + Decimal(within if block % 2 == 0 else 45-within) / 10
        rows.append(Candle(offset+i*step, center, center+Decimal("1.2"), center-Decimal("1.1"),
                           center+Decimal("0.2"), Decimal("100"), Decimal("10000"),
                           Decimal("1000000"), 10))
    return tuple(rows)


def _manifest(store: FileEvidenceStore, count: int = 180):
    pages = []
    for symbol in ("BTC-USDT", "ETH-USDT"):
        for period, step in (("15min", 900), ("4hour", 14400), ("1day", 86400)):
            values = _candles(step, count)
            page = store.put("kline_page", {"symbol": symbol, "period": period,
                "integrity": "COMPLETE", "candles": [{"open_time": x.open_time, "bar": x} for x in values]})
            pages.append(page)
    return store.put("dataset_manifest", {"integrity": "COMPLETE", "pages": pages}, refs=tuple(pages))


def _market_facts(store: FileEvidenceStore, count: int = 180):
    contract_refs, funding_refs = [], []
    end = count * 14_400
    for symbol in ("BTC-USDT", "ETH-USDT"):
        contract_refs.append(store.put("contract_market_fact", {
            "contract_code": symbol, "contract_size": ".001", "price_tick": ".1",
            "minimum_order_contracts": 1, "settlement_period_hours": 8,
            "contract_status": 1, "observed_at": end,
        }))
        events = [{"contract_code": symbol, "funding_time": timestamp,
                   "funding_rate": ".0001", "realized_rate": None,
                   "effective_rate": ".0001", "rate_source": "FUNDING_RATE",
                   "fee_asset": "USDT", "funding_fee_type": "normal"}
                  for timestamp in range(0, end, 28_800)]
        funding_refs.append(store.put("historical_funding_page", {
            "contract_code": symbol, "page_index": 1, "observed_at": end,
            "events": events, "raw_ref": "test"}))
    refs = tuple(contract_refs + funding_refs)
    return store.put("market_facts_manifest", {
        "integrity": "COMPLETE", "symbols": ("BTC-USDT", "ETH-USDT"),
        "start": 0, "end_exclusive": end, "contract_fact_refs": contract_refs,
        "funding_page_refs": funding_refs,
    }, refs=refs)


def test_provisional_admission_is_backtest_only() -> None:
    spec = profile_strategy(TimeframeProfile.INTRADAY_15M, read_env=False)
    admission = provisional_backtest_admission(spec, "a"*64)
    admission.require(DecisionSurface.BACKTEST)
    assert admission.allowed_surfaces == (DecisionSurface.BACKTEST,)


def test_redundant_p3_variants_are_auditable_but_not_run_by_default() -> None:
    active = {item.variant_id for item in DEFAULT_VARIANTS}
    redundant = {item.variant_id for item in REDUNDANT_RECORD_ONLY_VARIANTS}
    assert redundant == {"ADD_REGIME", "SENSITIVITY_SCORE_65", "SENSITIVITY_SCORE_70"}
    assert active.isdisjoint(redundant)
    versions = {item.variant_id: item.condition_version for item in DEFAULT_VARIANTS}
    assert versions["ADD_PRICE_SLOPE"] == "r2-p3-v2"
    assert versions["ADD_EMA_SLOPE"] == "r2-p3-v2"


def test_profile_research_is_content_addressed_and_reproducible(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = _manifest(store)
        facts = _market_facts(store)
        runner = ProfileLifecycleResearch(store, manifest, facts, TimeframeProfile.INTRADAY_15M)
        variant = (ResearchVariant("BASELINE"),)
        first = runner.run(variants=variant, seed=7)
        second = runner.run(variants=variant, seed=7)
        assert first == second
        payload = store.get(first)["payload"]
        assert payload["result_kind"] == "STRATEGY_SIMULATION"
        assert payload["execution_assumptions"]["intrabar"] == "stop before target"
        assert {row["split"] for row in payload["results"]} == {"TRAIN", "VALIDATION", "TEST"}
        with pytest.raises(ValueError, match="already consumed"):
            runner.run(variants=(replace(variant[0], minimum_score=Decimal("71")),), seed=7)


def test_future_candle_does_not_change_prior_decision(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = _manifest(store, 180)
        facts = _market_facts(store, 180)
        runner = ProfileLifecycleResearch(store, manifest, facts, TimeframeProfile.INTRADAY_15M)
        base = _candles(900, 180)
        before = runner._decisions("BTC-USDT", base)[-1]
        extended = base + (Candle(180*900, Decimal("999"), Decimal("1000"), Decimal("998"),
                                  Decimal("999"), volume=Decimal("10000")),)
        after = runner._decisions("BTC-USDT", extended)[179]
        assert before is not None and after is not None
        assert before.decision_hash == after.decision_hash


def test_parameter_override_research_keeps_entry_and_execution_configs_separate(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = _manifest(store, 220)
        facts = _market_facts(store, 220)
        runner = ProfileLifecycleResearch(store, manifest, facts, TimeframeProfile.INTRADAY_15M)
        ref = runner.run(variants=(ResearchVariant(
            "LOOKBACK_16", entry_parameters=(("breakout_lookback", 16),)),),
            include_test=False, seed=11)
        payload = store.get(ref)["payload"]
        assert {row["split"] for row in payload["results"]} == {"TRAIN", "VALIDATION"}
        assert payload["test_segment"] == "NOT_READ"
        assert payload["walk_forward"]["end_percent"] == 80
        assert payload["variants"][0]["entry_parameters"] == [["breakout_lookback", 16]]
        assert all(row["candidate_diagnostics"]["available"] >=
                   row["candidate_diagnostics"]["passed"] for row in payload["results"])


@pytest.mark.parametrize(("profile", "variant"), (
    (TimeframeProfile.INTRADAY_15M, ResearchVariant(
        "R3_15M_DONCHIAN_RETEST_RECLAIM", (ModelCode.DONCHIAN_BREAKOUT,),
        structural_condition="R3_15M_DONCHIAN_RETEST_RECLAIM")),
    (TimeframeProfile.SWING_4H, ResearchVariant(
        "R3_4H_1D_TREND_PERSISTENCE_3", (ModelCode.EMA_MOMENTUM_CONTINUATION,),
        structural_condition="R3_4H_1D_TREND_PERSISTENCE_3")),
))
def test_shared_lifecycle_runner_applies_r3_structural_condition(
        tmp_path, profile: TimeframeProfile, variant: ResearchVariant) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = _manifest(store, 180)
        facts = _market_facts(store, 180)
        result = ProfileLifecycleResearch(
            store, manifest, facts, profile).run(variants=(variant,), include_test=False)
        payload = store.get(result)["payload"]
        trace_key = f"BTC-USDT:{variant.variant_id}"
        assert trace_key in payload["structural_condition_traces"]
        assert payload["test_segment"] == "NOT_READ"
        assert len(payload["results"]) == 8
