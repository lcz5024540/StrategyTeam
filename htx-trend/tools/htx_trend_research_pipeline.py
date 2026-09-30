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
from datetime import datetime
from pathlib import Path
from typing import Iterable, Sequence
from zoneinfo import ZoneInfo

from htx_trend_backtest import Bar, adx, ema, load_bars
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
TZ = ZoneInfo("Asia/Shanghai")


class _CompletedBars(Sequence[Bar]):
    """A completed-bar prefix that avoids copying the whole history per candidate."""

    def __init__(self, values: list[Bar], stop: int):
        self._values = values
        self._stop = stop

    def __len__(self) -> int:
        return self._stop

    def __getitem__(self, item):
        if isinstance(item, slice):
            return self._values[:self._stop][item]
        index = item + self._stop if item < 0 else item
        if index < 0 or index >= self._stop:
            raise IndexError(index)
        return self._values[index]


def _manifest_epoch(value: str) -> int:
    """Accept both legacy naive timestamps and ISO-8601 timestamps with offsets."""
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=TZ)
    return int(parsed.timestamp())


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


def _rolling_entry_features(bars: list[Bar], config: ModelConfig) -> dict[str, list[float]]:
    """Precompute the O(n) features used by the model prefilter."""
    count = len(bars)
    fast, slow, atr, vwap, adx = ([0.0] * count for _ in range(5))
    if not bars:
        return {"fast": fast, "slow": slow, "atr": atr, "vwap": vwap, "adx": adx}
    alpha_fast, alpha_slow = 2 / (config.fast_ema + 1), 2 / (config.slow_ema + 1)
    fast_value = slow_value = bars[0].close
    tr_prefix, plus_prefix, minus_prefix, dx_prefix = ([0.0] * (count + 1) for _ in range(4))
    volume_prefix, price_volume_prefix = [0.0] * (count + 1), [0.0] * (count + 1)
    for index, bar in enumerate(bars):
        if index:
            fast_value += (bar.close - fast_value) * alpha_fast
            slow_value += (bar.close - slow_value) * alpha_slow
            previous = bars[index - 1]
            tr_prefix[index + 1] = tr_prefix[index] + max(abs(bar.high - bar.low), abs(bar.high - previous.close), abs(bar.low - previous.close))
            up, down = bar.high - previous.high, previous.low - bar.low
            plus_prefix[index + 1] = plus_prefix[index] + (up if up > down and up > 0 else 0.0)
            minus_prefix[index + 1] = minus_prefix[index] + (down if down > up and down > 0 else 0.0)
        else:
            tr_prefix[index + 1] = tr_prefix[index]
            plus_prefix[index + 1], minus_prefix[index + 1] = plus_prefix[index], minus_prefix[index]
        fast[index], slow[index] = fast_value, slow_value
        tr_start = max(1, index - config.atr_period + 1)
        tr_count = index - tr_start + 1
        atr[index] = (tr_prefix[index + 1] - tr_prefix[tr_start]) / tr_count if tr_count > 0 else 0.0
        if index:
            dx_start = max(1, index - config.atr_period + 1)
            total_tr = tr_prefix[index + 1] - tr_prefix[dx_start]
            if total_tr:
                plus_di = (plus_prefix[index + 1] - plus_prefix[dx_start]) * 100.0 / total_tr
                minus_di = (minus_prefix[index + 1] - minus_prefix[dx_start]) * 100.0 / total_tr
                denominator = plus_di + minus_di
                dx = 0.0 if denominator <= 0 else abs(plus_di - minus_di) * 100.0 / denominator
            else:
                dx = 0.0
            dx_prefix[index + 1] = dx_prefix[index] + dx
            adx[index] = (dx_prefix[index + 1] - dx_prefix[dx_start]) / (index - dx_start + 1)
        else:
            dx_prefix[index + 1] = dx_prefix[index]
        volume = bar.turnover or bar.amount
        volume_prefix[index + 1] = volume_prefix[index] + volume
        price_volume_prefix[index + 1] = price_volume_prefix[index] + ((bar.high + bar.low + bar.close) / 3.0) * volume
        vwap_start = max(0, index - config.vwap_window + 1)
        total_volume = volume_prefix[index + 1] - volume_prefix[vwap_start]
        vwap[index] = (price_volume_prefix[index + 1] - price_volume_prefix[vwap_start]) / total_volume if total_volume else 0.0
    return {"fast": fast, "slow": slow, "atr": atr, "vwap": vwap, "adx": adx}


