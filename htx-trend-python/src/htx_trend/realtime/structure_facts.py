"""Target-independent structural facts for the registered offline comparison.

No broker, account or trading authority is available in this observer. Only a
valid frozen target can be converted into an ordinary price-plan input.
"""

from __future__ import annotations

import copy
from dataclasses import dataclass
from decimal import ROUND_CEILING, ROUND_FLOOR
from decimal import Decimal as D

from ..indicators import atr
from ..models import TrendSide
from ..research.store import digest, primitive
from .feed import EventOrder
from .plans import StructurePlan
from .structure_events import StructureEventEngine
from .structures import direction, levels

TARGET_GROUPS = ("J1", "J2", "J1_BOX32", "J2_BOX32")
DONE = {"INVALIDATED", "OBSERVED"}


@dataclass(frozen=True)
class StructureFact:
    fact_id: str
    origin: str
    contract: str
    side: TrendSide
    model: str
    edge: str
    line: D
    band: D
    atr: D
    tick: D
    anchor_ms: int
    confirmed_ms: int
    confirmed_seq: int
    created_ms: int
    structure_bar: int
    trend_bar: int
    sources: tuple[str, ...]
    evidence_refs: tuple[str, ...]
    parameter_fingerprint: str
    epoch: str | None = None

    @property
    def anchor_bar(self):
        # M/T retain their actual sub-bar extreme time; never forge a 15m anchor.
        return self.anchor_ms // 1000

    @classmethod
    def load(cls, value):
        value = dict(value)
        value["side"] = TrendSide(value["side"])
        for key in ("line", "band", "atr", "tick"):
            value[key] = D(value[key])
        for key in ("sources", "evidence_refs"):
            value[key] = tuple(value[key])
        return cls(**value)

    def plan(self, target):
        if target["status"] != "AVAILABLE" or target["price"] is None:
            raise ValueError("a fact without a valid target is not a trade plan")
        return StructurePlan(
            self.fact_id,
            self.contract,
            self.model,
            self.side,
            self.line,
            self.band,
            self.atr,
            self.tick,
            D(target["price"]),
            target["kind"],
            self.structure_bar,
            self.trend_bar,
            self.anchor_bar,
            self.sources + ("RESEARCH_FACT:" + self.origin,),
            tuple(sorted(set(self.evidence_refs + tuple(target["refs"])))),
            digest(
                {
                    "research": "TARGET_CONTINUATION_V1",
                    "origin": self.origin,
                    "parameters": self.parameter_fingerprint,
                }
            ),
            self.created_ms,
        )


def facts_for_frame(
    entry,
    hour,
    spec,
    *,
    now_ms,
    sequence,
    parameters,
    signal_side,
    origin="S",
    local_lines=(),
):
    side, reason = direction(entry, hour, spec, now_ms, signal_side)
    if reason:
        return (), reason
    volatility = atr(entry.candles, 14)
    if volatility <= 0:
        return (), "NO_ATR"
    band = max(2 * spec.price_tick, parameters.band_atr * volatility)
    raw = (
        levels(entry, spec.price_tick, parameters.band_atr)
        if origin == "S"
        else local_lines
    )
    output = []
    for row in raw:
        if row.get("withdrawn"):
            continue
        if origin == "S":
            anchor = row["anchor"] * 1000
            confirmed, seq, epoch = now_ms, sequence, None
            sources, refs = row["sources"], ()
        else:
            anchor, confirmed, seq, epoch = (
                row[k] for k in ("anchor_ms", "confirmed_ms", "confirmed_seq", "epoch")
            )
            if confirmed > now_ms:
                continue
            sources, refs = (origin + ":" + row["identity"],), row["refs"]
            if any(
                c.close > row["price"] + row["band"]
                if row["edge"] == "HIGH"
                else c.close < row["price"] - row["band"]
                for c in entry.candles
                if (c.open_time + 900) * 1000 > confirmed
            ):
                continue
        if now_ms >= anchor + 32 * 900000:
            continue
        rejection = row["edge"] == ("LOW" if side is TrendSide.LONG else "HIGH")
        ident = digest(
            {
                "family": "15M" if origin == "S" else "LOCAL_EXTREME",
                "contract": spec.contract_code,
                "side": side,
                "edge": row["edge"],
                "anchor_ms": anchor,
                **({"price": row["price"]} if origin != "S" else {}),
            }
        )
        output.append(
            StructureFact(
                ident,
                origin,
                spec.contract_code,
                side,
                "RT_RANGE_REJECTION" if rejection else "RT_BREAKOUT_RETEST",
                row["edge"],
                row["price"],
                band,
                volatility,
                spec.price_tick,
                anchor,
                confirmed,
                seq,
                now_ms,
                entry.candles[-1].open_time,
                hour.candles[-1].open_time,
                tuple(sources),
                tuple(
                    sorted(set(entry.evidence_refs + hour.evidence_refs + tuple(refs)))
                ),
                parameters.fingerprint,
                epoch,
            )
        )
    return tuple(output), None


