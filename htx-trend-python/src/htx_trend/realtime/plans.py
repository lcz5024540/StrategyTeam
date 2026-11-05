"""Frozen structures and deterministic, restart-safe two-trade trigger automata."""

from __future__ import annotations

import copy
from dataclasses import dataclass
from decimal import Decimal as D

from ..indicators import atr, ema
from ..market_data import ContractSpec, KlineWindow
from ..models import TrendSide
from ..research.store import digest, primitive
from . import MODELS, VERSION
from .feed import EventOrder, MarketEvent
from .storage import TERMINAL

ORIGINAL = "ORIGINAL_20_32_V1"
EVENT_WINDOWS = "EVENT_WINDOWS_CONSERVATIVE_V1"
EVENT_LOCAL = "EVENT_LOCAL_CONSERVATIVE_V1"
SBOX = "SBOX_FACTS_CONSERVATIVE_V1"


@dataclass(frozen=True)
class Parameters:
    band_atr: D = D(".10")
    min_net_r: D = D("1.5")
    max_age_ms: int = 2000
    candidate: str = ORIGINAL
    target_mode: str = "J1"

    regime_timing: str = "WINDOW"
    bayes_threshold: D | None = None
    bayes_snapshot_hash: str = ""
    bayes_cost_id: str = ""

    def __post_init__(self):
        if self.candidate == "REGIME_AB_V1":
            if self.regime_timing not in ("WINDOW", "FIRST_QUOTE"):
                raise ValueError("unknown regime timing")
            if self.bayes_threshold not in (None, D(".80"), D(".90"), D(".95")):
                raise ValueError("unregistered posterior threshold")
            if self.target_mode not in ("ATR_1_1", "ATR_2_1", "ATR_2_2") or self.min_net_r != 0 or self.max_age_ms != 2000:
                raise ValueError("regime requires explicit bracket, no signal netR, 2s freshness")
            return
        if self.candidate == "INDICATOR_UNION_V1":
            if self.target_mode not in ("ATR_1_1", "ATR_2_1", "ATR_2_2") or self.min_net_r != 0 or self.max_age_ms != 2000:
                raise ValueError("union requires explicit ATR exit, no signal netR threshold and2s freshness")
            return
        if self.band_atr not in (D(".05"), D(".10")) or self.min_net_r not in (
            D(1),
            D("1.5"),
            D(2),
        ):
            raise ValueError("parameters outside the six preregistered combinations")
        if self.max_age_ms != 2000:
            raise ValueError(
                "realtime-v1 requires the registered 2-second freshness bound"
            )
        if self.candidate not in (ORIGINAL, EVENT_WINDOWS, EVENT_LOCAL, SBOX):
            raise ValueError("unknown realtime candidate rule")
        if self.candidate == SBOX:
            if (self.target_mode, self.band_atr, self.min_net_r) != (
                "J1_BOX32",
                D(".10"),
                D("1.5"),
            ):
                raise ValueError(
                    "SBOX v1 requires the confirmed J1_BOX32 / k=.10 / R=1.5"
                )
        elif self.target_mode not in ("J1", "J2") or (
            self.candidate in (ORIGINAL, EVENT_WINDOWS) and self.target_mode != "J1"
        ):
            raise ValueError("unsupported realtime target mode")

    @property
    def payload(self):
        # Keep the deployed v1 fingerprint byte-for-byte when opt-in fields are absent.
        value = {
            "band_atr": self.band_atr,
            "min_net_r": self.min_net_r,
            "max_age_ms": self.max_age_ms,
        }
        if self.candidate != ORIGINAL:
            value.update(candidate=self.candidate, target_mode=self.target_mode)
        if self.candidate == "REGIME_AB_V1":
            value.update(regime_timing=self.regime_timing, bayes_threshold=self.bayes_threshold,
                         bayes_snapshot_hash=self.bayes_snapshot_hash, bayes_cost_id=self.bayes_cost_id)
        return value

    @property
    def fingerprint(self):
        return digest({"version": VERSION, "parameters": self.payload})


GRID = tuple(
    Parameters(k, r) for k in (D(".05"), D(".10")) for r in (D(1), D("1.5"), D(2))
)


@dataclass(frozen=True)
class StructurePlan:
    plan_id: str
    contract: str
    model: str
    side: TrendSide
    line: D
    band: D
    atr: D
    tick: D
    target: D
    target_kind: str
    structure_bar: int
    trend_bar: int
    anchor_bar: int
    sources: tuple[str, ...]
    evidence_refs: tuple[str, ...]
    parameter_fingerprint: str
    created_ms: int

    def dump(self):
        return primitive(self)

    @classmethod
    def load(cls, row):
        row = dict(row)
        for name in ("line", "band", "atr", "tick", "target"):
            row[name] = D(row[name])
        row["side"] = TrendSide(row["side"])
        row["sources"], row["evidence_refs"] = (
            tuple(row["sources"]),
            tuple(row["evidence_refs"]),
        )
        if row["model"] not in MODELS and row["model"] not in ("RT_INDICATOR_UNION", "RT_REGIME_AB"):
            raise ValueError("unknown realtime model")
        return cls(**row)


