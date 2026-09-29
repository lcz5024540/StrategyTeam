#!/usr/bin/env python3
"""P0 offline derivation: frozen candles -> regimes, labels and evaluation ledger input.

The script accepts only a COMPLETE, content-addressed public-kline manifest.  It
never contacts HTX, reads account credentials or submits orders.  Its JSON output
is deliberately an import payload for the immutable research tables, not a live
strategy input.
"""
from __future__ import annotations

import argparse
import bisect
import json
from dataclasses import asdict
from pathlib import Path
from typing import Iterable

from htx_trend_backtest import Bar, ema, load_bars
from htx_trend_research_engine import (
    DatasetIntegrity, DonchianBreakoutModel, EmaVwapMomentumModel,
    ExecutionAssumptions, FibonacciPullbackModel, ModelConfig, OrderStyle,
    Side, SignalContext, simulate_entry, simulate_exit,
)

SCHEMA = "htx-trend-research-derived-ledger-v1"
RESEARCH_VERSION = "research-engine-v1"
MODEL_VERSION = "independent-model-contract-v1"
ENTRY_PERIOD = "15min"
HORIZON_BARS = 32
MIN_COMPARABLE_TRADES = 30


def _manifest(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported research manifest schema")
    if value.get("inputIntegrity") != "COMPLETE":
        raise ValueError("P0 derivation requires a COMPLETE frozen dataset")
    if any(row.get("sourceKind") != "RAW_PUBLIC" for row in value.get("inputs", [])):
        raise ValueError("P0 derivation currently requires materialized RAW_PUBLIC inputs")
    return value


def _direction_series(bars: list[Bar], fast_period: int = 20, slow_period: int = 60) -> list[str]:
    """Return completed-bar EMA directions in O(n), without using future candles."""
    if not bars:
        return []
    alpha_fast, alpha_slow = 2 / (fast_period + 1), 2 / (slow_period + 1)
    fast = slow = bars[0].close
    values: list[str] = []
    for index, bar in enumerate(bars):
        if index:
            fast += alpha_fast * (bar.close - fast)
            slow += alpha_slow * (bar.close - slow)
        values.append("FLAT" if index < slow_period else ("LONG" if fast > slow else "SHORT" if fast < slow else "FLAT"))
    return values


def _latest_direction(bars: list[Bar], directions: list[str], bar_time: int) -> str:
    index = bisect.bisect_right([bar.ts for bar in bars], bar_time) - 1
    return directions[index] if index >= 0 else "FLAT"


def _market_breadth(all_bars: dict[str, dict[str, list[Bar]]]) -> dict[int, tuple[float | None, int]]:
    """Cross-sectional 15m breadth: long directions / all non-flat directions at one completed bar."""
    counts: dict[int, list[int]] = {}
    for periods in all_bars.values():
        for bar, direction in zip(periods[ENTRY_PERIOD], _direction_series(periods[ENTRY_PERIOD])):
            if direction == "FLAT":
                continue
            bucket = counts.setdefault(bar.ts, [0, 0])
            bucket[0] += direction == "LONG"
            bucket[1] += 1
    return {bar_time: (long_count / total if total else None, total) for bar_time, (long_count, total) in counts.items()}


def _regime(daily: str, four_hour: str, one_hour: str) -> str:
    if daily == four_hour == one_hour == "LONG":
        return "TREND_UP"
    if daily == four_hour == one_hour == "SHORT":
        return "TREND_DOWN"
    if daily == four_hour and daily != "FLAT" and one_hour != daily:
        return "PULLBACK"
    return "RANGE"


def _barrier(side: Side, entry: float, stop: float, target_one: float, target_two: float,
             bars: list[Bar]) -> tuple[str, int | None, str | None]:
    """Use stop-first ordering when an OHLC bar touches both boundaries."""
    for bar in bars:
        stop_hit = bar.low <= stop if side is Side.LONG else bar.high >= stop
        one_hit = bar.high >= target_one if side is Side.LONG else bar.low <= target_one
        two_hit = bar.high >= target_two if side is Side.LONG else bar.low <= target_two
        if stop_hit:
            return "STOP", bar.ts, "same_bar_stop_first" if one_hit or two_hit else None
        if two_hit:
            return "TARGET_2R", bar.ts, None
        if one_hit:
            return "TARGET_1R", bar.ts, None
    return "TIMEOUT", None, None


def _excursions(side: Side, entry: float, bars: list[Bar]) -> tuple[float, float]:
    if not bars:
        return 0.0, 0.0
    if side is Side.LONG:
        return max(bar.high - entry for bar in bars), max(entry - bar.low for bar in bars)
    return max(entry - bar.low for bar in bars), max(bar.high - entry for bar in bars)


def _split(timestamp: int, start: int, end: int) -> str:
    span = end - start
    if timestamp < start + span // 2:
        return "TRAIN"
    if timestamp < start + (span * 3) // 4:
        return "VALIDATION"
    return "OUT_OF_SAMPLE"


def _evaluation(rows: list[dict], dataset_key: str, model: str, split: str) -> dict:
    pnl = [row["netProfit"] for row in rows]
    wins, losses = [x for x in pnl if x > 0], [-x for x in pnl if x < 0]
    equity = peak = 0.0
    drawdown = 0.0
    for value in pnl:
        equity += value
        peak = max(peak, equity)
        drawdown = min(drawdown, equity - peak)
    count = len(rows)
    decision = "INSUFFICIENT_SAMPLE" if count < MIN_COMPARABLE_TRADES else "REJECT"
    passes_profit_factor = bool(wins) and (not losses or sum(wins) / sum(losses) >= 1.3)
    if split != "OUT_OF_SAMPLE":
        decision = "INSUFFICIENT_EVIDENCE"
    elif count >= MIN_COMPARABLE_TRADES and sum(pnl) > 0 and passes_profit_factor:
        decision = "PAPER_CANDIDATE"
    fees = sum(row["estimatedEntryFee"] + row["estimatedExitFee"] for row in rows)
    return {
        "evaluationKey": f"evaluation:{dataset_key}:{RESEARCH_VERSION}:{model}:{split}",
        "datasetKey": dataset_key, "researchVersion": RESEARCH_VERSION, "sampleSplit": split,
        "modelCode": model, "modelVersion": MODEL_VERSION, "tradeCount": count,
        "winCount": len(wins), "lossCount": len(losses), "netProfit": sum(pnl),
        "profitFactor": (sum(wins) / sum(losses)) if losses else (999.0 if wins else 0.0),
        "expectancy": sum(pnl) / count if count else 0.0, "maxDrawdown": drawdown,
        "feeTotal": fees, "decision": decision,
        "evidence": {"costsIncluded": True, "minimumComparableTrades": MIN_COMPARABLE_TRADES,
                     "qualification": "out_of_sample only; no parameter selection in this job",
                     "promotionEligible": split == "OUT_OF_SAMPLE"},
    }


def build_ledger(data_dir: Path, manifest_path: Path,
                 config: ModelConfig = ModelConfig(),
                 execution: ExecutionAssumptions = ExecutionAssumptions()) -> dict:
    manifest = _manifest(manifest_path)
    symbols = manifest["contracts"]
    start = int(__import__("datetime").datetime.fromisoformat(manifest["inputStartAt"] + "+08:00").timestamp())
    end = int(__import__("datetime").datetime.fromisoformat(manifest["inputEndAt"] + "+08:00").timestamp())
    all_bars = {symbol: {period: load_bars(data_dir, symbol, period) for period in manifest["periods"]}
                for symbol in symbols}
    direction_series = {symbol: {period: _direction_series(bars) for period, bars in periods.items()}
                        for symbol, periods in all_bars.items()}
    breadth_by_bar = _market_breadth(all_bars)
    models = (DonchianBreakoutModel(), FibonacciPullbackModel(), EmaVwapMomentumModel())
    regimes, labels = [], []
    seen_regimes: set[str] = set()
    for symbol in symbols:
        entry_bars = all_bars[symbol][ENTRY_PERIOD]
        for index in range(max(config.slow_ema, config.donchian_lookback) + 1, len(entry_bars) - HORIZON_BARS - 1):
            completed = entry_bars[:index + 1]
            bar_time = completed[-1].ts
            daily = _latest_direction(all_bars[symbol]["1day"], direction_series[symbol]["1day"], bar_time)
            four_hour = _latest_direction(all_bars[symbol]["4hour"], direction_series[symbol]["4hour"], bar_time)
            one_hour = _latest_direction(all_bars[symbol]["60min"], direction_series[symbol]["60min"], bar_time)
            breadth_ratio, breadth_universe = breadth_by_bar.get(bar_time, (None, 0))
            state = _regime(daily, four_hour, one_hour) if breadth_ratio is None else (
                "TREND_UP" if daily == four_hour == one_hour == "LONG" and breadth_ratio >= .55 else
                "TREND_DOWN" if daily == four_hour == one_hour == "SHORT" and breadth_ratio <= .45 else
                _regime(daily, four_hour, one_hour))
            state_key = f"regime:{manifest['datasetKey']}:{symbol}:{bar_time}"
            if state_key not in seen_regimes:
                seen_regimes.add(state_key)
                regimes.append({"snapshotKey": state_key, "datasetKey": manifest["datasetKey"], "scope": "CONTRACT",
                    "contractCode": symbol, "barTime": bar_time, "stateCode": state, "dailyDirection": daily,
                    "fourHourDirection": four_hour, "oneHourDirection": one_hour, "breadthRatio": breadth_ratio,
                    "volatilityScore": None, "crowdingScore": None, "dataIntegrity": "COMPLETE",
                    "evidence": {"ruleVersion": "regime-v1", "sourcePeriods": ["15min", "60min", "4hour", "1day"],
                                 "breadthUniverse": breadth_universe}})
            for side in (Side.LONG, Side.SHORT):
                context = SignalContext(symbol, bar_time, completed, side, f"regime={state}")
                for model in models:
                    for signal in model.evaluate(context, config, execution):
                        fill = simulate_entry(signal, entry_bars[index + 1], execution)
                        if fill is None:
                            continue
                        future = entry_bars[index + 2:index + 2 + HORIZON_BARS]
                        trade = simulate_exit(fill, future, execution)
                        risk = abs(fill.entry_price - signal.price_plan.invalidation_price)
                        target_one = fill.entry_price + risk if side is Side.LONG else fill.entry_price - risk
                        first, first_time, ambiguity = _barrier(side, fill.entry_price,
                            signal.price_plan.invalidation_price, target_one, signal.price_plan.target_price, future)
                        mfe, mae = _excursions(side, fill.entry_price, future)
                        quantity = execution.notional_usdt / fill.entry_price
                        entry_fee = fill.entry_price * quantity * execution.fee_rate
                        exit_fee = trade.exit_price * quantity * execution.fee_rate
                        labels.append({"labelKey": f"label:{manifest['datasetKey']}:{signal.model}:{symbol}:{side.value}:{bar_time}",
                            "datasetKey": manifest["datasetKey"], "modelCode": signal.model, "modelVersion": MODEL_VERSION,
                            "contractCode": symbol, "side": side.value, "timeframeProfile": "INTRADAY_15M",
                            "entryBarTime": bar_time, "entryPrice": fill.entry_price,
                            "stopPrice": signal.price_plan.invalidation_price, "targetOneRPrice": target_one,
                            "targetTwoRPrice": signal.price_plan.target_price,
                            "horizonBarTime": future[-1].ts if future else fill.entry_time, "firstBarrier": first,
                            "firstBarrierTime": first_time, "mfe": mfe, "mae": mae,
                            "estimatedEntryFee": entry_fee, "estimatedExitFee": exit_fee, "netProfit": trade.net_pnl,
                            "ambiguityReason": ambiguity, "dataIntegrity": "COMPLETE", "marketState": state,
                            "evidence": {"signalScore": signal.score, "signalEvidence": signal.evidence,
                                "orderStyle": signal.price_plan.order_style.value, "exitReason": trade.exit_reason,
                                "costsIncluded": True, "horizonBars": HORIZON_BARS}})
    evaluations = []
    for model in sorted({row["modelCode"] for row in labels}):
        for split in ("TRAIN", "VALIDATION", "OUT_OF_SAMPLE"):
            evaluations.append(_evaluation([row for row in labels if row["modelCode"] == model and
                _split(row["entryBarTime"], start, end) == split], manifest["datasetKey"], model, split))
    return {"schema": SCHEMA, "datasetKey": manifest["datasetKey"], "inputIntegrity": "COMPLETE",
            "researchVersion": RESEARCH_VERSION, "states": regimes, "labels": labels, "evaluations": evaluations}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    ledger = build_ledger(args.data_dir, args.manifest)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "states": len(ledger["states"]), "labels": len(ledger["labels"]),
                      "evaluations": len(ledger["evaluations"]), "integrity": ledger["inputIntegrity"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
