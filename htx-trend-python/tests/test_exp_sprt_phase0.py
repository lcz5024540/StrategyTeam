"""EXP-SPRT-20261102 Phase-0 shadow collection tests.

探索 / Phase-0 only. These tests never place orders and never import live workers.
"""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import json

import httpx
import pytest

from htx_trend.htx_v5 import HtxV5Client
from htx_trend.market_data import Candle

from exp_sprt.arm_a import arm_a_for
from exp_sprt.evaluate import BookSnap, MarketObservation, ShadowSession, TradePrint
from exp_sprt.evidence import DEFAULT_EVIDENCE_ROOT, EvidenceStore
from exp_sprt.market import ReadOnlyHtxShadowFeed, ShadowBoundaryError
from exp_sprt.plan import PLAN_FIELDS, build_plan_document, generate_plan
from exp_sprt.samples import (
    BASE_OPEN,
    breakout_as_of,
    breakout_candles,
    rejection_as_of,
    rejection_candles,
    write_phase0_examples,
)


def _book(bid: str, ask: str, bsz: str = "5", asz: str = "4") -> BookSnap:
    return BookSnap(
        bids=((Decimal(bid), Decimal(bsz)),),
        asks=((Decimal(ask), Decimal(asz)),),
    )


def _obs(ts_ms: int, price: str, bid: str | None = None, ask: str | None = None, *,
         stream_ok: bool = True, origin: str = "fixture_replay") -> MarketObservation:
    book = None if bid is None else _book(bid, ask or bid)
    trade = TradePrint(ts_ms, Decimal(price), Decimal("1"), "sell", f"t{ts_ms}")
    return MarketObservation(ts_ms, (trade,), book, stream_ok, origin)


def _short_rejection_plan() -> dict:
    return build_plan_document(
        symbol="BTC-USDT",
        generated_at="2023-11-14T22:13:20Z",
        bias="SHORT",
        zone_support=(("100", "103"),),
        zone_resistance=(("117", "120"),),
        behavior="REJECTION",
        invalidation_price="121",
        target_price="100",
        target_structure_ref="phase0-swing20:low:explicit",
        band_low="114",
        band_high="120",
        evidence_hash="sha256:" + "ab" * 32,
        signal_close_epoch=1_700_000_000,
        timeframe="15min",
    )


def _short_breakout_plan() -> dict:
    plan = _short_rejection_plan()
    return build_plan_document(
        symbol="BTC-USDT",
        generated_at=plan["generated_at"],
        bias="SHORT",
        zone_support=(("100", "103"),),
        zone_resistance=(("117", "120"),),
        behavior="BREAKOUT",
        invalidation_price="121",
        target_price="90",
        target_structure_ref="phase0-swing20:low:explicit",
        band_low="97",
        band_high="100",
        evidence_hash="sha256:" + "cd" * 32,
        signal_close_epoch=1_700_000_000,
        timeframe="15min",
    )


def _decisions(session: ShadowSession, observations: list[MarketObservation]) -> list[dict]:
    events: list[dict] = []
    for item in observations:
        events.extend(session.apply(item))
    return [item for item in events if item["stage"] == "primary_decision"]


def _r1_tape(start: int, break_price: str, break_bid: str, break_ask: str) -> list[MarketObservation]:
    return [
        _obs(start, "118", "117.9", "118.1"),
        _obs(start + 1_000, "119", "118.9", "119.1"),
        _obs(start + 2_000, break_price, break_bid, break_ask),
    ]


