from dataclasses import replace
from decimal import Decimal as D

import pytest
from htx_trend.execution import ExecutionFacts
from htx_trend.market_data import FundingRate, OrderBook
from htx_trend.models import TrendSide
from htx_trend.paper_worker import PaperFileLedger
from htx_trend.realtime import VERSION
from htx_trend.realtime.brokers import PaperBroker
from htx_trend.realtime.feed import MarketEvent
from htx_trend.realtime.plans import SBOX, Parameters, create_engine, generate
from htx_trend.realtime.risk import evaluate
from htx_trend.realtime.storage import PaperPlanStore, PlanStore
from test_realtime_execution import policy
from test_realtime_worker import worker  # noqa: F401 — shared pytest fixture
from test_structure_events import inputs as wide_inputs

P = Parameters(candidate=SBOX, target_mode="J1_BOX32")


def inputs():
    entry, hour, spec, at = wide_inputs()
    # Keep real structural extrema in this synthetic fixture; narrow ordinary bars
    # so the shared 20U minimum notional fits the unchanged 1.5U risk budget.
    candles = tuple(
        replace(
            c,
            high=D(101) if c.high == 104 else c.high,
            low=D(99) if c.low == 96 else c.low,
        )
        for c in entry.candles
    )
    return replace(entry, candles=candles), hour, spec, at


@pytest.fixture
def engine(tmp_path):
    store = PlanStore(tmp_path / "plans.sqlite")
    e = create_engine(store, "p", parameters=P)
    entry, hour, spec, at = inputs()
    e.refresh_frame(entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT)
    yield e, at, spec
    store.close()


def send(e, at, seq, p=None, bid=None, epoch="e"):
    v = MarketEvent(
        "TRADE" if p is not None else "BBO",
        "ETH-USDT",
        at + seq,
        at + seq,
        str(seq),
        epoch,
        price=D(str(p)) if p is not None else None,
        bid=D(str(bid)) if bid is not None else None,
        ask=D(str(bid)) + D(".01") if bid is not None else None,
        bid_size=D(10000),
        ask_size=D(10000),
    )
    return e.accept(v, now_ms=at + seq)


def trigger(e, at):
    send(e, at, 1, bid=77)
    send(e, at, 2, p=77)
    send(e, at, 3, p=77)
    send(e, at, 4, p=80)
    send(e, at, 5, p=77)
    return next(t for t in send(e, at, 6, p=77) if t.plan.line == 80)


def test_parameters_are_explicit_and_original_unchanged():
    for kwargs in ({"min_net_r": D(1)}, {"band_atr": D(".05")}, {"target_mode": "J1"}):
        with pytest.raises(ValueError):
            replace(P, **kwargs)
    assert Parameters().candidate != SBOX
    with pytest.raises(ValueError, match="activation"):
        entry, hour, spec, at = inputs()
        generate(entry, hour, spec, now_ms=at, parameters=P)


def test_real_identity_and_frozen_target_feed_shared_risk(engine):
    e, at, spec = engine
    t = trigger(e, at)
    assert t.plan.parameter_fingerprint == P.fingerprint
    assert not any(s.startswith("RESEARCH") for s in t.plan.sources)
    assert t.plan.target == 40 and t.plan.target_kind == "BOX32_MEASURED"
    assert t.invalidation_price >= t.reference + D("1.25") * t.plan.atr
    facts = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        available_margin_usdt=D(300),
        account_equity_usdt=D(300),
        leverage=D(5),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    gate, intent = evaluate(
        t,
        now_ms=at + 6,
        book=OrderBook(((D(77), D(10000)),), ((D("77.01"), D(10000)),)),
        contract=spec,
        funding=FundingRate("ETH-USDT", D(0)),
        facts=facts,
        instance_key="p",
        parameters=P,
    )
    assert gate.allowed and intent is not None
    assert intent.price.expected_net_r >= D("1.5")
    assert e.rows[t.plan.plan_id]["phase"] == "TRIGGERED"