def assess_target(fact, frame, *, group, now_ms, parameters, local_lines=()):
    """Select only from the activation-visible frame; never infer an absent price."""
    if group not in TARGET_GROUPS:
        raise ValueError("unknown registered target group")
    entry, hour, spec, signal = frame
    side, reason = direction(entry, hour, spec, now_ms, signal)
    base = {
        "group": group,
        "price": None,
        "kind": "NONE",
        "status": "NO_TARGET",
        "frozen_ms": now_ms,
        "refs": tuple(entry.evidence_refs + hour.evidence_refs),
    }
    if reason or side is not fact.side:
        return {**base, "status": reason or "DIRECTION_CHANGED"}
    long = fact.side is TrendSide.LONG
    edge = "HIGH" if long else "LOW"
    zone = fact.line + fact.band if long else fact.line - fact.band
    inventory = levels(entry, spec.price_tick, parameters.band_atr)
    candidates = [
        r
        for r in inventory
        if r["edge"] == edge
        and (
            group.startswith("J2")
            or any(s.startswith(("20:", "32:")) for s in r["sources"])
        )
    ]
    if group.startswith("J2"):
        for r in local_lines:
            if (
                r.get("withdrawn")
                or r["edge"] != edge
                or r["confirmed_ms"] > now_ms
                or now_ms >= r["anchor_ms"] + 32 * 900000
            ):
                continue
            if any(
                c.close > r["price"] + r["band"]
                if edge == "HIGH"
                else c.close < r["price"] - r["band"]
                for c in entry.candles
                if (c.open_time + 900) * 1000 > r["confirmed_ms"]
            ):
                continue
            candidates.append(r)
    candidates = [
        r for r in candidates if (r["price"] > zone if long else r["price"] < zone)
    ]
    target = None
    if candidates:
        target = (
            min(r["price"] for r in candidates)
            if long
            else max(r["price"] for r in candidates)
        )
        base.update(
            kind="EXISTING_BOUNDARY",
            refs=tuple(
                sorted(
                    set(
                        base["refs"]
                        + tuple(
                            ref
                            for r in candidates
                            if r["price"] == target
                            for ref in r.get("refs", ())
                        )
                    )
                )
            ),
        )
    elif group.endswith("BOX32") and fact.model == "RT_BREAKOUT_RETEST":
        high = max(c.high for c in entry.candles[-32:])
        low = min(c.low for c in entry.candles[-32:])
        # An internal local line cannot acquire an arbitrary distant measured target.
        if high > low and (fact.line >= high if long else fact.line <= low):
            target = 2 * high - low if long else 2 * low - high
            base.update(
                kind="BOX32_MEASURED",
                box_high=high,
                box_low=low,
                box_last_bar=entry.candles[-1].open_time,
            )
    if target is None:
        return base
    if not target.is_finite() or target <= 0:
        return {**base, "status": "INVALID_TARGET"}
    target = (target / spec.price_tick).to_integral_value(
        rounding=ROUND_FLOOR if long else ROUND_CEILING
    ) * spec.price_tick
    if not (target > zone if long else target < zone):
        return {**base, "status": "TARGET_INSIDE_ZONE"}
    return {**base, "price": target, "status": "AVAILABLE"}


