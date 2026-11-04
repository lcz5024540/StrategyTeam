"""Broad structure discovery with shared execution/holding lifecycle interfaces."""
from __future__ import annotations

import copy
from decimal import Decimal as D, ROUND_FLOOR, ROUND_CEILING
from itertools import pairwise

from ..indicators import atr
from ..models import TrendSide
from ..research.store import digest, primitive
from .plans import OPPORTUNITY, RealtimeTrigger, StructurePlan
from .selection import features, rank
from .storage import TERMINAL
from .structure_events import StructureEventEngine
from .structures import levels


def input_reason(entry, contract, now_ms):
    if (entry.contract_code != contract.contract_code or entry.period != "15min"
            or not entry.is_complete or len(entry.candles) < 32
            or any(b.open_time-a.open_time != 900 for a, b in pairwise(entry.candles))):
        return "INCOMPLETE_INPUT"
    if not 0 <= now_ms // 1000 - entry.candles[-1].open_time - 900 < 900:
        return "STALE_INPUT"
    if contract.contract_status != 1 or any(
        not p.is_finite() or p <= 0 for p in (contract.price_tick, contract.contract_size)
    ):
        return "CONTRACT_UNAVAILABLE"
    return None


def discover(entry, trend, contract, *, now_ms, parameters, audit):
    reason = input_reason(entry, contract, now_ms)
    if reason:
        audit.append({"reason": reason})
        return ()
    volatility = atr(entry.candles[-32:], 14)
    if volatility <= 0:
        audit.append({"reason": "NO_ATR"})
        return ()
    band = max(2 * contract.price_tick, parameters.band_atr * volatility)
    inventory = levels(entry, contract.price_tick, parameters.band_atr)
    box_high = max(c.high for c in entry.candles[-32:])
    box_low = min(c.low for c in entry.candles[-32:])
    output = []
    for level in inventory:
        for side in (TrendSide.LONG, TrendSide.SHORT):
            long = side is TrendSide.LONG
            rejection = level["edge"] == ("LOW" if long else "HIGH")
            family = "RANGE_REJECTION" if rejection else "BREAKOUT_RETEST"
            if rejection and any(s.startswith("PIVOT") for s in level["sources"]):
                prior = [l for l in inventory if l["edge"] == level["edge"]
                         and l["anchor"] < level["anchor"]
                         and any(s.startswith("PIVOT") for s in l["sources"])]
                if prior:
                    previous = max(prior, key=lambda l: l["anchor"])
                    if (level["price"] > previous["price"] if long else
                            level["price"] < previous["price"]):
                        family = "TREND_PULLBACK"
            ident = digest({"candidate": OPPORTUNITY, "contract": contract.contract_code,
                            "side": side, "edge": level["edge"], "anchor": level["anchor"]})
            zone = level["price"] + band if long else level["price"] - band
            targets = [l["price"] for l in inventory
                       if l["edge"] == ("HIGH" if long else "LOW")
                       and (l["price"] > zone if long else l["price"] < zone)]
            kind = "VISIBLE_OPPOSING_LEVEL"
            if targets:
                target = min(targets) if long else max(targets)
            elif not rejection and level["price"] == (box_high if long else box_low):
                target = level["price"] + (box_high-box_low) * (1 if long else -1)
                kind = "OWN_BOX_WIDTH_PROJECTION"
            else:
                audit.append({"plan_id": ident, "family": family, "level": level,
                              "side": side, "reason": "NO_TARGET"})
                continue
            target = (target / contract.price_tick).to_integral_value(
                rounding=ROUND_FLOOR if long else ROUND_CEILING) * contract.price_tick
            if target <= zone if long else target >= zone:
                audit.append({"plan_id": ident, "family": family,
                              "reason": "TARGET_INSIDE_ZONE"})
                continue
            plan = StructurePlan(
                ident, contract.contract_code,
                "RT_TREND_PULLBACK" if family == "TREND_PULLBACK" else
                "RT_RANGE_REJECTION" if rejection else "RT_BREAKOUT_RETEST",
                side, level["price"], band, volatility, contract.price_tick,
                target, kind, entry.candles[-1].open_time,
                trend.candles[-1].open_time if trend and trend.candles else 0,
                level["anchor"], level["sources"] + ("RULE:" + OPPORTUNITY,
                    "FAMILY:" + family, "EDGE:" + level["edge"],
                    "SELECTOR:" + parameters.selection_variant,
                    "CALIBRATION:" + (parameters.calibration_hash or "UNKNOWN")),
                tuple(entry.evidence_refs), parameters.fingerprint, now_ms)
            output.append(plan)
            audit.append({"plan_id": ident, "family": family, "side": side,
                          "line": plan.line, "target": target, "target_kind": kind,
                          "reason": "DISCOVERED"})
    return tuple(output)


