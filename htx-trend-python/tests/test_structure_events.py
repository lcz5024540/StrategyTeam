from dataclasses import replace
from decimal import Decimal as D

import pytest
from htx_trend.market_data import Candle, ContractSpec, KlineWindow
from htx_trend.models import TrendSide
from htx_trend.realtime import VERSION
from htx_trend.realtime.plans import (
    EVENT_LOCAL,
    EVENT_WINDOWS,
    ORIGINAL,
    Parameters,
    create_engine,
    generate,
)
from htx_trend.realtime.storage import PlanStore
from htx_trend.realtime.structures import levels
from htx_trend.research.store import digest
from test_realtime_plans import book, plan, push, trade

PARAMS = Parameters(candidate=EVENT_LOCAL)


def new_plan(**kwargs):
    return replace(
        plan(**kwargs),
        parameter_fingerprint=PARAMS.fingerprint,
        sources=("20:HIGH", "RULE:" + EVENT_LOCAL),
    )


@pytest.fixture
def engine(tmp_path):
    store = PlanStore(tmp_path / "events.sqlite")
    value = create_engine(store, "events", parameters=PARAMS)
    yield value
    store.close()


def inputs():
    end = 720000
    entry = [
        Candle(end - (160 - i) * 900, D(100), D(104), D(96), D(100)) for i in range(160)
    ]
    entry[130] = replace(entry[130], high=D(120), low=D(80))
    entry[146] = replace(entry[146], low=D(94))
    entry[151] = replace(entry[151], low=D(90))
    entry[154] = replace(entry[154], high=D(110))
    hour = tuple(
        Candle(end - (160 - i) * 3600, D(300 - i), D(301 - i), D(299 - i), D(300 - i))
        for i in range(160)
    )
    return (
        KlineWindow("ETH-USDT", "15min", tuple(entry), 160, ("entry",)),
        KlineWindow("ETH-USDT", "60min", hour, 160, ("hour",)),
        ContractSpec("ETH-USDT", D(".01"), D(".01"), 1),
        end * 1000 + 100,
    )


def test_default_parameters_keep_deployed_fingerprint():
    assert Parameters().fingerprint == digest(
        {
            "version": VERSION,
            "parameters": {
                "band_atr": D(".10"),
                "min_net_r": D("1.5"),
                "max_age_ms": 2000,
            },
        }
    )
    assert (
        len(
            {
                Parameters(candidate=c).fingerprint
                for c in (ORIGINAL, EVENT_WINDOWS, EVENT_LOCAL)
            }
        )
        == 3
    )


def test_all_local_candidates_have_own_target_and_stable_anchor_identity():
    entry, hour, spec, at = inputs()
    base = generate(entry, hour, spec, now_ms=at)
    candidates = generate(entry, hour, spec, now_ms=at, parameters=PARAMS)
    assert len(candidates) > len(base)
    local = next(p for p in candidates if p.line == 110)
    assert local.target == 90 and "PIVOT_2_2:HIGH" in local.sources
    j2 = generate(
        entry, hour, spec, now_ms=at, parameters=replace(PARAMS, target_mode="J2")
    )
    assert next(p for p in j2 if p.line == 110).target == 94
    updated = replace(
        entry,
        candles=entry.candles[:154]
        + (replace(entry.candles[154], high=D(111)),)
        + entry.candles[155:],
    )
    revised = generate(updated, hour, spec, now_ms=at + 1, parameters=PARAMS)
    assert next(p for p in revised if p.line == 111).plan_id == local.plan_id


def test_pivot_requires_right_bars_and_existing_basis_not_future_extrema():
    entry, _, spec, _ = inputs()
    before = replace(entry, candles=entry.candles[:156])
    assert not any(
        l["price"] == 110 and "PIVOT_2_2:HIGH" in l["sources"]
        for l in levels(before, spec.price_tick, D(".1"))
    )
    assert any(
        l["price"] == 110 and "PIVOT_2_2:HIGH" in l["sources"]
        for l in levels(entry, spec.price_tick, D(".1"))
    )


def test_event_control_preserves_original_generated_geometry():
    entry, hour, spec, at = inputs()
    a = generate(entry, hour, spec, now_ms=at)
    b = generate(
        entry, hour, spec, now_ms=at, parameters=Parameters(candidate=EVENT_WINDOWS)
    )
    assert [(p.line, p.target, p.band, p.atr, p.model) for p in a] == [
        (p.line, p.target, p.band, p.atr, p.model) for p in b
    ]


