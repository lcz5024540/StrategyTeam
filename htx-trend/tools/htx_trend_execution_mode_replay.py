#!/usr/bin/env python3
"""BT-03: compare market, passive-limit, IOC and one-reprice execution on frozen price plans.

This is research-only.  It accepts a frozen plan and completed future bars, never talks to HTX,
and refuses incomplete evidence rather than treating a candle close as a historical fill.
"""
from __future__ import annotations

import argparse, json
from dataclasses import dataclass
from pathlib import Path


STYLES = ("MARKET_BASELINE", "PASSIVE_LIMIT", "IOC", "ONE_REPRICE")

@dataclass(frozen=True)
class Bar: open: float; high: float; low: float; close: float
@dataclass(frozen=True)
class Plan:
    side: str; ideal: float; worst: float; notional: float; ttl_bars: int; exit_price: float
@dataclass(frozen=True)
class Result:
    style: str; state: str; fill_price: float | None; filled_notional: float; fee: float; net_pnl: float | None; slippage_bps: float | None; reason: str | None

def touched(bar: Bar, price: float) -> bool: return bar.low <= price <= bar.high
def adverse(side: str, price: float, bps: float) -> float: return price * (1 + bps / 10_000 if side == "LONG" else 1 - bps / 10_000)
def pnl(plan: Plan, price: float, fee_rate: float) -> tuple[float, float, float]:
    qty = plan.notional / price
    gross = (plan.exit_price - price) * qty if plan.side == "LONG" else (price - plan.exit_price) * qty
    fee = (price + plan.exit_price) * qty * fee_rate
    return gross - fee, fee, (price - plan.ideal) / plan.ideal * 10_000 * (1 if plan.side == "LONG" else -1)

def simulate(plan: Plan, bars: list[Bar], style: str, fee_rate: float = .0004, market_slippage_bps: float = 2.) -> Result:
    if style not in STYLES or plan.side not in ("LONG", "SHORT") or not bars or plan.notional <= 0 or plan.ideal <= 0 or plan.worst <= 0:
        return Result(style, "INSUFFICIENT_EVIDENCE", None, 0, 0, None, None, "incomplete plan or future bars")
    first = bars[0]
    if style == "MARKET_BASELINE":
        price = adverse(plan.side, first.open, market_slippage_bps)
        if (plan.side == "LONG" and price > plan.worst) or (plan.side == "SHORT" and price < plan.worst):
            return Result(style, "REJECTED", None, 0, 0, None, None, "market price exceeds worst acceptable price")
        value, fee, slip = pnl(plan, price, fee_rate); return Result(style, "FILLED", price, plan.notional, fee, value, slip, None)
    if style == "IOC":
        if not touched(first, plan.ideal): return Result(style, "REJECTED", None, 0, 0, None, None, "ioc_limit_not_touched")
        value, fee, slip = pnl(plan, plan.ideal, fee_rate); return Result(style, "FILLED", plan.ideal, plan.notional, fee, value, slip, None)
    window = bars[:max(1, plan.ttl_bars)]
    for bar in window:
        if touched(bar, plan.ideal):
            value, fee, slip = pnl(plan, plan.ideal, fee_rate); return Result(style, "FILLED", plan.ideal, plan.notional, fee, value, slip, None)
    if style == "PASSIVE_LIMIT": return Result(style, "EXPIRED", None, 0, 0, None, None, "passive_limit_not_touched_before_ttl")
    # One reprice may improve the chance of a fill but stays within the immutable worst boundary.
    if len(bars) <= len(window): return Result(style, "EXPIRED", None, 0, 0, None, None, "reprice_window_unavailable")
    next_bar = bars[len(window)]
    reprice = min(plan.worst, next_bar.close) if plan.side == "LONG" else max(plan.worst, next_bar.close)
    if not touched(next_bar, reprice): return Result(style, "EXPIRED", None, 0, 0, None, None, "one_reprice_not_touched")
    value, fee, slip = pnl(plan, reprice, fee_rate); return Result(style, "FILLED", reprice, plan.notional, fee, value, slip, "one_reprice")

def aggregate(results: list[Result]) -> dict:
    filled = [r for r in results if r.state == "FILLED"]
    net = sum(r.net_pnl or 0 for r in filled); wins = sum(max(r.net_pnl or 0, 0) for r in filled); losses = -sum(min(r.net_pnl or 0, 0) for r in filled)
    return {"candidateCount": len(results), "filledCount": len(filled), "fillRate": len(filled) / len(results) if results else 0,
            "netPnl": net, "feeTotal": sum(r.fee for r in filled), "profitFactor": wins / losses if losses else (999 if wins else 0),
            "averageSlippageBps": sum(r.slippage_bps or 0 for r in filled) / len(filled) if filled else None,
            "states": {state: sum(r.state == state for r in results) for state in sorted({r.state for r in results})}}

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--input", type=Path, required=True); parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); source = json.loads(args.input.read_text())
    if source.get("inputIntegrity") != "COMPLETE" or not source.get("candidates"):
        report = {"schema": "htx-trend-execution-mode-replay-v1", "evidenceState": "INSUFFICIENT_EVIDENCE", "reason": "COMPLETE frozen price-plan candidates and future bars are required"}
    else:
        by_style = {style: [] for style in STYLES}
        for row in source["candidates"]:
            plan = Plan(**row["plan"]); bars = [Bar(**bar) for bar in row["futureBars"]]
            for style in STYLES: by_style[style].append(simulate(plan, bars, style))
        report = {"schema": "htx-trend-execution-mode-replay-v1", "evidenceState": "COMPLETE", "styles": {style: aggregate(rows) for style, rows in by_style.items()}}
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n"); print(json.dumps(report, ensure_ascii=False))

if __name__ == "__main__": main()