@dataclass(frozen=True)
class RealtimeTrigger:
    plan: StructurePlan
    trigger_ms: int
    exchange_ms: int
    reference: D
    raw_invalidation: D
    invalidation_price: D
    trade_refs: tuple[str, ...]
    book_ref: str
    epoch: str

    @property
    def side(self):
        return self.plan.side

    @property
    def entry_price(self):
        return self.reference

    @property
    def target_low(self):
        return self.plan.target

    @property
    def target_high(self):
        return self.plan.target


def generate(
    entry: KlineWindow,
    trend: KlineWindow,
    contract: ContractSpec,
    *,
    now_ms: int,
    parameters=Parameters(),
    signal_side=TrendSide.FLAT,
) -> tuple[StructurePlan, ...]:
    if parameters.candidate == SBOX:
        raise ValueError("SBOX targets freeze at activation; use create_engine.refresh_frame")
    if parameters.candidate != ORIGINAL:
        from .structures import generate_candidates

        return generate_candidates(
            entry,
            trend,
            contract,
            now_ms=now_ms,
            parameters=parameters,
            signal_side=signal_side,
        )
    if (
        entry.period != "15min"
        or trend.period != "60min"
        or not entry.is_complete
        or not trend.is_complete
        or len(entry.candles) < 32
        or len(trend.candles) < 60
        or entry.contract_code != contract.contract_code
        or trend.contract_code != contract.contract_code
    ):
        return ()
    last, hour = entry.candles[-1], trend.candles[-1]
    now = now_ms // 1000
    if (
        not 0 <= now - (last.open_time + 900) < 900
        or not 0 <= now - (hour.open_time + 3600) < 3600
    ):
        return ()
    if (
        any(
            not v.is_finite() or v <= 0
            for v in (contract.price_tick, contract.contract_size)
        )
        or contract.contract_status != 1
    ):
        return ()
    fast, slow = (ema([c.close for c in trend.candles[-160:]], n) for n in (20, 60))
    side = (
        TrendSide.LONG
        if fast > slow
        else TrendSide.SHORT
        if fast < slow
        else TrendSide.FLAT
    )
    if side is TrendSide.FLAT or signal_side not in (TrendSide.FLAT, side):
        return ()
    volatility = atr(entry.candles, 14)
    if volatility <= 0:
        return ()
    band = max(2 * contract.price_tick, volatility * parameters.band_atr)
    levels = {}
    for n in (20, 32):
        bars = entry.candles[-n:]
        for edge, field in (("HIGH", "high"), ("LOW", "low")):
            level = (
                max(getattr(c, field) for c in bars)
                if edge == "HIGH"
                else min(getattr(c, field) for c in bars)
            )
            anchor = min(c.open_time for c in bars if getattr(c, field) == level)
            item = levels.setdefault((edge, level), {"anchor": anchor, "sources": []})
            item["anchor"] = min(anchor, item["anchor"])
            item["sources"].append(str(n) + ":" + edge)
    box_high = max(c.high for c in entry.candles[-32:])
    box_low = min(c.low for c in entry.candles[-32:])
    output = []
    for model in sorted(MODELS):
        rejection = model == "RT_RANGE_REJECTION"
        edge = (
            ("LOW" if side is TrendSide.LONG else "HIGH")
            if rejection
            else ("HIGH" if side is TrendSide.LONG else "LOW")
        )
        candidates = [
            (price, item) for (kind, price), item in levels.items() if kind == edge
        ]
        # One active candidate per model. Select the nearest boundary to the last closed price.
        line, source = min(
            candidates, key=lambda item: (abs(item[0] - last.close), item[0])
        )
        profit_levels = (
            [price for (_, price) in levels if price > line + band]
            if side is TrendSide.LONG
            else [price for (_, price) in levels if price < line - band]
        )
        if profit_levels:
            target = (
                min(profit_levels) if side is TrendSide.LONG else max(profit_levels)
            )
            target_kind = "EXISTING_BOUNDARY"
        elif not rejection:
            target = (
                2 * box_high - box_low
                if side is TrendSide.LONG
                else 2 * box_low - box_high
            )
            target_kind = "BOX32_MEASURED"
        else:
            continue
        if target <= 0:
            continue
        # No polling time, current-bar hash or ordinary price revision in economic identity.
        ident = digest(
            {
                "version": VERSION,
                "contract": contract.contract_code,
                "side": side,
                "model": model,
                "anchor_bar": source["anchor"],
                "edge": edge,
            }
        )
        output.append(
            StructurePlan(
                ident,
                contract.contract_code,
                model,
                side,
                line,
                band,
                volatility,
                contract.price_tick,
                target,
                target_kind,
                last.open_time,
                hour.open_time,
                source["anchor"],
                tuple(sorted(source["sources"])),
                tuple(entry.evidence_refs + trend.evidence_refs),
                parameters.fingerprint,
                now_ms,
            )
        )
    return tuple(output)