@pytest.mark.parametrize(
    "side,prices,quote",
    [
        (TrendSide.SHORT, [98, 100, 98.5, 98.4], (98.39, 98.4)),
        (TrendSide.LONG, [102, 100, 101.5, 101.6], (101.6, 101.61)),
    ],
)
def test_symmetric_rejection_and_frozen_target(engine, side, prices, quote):
    p = new_plan(side=side)
    engine.refresh("ETH-USDT", [p])
    push(engine, book(1, *map(str, quote)))
    for i, price in enumerate(prices[:2], 2):
        assert not push(engine, trade(i, str(price)))
    engine.refresh(
        "ETH-USDT", [replace(p, target=D(85) if side is TrendSide.SHORT else D(115))]
    )
    assert engine.rows[p.plan_id]["plan"]["target"] == str(p.target)
    assert not push(engine, trade(4, str(prices[2])))
    result = push(engine, trade(5, str(prices[3])))
    assert len(result) == 1
    assert abs(result[0].invalidation_price - result[0].reference) >= D("1.25") * p.atr


def test_broken_event_survives_rolling_replacement_and_needs_separate_retest(engine):
    p = new_plan(model="RT_BREAKOUT_RETEST")
    engine.refresh("ETH-USDT", [p])
    push(engine, trade(1, "98"))
    push(engine, trade(2, "98"))
    assert engine.rows[p.plan_id]["phase"] == "BROKEN"
    engine.refresh("ETH-USDT", [])
    assert engine.rows[p.plan_id]["phase"] == "BROKEN"
    push(engine, book(3, "98.3", "98.4"))
    assert not push(engine, trade(4, "98"))
    push(engine, trade(5, "100"))
    assert engine.rows[p.plan_id]["phase"] == "RETEST"
    assert not push(engine, trade(6, "98.5"))
    assert len(push(engine, trade(7, "98.4"))) == 1


def test_unstarted_replaced_candidate_does_not_gain_preservation(engine):
    p = new_plan()
    engine.refresh("ETH-USDT", [p])
    engine.refresh("ETH-USDT", [])
    assert engine.rows[p.plan_id]["phase"] == "INVALIDATED"


@pytest.mark.parametrize("phase", ["WAITING", "BROKEN", "ARMED", "RETEST", "TRIGGERED"])
def test_disconnect_and_restart_are_terminal_not_reset(engine, phase):
    p = new_plan()
    engine.refresh("ETH-USDT", [p])
    engine.rows[p.plan_id]["phase"] = phase
    engine._save(p.plan_id)
    engine.disconnect()
    engine.refresh("ETH-USDT", [p])
    recovered = create_engine(engine.store, "events", parameters=PARAMS)
    assert recovered.rows[p.plan_id]["phase"] == "INVALIDATED"


@pytest.mark.parametrize(
    "phase", ["SUBMITTED", "UNKNOWN", "PARTIAL", "FILLED", "NO_FILL"]
)
def test_disconnect_never_erases_execution_recovery(engine, phase):
    p = new_plan()
    engine.refresh("ETH-USDT", [p])
    engine.terminal(p.plan_id, phase)
    engine.disconnect()
    assert engine.rows[p.plan_id]["phase"] == phase


def test_duplicate_out_of_order_and_retired_epoch_do_not_confirm(engine):
    p = new_plan(model="RT_BREAKOUT_RETEST")
    engine.refresh("ETH-USDT", [p])
    push(engine, trade(2, "98"))
    push(engine, trade(2, "98"))
    push(engine, trade(1, "98"))
    assert engine.rows[p.plan_id]["count"] == 1
    push(engine, trade(3, "98", epoch="b"))
    assert engine.rows[p.plan_id]["phase"] == "INVALIDATED"
    assert not push(engine, trade(4, "98", epoch="a"))


def test_original_anchor_expiry_not_refreshed_by_new_plan_time(engine):
    p = new_plan()
    engine.refresh("ETH-USDT", [p])
    engine.refresh("ETH-USDT", [replace(p, created_ms=100)])
    push(engine, trade(32 * 900000, "98"))
    assert engine.rows[p.plan_id]["reason"] == "ORIGINAL_ANCHOR_EXPIRED"


def test_target_crossed_before_trigger_is_not_chased(engine):
    p = new_plan()
    engine.refresh("ETH-USDT", [p])
    push(engine, trade(1, "89"))
    assert engine.rows[p.plan_id]["reason"] == "TARGET_ALREADY_CROSSED"