def test_generated_plan_matches_frozen_schema_and_break_level_mapping() -> None:
    candles = rejection_candles()
    plan = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=rejection_as_of())
    assert tuple(plan) == PLAN_FIELDS
    assert "break_level" not in plan
    assert plan["schema_version"] == "sprt-plan-v0.1"
    assert plan["symbol"] == "BTC-USDT"
    assert plan["timeframe_structure"] == "15min"
    assert plan["bias"] == "SHORT"
    assert plan["trade_forbidden_if"] == []
    assert plan["zone_support"] == [{"low": "100", "high": "103"}]
    assert plan["zone_resistance"] == [{"low": "117", "high": "120"}]
    assert plan["invalidation_price"] == "120"
    assert plan["target_price"] == "100"
    assert plan["max_acceptable_fill_band"] == {"low": "114", "high": "120"}
    behavior = plan["entry_behavior"]
    assert behavior["kind"] == "REJECTION"
    assert behavior["primary_predicate"] == "R1"
    assert behavior["sensitivity_predicates"] == ["R2"]
    assert behavior["exploration_predicates"] == ["R3", "R4"]
    assert behavior["status"] == "FROZEN_P3"
    assert behavior["dual_primary_forbidden"] is True
    assert behavior["track"] == "探索"
    assert behavior["phase"] == "phase-0"
    assert "DRAFT" not in json.dumps(behavior)
    assert "未冻" not in json.dumps(behavior)
    mapping = plan["evidence_ptr"]["break_level_mapping_sentence"]
    assert "S := z_lo" in mapping and "zone_support" in mapping
    assert "S := z_hi" in mapping and "zone_resistance" in mapping
    assert plan["evidence_ptr"]["kline_window_sha256"].startswith("sha256:")
    assert plan["evidence_ptr"]["track"] == "探索/Phase-0"
    assert plan["dedupe_cooldown"]["window_seconds"] == 900
    assert plan["dedupe_cooldown"]["effect"] == "CENSOR_NOT_M2_DENOMINATOR"
    assert "price_left_zone_and_reentered" in plan["reactivate_if"]["conditions"]
    assert "cooldown_elapsed" in plan["reactivate_if"]["conditions"]
    again = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=rejection_as_of())
    assert again["plan_id"] == plan["plan_id"]


def test_breakout_plan_uses_b2_and_support_band() -> None:
    plan = generate_plan(breakout_candles(), symbol="BTC-USDT", as_of_epoch=breakout_as_of())
    rejection = generate_plan(rejection_candles(), symbol="BTC-USDT", as_of_epoch=rejection_as_of())
    assert plan["entry_behavior"]["kind"] == "BREAKOUT"
    assert plan["entry_behavior"]["primary_predicate"] == "B2"
    assert plan["entry_behavior"]["sensitivity_predicates"] == ["B1"]
    assert plan["entry_behavior"]["exploration_predicates"] == ["B3"]
    assert plan["max_acceptable_fill_band"] == {"low": "97", "high": "100"}
    assert plan["schema_version"] == rejection["schema_version"] == "sprt-plan-v0.1"
    assert plan["plan_id"] != rejection["plan_id"]


def test_forming_bar_cannot_change_bias_zones_invalidation_or_target() -> None:
    candles = rejection_candles()
    as_of = rejection_as_of()
    clean = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=as_of)
    forming = Candle(
        as_of, Decimal("1"), Decimal("100000"), Decimal("1"), Decimal("100000"),
        Decimal("1"), Decimal("1"), Decimal("1"), 1,
    )
    dirty = generate_plan([*candles, forming], symbol="BTC-USDT", as_of_epoch=as_of)
    assert dirty["plan_id"] == clean["plan_id"]
    assert dirty["bias"] == clean["bias"]
    assert dirty["zone_support"] == clean["zone_support"]
    assert dirty["zone_resistance"] == clean["zone_resistance"]
    assert dirty["invalidation_price"] == clean["invalidation_price"]
    assert dirty["target_price"] == clean["target_price"]
    assert dirty["evidence_ptr"]["kline_window_sha256"] == clean["evidence_ptr"]["kline_window_sha256"]


def test_structure_update_mints_new_plan_id_without_schema_bump() -> None:
    candles = list(rejection_candles())
    first = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=rejection_as_of())
    opened = BASE_OPEN + 21 * 900
    candles.append(Candle(
        opened, Decimal("118"), Decimal("140"), Decimal("130"), Decimal("135"),
        Decimal("1"), Decimal("1"), Decimal("1"), 1,
    ))
    second = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=opened + 900)
    assert second["schema_version"] == "sprt-plan-v0.1"
    assert second["plan_id"] != first["plan_id"]
    assert "break_level" not in second


def test_one_symbol_rolling_windows_stay_on_sprt_plan_v01() -> None:
    candles = list(rejection_candles())
    opened = BASE_OPEN + 21 * 900
    candles.append(Candle(
        opened, Decimal("118"), Decimal("119"), Decimal("116"), Decimal("117"),
        Decimal("1"), Decimal("1"), Decimal("1"), 1,
    ))
    plans = [
        generate_plan(candles[:end], symbol="BTC-USDT", as_of_epoch=BASE_OPEN + end * 900)
        for end in (21, 22)
    ]
    assert {item["symbol"] for item in plans} == {"BTC-USDT"}
    assert {item["schema_version"] for item in plans} == {"sprt-plan-v0.1"}
    assert all(tuple(item) == PLAN_FIELDS for item in plans)