class FactObserver:
    """Offline stages with durable fact identity and independent target eligibility."""

    def __init__(self, store, instance, parameters, origin="S"):
        self.store, self.instance, self.parameters, self.origin = (
            store,
            instance,
            parameters,
            origin,
        )
        self.rows = copy.deepcopy(store.all(instance))
        self.dirty = set()
        if any(
            row["fact"]["origin"] != origin
            or row["fact"]["parameter_fingerprint"] != parameters.fingerprint
            for row in self.rows.values()
        ):
            raise ValueError("incompatible fact store origin or parameters")
        self.frames, self.local_lines, self.books = {}, {}, {}
        self.order, self.epoch, self.retired = EventOrder(), None, set()
        self.transitions = []
        self.disconnect("RESTART")

    def save(self, ident):
        self.dirty.add(ident)
        self.store.save(self.instance, ident, primitive(self.rows[ident]))
        self.dirty.discard(ident)

    def flush(self):
        for ident in tuple(self.dirty):
            self.save(ident)

    def invalidate(self, ident, reason):
        self.rows[ident].update(
            phase="INVALIDATED", reason=reason, count=0, confirmations=[]
        )
        self.save(ident)

    def disconnect(self, reason="CONNECTION_INTERRUPTED"):
        if self.epoch is not None:
            self.retired.add(self.epoch)
        self.epoch = None
        self.order.reset()
        self.books.clear()
        for ident, row in self.rows.items():
            if row["phase"] not in DONE:
                self.invalidate(ident, reason)

    def refresh(
        self, entry, hour, spec, *, now_ms, sequence, signal_side, local_lines=()
    ):
        self.flush()
        code = spec.contract_code
        self.frames[code] = (entry, hour, spec, signal_side)
        self.local_lines[code] = local_lines
        facts, failure = facts_for_frame(
            entry,
            hour,
            spec,
            now_ms=now_ms,
            sequence=sequence,
            parameters=self.parameters,
            signal_side=signal_side,
            origin=self.origin,
            local_lines=local_lines,
        )
        wanted = {f.fact_id: f for f in facts}
        if failure in ("STALE_INPUT", "INCOMPLETE_INPUT"):
            return facts
        for ident, row in self.rows.items():
            if row["fact"]["contract"] != code or row["phase"] in DONE:
                continue
            fact = StructureFact.load(row["fact"])
            reason = failure
            if (
                spec.price_tick != fact.tick
                or fact.parameter_fingerprint != self.parameters.fingerprint
            ):
                reason = "SPEC_OR_PARAMETERS_CHANGED"
            if reason is None and fact.origin == "S":
                # This production validator reads basis/identity, never a target.
                reason = StructureEventEngine._invalid(self, fact, now_ms)
            if reason is None and (
                fact.side is not direction(entry, hour, spec, now_ms, signal_side)[0]
            ):
                reason = "DIRECTION_CHANGED"
            if now_ms >= fact.anchor_ms + 32 * 900000:
                reason = "ORIGINAL_ANCHOR_EXPIRED"
            if reason is None and row["phase"] == "WAITING" and ident not in wanted:
                reason = "UNSTARTED_STRUCTURE_REPLACED"
            if reason is None and row.get("started_ms") is not None:
                for c in entry.candles:
                    if (c.open_time + 900) * 1000 <= row["started_ms"]:
                        continue
                    if (
                        c.close < fact.line - fact.band
                        if fact.side is TrendSide.LONG
                        else c.close > fact.line + fact.band
                    ):
                        reason = "CLOSED_BAR_RECLAIMED"
                        break
            if reason:
                self.invalidate(ident, reason)
        for ident, fact in wanted.items():
            if ident not in self.rows:
                self.rows[ident] = {
                    "fact": primitive(fact),
                    "phase": "WAITING",
                    "count": 0,
                    "confirmations": [],
                    "prior_price": None,
                    "last_trade_ms": None,
                    "extreme": None,
                    "probe_extreme": None,
                    "started_ms": None,
                    "targets": {},
                }
                self.save(ident)
            elif self.rows[ident]["phase"] == "WAITING":
                row = self.rows[ident]
                old = StructureFact.load(row["fact"])
                if (old.line, old.band, old.atr) != (fact.line, fact.band, fact.atr):
                    row.update(
                        count=0,
                        confirmations=[],
                        prior_price=None,
                        last_trade_ms=None,
                        probe_extreme=None,
                    )
                row["fact"] = primitive(fact)
                self.save(ident)
        return facts

    def accept(self, event, *, now_ms, sequence):
        self.flush()
        if event.epoch in self.retired:
            return ()
        if self.epoch is not None and self.epoch != event.epoch:
            self.disconnect()
        self.epoch = event.epoch
        if not event.fresh(now_ms) or not self.order.accept(event):
            return ()
        if event.kind == "BBO":
            self.books[event.contract] = event
            return ()
        if event.kind != "TRADE":
            return ()
        observations = []
        for ident, row in self.rows.items():
            if row["fact"]["contract"] != event.contract or row["phase"] in DONE:
                continue
            before = copy.deepcopy(row)
            fact = StructureFact.load(row["fact"])
            if now_ms >= fact.anchor_ms + 32 * 900000:
                self.invalidate(ident, "ORIGINAL_ANCHOR_EXPIRED")
                continue
            if sequence <= fact.confirmed_seq or event.received_ms < fact.created_ms:
                continue
            frame = self.frames[event.contract]
            if not (
                0 <= now_ms / 1000 - frame[0].candles[-1].open_time - 900 < 900
                and 0 <= now_ms / 1000 - frame[1].candles[-1].open_time - 3600 < 3600
            ):
                row.update(
                    count=0, confirmations=[], last_trade_ms=None, probe_extreme=None
                )
                if row != before:
                    self.save(ident)
                continue
            long, price = fact.side is TrendSide.LONG, event.price
            for target in row["targets"].values():
                if target["status"] == "AVAILABLE" and (
                    price >= D(target["price"]) if long else price <= D(target["price"])
                ):
                    target["status"] = "TARGET_ALREADY_CROSSED"
            low, high = fact.line - fact.band, fact.line + fact.band
            favorable, zone = (
                (price > high if long else price < low),
                low <= price <= high,
            )
            previous = D(row["prior_price"]) if row["prior_price"] is not None else None
            if (
                row["last_trade_ms"] is not None
                and event.exchange_ms - row["last_trade_ms"] > 2000
            ):
                row.update(count=0, confirmations=[], probe_extreme=None)

            phase = row["phase"]
            if phase == "WAITING":
                if fact.model == "RT_RANGE_REJECTION":
                    if (
                        zone
                        and previous is not None
                        and (previous > high if long else previous < low)
                    ):
                        row.update(
                            phase="ARMED", extreme=str(price), count=0, confirmations=[]
                        )
                elif favorable:
                    old_extreme = row.get("probe_extreme")
                    row["probe_extreme"] = str(
                        price
                        if old_extreme is None
                        else max(D(old_extreme), price)
                        if long
                        else min(D(old_extreme), price)
                    )
                    StructureEventEngine._confirm(row, event)
                    if row["count"] >= 2:
                        row.update(phase="BROKEN", count=0, confirmations=[])
                else:
                    row.update(count=0, confirmations=[], probe_extreme=None)
            elif phase == "BROKEN":
                if zone:
                    row.update(
                        phase="RETEST", extreme=str(price), count=0, confirmations=[]
                    )
            elif phase in ("ARMED", "RETEST"):
                extreme = D(row["extreme"])
                row["extreme"] = str(
                    min(extreme, price) if long else max(extreme, price)
                )
                if favorable:
                    StructureEventEngine._confirm(row, event)
                    book = self.books.get(event.contract)
                    quote_ok = (
                        book is not None
                        and book.epoch == event.epoch
                        and book.fresh(now_ms)
                        and abs(book.exchange_ms - event.exchange_ms) <= 2000
                        and (book.bid > high if long else book.ask < low)
                    )
                    if row["count"] >= 2 and quote_ok:
                        reference = book.ask if long else book.bid
                        raw = (
                            D(row["extreme"]) - fact.band
                            if long
                            else D(row["extreme"]) + fact.band
                        )
                        stop = (
                            min(raw, reference - D("1.25") * fact.atr)
                            if long
                            else max(raw, reference + D("1.25") * fact.atr)
                        )
                        observation = {
                            "fact": primitive(fact),
                            "event_id": row["event_id"],
                            "trigger_ms": event.received_ms,
                            "exchange_ms": event.exchange_ms,
                            "reference": str(reference),
                            "raw_invalidation": str(raw),
                            "stop": str(stop),
                            "trade_refs": row["confirmations"],
                            "book_ref": digest(book),
                            "epoch": event.epoch,
                            "targets": copy.deepcopy(row["targets"]),
                            "order_submitted": False,
                        }
                        observations.append(observation)
                        row.update(phase="OBSERVED", observation=observation)
                else:
                    row.update(count=0, confirmations=[])
            if row["phase"] in ("ARMED", "BROKEN") and row["started_ms"] is None:
                row["started_ms"] = event.received_ms
                row["event_id"] = digest(
                    {"fact_id": ident, "first_stage_ref": digest(event)}
                )
                row["first_stage_ref"] = digest(event)
                row["targets"] = {
                    g: primitive(
                        assess_target(
                            fact,
                            frame,
                            group=g,
                            now_ms=now_ms,
                            parameters=self.parameters,
                            local_lines=self.local_lines.get(event.contract, ()),
                        )
                    )
                    for g in TARGET_GROUPS
                }
                for target in row["targets"].values():
                    stage_extreme = D(row.get("probe_extreme") or str(price))
                    if target["status"] == "AVAILABLE" and (
                        max(stage_extreme, price) >= D(target["price"])
                        if long
                        else min(stage_extreme, price) <= D(target["price"])
                    ):
                        target["status"] = "TARGET_ALREADY_CROSSED"
            row["prior_price"] = str(
                low - fact.tick
                if price < low
                else high + fact.tick
                if price > high
                else fact.line
            )
            row["last_trade_ms"] = event.exchange_ms if row["count"] else None
            if before["phase"] != row["phase"]:
                self.transitions.append(
                    {
                        "fact_id": ident,
                        "phase": row["phase"],
                        "at_ms": event.received_ms,
                        "ref": digest(event),
                    }
                )
            if row != before:
                self.save(ident)
        return tuple(observations)
