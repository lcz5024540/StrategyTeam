"""Confirmed S/J1_BOX32 adapter for the existing single-writer execution pipeline.

Facts and executable intents have distinct durable namespaces. A crash between
fact observation and plan creation loses that opportunity, never replays it.
"""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal as D

from ..models import TrendSide
from ..research.store import digest, primitive
from .feed import EventOrder
from .plans import SBOX, RealtimeTrigger, StructurePlan, TriggerEngine
from .storage import TERMINAL
from .structure_events import StructureEventEngine
from .structure_facts import FactObserver, StructureFact


class SBoxEngine(TriggerEngine):
    def __init__(self, store, instance, *, parameters):
        if parameters.candidate != SBOX:
            raise ValueError("SBOX engine requires explicit confirmed parameters")
        self.store, self.instance, self.parameters = store, instance, parameters
        self.rows = store.all(instance)
        self.dirty = set()
        self.sequence = 0
        self.action_order = EventOrder()
        self.generation_audit = []
        self.fact_instance = instance + ":SBOX_FACT_V1"
        if len(self.fact_instance) > 96:
            raise ValueError("SBOX fact namespace exceeds instance-key capacity")
        self.observer = FactObserver(store, self.fact_instance, parameters, "S")
        self.books = self.observer.books
        self.frames = self.observer.frames
        self.disconnect()
        for ident, row in self.observer.rows.items():
            if (
                row["phase"] == "OBSERVED"
                and self.plan_id(ident) not in self.rows
                and "decision" not in row
            ):
                row["decision"] = {
                    "phase": "REJECTED",
                    "reason": "INTERRUPTED_BEFORE_EXECUTABLE_PLAN",
                }
                self.observer.save(ident)

    @staticmethod
    def plan_id(fact_id):
        return digest({"candidate": SBOX, "fact_id": fact_id})

    def _save(self, ident):
        self.dirty.add(ident)
        self.store.save(self.instance, ident, self.rows[ident])
        self.dirty.discard(ident)

    def flush(self):
        self.observer.flush()
        for ident in tuple(self.dirty):
            self._save(ident)

    def disconnect(self):
        self.observer.disconnect()
        self.action_order.reset()
        for ident, row in self.rows.items():
            if row["phase"] not in TERMINAL:
                row.update(phase="REJECTED", reason="SBOX_TRIGGER_INTERRUPTED")
                self._save(ident)

    def refresh_frame(self, entry, trend, contract, *, now_ms, signal_side):
        self.flush()
        facts = self.observer.refresh(
            entry,
            trend,
            contract,
            now_ms=now_ms,
            sequence=self.sequence,
            signal_side=signal_side,
        )
        for ident, row in self.rows.items():
            if (
                row["phase"] == "TRIGGERED"
                and row["plan"]["contract"] == contract.contract_code
            ):
                reason = StructureEventEngine._invalid(
                    self, StructurePlan.load(row["plan"]), now_ms
                )
                if reason and reason not in ("STALE_INPUT", "INCOMPLETE_INPUT"):
                    self.terminal(ident, "INVALIDATED", reason=reason)
        self.generation_audit = [
            {
                "fact": primitive(f),
                "phase": self.observer.rows[f.fact_id]["phase"],
                "targets": self.observer.rows[f.fact_id]["targets"],
                "selected_target_mode": self.parameters.target_mode,
            }
            for f in facts
        ]
        # A target-free fact is not a StructurePlan. Include it only in the audit.
        return ()

    def accept(self, event, *, now_ms):
        self.flush()
        if event.epoch in self.observer.retired:
            return ()
        if self.observer.epoch is not None and self.observer.epoch != event.epoch:
            self.disconnect()
        if not event.fresh(now_ms) or not self.action_order.accept(event):
            return ()
        self.sequence += 1
        if event.kind == "BBO":
            # Frozen targets can become ineligible before a plan exists. Keep
            # the structural fact, never restore a target after a quote crossed it.
            for ident, row in self.observer.rows.items():
                if (
                    row["fact"]["contract"] != event.contract
                    or row["phase"] not in {"ARMED", "BROKEN", "RETEST"}
                    or event.received_ms < row["started_ms"]
                    or event.exchange_ms
                    < row.get("started_exchange_ms", row["started_ms"])
                ):
                    continue
                long = row["fact"]["side"] == "LONG"
                price = event.bid if long else event.ask
                changed = False
                for target in row["targets"].values():
                    if target["status"] == "AVAILABLE" and (
                        price >= D(target["price"])
                        if long
                        else price <= D(target["price"])
                    ):
                        target.update(
                            status="TARGET_ALREADY_CROSSED",
                            crossed_by="BBO",
                            crossing_ref=digest(event),
                        )
                        changed = True
                if changed:
                    self.observer.save(ident)
        if event.kind in ("TRADE", "BBO"):
            for ident, row in self.rows.items():
                if (
                    row["phase"] != "TRIGGERED"
                    or row["plan"]["contract"] != event.contract
                ):
                    continue
                plan = StructurePlan.load(row["plan"])
                long = plan.side is TrendSide.LONG
                stop = D(row["trigger"]["invalidation_price"])
                fact = self.observer.rows.get(row["fact_id"], {})
                target_time_ok = event.received_ms >= fact.get(
                    "started_ms", row["trigger"]["trigger_ms"]
                ) and event.exchange_ms >= fact.get(
                    "started_exchange_ms", row["trigger"]["exchange_ms"]
                )
                stop_time_ok = (
                    event.received_ms >= row["trigger"]["trigger_ms"]
                    and event.exchange_ms >= row["trigger"]["exchange_ms"]
                )
                price = (
                    event.price
                    if event.kind == "TRADE"
                    else event.bid
                    if long
                    else event.ask
                )
                if target_time_ok and (
                    price >= plan.target if long else price <= plan.target
                ):
                    self.terminal(ident, "INVALIDATED", reason="TARGET_ALREADY_CROSSED")
                elif stop_time_ok and (price <= stop if long else price >= stop):
                    self.terminal(
                        ident, "INVALIDATED", reason="TRIGGER_INVALIDATION_CROSSED"
                    )
        observations = self.observer.accept(
            event, now_ms=now_ms, sequence=self.sequence
        )
        triggers = []
        for observation in observations:
            fact = StructureFact.load(observation["fact"])
            row = self.observer.rows[fact.fact_id]
            target = observation["targets"][self.parameters.target_mode]
            if target["status"] != "AVAILABLE":
                row["decision"] = {
                    "phase": "REJECTED",
                    "reason": target["status"],
                    "target_mode": self.parameters.target_mode,
                }
                self.observer.save(fact.fact_id)
                continue
            ident = self.plan_id(fact.fact_id)
            if ident in self.rows:
                continue  # Same economic structure can never manufacture a second intent.
            plan = replace(
                fact.plan(target),
                plan_id=ident,
                parameter_fingerprint=self.parameters.fingerprint,
                sources=fact.sources
                + ("RULE:" + SBOX, "TARGET:" + self.parameters.target_mode),
            )
            trigger = RealtimeTrigger(
                plan,
                observation["trigger_ms"],
                observation["exchange_ms"],
                D(observation["reference"]),
                D(observation["raw_invalidation"]),
                D(observation["stop"]),
                tuple(observation["trade_refs"]),
                observation["book_ref"],
                observation["epoch"],
            )
            self.rows[ident] = {
                "plan": plan.dump(),
                "phase": "TRIGGERED",
                "trigger": primitive(trigger),
                "fact_id": fact.fact_id,
                "fact_instance": self.fact_instance,
                "event_id": observation["event_id"],
                "candidate_rule": SBOX,
                "target_mode": self.parameters.target_mode,
                "target_assessment": primitive(target),
            }
            self._save(ident)
            row["decision"] = {"phase": "TRIGGERED", "plan_id": ident}
            self.observer.save(fact.fact_id)
            triggers.append(trigger)
        return tuple(triggers)
