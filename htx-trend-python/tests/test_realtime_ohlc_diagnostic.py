from dataclasses import replace
from decimal import Decimal as D

import pytest
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.market_data import Candle, ContractSpec
from htx_trend.models import TrendSide
from htx_trend.realtime.ohlc_diagnostic import (
    ArchivedBar,
    Geometry,
    barriers,
    cost_geometry,
    scenario_points,
    snapshots,
    visible_window,
    walk,
)
from htx_trend.realtime.plans import Parameters, StructurePlan


def plan(side=TrendSide.SHORT, model="RT_RANGE_REJECTION", key="p"):
    return StructurePlan(
        key,
        "ETH-USDT",
        model,
        side,
        D(100),
        D(1),
        D(2),
        D(".01"),
        D(90) if side is TrendSide.SHORT else D(110),
        "EXISTING_BOUNDARY",
        0,
        0,
        0,
        ("20:HIGH",),
        (),
        Parameters().fingerprint,
        0,
    )


def bar(t=0, available=901, ref="a", period="15min", price="100"):
    p = D(price)
    return ArchivedBar(
        period, Candle(t, p, p + 2, p - 2, p, D(1), D(100), D(100), 1), available, ref
    )


def test_availability_revision_and_prefix():
    a = bar()
    revision = replace(
        a, available=1200, ref="b", candle=replace(a.candle, close=D(101))
    )
    future = bar(900, 1801, "c")
    assert not visible_window([a, revision, future], "15min", 900).candles
    assert visible_window([a, revision, future], "15min", 1100).candles == (a.candle,)
    assert visible_window([a, revision, future], "15min", 1200).candles == (
        revision.candle,
    )
    assert visible_window([a], "15min", 1100) == visible_window(
        [a, revision, future], "15min", 1100
    )
    with pytest.raises(ValueError, match="conflicting"):
        visible_window([a, replace(revision, available=901)], "15min", 1200)


@pytest.mark.parametrize("side", [TrendSide.LONG, TrendSide.SHORT])
def test_rejection_symmetric_and_terminal_identity(side):
    e = Geometry()
    p = plan(side)
    e.refresh([p])
    prices = [98, 99, 102, 98] if side is TrendSide.SHORT else [102, 101, 98, 102]
    for i, price in enumerate(prices):
        e.point(i, D(price))
    assert len(e.opportunities) == 1
    op = e.opportunities[0]
    assert op["stop"] == (D(103) if side is TrendSide.SHORT else D(97))
    # Even a revised structure/ordinary refresh cannot revive the same identity.
    e.refresh([replace(p, structure_bar=900)])
    for i, price in enumerate(prices):
        e.point(i + 10, D(price))
    assert len(e.opportunities) == 1
    assert cost_geometry(op, D(".10"))["net_r_excluding_unknown_funding"] > 0


@pytest.mark.parametrize("side", [TrendSide.LONG, TrendSide.SHORT])
def test_breakout_requires_separate_retest(side):
    e = Geometry()
    e.refresh([plan(side, "RT_BREAKOUT_RETEST")])
    favorable = D(98) if side is TrendSide.SHORT else D(102)
    e.point(0, favorable)
    e.point(1, favorable)
    assert not e.opportunities
    e.point(2, D(100))
    e.point(3, favorable)
    assert len(e.opportunities) == 1


def test_breakout_retest_failure_and_structure_replacement():
    e = Geometry()
    e.refresh([plan(model="RT_BREAKOUT_RETEST")])
    for i, price in enumerate((98, 100, 102, 98)):
        e.point(i, D(price))
    assert not e.opportunities
    assert e.rows["p"]["reason"] == "RETEST_FAILED"
    e.refresh([plan(key="new")])
    assert e.rows["new"]["phase"] == "WAITING"


def test_path_order_changes_barrier_without_claiming_fill():
    op = {"at_assumed": 0, "plan": plan(), "stop": D(105)}
    high_first = [(0, D(100)), (300, D(110)), (600, D(80)), (899, D(100))]
    low_first = [(0, D(100)), (300, D(80)), (600, D(110)), (899, D(100))]
    assert barriers(op, high_first)["outcome"] == "STOP_CONTACT"
    assert barriers(op, low_first)["outcome"] == "TARGET_CONTACT"
    assert barriers(op, [(0, D(100)), (899, D(101))])["outcome"] == "RIGHT_CENSORED"