def test_r1_zone_event_records_book_and_trade_hashes() -> None:
    session = ShadowSession(_short_rejection_plan())
    events = []
    for item in _r1_tape(1_700_000_003_000, "116.5", "116.4", "116.6"):
        events.extend(session.apply(item))
    arrival = next(item for item in events if item["stage"] == "zone_enter")
    decision = next(item for item in events if item["stage"] == "primary_decision")
    assert arrival["primary_predicate"] == "R1"
    assert arrival["mid_price"] == "118"
    assert arrival["orderbook_snapshot_hash"].startswith("sha256:")
    assert arrival["orders_placed"] == 0
    assert decision["decision"] == "confirmed"
    assert decision["primary_predicate"] == "R1"
    assert decision["primary_result"] is True
    assert decision["formal_m2_counted"] is False
    assert decision["description_only"] is True
    assert decision["track"] == "探索"
    assert decision["phase"] == "phase-0"
    assert decision["dual_primary"] is False
    assert decision["orderbook_snapshot_hash"].startswith("sha256:")
    assert decision["trade_evidence_hash"].startswith("sha256:")
    assert decision["inside_max_acceptable_fill_band"] is True
    assert decision["shadow_executable_price"] == "116.4"
    assert decision["rr_recompute"]["qualified"] is True
    assert decision["mid_source"] == "book"
    assert decision["false_trigger"] is None
    assert decision["m2_denominator"] == "pending_outcome_not_in_denominator"
    assert [item["id"] for item in decision["sensitivity_notes"]] == ["R2"]
    assert [item["id"] for item in decision["exploration_notes"]] == ["R3", "R4"]
    assert decision["data_origin"] == "fixture_replay"
    assert decision["orders_placed"] == 0


def test_r2_sensitivity_is_not_a_second_primary() -> None:
    session = ShadowSession(_short_rejection_plan())
    tape = [
        _obs(1_700_000_010_000, "118", "117.9", "118.1"),
        _obs(1_700_000_011_000, "121", "120.9", "121.1"),
        _obs(1_700_000_012_000, "116.5", "116.4", "116.6"),
    ]
    decision = _decisions(session, tape)[-1]
    assert decision["primary_predicate"] == "R1"
    assert decision["primary_result"] is False
    assert decision["decision"] == "non_confirmation"
    assert decision["sensitivity_notes"][0]["id"] == "R2"
    assert decision["sensitivity_notes"][0]["result"] is True
    assert decision["dual_primary"] is False
    assert decision["formal_m2_counted"] is False
    assert decision["m2_denominator"] == "excluded_non_confirmation"
    assert "B2" not in json.dumps(decision["sensitivity_notes"])


def test_b2_confirmation_and_b1_does_not_promote() -> None:
    session = ShadowSession(_short_breakout_plan())
    break_only = session.apply(_obs(1_700_000_020_000, "99.5", "99.4", "99.6"))
    assert break_only[-1]["primary_predicate"] == "B2"
    assert break_only[-1]["primary_result"] is False
    assert break_only[-1]["sensitivity_notes"][0]["id"] == "B1"
    assert break_only[-1]["sensitivity_notes"][0]["result"] is True
    assert break_only[-1]["decision"] == "non_confirmation"
    follow = [
        _obs(1_700_000_021_000, "99.8", "99.7", "99.9"),
        _obs(1_700_000_022_000, "99.2", "99.1", "99.3"),
    ]
    decision = _decisions(session, follow)[-1]
    assert decision["decision"] == "confirmed"
    assert decision["primary_predicate"] == "B2"
    assert decision["primary_result"] is True
    assert decision["formal_m2_counted"] is False
    assert decision["shadow_executable_price"] == "99.1"
    assert decision["orderbook_snapshot_hash"].startswith("sha256:")
    assert decision["trade_evidence_hash"].startswith("sha256:")
    assert [item["id"] for item in decision["exploration_notes"]] == ["B3"]
    assert decision["dual_primary"] is False


def test_b2_reentry_above_s_is_non_confirmation() -> None:
    session = ShadowSession(_short_breakout_plan())
    tape = [
        _obs(1_700_000_030_000, "99.5", "99.4", "99.6"),
        _obs(1_700_000_031_000, "99.8", "99.7", "99.9"),
        _obs(1_700_000_032_000, "100.2", "100.1", "100.3"),
    ]
    decision = _decisions(session, tape)[-1]
    assert decision["decision"] == "non_confirmation"
    assert "retest_reentered_S" in decision["reasons"]
    assert decision["false_trigger"] is None
    assert decision["m2_denominator"] == "excluded_non_confirmation"


