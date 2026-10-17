from decimal import Decimal

from htx_trend.market_data import Candle
from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.research.candidate_labels import CandidateLabelConfig, CandidateLabelResearch
from htx_trend.research.condition_audit import ConditionEffectivenessAudit
from htx_trend.research.store import FileEvidenceStore
from htx_trend.timeframes import TimeframeProfile


def candles(step: int, count: int) -> tuple[Candle, ...]:
    rows = []
    for index in range(count):
        close = Decimal("100") + Decimal(index) / Decimal("10")
        rows.append(Candle(index*step, close-Decimal(".05"), close+Decimal(".2"),
                           close-Decimal(".2"), close, Decimal("100"), Decimal("100")))
    return tuple(rows)


def freeze(store: FileEvidenceStore, count: int = 170) -> tuple[str, str]:
    pages = []
    for period, step in (("15min", 900), ("30min", 1_800), ("60min", 3_600),
                         ("4hour", 14_400), ("1day", 86_400)):
        values = candles(step, count)
        pages.append(store.put("kline_page", {"symbol": "BTC-USDT", "period": period,
            "integrity": "COMPLETE", "candles": [{"open_time": row.open_time, "bar": row} for row in values]}))
    manifest = store.put("dataset_manifest", {"integrity": "COMPLETE", "pages": pages}, refs=tuple(pages))
    end = count*86_400
    contract = store.put("contract_market_fact", {"contract_code": "BTC-USDT",
        "contract_size": ".001", "price_tick": ".1", "minimum_order_contracts": 1,
        "settlement_period_hours": 8, "contract_status": 1, "observed_at": end})
    funding = store.put("historical_funding_page", {"contract_code": "BTC-USDT", "page_index": 1,
        "observed_at": end, "raw_ref": "test", "events": [
            {"contract_code": "BTC-USDT", "funding_time": time, "funding_rate": ".0001",
             "realized_rate": None, "effective_rate": ".0001", "rate_source": "FUNDING_RATE",
             "fee_asset": "USDT", "funding_fee_type": "normal"}
            for time in range(0, end, 28_800)]})
    facts = store.put("market_facts_manifest", {"integrity": "COMPLETE", "symbols": ["BTC-USDT"],
        "start": 0, "end_exclusive": end, "contract_fact_refs": [contract],
        "funding_page_refs": [funding]}, refs=(contract, funding))
    return manifest, facts


def test_candidate_census_has_three_models_per_closed_bar_and_traceable_labels(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest, facts = freeze(store)
        runner = CandidateLabelResearch(store, manifest, facts, TimeframeProfile.SWING_4H,
                                        label_config=CandidateLabelConfig(3, Decimal(".0004"), Decimal(".0003")),
                                        chunk_size=7)
        result = runner.run(symbols=("BTC-USDT",))
        payload = store.get(result)["payload"]
        assert payload["observation_count"] == 170*3
        assert payload["report"]["all_model_evaluations"] == payload["observation_count"]
        rows = [row for ref in payload["observation_chunk_refs"]
                for row in store.get(ref)["payload"]["rows"]]
        assert len(rows) == payload["observation_count"]
        assert all(row["window_hash"] and row["observation_id"] for row in rows)
        assert all(row["model_status"] in {"CANDIDATE", "REJECTED"} for row in rows)
        assert all(row["reject_codes"] or row["label"] is not None for row in rows)


def test_future_bars_change_only_label_not_prior_observation_features(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest, facts = freeze(store)
        runner = CandidateLabelResearch(store, manifest, facts, TimeframeProfile.SWING_4H,
                                        label_config=CandidateLabelConfig(3, Decimal(".0004"), Decimal(".0003")))
        base = candles(14_400, 170)
        daily = candles(86_400, 170)
        before = runner._symbol_observations("BTC-USDT", {"4hour": base, "1day": daily})
        last = base[-1]
        future = tuple(Candle(last.open_time+(i+1)*14_400, last.close, last.close+Decimal(".2"),
                              last.close-Decimal(".2"), last.close+Decimal(i+1)/Decimal("10"),
                              Decimal("100"), Decimal("100")) for i in range(3))
        after = runner._symbol_observations("BTC-USDT", {"4hour": base+future, "1day": daily})
        prior = {row.model: row for row in before if row.signal_time == last.open_time}
        extended = {row.model: row for row in after if row.signal_time == last.open_time}
        for model, row in prior.items():
            newer = extended[model]
            assert (newer.observation_id, newer.window_hash, newer.adx, newer.roc,
                    newer.price_slope, newer.ema_slope) == (
                    row.observation_id, row.window_hash, row.adx, row.roc,
                    row.price_slope, row.ema_slope)
            if row.model_status == "CANDIDATE":
                assert row.label.status == "UNKNOWN_INCOMPLETE_HORIZON"
                assert newer.label.status == "KNOWN"


def test_same_bar_target_and_stop_uses_adverse_stop_first_order(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest, facts = freeze(store)
        runner = CandidateLabelResearch(store, manifest, facts, TimeframeProfile.SWING_4H,
                                        label_config=CandidateLabelConfig(1, Decimal("0"), Decimal("0")))
        history = list(candles(14_400, 161))
        entry = history[-1]
        history[-1] = Candle(entry.open_time, Decimal("100"), Decimal("102"), Decimal("98"),
                             Decimal("100"), Decimal("100"), Decimal("100"))
        structure = PriceStructure(Decimal("101"), Decimal("99"), Decimal("102"), Decimal("98"),
                                   Decimal("99"), Decimal("101"))
        candidate = ModelCandidate(ModelCode.DONCHIAN_BREAKOUT, TrendSide.LONG,
                                   history[-2].open_time, Decimal("90"), Decimal("100"),
                                   Decimal("99"), Decimal("101"), Decimal("101"), Decimal("1"),
                                   structure, ("test",))
        label = runner._label("BTC-USDT", tuple(history), 159, candidate)
        assert label.stop_first is True and label.target_first is False
        assert label.exit_reason == "STOP_FIRST"
        assert label.exit_time == history[-1].open_time + 14_400


def test_condition_audit_is_reproducible_and_exposes_redundant_conditions(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest, facts = freeze(store)
        refs = {}
        for profile in (TimeframeProfile.INTRADAY_15M, TimeframeProfile.SWING_4H):
            refs[profile.value] = CandidateLabelResearch(
                store, manifest, facts, profile,
                label_config=CandidateLabelConfig(3, Decimal(".0004"), Decimal(".0003"))).run(
                    symbols=("BTC-USDT",))
        p2_index = store.put("dual_profile_candidate_label_index", {
            "manifest_hash": manifest, "market_facts_manifest_hash": facts,
            "profile_results": refs}, refs=(facts, *refs.values()))
        runner = ConditionEffectivenessAudit(store, p2_index, bootstrap_iterations=100)
        first = runner.run()
        second = runner.run()
        assert first == second
        for result_ref in store.get(first)["payload"]["profile_results"].values():
            payload = store.get(result_ref)["payload"]
            summaries = {row["condition"]["condition_id"]: row for row in payload["summaries"]}
            for condition in ("ADD_REGIME", "SENSITIVITY_SCORE_65", "SENSITIVITY_SCORE_70"):
                assert summaries[condition]["overall"]["rejected"] == 0
                assert summaries[condition]["overall"]["conclusion"] == "REDUNDANT_RECORD_ONLY"
            assert summaries["LEGACY_EMA_SPREAD_PROXY"]["overall"]["conclusion"] == "DEPRECATED_DIAGNOSTIC_ONLY"
            assert payload["observation_chunk_refs"]
