"""Fixture tapes for EXP-SPRT Phase-0 examples.

The candles and prints below are exploration fixtures. They are not a live
subscription and must not be described as exchange ticks.
"""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path

from htx_trend.market_data import Candle

from .arm_a import arm_a_for
from .evaluate import BookSnap, MarketObservation, ShadowSession, TradePrint
from .evidence import EvidenceStore
from .plan import generate_plan

BASE_OPEN = 1_700_000_000
PERIOD = 900


def _candle(index: int, open_: str, high: str, low: str, close: str) -> Candle:
    return Candle(
        BASE_OPEN + index * PERIOD,
        Decimal(open_), Decimal(high), Decimal(low), Decimal(close),
        Decimal("1"), Decimal("1"), Decimal("1"), 1,
    )


def _structure() -> list[Candle]:
    bars: list[Candle] = []
    for index in range(20):
        open_, high, low, close = "110", "111", "109", "110"
        if index == 0:
            open_, high, low, close = "105", "110", "100", "108"
        elif index == 1:
            open_, high, low, close = "110", "120", "110", "112"
        elif index == 9:
            open_, high, low, close = "110", "111", "107", "108"
        elif index == 19:
            open_, high, low, close = "105", "106", "104", "105"
        bars.append(_candle(index, open_, high, low, close))
    return bars


def rejection_candles() -> list[Candle]:
    return [*_structure(), _candle(20, "116", "119", "117", "118")]


def breakout_candles() -> list[Candle]:
    return [*_structure(), _candle(20, "100", "101", "99", "99")]


def rejection_as_of() -> int:
    return BASE_OPEN + 21 * PERIOD


def breakout_as_of() -> int:
    return rejection_as_of()


def _print(ts_ms: int, price: str, bid: str, ask: str) -> MarketObservation:
    trade = TradePrint(ts_ms, Decimal(price), Decimal("1"), "sell", f"fixture-{ts_ms}")
    book = BookSnap(
        bids=((Decimal(bid), Decimal("5")),),
        asks=((Decimal(ask), Decimal("4")),),
    )
    return MarketObservation(ts_ms, (trade,), book, True, "fixture_replay")


def _replay(plan: dict, tape: list[MarketObservation]) -> list[dict]:
    session = ShadowSession(plan)
    events: list[dict] = []
    for item in tape:
        events.extend(session.apply(item))
    return events


def _write_file_count(root: Path) -> None:
    lines = ["EXP-SPRT-20261102 phase-0 exploration file count"]
    for name in ("plans", "zone_events", "arm_a", "meta"):
        count = len([path for path in (root / name).glob("*") if path.is_file()])
        if name == "meta":
            count += 1
        lines.append(f"{name} {count}")
    path = root / "meta" / "file_count.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_phase0_examples(root: Path) -> dict:
    """Write one symbol's legal plans plus labeled R1/B2 zone events."""
    store = EvidenceStore(root)
    specs = (
        (rejection_candles(), rejection_as_of()),
        (breakout_candles(), breakout_as_of()),
    )
    plans = []
    for candles, as_of in specs:
        plan = generate_plan(candles, symbol="BTC-USDT", as_of_epoch=as_of)
        store.write_plan(plan)
        store.write_arm_a(arm_a_for(plan, candles, as_of_epoch=as_of))
        plans.append(plan)
    by_predicate = {plan["entry_behavior"]["primary_predicate"]: plan for plan in plans}
    r1_events = _replay(by_predicate["R1"], [
        _print(1_700_000_200_000, "118", "117.9", "118.1"),
        _print(1_700_000_201_000, "119", "118.9", "119.1"),
        _print(1_700_000_202_000, "116.5", "116.4", "116.6"),
    ])
    b2_events = _replay(by_predicate["B2"], [
        _print(1_700_000_210_000, "99.5", "99.4", "99.6"),
        _print(1_700_000_211_000, "99.8", "99.7", "99.9"),
        _print(1_700_000_212_000, "99.2", "99.1", "99.3"),
    ])
    for event in (*r1_events, *b2_events):
        store.write_zone_event(event)
    manifest = {
        "exp_id": "EXP-SPRT-20261102",
        "track": "探索",
        "phase": "phase-0",
        "schema_version": "sprt-plan-v0.1",
        "symbol": "BTC-USDT",
        "description_only": True,
        "formal_window": False,
        "formal_m2_counted": False,
        "orders_placed": 0,
        "data_origin": "fixture_replay",
        "primary_predicates": {"REJECTION": "R1", "BREAKOUT": "B2"},
        "dual_primary_forbidden": True,
        "n0": {"L1": 400, "L2": 200, "status": "design_target_description_only"},
        "plan_ids": [plan["plan_id"] for plan in plans],
        "note": "Fixture replay for Phase-0 exploration. Not an exchange subscription.",
    }
    store.write_meta("phase0-manifest.json", manifest)
    store.write_meta("dev-self-attestation.json", {
        "exp_id": "EXP-SPRT-20261102",
        "track": "探索",
        "phase": "phase-0",
        "orders_placed": 0,
        "live_worker_imported": False,
        "paper_worker_imported": False,
        "mutation_endpoints_called": False,
        "package_tip_modified": False,
        "note": "Phase-0 writer does not import worker or execution modules and does not restart processes.",
    })
    _write_file_count(root)
    return manifest