def test_band_reject_is_non_confirmation_not_false_trigger() -> None:
    session = ShadowSession(_short_rejection_plan())
    decision = _decisions(session, _r1_tape(1_700_000_040_000, "113", "112.9", "113.1"))[-1]
    assert decision["decision"] == "non_confirmation"
    assert "band" in decision["reasons"]
    assert decision["inside_max_acceptable_fill_band"] is False
    assert decision["false_trigger"] is None
    assert decision["m2_denominator"] == "excluded_non_confirmation"
    assert decision["formal_m2_counted"] is False


def test_rr_reject_is_non_confirmation() -> None:
    plan = build_plan_document(
        symbol="BTC-USDT",
        generated_at="2023-11-14T22:13:20Z",
        bias="SHORT",
        zone_support=(("100", "103"),),
        zone_resistance=(("117", "120"),),
        behavior="REJECTION",
        invalidation_price="121",
        target_price="130",
        target_structure_ref="phase0-swing20:low:explicit",
        band_low="114",
        band_high="120",
        evidence_hash="sha256:" + "ef" * 32,
        signal_close_epoch=1_700_000_000,
        timeframe="15min",
    )
    decision = _decisions(ShadowSession(plan), _r1_tape(1_700_000_050_000, "116.5", "116.4", "116.6"))[-1]
    assert decision["primary_result"] is True
    assert decision["decision"] == "non_confirmation"
    assert "rr_recompute_unqualified" in decision["reasons"]
    assert decision["rr_recompute"]["qualified"] is False
    assert decision["false_trigger"] is None
    assert decision["m2_denominator"] == "excluded_non_confirmation"


def test_missing_book_keeps_r1_on_trade_mid_and_censors_r3_only() -> None:
    session = ShadowSession(_short_rejection_plan())
    tape = [
        _obs(1_700_000_060_000, "118"),
        _obs(1_700_000_061_000, "119"),
        _obs(1_700_000_062_000, "116.5"),
    ]
    decision = _decisions(session, tape)[-1]
    assert decision["decision"] == "confirmed"
    assert decision["mid_source"] == "trade"
    assert decision["orderbook_snapshot_hash"] is None
    assert decision["trade_evidence_hash"].startswith("sha256:")
    assert decision["shadow_executable_price"] == "116.5"
    r3 = next(item for item in decision["exploration_notes"] if item["id"] == "R3")
    assert r3["result"] == "not_applicable_book_missing"
    assert decision["m2_denominator"] == "pending_outcome_not_in_denominator"
    assert session.counts["censored_stream_gap"] == 0


def test_stream_gap_is_censored_and_does_not_invent_a_tick() -> None:
    session = ShadowSession(_short_rejection_plan())
    event = session.apply(MarketObservation(
        1_700_000_070_000, (), None, stream_ok=False, data_origin="htx_public_readonly",
    ))[0]
    assert event["decision"] == "censored"
    assert "stream_gap" in event["reasons"]
    assert event["mid_price"] is None
    assert event["trade_evidence_hash"] is None
    assert event["orderbook_snapshot_hash"] is None
    assert event["m2_denominator"] == "excluded_censored"
    assert event["false_trigger"] is None
    assert session.counts["censored_stream_gap"] == 1
    assert event["orders_placed"] == 0


def test_dedupe_cooldown_censors_would_trigger_outside_m2() -> None:
    session = ShadowSession(_short_rejection_plan())
    first = _decisions(session, _r1_tape(1_700_000_080_000, "116.5", "116.4", "116.6"))[-1]
    assert first["decision"] == "confirmed"
    second = _decisions(session, _r1_tape(1_700_000_083_000, "116.5", "116.4", "116.6"))[-1]
    assert second["decision"] == "censored"
    assert "dedupe_cooldown" in second["reasons"]
    assert second["dedupe"]["would_trigger_without_dedupe"] is True
    assert second["dedupe"]["status"] == "cooldown_suppress"
    assert second["dedupe"]["effect"] == "LOG_ONLY_NO_ORDERS"
    assert second["m2_denominator"] == "excluded_censored"
    assert second["false_trigger"] is None
    assert second["formal_m2_counted"] is False
    assert session.counts["censored_dedupe"] == 1
    assert session.counts["confirmed_description_only"] == 1


