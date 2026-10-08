#!/usr/bin/env python3
"""Evaluate read-only RSI, MACD and participation evidence on frozen model labels.

This program never talks to HTX, reads credentials, writes a database, or changes
strategy parameters.  It bins the exact completed-bar evidence attached to frozen
model labels so a later policy change can demonstrate incremental, post-fee value
instead of adding a new indicator to an aggregate score by intuition.
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from htx_trend_backtest import Bar, load_bars

SCHEMA = "htx-trend-indicator-ablation-v1"
ENTRY_PERIOD = "15min"
RSI_PERIOD = 14
MACD_FAST, MACD_SLOW, MACD_SIGNAL = 12, 26, 9
PARTICIPATION_LOOKBACK = 20
MIN_SAMPLE = 30
SPLITS = ("ALL", "TRAIN", "VALIDATION", "OUT_OF_SAMPLE")


def ema(values: list[float], period: int) -> list[float]:
    if not values:
        return []
    multiplier = 2.0 / (period + 1.0)
    result = [values[0]]
    for value in values[1:]:
        result.append((value - result[-1]) * multiplier + result[-1])
    return result


def rsi(closes: list[float]) -> float | None:
    if len(closes) <= RSI_PERIOD:
        return None
    gains = losses = 0.0
    for index in range(1, RSI_PERIOD + 1):
        delta = closes[index] - closes[index - 1]
        gains += max(delta, 0.0)
        losses += max(-delta, 0.0)
    average_gain, average_loss = gains / RSI_PERIOD, losses / RSI_PERIOD
    for index in range(RSI_PERIOD + 1, len(closes)):
        delta = closes[index] - closes[index - 1]
        average_gain = (average_gain * (RSI_PERIOD - 1) + max(delta, 0.0)) / RSI_PERIOD
        average_loss = (average_loss * (RSI_PERIOD - 1) + max(-delta, 0.0)) / RSI_PERIOD
    if average_loss == 0.0:
        return 100.0
    return 100.0 - 100.0 / (1.0 + average_gain / average_loss)


def observation(bars: list[Bar], index: int) -> dict:
    """Only evaluates the prefix ending at index; future bars cannot affect output."""
    prefix = bars[:index + 1]
    if len(prefix) < MACD_SLOW + MACD_SIGNAL:
        return {"status": "UNAVAILABLE", "reason": "insufficient_completed_bars"}
    closes = [bar.close for bar in prefix]
    fast, slow = ema(closes, MACD_FAST), ema(closes, MACD_SLOW)
    line = [left - right for left, right in zip(fast, slow)]
    signal = ema(line, MACD_SIGNAL)
    histogram = line[-1] - signal[-1]
    prior_histogram = line[-2] - signal[-2]
    volume = prefix[-1].amount
    turnover = prefix[-1].turnover
    volume_history = [bar.amount for bar in prefix[-PARTICIPATION_LOOKBACK - 1:-1]]
    turnover_history = [bar.turnover for bar in prefix[-PARTICIPATION_LOOKBACK - 1:-1]]
    volume_history = [value for value in volume_history if value > 0]
    turnover_history = [value for value in turnover_history if value > 0]
    volume_ratio = volume / (sum(volume_history) / len(volume_history)) if volume_history and volume > 0 else None
    turnover_ratio = turnover / (sum(turnover_history) / len(turnover_history)) if turnover_history and turnover > 0 else None
    candle = prefix[-1]
    candle_range = candle.high - candle.low
    return {
        "status": "AVAILABLE",
        "rsi": rsi(closes),
        "macdHistogram": histogram,
        "macdHistogramSlope": histogram - prior_histogram,
        "volumeRatio": volume_ratio,
        "turnoverRatio": turnover_ratio,
        "closeLocation": ((candle.close - candle.low) / candle_range) if candle_range > 0 else None,
        "upperWickRatio": ((candle.high - max(candle.open, candle.close)) / candle_range) if candle_range > 0 else None,
        "lowerWickRatio": ((min(candle.open, candle.close) - candle.low) / candle_range) if candle_range > 0 else None,
    }


def rsi_bucket(value: float | None) -> str:
    if value is None:
        return "UNAVAILABLE"
    if value >= 70:
        return "STRONG_UP"
    if value >= 55:
        return "UP"
    if value > 45:
        return "NEUTRAL"
    if value > 30:
        return "DOWN"
    return "STRONG_DOWN"


def directional_macd(side: str, values: dict) -> str:
    histogram, slope = values.get("macdHistogram"), values.get("macdHistogramSlope")
    if histogram is None or slope is None:
        return "UNAVAILABLE"
    aligned = histogram > 0 and slope > 0 if side == "LONG" else histogram < 0 and slope < 0
    opposed = histogram < 0 and slope < 0 if side == "LONG" else histogram > 0 and slope > 0
    return "ALIGNED_ACCELERATION" if aligned else "OPPOSED_ACCELERATION" if opposed else "MIXED"


def volume_bucket(value: float | None) -> str:
    if value is None:
        return "UNAVAILABLE"
    if value < 0.8:
        return "CONTRACTION"
    if value < 1.1:
        return "NORMAL"
    if value < 1.5:
        return "EXPANSION"
    return "STRONG_EXPANSION"


def _epoch(value: str) -> int:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=ZoneInfo("Asia/Shanghai"))
    return int(parsed.timestamp())


def _split(bar_time: int, start: int, end: int) -> str:
    span = end - start
    if bar_time < start + span // 2:
        return "TRAIN"
    if bar_time < start + span * 3 // 4:
        return "VALIDATION"
    return "OUT_OF_SAMPLE"


class _Metrics:
    """Streaming post-fee metrics; reports remain small even with a large frozen ledger."""

    def __init__(self) -> None:
        self.count = self.wins = self.losses = 0
        self.net = self.gross_profit = self.gross_loss = 0.0

    def add(self, pnl: float) -> None:
        self.count += 1
        self.net += pnl
        if pnl > 0:
            self.wins += 1
            self.gross_profit += pnl
        elif pnl < 0:
            self.losses += 1
            self.gross_loss -= pnl

    def report(self) -> dict:
        return {
            "tradeCount": self.count, "winCount": self.wins, "lossCount": self.losses,
            "winRate": self.wins / self.count if self.count else 0.0,
            "netPnl": self.net, "expectancy": self.net / self.count if self.count else 0.0,
            "profitFactor": self.gross_profit / self.gross_loss if self.gross_loss else (999.0 if self.gross_profit else 0.0),
            "feeIncluded": True, "sufficientSample": self.count >= MIN_SAMPLE,
        }


def observation_series(bars: list[Bar]) -> dict[int, dict]:
    """Compute every completed-bar observation once per symbol in O(n)."""
    if not bars:
        return {}
    closes = [bar.close for bar in bars]
    fast, slow = ema(closes, MACD_FAST), ema(closes, MACD_SLOW)
    line = [left - right for left, right in zip(fast, slow)]
    signal = ema(line, MACD_SIGNAL)
    values: dict[int, dict] = {}
    average_gain = average_loss = 0.0
    for index, bar in enumerate(bars):
        if index == RSI_PERIOD:
            gains = losses = 0.0
            for offset in range(1, RSI_PERIOD + 1):
                delta = closes[offset] - closes[offset - 1]
                gains += max(delta, 0.0)
                losses += max(-delta, 0.0)
            average_gain, average_loss = gains / RSI_PERIOD, losses / RSI_PERIOD
        elif index > RSI_PERIOD:
            delta = closes[index] - closes[index - 1]
            average_gain = (average_gain * (RSI_PERIOD - 1) + max(delta, 0.0)) / RSI_PERIOD
            average_loss = (average_loss * (RSI_PERIOD - 1) + max(-delta, 0.0)) / RSI_PERIOD
        if index < MACD_SLOW + MACD_SIGNAL - 1:
            continue
        rsi_value = 100.0 if average_loss == 0.0 else 100.0 - 100.0 / (1.0 + average_gain / average_loss)
        volume_history = [item.amount for item in bars[max(0, index - PARTICIPATION_LOOKBACK):index]]
        turnover_history = [item.turnover for item in bars[max(0, index - PARTICIPATION_LOOKBACK):index]]
        volume_history = [value for value in volume_history if value > 0]
        turnover_history = [value for value in turnover_history if value > 0]
        volume_ratio = bar.amount / (sum(volume_history) / len(volume_history)) \
            if volume_history and bar.amount > 0 else None
        turnover_ratio = bar.turnover / (sum(turnover_history) / len(turnover_history)) \
            if turnover_history and bar.turnover > 0 else None
        candle_range = bar.high - bar.low
        values[bar.ts] = {
            "status": "AVAILABLE", "rsi": rsi_value,
            "macdHistogram": line[index] - signal[index],
            "macdHistogramSlope": (line[index] - signal[index]) - (line[index - 1] - signal[index - 1]),
            "volumeRatio": volume_ratio,
            "turnoverRatio": turnover_ratio,
            "closeLocation": (bar.close - bar.low) / candle_range if candle_range > 0 else None,
            "upperWickRatio": (bar.high - max(bar.open, bar.close)) / candle_range if candle_range > 0 else None,
            "lowerWickRatio": (min(bar.open, bar.close) - bar.low) / candle_range if candle_range > 0 else None,
        }
    return values


def _add_metric(target: dict, model: str, bucket: str | None, split: str, pnl: float) -> None:
    if bucket is None:
        values = target.setdefault(model, {})
    else:
        values = target.setdefault(model, {}).setdefault(bucket, {})
    values.setdefault("ALL", _Metrics()).add(pnl)
    values.setdefault(split, _Metrics()).add(pnl)


def _metric_reports(values: dict) -> dict:
    if values and all(isinstance(value, _Metrics) for value in values.values()):
        return {split: values.get(split, _Metrics()).report() for split in SPLITS}
    return {key: _metric_reports(value) for key, value in sorted(values.items())}


def build_report(data_dir: Path, derived_ledger_path: Path, manifest_path: Path) -> dict:
    ledger = json.loads(derived_ledger_path.read_text(encoding="utf-8"))
    if ledger.get("schema") != "htx-trend-research-derived-ledger-v1":
        raise ValueError("unsupported derived-ledger schema")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "htx-trend-research-dataset-manifest-v1" or manifest.get("inputIntegrity") != "COMPLETE":
        raise ValueError("indicator ablation requires a COMPLETE research manifest")
    if manifest.get("datasetKey") != ledger.get("datasetKey"):
        raise ValueError("derived ledger and manifest dataset keys differ")
    start, end = _epoch(manifest["inputStartAt"]), _epoch(manifest["inputEndAt"])
    if end <= start:
        raise ValueError("research manifest range is invalid")
    cache: dict[str, dict[int, dict]] = {}
    baseline: dict[str, dict[str, _Metrics]] = {}
    dimensions: dict[str, dict[str, dict[str, dict[str, _Metrics]]]] = {
        "rsiBucket": {}, "macdState": {}, "volumeState": {}, "turnoverState": {}
    }
    analyzed_labels = skipped_labels = 0
    for label in ledger.get("labels", []):
        symbol, bar_time = label.get("contractCode"), label.get("entryBarTime")
        if not symbol or not isinstance(bar_time, int):
            skipped_labels += 1
            continue
        if symbol not in cache:
            cache[symbol] = observation_series(load_bars(data_dir, symbol, ENTRY_PERIOD))
        factors = cache[symbol].get(bar_time)
        if factors is None:
            skipped_labels += 1
            continue
        model, side, pnl = label.get("modelCode"), label.get("side"), label.get("netProfit")
        if not model or side not in {"LONG", "SHORT"} or pnl is None:
            skipped_labels += 1
            continue
        pnl_value = float(pnl)
        analyzed_labels += 1
        split = _split(bar_time, start, end)
        for scope in (model, f"{model}:{side}"):
            _add_metric(baseline, scope, None, split, pnl_value)
        bucket_values = {"rsiBucket": rsi_bucket(factors["rsi"]),
                         "macdState": directional_macd(side, factors),
                         "volumeState": volume_bucket(factors["volumeRatio"]),
                         "turnoverState": volume_bucket(factors["turnoverRatio"])}
        for dimension, bucket in bucket_values.items():
            for scope in (model, f"{model}:{side}"):
                _add_metric(dimensions[dimension], scope, bucket, split, pnl_value)
    return {
        "schema": SCHEMA, "readOnly": True, "datasetKey": ledger.get("datasetKey"),
        "inputIntegrity": ledger.get("inputIntegrity"), "analyzedLabelCount": analyzed_labels,
        "skippedLabelCount": skipped_labels,
        "baselineByModel": _metric_reports(baseline), "factorBuckets": _metric_reports(dimensions),
        "ahr999": {"status": "NOT_AVAILABLE", "reason": "canonical AHR999 requires an approved external long-horizon source; no HTX price proxy is labelled AHR999"},
        "decision": "OBSERVE_ONLY",
        "promotionRule": "no factor may enter a live gate until walk-forward and paper results show post-fee incremental value by model and market state",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--derived-ledger", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = build_report(args.data_dir, args.derived_ledger, args.manifest)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "rows": report["analyzedLabelCount"], "decision": report["decision"]}))


if __name__ == "__main__":
    main()
