from dataclasses import replace
from decimal import Decimal as D

import pytest
from htx_trend.models import TrendSide
from htx_trend.realtime.feed import MarketEvent
from htx_trend.realtime.plans import EVENT_LOCAL, Parameters, generate
from htx_trend.realtime.storage import PlanStore
from htx_trend.realtime.structure_facts import (
    FactObserver,
    StructureFact,
    assess_target,
    facts_for_frame,
)
from test_structure_events import inputs

P = Parameters(candidate=EVENT_LOCAL)


@pytest.fixture
def observer(tmp_path):
    entry, hour, spec, at = inputs()
    store = PlanStore(tmp_path / "facts.sqlite")
    engine = FactObserver(store, "x", P)
    facts = engine.refresh(
        entry, hour, spec, now_ms=at, sequence=0, signal_side=TrendSide.FLAT
    )
    yield engine, facts, at
    store.close()


def send(engine, at, seq, price=None, bid=None, epoch="e"):
    event = MarketEvent(
        "TRADE" if price is not None else "BBO",
        "ETH-USDT",
        at + seq,
        at + seq,
        str(seq),
        epoch,
        price=D(str(price)) if price is not None else None,
        bid=D(str(bid)) if bid is not None else None,
        ask=D(str(bid)) + D(".01") if bid is not None else None,
    )
    return engine.accept(event, now_ms=at + seq, sequence=seq)


def test_no_target_is_a_real_fact_and_box_formula_matches_original(observer):
    engine, facts, at = observer
    fact = next(f for f in facts if f.line == 80)
    frame = engine.frames["ETH-USDT"]
    missing = assess_target(fact, frame, group="J1", now_ms=at, parameters=P)
    assert missing["price"] is None and missing["status"] == "NO_TARGET"
    with pytest.raises(ValueError, match="not a trade plan"):
        fact.plan(missing)
    measured = assess_target(fact, frame, group="J1_BOX32", now_ms=at, parameters=P)
    entry = frame[0]
    entry = replace(
        entry,
        candles=entry.candles[:-1]
        + (replace(entry.candles[-1], low=D(80), close=D(81)),),
    )
    original = generate(entry, *frame[1:3], now_ms=at)
    assert (
        measured["price"]
        == next(p.target for p in original if p.model == "RT_BREAKOUT_RETEST")
        == 40
    )
    assert measured["box_low"] == 80 and measured["box_high"] == 120


def test_no_target_observes_full_pattern_but_never_becomes_valid_later(observer):
    engine, facts, at = observer
    fact = next(f for f in facts if f.line == 80)
    price = fact.line - fact.band - 1
    send(engine, at, 1, bid=price - D(".01"))
    send(engine, at, 2, price=price)
    send(engine, at, 3, price=price)
    row = engine.rows[fact.fact_id]
    assert row["phase"] == "BROKEN"
    assert row["targets"]["J1"]["price"] is None
    send(engine, at, 4, price=fact.line)
    send(engine, at, 5, price=price)
    observations = send(engine, at, 6, price=price)
    observed = next(o for o in observations if o["fact"]["fact_id"] == fact.fact_id)
    assert observed["targets"]["J1"]["status"] == "NO_TARGET"
    assert observed["targets"]["J1_BOX32"]["status"] == "AVAILABLE"
    assert observed["order_submitted"] is False
    assert row["phase"] == "OBSERVED"
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    engine.refresh(entry, hour, spec, now_ms=at + 7, sequence=7, signal_side=signal)
    assert row["targets"]["J1"]["price"] is None
    assert not send(engine, at, 8, price=price)


def test_frozen_target_and_identity_survive_new_window(observer):
    engine, facts, at = observer
    f = next(f for f in facts if f.line == 80)
    price = f.line - f.band - 1
    send(engine, at, 1, price=price)
    send(engine, at, 2, price=price)
    row = engine.rows[f.fact_id]
    before = dict(row["targets"]["J1_BOX32"])
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    # Same real source anchor, revised unrelated box high; frozen target must not drift.
    changed = list(entry.candles)
    changed[129] = replace(changed[129], high=D(125))
    engine.refresh(
        replace(entry, candles=tuple(changed)),
        hour,
        spec,
        now_ms=at + 3,
        sequence=3,
        signal_side=signal,
    )
    assert row["targets"]["J1_BOX32"] == before
    assert row["phase"] == "BROKEN"


