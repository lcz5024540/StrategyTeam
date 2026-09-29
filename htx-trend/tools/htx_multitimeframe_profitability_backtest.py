#!/usr/bin/env python3
"""Frozen-candle profitability backtest for the HTX trend research sleeve.

This is deliberately a research gate, not a live trading component.  It creates
entries from completed 15m/30m/1h/4h/1d candles, charges both sides of the
trade, and compares every result with the valid no-trade baseline (zero).
"""
from __future__ import annotations

import argparse
import csv
import json
from dataclasses import dataclass, field, replace
from datetime import datetime, time
from pathlib import Path
from typing import Dict, Iterable, List, Optional
from zoneinfo import ZoneInfo

from htx_trend_backtest import Bar, adx, average_tail, completed, ema, fmt_ts, load_bars, true_ranges


SECONDS = {"15min": 900, "30min": 1800, "60min": 3600, "4hour": 14400, "1day": 86400}
SHANGHAI = ZoneInfo("Asia/Shanghai")


@dataclass(frozen=True)
class Config:
    fee_rate: float = 0.0004
    notional: float = 200.0
    max_positions: int = 3
    min_adx: float = 22.0
    min_net_r: float = 1.25
    max_chase_atr: float = 0.20
    atr_stop: float = 1.25
    target_r: float = 2.0
    trailing_atr: float = 1.5
    warmup: int = 64
    min_model_trades: int = 12
    min_pf: float = 1.20


@dataclass(frozen=True)
class Candidate:
    symbol: str
    model: str
    side: str
    signal_time: int
    trigger: float
    stop: float
    target: float
    atr: float
    expected_net_r: float
    score: float
    evidence: str


@dataclass
class Position:
    candidate: Candidate
    entry_time: int
    entry: float
    highest: float
    lowest: float
    armed: bool = False
    protection: Optional[float] = None


@dataclass(frozen=True)
class Trade:
    symbol: str
    model: str
    side: str
    entry_time: int
    exit_time: int
    entry: float
    exit: float
    net_pnl: float
    exit_reason: str
    expected_net_r: float


@dataclass
class Result:
    trades: List[Trade] = field(default_factory=list)
    rejected: Dict[str, int] = field(default_factory=dict)

    def reject(self, reason: str) -> None:
        self.rejected[reason] = self.rejected.get(reason, 0) + 1


def closes_before(bars: List[Bar], seconds: int, as_of: int) -> List[Bar]:
    return completed(bars, seconds, as_of)


def derive_daily_from_4hour(bars: List[Bar]) -> List[Bar]:
    """Build complete Asia/Shanghai daily candles from already-frozen 4h data.

    It is a deterministic fallback when a raw 1d response was not captured. A
    date with fewer than six 4h bars is intentionally omitted, so the runner
    can never make a daily decision from an unfinished day.
    """
    grouped: Dict[object, List[Bar]] = {}
    for bar in sorted(bars, key=lambda item: item.ts):
        grouped.setdefault(datetime.fromtimestamp(bar.ts, SHANGHAI).date(), []).append(bar)
    daily: List[Bar] = []
    for date, values in sorted(grouped.items()):
        if len(values) != 6:
            continue
        start = int(datetime.combine(date, time.min, SHANGHAI).timestamp())
        daily.append(Bar(start, values[0].open, max(item.high for item in values), min(item.low for item in values),
                         values[-1].close, sum(item.amount for item in values), sum(item.turnover for item in values)))
    return daily


def direction(bars: List[Bar], period: str, as_of: int, fast: int = 20, slow: int = 60) -> str:
    values = closes_before(bars, SECONDS[period], as_of)
    if len(values) < slow + 2:
        return "FLAT"
    closes = [bar.close for bar in values]
    ef, es = ema(closes, fast), ema(closes, slow)
    if closes[-1] > closes[-2] and closes[-1] >= ef and ef > es:
        return "LONG"
    if closes[-1] < closes[-2] and closes[-1] <= ef and ef < es:
        return "SHORT"
    return "FLAT"


