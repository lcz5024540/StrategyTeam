"""Explicit cold-start ranking and point-in-time empirical outcome estimates.

Net-R distributions refer to actual fully costed exits, not target-touch labels.
No pseudo-probability is synthesized from indicators or reward/risk ratios.
"""
from __future__ import annotations

from decimal import Decimal as D
from math import sqrt
from statistics import mean

from ..indicators import ema
from ..models import TrendSide
from ..research.store import digest, primitive

SCHEMA = "opportunity-outcomes-v1"
MIN_SAMPLES = 30


def policy_fingerprint(execution, policy, latency_ms):
    holding = policy.to_dict()
    return digest({"execution": execution, "latency_ms": latency_ms,
                   "holding": {k: v for k, v in holding.items() if k not in {"owner", "hash"}}})


def features(plan, windows, book, now_ms):
    long = plan.side is TrendSide.LONG
    sign = D(1) if long else D(-1)
    direction = None
    window = windows.get("4hour")
    refs = []
    if (window is not None and window.is_complete and len(window.candles) >= 60
            and window.contract_code == plan.contract and window.period == "4hour"
            and 0 <= now_ms // 1000 - window.candles[-1].open_time - 14400 < 14400
            and all((c.open_time + 14400) * 1000 <= now_ms for c in window.candles)):
        values = [c.close for c in window.candles[-160:]]
        fast, slow = ema(values, 20), ema(values, 60)
        direction = sign * (D(1) if fast > slow else D(-1) if fast < slow else D(0))
        refs = list(window.evidence_refs)
    mid = (book.top.bid + book.top.ask) / 2
    bids = sum((qty for px, qty in book.bids if mid - plan.atr <= px <= mid), D(0))
    asks = sum((qty for px, qty in book.asks if mid <= px <= mid + plan.atr), D(0))
    imbalance = sign * (bids - asks) / (bids + asks) if bids + asks > 0 else None
    return primitive({
        "family": next(s.split(":", 1)[1] for s in plan.sources if s.startswith("FAMILY:")),
        "direction": direction, "book": imbalance, "direction_refs": refs,
        "direction_status": "AVAILABLE" if direction is not None else "UNKNOWN",
        "book_status": "AVAILABLE" if imbalance is not None else "UNKNOWN",
    })


def bucket(feature, variant):
    parts = [feature["family"]]
    for name, enabled in (("direction", variant in {"DIRECTION", "BOTH"}),
                          ("book", variant in {"BOOK", "BOTH"})):
        if enabled:
            value = feature.get(name)
            parts.append("UNKNOWN" if value is None else
                         "POSITIVE" if D(value) > 0 else
                         "NEGATIVE" if D(value) < 0 else "NEUTRAL")
    return "/".join(parts)


def fit(samples, *, cutoff_ms, policy_hash):
    """Reject invalid training rows instead of silently dropping inconvenient losses."""
    if len(policy_hash) != 64 or any(c not in "0123456789abcdef" for c in policy_hash):
        raise ValueError("training requires a frozen execution/exit/cost policy hash")
    seen = set()
    normalized = []
    for row in samples:
        if row["opportunity_id"] in seen:
            raise ValueError("duplicate economic outcome")
        seen.add(row["opportunity_id"])
        if (row["status"] != "CLOSED" or row["cost_status"] != "VERIFIED"
                or row["policy_hash"] != policy_hash
                or not 0 <= row["entry_ms"] < row["exit_ms"] <= row["available_ms"] <= cutoff_ms
                or row["feature_ms"] > row["entry_ms"]
                or not D(str(row["net_r"])).is_finite()):
            raise ValueError("unmatured, uncosted, future or incompatible outcome")
        feature = row["features"]
        if feature["family"] not in {"BREAKOUT_RETEST", "TREND_PULLBACK", "RANGE_REJECTION"}:
            raise ValueError("unknown outcome family")
        for name in ("direction", "book"):
            value = feature.get(name)
            if value is not None and (not D(str(value)).is_finite() or abs(D(str(value))) > 1):
                raise ValueError("invalid pre-entry feature")
        normalized.append(primitive(row))
    body = {"schema": SCHEMA, "cutoff_ms": cutoff_ms, "policy_hash": policy_hash,
            "samples": sorted(normalized, key=lambda r: r["opportunity_id"])}
    return {**body, "hash": digest(body)}


class Calibration:
    def __init__(self, artifact):
        expected = fit(artifact["samples"], cutoff_ms=artifact["cutoff_ms"],
                       policy_hash=artifact["policy_hash"])
        if artifact != expected:
            raise ValueError("calibration schema/content hash mismatch")
        self.artifact = artifact

    def estimate(self, feature, variant, *, at_ms, policy_hash):
        if at_ms <= self.artifact["cutoff_ms"] or policy_hash != self.artifact["policy_hash"]:
            raise ValueError("calibration is not point-in-time/policy compatible")
        key = bucket(feature, variant)
        values = [float(r["net_r"]) for r in self.artifact["samples"]
                  if bucket(r["features"], variant) == key]
        n = len(values)
        if n < MIN_SAMPLES:
            return {"status": "INSUFFICIENT_SUPPORT", "n": n, "win_probability": None,
                    "mean_net_r": None, "bucket": key}
        p = sum(v > 0 for v in values) / n
        z = 1.96
        center = (p + z*z/(2*n)) / (1+z*z/n)
        radius = z * sqrt(p*(1-p)/n+z*z/(4*n*n)) / (1+z*z/n)
        ordered = sorted(values)
        return {"status": "EMPIRICAL_IN_SAMPLE", "n": n, "bucket": key,
                "win_probability": p, "wilson95": [center-radius, center+radius],
                "mean_net_r": mean(values), "p10_net_r": ordered[int((n-1)*.1)],
                "p50_net_r": ordered[int((n-1)*.5)], "p90_net_r": ordered[int((n-1)*.9)],
                "outcome": "CLOSED_NET_PROFIT_AFTER_COSTS",
                "calibration_hash": self.artifact["hash"]}


def rank(ready, decisions, variant):
    """Same-batch comparison only; never compare empirical EV to an uncalibrated R."""
    calibrated = bool(ready) and all(
        decisions[t.plan.plan_id]["estimate"].get("mean_net_r") is not None for _, t in ready)

    def key(item):
        net_r, trigger = item
        decision = decisions[trigger.plan.plan_id]
        f, estimate = decision["features"], decision["estimate"]
        primary = D(str(estimate["mean_net_r"])) if calibrated else net_r
        extra = []
        for name, enabled in (("direction", variant in {"DIRECTION", "BOTH"}),
                              ("book", variant in {"BOOK", "BOTH"})):
            if enabled:
                # UNKNOWN is neutral for ranking, but remains explicitly unknown in evidence.
                extra.append(-D(f[name]) if f.get(name) is not None else D(0))
        return (-primary, *extra, trigger.plan.plan_id)

    return sorted(ready, key=key), "EMPIRICAL_MEAN_NET_R" if calibrated else "UNCALIBRATED_NET_R"