def test_closed_reclaim_and_revision_terminate_started_event(engine):
    entry, hour, spec, at = inputs()
    plans = engine.refresh_frame(
        entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT
    )
    p = next(p for p in plans if p.line == 110)
    row = engine.rows[p.plan_id]
    row.update(phase="ARMED", event_started_ms=at)
    new_candle = replace(
        entry.candles[-1],
        open_time=entry.candles[-1].open_time + 900,
        high=D(120),
        close=D(119),
    )
    changed = replace(entry, candles=entry.candles[1:] + (new_candle,))
    engine.refresh_frame(
        changed, hour, spec, now_ms=at + 900000, signal_side=TrendSide.FLAT
    )
    assert row["reason"] == "CLOSED_BAR_RECLAIMED"


def test_state_write_failure_must_flush_before_processing(engine, monkeypatch):
    p = new_plan()
    save = engine.store.save

    def fail(*args):
        raise OSError("full")

    monkeypatch.setattr(engine.store, "save", fail)
    with pytest.raises(OSError):
        engine.refresh("ETH-USDT", [p])
    with pytest.raises(OSError):
        push(engine, trade(1, "98"))
    assert engine.dirty
    monkeypatch.setattr(engine.store, "save", save)
    push(engine, trade(1, "98"))
    assert (
        not engine.dirty and engine.store.all("events")[p.plan_id]["phase"] == "WAITING"
    )


def test_new_candidate_cannot_borrow_original_trial_permission(tmp_path):
    from htx_trend.realtime.trial_admission import validate
    from test_realtime_trial_admission import artifact, save

    v = artifact(tmp_path)
    v.update(
        permission="TRIAL",
        candidate=ORIGINAL,
        candidate_confirmation_ref=v["confirmation_ref"],
        candidate_confirmation_sha256=v["confirmation_sha256"],
    )
    with pytest.raises(ValueError, match="candidate"):
        validate(save(tmp_path, v), v, entry=True, candidate=EVENT_LOCAL)
    v["candidate"] = EVENT_LOCAL
    validate(save(tmp_path, v), v, entry=True, candidate=EVENT_LOCAL)
    with pytest.raises(ValueError, match="candidate"):
        validate(save(tmp_path, v), v, entry=True, candidate=EVENT_WINDOWS)


def test_profile_config_binds_new_candidate_but_keeps_old_parameters(monkeypatch):
    from htx_trend.realtime.factory import parameters

    monkeypatch.setenv("HTX_RT_CANDIDATE", EVENT_LOCAL)
    monkeypatch.setenv("HTX_RT_TARGET_MODE", "J2")
    assert parameters().candidate == EVENT_LOCAL and parameters().target_mode == "J2"
    monkeypatch.setenv("HTX_RT_CANDIDATE", "MINUTE_RESEARCH")
    with pytest.raises(ValueError, match="unknown"):
        parameters()


def test_short_frame_delay_does_not_destroy_a_started_event(engine):
    entry, hour, spec, at = inputs()
    p = next(
        p
        for p in engine.refresh_frame(
            entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT
        )
        if p.line == 110
    )
    row = engine.rows[p.plan_id]
    row.update(phase="ARMED", event_started_ms=at, extreme="110")
    late = at + 900000
    engine.refresh_frame(entry, hour, spec, now_ms=late, signal_side=TrendSide.FLAT)
    assert row["phase"] == "ARMED"
    assert not push(engine, trade(late, "108"))
    assert row["phase"] == "ARMED" and row["count"] == 0