@pytest.mark.parametrize(
    "phase", ["SUBMITTED", "UNKNOWN", "PARTIAL", "FILLED", "NO_FILL"]
)
def test_execution_state_survives_restart_without_new_intent(engine, phase):
    e, at, spec = engine
    t = trigger(e, at)
    e.terminal(t.plan.plan_id, phase, client_order_id="same-id")
    restored = create_engine(e.store, "p", parameters=P)
    assert restored.rows[t.plan.plan_id]["phase"] == phase
    entry, hour, _, _ = inputs()
    restored.refresh_frame(
        entry, hour, spec, now_ms=at + 10, signal_side=TrendSide.FLAT
    )
    assert not send(restored, at, 11, p=77)
    assert restored.rows[t.plan.plan_id]["client_order_id"] == "same-id"
    if phase in ("SUBMITTED", "UNKNOWN"):
        restored.terminal(t.plan.plan_id, "FILLED", position_id="existing")
        assert restored.rows[t.plan.plan_id]["phase"] == "FILLED"


def test_unsubmitted_trigger_rejected_on_restart(engine):
    e, at, _ = engine
    t = trigger(e, at)
    restored = create_engine(e.store, "p", parameters=P)
    assert restored.rows[t.plan.plan_id]["phase"] == "REJECTED"


@pytest.mark.parametrize(
    "price,reason",
    [(30, "TARGET_ALREADY_CROSSED"), (150, "TRIGGER_INVALIDATION_CROSSED")],
)
def test_later_message_in_same_drain_invalidates_queued_trigger(engine, price, reason):
    e, at, _ = engine
    t = trigger(e, at)
    send(e, at, 7, p=price)
    assert e.rows[t.plan.plan_id]["phase"] == "INVALIDATED"
    assert e.rows[t.plan.plan_id]["reason"] == reason


def test_connection_switch_invalidates_trigger_but_preserves_unknown_order(engine):
    e, at, _ = engine
    t = trigger(e, at)
    send(e, at, 7, p=77, epoch="next")
    assert e.rows[t.plan.plan_id]["phase"] == "REJECTED"
    assert not send(e, at, 8, p=77, epoch="e")


def test_no_target_is_persisted_without_fake_executable_plan(engine):
    e, at, _ = engine
    # Internal 90 support has an 80 target. Force no profitable target using the selected
    # target assessment in a unit fault fixture, without introducing a fake price.
    send(e, at, 1, bid=77)
    send(e, at, 2, p=77)
    send(e, at, 3, p=77)
    fact = next(
        (i, r) for i, r in e.observer.rows.items() if D(r["fact"]["line"]) == 80
    )
    fact[1]["targets"]["J1_BOX32"].update(status="NO_TARGET", price=None)
    send(e, at, 4, p=80)
    send(e, at, 5, p=77)
    send(e, at, 6, p=77)
    assert e.plan_id(fact[0]) not in e.rows
    assert fact[1]["decision"]["reason"] == "NO_TARGET"
    assert e.store.all(e.fact_instance)[fact[0]]["decision"]["reason"] == "NO_TARGET"


def test_plan_persist_failure_cannot_emit_or_replay_order(engine, monkeypatch):
    e, at, _ = engine
    original = e.store.save

    def fail(instance, ident, value):
        if instance == "p":
            raise OSError("injected disk failure")
        original(instance, ident, value)

    monkeypatch.setattr(e.store, "save", fail)
    with pytest.raises(OSError):
        trigger(e, at)
    assert e.dirty
    monkeypatch.setattr(e.store, "save", original)
    # A process restart sees the terminal fact, not an executable missed order.
    restored = create_engine(e.store, "p", parameters=P)
    assert not restored.rows
    assert any(
        r.get("decision", {}).get("reason") == "INTERRUPTED_BEFORE_EXECUTABLE_PLAN"
        for r in restored.observer.rows.values()
    )


