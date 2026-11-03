"""Registered target/continuation study on a hash-checked public tape. No orders."""

from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import json
from collections import Counter, defaultdict
from decimal import Decimal as D
from pathlib import Path

from ..indicators import atr
from ..java_entry_contract import JavaEntryConfig, evaluate
from ..market_data import ContractSpec
from ..research.store import canonical, digest, primitive
from .fast_structure_diagnostic import (
    EphemeralStore,
    Line,
    Minutes,
    Reversal,
    read_tape,
    window,
)
from .feed import EventOrder, MarketEvent
from .joint_price_diagnostic import QuotePaths
from .ohlc_diagnostic import cost_geometry
from .plans import EVENT_LOCAL, Parameters
from .structure_facts import DONE, TARGET_GROUPS, FactObserver, StructureFact
from .structures import direction


class Study:
    def __init__(self):
        self.frames, self.engines = {}, {}
        self.frame_valid, self.volatility = {}, {}
        self.bad_minutes = set()
        self.lines = defaultdict(list)
        self.minutes, self.reversal = Minutes(), Reversal()
        self.order, self.epoch, self.retired = EventOrder(), None, set()
        self.now, self.sequence = 0, 0
        self.sources, self.counts, self.rejections = {}, Counter(), Counter()
        self.observations, self.generations = [], []
        self.coverage_revisions = []
        self.paths = QuotePaths()

    def gap(self):
        if self.epoch is not None:
            self.retired.add(self.epoch)
        self.epoch = None
        self.minutes.reset(self.now)
        self.reversal.reset()
        self.order.reset()
        self.lines.clear()
        for engine in self.engines.values():
            engine.disconnect()

    def engine(self, origin, k, code):
        key = (origin, str(k), code)
        if key not in self.engines:
            self.engines[key] = FactObserver(
                EphemeralStore(), str(key), Parameters(k, candidate=EVENT_LOCAL), origin
            )
        return self.engines[key]

    def refresh(self, code, origins=("S", "M", "T")):
        frame = self.frames[code]
        entry, hour, spec, signal = frame
        for origin in origins:
            for k in (D(".05"), D(".10")):
                engine = self.engine(origin, k, code)
                facts = engine.refresh(
                    entry,
                    hour,
                    spec,
                    now_ms=self.now,
                    sequence=self.sequence,
                    signal_side=signal,
                    local_lines=self.lines[(origin, str(k), code)],
                )
                self.generations.append(
                    {
                        "origin": origin,
                        "k": str(k),
                        "contract": code,
                        "at_ms": self.now,
                        "fact_ids": [f.fact_id for f in facts],
                        "input_refs": entry.evidence_refs + hour.evidence_refs,
                    }
                )

    def add_line(self, origin, code, value, confirmation_ref, minutes=()):
        edge, price, anchor, refs = value
        frame = self.frames[code]
        vol = self.volatility[code]
        for k in (D(".05"), D(".10")):
            line = Line(
                origin,
                edge,
                price,
                anchor,
                self.now,
                max(2 * frame[2].price_tick, k * vol),
                refs,
            )
            rows = self.lines[(origin, str(k), code)]
            if all(r["identity"] != line.identity for r in rows):
                rows.append(
                    {
                        "identity": line.identity,
                        "edge": edge,
                        "price": price,
                        "anchor_ms": anchor,
                        "confirmed_ms": self.now,
                        "confirmed_seq": self.sequence,
                        "band": line.band,
                        "epoch": self.epoch,
                        "refs": tuple(sorted(set(refs + (confirmation_ref,)))),
                        "minutes": minutes,
                    }
                )

    def reject_minute(self, event, source=None, reason="REJECTED_TRADE"):
        minute = event.exchange_ms // 60000 * 60000
        self.bad_minutes.add((event.contract, event.epoch, minute))
        ref = digest(event)
        if source is not None:
            self.sources[ref] = source
        for bucket in (
            list(self.minutes.pending.values()) + self.minutes.rows[event.contract]
        ):
            if bucket["contract"] == event.contract and bucket["minute"] == minute:
                bucket["partial"] = True
        for k in ("0.05", "0.10"):
            for line in self.lines[("M", k, event.contract)]:
                if minute in line["minutes"]:
                    line["withdrawn"] = True
                    affected = []
                    engine = self.engines.get(("M", k, event.contract))
                    if engine:
                        for ident, row in engine.rows.items():
                            uses_line = (
                                "M:" + line["identity"] in row["fact"]["sources"]
                            )
                            target_groups = [
                                g
                                for g, t in row["targets"].items()
                                if set(t["refs"]) & set(line["refs"])
                            ]
                            if uses_line or target_groups:
                                affected.append(ident)
                                for group in target_groups:
                                    row.setdefault("target_coverage", {})[group] = (
                                        "WITHDRAWN_AFTER_OBSERVATION"
                                    )
                                if uses_line:
                                    row["coverage"] = "WITHDRAWN_AFTER_OBSERVATION"
                                if uses_line and row["phase"] not in DONE:
                                    engine.invalidate(
                                        ident, "MINUTE_COVERAGE_WITHDRAWN"
                                    )
                                else:
                                    engine.save(ident)
                    self.coverage_revisions.append(
                        {
                            "known_ms": self.now,
                            "source_ref": ref if source is not None else None,
                            "reason": reason,
                            "k": k,
                            "contract": event.contract,
                            "line_id": line["identity"],
                            "fact_ids": affected,
                        }
                    )

    def observe(self, value, source):
        self.sequence += 1
        self.now = max(self.now, value["received_ms"])
        kind, code = value["kind"], value.get("contract")
        self.counts[kind] += 1
        self.paths.observe(value, source, self.now)
        if kind == "GAP":
            self.gap()
            return
        if kind == "FRAME":
            ref = digest(source)
            self.sources[ref] = source
            entry = window(value["windows"]["15min"], code, "15min", ref)
            hour = window(value["windows"]["60min"], code, "60min", ref)
            spec = dict(value["spec"])
            for key in ("price_tick", "contract_size"):
                spec[key] = D(spec[key])
            self.frames[code] = (
                entry,
                hour,
                ContractSpec(**spec),
                evaluate(entry.candles, JavaEntryConfig()).side,
            )
            frame = self.frames[code]
            self.frame_valid[code] = (
                direction(*frame[:3], self.now, frame[3])[1] is None
            )
            self.volatility[code] = atr(entry.candles, 14)
            self.refresh(code)
            return
        if kind not in ("TRADE", "BBO"):
            return
        e = MarketEvent.load(value)
        if e.epoch in self.retired:
            self.rejections["RETIRED_EPOCH"] += 1
            return
        if self.epoch is not None and self.epoch != e.epoch:
            self.gap()
        self.epoch = e.epoch
        if not e.fresh(self.now):
            self.rejections["STALE_" + kind] += 1
            if kind == "TRADE":
                self.reject_minute(e, source, "STALE_TRADE")
            return
        if not self.order.accept(e):
            self.rejections[self.order.rejection + "_" + kind] += 1
            if kind == "TRADE" and self.order.rejection != "DUPLICATE":
                self.reject_minute(e, source, self.order.rejection)
            return
        ref = digest(e)
        self.sources[ref] = source
        ready = []
        sealed = self.minutes.observe(e, ref)
        for bucket in list(self.minutes.pending.values()) + sealed:
            if (
                bucket["contract"],
                bucket["epoch"],
                bucket["minute"],
            ) in self.bad_minutes:
                bucket["partial"] = True
        for minute in sealed:
            for pivot in self.minutes.pivots(code, minute["minute"]):
                ready.append(
                    (
                        pivot,
                        tuple(
                            r["minute"]
                            for r in self.minutes.rows[code]
                            if minute["minute"] - 240000
                            <= r["minute"]
                            <= minute["minute"]
                        ),
                    )
                )
        frame = self.frames.get(code)
        valid = (
            frame
            and self.frame_valid[code]
            and (
                0 <= self.now / 1000 - frame[0].candles[-1].open_time - 900 < 900
                and 0 <= self.now / 1000 - frame[1].candles[-1].open_time - 3600 < 3600
            )
        )
        if valid:
            for pivot, minutes in ready:
                self.add_line("M", code, pivot, ref, minutes)
            if ready:
                self.refresh(code, ("M",))
            if kind == "TRADE":
                delta = max(2 * frame[2].price_tick, D(".25") * self.volatility[code])
                line = self.reversal.observe(e, delta, ref)
                if line:
                    self.add_line("T", code, line, ref)
                    self.refresh(code, ("T",))
        elif kind == "TRADE":
            self.rejections["TRADE_WITHOUT_VALID_FRAME"] += 1
            self.reversal.state.pop(code, None)
        for origin in ("S", "M", "T"):
            for k in ("0.05", "0.10"):
                engine = self.engines.get((origin, k, code))
                if engine is None:
                    continue
                for observation in engine.accept(
                    e, now_ms=self.now, sequence=self.sequence
                ):
                    self.observations.append({"origin": origin, "k": k, **observation})
                    f = StructureFact.load(observation["fact"])
                    for group, target in observation["targets"].items():
                        if target["status"] != "AVAILABLE":
                            continue
                        plan = f.plan(target)
                        trigger = {
                            "plan": primitive(plan),
                            "trigger_ms": observation["trigger_ms"],
                            "exchange_ms": observation["exchange_ms"],
                            "reference": observation["reference"],
                            "invalidation_price": observation["stop"],
                        }
                        key = digest(
                            {
                                "origin": origin,
                                "k": k,
                                "event_id": observation["event_id"],
                                "target_group": group,
                            }
                        )
                        self.paths.add(key, trigger, plan.target, engine.books[code])

    def result(self):
        paths = copy.deepcopy(self.paths)
        paths.finish(self.now)
        decisions = []
        for observation in self.observations:
            f = StructureFact.load(observation["fact"])
            state = self.engines[
                (observation["origin"], observation["k"], f.contract)
            ].rows[f.fact_id]
            for group, target in observation["targets"].items():
                coverage = state.get(
                    "coverage",
                    state.get("target_coverage", {}).get(
                        group, "AS_OBSERVED_NO_KNOWN_WITHDRAWAL"
                    ),
                )
                costs = {}
                if target["status"] == "AVAILABLE":
                    plan = f.plan(target)
                    costs = {
                        str(s): primitive(
                            cost_geometry(
                                {
                                    "plan": plan,
                                    "reference_assumed": D(observation["reference"]),
                                    "stop": D(observation["stop"]),
                                },
                                s,
                            )
                        )
                        for s in (D(".05"), D(".10"), D(".20"))
                    }
                decisions.append(
                    {
                        "origin": observation["origin"],
                        "k": observation["k"],
                        "event_id": observation["event_id"],
                        "fact_id": f.fact_id,
                        "model": f.model,
                        "contract": f.contract,
                        "trigger_ms": observation["trigger_ms"],
                        "target_group": group,
                        "target": target,
                        "costs": costs,
                        "order_submitted": False,
                        "coverage": coverage,
                        "path_key": digest(
                            {
                                "origin": observation["origin"],
                                "k": observation["k"],
                                "event_id": observation["event_id"],
                                "target_group": group,
                            }
                        ),
                    }
                )
        coverage_by_path = {r["path_key"]: r["coverage"] for r in decisions}
        for path in paths.results:
            path["coverage"] = coverage_by_path.get(path["key"], "UNKNOWN")
        summaries = []
        for origin in ("S", "M", "T"):
            for k in ("0.05", "0.10"):
                for group in TARGET_GROUPS:
                    rows = [
                        r
                        for r in decisions
                        if (r["origin"], r["k"], r["target_group"])
                        == (origin, k, group)
                    ]
                    for slip in ("0.05", "0.10", "0.20"):
                        vals = [
                            r["costs"]
                            .get(slip, {})
                            .get("net_r_excluding_unknown_funding")
                            for r in rows
                            if r["coverage"] == "AS_OBSERVED_NO_KNOWN_WITHDRAWAL"
                        ]
                        summaries.append(
                            {
                                "origin": origin,
                                "k": k,
                                "target_group": group,
                                "slippage_atr": slip,
                                "observed_patterns": len(rows),
                                "coverage_withdrawn": sum(
                                    r["coverage"] != "AS_OBSERVED_NO_KNOWN_WITHDRAWAL"
                                    for r in rows
                                ),
                                "target_status": dict(
                                    Counter(r["target"]["status"] for r in rows)
                                ),
                                "valid_geometry": sum(v is not None for v in vals),
                                "net_r_pass": {
                                    str(t): sum(
                                        v is not None and D(v) >= t for v in vals
                                    )
                                    for t in (D(1), D("1.5"), D(2))
                                },
                            }
                        )
        states = [
            {"origin": origin, "k": k, "contract": code, "fact_id": ident, "state": row}
            for (origin, k, code), engine in sorted(self.engines.items())
            for ident, row in sorted(engine.rows.items())
        ]
        transitions = [
            {"origin": origin, "k": k, "contract": code, **t}
            for (origin, k, code), engine in sorted(self.engines.items())
            for t in engine.transitions
        ]
        lines = [
            {"origin": origin, "k": k, "contract": code, "line": line}
            for (origin, k, code), rows in sorted(self.lines.items())
            for line in rows
        ]
        data = primitive(
            {
                "scope": "FACT_PATTERNS_AND_ASSUMED_GEOMETRY_ONLY",
                "counts": dict(self.counts),
                "rejections": dict(self.rejections),
                "observations": self.observations,
                "decisions": decisions,
                "summaries": summaries,
                "states": states,
                "transitions": transitions,
                "local_lines": lines,
                "generations": self.generations,
                "coverage_revisions": self.coverage_revisions,
                "quote_paths": paths.results,
                "actual_orders": 0,
                "realized_net_pnl": None,
                "drawdown": None,
                "missing": [
                    "No DEPTH/IOC latency/fills",
                    "No independent account SCAN/budget/exit replay",
                    "Funding/fees coverage incomplete",
                    "No independent holdout",
                ],
            }
        )
        self.sources.update(self.paths.source_refs)
        refs = set()

        def collect(value):
            if isinstance(value, dict):
                for key, v in value.items():
                    if key in (
                        "refs",
                        "evidence_refs",
                        "input_refs",
                        "trade_refs",
                        "confirmations",
                    ):
                        refs.update(v)
                    elif (
                        key in ("ref", "first_stage_ref", "book_ref", "source_ref")
                        and v
                    ):
                        refs.add(v)
                    collect(v)
            elif isinstance(value, list):
                for v in value:
                    collect(v)

        collect(data)
        if not refs <= self.sources.keys():
            raise ValueError("unresolved provenance")
        data["source_refs"] = {ref: self.sources[ref] for ref in sorted(refs)}
        return data


def replay(path):
    study = Study()
    for row in read_tape(path):
        if row["type"] == "EVENT":
            study.observe(row["observation"], row["source_ref"])
    return study.result()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--protocol", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    protocol = json.loads(args.protocol.read_text())
    path = Path(protocol["input_tape"])
    if hashlib.sha256(path.read_bytes()).hexdigest() != protocol["input_sha256"]:
        raise ValueError("input hash mismatch")
    result = replay(path)
    result["protocol_hash"] = digest(protocol)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(gzip.compress(canonical(result), mtime=0))
    print(
        json.dumps(
            [r for r in result["summaries"] if r["slippage_atr"] == "0.10"],
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
