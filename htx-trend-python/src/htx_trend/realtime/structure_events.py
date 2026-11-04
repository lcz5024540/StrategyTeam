"""Opt-in conservative event lifecycle; the existing broker remains the only writer."""

from __future__ import annotations

import copy
from decimal import Decimal as D

from ..indicators import atr
from ..models import TrendSide
from ..research.store import digest, primitive
from .feed import MarketEvent
from .plans import RealtimeTrigger, StructurePlan, TriggerEngine
from .storage import TERMINAL
from .structures import direction, generate_candidates


class StructureEventEngine(TriggerEngine):
    def __init__(self, store, instance, *, parameters):
        self.frames = {}
        self.retired_epochs = set()
        self.dirty = set()
        self.generation_audit = []
        super().__init__(store, instance, parameters=parameters)

    def _save(self, ident):
        self.dirty.add(ident)
        super()._save(ident)
        self.dirty.discard(ident)

    def flush(self):
        for ident in tuple(self.dirty):
            self._save(ident)

    def disconnect(self):
        if self.epoch is not None:
            self.retired_epochs.add(self.epoch)
        self.epoch = None
        self.order.reset()
        self.books.clear()
        for ident, row in self.rows.items():
            if row["phase"] not in TERMINAL:
                row.update(
                    phase="INVALIDATED",
                    reason="CONSERVATIVE_CONNECTION_INTERRUPTED",
                    count=0,
                    confirmations=[],
                )
                self._save(ident)

    def refresh_frame(self, entry, trend, contract, *, now_ms, signal_side):
        self.flush()
        self.frames[contract.contract_code] = (entry, trend, contract, signal_side)
        self.generation_audit = []
        plans = generate_candidates(
            entry,
            trend,
            contract,
            now_ms=now_ms,
            parameters=self.parameters,
            signal_side=signal_side,
            audit=self.generation_audit,
        )
        self.refresh(contract.contract_code, plans, now_ms=now_ms)
        return plans

    def _invalid(self, p, now_ms):
        if now_ms >= (p.anchor_bar + 32 * 900) * 1000:
            return "ORIGINAL_ANCHOR_EXPIRED"
        frame = self.frames.get(p.contract)
        if frame is None:
            return None  # Direct refresh is used only by deterministic unit fixtures.
        entry, trend, spec, signal_side = frame
        side, reason = direction(entry, trend, spec, now_ms, signal_side)
        if reason or side is not p.side:
            return reason or "DIRECTION_CHANGED"
        if (
            p.parameter_fingerprint != self.parameters.fingerprint
            or spec.price_tick != p.tick
        ):
            return "PARAMETERS_OR_TICK_CHANGED"
        anchor = next((c for c in entry.candles if c.open_time == p.anchor_bar), None)
        rejection = p.model == "RT_RANGE_REJECTION"
        high = (p.side is TrendSide.SHORT) == rejection
        if anchor is None or (anchor.high if high else anchor.low) != p.line:
            return "STRUCTURE_BASIS_REVISED"
        valid_sources = []
        for source in p.sources:
            if source.startswith(("20:", "32:")):
                length = int(source.split(":")[0])
                original = [
                    c
                    for c in entry.candles
                    if p.structure_bar - (length - 1) * 900
                    <= c.open_time
                    <= p.structure_bar
                ]
                if len(original) == length:
                    extreme = (
                        max(c.high for c in original)
                        if high
                        else min(c.low for c in original)
                    )
                    valid_sources.append(extreme == p.line)
            elif source.startswith("PIVOT_2_2:"):
                index = entry.candles.index(anchor)
                group = entry.candles[index - 2 : index + 3]
                valid = (
                    index >= 2
                    and len(group) == 5
                    and all(
                        (anchor.high > c.high if high else anchor.low < c.low)
                        for c in group
                        if c.open_time != anchor.open_time
                    )
                )
                if valid:
                    historical_band = max(
                        2 * p.tick,
                        self.parameters.band_atr * atr(entry.candles[: index + 3], 14),
                    )
                    valid = not any(
                        (
                            c.close > p.line + historical_band
                            if high
                            else c.close < p.line - historical_band
                        )
                        for c in entry.candles[index + 3 :]
                        if (c.open_time + 900) * 1000 <= p.created_ms
                    )
                valid_sources.append(valid)
        if valid_sources and not any(valid_sources):
            return "STRUCTURE_BASIS_REVISED"
        return None

    def refresh(self, contract, plans, *, now_ms=None):
        self.flush()
        wanted = {p.plan_id: p for p in plans}
        for ident, row in self.rows.items():
            if row["plan"]["contract"] != contract or row["phase"] in TERMINAL:
                continue
            p = StructurePlan.load(row["plan"])
            reason = self._invalid(p, now_ms) if now_ms is not None else None
            if reason in ("STALE_INPUT", "INCOMPLETE_INPUT"):
                continue
            if reason is None and row["phase"] == "WAITING" and ident not in wanted:
                reason = "UNSTARTED_STRUCTURE_REPLACED"
            frame = self.frames.get(contract)
            if reason is None and frame and row.get("event_started_ms") is not None:
                for candle in frame[0].candles:
                    # Only closes after the event count; no pre-event extrema are used.
                    if (candle.open_time + 900) * 1000 <= row["event_started_ms"]:
                        continue
                    reclaimed = (
                        candle.close < p.line - p.band
                        if p.side is TrendSide.LONG
                        else candle.close > p.line + p.band
                    )
                    if reclaimed:
                        reason = "CLOSED_BAR_RECLAIMED"
                        break
            if reason:
                row.update(
                    phase="INVALIDATED", reason=reason, count=0, confirmations=[]
                )
                self._save(ident)
        for ident, p in wanted.items():
            row = self.rows.get(ident)
            if row is None:
                self.rows[ident] = {
                    "plan": p.dump(),
                    "phase": "WAITING",
                    "count": 0,
                    "prior_price": None,
                    "extreme": None,
                    "confirmations": [],
                    "last_trade_ms": None,
                    "candidate_rule": self.parameters.candidate,
                    "target_mode": self.parameters.target_mode,
                    "event_started_ms": None,
                    "source_anchor_expires_ms": (p.anchor_bar + 32 * 900) * 1000,
                }
                self._save(ident)
            elif row["phase"] == "WAITING":
                previous = StructurePlan.load(row["plan"])
                if (
                    previous.line,
                    previous.band,
                    previous.target,
                    previous.atr,
                    previous.tick,
                ) != (p.line, p.band, p.target, p.atr, p.tick):
                    row.update(
                        count=0, confirmations=[], prior_price=None, last_trade_ms=None
                    )
                row["plan"] = p.dump()
                self._save(ident)

    def accept(self, event: MarketEvent, *, now_ms: int) -> tuple[RealtimeTrigger, ...]:
        self.flush()
        if event.epoch in self.retired_epochs:
            return ()
        if self.epoch is not None and self.epoch != event.epoch:
            self.disconnect()
        self.epoch = event.epoch
        if not event.fresh(now_ms, self.parameters.max_age_ms) or not self.order.accept(
            event
        ):
            return ()
        if event.kind == "BBO":
            self.books[event.contract] = event
            return ()
        if event.kind != "TRADE":
            return ()
        book = self.books.get(event.contract)
        triggers = []
        for ident, row in self.rows.items():
            if row["plan"]["contract"] != event.contract or row["phase"] in TERMINAL:
                continue
            before = copy.deepcopy(row)
            p = StructurePlan.load(row["plan"])
            # Structural validity is computed once at refresh, never EMA/pivots per tick.
            if now_ms >= (p.anchor_bar + 32 * 900) * 1000:
                row.update(phase="INVALIDATED", reason="ORIGINAL_ANCHOR_EXPIRED")
                self._save(ident)
                continue
            frame = self.frames.get(p.contract)
            if frame and not (
                0 <= now_ms / 1000 - frame[0].candles[-1].open_time - 900 < 900
                and 0 <= now_ms / 1000 - frame[1].candles[-1].open_time - 3600 < 3600
            ):
                row.update(count=0, confirmations=[], last_trade_ms=None)
                if row != before:
                    self._save(ident)
                continue
            if event.received_ms < p.created_ms:
                continue
            long = p.side is TrendSide.LONG
            price = event.price
            if price >= p.target if long else price <= p.target:
                row.update(phase="INVALIDATED", reason="TARGET_ALREADY_CROSSED")
                self._save(ident)
                continue
            if row["phase"] == "TRIGGERED":
                frozen_stop = row.get("trigger", {}).get("invalidation_price")
                if frozen_stop is not None and (
                    price <= D(frozen_stop) if long else price >= D(frozen_stop)
                ):
                    row.update(
                        phase="INVALIDATED", reason="TRIGGER_INVALIDATION_CROSSED"
                    )
                    self._save(ident)
                continue
            low, high = p.line - p.band, p.line + p.band
            previous = D(row["prior_price"]) if row["prior_price"] is not None else None
            rejection = p.model == "RT_RANGE_REJECTION"
            favorable = price > high if long else price < low
            zone = low <= price <= high
            phase = row["phase"]
            last_ms = row["last_trade_ms"]
            if (
                last_ms is not None
                and event.exchange_ms - last_ms > self.parameters.max_age_ms
            ):
                row.update(count=0, confirmations=[])
            if phase == "WAITING":
                if rejection:
                    safe_approach = previous is not None and (
                        previous > high if long else previous < low
                    )
                    if zone and safe_approach:
                        row.update(
                            phase="ARMED", extreme=str(price), count=0, confirmations=[]
                        )
                elif favorable:
                    self._confirm(row, event)
                    if row["count"] >= 2:
                        row.update(phase="BROKEN", count=0, confirmations=[])
                else:
                    row.update(count=0, confirmations=[])
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
                    self._confirm(row, event)
                    quote_ok = (
                        book is not None
                        and book.epoch == event.epoch
                        and book.fresh(now_ms)
                        and abs(book.exchange_ms - event.exchange_ms)
                        <= self.parameters.max_age_ms
                        and (book.bid > high if long else book.ask < low)
                    )
                    if row["count"] >= 2 and quote_ok:
                        raw_stop = (
                            D(row["extreme"]) - p.band
                            if long
                            else D(row["extreme"]) + p.band
                        )
                        reference = book.ask if long else book.bid
                        stop = (
                            min(raw_stop, reference - D("1.25") * p.atr)
                            if long
                            else max(raw_stop, reference + D("1.25") * p.atr)
                        )
                        triggers.append(
                            RealtimeTrigger(
                                p,
                                event.received_ms,
                                event.exchange_ms,
                                reference,
                                raw_stop,
                                stop,
                                tuple(row["confirmations"][-2:]),
                                digest(book),
                                event.epoch,
                            )
                        )
                        row.update(phase="TRIGGERED", trigger=primitive(triggers[-1]))
                else:
                    row.update(count=0, confirmations=[])
            if (
                row["phase"] in ("ARMED", "BROKEN", "RETEST")
                and row.get("event_started_ms") is None
            ):
                row["event_started_ms"] = event.received_ms
                row["event_source_ref"] = digest(event)
                row["event_id"] = digest(
                    {
                        "plan_id": ident,
                        "epoch": event.epoch,
                        "first_stage_event": digest(event),
                    }
                )
                row["frozen_target"] = str(p.target)
                row["frozen_band"] = str(p.band)
            # The next approach only needs the side of the zone, not every tick.
            row["prior_price"] = str(
                low - p.tick
                if price < low
                else high + p.tick
                if price > high
                else p.line
            )
            row["last_trade_ms"] = event.exchange_ms if row["count"] else None
            if row != before:
                self._save(ident)
        return tuple(triggers)