def test_target_crossing_stops_only_target_eligibility_not_fact_audit(observer):
    engine, facts, at = observer
    f = next(f for f in facts if f.line == 110)
    send(engine, at, 1, price=107)
    send(engine, at, 2, price=110)
    row = engine.rows[f.fact_id]
    assert row["phase"] == "ARMED"
    send(engine, at, 3, price=70)
    assert row["targets"]["J1"]["status"] == "TARGET_ALREADY_CROSSED"
    send(engine, at, 4, price=110)
    send(engine, at, 5, bid=107)
    send(engine, at, 6, price=107)
    got = send(engine, at, 7, price=107)
    assert (
        next(o for o in got if o["fact"]["fact_id"] == f.fact_id)["targets"]["J1"][
            "status"
        ]
        == "TARGET_ALREADY_CROSSED"
    )


def test_restart_and_disconnect_do_not_resurrect_unfinished_facts(observer):
    engine, facts, at = observer
    f = next(f for f in facts if f.line == 80)
    send(engine, at, 1, price=77)
    send(engine, at, 2, price=77)
    restored = FactObserver(engine.store, "x", P)
    assert restored.rows[f.fact_id]["phase"] == "INVALIDATED"
    assert restored.rows[f.fact_id]["reason"] == "RESTART"
    engine.disconnect()
    assert not send(engine, at, 3, price=77, epoch="e")
    assert engine.rows[f.fact_id]["phase"] == "INVALIDATED"


def test_local_real_anchor_and_confirming_event_are_not_faked(observer):
    engine, _, at = observer
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    line = {
        "edge": "LOW",
        "price": D(99),
        "anchor_ms": at - 1111,
        "confirmed_ms": at,
        "confirmed_seq": 10,
        "epoch": "e",
        "identity": "real-extreme",
        "refs": ("extreme", "confirmation"),
        "band": D(1),
    }
    local = FactObserver(engine.store, "local", P, "T")
    facts = local.refresh(
        entry,
        hour,
        spec,
        now_ms=at,
        sequence=10,
        signal_side=signal,
        local_lines=[line],
    )
    f = facts[0]
    assert f.anchor_ms == at - 1111 and f.anchor_bar % 900 != 0
    assert not send(local, at, 10, price=97)
    assert local.rows[f.fact_id]["count"] == 0
    send(local, at, 11, price=97)
    assert local.rows[f.fact_id]["count"] == 1
    # Duplicate message cannot supply the second break confirmation.
    send(local, at, 11, price=97)
    assert local.rows[f.fact_id]["count"] == 1
    send(local, at, 12, price=97)
    assert local.rows[f.fact_id]["phase"] == "BROKEN"
    local.refresh(
        entry,
        hour,
        spec,
        now_ms=at + 13,
        sequence=13,
        signal_side=signal,
        local_lines=[line],
    )
    assert local.rows[f.fact_id]["phase"] == "BROKEN"


def test_box_target_is_not_given_to_internal_or_rejection_lines(observer):
    engine, facts, at = observer
    frame = engine.frames["ETH-USDT"]
    outer = next(f for f in facts if f.line == 80)
    # Price lies just above outer low but the band reaches beyond it: no profitable boundary.
    internal = replace(outer, line=D("80.1"))
    assert (
        assess_target(internal, frame, group="J1_BOX32", now_ms=at, parameters=P)[
            "status"
        ]
        == "NO_TARGET"
    )
    rejection = replace(outer, model="RT_RANGE_REJECTION")
    assert (
        assess_target(rejection, frame, group="J1_BOX32", now_ms=at, parameters=P)[
            "status"
        ]
        == "NO_TARGET"
    )