def aligned_context(series: Dict[str, List[Bar]], as_of: int) -> str:
    # The frozen 4h history spans roughly fifty days; a 12/40 daily regime is
    # therefore observable without silently fetching a newer data set.
    daily = direction(series["1day"], "1day", as_of, 12, 40)
    four_hour = direction(series["4hour"], "4hour", as_of)
    one_hour = direction(series["60min"], "60min", as_of)
    if daily in ("LONG", "SHORT") and daily == four_hour and one_hour != opposite(daily):
        return daily
    return "FLAT"


def opposite(side: str) -> str:
    return "SHORT" if side == "LONG" else "LONG" if side == "SHORT" else "FLAT"


def atr(bars: List[Bar]) -> float:
    return average_tail(true_ranges(bars), 14)


def rolling_vwap(bars: List[Bar], size: int = 24) -> float:
    window = bars[-size:]
    numerator = denominator = 0.0
    for bar in window:
        volume = bar.turnover if bar.turnover > 0 else bar.amount
        if volume <= 0:
            continue
        numerator += ((bar.high + bar.low + bar.close) / 3.0) * volume
        denominator += volume
    return numerator / denominator if denominator else 0.0


def price_plan(side: str, entry: float, stop: float, cfg: Config) -> Optional[tuple[float, float]]:
    risk = (entry - stop) if side == "LONG" else (stop - entry)
    if risk <= 0 or entry <= 0:
        return None
    target = entry + cfg.target_r * risk if side == "LONG" else entry - cfg.target_r * risk
    quantity = cfg.notional / entry
    target_gross = (target - entry) * quantity if side == "LONG" else (entry - target) * quantity
    target_net = target_gross - (entry + target) * quantity * cfg.fee_rate
    risk_cash = risk * quantity
    return target, target_net / risk_cash if risk_cash else None


def candidates_for(symbol: str, series: Dict[str, List[Bar]], as_of: int, cfg: Config) -> List[Candidate]:
    context = aligned_context(series, as_of)
    entry = closes_before(series["15min"], SECONDS["15min"], as_of)
    structure = closes_before(series["30min"], SECONDS["30min"], as_of)
    if context == "FLAT" or len(entry) < cfg.warmup or len(structure) < cfg.warmup:
        return []
    last, previous = entry[-1], entry[-2]
    value_atr = atr(entry)
    if value_atr <= 0 or adx(entry, 14) < cfg.min_adx or direction(series["30min"], "30min", as_of) != context:
        return []
    closes = [bar.close for bar in entry]
    fast, slow = ema(closes, 20), ema(closes, 60)
    lookback = entry[-21:-1]
    high, low = max(bar.high for bar in lookback), min(bar.low for bar in lookback)
    values: List[Candidate] = []

    def add(model: str, stop: float, score: float, evidence: str) -> None:
        planned = price_plan(context, last.close, stop, cfg)
        if planned is None:
            return
        target, net_r = planned
        if net_r >= cfg.min_net_r:
            values.append(Candidate(symbol, model, context, last.ts, last.close, stop, target, value_atr, net_r, score, evidence))

    if context == "LONG" and last.close > high and fast > slow:
        add("DONCHIAN_BREAKOUT", min(last.low, last.close - cfg.atr_stop * value_atr), adx(entry, 14) + 30,
            "1d/4h/1h/30m aligned; 15m Donchian breakout")
    if context == "SHORT" and last.close < low and fast < slow:
        add("DONCHIAN_BREAKOUT", max(last.high, last.close + cfg.atr_stop * value_atr), adx(entry, 14) + 30,
            "1d/4h/1h/30m aligned; 15m Donchian breakdown")

    span = high - low
    if span >= 1.2 * value_atr:
        if context == "LONG":
            upper, lower = high - .382 * span, high - .618 * span
            if lower <= last.close <= upper and last.close > previous.close and fast > slow:
                add("FIB_PULLBACK_CONTINUATION", min(low, last.close - cfg.atr_stop * value_atr), adx(entry, 14) + 20,
                    "1d/4h/1h/30m aligned; 15m Fibonacci recovery")
        else:
            lower, upper = low + .382 * span, low + .618 * span
            if lower <= last.close <= upper and last.close < previous.close and fast < slow:
                add("FIB_PULLBACK_CONTINUATION", max(high, last.close + cfg.atr_stop * value_atr), adx(entry, 14) + 20,
                    "1d/4h/1h/30m aligned; 15m Fibonacci recovery")

    vwap = rolling_vwap(entry)
    near_fast = abs(last.close - fast) <= .35 * value_atr
    if context == "LONG" and fast > slow and last.close > vwap and last.close > previous.close and near_fast:
        add("EMA_VWAP_MOMENTUM", min(last.low, last.close - cfg.atr_stop * value_atr), adx(entry, 14) + 15,
            "1d/4h/1h/30m aligned; EMA/VWAP momentum recovery")
    if context == "SHORT" and fast < slow and last.close < vwap and last.close < previous.close and near_fast:
        add("EMA_VWAP_MOMENTUM", max(last.high, last.close + cfg.atr_stop * value_atr), adx(entry, 14) + 15,
            "1d/4h/1h/30m aligned; EMA/VWAP momentum recovery")
    return values