def test_leaving_band_and_reentering_reactivates() -> None:
    session = ShadowSession(_short_rejection_plan())
    _decisions(session, _r1_tape(1_700_000_090_000, "116.5", "116.4", "116.6"))
    resolved = session.apply(_obs(1_700_000_091_000, "100", "99.9", "100.1"))
    assert resolved[-1]["event_type"] == "outcome_watch"
    assert resolved[-1]["false_trigger"] is False
    assert resolved[-1]["outcome"] == "target_first"
    assert resolved[-1]["formal_m2_counted"] is False
    assert resolved[-1]["m2_denominator"] == "eligible_description_only"
    decision = _decisions(session, _r1_tape(1_700_000_092_000, "116.5", "116.4", "116.6"))[-1]
    assert decision["decision"] == "confirmed"
    assert decision["dedupe"]["status"] == "reactivated"
    assert decision["dedupe"]["reactivate_reason"] == "left_zone_and_reentered"


def test_invalidation_before_target_is_descriptive_false_trigger() -> None:
    session = ShadowSession(_short_rejection_plan())
    _decisions(session, _r1_tape(1_700_000_100_000, "116.5", "116.4", "116.6"))
    outcome = session.apply(_obs(1_700_000_101_000, "121", "120.9", "121.1"))[-1]
    assert outcome["event_type"] == "outcome_watch"
    assert outcome["false_trigger"] is True
    assert outcome["outcome"] == "invalidation_first"
    assert outcome["primary_predicate"] == "R1"
    assert outcome["formal_m2_counted"] is False
    assert outcome["formal_window"] is False
    assert outcome["description_only"] is True
    assert outcome["m2_denominator"] == "eligible_description_only"
    assert outcome["plan_id"] == _short_rejection_plan()["plan_id"]
    assert "升窗" not in json.dumps(outcome)
    assert "过闸" not in json.dumps(outcome)


def test_arm_a_links_the_same_plan_at_15m_close() -> None:
    candles = rejection_candles()
    as_of = rejection_as_of()
    plan = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=as_of)
    arm = arm_a_for(plan, candles, as_of_epoch=as_of)
    assert arm["plan_id"] == plan["plan_id"]
    assert arm["symbol"] == "BTC-USDT"
    assert arm["timeframe"] == "15min"
    assert arm["trigger_price"] == "118"
    assert arm["bar_open_time"] == BASE_OPEN + 20 * 900
    assert arm["bar_close_time"] == as_of
    assert arm["orders_placed"] == 0
    assert arm["research_ledger_only"] is True
    assert arm["track"] == "探索"
    assert arm["phase"] == "phase-0"
    assert arm["plan_generated_at"] == plan["generated_at"]


def test_evidence_store_layout_and_default_root(tmp_path: Path) -> None:
    assert DEFAULT_EVIDENCE_ROOT == Path("/workspace/evidence/exp-sprt-phase0-20261102")
    plan = generate_plan(rejection_candles(), symbol="BTC-USDT", as_of_epoch=rejection_as_of())
    arm = arm_a_for(plan, rejection_candles(), as_of_epoch=rejection_as_of())
    event = _decisions(ShadowSession(plan), _r1_tape(1_700_000_110_000, "116.5", "116.4", "116.6"))[-1]
    store = EvidenceStore(tmp_path)
    plan_path = store.write_plan(plan)
    arm_path = store.write_arm_a(arm)
    event_path = store.write_zone_event(event)
    assert plan_path.parent == tmp_path / "plans"
    assert arm_path.parent == tmp_path / "arm_a"
    assert event_path.parent == tmp_path / "zone_events"
    assert json.loads(plan_path.read_text(encoding="utf-8"))["schema_version"] == "sprt-plan-v0.1"
    assert json.loads(arm_path.read_text(encoding="utf-8"))["plan_id"] == plan["plan_id"]
    assert json.loads(event_path.read_text(encoding="utf-8"))["primary_predicate"] == "R1"