def test_box_formula_long_is_symmetric(observer):
    engine, _, at = observer
    entry, hour, spec, _ = engine.frames["ETH-USDT"]
    candles = tuple(
        replace(
            c,
            open=500 - c.open,
            high=500 - c.low,
            low=500 - c.high,
            close=500 - c.close,
        )
        for c in hour.candles
    )
    hour = replace(hour, candles=candles)
    facts, _ = facts_for_frame(
        entry,
        hour,
        spec,
        now_ms=at,
        sequence=0,
        parameters=P,
        signal_side=TrendSide.FLAT,
    )
    f = next(f for f in facts if f.line == 120)
    target = assess_target(
        f,
        (entry, hour, spec, TrendSide.FLAT),
        group="J1_BOX32",
        now_ms=at,
        parameters=P,
    )
    assert target["price"] == 160
    assert StructureFact.load(primitive_fact(f)) == f


def primitive_fact(f):
    from htx_trend.research.store import primitive

    return primitive(f)


def test_restore_incompatible_parameters_fails_without_mutation(observer):
    engine, _, _ = observer
    before = engine.store.all("x")
    with pytest.raises(ValueError, match="incompatible"):
        FactObserver(engine.store, "x", replace(P, band_atr=D(".05")))
    with pytest.raises(ValueError, match="incompatible"):
        FactObserver(engine.store, "x", P, "T")
    assert engine.store.all("x") == before


@pytest.mark.parametrize("origin", ["M", "T"])
def test_local_break_retest_is_independent_and_requires_new_stages(observer, origin):
    engine, _, at = observer
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    line = {
        "edge": "LOW",
        "price": D(99),
        "anchor_ms": at - 1234,
        "confirmed_ms": at,
        "confirmed_seq": 10,
        "epoch": "e",
        "identity": "local",
        "refs": ("local",),
        "band": D(1),
    }
    local = FactObserver(engine.store, origin, P, origin)
    facts = local.refresh(
        entry,
        hour,
        spec,
        now_ms=at,
        sequence=10,
        signal_side=signal,
        local_lines=[line],
    )
    assert len(facts) == 1 and facts[0].model == "RT_BREAKOUT_RETEST"
    f = facts[0]
    price = f.line - f.band - 1
    send(local, at, 10, price=price)
    send(local, at, 11, bid=price - D(".01"))
    send(local, at, 12, price=price)
    assert not send(local, at, 13, price=price)
    assert local.rows[f.fact_id]["phase"] == "BROKEN"
    assert not send(local, at, 14, price=price)
    assert not send(local, at, 15, price=f.line)
    assert local.rows[f.fact_id]["phase"] == "RETEST"
    assert not send(local, at, 16, price=price)
    got = send(local, at, 17, price=price)
    assert len(got) == 1 and got[0]["fact"]["origin"] == origin
    assert len(got[0]["trade_refs"]) == 2
    assert got[0]["targets"]["J1"]["status"] == "AVAILABLE"
    assert got[0]["order_submitted"] is False


def test_withdrawn_local_line_cannot_generate_fact_or_supply_target(observer):
    engine, facts, at = observer
    frame = engine.frames["ETH-USDT"]
    entry, hour, spec, signal = frame
    line = {
        "edge": "LOW",
        "price": D(95),
        "anchor_ms": at - 1234,
        "confirmed_ms": at,
        "confirmed_seq": 10,
        "epoch": "e",
        "identity": "bad-minute",
        "refs": ("bad",),
        "band": D(1),
        "withdrawn": True,
    }
    local, reason = facts_for_frame(
        entry,
        hour,
        spec,
        now_ms=at,
        sequence=10,
        parameters=P,
        signal_side=signal,
        origin="M",
        local_lines=[line],
    )
    assert not local and reason is None
    f = next(f for f in facts if f.line == 110)
    target = assess_target(
        f, frame, group="J2", now_ms=at, parameters=P, local_lines=[line]
    )
    assert target == assess_target(f, frame, group="J2", now_ms=at, parameters=P)
    assert "bad" not in target["refs"]


