from dataclasses import replace
from decimal import Decimal as D

import pytest
from htx_trend.market_data import Candle
from htx_trend.models import TrendSide
from htx_trend.realtime.local_structure_diagnostic import Study
from htx_trend.realtime.ohlc_diagnostic import ArchivedBar
from htx_trend.realtime.plans import Parameters, StructurePlan


def fixture_bars(low=False):
    rows = []
    for i in range(19):
        c = Candle(i * 900, D(100), D(101), D(99), D(100))
        if i in (12,):
            c = replace(c, low=D(95)) if low else replace(c, high=D(105))
        rows.append(ArchivedBar("15min", c, (i + 1) * 900 + 1, str(i)))
    return rows


@pytest.mark.parametrize("low", [False, True])
def test_pivot_requires_two_closed_and_archived_right_bars(low):
    rows = fixture_bars(low)
    study = Study(rows)
    edge = "LOW" if low else "HIGH"
    assert not [p for p in study.pivots(15 * 900, D(".1")) if p.edge == edge]
    pivots = [p for p in study.pivots(15 * 900 + 1, D(".1")) if p.edge == edge]
    assert len(pivots) == 1
    assert pivots[0].anchor_bar == 12 * 900
    assert pivots[0].confirmation_bar == 14 * 900
    assert pivots[0].available == 15 * 900 + 1
    assert len(pivots[0].refs) == 5


def test_equal_extreme_is_not_an_arbitrary_pivot():
    rows = fixture_bars()
    rows[13] = replace(rows[13], candle=replace(rows[13].candle, high=D(105)))
    assert not [
        p for p in Study(rows).pivots(16 * 900 + 1, D(".1")) if p.edge == "HIGH"
    ]


def test_future_close_invalidation_is_not_visible_early():
    rows = fixture_bars()
    rows[16] = replace(
        rows[16], candle=replace(rows[16].candle, high=D(111), close=D(110))
    )
    study = Study(rows)
    assert any(p.anchor_bar == 12 * 900 for p in study.pivots(16 * 900 + 1, D(".1")))
    assert not any(
        p.anchor_bar == 12 * 900 for p in study.pivots(17 * 900 + 1, D(".1"))
    )
    base = Study(rows[:16])
    assert base.pivots(16 * 900 + 1, D(".1")) == study.pivots(16 * 900 + 1, D(".1"))


@pytest.mark.parametrize("low", [False, True])
def test_failure_uses_preexisting_line_and_only_exists_after_close(low):
    rows = fixture_bars(low)
    c = rows[16].candle
    c = (
        replace(c, low=D(88), high=D(103), close=D(102))
        if low
        else replace(c, high=D(112), low=D(97), close=D(98))
    )
    rows[16] = replace(rows[16], candle=c)
    study = Study(rows)
    assert not study.failures(17 * 900, D(".1"))
    levels = study.failures(17 * 900 + 1, D(".1"))
    assert len(levels) == 1
    assert levels[0].anchor_bar == 16 * 900
    assert levels[0].price == (D(88) if low else D(112))
    assert levels[0].parent_ids


def test_failure_cannot_use_pivot_confirmed_after_bar_start():
    rows = fixture_bars()
    # Delay the pivot's archive until after bar16 started, though its K-line closed earlier.
    rows[14] = replace(rows[14], available=16 * 900 + 10)
    rows[16] = replace(
        rows[16], candle=replace(rows[16].candle, high=D(112), low=D(97), close=D(98))
    )
    assert not Study(rows).failures(17 * 900 + 1, D(".1"))


def test_gap_disqualifies_five_bar_confirmation():
    rows = fixture_bars()
    rows[13] = replace(
        rows[13], candle=replace(rows[13].candle, open_time=13 * 900 + 1)
    )
    assert not [
        p for p in Study(rows).pivots(16 * 900 + 1, D(".1")) if p.edge == "HIGH"
    ]


def test_closer_rejection_line_keeps_original_target_and_breakout():
    study = Study(fixture_bars())
    base = StructurePlan(
        "base",
        "ETH-USDT",
        "RT_RANGE_REJECTION",
        TrendSide.SHORT,
        D(120),
        D(".2"),
        D(2),
        D(".01"),
        D(90),
        "EXISTING_BOUNDARY",
        14 * 900,
        0,
        0,
        ("20:HIGH",),
        (),
        Parameters().fingerprint,
        (15 * 900 + 1) * 1000,
    )
    breakout = replace(base, plan_id="break", model="RT_BREAKOUT_RETEST", line=D(90))
    scan = {"at": 15 * 900 + 1, "plans": (base, breakout)}
    chosen = study.candidate_plans(scan, Parameters(), "B")
    assert chosen[0].line == D(105)
    assert chosen[0].target == D(90)
    assert chosen[1] == breakout
    assert study.candidate_plans(scan, Parameters(), "A") == scan["plans"]
    # C without a confirmed failed break falls back to baseline, not B.
    assert study.candidate_plans(scan, Parameters(), "C") == scan["plans"]


def test_same_anchor_cannot_become_new_identity_after_source_reclassification():
    from htx_trend.realtime import VERSION
    from htx_trend.realtime.ohlc_diagnostic import Geometry
    from htx_trend.research.store import digest

    identity = digest(
        {
            "version": VERSION,
            "contract": "ETH-USDT",
            "side": TrendSide.SHORT,
            "model": "RT_RANGE_REJECTION",
            "anchor_bar": 12 * 900,
            "edge": "HIGH",
        }
    )
    # An earlier range extreme is later confirmed as a local pivot at the same anchor.
    old = StructurePlan(
        identity,
        "ETH-USDT",
        "RT_RANGE_REJECTION",
        TrendSide.SHORT,
        D(105),
        D(".2"),
        D(2),
        D(".01"),
        D(90),
        "EXISTING_BOUNDARY",
        14 * 900,
        0,
        12 * 900,
        ("20:HIGH",),
        (),
        Parameters().fingerprint,
        (15 * 900 + 1) * 1000,
    )
    engine = Geometry()
    engine.refresh([old])
    engine.rows[identity]["phase"] = "TERMINAL"
    current_range = replace(old, plan_id="different", line=D(120), anchor_bar=1)
    chosen = Study(fixture_bars()).candidate_plans(
        {"at": 15 * 900 + 1, "plans": (current_range,)}, Parameters(), "B"
    )[0]
    assert chosen.plan_id == identity
    engine.refresh([chosen])
    assert engine.rows[identity]["phase"] == "TERMINAL"
