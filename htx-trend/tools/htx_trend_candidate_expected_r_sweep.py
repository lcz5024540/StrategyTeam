#!/usr/bin/env python3
"""Sweep one candidate-state entry threshold on frozen HTX K-lines.

The live entry chain currently rejects most generated candidates before book and
account preflight because their structural expected-R is below 1.25.  This
research tool changes *only* that number.  It preserves completed 15-minute
bars, ensemble/model structures, momentum >= 60, EMA alignment, breadth,
mixed-market A-grade rules, and Fibonacci candle-quality rules.

It is read-only: it does not query HTX, credentials, a database, or write any
strategy configuration.  Its fixed-forward labels measure entry opportunity,
not the live exit engine or actual fills.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PYTHON_SOURCE = ROOT / "htx-trend-python" / "src"
if str(PYTHON_SOURCE) not in sys.path:
    sys.path.insert(0, str(PYTHON_SOURCE))

from htx_trend.java_entry_contract import JavaEntryConfig, candidates, evaluate
from htx_trend.market_data import Candle
from htx_trend.models import ModelCode, TrendSide
from htx_trend.strategy_context import (EntryStateConfig, FibonacciQuality, MarketContext, MarketRegime,
                                        evaluate_candidate_state)
from htx_trend_backtest import load_bars

SCHEMA = "htx-trend-candidate-expected-r-sweep-v1"
PERIOD = "15min"
WINDOW_BARS = 160
MINIMUM_CANDLES = 125
BREADTH_THRESHOLD = Decimal("0.70")
OPPOSITE_MAX = Decimal("0.30")
MIXED_SCORE = Decimal("92")
MIXED_EXPECTED_R = Decimal("1.75")
MIXED_MOMENTUM = Decimal("70")
FIB_MIN_VOLUME = Decimal("1")
FIB_MIN_BODY = Decimal("0.20")
FIB_MAX_COUNTER_WICK = Decimal("0.75")
FIB_MAX_RANGE_ATR = Decimal("2.50")


@dataclass
class Metrics:
    count: int = 0
    wins: int = 0
    losses: int = 0
    net: float = 0.0
    gross_profit: float = 0.0
    gross_loss: float = 0.0

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
            "tradeCount": self.count,
            "winCount": self.wins,
            "lossCount": self.losses,
            "winRate": self.wins / self.count if self.count else 0.0,
            "netPnl": self.net,
            "expectancy": self.net / self.count if self.count else 0.0,
            "profitFactor": self.gross_profit / self.gross_loss if self.gross_loss else (999.0 if self.gross_profit else 0.0),
            "feeIncluded": True,
        }


def _manifest(path: Path) -> dict:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported research manifest schema")
    if manifest.get("inputIntegrity") != "COMPLETE" or manifest.get("periods") != [PERIOD]:
        raise ValueError("expected-R sweep requires COMPLETE frozen 15-minute K-lines")
    return manifest


def _candles(bars) -> list[Candle]:
    return [Candle(bar.ts, Decimal(str(bar.open)), Decimal(str(bar.high)), Decimal(str(bar.low)), Decimal(str(bar.close)),
                   Decimal(str(bar.amount)), None, Decimal(str(bar.turnover))) for bar in bars]


def _split(start: int, end: int, bar_time: int) -> str:
    span = end - start
    if bar_time < start + span // 2:
        return "TRAIN"
    if bar_time < start + span * 3 // 4:
        return "VALIDATION"
    return "OUT_OF_SAMPLE"


def _market_context(signals) -> MarketContext:
    total = len(signals)
    longs = sum(item.side is TrendSide.LONG for item in signals.values())
    shorts = sum(item.side is TrendSide.SHORT for item in signals.values())
    long_ratio = Decimal(longs) / Decimal(total) if total else Decimal("0")
    short_ratio = Decimal(shorts) / Decimal(total) if total else Decimal("0")
    if long_ratio >= BREADTH_THRESHOLD and short_ratio <= OPPOSITE_MAX:
        return MarketContext(regime=MarketRegime.BROAD_UP, breadth_side=TrendSide.LONG, breadth_ratio=long_ratio)
    if short_ratio >= BREADTH_THRESHOLD and long_ratio <= OPPOSITE_MAX:
        return MarketContext(regime=MarketRegime.BROAD_DOWN, breadth_side=TrendSide.SHORT, breadth_ratio=short_ratio)
    return MarketContext(regime=MarketRegime.MIXED, breadth_side=TrendSide.FLAT,
                         breadth_ratio=max(long_ratio, short_ratio))


def _momentum(candidate, adx: Decimal) -> Decimal:
    return min(Decimal("100"), (candidate.score + adx) / Decimal("2"))


def _fib_quality_allowed(candidate, window: list[Candle], signal) -> tuple[bool, str]:
    if candidate.model is not ModelCode.FIB_PULLBACK_CONTINUATION:
        return True, "NOT_APPLICABLE"
    quality = FibonacciQuality.from_candles(window, candidate.side, signal.indicators.atr, signal.indicators.ema_fast)
    if not quality.ema_reclaimed:
        return False, "FIB_EMA_RECLAIM"
    if quality.volume_ratio is None or quality.volume_ratio < FIB_MIN_VOLUME:
        return False, "FIB_VOLUME"
    if quality.body_to_range is None or quality.body_to_range < FIB_MIN_BODY:
        return False, "FIB_BODY"
    if quality.range_to_atr is None or quality.range_to_atr > FIB_MAX_RANGE_ATR:
        return False, "FIB_RANGE_ATR"
    if quality.counter_wick_to_range is None or quality.counter_wick_to_range > FIB_MAX_COUNTER_WICK:
        return False, "FIB_COUNTER_WICK"
    return True, "PASS"


def _fixed_horizon_pnl(side: TrendSide, next_bar, future, *, notional: float, fee_rate: float,
                       slippage_bps: float) -> float:
    adverse = slippage_bps / 10_000.0
    entry = next_bar.open * (1 + adverse) if side is TrendSide.LONG else next_bar.open * (1 - adverse)
    raw_exit = future[-1].close
    exit_price = raw_exit * (1 - adverse) if side is TrendSide.LONG else raw_exit * (1 + adverse)
    quantity = notional / entry
    gross = (exit_price - entry) * quantity if side is TrendSide.LONG else (entry - exit_price) * quantity
    return gross - (entry + exit_price) * quantity * fee_rate


def _quality_gate(candidate, signal, window: list[Candle], context: MarketContext, *,
                  expected_r_threshold: Decimal, momentum_threshold: Decimal,
                  require_mixed_a_grade: bool) -> str:
    """Return PASS or the first production-equivalent gate in this research scope."""
    momentum = _momentum(candidate, signal.indicators.adx)
    state = evaluate_candidate_state(candidate.score, candidate.expected_r, momentum, candidate.side,
                                     signal.indicators.ema_fast, signal.indicators.ema_slow, signal.indicators.adx,
                                     context, config=EntryStateConfig(min_expected_r=expected_r_threshold,
                                                                        min_momentum_score=momentum_threshold))
    if not state.tradable:
        if candidate.expected_r < expected_r_threshold:
            return "EXPECTED_R"
        if momentum < momentum_threshold:
            return "MOMENTUM"
        if not ((candidate.side is TrendSide.LONG and signal.indicators.ema_fast > signal.indicators.ema_slow)
                or (candidate.side is TrendSide.SHORT and signal.indicators.ema_fast < signal.indicators.ema_slow)):
            return "EMA"
        if context.breadth_opposed(candidate.side):
            return "BREADTH_OPPOSED"
        return "STATE_SCORE"
    if require_mixed_a_grade and context.regime is MarketRegime.MIXED and not (
            candidate.score >= MIXED_SCORE and candidate.expected_r >= MIXED_EXPECTED_R and momentum >= MIXED_MOMENTUM):
        return "MIXED_A_GRADE"
    allowed, reason = _fib_quality_allowed(candidate, window, signal)
    return "PASS" if allowed else reason


def _best_per_model(values) -> dict[ModelCode, object]:
    result: dict[ModelCode, object] = {}
    for candidate in values:
        previous = result.get(candidate.model)
        if previous is None or candidate.score > previous.score:
            result[candidate.model] = candidate
    return result


def run(data_dir: Path, manifest_path: Path, *, thresholds: tuple[Decimal, ...], horizon_bars: int,
        sweep: str = "expected-r",
        notional: float = 200.0, fee_rate: float = 0.0004, slippage_bps: float = 2.0) -> dict:
    manifest = _manifest(manifest_path)
    start = int(datetime.fromisoformat(manifest["inputStartAt"]).timestamp())
    end = int(datetime.fromisoformat(manifest["inputEndAt"]).timestamp())
    symbols = tuple(manifest["contracts"])
    bars = {symbol: load_bars(data_dir, symbol, PERIOD) for symbol in symbols}
    if any(len(values) < WINDOW_BARS + horizon_bars + 1 for values in bars.values()):
        raise ValueError("frozen data has insufficient bars for one or more symbols")
    candles = {symbol: _candles(values) for symbol, values in bars.items()}
    config = JavaEntryConfig()
    counters = {str(value): {} for value in thresholds}
    metrics: dict[str, dict[str, Metrics]] = {str(value): {} for value in thresholds}
    generated = 0
    first_index = WINDOW_BARS - 1
    last_index = min(len(values) for values in bars.values()) - horizon_bars - 1

    def increment(threshold: Decimal, key: str) -> None:
        bucket = counters[str(threshold)]
        bucket[key] = bucket.get(key, 0) + 1

    for index in range(first_index, last_index):
        signals = {symbol: evaluate(candles[symbol][index + 1 - WINDOW_BARS:index + 1], config) for symbol in symbols}
        context = _market_context(signals)
        for symbol, signal in signals.items():
            if signal.side is TrendSide.FLAT:
                continue
            window = candles[symbol][index + 1 - WINDOW_BARS:index + 1]
            selected = _best_per_model(candidates(window, signal, config))
            for candidate in selected.values():
                generated += 1
                for threshold in thresholds:
                    expected_r_threshold = threshold if sweep == "expected-r" else Decimal("1.25")
                    momentum_threshold = threshold if sweep == "momentum" else Decimal("60")
                    reason = _quality_gate(candidate, signal, window, context,
                                           expected_r_threshold=expected_r_threshold,
                                           momentum_threshold=momentum_threshold,
                                           require_mixed_a_grade=(threshold != Decimal("0") if sweep == "mixed-market" else True))
                    increment(threshold, reason)
                    if reason != "PASS":
                        continue
                    next_bar, future = bars[symbol][index + 1], bars[symbol][index + 2:index + 2 + horizon_bars]
                    pnl = _fixed_horizon_pnl(candidate.side, next_bar, future, notional=notional,
                                              fee_rate=fee_rate, slippage_bps=slippage_bps)
                    split = _split(start, end, candidate.bar_time)
                    for group in ("ALL", split, candidate.model.value, f"{candidate.model.value}:{candidate.side.value}"):
                        metrics[str(threshold)].setdefault(group, Metrics()).add(pnl)

    rendered = {
        threshold: {
            "gateFunnel": dict(sorted(counters[threshold].items())),
            "metrics": {group: metric.report() for group, metric in sorted(values.items())},
        } for threshold, values in metrics.items()
    }
    baseline = str(Decimal("1.25") if sweep == "expected-r" else Decimal("60") if sweep == "momentum" else Decimal("1"))
    return {
        "schema": SCHEMA,
        "decision": "OBSERVE_ONLY",
        "readOnly": True,
        "datasetKey": manifest["datasetKey"],
        "inputIntegrity": manifest["inputIntegrity"],
        "scope": {
            "changedCondition": ("candidate trend-state minExpectedR only" if sweep == "expected-r"
                                 else "candidate trend-state minMomentumScore only" if sweep == "momentum"
                                 else "mixed-market A-grade prerequisite only (1=required, 0=bypassed)"),
            "thresholds": [str(value) for value in thresholds],
            "preserved": [
                "TRENDING_UP/TRENDING_DOWN common prerequisite",
                "ensemble direction and model structure",
                "momentum >= 60, EMA alignment, breadth/counter-market rejection",
                "mixed-market A-grade requirement",
                "Fibonacci relative-volume and candle-microstructure quality",
                "next-open entry, 2 bps each-side slippage, 0.04% each-side fee",
            ],
            "notEvaluated": ["database policy routing", "funding", "order book/depth", "margin", "portfolio capacity", "actual exit v4"],
            "entryWindowBars": WINDOW_BARS,
            "horizonBars": horizon_bars,
            "notionalUsdt": notional,
        },
        "generatedModelCandidates": generated,
        "baselineThreshold": baseline,
        "variants": rendered,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--horizon-bars", type=int, default=16)
    parser.add_argument("--sweep", choices=("expected-r", "momentum", "mixed-market"), default="expected-r")
    parser.add_argument("--thresholds", default=None)
    args = parser.parse_args()
    default_thresholds = ("1.25,1.15,1.00,0.90" if args.sweep == "expected-r"
                          else "60,58,55,50" if args.sweep == "momentum" else "1,0")
    thresholds = tuple(Decimal(value.strip()) for value in (args.thresholds or default_thresholds).split(",") if value.strip())
    if not thresholds or args.horizon_bars < 1:
        raise SystemExit("thresholds and horizon-bars must be positive")
    result = run(args.data_dir, args.manifest, thresholds=thresholds, horizon_bars=args.horizon_bars, sweep=args.sweep)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "out": str(args.out),
        "generatedModelCandidates": result["generatedModelCandidates"],
        "passes": {key: value["gateFunnel"].get("PASS", 0) for key, value in result["variants"].items()},
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
