"""Research-only local levels. No imports from a worker, no executable entry path."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import itertools
import json
from collections import Counter
from dataclasses import dataclass, replace
from decimal import Decimal as D
from functools import lru_cache
from pathlib import Path

from ..indicators import atr
from ..models import TrendSide
from ..research.store import digest, primitive
from . import VERSION
from .ohlc_diagnostic import (
    SCOPE,
    barriers,
    cost_geometry,
    load_archive,
    scenario_points,
    snapshots,
    visible_window,
    walk,
)
from .plans import GRID


@dataclass(frozen=True)
class LocalLevel:
    identity: str
    kind: str
    edge: str
    price: D
    band: D
    anchor_bar: int
    confirmation_bar: int
    available: float
    refs: tuple[str, ...]
    parent_ids: tuple[str, ...] = ()


class Study:
    def __init__(self, bars, tick=D(".01")):
        self.bars = tuple(bars)
        self.tick = tick
        self.refs = {r.ref: r for r in bars}
        # Caches belong to this finite study; no class-level cache retains old datasets.
        for name in ("window", "band", "pivots", "failures"):
            setattr(self, name, lru_cache(maxsize=8192)(getattr(self, name)))

    def window(self, at):
        return visible_window(self.bars, "15min", at)

    def band(self, at, k):
        candles = self.window(at).candles
        if len(candles) < 15:
            return None
        return max(self.tick * 2, atr(candles, 14) * k)

    def valid(self, level, candles):
        return all(
            c.close <= level.price + level.band
            if level.edge == "HIGH"
            else c.close >= level.price - level.band
            for c in candles
            if c.open_time > level.confirmation_bar
        )

    def pivots(self, at, k):
        window = self.window(at)
        candles = window.candles
        if len(candles) < 5:
            return ()
        rows = [self.refs[r] for r in window.evidence_refs]
        cutoff = candles[-1].open_time - 31 * 900
        output = []
        for i in range(2, len(candles) - 2):
            center = candles[i]
            if center.open_time < cutoff:
                continue
            group = rows[i - 2 : i + 3]
            if any(
                b.candle.open_time - a.candle.open_time != 900
                for a, b in itertools.pairwise(group)
            ):
                continue
            available = max(r.available for r in group)
            band = self.band(available, k)
            if band is None:
                continue
            for edge, field in (("HIGH", "high"), ("LOW", "low")):
                price = getattr(center, field)
                others = [
                    getattr(candles[j], field) for j in range(i - 2, i + 3) if j != i
                ]
                if not (
                    all(price > x for x in others)
                    if edge == "HIGH"
                    else all(price < x for x in others)
                ):
                    continue
                level = LocalLevel(
                    digest(
                        {"kind": "PIVOT_2_2", "edge": edge, "anchor": center.open_time}
                    ),
                    "PIVOT_2_2",
                    edge,
                    price,
                    band,
                    center.open_time,
                    candles[i + 2].open_time,
                    available,
                    tuple(r.ref for r in group),
                )
                if self.valid(level, candles):
                    output.append(level)
        return tuple(output)

    def failures(self, at, k):
        window = self.window(at)
        candles = window.candles
        output = []
        for c, ref in zip(candles[-32:], window.evidence_refs[-32:]):
            old = self.pivots(c.open_time, k)
            band_before = self.band(c.open_time, k)
            if band_before is None:
                continue
            confirmed = self.refs[ref].available
            band = self.band(confirmed, k)
            if band is None:
                continue
            for edge in ("HIGH", "LOW"):
                parents = [
                    p
                    for p in old
                    if p.edge == edge
                    and (
                        (
                            c.high > p.price + band_before
                            and c.close < p.price - band_before
                        )
                        if edge == "HIGH"
                        else (
                            c.low < p.price - band_before
                            and c.close > p.price + band_before
                        )
                    )
                ]
                if not parents:
                    continue
                level = LocalLevel(
                    digest(
                        {
                            "kind": "FAILED_LOCAL_BREAK",
                            "edge": edge,
                            "anchor": c.open_time,
                        }
                    ),
                    "FAILED_LOCAL_BREAK",
                    edge,
                    c.high if edge == "HIGH" else c.low,
                    band,
                    c.open_time,
                    c.open_time,
                    confirmed,
                    tuple(sorted({ref} | {r for p in parents for r in p.refs})),
                    tuple(sorted(p.identity for p in parents)),
                )
                if self.valid(level, candles):
                    output.append(level)
        return tuple(output)

    def candidate_plans(self, scan, parameters, variant):
        if variant == "A":
            return scan["plans"]
        levels = (
            self.pivots(scan["at"], parameters.band_atr)
            if variant == "B"
            else self.failures(scan["at"], parameters.band_atr)
        )
        last = self.window(scan["at"]).candles[-1]
        result = []
        for base in scan["plans"]:
            if base.model != "RT_RANGE_REJECTION":
                result.append(base)
                continue
            edge = "HIGH" if base.side is TrendSide.SHORT else "LOW"
            choices = [(abs(base.line - last.close), 0, base.plan_id, base)]
            for level in levels:
                if level.edge != edge:
                    continue
                p = replace(
                    base,
                    plan_id=digest(
                        {
                            "version": VERSION,
                            "contract": base.contract,
                            "side": base.side,
                            "model": base.model,
                            "anchor_bar": level.anchor_bar,
                            "edge": edge,
                        }
                    ),
                    line=level.price,
                    anchor_bar=level.anchor_bar,
                    sources=(level.kind, level.identity),
                    evidence_refs=tuple(sorted(set(base.evidence_refs + level.refs))),
                )
                choices.append((abs(p.line - last.close), 1, p.plan_id, p))
            result.append(min(choices, key=lambda c: c[:3])[3])
        return tuple(result)

    def target_sensitivity(self, op, parameters):
        # Only levels known when the plan was generated, not at a future hypothetical entry.
        p = op["plan"]
        levels = self.pivots(p.created_ms / 1000, parameters.band_atr)
        long = p.side is TrendSide.LONG
        candidates = [
            x
            for x in levels
            if x.edge == ("HIGH" if long else "LOW")
            and (
                x.price > op["reference_assumed"]
                if long
                else x.price < op["reference_assumed"]
            )
        ]
        better = [
            x
            for x in candidates
            if (x.price < p.target if long else x.price > p.target)
        ]
        if not better:
            return {
                "changed": False,
                "target": p.target,
                "geometry": cost_geometry(op, D(".10")),
            }
        level = min(better, key=lambda x: abs(x.price - op["reference_assumed"]))
        alternate = {
            **op,
            "plan": replace(
                p, target=level.price, target_kind="RESEARCH_NEAREST_PIVOT"
            ),
        }
        return {
            "changed": True,
            "target": level.price,
            "level": primitive(level),
            "geometry": cost_geometry(alternate, D(".10")),
        }


def run(bars, specs, configs, orders, baseline, protocol_hash):
    ticks = {row[1].price_tick for row in specs}
    if len(ticks) != 1:
        raise ValueError("changing historical tick requires a time-versioned study")
    study = Study(bars, ticks.pop())
    paths = {name: scenario_points(bars, name) for name in ("OHLC", "OLHC")}
    end = paths["OHLC"][-1][0]
    runs = []
    from datetime import datetime

    from .ohlc_diagnostic import CST

    for parameters in GRID:
        base_scans = snapshots(bars, specs, configs, parameters, end)
        for variant in ("A", "B", "C"):
            scans = [
                {**s, "plans": study.candidate_plans(s, parameters, variant)}
                if s["apply_refresh"]
                else s
                for s in base_scans
            ]
            for path, points in paths.items():
                engine = walk(points, scans)
                for op in engine.opportunities:
                    op["cost_sensitivity"] = {
                        str(v): cost_geometry(op, v)
                        for v in (D(".05"), D(".10"), D(".20"))
                    }
                    net_r = op["cost_sensitivity"]["0.10"][
                        "net_r_excluding_unknown_funding"
                    ]
                    op["r_screen_only"] = (
                        net_r is not None and net_r >= parameters.min_net_r
                    )
                    op["barrier_diagnostic"] = barriers(op, points)
                    op["nearer_target_sensitivity"] = study.target_sensitivity(
                        op, parameters
                    )
                if variant == "A":
                    expected = next(
                        x
                        for x in baseline["runs"]
                        if D(x["band_atr"]) == parameters.band_atr
                        and D(x["min_net_r"]) == parameters.min_net_r
                        and x["path"] == path
                    )
                    actual = primitive(
                        [
                            {
                                k: v
                                for k, v in o.items()
                                if k != "nearer_target_sensitivity"
                            }
                            for o in engine.opportunities
                        ]
                    )
                    if actual != expected["opportunities"]:
                        raise AssertionError("baseline opportunity replay diverged")
                cases = []
                for o in orders:
                    start = (
                        datetime.fromisoformat(o["opened_at"])
                        .replace(tzinfo=CST)
                        .timestamp()
                    )
                    finish = (
                        datetime.fromisoformat(o["closed_at"])
                        .replace(tzinfo=CST)
                        .timestamp()
                    )
                    selected = next(
                        (
                            s
                            for s in reversed(scans)
                            if s["at"] <= start and s["apply_refresh"]
                        ),
                        None,
                    )
                    same = [
                        x
                        for x in engine.opportunities
                        if int(x["at_assumed"] // 900) == int(start // 900)
                        and x["plan"].side.value == o["side"]
                    ]
                    during = [
                        x
                        for x in engine.opportunities
                        if start <= x["at_assumed"] <= finish
                        and x["plan"].side.value == o["side"]
                    ]
                    cases.append(
                        {
                            "id": o["id"],
                            "plans": selected["plans"] if selected else (),
                            "pivot_levels": study.pivots(start, parameters.band_atr),
                            "failed_levels": study.failures(start, parameters.band_atr),
                            "same_bar_shapes": [
                                {
                                    "at": x["time_cst_assumed"],
                                    "model": x["plan"].model,
                                    "line": x["plan"].line,
                                    "r_screen": x["r_screen_only"],
                                    "net_r": x["cost_sensitivity"]["0.10"][
                                        "net_r_excluding_unknown_funding"
                                    ],
                                }
                                for x in same
                            ],
                            "holding_shapes": [
                                {
                                    "at": x["time_cst_assumed"],
                                    "line": x["plan"].line,
                                    "r_screen": x["r_screen_only"],
                                }
                                for x in during
                            ],
                        }
                    )
                summary = {
                    "unique_plans": len(engine.rows),
                    "touched_plans": sum(x["touched"] for x in engine.rows.values()),
                    "shapes": len(engine.opportunities),
                    "r_screen_only": sum(
                        o["r_screen_only"] for o in engine.opportunities
                    ),
                    "nearer_target_r_screen": sum(
                        (
                            o["nearer_target_sensitivity"]["geometry"][
                                "net_r_excluding_unknown_funding"
                            ]
                            or D(-1)
                        )
                        >= parameters.min_net_r
                        for o in engine.opportunities
                    ),
                    "models": dict(
                        Counter(o["plan"].model for o in engine.opportunities)
                    ),
                    "source_families": dict(
                        Counter(o["plan"].sources[0] for o in engine.opportunities)
                    ),
                    "final_phases": dict(
                        Counter(x["phase"] for x in engine.rows.values())
                    ),
                    "matched_screenshot_bars": sum(
                        bool(c["same_bar_shapes"]) for c in cases
                    ),
                }
                runs.append(
                    {
                        "variant": variant,
                        "band_atr": parameters.band_atr,
                        "min_net_r": parameters.min_net_r,
                        "path": path,
                        "summary": summary,
                        "opportunities": engine.opportunities,
                        "cases": cases,
                        "plan_terminal_reasons": [
                            {
                                "id": p,
                                "sources": x["plan"].sources,
                                "phase": x["phase"],
                                "reason": x["reason"],
                            }
                            for p, x in engine.rows.items()
                        ],
                    }
                )
    return {
        "scope": SCOPE,
        "financial_admission": "NOT_ELIGIBLE",
        "baseline_verified": True,
        "protocol_sha256": protocol_hash,
        "no_parameter_selection": True,
        "runs": runs,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("archive", "orders", "baseline", "protocol", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    bars, specs, configs, provenance = load_archive(
        args.archive, "/opt/coinstrategy/htx-trend-paper-primary/evidence"
    )
    baseline = json.loads(gzip.decompress(Path(args.baseline).read_bytes()))
    orders = json.loads(Path(args.orders).read_bytes())["orders"]
    result = run(
        bars,
        specs,
        configs,
        orders,
        baseline,
        hashlib.sha256(Path(args.protocol).read_bytes()).hexdigest(),
    )
    result["provenance"] = {
        **provenance,
        "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "baseline_sha256": hashlib.sha256(Path(args.baseline).read_bytes()).hexdigest(),
        "orders_sha256": hashlib.sha256(Path(args.orders).read_bytes()).hexdigest(),
    }
    # Intern long immutable window references instead of duplicating 160+160 hashes per opportunity.
    raw = primitive(result)
    pool = {}

    def compact(value):
        if isinstance(value, dict):
            if "evidence_refs" in value:
                refs = value.pop("evidence_refs")
                key = digest(refs)
                pool[key] = refs
                value["evidence_refset"] = key
            for item in value.values():
                compact(item)
        elif isinstance(value, list):
            for item in value:
                compact(item)

    compact(raw)
    raw["reference_sets"] = pool
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(
        gzip.compress(
            json.dumps(raw, ensure_ascii=False, sort_keys=True).encode(), mtime=0
        )
    )
    print(
        json.dumps(
            [
                {
                    k: v
                    for k, v in x.items()
                    if k in ("variant", "band_atr", "min_net_r", "path", "summary")
                }
                for x in raw["runs"]
            ],
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
