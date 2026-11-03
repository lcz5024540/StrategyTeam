"""Public-tape research: observed minute pivots versus trade reversal extrema.

Uses real TRADE/BBO confirmations. No orders, ledger, private API or profit claims.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import itertools
import json
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from decimal import Decimal as D
from pathlib import Path

from ..indicators import atr
from ..java_entry_contract import JavaEntryConfig, evaluate
from ..market_data import Candle, ContractSpec, KlineWindow
from ..models import TrendSide
from ..research.store import canonical, digest, primitive
from .feed import EventOrder, MarketEvent
from .ohlc_diagnostic import cost_geometry
from .plans import Parameters, TriggerEngine, generate


class EphemeralStore:
    """TriggerEngine owns offline state; this adapter never persists a trading intent."""

    def all(self, instance):
        return {}

    def save(self, instance, ident, value):
        pass


def read_tape(path):
    complete = False
    header = False
    counts = Counter()
    with gzip.open(path, "rt") as stream:
        for line in stream:
            row = json.loads(line)
            if complete:
                raise ValueError("records after export footer")
            if row["type"] == "HEADER":
                if header:
                    raise ValueError("duplicate export header")
                header = True
            elif not header:
                raise ValueError("export lacks initial header")
            elif row["type"] == "EVENT":
                if digest(row["observation"]) != row["source_ref"]["event_hash"]:
                    raise ValueError("observation hash mismatch")
                counts[row["observation"]["kind"]] += 1
            elif row["type"] == "COMPLETE":
                if dict(counts) != row["exported_counts"]:
                    raise ValueError("export event count mismatch")
                complete = True
            else:
                raise ValueError("unknown export record")
            yield row
    if not complete:
        raise ValueError("incomplete export: no verified footer")


def window(raw, code, period, ref):
    candles = []
    for value in raw["candles"]:
        value = dict(value)
        for key in ("open", "high", "low", "close", "amount", "volume", "turnover"):
            if value.get(key) is not None:
                value[key] = D(value[key])
        candles.append(Candle(**value))
    return KlineWindow(code, period, tuple(candles), 160, (ref,))


@dataclass(frozen=True)
class Line:
    kind: str
    edge: str
    price: D
    anchor_ms: int
    confirmed_ms: int
    band: D
    refs: tuple[str, ...]

    @property
    def identity(self):
        # Minute/trade confirmation of the same observed extreme shares identity.
        return digest(
            {"edge": self.edge, "price": self.price, "extreme_ms": self.anchor_ms}
        )


class Minutes:
    def __init__(self):
        self.pending = {}
        self.rows = defaultdict(list)
        self.epoch_start = {}
        self.errors = Counter()
        self.generation = 0
        self.gap_at = None

    def reset(self, at):
        self.pending.clear()
        self.epoch_start.clear()
        self.gap_at = at
        self.generation += 1
        self.errors["GAP"] += 1

    def observe(self, event, ref):
        code = event.contract
        if self.gap_at is None:
            self.gap_at = event.received_ms
        start = self.epoch_start.setdefault((code, event.epoch), self.gap_at)
        watermark = min(event.exchange_ms, event.received_ms) - 2000
        sealed = []
        for key, bucket in list(self.pending.items()):
            if key[0] == code and bucket["minute"] + 60000 <= watermark:
                bucket.update(confirmed_ms=event.received_ms, confirmed_by=ref)
                self.rows[code].append(bucket)
                sealed.append(bucket)
                del self.pending[key]
        if event.kind != "TRADE":
            return sealed
        minute = event.exchange_ms // 60000 * 60000
        if self.rows[code] and minute <= self.rows[code][-1]["minute"]:
            self.errors["LATE_SEALED_MINUTE"] += 1
            return sealed
        key = (code, minute)
        current = self.pending.setdefault(
            key,
            {
                "contract": code,
                "minute": minute,
                "epoch": event.epoch,
                "generation": self.generation,
                "partial": minute < start,
                "open": str(event.price),
                "high": str(event.price),
                "low": str(event.price),
                "close": str(event.price),
                "high_ms": event.exchange_ms,
                "low_ms": event.exchange_ms,
                "high_ref": ref,
                "low_ref": ref,
                "volume": "0",
                "count": 0,
                "refs": [],
            },
        )
        if current["epoch"] != event.epoch:
            current["partial"] = True
        if event.price > D(current["high"]):
            current.update(
                high=str(event.price), high_ms=event.exchange_ms, high_ref=ref
            )
        if event.price < D(current["low"]):
            current.update(low=str(event.price), low_ms=event.exchange_ms, low_ref=ref)
        current["close"] = str(event.price)
        current["volume"] = str(D(current["volume"]) + (event.amount or D(0)))
        current["count"] += 1
        current["refs"].append(ref)
        return sealed

    def pivots(self, code, through_minute=None):
        rows = [
            r
            for r in self.rows[code]
            if through_minute is None or r["minute"] <= through_minute
        ][-5:]
        if (
            len(rows) != 5
            or any(r["partial"] for r in rows)
            or len({(r["epoch"], r["generation"]) for r in rows}) != 1
        ):
            return ()
        if any(b["minute"] - a["minute"] != 60000 for a, b in itertools.pairwise(rows)):
            return ()
        middle = rows[2]
        output = []
        for edge, field in (("HIGH", "high"), ("LOW", "low")):
            price = D(middle[field])
            others = [D(r[field]) for r in rows if r is not middle]
            if (
                all(price > x for x in others)
                if edge == "HIGH"
                else all(price < x for x in others)
            ):
                output.append(
                    (
                        edge,
                        price,
                        middle[field + "_ms"],
                        tuple(ref for r in rows for ref in r["refs"]),
                    )
                )
        return tuple(output)


class Reversal:
    def __init__(self):
        self.state = {}
        self.resets = Counter()

    def reset(self):
        self.state.clear()

    def observe(self, event, delta, ref):
        key = event.contract
        s = self.state.get(key)
        p = event.price
        if (
            s is None
            or s["epoch"] != event.epoch
            or event.exchange_ms - s["last_ms"] > 2000
        ):
            self.resets["START_OR_TRADE_GAP"] += 1
            self.state[key] = {
                "epoch": event.epoch,
                "last_ms": event.exchange_ms,
                "mode": None,
                "high": p,
                "low": p,
                "high_ms": event.exchange_ms,
                "low_ms": event.exchange_ms,
                "high_ref": ref,
                "low_ref": ref,
                "delta": delta,
            }
            return None
        s["last_ms"] = event.exchange_ms
        if p > s["high"]:
            s.update(high=p, high_ms=event.exchange_ms, high_ref=ref)
        if p < s["low"]:
            s.update(low=p, low_ms=event.exchange_ms, low_ref=ref)
        edge = None
        if s["mode"] in (None, "DOWN") and p - s["low"] >= s["delta"]:
            edge = "LOW"
        elif s["mode"] in (None, "UP") and s["high"] - p >= s["delta"]:
            edge = "HIGH"
        if edge is None:
            return None
        field = edge.lower()
        result = (edge, s[field], s[field + "_ms"], (s[field + "_ref"], ref))
        s.update(
            mode="UP" if edge == "LOW" else "DOWN",
            high=p,
            low=p,
            high_ms=event.exchange_ms,
            low_ms=event.exchange_ms,
            high_ref=ref,
            low_ref=ref,
            delta=delta,
        )
        return result


class Research:
    def __init__(self, header):
        self.header = header
        self.frames = {}
        self.order = EventOrder()
        self.minutes = Minutes()
        self.reversal = Reversal()
        self.groups = {}
        self.lines = defaultdict(list)
        self.counters = Counter()
        self.per_contract = defaultdict(Counter)
        self.events = Counter()
        self.rejected_trade_minutes = Counter()
        self.triggers = []
        self.refmap = {}
        self.source_refs = {}
        self.book_refs = {}
        self.frame_intervals = defaultdict(list)
        self.new_lines = []
        self.paused = set()
        self.now = header["first_ms"]

    def group(self, variant, k, code):
        key = (variant, k, code)
        if key not in self.groups:
            self.groups[key] = TriggerEngine(
                EphemeralStore(), str(key), parameters=Parameters(k, D("1.5"))
            )
        return self.groups[key]

    def fresh_frame(self, code, at):
        f = self.frames.get(code)
        return bool(
            f
            and f["entry"].is_complete
            and f["trend"].is_complete
            and 0 <= at / 1000 - f["entry"].candles[-1].open_time - 900 < 900
            and 0 <= at / 1000 - f["trend"].candles[-1].open_time - 3600 < 3600
        )

    def refresh(self, code, at):
        if not self.fresh_frame(code, at):
            return
        f = self.frames[code]
        for k in (D(".05"), D(".10")):
            base = generate(
                f["entry"],
                f["trend"],
                f["spec"],
                now_ms=at,
                parameters=Parameters(k, D("1.5")),
                signal_side=f["signal"].side,
            )
            for variant in ("A", "M", "T"):
                engine = self.group(variant, k, code)
                plans = []
                for p in base:
                    if variant == "A" or p.model != "RT_RANGE_REJECTION":
                        plans.append(p)
                        continue
                    edge = "HIGH" if p.side is TrendSide.SHORT else "LOW"
                    choices = [
                        (abs(p.line - f["entry"].candles[-1].close), 0, p.plan_id, p)
                    ]
                    for line in self.lines[(variant, k, code)]:
                        if (
                            line.edge != edge
                            or line.anchor_ms / 1000
                            < f["entry"].candles[-1].open_time - 31 * 900
                        ):
                            continue
                        if any(
                            c.close > line.price + line.band
                            if edge == "HIGH"
                            else c.close < line.price - line.band
                            for c in f["entry"].candles
                            if (c.open_time + 900) * 1000 > line.confirmed_ms
                        ):
                            continue
                        ident = digest(
                            {
                                "model": p.model,
                                "contract": code,
                                "side": p.side,
                                "local_extreme": line.identity,
                            }
                        )
                        for row in engine.rows.values():
                            old = row["plan"]
                            if (
                                old["model"] == p.model
                                and D(old["line"]) == line.price
                                and old["anchor_bar"] == line.anchor_ms // 900000 * 900
                            ):
                                ident = old["plan_id"]
                                break
                        candidate = replace(
                            p,
                            plan_id=ident,
                            line=line.price,
                            anchor_bar=line.anchor_ms // 1000,
                            sources=(variant, line.identity),
                            evidence_refs=tuple(
                                sorted(set(p.evidence_refs + line.refs))
                            ),
                        )
                        choices.append(
                            (
                                abs(line.price - f["entry"].candles[-1].close),
                                1,
                                ident,
                                candidate,
                            )
                        )
                    plans.append(min(choices, key=lambda x: x[:3])[3])
                engine.refresh(code, plans)

    def add_line(self, kind, code, value, at):
        if not value or code not in self.frames:
            return
        f = self.frames[code]
        edge, price, anchor, refs = value
        for k in (D(".05"), D(".10")):
            line = Line(
                kind,
                edge,
                price,
                anchor,
                at,
                max(f["spec"].price_tick * 2, k * f["atr"]),
                refs,
            )
            key = (kind, k, code)
            if all(x.identity != line.identity for x in self.lines[key]):
                self.lines[key].append(line)
                self.new_lines.append({"contract": code, "k": k, "line": line})
        self.refresh(code, at)

    def observe(self, event, source_ref):
        ref = digest(source_ref)
        kind = event["kind"]
        if kind in ("TRADE", "FRAME"):
            self.source_refs[ref] = source_ref
        code = event.get("contract")
        self.events[kind] += 1
        self.now = max(self.now, event["received_ms"])
        if kind == "GAP":
            self.order.reset()
            self.minutes.reset(self.now)
            self.reversal.reset()
            for engine in self.groups.values():
                engine.disconnect()
            self.counters["GAP"] += 1
            return
        if kind == "FRAME":
            spec = dict(event["spec"])
            for key in ("price_tick", "contract_size"):
                spec[key] = D(spec[key])
            entry = window(event["windows"]["15min"], code, "15min", ref)
            trend = window(event["windows"]["60min"], code, "60min", ref)
            self.frames[code] = {
                "entry": entry,
                "trend": trend,
                "spec": ContractSpec(**spec),
                "atr": atr(entry.candles, 14),
                "signal": evaluate(entry.candles, JavaEntryConfig()),
                "ref": ref,
            }
            end = min(
                (entry.candles[-1].open_time + 1800) * 1000,
                (trend.candles[-1].open_time + 7200) * 1000,
                self.header["last_ms"],
            )
            if end > self.now:
                self.frame_intervals[code].append((self.now, end))
            self.refresh(code, self.now)
            return
        if kind not in ("TRADE", "BBO"):
            return
        e = MarketEvent.load(event)
        if not e.fresh(self.now):
            self.counters["STALE_" + kind] += 1
            if kind == "TRADE":
                self.rejected_trade_minutes[
                    f"{code}:{e.exchange_ms // 60000 * 60}"
                ] += 1
            self.per_contract[code]["STALE_" + kind] += 1
            return
        if not self.order.accept(e):
            self.counters[self.order.rejection + "_" + kind] += 1
            if kind == "TRADE":
                self.rejected_trade_minutes[
                    f"{code}:{e.exchange_ms // 60000 * 60}"
                ] += 1
            return
        self.per_contract[code]["ACCEPTED_" + kind] += 1
        if kind == "TRADE":
            self.refmap[digest(e)] = ref
        else:
            self.book_refs[code] = (digest(e), ref, source_ref)
        for minute in self.minutes.observe(e, ref):
            self.source_refs[ref] = source_ref
            for value in self.minutes.pivots(code, minute["minute"]):
                self.add_line("M", code, value, self.now)
        if kind == "TRADE" and code in self.frames:
            f = self.frames[code]
            delta = max(f["spec"].price_tick * 2, D(".25") * f["atr"])
            self.add_line("T", code, self.reversal.observe(e, delta, ref), self.now)
        if not self.fresh_frame(code, self.now):
            if kind == "TRADE":
                self.per_contract[code]["TRADE_WITHOUT_FRESH_FRAME"] += 1
            if code not in self.paused:
                for k in (D(".05"), D(".10")):
                    for v in ("A", "M", "T"):
                        self.group(v, k, code).disconnect()
                self.paused.add(code)
            return
        self.paused.discard(code)
        for k in (D(".05"), D(".10")):
            for variant in ("A", "M", "T"):
                engine = self.group(variant, k, code)
                for trigger in engine.accept(e, now_ms=self.now):
                    op = {
                        "plan": trigger.plan,
                        "reference_assumed": trigger.reference,
                        "stop": trigger.invalidation_price,
                    }
                    costs = {
                        str(s): cost_geometry(op, s)
                        for s in (D(".05"), D(".10"), D(".20"))
                    }
                    if code in self.book_refs:
                        self.source_refs[self.book_refs[code][1]] = self.book_refs[
                            code
                        ][2]
                    for cost in costs.values():
                        if "reward_per_eth_assumed" in cost:
                            cost["reward_per_base_unit_assumed"] = cost.pop(
                                "reward_per_eth_assumed"
                            )
                        if "risk_per_eth_assumed" in cost:
                            cost["risk_per_base_unit_assumed"] = cost.pop(
                                "risk_per_eth_assumed"
                            )
                    self.triggers.append(
                        {
                            "variant": variant,
                            "k": k,
                            "contract": code,
                            "trigger": primitive(trigger),
                            "cost_assumptions": costs,
                            "trade_source_refs": [
                                self.refmap.get(h) for h in trigger.trade_refs
                            ],
                            "book_source_ref": self.book_refs.get(code),
                            "frame_source_ref": self.frames[code]["ref"],
                            "order_submitted": False,
                        }
                    )
                    engine.terminal(
                        trigger.plan.plan_id,
                        "REJECTED",
                        reason="RESEARCH_ONLY_NO_ORDER",
                    )


def official_comparison(minutes, official, header):
    output = []
    for source in official["rows"]:
        code = source["contract"]
        observed = {x["minute"] // 1000: x for x in minutes.rows[code]}
        counts = Counter()
        details = []
        for row in source.get("response", {}).get("data", []):
            t = int(row["id"])
            if t * 1000 < header["first_ms"] or (t + 60) * 1000 > header["last_ms"]:
                continue
            o = observed.get(t)
            if not o:
                status = "NO_OBSERVED_MINUTE"
            elif o["partial"]:
                status = "PARTIAL_MINUTE"
            else:
                equal = all(
                    D(str(row[k])) == D(o[k]) for k in ("open", "high", "low", "close")
                )
                volume = D(str(row["vol"])) == D(o["volume"])
                status = "OHLC_VOLUME_MATCH" if equal and volume else "MISMATCH"
            counts[status] += 1
            details.append(
                {
                    "minute": t,
                    "status": status,
                    "official_count": row.get("count"),
                    "observed_count": o["count"] if o else None,
                    "official_volume": str(row.get("vol")),
                    "observed_volume": o["volume"] if o else None,
                }
            )
        output.append(
            {
                "contract": code,
                "counts": dict(counts),
                "minutes": details,
                "fetch_error": source.get("error"),
                "response_status": source.get("response", {}).get("status"),
            }
        )
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("tape", "official", "protocol", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    research = None
    footer = None
    for row in read_tape(args.tape):
        if row["type"] == "HEADER":
            research = Research(row)
        elif row["type"] == "COMPLETE":
            footer = row
        else:
            research.observe(row["observation"], row["source_ref"])
    if research is None or footer is None:
        raise ValueError("missing export boundary")
    official = json.loads(gzip.decompress(Path(args.official).read_bytes()))
    result = {
        "status": "RESEARCH_ONLY_NOT_ELIGIBLE",
        "orders_submitted": 0,
        "header": research.header,
        "export_footer": footer,
        "events": dict(research.events),
        "rejections": dict(research.counters),
        "per_contract": dict(research.per_contract),
        "frame_fresh_intervals": dict(research.frame_intervals),
        "minute_errors": dict(research.minutes.errors),
        "rejected_trade_minutes": dict(research.rejected_trade_minutes),
        "minutes": dict(research.minutes.rows),
        "reversal_resets": dict(research.reversal.resets),
        "lines": research.new_lines,
        "triggers": research.triggers,
        "source_refs": research.source_refs,
        "unfinished_minutes": len(research.minutes.pending),
        "official_minute_check": official_comparison(
            research.minutes, official, research.header
        ),
        "strategy_config": primitive(JavaEntryConfig()),
        "config_scope": "CODE_DEFAULTS_NOT_VERIFIED_PRIVATE_RUNTIME_CONFIG",
        "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "protocol_sha256": hashlib.sha256(Path(args.protocol).read_bytes()).hexdigest(),
        "tape_sha256": hashlib.sha256(Path(args.tape).read_bytes()).hexdigest(),
        "official_sha256": hashlib.sha256(Path(args.official).read_bytes()).hexdigest(),
    }
    summaries = []
    for v in ("A", "M", "T"):
        for k in (D(".05"), D(".10")):
            selected = [
                t for t in research.triggers if t["variant"] == v and t["k"] == k
            ]
            for limit in (D(1), D("1.5"), D(2)):
                passed = [
                    t
                    for t in selected
                    if (
                        t["cost_assumptions"]["0.10"]["net_r_excluding_unknown_funding"]
                        or D(-1)
                    )
                    >= limit
                ]
                summaries.append(
                    {
                        "variant": v,
                        "k": k,
                        "min_net_r": limit,
                        "real_event_triggers": len(selected),
                        "r_screen_only": len(passed),
                        "contracts": dict(Counter(t["contract"] for t in selected)),
                    }
                )
    result["summaries"] = summaries
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(gzip.compress(canonical(result), mtime=0))
    print(
        json.dumps(
            primitive(
                {
                    "summaries": summaries,
                    "rejections": research.counters,
                    "reversal_resets": research.reversal.resets,
                    "line_counts": Counter(x["line"].kind for x in research.new_lines),
                    "official_checks": {
                        x["contract"]: x["counts"]
                        for x in result["official_minute_check"]
                    },
                }
            ),
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
