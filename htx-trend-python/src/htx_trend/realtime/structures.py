"""Production pure calculations for independently evaluated structural candidates."""

from __future__ import annotations

from dataclasses import replace
from decimal import ROUND_CEILING, ROUND_FLOOR
from itertools import pairwise

from ..indicators import atr, ema
from ..models import TrendSide
from ..research.store import digest
from .plans import EVENT_LOCAL, EVENT_WINDOWS, ORIGINAL, StructurePlan


def direction(entry, trend, contract, now_ms, signal_side):
    if (
        entry.contract_code != contract.contract_code
        or trend.contract_code != contract.contract_code
        or entry.period != "15min"
        or trend.period != "60min"
        or not entry.is_complete
        or not trend.is_complete
        or len(entry.candles) < 32
        or len(trend.candles) < 60
    ):
        return None, "INCOMPLETE_INPUT"
    if not (
        0 <= now_ms / 1000 - entry.candles[-1].open_time - 900 < 900
        and 0 <= now_ms / 1000 - trend.candles[-1].open_time - 3600 < 3600
    ):
        return None, "STALE_INPUT"
    if contract.contract_status != 1 or any(
        not p.is_finite() or p <= 0
        for p in (contract.price_tick, contract.contract_size)
    ):
        return None, "CONTRACT_UNAVAILABLE"
    fast, slow = (ema([c.close for c in trend.candles[-160:]], n) for n in (20, 60))
    side = (
        TrendSide.LONG
        if fast > slow
        else TrendSide.SHORT
        if fast < slow
        else TrendSide.FLAT
    )
    if side is TrendSide.FLAT or signal_side not in (side, TrendSide.FLAT):
        return None, "DIRECTION_OPPOSED_OR_FLAT"
    return side, None


def levels(entry, tick, k):
    """All 20/32 edges plus strict confirmed 2-left/2-right pivots; no future bars."""
    candles = entry.candles
    output = {}

    def add(edge, price, anchor, source):
        key = (edge, price, anchor)
        output.setdefault(key, set()).add(source)

    for n in (20, 32):
        for edge, field, choose in (("HIGH", "high", max), ("LOW", "low", min)):
            price = choose(getattr(c, field) for c in candles[-n:])
            anchor = min(
                c.open_time for c in candles[-n:] if getattr(c, field) == price
            )
            add(edge, price, anchor, f"{n}:{edge}")
    cutoff = candles[-1].open_time - 31 * 900
    for i in range(2, len(candles) - 2):
        c = candles[i]
        if c.open_time < cutoff:
            continue
        group = candles[i - 2 : i + 3]
        if any(b.open_time - a.open_time != 900 for a, b in pairwise(group)):
            continue
        band = max(2 * tick, k * atr(candles[: i + 3], 14))
        for edge, field in (("HIGH", "high"), ("LOW", "low")):
            price = getattr(c, field)
            if not all(
                price > getattr(other, field)
                if edge == "HIGH"
                else price < getattr(other, field)
                for j, other in enumerate(group)
                if j != 2
            ):
                continue
            if any(
                later.close > price + band
                if edge == "HIGH"
                else later.close < price - band
                for later in candles[i + 3 :]
            ):
                continue
            add(edge, price, c.open_time, f"PIVOT_2_2:{edge}")
    return [
        {
            "edge": edge,
            "price": price,
            "anchor": anchor,
            "sources": tuple(sorted(sources)),
        }
        for (edge, price, anchor), sources in sorted(output.items())
    ]


def generate_candidates(
    entry,
    trend,
    contract,
    *,
    now_ms,
    parameters,
    signal_side=TrendSide.FLAT,
    audit=None,
):
    audit = [] if audit is None else audit
    if parameters.candidate == EVENT_WINDOWS:
        from .plans import generate

        base = generate(
            entry,
            trend,
            contract,
            now_ms=now_ms,
            parameters=replace(parameters, candidate=ORIGINAL, target_mode="J1"),
            signal_side=signal_side,
        )
        return tuple(
            replace(
                p,
                plan_id=digest({"base": p.plan_id, "candidate": EVENT_WINDOWS}),
                sources=p.sources + ("RULE:" + EVENT_WINDOWS,),
                parameter_fingerprint=parameters.fingerprint,
            )
            for p in base
        )
    side, reason = direction(entry, trend, contract, now_ms, signal_side)
    if reason:
        audit.append({"reason": reason})
        return ()
    volatility = atr(entry.candles, 14)
    if volatility <= 0:
        audit.append({"reason": "NO_ATR"})
        return ()
    band = max(2 * contract.price_tick, parameters.band_atr * volatility)
    inventory = levels(entry, contract.price_tick, parameters.band_atr)
    candidates = (
        inventory
        if parameters.candidate == EVENT_LOCAL
        else [
            l
            for l in inventory
            if any(s.startswith(("20:", "32:")) for s in l["sources"])
        ]
    )
    long = side is TrendSide.LONG
    output = []
    for level in candidates:
        rejection = level["edge"] == ("LOW" if long else "HIGH")
        model = "RT_RANGE_REJECTION" if rejection else "RT_BREAKOUT_RETEST"
        # No price, polling timestamp, source classification or model alias in identity.
        ident = digest(
            {
                "candidate": parameters.candidate,
                "contract": contract.contract_code,
                "side": side,
                "edge": level["edge"],
                "anchor": level["anchor"],
            }
        )
        zone = level["price"] + band if long else level["price"] - band
        choices = [
            l
            for l in inventory
            if l["edge"] == ("HIGH" if long else "LOW")
            and (
                parameters.target_mode == "J2"
                or any(s.startswith(("20:", "32:")) for s in l["sources"])
            )
            and (l["price"] > zone if long else l["price"] < zone)
        ]
        if not choices:
            audit.append({"plan_id": ident, "level": level, "reason": "NO_TARGET"})
            continue
        target = (
            min(l["price"] for l in choices)
            if long
            else max(l["price"] for l in choices)
        )
        target = (target / contract.price_tick).to_integral_value(
            rounding=ROUND_FLOOR if long else ROUND_CEILING
        ) * contract.price_tick
        if not (target > zone if long else target < zone):
            audit.append(
                {
                    "plan_id": ident,
                    "level": level,
                    "reason": "TARGET_INSIDE_ZONE_AFTER_TICK",
                }
            )
            continue
        plan = StructurePlan(
            ident,
            contract.contract_code,
            model,
            side,
            level["price"],
            band,
            volatility,
            contract.price_tick,
            target,
            parameters.target_mode + ":FROZEN_NEAREST_BOUNDARY",
            entry.candles[-1].open_time,
            trend.candles[-1].open_time,
            level["anchor"],
            level["sources"] + ("RULE:" + parameters.candidate,),
            tuple(entry.evidence_refs + trend.evidence_refs),
            parameters.fingerprint,
            now_ms,
        )
        output.append(plan)
        audit.append(
            {
                "plan_id": ident,
                "level": level,
                "target": target,
                "reason": "CANDIDATE_WAITING_TRIGGER",
            }
        )
    return tuple(output)
