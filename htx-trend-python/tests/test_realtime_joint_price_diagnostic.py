from collections import defaultdict
from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace

import pytest
from htx_trend.market_data import Candle, KlineWindow
from htx_trend.models import TrendSide
from htx_trend.realtime.fast_structure_diagnostic import EphemeralStore, Line
from htx_trend.realtime.feed import MarketEvent
from htx_trend.realtime.joint_price_diagnostic import (
    QuotePaths,
    TargetEngine,
    target_snapshot,
)
from htx_trend.realtime.plans import Parameters, StructurePlan, TriggerEngine
from htx_trend.research.store import digest, primitive

T = 576000000
CODE = "ETH-USDT"


def plan(side=TrendSide.SHORT):
    return StructurePlan(
        "p",
        CODE,
        "RT_RANGE_REJECTION",
        side,
        D(100),
        D(1),
        D(2),
        D(".01"),
        D(90) if side is TrendSide.SHORT else D(110),
        "EXISTING_BOUNDARY",
        T // 1000 - 900,
        0,
        0,
        ("original",),
        (),
        Parameters().fingerprint,
        T,
    )


def frame():
    bars = tuple(
        Candle(T // 1000 - (32 - i) * 900, D(100), D(110), D(90), D(100))
        for i in range(32)
    )
    return {"entry": KlineWindow(CODE, "15min", bars, 32, ("frame",)), "ref": "frame"}


def line(edge="LOW", price=95, confirmed=T - 100):
    return Line("M", edge, D(price), T - 60000, confirmed, D(".5"), ("known-print",))


def trade(at, price, epoch="e"):
    return MarketEvent("TRADE", CODE, at, at, str(at), epoch, price=D(price))


def book(at, bid, ask, epoch="e"):
    return MarketEvent("BBO", CODE, at, at, str(at), epoch, bid=D(bid), ask=D(ask))


@pytest.mark.parametrize(
    "side,edge,price", [(TrendSide.LONG, "HIGH", 105), (TrendSide.SHORT, "LOW", 95)]
)
def test_symmetric_nearest_target_and_no_skip(side, edge, price):
    p = plan(side)
    s = target_snapshot(p, frame(), [line(edge, price), line(edge, price)], T)
    assert s["J2"]["target"] == D(price)
    assert s["J1"]["target"] == p.target
    assert len(s["J2"]["sources"]) == 2  # same price merges all source evidence


def test_future_and_invalidated_local_lines_are_excluded():
    f = frame()
    # Future line and support above subsequent closed price are not valid targets.
    assert target_snapshot(plan(), f, [line(confirmed=T + 1)], T)["J2"]["target"] == D(
        90
    )
    bars = list(f["entry"].candles)
    bars[-1] = replace(bars[-1], close=D(94))
    f["entry"] = replace(f["entry"], candles=tuple(bars))
    assert target_snapshot(plan(), f, [line(confirmed=T - 1800000)], T)["J2"][
        "target"
    ] == D(90)


def test_no_target_and_conservative_tick_rounding():
    p = replace(plan(), line=D(80))
    assert target_snapshot(p, frame(), [], T)["J1"]["reason"] == "NO_TARGET"
    p = replace(plan(), line=D(91), band=D(".005"), tick=D(".01"))
    s = target_snapshot(p, frame(), [line(price="90.994")], T)
    assert s["J2"]["reason"] == "TICK_ROUNDING_INSIDE_ZONE"
    # Do not silently fall back from the closest unrepresentable target to 90.
    assert s["J2"]["target"] is None


def test_activation_freezes_and_reconnect_creates_new_snapshot():
    owner = SimpleNamespace(
        frames={CODE: frame()}, lines=defaultdict(list), activations=[]
    )
    key = ("M", D(".1"), CODE)
    owner.lines[key] = [line()]
    engine = TargetEngine(owner, key)
    engine.refresh(CODE, [plan()])
    for event in [trade(T + 1, 98), trade(T + 2, 100)]:
        engine.accept(event, now_ms=event.received_ms)
    assert engine.active_targets["p"]["J2"]["target"] == D(95)
    owner.lines[key] = [line(price=97)]
    engine.refresh(CODE, [plan()])
    engine.accept(trade(T + 3, 100), now_ms=T + 3)
    assert engine.active_targets["p"]["J2"]["target"] == D(95)
    for event in [trade(T + 4, 98, "new"), trade(T + 5, 100, "new")]:
        engine.accept(event, now_ms=event.received_ms)
    assert len(owner.activations) == 2
    assert engine.active_targets["p"]["J2"]["target"] == D(97)


def test_target_sidecar_preserves_real_trigger_and_stop():
    owner = SimpleNamespace(
        frames={CODE: frame()}, lines=defaultdict(list), activations=[]
    )
    engines = [
        TargetEngine(owner, ("M", D(".1"), CODE)),
        TriggerEngine(EphemeralStore(), "original"),
    ]
    out = []
    for engine in engines:
        engine.refresh(CODE, [plan()])
        triggers = []
        for event in [
            book(T + 1, 98, 98.01),
            trade(T + 2, 98),
            trade(T + 3, 100),
            trade(T + 4, 98),
            trade(T + 5, 98),
        ]:
            triggers.extend(engine.accept(event, now_ms=event.received_ms))
        out.append(primitive(triggers))
    assert len(out[0]) == 1 and out[0] == out[1]
    assert D(out[0][0]["invalidation_price"]) == D(101)


def trigger(side="SHORT", at=T):
    return {
        "plan": {"side": side},
        "trigger_ms": at,
        "exchange_ms": at,
        "reference": "100",
        "invalidation_price": "105" if side == "SHORT" else "95",
    }


def observe(paths, e):
    value = e.dump()
    ref = {"segment": "a" * 64, "index": e.received_ms, "event_hash": digest(value)}
    paths.observe(value, ref, e.received_ms)


@pytest.mark.parametrize(
    "side,target,bid,ask,outcome",
    [
        ("SHORT", 95, 94, 95, "TARGET_REFERENCE_TOUCH"),
        ("SHORT", 95, 104, 105, "STOP_REFERENCE_TOUCH"),
        ("LONG", 105, 105, 106, "TARGET_REFERENCE_TOUCH"),
        ("LONG", 105, 95, 96, "STOP_REFERENCE_TOUCH"),
    ],
)
def test_future_executable_quote_contact(side, target, bid, ask, outcome):
    p = QuotePaths()
    p.add("x", trigger(side), D(target), book(T, 99, 100))
    # Price before/on entry is never credited as a future touch.
    observe(p, book(T, bid, ask))
    assert not p.results
    observe(p, book(T + 1, bid, ask))
    assert p.results[0]["outcome"] == outcome


def test_quote_gap_censors_even_when_next_quote_touches_target():
    p = QuotePaths()
    p.add("x", trigger(), D(95), book(T, 99, 100))
    observe(p, book(T + 2001, 94, 95))
    assert p.results[0]["outcome"] == "CENSORED_QUOTE_GAP"


def test_wrong_side_and_end_are_not_zero_profit():
    p = QuotePaths()
    p.add("bad", trigger(), D(101), book(T, 99, 100))
    p.add("end", trigger(), D(95), book(T, 99, 100))
    p.finish(T + 10)
    assert [r["outcome"] for r in p.results] == [
        "INVALID_GEOMETRY",
        "CENSORED_DATA_END",
    ]
    assert all("profit" not in r for r in p.results)


def test_late_pretrigger_quote_cannot_be_a_posttrigger_touch():
    p = QuotePaths()
    t = trigger(at=T + 1100)
    t["exchange_ms"] = T + 1050
    p.add("late", t, D(95), replace(book(T + 1000, 99, 100), exchange_ms=T))
    observe(p, replace(book(T + 1110, 94, 95), exchange_ms=T + 50))
    assert not p.results
    observe(p, replace(book(T + 1120, 94, 95), exchange_ms=T + 1060))
    assert p.results[0]["outcome"] == "TARGET_REFERENCE_TOUCH"
    assert p.results[0]["observed_ms"] == T + 1120