def create_engine(store, instance, *, parameters=None):
    parameters = Parameters() if parameters is None else parameters
    if parameters.candidate == "REGIME_AB_V1":
        from .regime import RegimeEngine
        return RegimeEngine(store, instance, parameters=parameters)
    if parameters.candidate == "INDICATOR_UNION_V1":
        from .indicator_union import IndicatorUnionEngine
        return IndicatorUnionEngine(store, instance, parameters=parameters)
    if parameters.candidate == SBOX:
        from .sbox import SBoxEngine

        return SBoxEngine(store, instance, parameters=parameters)
    if parameters.candidate == ORIGINAL:
        return TriggerEngine(store, instance, parameters=parameters)
    from .structure_events import StructureEventEngine

    return StructureEventEngine(store, instance, parameters=parameters)


class TriggerEngine:
    def __init__(self, store, instance, *, parameters=Parameters()):
        self.store, self.instance, self.parameters = store, instance, parameters
        self.rows = store.all(instance)
        self.order = EventOrder()
        self.books: dict[str, MarketEvent] = {}
        self.epoch = None
        # Process restart never resumes an incomplete two-trade confirmation.
        self.disconnect()

    def _save(self, ident):
        self.store.save(self.instance, ident, self.rows[ident])

    def refresh(self, contract, plans):
        wanted = {p.plan_id: p for p in plans}
        for ident, row in self.rows.items():
            if (
                row["plan"]["contract"] == contract
                and ident not in wanted
                and row["phase"] not in TERMINAL
            ):
                row.update(
                    phase="INVALIDATED",
                    reason="STRUCTURE_REPLACED_OR_DIRECTION_INVALID",
                )
                self._save(ident)
        for ident, plan in wanted.items():
            row = self.rows.get(ident)
            if row is None:
                self.rows[ident] = {
                    "plan": plan.dump(),
                    "phase": "WAITING",
                    "count": 0,
                    "prior_price": None,
                    "extreme": None,
                    "confirmations": [],
                    "last_trade_ms": None,
                }
                self._save(ident)
            elif row["phase"] not in TERMINAL and row["phase"] != "TRIGGERED":
                # Preserve activated hypothesis; an ordinary revision cannot give it new life.
                if row["phase"] == "WAITING":
                    row["plan"] = plan.dump()
                    self._save(ident)

    def disconnect(self):
        self.epoch = None
        self.order.reset()
        self.books.clear()
        for ident, row in self.rows.items():
            if row["phase"] not in TERMINAL:
                # A trigger not yet submitted is not durable executable evidence after a gap.
                if row["phase"] == "TRIGGERED":
                    row.update(phase="REJECTED", reason="TRIGGER_INTERRUPTED")
                else:
                    row.update(
                        phase="WAITING",
                        count=0,
                        prior_price=None,
                        extreme=None,
                        confirmations=[],
                        last_trade_ms=None,
                    )
                self._save(ident)

    def terminal(self, ident, phase, **detail):
        if phase not in TERMINAL:
            raise ValueError("invalid terminal state")
        row = self.rows[ident]
        if row["phase"] in TERMINAL and row["phase"] != phase:
            # Execution recovery may resolve a previously submitted or unknown intent only.
            if row["phase"] not in {"SUBMITTED", "UNKNOWN"}:
                raise ValueError("terminal plan cannot be reactivated")
        row.update(phase=phase, **primitive(detail))
        self._save(ident)

    def accept(self, event: MarketEvent, *, now_ms: int) -> tuple[RealtimeTrigger, ...]:
        if self.epoch != event.epoch:
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
            if (
                row["plan"]["contract"] != event.contract
                or row["phase"] in TERMINAL
                or row["phase"] == "TRIGGERED"
            ):
                continue
            before = copy.deepcopy(row)
            p = StructurePlan.load(row["plan"])
            if event.received_ms < p.created_ms:
                continue
            long = p.side is TrendSide.LONG
            price = event.price
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
                elif price < low if long else price > high:
                    row.update(phase="INVALIDATED", reason="BREAKOUT_RECLAIMED")
            elif phase == "RETEST" and (price < low if long else price > high):
                row.update(
                    phase="INVALIDATED",
                    reason="RETEST_FAILED",
                    count=0,
                    confirmations=[],
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

    @staticmethod
    def _confirm(row, event):
        row["count"] += 1
        row["confirmations"] = (row["confirmations"] + [digest(event)])[-2:]