def test_fact_persist_failure_blocks_new_trigger_and_can_flush(engine, monkeypatch):
    e, at, _ = engine
    original = e.store.save

    def fail(instance, ident, value):
        if instance == e.fact_instance:
            raise OSError("injected fact IO")
        original(instance, ident, value)

    monkeypatch.setattr(e.store, "save", fail)
    send(e, at, 1, bid=77)
    with pytest.raises(OSError):
        send(e, at, 2, p=77)
    assert e.observer.dirty and not e.rows
    monkeypatch.setattr(e.store, "save", original)
    e.flush()
    assert not e.observer.dirty


@pytest.mark.parametrize("fail_first", [False, True])
def test_paper_partial_ioc_and_restart_use_same_confirmed_plan(
    tmp_path, monkeypatch, fail_first
):
    ledger = PaperFileLedger(
        tmp_path / "paper.json",
        {"instance": "p", "strategy": VERSION},
        mirror=lambda _: None,
    )
    e = create_engine(PaperPlanStore(ledger), "p", parameters=P)
    entry, hour, spec, at = inputs()
    e.refresh_frame(entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT)
    t = trigger(e, at)
    broker = PaperBroker(ledger, e, "paper:p")
    facts = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        available_margin_usdt=D(300),
        account_equity_usdt=D(300),
        leverage=D(5),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    _, intent = evaluate(
        t,
        now_ms=at + 6,
        book=OrderBook(((D(77), D(10000)),), ((D("77.01"), D(10000)),)),
        contract=spec,
        funding=FundingRate("ETH-USDT", D(0)),
        facts=facts,
        instance_key="p",
        parameters=P,
    )
    if fail_first:
        import copy

        before = copy.deepcopy(ledger.data)
        facts_before = copy.deepcopy(e.observer.rows)
        original_save = e.store.save

        def fail(instance, ident, value):
            if instance == e.instance and value["phase"] == "SUBMITTED":
                raise OSError("injected ledger submission failure")
            return original_save(instance, ident, value)

        monkeypatch.setattr(e.store, "save", fail)
        with pytest.raises(OSError):
            broker.submit(
                intent,
                t,
                spec,
                now_ms=at + 6,
                fee_rate=D(".0004"),
                holding_parameters=policy(),
            )
        assert ledger.data == before and e.observer.rows == facts_before
        assert e.rows[t.plan.plan_id]["phase"] == "TRIGGERED"
        monkeypatch.setattr(e.store, "save", original_save)
        e.flush()
        assert not e.dirty and not ledger.data.get("realtime_pending")
    assert (
        broker.submit(
            intent,
            t,
            spec,
            now_ms=at + 6,
            fee_rate=D(".0004"),
            holding_parameters=policy(),
        )
        == "SUBMITTED"
    )
    book = MarketEvent(
        "BBO",
        "ETH-USDT",
        at + 300,
        at + 300,
        "fill",
        "e",
        bid=D(77),
        ask=D("77.01"),
        bid_size=D(1),
        ask_size=D(1),
    )
    ids = broker.on_book(book, now_ms=at + 300)
    assert len(ids) == 1
    assert e.rows[t.plan.plan_id]["phase"] == "PARTIAL"
    restored = create_engine(PaperPlanStore(ledger), "p", parameters=P)
    assert restored.rows[t.plan.plan_id]["phase"] == "PARTIAL"
    assert ledger.position_context(ids[0])["initial_stop"] == str(t.invalidation_price)
    ledger.close()


@pytest.mark.parametrize(
    "bid,reason",
    [(30, "TARGET_ALREADY_CROSSED"), (150, "TRIGGER_INVALIDATION_CROSSED")],
)
def test_bbo_cross_then_recovery_does_not_revive_trigger(engine, bid, reason):
    e, at, _ = engine
    t = trigger(e, at)
    send(e, at, 7, bid=bid)
    send(e, at, 8, bid=77)
    assert e.rows[t.plan.plan_id]["phase"] == "INVALIDATED"
    assert e.rows[t.plan.plan_id]["reason"] == reason