def net_pnl(side: str, entry: float, exit_price: float, notional: float, fee_rate: float) -> float:
    quantity = notional / entry
    gross = (exit_price - entry) * quantity if side == "LONG" else (entry - exit_price) * quantity
    return gross - (entry + exit_price) * quantity * fee_rate


def close(position: Position, time: int, price: float, reason: str, cfg: Config) -> Trade:
    c = position.candidate
    return Trade(c.symbol, c.model, c.side, position.entry_time, time, position.entry, price,
                 net_pnl(c.side, position.entry, price, cfg.notional, cfg.fee_rate), reason, c.expected_net_r)


def run(symbols: List[str], data: Dict[str, Dict[str, List[Bar]]], start: int, end: int, cfg: Config) -> Result:
    result, positions = Result(), {}
    all_times = sorted({bar.ts for symbol in symbols for bar in data[symbol]["15min"] if start <= bar.ts < end})
    lookup = {symbol: {bar.ts: bar for bar in data[symbol]["15min"]} for symbol in symbols}
    indices = {symbol: {bar.ts: i for i, bar in enumerate(data[symbol]["15min"])} for symbol in symbols}
    for ts in all_times:
        as_of = ts + SECONDS["15min"]
        for symbol, position in list(positions.items()):
            bar = lookup[symbol].get(ts)
            if not bar:
                continue
            c = position.candidate
            position.highest, position.lowest = max(position.highest, bar.high), min(position.lowest, bar.low)
            risk = abs(position.entry - c.stop)
            if c.side == "LONG":
                if bar.low <= c.stop:
                    result.trades.append(close(position, as_of, c.stop, "initial-stop", cfg)); del positions[symbol]; continue
                if bar.high >= c.target:
                    result.trades.append(close(position, as_of, c.target, "target-2r", cfg)); del positions[symbol]; continue
                if not position.armed and position.highest >= position.entry + risk:
                    position.armed, position.protection = True, position.entry
                if position.armed:
                    position.protection = max(position.protection or position.entry, position.highest - c.atr * cfg.trailing_atr)
                    if bar.low <= position.protection:
                        result.trades.append(close(position, as_of, position.protection, "profit-protection", cfg)); del positions[symbol]
            else:
                if bar.high >= c.stop:
                    result.trades.append(close(position, as_of, c.stop, "initial-stop", cfg)); del positions[symbol]; continue
                if bar.low <= c.target:
                    result.trades.append(close(position, as_of, c.target, "target-2r", cfg)); del positions[symbol]; continue
                if not position.armed and position.lowest <= position.entry - risk:
                    position.armed, position.protection = True, position.entry
                if position.armed:
                    position.protection = min(position.protection or position.entry, position.lowest + c.atr * cfg.trailing_atr)
                    if bar.high >= position.protection:
                        result.trades.append(close(position, as_of, position.protection, "profit-protection", cfg)); del positions[symbol]
        ranked: List[Candidate] = []
        for symbol in symbols:
            if symbol in positions:
                continue
            index = indices[symbol].get(ts)
            if index is None or index + 1 >= len(data[symbol]["15min"]):
                continue
            options = candidates_for(symbol, data[symbol], as_of, cfg)
            if not options:
                continue
            candidate = max(options, key=lambda item: item.score)
            next_open = data[symbol]["15min"][index + 1].open
            chase = (next_open - candidate.trigger) if candidate.side == "LONG" else (candidate.trigger - next_open)
            if chase > cfg.max_chase_atr * candidate.atr:
                result.reject("entry-chase"); continue
            ranked.append(candidate)
        for candidate in sorted(ranked, key=lambda item: item.score, reverse=True)[:max(0, cfg.max_positions - len(positions))]:
            index = indices[candidate.symbol][ts]
            entry = data[candidate.symbol]["15min"][index + 1].open
            planned = price_plan(candidate.side, entry, candidate.stop, cfg)
            if planned is None:
                result.reject("invalid-executed-price-plan"); continue
            target, actual_net_r = planned
            if actual_net_r < cfg.min_net_r:
                result.reject("executed-price-plan-net-r"); continue
            executed = replace(candidate, target=target, expected_net_r=actual_net_r)
            positions[candidate.symbol] = Position(executed, ts + SECONDS["15min"], entry, entry, entry)
    for symbol, position in positions.items():
        bars = [bar for bar in data[symbol]["15min"] if bar.ts < end]
        if bars:
            result.trades.append(close(position, end, bars[-1].close, "window-end", cfg))
    return result