def test_path_rejects_gaps_revisions_and_bad_path():
    with pytest.raises(ValueError, match="gap"):
        scenario_points([bar(), bar(1800, 2701, "b")], "OHLC")
    with pytest.raises(ValueError, match="revisions"):
        scenario_points([bar(), bar(ref="b")], "OHLC")
    with pytest.raises(ValueError, match="unknown"):
        scenario_points([bar()], "random")


def test_late_plan_cannot_claim_earlier_path_excursion():
    points = [(0, D(98)), (300, D(102)), (600, D(98)), (899, D(98))]
    early = walk(points, [{"at": 1, "plans": (plan(),)}])
    late = walk(points, [{"at": 601, "plans": (plan(),)}])
    assert len(early.opportunities) == 1
    assert not late.opportunities
    assert early.opportunities[0]["scope"] == "ASSUMPTION_ONLY_NOT_EXECUTABLE"


def test_no_bridge_over_interbar_jump():
    # Jump from below to above the zone doesn't prove a traded touch.
    e = walk([(0, D(98)), (1, D(102)), (300, D(98))], [{"at": 0, "plans": (plan(),)}])
    assert not e.opportunities


def test_prefix_plan_inputs_exclude_future():
    start = 576000
    bars = [
        bar(i * 3600, start + 60, f"h{i}", "60min", str(100 + i)) for i in range(160)
    ]
    bars += [
        bar(start - (160 - i) * 900, start + 60, f"m{i}", price=str(100 + i))
        for i in range(160)
    ]
    spec = [(start + 60, ContractSpec("ETH-USDT", D(".01"), D(".01"), 1), "spec")]
    configs = [(start + 60, JavaEntryConfig(), "config", "commit")]
    old = snapshots(bars, spec, configs, Parameters(), start + 1800)
    extended = bars + [bar(start, start + 960, "future", price="1000")]
    new = snapshots(extended, spec, configs, Parameters(), start + 1800)
    assert old == [s for s in new if s["at"] < start + 960]
    assert old[0]["entry_bar"] == start - 900
    for s in new:
        for p in s["plans"]:
            assert p.structure_bar + 900 <= s["at"]
            assert p.trend_bar + 3600 <= s["at"]


def test_cost_monotonic_under_assumed_slippage():
    e = Geometry()
    e.refresh([plan()])
    for i, p in enumerate([98, 99, 100, 98]):
        e.point(i, D(p))
    op = e.opportunities[0]
    values = [
        cost_geometry(op, D(s))["net_r_excluding_unknown_funding"]
        for s in (".05", ".10", ".20")
    ]
    assert values[0] > values[1] > values[2]


def test_incomplete_archive_write_does_not_invalidate_an_active_plan():
    scans = [
        {"at": 0, "plans": (plan(),), "apply_refresh": True},
        {"at": 200, "plans": (), "apply_refresh": False},
        {"at": 201, "plans": (plan(),), "apply_refresh": True},
    ]
    result = walk([(0, D(98)), (300, D(102)), (600, D(98))], scans)
    assert len(result.opportunities) == 1
    assert result.rows["p"]["phase"] == "TERMINAL"


def test_hypothetical_entry_is_tick_aligned():
    e = Geometry()
    e.refresh([plan()])
    for i, p in enumerate(["98", "99", "100", "98.987654"]):
        e.point(i, D(p))
    assert e.opportunities[0]["reference_assumed"] == D("98.98")


def test_archive_integrity_fails_closed(tmp_path):
    import gzip
    import json

    from htx_trend.realtime.ohlc_diagnostic import load_archive
    from htx_trend.research.store import digest

    obj = {"kind": "compact_candle", "refs": ["missing"], "payload": {}}
    path = tmp_path / "archive.gz"

    def write(key):
        path.write_bytes(
            gzip.compress(
                json.dumps(
                    {"sources": {"s": {"objects": {key: {"object": obj}}}}}
                ).encode()
            )
        )

    write("wrong")
    with pytest.raises(ValueError, match="hash mismatch"):
        load_archive(path, "s")
    write(digest(obj))
    with pytest.raises(ValueError, match="missing CAS reference"):
        load_archive(path, "s")