def test_bad_trade_before_minute_creation_marks_future_bucket_partial():
    from htx_trend.realtime.target_continuation_replay import Study
    from htx_trend.research.store import primitive

    s = Study()
    e = MarketEvent("TRADE", "ETH-USDT", 120000, 120100, "1", "e", price=D(100))
    s.reject_minute(e)
    # Seed the epoch early enough so this minute would otherwise be complete.
    s.minutes.gap_at = 60000
    s.observe(primitive(e), {"segment": "fixture", "line": 1})
    assert s.minutes.pending[("ETH-USDT", 120000)]["partial"] is True


def test_box_nonpositive_measurement_is_rejected(observer):
    engine, facts, at = observer
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    changed = list(entry.candles)
    changed[129] = replace(changed[129], high=D(200))
    fact = next(f for f in facts if f.line == 80)
    target = assess_target(
        fact,
        (replace(entry, candles=tuple(changed)), hour, spec, signal),
        group="J1_BOX32",
        now_ms=at,
        parameters=P,
    )
    assert target["status"] == "INVALID_TARGET" and target["price"] is None


def test_first_break_confirmation_crossed_target_remains_ineligible(observer):
    engine, facts, at = observer
    f = next(f for f in facts if f.line == 80)
    send(engine, at, 1, price=30)
    send(engine, at, 2, price=77)
    row = engine.rows[f.fact_id]
    assert row["phase"] == "BROKEN"
    assert row["targets"]["J1_BOX32"]["status"] == "TARGET_ALREADY_CROSSED"
    send(engine, at, 3, price=80)
    send(engine, at, 4, bid=77)
    send(engine, at, 5, price=77)
    got = send(engine, at, 6, price=77)
    assert (
        next(o for o in got if o["fact"]["fact_id"] == f.fact_id)["targets"][
            "J1_BOX32"
        ]["status"]
        == "TARGET_ALREADY_CROSSED"
    )


def test_interrupted_break_confirmation_does_not_keep_old_extreme(observer):
    engine, facts, at = observer
    f = next(f for f in facts if f.line == 80)
    send(engine, at, 1, price=30)
    send(engine, at, 2, price=80)
    send(engine, at, 3, price=77)
    send(engine, at, 4, price=77)
    assert engine.rows[f.fact_id]["targets"]["J1_BOX32"]["status"] == "AVAILABLE"


def test_result_snapshot_is_idempotent_and_censors_tail_quote_gap():
    from htx_trend.realtime.target_continuation_replay import Study

    s = Study()
    book = MarketEvent("BBO", "ETH-USDT", 1000, 1000, "1", "e", bid=D(99), ask=D(100))
    trigger = {
        "plan": {"side": "LONG"},
        "reference": "100",
        "invalidation_price": "90",
        "trigger_ms": 1000,
        "exchange_ms": 1000,
    }
    s.paths.add("opportunity", trigger, D(110), book)
    s.now = 10000
    a, b = s.result(), s.result()
    assert a == b and len(a["quote_paths"]) == 1
    assert a["quote_paths"][0]["outcome"] == "CENSORED_QUOTE_GAP"
    assert a["quote_paths"][0]["observed_ms"] == 3000
    assert s.paths.pending["ETH-USDT"]


def test_local_spec_change_invalidates_active_event(observer):
    engine, _, at = observer
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    line = {
        "edge": "HIGH",
        "price": D(110),
        "anchor_ms": at - 1234,
        "confirmed_ms": at,
        "confirmed_seq": 0,
        "epoch": "e",
        "identity": "local-high",
        "refs": ("local",),
        "band": D(1),
    }
    local = FactObserver(engine.store, "tick-check", P, "T")
    f = local.refresh(
        entry, hour, spec, now_ms=at, sequence=0, signal_side=signal, local_lines=[line]
    )[0]
    send(local, at, 1, price=107)
    send(local, at, 2, price=110)
    assert local.rows[f.fact_id]["phase"] == "ARMED"
    local.refresh(
        entry,
        hour,
        replace(spec, price_tick=spec.price_tick * 2),
        now_ms=at + 3,
        sequence=3,
        signal_side=signal,
        local_lines=[line],
    )
    assert local.rows[f.fact_id]["reason"] == "SPEC_OR_PARAMETERS_CHANGED"


