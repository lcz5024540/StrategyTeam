#!/usr/bin/env python3
"""Ablate only the Java combination entry's common trend-regime prerequisite.

This is deliberately a *candidate-quality* experiment.  It preserves the
ensemble direction, each model's own structure rules, and the same frozen
15-minute candles.  The only counterfactual is that a non-FLAT ensemble signal
may reach model evaluation when its regime is RANGE or LOW_VOLATILITY.

It does not imitate order-book, account, policy, portfolio or exit decisions,
and cannot submit an order.  The standardized next-open entry plus fixed
forward horizon lets us judge the entry filter without mixing in exit logic.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass, replace
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
PYTHON_SOURCE = ROOT / "htx-trend-python" / "src"
if str(PYTHON_SOURCE) not in sys.path:
    sys.path.insert(0, str(PYTHON_SOURCE))

from htx_trend.java_entry_contract import JavaEntryConfig, JavaTrendSignal, candidates, evaluate
from htx_trend.market_data import Candle
from htx_trend.models import TrendSide
from htx_trend_backtest import load_bars

SCHEMA = "htx-trend-entry-regime-ablation-v1"
PERIOD = "15min"
MINIMUM_CANDLES = 125
LIVE_ENTRY_WINDOW_BARS = 160


@dataclass(frozen=True)
class Outcome:
    variant: str
    cohort: str
    model: str
    side: str
    bar_time: int
    entry_price: float
    exit_price: float
    net_pnl: float
    max_favorable_pnl: float
    max_adverse_pnl: float


class Metrics:
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
            "candidateCount": self.count,
            "winCount": self.wins,
            "lossCount": self.losses,
            "winRate": self.wins / self.count if self.count else 0.0,
            "netPnl": self.net,
            "expectancy": self.net / self.count if self.count else 0.0,
            "profitFactor": self.gross_profit / self.gross_loss if self.gross_loss else (999.0 if self.gross_profit else 0.0),
            "feeIncluded": True,
        }


def _manifest(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported research manifest schema")
    if value.get("inputIntegrity") != "COMPLETE":
        raise ValueError("entry-regime ablation requires a COMPLETE frozen dataset")
    if value.get("periods") != [PERIOD]:
        raise ValueError("entry-regime ablation is intentionally scoped to a COMPLETE 15min-only frozen dataset")
    return value


def _candles(bars) -> list[Candle]:
    return [Candle(bar.ts, Decimal(str(bar.open)), Decimal(str(bar.high)), Decimal(str(bar.low)), Decimal(str(bar.close)),
                   Decimal(str(bar.amount)), None, Decimal(str(bar.turnover))) for bar in bars]


def relaxed_regime(signal: JavaTrendSignal) -> JavaTrendSignal:
    """Remove only the common regime precondition while retaining direction facts."""
    if signal.side is TrendSide.LONG:
        return replace(signal, regime="TRENDING_UP")
    if signal.side is TrendSide.SHORT:
        return replace(signal, regime="TRENDING_DOWN")
    return signal


def _cohort(start: int, end: int, bar_time: int) -> str:
    span = end - start
    if bar_time < start + span // 2:
        return "TRAIN"
    if bar_time < start + span * 3 // 4:
        return "VALIDATION"
    return "OUT_OF_SAMPLE"


def _price(side: TrendSide, raw: float, bps: float, entering: bool) -> float:
    adverse = bps / 10_000.0
    if side is TrendSide.LONG:
        return raw * (1.0 + adverse) if entering else raw * (1.0 - adverse)
    return raw * (1.0 - adverse) if entering else raw * (1.0 + adverse)


def _pnl(side: TrendSide, entry: float, exit_price: float, notional: float, fee_rate: float) -> float:
    quantity = notional / entry
    gross = (exit_price - entry) * quantity if side is TrendSide.LONG else (entry - exit_price) * quantity
    return gross - (entry + exit_price) * quantity * fee_rate


def _outcome(variant: str, model: str, side: TrendSide, bar_time: int, next_bar, future: list,
             start: int, end: int, notional: float, fee_rate: float, slippage_bps: float) -> Outcome:
    entry = _price(side, next_bar.open, slippage_bps, True)
    exit_price = _price(side, future[-1].close, slippage_bps, False)
    max_favorable = max((_price(side, bar.high if side is TrendSide.LONG else bar.low, slippage_bps, False)
                         for bar in future), default=exit_price)
    max_adverse = min((_price(side, bar.low if side is TrendSide.LONG else bar.high, slippage_bps, False)
                       for bar in future), default=exit_price)
    return Outcome(variant, _cohort(start, end, bar_time), model, side.value, bar_time, entry, exit_price,
                   _pnl(side, entry, exit_price, notional, fee_rate),
                   _pnl(side, entry, max_favorable, notional, fee_rate),
                   _pnl(side, entry, max_adverse, notional, fee_rate))


def _best_per_model(values: Iterable) -> dict[str, object]:
    """Java may emit multiple FIB confirmation variants; retain its best score per model/bar."""
    selected: dict[str, object] = {}
    for candidate in values:
        previous = selected.get(candidate.model.value)
        if previous is None or candidate.score > previous.score:
            selected[candidate.model.value] = candidate
    return selected


def run(data_dir: Path, manifest_path: Path, horizon_bars: int = 16, notional: float = 200.0,
        fee_rate: float = 0.0004, slippage_bps: float = 2.0,
        window_bars: int = LIVE_ENTRY_WINDOW_BARS) -> dict:
    manifest = _manifest(manifest_path)
    start = int(datetime.fromisoformat(manifest["inputStartAt"]).timestamp())
    end = int(datetime.fromisoformat(manifest["inputEndAt"]).timestamp())
    config = JavaEntryConfig()
    outcomes: list[Outcome] = []
    funnel = {"evaluatedBars": 0, "directionalSignals": 0, "baselineCandidates": 0, "relaxedCandidates": 0,
              "addedByRegimeAblation": 0, "byOriginalRegime": {}}
    if window_bars < MINIMUM_CANDLES:
        raise ValueError(f"window_bars must be at least {MINIMUM_CANDLES}")
    for symbol in manifest["contracts"]:
        bars = load_bars(data_dir, symbol, PERIOD)
        if len(bars) < window_bars + horizon_bars + 1:
            raise ValueError(f"{symbol} lacks enough frozen 15min bars")
        normalized = _candles(bars)
        for index in range(window_bars - 1, len(normalized) - horizon_bars - 1):
            # Production asks HTX for a bounded current window (160 bars by
            # default).  The replay must use the identical prefix length;
            # feeding all historical bars changes EMA and MACD warmup behavior.
            window = normalized[index + 1 - window_bars:index + 1]
            signal = evaluate(window, config)
            funnel["evaluatedBars"] += 1
            if signal.side is TrendSide.FLAT:
                continue
            funnel["directionalSignals"] += 1
            baseline = _best_per_model(candidates(window, signal, config))
            relaxed = _best_per_model(candidates(window, relaxed_regime(signal), config))
            funnel["baselineCandidates"] += len(baseline)
            funnel["relaxedCandidates"] += len(relaxed)
            added = set(relaxed) - set(baseline)
            funnel["addedByRegimeAblation"] += len(added)
            if added:
                funnel["byOriginalRegime"][signal.regime] = funnel["byOriginalRegime"].get(signal.regime, 0) + len(added)
            next_bar, future = bars[index + 1], bars[index + 2:index + 2 + horizon_bars]
            for model, item in baseline.items():
                outcomes.append(_outcome("BASELINE", model, item.side, item.bar_time, next_bar, future, start, end,
                                         notional, fee_rate, slippage_bps))
            for model in added:
                item = relaxed[model]
                outcomes.append(_outcome("REGIME_REMOVED_ONLY", model, item.side, item.bar_time, next_bar, future,
                                         start, end, notional, fee_rate, slippage_bps))

    metrics: dict[str, dict[str, Metrics]] = {}
    for row in outcomes:
        key = f"{row.variant}:{row.model}:{row.side}"
        metrics.setdefault(key, {}).setdefault(row.cohort, Metrics()).add(row.net_pnl)
        metrics[key].setdefault("ALL", Metrics()).add(row.net_pnl)
    rendered = {key: {cohort: bucket.report() for cohort, bucket in sorted(value.items())}
                for key, value in sorted(metrics.items())}
    return {"schema": SCHEMA, "decision": "OBSERVE_ONLY", "datasetKey": manifest["datasetKey"],
            "inputIntegrity": manifest["inputIntegrity"], "scope": {
                "removedCondition": "common candidates() regime in TRENDING_UP/TRENDING_DOWN",
                "preserved": ["ensemble direction and score", "Donchian/Fibonacci/EMA model structures",
                              "same frozen K-lines", "same next-open cost model"],
                "notEvaluated": ["policy routing", "market breadth", "order book", "funding", "margin", "portfolio", "exit engine"],
                "entryWindowBars": window_bars, "horizonBars": horizon_bars, "notionalUsdt": notional, "feeRate": fee_rate,
                "slippageBpsEachSide": slippage_bps},
            "funnel": funnel, "metrics": rendered,
            "outcomes": [asdict(row) for row in outcomes]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--horizon-bars", type=int, default=16)
    parser.add_argument("--window-bars", type=int, default=LIVE_ENTRY_WINDOW_BARS)
    args = parser.parse_args()
    if args.horizon_bars < 1:
        raise SystemExit("--horizon-bars must be positive")
    result = run(args.data_dir, args.manifest, args.horizon_bars, window_bars=args.window_bars)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "funnel": result["funnel"], "groups": len(result["metrics"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
