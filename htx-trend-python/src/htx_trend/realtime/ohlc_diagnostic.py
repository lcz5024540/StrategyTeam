"""Offline OHLC geometry scenarios. Never produces trades or admission evidence.

The three linear legs are assumptions, not recovered ticks or price bounds.
Two-trade and BBO confirmation, budget, reentry and actual exits are NOT tested.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import itertools
import json
from collections import Counter
from dataclasses import dataclass, replace
from datetime import datetime
from decimal import ROUND_CEILING, ROUND_FLOOR
from decimal import Decimal as D
from pathlib import Path
from types import SimpleNamespace
from zoneinfo import ZoneInfo

from ..execution import ExecutionConfig, structured_price_plan
from ..java_entry_contract import JavaEntryConfig, evaluate
from ..market_data import Candle, ContractSpec, KlineWindow
from ..models import TrendSide
from ..research.store import digest, primitive
from .plans import GRID, generate

CST = ZoneInfo("Asia/Shanghai")
SCOPE = "ASSUMPTION_ONLY_NOT_EXECUTABLE"


def epoch(value):
    return datetime.fromisoformat(value).timestamp()


def local(value):
    return datetime.fromtimestamp(value, CST).isoformat()


@dataclass(frozen=True)
class ArchivedBar:
    period: str
    candle: Candle
    available: float
    ref: str


def load_archive(path, source):
    blob = Path(path).read_bytes()
    archive = json.loads(gzip.decompress(blob))
    objects = archive["sources"][source]["objects"]
    for ref, row in objects.items():
        if digest(row["object"]) != ref:
            raise ValueError("CAS hash mismatch: " + ref)
        if any(r not in objects for r in row["object"].get("refs", [])):
            raise ValueError("missing CAS reference: " + ref)
    bars, specs, configs = [], [], []
    for ref, row in objects.items():
        obj, at = row["object"], epoch(row["recorded_at"])
        p = obj["payload"]
        if obj["kind"] == "compact_candle":
            if p["contract_code"] != "ETH-USDT" or p["period"] not in (
                "15min",
                "60min",
            ):
                continue
            raw = dict(p["candle"])
            for key, value in raw.items():
                if key not in ("open_time", "trade_count") and value is not None:
                    raw[key] = D(value)
            c = Candle(**raw)
            if not (
                D(0) < c.low <= min(c.open, c.close) <= max(c.open, c.close) <= c.high
            ):
                raise ValueError("invalid OHLC")
            seconds = 900 if p["period"] == "15min" else 3600
            bars.append(
                ArchivedBar(p["period"], c, max(at, c.open_time + seconds), ref)
            )
        elif (
            obj["kind"] == "compact_entry_decision" and p["contract_code"] == "ETH-USDT"
        ):
            raw = dict(p["contract"])
            for key in ("price_tick", "contract_size"):
                raw[key] = D(raw[key])
            # Decision metadata cannot be exposed before its recorded decision time.
            specs.append((max(at, epoch(p["at"])), ContractSpec(**raw), ref))
        elif obj["kind"] == "compact_strategy_version":
            raw = dict(p["config"]["entry"])
            default = JavaEntryConfig()
            for key, value in raw.items():
                if isinstance(getattr(default, key), D):
                    raw[key] = D(value)
            configs.append((at, JavaEntryConfig(**raw), ref, p["source_commit"]))
    if not bars or not specs or not configs:
        raise ValueError("missing candles, metadata or configuration")
    bars.sort(key=lambda x: (x.available, x.ref))
    specs.sort(key=lambda x: x[0])
    configs.sort(key=lambda x: x[0])
    return (
        bars,
        specs,
        configs,
        {"sha256": hashlib.sha256(blob).hexdigest(), "verified_objects": len(objects)},
    )


def visible_window(bars, period, at):
    visible = {}
    for row in bars:
        if row.period == period and row.available <= at:
            previous = visible.get(row.candle.open_time)
            if previous is None or row.available > previous.available:
                visible[row.candle.open_time] = row
            elif row.available == previous.available and row.ref != previous.ref:
                raise ValueError("conflicting simultaneous revisions")
    selected = sorted(visible.values(), key=lambda x: x.candle.open_time)[-160:]
    return KlineWindow(
        "ETH-USDT",
        period,
        tuple(r.candle for r in selected),
        160,
        tuple(r.ref for r in selected),
    )


def snapshots(bars, specs, configs, parameters, end):
    times = sorted(
        {r.available for r in bars} | {r[0] for r in specs} | {r[0] for r in configs}
    )
    result, previous = [], None
    for at in times:
        if at >= end:
            break
        entry, hour = (visible_window(bars, p, at) for p in ("15min", "60min"))
        spec = next((r for r in reversed(specs) if r[0] <= at), None)
        config = next((r for r in reversed(configs) if r[0] <= at), None)
        identity = (
            entry.evidence_refs,
            hour.evidence_refs,
            spec[1] if spec else None,
            config[2] if config else None,
        )
        if identity == previous:
            continue
        previous = identity
        reason, signal, plans = "READY", None, ()
        apply_refresh = False
        if not spec or not config:
            reason = "METADATA_OR_CONFIG_NOT_VISIBLE"
        elif not entry.is_complete or not hour.is_complete:
            reason = "INCOMPLETE_160_BAR_INPUT"
        elif not (
            0 <= at - (entry.candles[-1].open_time + 900) < 900
            and 0 <= at - (hour.candles[-1].open_time + 3600) < 3600
        ):
            reason = "AWAITING_FRESH_ARCHIVED_WINDOWS"
        else:
            apply_refresh = True
            signal = evaluate(entry.candles, config[1])
            plans = generate(
                entry,
                hour,
                spec[1],
                now_ms=int(at * 1000),
                parameters=parameters,
                signal_side=signal.side,
            )
            if not plans:
                reason = "DIRECTION_OR_STRUCTURE_REJECTED"
        result.append(
            {
                "at": at,
                "time_cst": local(at),
                "reason": reason,
                "apply_refresh": apply_refresh,
                "entry_bar": entry.candles[-1].open_time if entry.candles else None,
                "hour_bar": hour.candles[-1].open_time if hour.candles else None,
                "signal_side": signal.side.value if signal else None,
                "signal_regime": signal.regime if signal else None,
                "config_ref": config[2] if config else None,
                "metadata_ref": spec[2] if spec else None,
                "plans": plans,
            }
        )
    return result


def scenario_points(bars, path):
    if path not in ("OHLC", "OLHC"):
        raise ValueError("unknown scenario")
    by_time = {}
    for row in bars:
        if row.period == "15min":
            if row.candle.open_time in by_time:
                raise ValueError(
                    "OHLC outcome has revisions: supply a separately audited path dataset"
                )
            by_time[row.candle.open_time] = row.candle
    ordered = sorted(by_time.values(), key=lambda c: c.open_time)
    if any(b.open_time - a.open_time != 900 for a, b in itertools.pairwise(ordered)):
        raise ValueError("scenario contains a candle gap")
    points = []
    for c in ordered:
        fields = (
            (c.open, c.high, c.low, c.close)
            if path == "OHLC"
            else (c.open, c.low, c.high, c.close)
        )
        # Close at 899, next open at 900: the interbar jump is explicitly discontinuous.
        points.extend(
            (c.open_time + delta, price)
            for delta, price in zip((0, 300, 600, 899), fields)
        )
    return points


class Geometry:
    """Relaxed price-stage recognizer; never constructs MarketEvent or order intent."""

    def __init__(self):
        self.rows, self.opportunities = {}, []

    def refresh(self, plans):
        wanted = {p.plan_id: p for p in plans}
        for key, row in self.rows.items():
            if key not in wanted and row["phase"] not in ("TERMINAL", "INVALIDATED"):
                row.update(phase="INVALIDATED", reason="STRUCTURE_REPLACED")
        for key, p in wanted.items():
            row = self.rows.get(key)
            if row is None:
                self.rows[key] = {
                    "plan": p,
                    "phase": "WAITING",
                    "prior": None,
                    "extreme": None,
                    "touched": False,
                    "reason": None,
                }
            elif row["phase"] == "WAITING":
                row["plan"] = p

    def levels(self):
        return {
            x
            for r in self.rows.values()
            if r["phase"] not in ("TERMINAL", "INVALIDATED")
            for p in (r["plan"],)
            for x in (
                p.line - p.band - p.tick,
                p.line - p.band,
                p.line,
                p.line + p.band,
                p.line + p.band + p.tick,
            )
        }

    def point(self, at, price):
        for row in self.rows.values():
            if row["phase"] in ("TERMINAL", "INVALIDATED"):
                continue
            p, phase, previous = row["plan"], row["phase"], row["prior"]
            long = p.side is TrendSide.LONG
            low, high = p.line - p.band, p.line + p.band
            zone = low <= price <= high
            favorable = price > high if long else price < low
            adverse = price < low if long else price > high
            row["touched"] |= zone
            if phase == "WAITING":
                if p.model == "RT_RANGE_REJECTION":
                    approach = previous is not None and (
                        previous > high if long else previous < low
                    )
                    if zone and approach:
                        row.update(phase="ARMED", extreme=price)
                elif favorable:
                    row["phase"] = "BROKEN"
            elif phase == "BROKEN":
                if zone:
                    row.update(phase="RETEST", extreme=price)
                elif adverse:
                    row.update(phase="INVALIDATED", reason="BREAKOUT_RECLAIMED")
            elif phase == "RETEST" and adverse:
                row.update(phase="INVALIDATED", reason="RETEST_FAILED")
            elif phase in ("ARMED", "RETEST"):
                row["extreme"] = (
                    min(row["extreme"], price) if long else max(row["extreme"], price)
                )
                if favorable:
                    raw_stop = (
                        row["extreme"] - p.band if long else row["extreme"] + p.band
                    )
                    reference = (price / p.tick).to_integral_value(
                        rounding=ROUND_CEILING if long else ROUND_FLOOR
                    ) * p.tick
                    stop = (
                        min(raw_stop, reference - D("1.25") * p.atr)
                        if long
                        else max(raw_stop, reference + D("1.25") * p.atr)
                    )
                    self.opportunities.append(
                        {
                            "scope": SCOPE,
                            "at_assumed": at,
                            "time_cst_assumed": local(at),
                            "plan": p,
                            "reference_assumed": reference,
                            "raw_stop": raw_stop,
                            "stop": stop,
                        }
                    )
                    row["phase"] = "TERMINAL"
            row["prior"] = price


def walk(points, scans):
    engine, index = Geometry(), 0
    for (ta, a), (tb, b) in itertools.pairwise(points):
        # Refresh only after metadata/candles were archived. Split hypothetical legs at those clocks.
        cuts = [(ta, a)]
        while index < len(scans) and scans[index]["at"] <= ta:
            if scans[index].get("apply_refresh", True):
                engine.refresh(scans[index]["plans"])
            index += 1
        engine.point(ta, a)
        while index < len(scans) and scans[index]["at"] < tb:
            t = scans[index]["at"]
            if t > ta:
                value = a + (b - a) * D(str((t - ta) / (tb - ta))) if tb - ta > 1 else a
                cuts.append((t, value))
            index += 1
        cuts.append((tb, b))
        for (t0, p0), (t1, p1) in itertools.pairwise(cuts):
            if tb - ta > 1 and p0 != p1:
                levels = sorted(
                    (x for x in engine.levels() if min(p0, p1) < x < max(p0, p1)),
                    reverse=p1 < p0,
                )
                for x in levels:
                    engine.point(t0 + (t1 - t0) * float((x - p0) / (p1 - p0)), x)
            engine.point(t1, p1)
            # Exact timestamp snapshots are applied after arriving price, before subsequent movement.
            for scan in scans:
                if scan["at"] == t1 and scan.get("apply_refresh", True):
                    engine.refresh(scan["plans"])
                    engine.point(t1, p1)
    return engine


def cost_geometry(opportunity, slip):
    p = opportunity["plan"]
    candidate = SimpleNamespace(
        side=p.side,
        entry_price=opportunity["reference_assumed"],
        invalidation_price=opportunity["stop"],
        target_low=p.target,
        target_high=p.target,
    )
    try:
        sign = D(1) if p.side is TrendSide.LONG else D(-1)
        limit = (
            (candidate.entry_price + sign * p.atr * slip) / p.tick
        ).to_integral_value(
            rounding=ROUND_CEILING if sign > 0 else ROUND_FLOOR
        ) * p.tick
        plan = structured_price_plan(
            candidate,
            entry_limit=limit,
            atr=p.atr,
            execution_price=None,
            fee_rate=D(".0004"),
            entry_fee_rate=D(".0004"),
            passive=False,
            config=replace(ExecutionConfig(), slippage_atr_fraction=slip),
        )
        return {
            "net_r_excluding_unknown_funding": plan.expected_net_r,
            "reward_per_eth_assumed": plan.expected_net_reward,
            "risk_per_eth_assumed": plan.net_risk,
            "reason": "GEOMETRY_ONLY",
        }
    except ValueError as e:
        return {"net_r_excluding_unknown_funding": None, "reason": str(e)}


def barriers(opportunity, points):
    """First target/stop contact under the assumed path; not a strategy exit or fill."""
    start = opportunity["at_assumed"]
    long = opportunity["plan"].side is TrendSide.LONG
    target, stop = opportunity["plan"].target, opportunity["stop"]
    for (ta, a), (tb, b) in itertools.pairwise(points):
        if tb <= start:
            continue
        if ta < start:
            a = a + (b - a) * D(str((start - ta) / (tb - ta)))
            ta = start
        hit = []
        for kind, level in (("TARGET_CONTACT", target), ("STOP_CONTACT", stop)):
            beyond = (
                (a >= level if long else a <= level)
                if kind == "TARGET_CONTACT"
                else (a <= level if long else a >= level)
            )
            if beyond:
                hit.append((ta, kind))
            elif min(a, b) <= level <= max(a, b) and a != b:
                hit.append(
                    (
                        tb
                        if tb - ta <= 1
                        else ta + (tb - ta) * float((level - a) / (b - a)),
                        kind,
                    )
                )
        if hit:
            at, result = min(hit)
            return {"outcome": result, "at_assumed": at, "time_cst_assumed": local(at)}
    return {"outcome": "RIGHT_CENSORED"}


def run(bars, specs, configs, orders, provenance):
    points_by_path = {p: scenario_points(bars, p) for p in ("OHLC", "OLHC")}
    end = points_by_path["OHLC"][-1][0]
    runs = []
    for params in GRID:
        scans = snapshots(bars, specs, configs, params, end)
        for path, points in points_by_path.items():
            engine = walk(points, scans)
            for op in engine.opportunities:
                op["cost_sensitivity"] = {
                    str(s): cost_geometry(op, s) for s in (D(".05"), D(".10"), D(".20"))
                }
                r = op["cost_sensitivity"]["0.10"]["net_r_excluding_unknown_funding"]
                op["r_screen_only"] = r is not None and r >= params.min_net_r
                op["barrier_diagnostic"] = barriers(op, points)
            screenshots = []
            for o in orders:
                at = (
                    datetime.fromisoformat(o["opened_at"])
                    .replace(tzinfo=CST)
                    .timestamp()
                )
                close = (
                    datetime.fromisoformat(o["closed_at"])
                    .replace(tzinfo=CST)
                    .timestamp()
                )
                scan = next(
                    (
                        r
                        for r in reversed(scans)
                        if r["at"] <= at and r.get("apply_refresh", True)
                    ),
                    None,
                )
                samebar = [
                    r
                    for r in engine.opportunities
                    if int(r["at_assumed"] // 900) == int(at // 900)
                    and r["plan"].side.value == o["side"]
                ]
                holding = [
                    r
                    for r in engine.opportunities
                    if at <= r["at_assumed"] <= close
                    and r["plan"].side.value == o["side"]
                ]
                screenshots.append(
                    {
                        "id": o["id"],
                        "opened_at_assuming_cst": o["opened_at"],
                        "last_snapshot": scan,
                        "entry_average_is_not_first_fill": True,
                        "same_bar_shape_count": len(samebar),
                        "same_bar_r_screen_count": sum(
                            r["r_screen_only"] for r in samebar
                        ),
                        "holding_interval_shapes": [
                            {
                                "plan_id": r["plan"].plan_id,
                                "time_cst_assumed": r["time_cst_assumed"],
                                "r_screen_only": r["r_screen_only"],
                            }
                            for r in holding
                        ],
                    }
                )
            runs.append(
                {
                    "band_atr": params.band_atr,
                    "min_net_r": params.min_net_r,
                    "path": path,
                    "summary": {
                        "refreshes": len(scans),
                        "refresh_reasons": dict(Counter(r["reason"] for r in scans)),
                        "unique_plans": len(engine.rows),
                        "touched_plans": sum(
                            r["touched"] for r in engine.rows.values()
                        ),
                        "shapes": len(engine.opportunities),
                        "r_screen_only": sum(
                            r["r_screen_only"] for r in engine.opportunities
                        ),
                        "models": dict(
                            Counter(r["plan"].model for r in engine.opportunities)
                        ),
                        "final_phases": dict(
                            Counter(r["phase"] for r in engine.rows.values())
                        ),
                        "barrier_contacts_r_screen": dict(
                            Counter(
                                r["barrier_diagnostic"]["outcome"]
                                for r in engine.opportunities
                                if r["r_screen_only"]
                            )
                        ),
                    },
                    "plans": list(engine.rows.values()),
                    "snapshots": scans,
                    "opportunities": engine.opportunities,
                    "screenshots": screenshots,
                }
            )
    return {
        "scope": SCOPE,
        "financial_admission": "NOT_ELIGIBLE",
        "provenance": provenance,
        "coverage": {
            p: {
                "bars": len([r for r in bars if r.period == p]),
                "first_open_cst": local(
                    min(r.candle.open_time for r in bars if r.period == p)
                ),
                "last_open_cst": local(
                    max(r.candle.open_time for r in bars if r.period == p)
                ),
                "first_archived_cst": local(
                    min(r.available for r in bars if r.period == p)
                ),
            }
            for p in ("15min", "60min")
        },
        "not_tested": [
            "two_distinct_trades",
            "BBO",
            "IOC_fill",
            "reentry_gate",
            "account_budget",
            "trend_exits",
            "funding",
            "portfolio_pnl",
        ],
        "assumptions": {
            "paths": "three linear legs at 0/300/600/899 seconds; not mathematical bounds",
            "fee_each_side": "0.0004",
            "slippage_atr_each_side": ["0.05", "0.10", "0.20"],
            "funding": "UNKNOWN",
            "screenshot_timezone": "CST_ASSUMED",
            "holdout": "NOT_INDEPENDENT: Oct30-31 hypothesis cases; no selection performed",
        },
        "runs": runs,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True)
    parser.add_argument("--orders", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument(
        "--source", default="/opt/coinstrategy/htx-trend-paper-primary/evidence"
    )
    args = parser.parse_args()
    bars, specs, configs, provenance = load_archive(args.archive, args.source)
    order_bytes = Path(args.orders).read_bytes()
    provenance["orders_sha256"] = hashlib.sha256(order_bytes).hexdigest()
    provenance["diagnostic_source_sha256"] = hashlib.sha256(
        Path(__file__).read_bytes()
    ).hexdigest()
    result = run(bars, specs, configs, json.loads(order_bytes)["orders"], provenance)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_bytes(
        gzip.compress(
            json.dumps(primitive(result), ensure_ascii=False, sort_keys=True).encode(),
            mtime=0,
        )
    )
    print(
        json.dumps(
            primitive(
                {
                    "coverage": result["coverage"],
                    "runs": [
                        {
                            k: v
                            for k, v in r.items()
                            if k in ("band_atr", "min_net_r", "path", "summary")
                        }
                        for r in result["runs"]
                    ],
                }
            ),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