class OpportunityEngine(StructureEventEngine):
    """Opt-in state machine; inherited store/terminal contract, no legacy direction gate."""
    def __init__(self, store, instance, *, parameters):
        self.selections = {}
        self.calibration = None
        self.policy_hash = None
        super().__init__(store, instance, parameters=parameters)

    def refresh_frame(self, entry, trend, contract, *, now_ms, signal_side=None):
        self.flush()
        self.frames[contract.contract_code] = (entry, trend, contract, signal_side)
        self.generation_audit = []
        plans = discover(entry, trend, contract, now_ms=now_ms,
                         parameters=self.parameters, audit=self.generation_audit)
        for ident, row in self.rows.items():
            if row["plan"]["contract"] != contract.contract_code or row["phase"] in TERMINAL:
                continue
            reason = self._invalid(StructurePlan.load(row["plan"]), now_ms)
            if reason and reason not in {"STALE_INPUT", "INCOMPLETE_INPUT"}:
                self.terminal(ident, "INVALIDATED", reason=reason)
        for p in plans:
            if p.plan_id not in self.rows:
                self.rows[p.plan_id] = {
                    "plan": p.dump(), "phase": "WAITING", "count": 0,
                    "prior_price": None, "extreme": None, "confirmations": [],
                    "last_trade_ms": None, "candidate_rule": OPPORTUNITY,
                    "basis": {str(c.open_time): digest(c) for c in entry.candles[-32:]},
                }
                self._save(p.plan_id)
        for item in self.generation_audit:
            row = self.rows.get(item.get("plan_id"))
            if row is not None:
                item.update(phase=row["phase"], frozen_plan=row["plan"])
        return plans

    def _invalid(self, p, now_ms):
        if now_ms >= (p.anchor_bar + 32 * 900) * 1000:
            return "STRUCTURE_EXPIRED"
        frame = self.frames.get(p.contract)
        if frame is None:
            return "INCOMPLETE_INPUT"
        entry, _, contract, _ = frame
        reason = input_reason(entry, contract, now_ms)
        if reason:
            return reason
        if p.parameter_fingerprint != self.parameters.fingerprint or contract.price_tick != p.tick:
            return "PARAMETERS_OR_TICK_CHANGED"
        row = self.rows[p.plan_id]
        for candle in entry.candles:
            original = row["basis"].get(str(candle.open_time))
            if original is not None and original != digest(candle):
                return "STRUCTURE_BASIS_REVISED"
        return None

    def preflight(self, trigger, frame, now_ms):
        self.flush()
        row = self.rows.get(trigger.plan.plan_id, {})
        if row.get("phase") != "TRIGGERED":
            raise ValueError("opportunity is no longer actionable")
        reason = self._invalid(trigger.plan, now_ms)
        if reason:
            raise ValueError(reason)

    def selection(self, trigger, frame, book, now_ms, net_r):
        f = features(trigger.plan, frame["windows"], book, now_ms)
        estimate = {"status": "UNCALIBRATED", "win_probability": None, "mean_net_r": None}
        if self.parameters.calibration_hash:
            if self.calibration is None or self.calibration.artifact["hash"] != self.parameters.calibration_hash:
                raise ValueError("configured calibration not loaded")
            estimate = self.calibration.estimate(f, self.parameters.selection_variant,
                                                at_ms=now_ms, policy_hash=self.policy_hash)
        result = {"features": f, "estimate": estimate, "net_r": str(net_r),
                  "variant": self.parameters.selection_variant, "at_ms": now_ms,
                  "candidate_rule": OPPORTUNITY}
        self.selections[trigger.plan.plan_id] = result
        return result

    def rank_batch(self, ready):
        return rank(ready, self.selections, self.parameters.selection_variant)

    def accept(self, event, *, now_ms):
        self.flush()
        if event.epoch in self.retired_epochs:
            return ()
        if self.epoch is not None and self.epoch != event.epoch:
            self.disconnect()
        self.epoch = event.epoch
        if not event.fresh(now_ms, self.parameters.max_age_ms) or not self.order.accept(event):
            return ()
        if event.kind not in {"TRADE", "BBO"}:
            return ()
        if event.kind == "BBO":
            self.books[event.contract] = event
        book = self.books.get(event.contract)
        triggers = []
        for ident, row in self.rows.items():
            if row["plan"]["contract"] != event.contract or row["phase"] in TERMINAL:
                continue
            p = StructurePlan.load(row["plan"])
            if event.received_ms < p.created_ms or event.exchange_ms < p.created_ms:
                continue
            # Frame validation runs at refresh/preflight; only constant-time freshness per tick.
            frame = self.frames.get(p.contract)
            if frame is None or not 0 <= now_ms//1000-frame[0].candles[-1].open_time-900 < 900:
                continue
            if now_ms >= (p.anchor_bar + 32*900)*1000:
                self.terminal(ident, "INVALIDATED", reason="STRUCTURE_EXPIRED")
                continue
            long = p.side is TrendSide.LONG
            price = event.price if event.kind == "TRADE" else event.bid if long else event.ask
            if price >= p.target if long else price <= p.target:
                self.terminal(ident, "INVALIDATED", reason="TARGET_ALREADY_CROSSED")
                continue
            if row["phase"] == "TRIGGERED":
                trigger = row["trigger"]
                if event.exchange_ms >= trigger["exchange_ms"] and (
                    price <= D(trigger["invalidation_price"]) if long else
                    price >= D(trigger["invalidation_price"])
                ):
                    self.terminal(ident, "INVALIDATED", reason="TRIGGER_INVALIDATION_CROSSED")
                continue
            before = copy.deepcopy(row)
            low, high = p.line-p.band, p.line+p.band
            favorable = price > high if long else price < low
            if row["last_trade_ms"] is not None and event.exchange_ms-row["last_trade_ms"] > 2000:
                row.update(count=0, confirmations=[], last_trade_ms=None)
            if event.kind == "TRADE":
                zone = low <= price <= high
                previous = D(row["prior_price"]) if row["prior_price"] is not None else None
                rejection = p.model != "RT_BREAKOUT_RETEST"
                if row["phase"] == "WAITING":
                    if rejection:
                        approach = previous is not None and (previous > high if long else previous < low)
                        if zone and approach:
                            row.update(phase="ARMED", extreme=str(price), count=0, confirmations=[])
                    elif favorable:
                        self._confirm(row, event)
                        if row["count"] >= 2:
                            row.update(phase="BROKEN", count=0, confirmations=[])
                    else:
                        row.update(count=0, confirmations=[])
                elif row["phase"] == "BROKEN":
                    if zone:
                        row.update(phase="RETEST", extreme=str(price), count=0, confirmations=[])
                elif row["phase"] in {"ARMED", "RETEST"}:
                    row["extreme"] = str(min(D(row["extreme"]), price) if long else
                                         max(D(row["extreme"]), price))
                    if favorable:
                        self._confirm(row, event)
                    else:
                        row.update(count=0, confirmations=[])
                row["last_trade_ms"] = event.exchange_ms if row["count"] else None
                row["prior_price"] = str(low-p.tick if price < low else high+p.tick if price > high else p.line)
            quote_ok = (book is not None and book.epoch == event.epoch and book.fresh(now_ms)
                        and book.exchange_ms >= p.created_ms and book.received_ms >= p.created_ms
                        and row["last_trade_ms"] is not None
                        and abs(book.exchange_ms-row["last_trade_ms"]) <= 2000
                        and (book.bid > high if long else book.ask < low))
            if row["phase"] in {"ARMED", "RETEST"} and row["count"] >= 2 and quote_ok:
                raw_stop = D(row["extreme"]) - p.band if long else D(row["extreme"]) + p.band
                reference = book.ask if long else book.bid
                stop = min(raw_stop, reference-D("1.25")*p.atr) if long else max(raw_stop, reference+D("1.25")*p.atr)
                trigger = RealtimeTrigger(p, event.received_ms, event.exchange_ms,
                    reference, raw_stop, stop, tuple(row["confirmations"][-2:]), digest(book), event.epoch)
                row.update(phase="TRIGGERED", trigger=primitive(trigger))
                triggers.append(trigger)
            if row != before:
                self._save(ident)
        return tuple(triggers)