def test_paper_real_ledger_new_candidate_partial_fill_and_restart(tmp_path):
    from htx_trend.execution import ExecutionFacts
    from htx_trend.market_data import FundingRate, OrderBook
    from htx_trend.paper_worker import PaperFileLedger
    from htx_trend.realtime.brokers import PaperBroker
    from htx_trend.realtime.plans import RealtimeTrigger
    from htx_trend.realtime.risk import evaluate
    from htx_trend.realtime.storage import PaperPlanStore
    from test_realtime_execution import policy

    ledger = PaperFileLedger(
        tmp_path / "paper.json",
        {"instance": "p", "strategy": VERSION},
        mirror=lambda _: None,
    )
    e = create_engine(PaperPlanStore(ledger), "p", parameters=PARAMS)
    p = new_plan()
    e.refresh("ETH-USDT", [p])
    broker = PaperBroker(ledger, e, "paper:p")
    trigger = RealtimeTrigger(
        p, 1000, 1000, D(98), D(101), D(101), ("a", "b"), "bbo", "a"
    )
    spec = ContractSpec("ETH-USDT", D(".01"), D(".01"), 1)
    facts = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        account_equity_usdt=D(300),
        available_margin_usdt=D(300),
        leverage=D(5),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    gate, intent = evaluate(
        trigger,
        now_ms=1000,
        book=OrderBook(((D(98), D(10000)),), ((D("98.01"), D(10000)),)),
        contract=spec,
        funding=FundingRate("ETH-USDT", D(0)),
        facts=facts,
        instance_key="p",
        parameters=PARAMS,
    )
    assert gate.allowed
    broker.submit(
        intent,
        trigger,
        spec,
        now_ms=1000,
        fee_rate=D(".0004"),
        holding_parameters=policy(),
    )
    resumed = create_engine(PaperPlanStore(ledger), "p", parameters=PARAMS)
    assert resumed.rows[p.plan_id]["phase"] == "SUBMITTED"
    broker = PaperBroker(ledger, resumed, "paper:p")
    ids = broker.on_book(
        replace(book(1300, "97.9", "97.91"), bid_size=D(1)), now_ms=1300
    )
    assert len(ids) == 1 and ledger.position(ids[0]).quantity == 1
    assert ledger.position_context(ids[0])["entry_candidate_rule"] == EVENT_LOCAL
    assert (
        ledger.position_context(ids[0])["entry_parameters_fingerprint"]
        == PARAMS.fingerprint
    )
    assert not broker.on_book(book(1400, "98", "98.01"), now_ms=1400)
    assert len(ledger.data["orders"]) == 1
    ledger.close()


def test_waiting_confirmation_cannot_cross_price_plan_revision(engine):
    p = new_plan(model="RT_BREAKOUT_RETEST")
    engine.refresh("ETH-USDT", [p])
    push(engine, trade(1, "98"))
    assert engine.rows[p.plan_id]["count"] == 1
    engine.refresh("ETH-USDT", [replace(p, band=D(2))])
    assert engine.rows[p.plan_id]["count"] == 0
    push(engine, trade(2, "97"))
    assert engine.rows[p.plan_id]["phase"] == "WAITING"


def test_revision_of_pre_event_validity_bar_withdraws_active_pivot(engine):
    entry, hour, spec, at = inputs()
    p = next(
        p
        for p in engine.refresh_frame(
            entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT
        )
        if p.line == 110
    )
    row = engine.rows[p.plan_id]
    row.update(phase="ARMED", event_started_ms=at)
    cs = list(entry.candles)
    cs[158] = replace(cs[158], high=D(120), close=D(119))
    engine.refresh_frame(
        replace(entry, candles=tuple(cs)),
        hour,
        spec,
        now_ms=at + 1,
        signal_side=TrendSide.FLAT,
    )
    assert row["phase"] == "INVALIDATED" and row["reason"] == "STRUCTURE_BASIS_REVISED"


def test_control_cannot_claim_unimplemented_j2_target():
    with pytest.raises(ValueError, match="target mode"):
        Parameters(candidate=EVENT_WINDOWS, target_mode="J2")


def test_trade_hot_path_does_not_recalculate_structural_indicators(engine, monkeypatch):
    entry, hour, spec, at = inputs()
    engine.refresh_frame(entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT)
    monkeypatch.setattr(
        engine,
        "_invalid",
        lambda *_: pytest.fail("must validate structure only on refresh"),
    )
    assert not push(engine, trade(at + 1, "108"))


@pytest.mark.parametrize(
    "later,reason",
    [("89", "TARGET_ALREADY_CROSSED"), ("104", "TRIGGER_INVALIDATION_CROSSED")],
)
def test_triggered_but_unsubmitted_event_still_tracks_invalidation(
    engine, later, reason
):
    p = new_plan()
    engine.refresh("ETH-USDT", [p])
    push(engine, book(1, "98.3", "98.4"))
    for i, v in enumerate(["98", "100", "98.5", "98.4"], 2):
        result = push(engine, trade(i, v))
    assert len(result) == 1 and engine.rows[p.plan_id]["phase"] == "TRIGGERED"
    assert not push(engine, trade(6, later))
    push(engine, book(7, "98.3", "98.4"))
    push(engine, trade(8, "98.4"))
    assert engine.rows[p.plan_id]["phase"] == "INVALIDATED"
    assert engine.rows[p.plan_id]["reason"] == reason
