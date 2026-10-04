#!/usr/bin/env python3
"""Audit frozen HTX research inputs for future-data and indicator warm-up bias.

The checks are deliberately independent from the live HTX service.  They use
only the frozen public-candle dataset and must pass before an acceptance report
can promote any model to a paper candidate.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from htx_trend_backtest import Bar, load_bars
from htx_trend_research_engine import (
    DonchianBreakoutModel, EmaVwapMomentumModel, ExecutionAssumptions,
    FibonacciPullbackModel, ModelConfig, Side, SignalContext,
)
from htx_trend_research_pipeline import _CompletedBars, _rolling_entry_features

SCHEMA = "htx-trend-research-quality-gate-v1"
ENTRY_PERIOD = "15min"
WARMUPS = (64, 128, 256)
REQUIRED_WARMUP = 256
MAX_RELATIVE_DELTA = 0.0005
MAX_ADX_DELTA = 0.05
PRICE_COMPARISON_DECIMALS = 9


def _manifest(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported research manifest schema")
    if value.get("inputIntegrity") != "COMPLETE":
        raise ValueError("quality gate requires a COMPLETE frozen dataset")
    return value


def _sample_indexes(count: int) -> list[int]:
    """Choose deterministic late-enough bars for every contract.

    The earliest 25% probe still has substantially more than the required
    256 bars in a three-month 15m dataset.  The last probe leaves one bar for
    the usual entry simulation, without reading it in this audit.
    """
    minimum = REQUIRED_WARMUP
    latest = count - 2
    if latest < minimum:
        return []
    return sorted({max(minimum, min(latest, round(latest * fraction))) for fraction in (.25, .50, .75)})


def _indicator_snapshot(bars: list[Bar], index: int | None = None) -> dict[str, float]:
    features = _rolling_entry_features(bars, ModelConfig())
    index = len(bars) - 1 if index is None else index
    return {"fastEma": features["fast"][index], "slowEma": features["slow"][index],
            "atr": features["atr"][index], "vwap": features["vwap"][index], "adx": features["adx"][index]}


def _signal_snapshot(bars: list[Bar], symbol: str, index: int | None = None) -> list[dict]:
    """Return only entry facts which must be known at the completed bar."""
    features = _rolling_entry_features(bars, ModelConfig())
    index = len(bars) - 1 if index is None else index
    values = {"fast_ema": features["fast"][index], "slow_ema": features["slow"][index],
              "atr": features["atr"][index], "vwap": features["vwap"][index], "adx": features["adx"][index]}
    completed = _CompletedBars(bars, index + 1)
    rows = []
    for side in (Side.LONG, Side.SHORT):
        context = SignalContext(symbol, bars[index].ts, completed, side, "quality-gate", values)
        for model in (DonchianBreakoutModel(), FibonacciPullbackModel(), EmaVwapMomentumModel()):
            for signal in model.evaluate(context, ModelConfig(), ExecutionAssumptions()):
                plan = signal.price_plan
                rows.append({"model": signal.model, "side": signal.side.value,
                             # A binary floating-point tail is not a distinct
                             # research outcome. Actual execution still uses
                             # the contract price tick from HTX.
                             "trigger": round(plan.trigger_price, PRICE_COMPARISON_DECIMALS),
                             "ideal": round(plan.ideal_entry, PRICE_COMPARISON_DECIMALS),
                             "worst": round(plan.worst_entry, PRICE_COMPARISON_DECIMALS),
                             "invalidation": round(plan.invalidation_price, PRICE_COMPARISON_DECIMALS),
                             "target": round(plan.target_price, PRICE_COMPARISON_DECIMALS),
                             "style": plan.order_style.value})
    return rows


def _mutate_future(bars: list[Bar], index: int) -> list[Bar]:
    """Change only future candles; a causal calculation must be unchanged at index."""
    result = list(bars)
    for offset in range(index + 1, len(result)):
        bar = result[offset]
        multiplier = 1.70 if (offset - index) % 2 else .55
        result[offset] = Bar(bar.ts, bar.open * multiplier, bar.high * multiplier,
                             bar.low * multiplier, bar.close * multiplier, bar.amount, bar.turnover)
    return result


def _relative_delta(left: float, right: float) -> float:
    denominator = max(abs(left), abs(right), 1e-12)
    return abs(left - right) / denominator


def _indicator_deltas(reference: dict[str, float], value: dict[str, float]) -> dict[str, float]:
    return {key: abs(reference[key] - value[key]) if key == "adx" else _relative_delta(reference[key], value[key])
            for key in reference}


def _within_tolerance(deltas: dict[str, float]) -> bool:
    return all(value <= (MAX_ADX_DELTA if key == "adx" else MAX_RELATIVE_DELTA)
               for key, value in deltas.items())


def _check_contract(symbol: str, bars: list[Bar]) -> dict:
    lookahead, recursive = [], []
    for index in _sample_indexes(len(bars)):
        reference_prefix = bars[:index + 1]
        reference_indicators = _indicator_snapshot(bars, index)
        reference_signals = _signal_snapshot(bars, symbol, index)

        mutated = _mutate_future(bars, index)
        mutated_indicators = _indicator_snapshot(mutated, index)
        mutated_signals = _signal_snapshot(mutated, symbol, index)
        lookahead.append({"barTime": bars[index].ts,
                          "indicatorDeltas": _indicator_deltas(reference_indicators, mutated_indicators),
                          "signalChanged": reference_signals != mutated_signals,
                          "passed": reference_indicators == mutated_indicators and reference_signals == mutated_signals})

        benchmark_signals = reference_signals
        warmup_rows = []
        for warmup in WARMUPS:
            if index + 1 < warmup:
                continue
            window = bars[index - warmup + 1:index + 1]
            indicators = _indicator_snapshot(window)
            signals = _signal_snapshot(window, symbol)
            deltas = _indicator_deltas(reference_indicators, indicators)
            warmup_rows.append({"warmup": warmup, "indicatorDeltas": deltas,
                                "signalChanged": benchmark_signals != signals,
                                "stable": _within_tolerance(deltas) and benchmark_signals == signals})
        required = next((row for row in warmup_rows if row["warmup"] == REQUIRED_WARMUP), None)
        recursive.append({"barTime": bars[index].ts, "warmups": warmup_rows,
                          "requiredWarmupStable": bool(required and required["stable"])})
    return {"contract": symbol, "lookahead": lookahead, "recursive": recursive}


def build_report(data_dir: Path, manifest_path: Path) -> dict:
    manifest = _manifest(manifest_path)
    contracts = []
    for symbol in manifest["contracts"]:
        contracts.append(_check_contract(symbol, load_bars(data_dir, symbol, ENTRY_PERIOD)))
    lookahead_failures = sum(not row["passed"] for item in contracts for row in item["lookahead"])
    warmup_failures = sum(not row["requiredWarmupStable"] for item in contracts for row in item["recursive"])
    decision = "PASS" if not lookahead_failures and not warmup_failures else "REJECT"
    return {"schema": SCHEMA, "datasetKey": manifest["datasetKey"], "inputIntegrity": "COMPLETE",
            "decision": decision,
            "thresholds": {"requiredWarmup": REQUIRED_WARMUP,
                           "maxRelativeIndicatorDelta": MAX_RELATIVE_DELTA,
                           "maxAbsoluteAdxDelta": MAX_ADX_DELTA},
            "summary": {"contractsChecked": len(contracts),
                        "lookaheadProbeCount": sum(len(item["lookahead"]) for item in contracts),
                        "lookaheadFailures": lookahead_failures,
                        "recursiveProbeCount": sum(len(item["recursive"]) for item in contracts),
                        "requiredWarmupFailures": warmup_failures},
            "contracts": contracts,
            "evidence": "future bars are mutated after each probe; indicators and candidates at the probe must remain unchanged. recursive stability compares 64/128/256-bar starts to the complete prefix."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = build_report(args.data_dir, args.manifest)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "decision": report["decision"], **report["summary"]}, ensure_ascii=False))
    if report["decision"] != "PASS":
        raise SystemExit("research quality gate rejected the dataset")


if __name__ == "__main__":
    main()