def metrics(trades: Iterable[Trade]) -> dict:
    rows = list(trades); wins = [row.net_pnl for row in rows if row.net_pnl > 0]; losses = [-row.net_pnl for row in rows if row.net_pnl < 0]
    gp, gl = sum(wins), sum(losses)
    return {"trades": len(rows), "net_pnl": sum(row.net_pnl for row in rows), "profit_factor": gp / gl if gl else (999.0 if gp else 0.0),
            "expectancy": sum(row.net_pnl for row in rows) / len(rows) if rows else 0.0,
            "win_rate": len(wins) / len(rows) if rows else 0.0, "max_loss": -max(losses) if losses else 0.0}


def decision(value: dict, cfg: Config, input_integrity: str = "COMPLETE") -> str:
    """Return a promotion decision without allowing incomplete inputs to pass.

    A partial window may still be useful to expose a broken hypothesis, but it
    cannot establish a model's true out-of-sample performance.  Preserve that
    distinction in the machine-readable result instead of treating an
    incomplete sample as either a passing or a failing research experiment.
    """
    if input_integrity != "COMPLETE":
        return "INSUFFICIENT_EVIDENCE"
    if value["net_pnl"] <= 0 or value["profit_factor"] < cfg.min_pf or value["expectancy"] <= 0:
        return "REJECT"
    return "PAPER_CANDIDATE" if value["trades"] >= cfg.min_model_trades else "INSUFFICIENT_SAMPLE"


def load_manifest(path: Path, symbols: List[str]) -> dict:
    """Load and minimally validate the immutable input manifest for one run."""
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"cannot read dataset manifest {path}: {exc}") from exc
    if manifest.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise SystemExit("unsupported dataset manifest schema")
    if not manifest.get("datasetKey") or not manifest.get("inputChecksum"):
        raise SystemExit("dataset manifest has no immutable key or checksum")
    if sorted(manifest.get("contracts", [])) != sorted(symbols):
        raise SystemExit("dataset manifest contract universe differs from backtest request")
    missing_periods = sorted(set(SECONDS) - set(manifest.get("periods", [])))
    if missing_periods:
        raise SystemExit("dataset manifest lacks required periods: " + ", ".join(missing_periods))
    return manifest