def test_sbox_trial_requires_matching_confirmed_candidate(tmp_path):
    from htx_trend.realtime.trial_admission import validate
    from test_realtime_trial_admission import artifact, save

    v = artifact(tmp_path)
    v.update(
        permission="TRIAL",
        candidate=SBOX,
        candidate_confirmation_ref=v["confirmation_ref"],
        candidate_confirmation_sha256=v["confirmation_sha256"],
        parameters_hash=P.fingerprint,
    )
    path = save(tmp_path, v)
    validate(path, v, entry=True, candidate=SBOX)
    with pytest.raises(ValueError, match="candidate"):
        validate(path, v, entry=True)


@pytest.mark.parametrize("crossed", [False, True])
def test_shared_worker_full_drain_and_preflight(worker, crossed):  # noqa: F811
    from types import SimpleNamespace

    w, events, management = worker
    entry, hour, spec, at = inputs()
    w.engine = create_engine(w.engine.store, "p", parameters=P)
    w.parameters = P
    w.clock = lambda: (at + 10) / 1000
    w.stream.epoch = w.last_epoch = "e"
    w.frames["ETH-USDT"] = {
        "entry": entry,
        "trend": hour,
        "contract": spec,
        "windows": {"15min": entry, "60min": hour},
    }
    w.engine.refresh_frame(entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT)
    w.refreshed["ETH-USDT"] = tuple(
        (p, v.window_hash) for p, v in w.frames["ETH-USDT"]["windows"].items()
    )
    w.market = SimpleNamespace(
        order_book=lambda _: OrderBook(((D(77), D(10000)),), ((D("77.01"), D(10000)),)),
        funding_rate=lambda _: FundingRate("ETH-USDT", D(0)),
    )
    facts = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        available_margin_usdt=D(300),
        account_equity_usdt=D(300),
        leverage=D(5),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    w.broker.facts = lambda *args: facts
    w.broker.repo.latest_done_trade = lambda **kwargs: None
    for seq, p in enumerate([None, 77, 77, 80, 77, 77], 1):
        events.append(
            MarketEvent(
                "BBO" if p is None else "TRADE",
                "ETH-USDT",
                at + seq,
                at + seq,
                str(seq),
                "e",
                price=D(p) if p is not None else None,
                bid=D(77) if p is None else None,
                ask=D("77.01") if p is None else None,
            )
        )
    if crossed:
        events.extend(
            [
                MarketEvent(
                    "BBO",
                    "ETH-USDT",
                    at + 7,
                    at + 7,
                    "cross",
                    "e",
                    bid=D(150),
                    ask=D("150.01"),
                ),
                MarketEvent(
                    "BBO",
                    "ETH-USDT",
                    at + 8,
                    at + 8,
                    "recover",
                    "e",
                    bid=D(77),
                    ask=D("77.01"),
                ),
            ]
        )
    health = w.tick()
    row = next(
        r
        for r in w.engine.rows.values()
        if r.get("candidate_rule") == SBOX and D(r["plan"]["line"]) == 80
    )
    assert health.candidate_count == 1
    assert row["reason"] == (
        "TRIGGER_INVALIDATION_CROSSED" if crossed else "SHADOW_ONLY"
    )
    assert management.calls[0][0] == "stop"


def test_direct_legacy_generator_rejects_sbox():
    from htx_trend.realtime.structures import generate_candidates

    entry, hour, spec, at = inputs()
    with pytest.raises(ValueError, match="activation"):
        generate_candidates(entry, hour, spec, now_ms=at, parameters=P)