def test_late_minute_revision_preserves_observation_but_removes_eligibility(observer):
    from htx_trend.realtime.target_continuation_replay import Study
    from htx_trend.research.store import primitive

    engine, _, at = observer
    entry, hour, spec, signal = engine.frames["ETH-USDT"]
    study = Study()
    local = study.engine("M", D(".10"), "ETH-USDT")
    minute = (at - 1234) // 60000 * 60000
    line = {
        "edge": "LOW",
        "price": D(99),
        "anchor_ms": at - 1234,
        "confirmed_ms": at,
        "confirmed_seq": 0,
        "epoch": "e",
        "identity": "minute-line",
        "refs": ("local",),
        "band": D(1),
        "minutes": (minute,),
    }
    study.lines[("M", "0.10", "ETH-USDT")].append(line)
    f = local.refresh(
        entry, hour, spec, now_ms=at, sequence=0, signal_side=signal, local_lines=[line]
    )[0]
    send(local, at, 1, bid=97)
    send(local, at, 2, price=97)
    send(local, at, 3, price=97)
    send(local, at, 4, price=99)
    send(local, at, 5, price=97)
    observation = send(local, at, 6, price=97)[0]
    study.observations.append({"origin": "M", "k": "0.10", **observation})
    study.now = at + 10000
    late = MarketEvent(
        "TRADE", "ETH-USDT", at - 1234, study.now, "late", "e", price=D(100)
    )
    study.reject_minute(late, {"segment": "test", "line": 9}, "STALE_TRADE")
    assert local.rows[f.fact_id]["phase"] == "OBSERVED"
    assert study.coverage_revisions[0]["fact_ids"] == [f.fact_id]
    refs = (
        set(f.evidence_refs)
        | set(observation["trade_refs"])
        | {observation["book_ref"]}
    )
    for t in observation["targets"].values():
        refs.update(t["refs"])
    refs.add(local.rows[f.fact_id]["first_stage_ref"])
    refs.update(t["ref"] for t in local.transitions)
    for r in refs:
        study.sources[r] = {"segment": "test", "ref": r}
    result = study.result()
    assert result["decisions"][0]["coverage"] == "WITHDRAWN_AFTER_OBSERVATION"
    summary = next(
        s
        for s in result["summaries"]
        if (s["origin"], s["k"], s["target_group"], s["slippage_atr"])
        == ("M", "0.10", "J1", "0.10")
    )
    assert summary["observed_patterns"] == 1 and summary["coverage_withdrawn"] == 1
    assert summary["valid_geometry"] == 0
    assert primitive(late)["event_id"] == "late"


def test_bad_local_target_does_not_withdraw_unrelated_target_group(observer):
    from htx_trend.realtime.target_continuation_replay import Study

    engine, facts, at = observer
    f = next(f for f in facts if f.line == 110)
    send(engine, at, 1, price=107)
    send(engine, at, 2, price=110)
    row = engine.rows[f.fact_id]
    row["targets"]["J2"]["refs"] = ["bad-target"]
    study = Study()
    study.engines[("M", "0.10", "ETH-USDT")] = engine
    minute = at // 60000 * 60000
    study.lines[("M", "0.10", "ETH-USDT")].append(
        {"identity": "other-line", "minutes": (minute,), "refs": ("bad-target",)}
    )
    study.reject_minute(
        MarketEvent("TRADE", "ETH-USDT", at, at + 9999, "late", "e", price=D(100))
    )
    assert row["phase"] == "ARMED" and "coverage" not in row
    assert row["target_coverage"] == {"J2": "WITHDRAWN_AFTER_OBSERVATION"}