def write_output(path: Path, result: Result, cfg: Config, manifest: dict) -> None:
    path.mkdir(parents=True, exist_ok=True)
    with (path / "trades.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file, lineterminator="\n"); writer.writerow(["symbol", "model", "side", "entry_time", "exit_time", "entry", "exit", "net_pnl", "exit_reason", "expected_net_r"])
        for row in result.trades: writer.writerow([row.symbol, row.model, row.side, fmt_ts(row.entry_time), fmt_ts(row.exit_time), row.entry, row.exit, row.net_pnl, row.exit_reason, row.expected_net_r])
    input_integrity = manifest["inputIntegrity"]
    grouped = {model: metrics(row for row in result.trades if row.model == model) for model in sorted({row.model for row in result.trades})}
    summary = {
        "dataset_key": manifest["datasetKey"],
        "input_checksum": manifest["inputChecksum"],
        "input_integrity": input_integrity,
        "missing_or_partial": manifest.get("missingOrPartial", []),
        "all": metrics(result.trades),
        "models": {key: {**value, "decision": decision(value, cfg, input_integrity)} for key, value in grouped.items()},
        "rejected": result.rejected,
        "acceptance": "must beat no-trade (net PnL > 0), PF >= 1.20, positive expectancy, minimum sample, and COMPLETE frozen input",
    }
    (path / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# HTX 多周期趋势盈利回测", "", "本报告比较策略与不交易（0U）基准。任一模型未达到扣费后正收益、PF、期望值、样本量和完整冻结输入门槛，即不可进入实盘。", "",
             f"数据集：`{summary['dataset_key']}`", f"输入完整性：`{input_integrity}`"]
    if summary["missing_or_partial"]:
        lines.extend(["", "以下输入存在缺口，本次指标只能用于否定性诊断，不能作为模型淘汰或晋级的最终证据："])
        lines.extend(f"- `{item}`" for item in summary["missing_or_partial"])
    lines.extend(["", "| 模型 | 笔数 | 净收益 | PF | 单笔期望 | 胜率 | 结论 |", "|---|---:|---:|---:|---:|---:|---|"])
    for key, value in summary["models"].items(): lines.append(f"| {key} | {value['trades']} | {value['net_pnl']:.4f} | {value['profit_factor']:.2f} | {value['expectancy']:.4f} | {value['win_rate']*100:.1f}% | {value['decision']} |")
    total = summary["all"]; lines += ["", f"整体：{total['trades']} 笔，净收益 {total['net_pnl']:.4f}U，PF {total['profit_factor']:.2f}。", "", "边界：使用冻结 K 线的下一根开盘价、双边 taker 费率和同 K 线止损优先规则；不伪造盘口、部分成交或资金费结果。"]
    (path / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, required=True); parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True,
                        help="content-addressed manifest created by htx_trend_dataset_manifest.py")
    parser.add_argument("--allow-partial", action="store_true",
                        help="write an INSUFFICIENT_EVIDENCE diagnostic for a partial dataset; never promotes a model")
    parser.add_argument("--symbols", default="BTC-USDT,ETH-USDT,XAUT-USDT"); parser.add_argument("--start", required=True, type=int); parser.add_argument("--end", required=True, type=int)
    args = parser.parse_args(); symbols = [item.strip() for item in args.symbols.split(",") if item.strip()]
    manifest = load_manifest(args.manifest, symbols)
    if manifest["inputIntegrity"] != "COMPLETE" and not args.allow_partial:
        raise SystemExit("dataset inputIntegrity is not COMPLETE; repair or extend frozen inputs, or use --allow-partial for diagnostic-only output")
    data = {symbol: {period: load_bars(args.data_dir, symbol, period) for period in SECONDS} for symbol in symbols}
    for symbol in symbols:
        if not data[symbol]["1day"]:
            data[symbol]["1day"] = derive_daily_from_4hour(data[symbol]["4hour"])
    missing = [f"{symbol}:{period}" for symbol in symbols for period in SECONDS if not data[symbol][period]]
    if missing: raise SystemExit("missing frozen input: " + ", ".join(missing))
    write_output(args.out_dir, run(symbols, data, args.start, args.end, Config()), Config(), manifest)


if __name__ == "__main__": main()