@pytest.mark.parametrize("long", [False, True])
@pytest.mark.parametrize("fail_save", [False, True])
def test_frozen_fact_bbo_crossing_cannot_recover_to_order(
    tmp_path, monkeypatch, long, fail_save
):
    entry, hour, spec, at = inputs()
    if long:

        def mirror(window):
            return replace(
                window,
                candles=tuple(
                    replace(
                        c,
                        open=D(200) - c.open,
                        high=D(200) - c.low,
                        low=D(200) - c.high,
                        close=D(200) - c.close,
                    )
                    for c in window.candles
                ),
            )

        entry, hour = mirror(entry), mirror(hour)
    store = PlanStore(tmp_path / "plans.sqlite")
    e = create_engine(store, "x", parameters=P)
    e.refresh_frame(entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT)
    price, line = (123, 120) if long else (77, 80)
    send(e, at, 1, bid=price)
    send(e, at, 2, p=price)
    send(e, at, 3, p=price)
    ident, row = next(
        (i, r)
        for i, r in e.observer.rows.items()
        if D(r["fact"]["line"]) == line and r["phase"] == "BROKEN"
    )
    assert row["targets"]["J1_BOX32"]["status"] == "AVAILABLE"
    original = store.save
    if fail_save:

        def fail(instance, ident, value):
            if instance == e.fact_instance:
                raise OSError("injected target-state write failure")
            return original(instance, ident, value)

        monkeypatch.setattr(store, "save", fail)
        with pytest.raises(OSError):
            send(e, at, 4, bid=170 if long else 30)
        monkeypatch.setattr(store, "save", original)
        e.flush()
    else:
        send(e, at, 4, bid=170 if long else 30)
    assert row["targets"]["J1_BOX32"]["status"] == "TARGET_ALREADY_CROSSED"
    assert row["phase"] == "BROKEN"  # structural fact remains observable
    assert (
        store.all(e.fact_instance)[ident]["targets"]["J1_BOX32"]["crossed_by"] == "BBO"
    )
    send(e, at, 5, bid=price)
    send(e, at, 6, p=line)
    send(e, at, 7, p=price)
    outputs = send(e, at, 8, p=price)
    assert all(t.plan.line != line for t in outputs)
    assert (
        row["phase"] == "OBSERVED"
        and row["decision"]["reason"] == "TARGET_ALREADY_CROSSED"
    )
    assert e.plan_id(ident) not in e.rows
    store.close()


def test_pre_activation_exchange_quote_cannot_cancel_frozen_target(engine):
    e, at, _ = engine
    send(e, at, 1, bid=77)
    send(e, at, 2, p=77)
    send(e, at, 3, p=77)
    row = next(
        r
        for r in e.observer.rows.values()
        if D(r["fact"]["line"]) == 80 and r["phase"] == "BROKEN"
    )
    old = MarketEvent(
        "BBO",
        "ETH-USDT",
        at + 2,
        at + 4,
        "older-exchange",
        "e",
        bid=D(30),
        ask=D("30.01"),
    )
    e.accept(old, now_ms=at + 4)
    assert row["targets"]["J1_BOX32"]["status"] == "AVAILABLE"


@pytest.mark.parametrize(
    "exchange_offset,bid,expected",
    [
        (2, 30, "TRIGGERED"),  # before activation; cannot disqualify target
        (4, 150, "TRIGGERED"),  # before stop exists; cannot disqualify stop
        (
            4,
            30,
            "INVALIDATED",
        ),  # after activation; late crossed-target evidence matters
        (7, 150, "INVALIDATED"),  # after trigger; stop exists
    ],
)
def test_queued_trigger_checks_quote_exchange_time_boundaries(
    engine, exchange_offset, bid, expected
):
    e, at, _ = engine
    t = trigger(e, at)
    quote = MarketEvent(
        "BBO",
        "ETH-USDT",
        at + exchange_offset,
        at + 8,
        "late",
        "e",
        bid=D(bid),
        ask=D(bid) + D(".01"),
    )
    e.accept(quote, now_ms=at + 8)
    assert e.rows[t.plan.plan_id]["phase"] == expected