def test_sample_writer_emits_labeled_r1_and_b2_examples(tmp_path: Path) -> None:
    manifest = write_phase0_examples(tmp_path)
    plans = [json.loads(path.read_text(encoding="utf-8")) for path in (tmp_path / "plans").glob("*.json")]
    events = [json.loads(path.read_text(encoding="utf-8")) for path in (tmp_path / "zone_events").glob("*.json")]
    arms = [json.loads(path.read_text(encoding="utf-8")) for path in (tmp_path / "arm_a").glob("*.json")]
    assert {item["entry_behavior"]["primary_predicate"] for item in plans} >= {"R1", "B2"}
    assert any(item["primary_predicate"] == "R1" and item["decision"] == "confirmed" for item in events)
    assert any(item["primary_predicate"] == "B2" and item["decision"] == "confirmed" for item in events)
    assert any(item.get("orderbook_snapshot_hash") and item.get("trade_evidence_hash") for item in events)
    assert all(item["track"] == "探索" and item["phase"] == "phase-0" for item in events + arms)
    assert all(item["formal_m2_counted"] is False and item["orders_placed"] == 0 for item in events)
    assert all(item["data_origin"] == "fixture_replay" for item in events)
    linked = {item["plan_id"] for item in arms}
    assert linked == {item["plan_id"] for item in plans}
    assert manifest["track"] == "探索"
    assert manifest["formal_window"] is False
    assert manifest["n0"] == {"L1": 400, "L2": 200, "status": "design_target_description_only"}
    raw = json.dumps({"plans": plans, "events": events, "manifest": manifest}, ensure_ascii=False)
    assert "升窗" not in raw
    assert "过闸" not in raw


def test_package_source_does_not_touch_live_execution() -> None:
    root = Path(__file__).parents[1] / "src" / "exp_sprt"
    text = "\n".join(path.read_text(encoding="utf-8") for path in root.glob("*.py"))
    for banned in (
        "htx_trend.live_worker",
        "htx_trend.paper_worker",
        "htx_trend.execution",
        "htx_trend.worker",
        "htx_trend.runner",
        "place_order",
        "cancel_order",
        "set_leverage",
    ):
        assert banned not in text


def test_readonly_feed_polls_public_market_and_refuses_live_client() -> None:
    boundary = 1_800_000_000
    period = 900

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        assert "order" not in path
        assert "lever" not in path
        assert "position" not in path
        if path.endswith("/kline"):
            rows = [
                {"id": boundary - index * period, "open": "110", "high": "111", "low": "109", "close": "110"}
                for index in range(23)
            ]
            return httpx.Response(200, json={"status": "ok", "data": rows})
        if path.endswith("/depth"):
            return httpx.Response(200, json={"tick": {"bids": [["100", "2"]], "asks": [["101", "3"]]}})
        if path.endswith("/trade"):
            return httpx.Response(200, json={"tick": {"data": [
                {"id": 9, "ts": 99, "price": "100.5", "amount": "1", "direction": "buy"},
            ]}})
        raise AssertionError(path)

    client = HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler)))
    feed = ReadOnlyHtxShadowFeed.from_client(client)
    candles = feed.completed_candles("BTC-USDT", now_epoch=boundary + 3)
    assert len(candles) == 21
    observation = feed.latest_observation("BTC-USDT")
    assert observation.data_origin == "htx_public_readonly"
    assert observation.trades[0].price == Decimal("100.5")
    assert observation.book is not None
    assert observation.book.bids[0][0] == Decimal("100")

    live = HtxV5Client(access_key="not-a-real-key", secret_key="not-a-real-secret", live_trading_enabled=True,
                       client=httpx.Client(transport=httpx.MockTransport(handler)))
    with pytest.raises(ShadowBoundaryError):
        ReadOnlyHtxShadowFeed.from_client(live)


def test_readonly_feed_does_not_synthesize_trades() -> None:
    boundary = 1_800_000_000
    period = 900

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/kline"):
            rows = [
                {"id": boundary - index * period, "open": "110", "high": "111", "low": "109", "close": "110"}
                for index in range(23)
            ]
            return httpx.Response(200, json={"status": "ok", "data": rows})
        if request.url.path.endswith("/depth"):
            return httpx.Response(200, json={"tick": {"bids": [["100", "2"]], "asks": [["101", "3"]]}})
        if request.url.path.endswith("/trade"):
            return httpx.Response(200, json={"tick": {"data": []}})
        raise AssertionError(request.url.path)

    client = HtxV5Client(client=httpx.Client(transport=httpx.MockTransport(handler)))
    observation = ReadOnlyHtxShadowFeed.from_client(client).latest_observation("BTC-USDT")
    assert observation.trades == ()
    assert observation.book is not None
    assert observation.data_origin == "htx_public_readonly"
