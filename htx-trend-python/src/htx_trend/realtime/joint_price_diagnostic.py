"""Frozen activation-time target research, never an executable trading strategy."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import replace
from decimal import ROUND_CEILING, ROUND_FLOOR
from decimal import Decimal as D
from pathlib import Path

from ..models import TrendSide
from ..research.store import canonical, digest, primitive
from .fast_structure_diagnostic import EphemeralStore, Research, read_tape
from .feed import EventOrder, MarketEvent
from .ohlc_diagnostic import cost_geometry
from .plans import Parameters, StructurePlan, TriggerEngine


def target_snapshot(plan, frame, lines, at):
    """Choose the nearest opposite boundary; later information cannot revise it."""
    long = plan.side is TrendSide.LONG
    edge = "HIGH" if long else "LOW"
    zone = plan.line + plan.band if long else plan.line - plan.band
    levels = []
    for n in (20, 32):
        bars = frame["entry"].candles[-n:]
        price = max(c.high for c in bars) if long else min(c.low for c in bars)
        levels.append(
            {
                "price": price,
                "source": str(n) + ":" + edge,
                "refs": (frame["ref"],),
                "known_ms": at,
            }
        )
    local = []
    for line in lines:
        if (
            line.edge != edge
            or line.confirmed_ms > at
            or line.anchor_ms > at
            or line.anchor_ms / 1000 < frame["entry"].candles[-1].open_time - 31 * 900
        ):
            continue
        if any(
            (
                c.close > line.price + line.band
                if edge == "HIGH"
                else c.close < line.price - line.band
            )
            for c in frame["entry"].candles
            if (c.open_time + 900) * 1000 > line.confirmed_ms
        ):
            continue
        local.append(
            {
                "price": line.price,
                "source": line.kind + ":" + line.identity,
                "refs": line.refs,
                "known_ms": line.confirmed_ms,
            }
        )

    def select(choices):
        merged = defaultdict(list)
        for row in choices:
            p = row["price"]
            if p > zone if long else p < zone:
                merged[p].append(row)
        if not merged:
            return {"target": None, "reason": "NO_TARGET", "sources": []}
        price = min(merged) if long else max(merged)
        tick_price = (price / plan.tick).to_integral_value(
            rounding=ROUND_FLOOR if long else ROUND_CEILING
        ) * plan.tick
        if not (tick_price > zone if long else tick_price < zone):
            return {
                "target": None,
                "reason": "TICK_ROUNDING_INSIDE_ZONE",
                "sources": merged[price],
            }
        return {
            "target": tick_price,
            "reason": "FROZEN_NEAREST_BOUNDARY",
            "sources": merged[price],
        }

    return {
        "activation_ms": at,
        "plan": plan.dump(),
        "frame_ref": frame["ref"],
        "J1": select(levels),
        "J2": select(levels + local),
    }


class TargetEngine(TriggerEngine):
    def __init__(self, owner, key):
        self.owner, self.key = owner, key
        self.active_targets = {}
        super().__init__(
            EphemeralStore(), str(key), parameters=Parameters(key[1], D("1.5"))
        )

    def accept(self, event, *, now_ms):
        before = {i: r["phase"] for i, r in self.rows.items()}
        triggers = super().accept(event, now_ms=now_ms)
        for ident, row in self.rows.items():
            if row["phase"] == "ARMED" and before.get(ident) != "ARMED":
                p = StructurePlan.load(row["plan"])
                snapshot = target_snapshot(
                    p, self.owner.frames[p.contract], self.owner.lines[self.key], now_ms
                )
                snapshot.update(
                    group=primitive(self.key),
                    epoch=event.epoch,
                    sequence=len(self.owner.activations),
                )
                self.owner.activations.append(snapshot)
                self.active_targets[ident] = snapshot
        return triggers


class QuotePaths:
    """First future executable quote contact, censored at the first coverage gap."""

    def __init__(self):
        self.pending = defaultdict(list)
        self.results = []
        self.order = EventOrder()
        self.source_refs = {}

    def add(self, key, trigger, target, book):
        long = trigger["plan"]["side"] == "LONG"
        ref, stop = D(trigger["reference"]), D(trigger["invalidation_price"])
        if target is None or not (stop < ref < target if long else target < ref < stop):
            self.results.append({"key": key, "outcome": "INVALID_GEOMETRY"})
            return
        self.pending[book.contract].append(
            {
                "key": key,
                "long": long,
                "target": target,
                "stop": stop,
                "trigger_ms": trigger["trigger_ms"],
                "trigger_exchange_ms": trigger["exchange_ms"],
                "epoch": book.epoch,
                "last_received": book.received_ms,
                "last_exchange": book.exchange_ms,
            }
        )

    def finish_row(self, row, outcome, at, source=None):
        self.results.append(
            {
                "key": row["key"],
                "outcome": outcome,
                "observed_ms": at,
                "source_ref": source,
                "last_quote_received_ms": row["last_received"],
            }
        )

    def observe(self, value, source_ref, now):
        if value["kind"] == "GAP":
            for rows in self.pending.values():
                for row in rows:
                    self.finish_row(row, "CENSORED_CONNECTION_GAP", now)
            self.pending.clear()
            self.order.reset()
            return
        if value["kind"] != "BBO":
            return
        e = MarketEvent.load(value)
        rows = self.pending[e.contract]
        keep = []
        valid = e.fresh(now) and self.order.accept(e)
        for row in rows:
            if e.epoch != row["epoch"]:
                self.finish_row(row, "CENSORED_CONNECTION_GAP", now)
            elif (
                now - row["last_received"] > 2000
                or e.exchange_ms - row["last_exchange"] > 2000
            ):
                self.finish_row(row, "CENSORED_QUOTE_GAP", row["last_received"] + 2000)
            elif (
                not valid
                or e.received_ms <= row["trigger_ms"]
                or e.exchange_ms <= row["trigger_exchange_ms"]
            ):
                keep.append(row)
            else:
                price = e.bid if row["long"] else e.ask
                stop = price <= row["stop"] if row["long"] else price >= row["stop"]
                target = (
                    price >= row["target"] if row["long"] else price <= row["target"]
                )
                row.update(last_received=e.received_ms, last_exchange=e.exchange_ms)
                if stop or target:
                    ref = digest(source_ref)
                    self.source_refs[ref] = source_ref
                    self.finish_row(
                        row,
                        "STOP_REFERENCE_TOUCH" if stop else "TARGET_REFERENCE_TOUCH",
                        now,
                        ref,
                    )
                else:
                    keep.append(row)
        self.pending[e.contract] = keep

    def finish(self, at):
        for rows in self.pending.values():
            for row in rows:
                gap = at - row["last_received"] > 2000
                self.finish_row(
                    row,
                    "CENSORED_QUOTE_GAP" if gap else "CENSORED_DATA_END",
                    row["last_received"] + 2000 if gap else at,
                )
        self.pending.clear()


class JointResearch(Research):
    def __init__(self, header):
        super().__init__(header)
        self.activations = []
        self.joint = []
        self.paths = QuotePaths()

    def group(self, variant, k, code):
        key = (variant, k, code)
        if key not in self.groups:
            self.groups[key] = TargetEngine(self, key)
        return self.groups[key]

    def observe(self, event, source_ref):
        self.paths.observe(event, source_ref, max(self.now, event["received_ms"]))
        before = len(self.triggers)
        super().observe(event, source_ref)
        for row in self.triggers[before:]:
            trigger = row["trigger"]
            p = StructurePlan.load(trigger["plan"])
            ref, raw, stop = (
                D(trigger[k])
                for k in ("reference", "raw_invalidation", "invalidation_price")
            )
            expected = (
                min(raw, ref - D("1.25") * p.atr)
                if p.side is TrendSide.LONG
                else max(raw, ref + D("1.25") * p.atr)
            )
            assert stop == expected, "original risk formula changed"
            engine = self.group(row["variant"], row["k"], row["contract"])
            snap = engine.active_targets.get(p.plan_id)
            if p.model == "RT_RANGE_REJECTION":
                assert (
                    snap is not None and snap["activation_ms"] < trigger["trigger_ms"]
                )
            for geometry in ("J0", "J1", "J2"):
                if geometry == "J0" or p.model != "RT_RANGE_REJECTION":
                    selected = {
                        "target": p.target,
                        "reason": "ORIGINAL_TARGET",
                        "sources": p.sources,
                    }
                else:
                    selected = snap[geometry]
                target = selected["target"]
                costs = {}
                for slip in (D(".05"), D(".10"), D(".20")):
                    if target is None:
                        c = {
                            "net_r_excluding_unknown_funding": None,
                            "reason": selected["reason"],
                        }
                    else:
                        c = cost_geometry(
                            {
                                "plan": replace(p, target=target),
                                "reference_assumed": ref,
                                "stop": stop,
                            },
                            slip,
                        )
                        for old, new in (
                            ("reward_per_eth_assumed", "reward_per_base_unit_assumed"),
                            ("risk_per_eth_assumed", "risk_per_base_unit_assumed"),
                        ):
                            if old in c:
                                c[new] = c.pop(old)
                    costs[str(slip)] = c
                key = digest(
                    {
                        "variant": row["variant"],
                        "k": row["k"],
                        "geometry": geometry,
                        "plan_id": p.plan_id,
                        "trigger_ms": trigger["trigger_ms"],
                    }
                )
                self.joint.append(
                    {
                        "key": key,
                        "variant": row["variant"],
                        "k": row["k"],
                        "geometry": geometry,
                        "contract": p.contract,
                        "trigger": trigger,
                        "selection": selected,
                        "activation_sequence": snap["sequence"] if snap else None,
                        "cost_assumptions": costs,
                    }
                )
                self.paths.add(key, trigger, target, engine.books[p.contract])


def summary(rows, paths, cutoff):
    by_key = {x["key"]: x for x in paths}
    buckets = defaultdict(list)
    for row in rows:
        split = (
            "DIAGNOSTIC"
            if row["trigger"]["trigger_ms"] <= cutoff
            else "CHRONOLOGICAL_TAIL_CHECK"
        )
        buckets[(split, row["variant"], str(row["k"]), row["geometry"])].append(row)
    result = []
    for key, group in sorted(buckets.items()):
        r_values = [
            x["cost_assumptions"]["0.10"]["net_r_excluding_unknown_funding"]
            for x in group
        ]
        result.append(
            {
                "split": key[0],
                "variant": key[1],
                "k": key[2],
                "geometry": key[3],
                "triggers": len(group),
                "r_pass": {
                    str(n): sum(r is not None and D(r) >= n for r in r_values)
                    for n in (D(1), D("1.5"), D(2))
                },
                "invalid_geometry": sum(r is None for r in r_values),
                "paths": dict(Counter(by_key[x["key"]]["outcome"] for x in group)),
            }
        )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("prefix", "tail", "baseline", "manifest", "protocol", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    manifest = json.loads(Path(args.manifest).read_text())
    baseline = json.loads(gzip.decompress(Path(args.baseline).read_bytes()))
    assert (
        hashlib.sha256(Path(args.prefix).read_bytes()).hexdigest()
        == baseline["tape_sha256"]
    )
    study, footers = None, []
    for tape in (args.prefix, args.tail):
        for row in read_tape(tape):
            if row["type"] == "HEADER":
                if study is None:
                    study = JointResearch(dict(row, last_ms=manifest["last_ms"]))
                else:
                    assert row["first_ms"] >= manifest["diagnostic_end_ms"]
            elif row["type"] == "COMPLETE":
                footers.append(row)
                if tape == args.prefix:
                    assert primitive(study.events) == baseline["events"]
                    assert primitive(study.counters) == baseline["rejections"]
                    assert primitive(study.per_contract) == baseline["per_contract"]
            else:
                study.observe(row["observation"], row["source_ref"])
    assert study and len(footers) == 2
    cutoff = manifest["diagnostic_end_ms"]
    prefix = [x for x in study.triggers if x["trigger"]["trigger_ms"] <= cutoff]
    assert primitive(prefix) == baseline["triggers"], (
        "baseline trigger/context mismatch"
    )
    study.paths.finish(manifest["last_ms"])
    output = {
        "status": "RESEARCH_ONLY_NOT_ELIGIBLE",
        "orders_submitted": 0,
        "baseline_prefix_equal": True,
        "diagnostic_end_ms": cutoff,
        "header": study.header,
        "footers": footers,
        "events": study.events,
        "per_contract": study.per_contract,
        "frame_fresh_intervals": study.frame_intervals,
        "rejections": study.counters,
        "activations": study.activations,
        "triggers": study.joint,
        "source_refs": study.source_refs | study.paths.source_refs,
        "paths": study.paths.results,
        "summary": summary(study.joint, study.paths.results, cutoff),
        "input_sha256": {
            n: hashlib.sha256(Path(getattr(args, n)).read_bytes()).hexdigest()
            for n in ("prefix", "tail", "baseline", "manifest", "protocol")
        },
        "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }
    Path(args.output).write_bytes(gzip.compress(canonical(output), mtime=0))
    print(
        json.dumps(
            primitive(
                {
                    "baseline_prefix_equal": True,
                    "activations": len(study.activations),
                    "summary": output["summary"],
                }
            ),
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