def _potential_models(bars: list[Bar], index: int, config: ModelConfig,
                      features: dict[str, list[float]]) -> dict[Side, set[str]]:
    """Cheap, exact prefilter for the three model predicates.

    The model implementations remain the source of truth.  This only avoids
    rebuilding every indicator history for sides/models that cannot possibly
    pass their own price predicates on the completed bar.
    """
    last, previous = bars[index], bars[index - 1]
    fast, slow, value_atr, vwap = (features["fast"][index], features["slow"][index],
                                   features["atr"][index], features["vwap"][index])
    if value_atr <= 0:
        return {Side.LONG: set(), Side.SHORT: set()}
    structure = bars[index - config.donchian_lookback:index]
    high, low = max(bar.high for bar in structure), min(bar.low for bar in structure)
    span = high - low
    values = {Side.LONG: set(), Side.SHORT: set()}
    for side, aligned in ((Side.LONG, fast > slow), (Side.SHORT, fast < slow)):
        if not aligned:
            continue
        if (side is Side.LONG and last.close > high) or (side is Side.SHORT and last.close < low):
            values[side].add("DONCHIAN_BREAKOUT")
        if span >= config.fibonacci_min_atr_range * value_atr:
            lower, upper = ((high - config.fib_upper * span, high - config.fib_lower * span)
                            if side is Side.LONG else
                            (low + config.fib_lower * span, low + config.fib_upper * span))
            recovering = last.close > previous.close if side is Side.LONG else last.close < previous.close
            if lower <= last.close <= upper and recovering:
                values[side].add("FIB_PULLBACK_CONTINUATION")
        momentum = ((last.close > vwap and last.close > previous.close) if side is Side.LONG
                    else (last.close < vwap and last.close < previous.close))
        if vwap > 0 and abs(last.close - fast) <= .35 * value_atr and momentum:
            values[side].add("EMA_VWAP_MOMENTUM")
    return values


def build_ledger(data_dir: Path, manifest_path: Path,
                 config: ModelConfig = ModelConfig(),
                 execution: ExecutionAssumptions = ExecutionAssumptions()) -> dict:
    manifest = _manifest(manifest_path)
    symbols = manifest["contracts"]
    start = _manifest_epoch(manifest["inputStartAt"])
    end = _manifest_epoch(manifest["inputEndAt"])
    all_bars = {symbol: {period: load_bars(data_dir, symbol, period) for period in manifest["periods"]}
                for symbol in symbols}
    direction_series = {symbol: {period: _direction_series(bars) for period, bars in periods.items()}
                        for symbol, periods in all_bars.items()}
    breadth_by_bar = _market_breadth(all_bars)
    models = {model.name: model for model in (DonchianBreakoutModel(), FibonacciPullbackModel(), EmaVwapMomentumModel())}
    regimes, labels = [], []
    seen_regimes: set[str] = set()
    for symbol in symbols:
        entry_bars = all_bars[symbol][ENTRY_PERIOD]
        features = _rolling_entry_features(entry_bars, config)
        for index in range(max(config.slow_ema, config.donchian_lookback) + 1, len(entry_bars) - HORIZON_BARS - 1):
            bar_time = entry_bars[index].ts
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
            potentials = _potential_models(entry_bars, index, config, features)
            for side in (Side.LONG, Side.SHORT):
                selected = potentials[side]
                if not selected:
                    continue
                completed = _CompletedBars(entry_bars, index + 1)
                context = SignalContext(symbol, bar_time, completed, side, f"regime={state}", {
                    "fast_ema": features["fast"][index], "slow_ema": features["slow"][index],
                    "atr": features["atr"][index], "vwap": features["vwap"][index], "adx": features["adx"][index],
                })
                for model_name in sorted(selected):
                    model = models[model_name]
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
