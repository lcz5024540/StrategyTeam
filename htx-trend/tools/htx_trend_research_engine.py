#!/usr/bin/env python3
"""Pure, offline primitives for HTX trend-model research.

This module is intentionally isolated from HTX credentials, Spring services and
live order code.  It makes model evidence, executable prices and simulated
fills explicit so that every model is evaluated on the same frozen candles.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Dict, Iterable, List, Protocol, Sequence

from htx_trend_backtest import Bar, adx, average_tail, ema, true_ranges


class Side(str, Enum):
    LONG = "LONG"
    SHORT = "SHORT"


class OrderStyle(str, Enum):
    MARKET = "MARKET"
    LIMIT = "LIMIT"
    IOC = "IOC"


class DatasetIntegrity(str, Enum):
    COMPLETE = "COMPLETE"
    PARTIAL = "PARTIAL"


@dataclass(frozen=True)
class ExecutionAssumptions:
    fee_rate: float = 0.0004
    entry_slippage_bps: float = 2.0
    exit_slippage_bps: float = 2.0
    notional_usdt: float = 200.0
    conservative_same_bar_stop_first: bool = True


@dataclass(frozen=True)
class ModelConfig:
    fast_ema: int = 20
    slow_ema: int = 60
    atr_period: int = 14
    donchian_lookback: int = 20
    fibonacci_min_atr_range: float = 1.2
    fib_lower: float = 0.382
    fib_upper: float = 0.618
    vwap_window: int = 24
    stop_atr: float = 1.25
    target_r: float = 2.0


@dataclass(frozen=True)
class SignalContext:
    symbol: str
    bar_time: int
    entry_bars: Sequence[Bar]
    side: Side
    higher_timeframe_evidence: str = "UNSPECIFIED"


@dataclass(frozen=True)
class PricePlan:
    trigger_price: float
    ideal_entry: float
    worst_entry: float
    invalidation_price: float
    target_price: float
    order_style: OrderStyle
    max_wait_bars: int
    expected_net_r: float


@dataclass(frozen=True)
class ResearchSignal:
    model: str
    symbol: str
    side: Side
    bar_time: int
    score: float
    evidence: tuple[str, ...]
    price_plan: PricePlan


class SignalModel(Protocol):
    name: str

    def evaluate(self, context: SignalContext, config: ModelConfig,
                 execution: ExecutionAssumptions) -> List[ResearchSignal]:
        """Return zero or more independent candidates for the same completed bar."""


def analyze_models(models: Sequence[SignalModel], context: SignalContext, config: ModelConfig,
                   execution: ExecutionAssumptions) -> List[ResearchSignal]:
    """Run every model against the same completed bar without selecting a winner.

    Selection belongs to a later portfolio experiment.  Keeping this layer
    additive is what lets research compare model overlap and conflict instead
    of incorrectly assigning a candle to one exclusive model.
    """
    values: List[ResearchSignal] = []
    for model in models:
        values.extend(model.evaluate(context, config, execution))
    return values


def _atr(bars: Sequence[Bar], period: int) -> float:
    return average_tail(true_ranges(list(bars)), period)


def _vwap(bars: Sequence[Bar], size: int) -> float:
    window = bars[-size:]
    total_price_volume = total_volume = 0.0
    for bar in window:
        volume = bar.turnover or bar.amount
        if volume > 0:
            total_price_volume += ((bar.high + bar.low + bar.close) / 3.0) * volume
            total_volume += volume
    return total_price_volume / total_volume if total_volume else 0.0


def _adverse_price(side: Side, price: float, bps: float) -> float:
    return price * (1 + bps / 10_000) if side is Side.LONG else price * (1 - bps / 10_000)


def _net_r(side: Side, entry: float, stop: float, target: float, execution: ExecutionAssumptions) -> float:
    risk = entry - stop if side is Side.LONG else stop - entry
    if entry <= 0 or risk <= 0:
        return float("-inf")
    quantity = execution.notional_usdt / entry
    gross = (target - entry) * quantity if side is Side.LONG else (entry - target) * quantity
    fees = (entry + target) * quantity * execution.fee_rate
    return (gross - fees) / (risk * quantity)


def _plan(side: Side, trigger: float, stop: float, atr_value: float, config: ModelConfig,
          execution: ExecutionAssumptions, style: OrderStyle = OrderStyle.MARKET) -> PricePlan | None:
    ideal = trigger
    worst = _adverse_price(side, trigger, execution.entry_slippage_bps)
    risk = worst - stop if side is Side.LONG else stop - worst
    if risk <= 0:
        return None
    target = worst + config.target_r * risk if side is Side.LONG else worst - config.target_r * risk
    return PricePlan(trigger, ideal, worst, stop, target, style, 1,
                     _net_r(side, worst, stop, target, execution))


def _signal(name: str, context: SignalContext, score: float, evidence: Iterable[str], stop: float,
            atr_value: float, config: ModelConfig, execution: ExecutionAssumptions,
            style: OrderStyle = OrderStyle.MARKET) -> List[ResearchSignal]:
    plan = _plan(context.side, context.entry_bars[-1].close, stop, atr_value, config, execution, style)
    return [] if plan is None else [ResearchSignal(name, context.symbol, context.side, context.bar_time, score,
                                                    tuple(evidence), plan)]


class DonchianBreakoutModel:
    name = "DONCHIAN_BREAKOUT"

    def evaluate(self, context: SignalContext, config: ModelConfig,
                 execution: ExecutionAssumptions) -> List[ResearchSignal]:
        bars = context.entry_bars
        required = max(config.slow_ema, config.donchian_lookback) + 1
        if len(bars) < required:
            return []
        last, history = bars[-1], bars[-config.donchian_lookback - 1:-1]
        high, low = max(bar.high for bar in history), min(bar.low for bar in history)
        closes = [bar.close for bar in bars]
        fast, slow, value_atr = ema(closes, config.fast_ema), ema(closes, config.slow_ema), _atr(bars, config.atr_period)
        if value_atr <= 0:
            return []
        if context.side is Side.LONG and last.close > high and fast > slow:
            return _signal(self.name, context, adx(list(bars), config.atr_period) + 30,
                           ("Donchian upper-channel breakout", "EMA trend aligned", context.higher_timeframe_evidence),
                           min(last.low, last.close - config.stop_atr * value_atr), value_atr, config, execution)
        if context.side is Side.SHORT and last.close < low and fast < slow:
            return _signal(self.name, context, adx(list(bars), config.atr_period) + 30,
                           ("Donchian lower-channel breakdown", "EMA trend aligned", context.higher_timeframe_evidence),
                           max(last.high, last.close + config.stop_atr * value_atr), value_atr, config, execution)
        return []


class FibonacciPullbackModel:
    name = "FIB_PULLBACK_CONTINUATION"

    def evaluate(self, context: SignalContext, config: ModelConfig,
                 execution: ExecutionAssumptions) -> List[ResearchSignal]:
        bars = context.entry_bars
        required = max(config.slow_ema, config.donchian_lookback) + 1
        if len(bars) < required:
            return []
        last, previous = bars[-1], bars[-2]
        structure = bars[-config.donchian_lookback - 1:-1]
        high, low = max(bar.high for bar in structure), min(bar.low for bar in structure)
        span, value_atr = high - low, _atr(bars, config.atr_period)
        closes = [bar.close for bar in bars]
        fast, slow = ema(closes, config.fast_ema), ema(closes, config.slow_ema)
        if value_atr <= 0 or span < config.fibonacci_min_atr_range * value_atr:
            return []
        if context.side is Side.LONG:
            upper, lower = high - config.fib_lower * span, high - config.fib_upper * span
            if lower <= last.close <= upper and last.close > previous.close and fast > slow:
                return _signal(self.name, context, adx(list(bars), config.atr_period) + 20,
                               ("Fibonacci 0.382-0.618 pullback", "bullish recovery", context.higher_timeframe_evidence),
                               min(low, last.close - config.stop_atr * value_atr), value_atr, config, execution,
                               OrderStyle.LIMIT)
        else:
            lower, upper = low + config.fib_lower * span, low + config.fib_upper * span
            if lower <= last.close <= upper and last.close < previous.close and fast < slow:
                return _signal(self.name, context, adx(list(bars), config.atr_period) + 20,
                               ("Fibonacci 0.382-0.618 pullback", "bearish recovery", context.higher_timeframe_evidence),
                               max(high, last.close + config.stop_atr * value_atr), value_atr, config, execution,
                               OrderStyle.LIMIT)
        return []


class EmaVwapMomentumModel:
    name = "EMA_VWAP_MOMENTUM"

    def evaluate(self, context: SignalContext, config: ModelConfig,
                 execution: ExecutionAssumptions) -> List[ResearchSignal]:
        bars = context.entry_bars
        if len(bars) < max(config.slow_ema, config.vwap_window) + 2:
            return []
        last, previous = bars[-1], bars[-2]
        closes = [bar.close for bar in bars]
        fast, slow, value_atr = ema(closes, config.fast_ema), ema(closes, config.slow_ema), _atr(bars, config.atr_period)
        value_vwap = _vwap(bars, config.vwap_window)
        if value_atr <= 0 or value_vwap <= 0 or abs(last.close - fast) > .35 * value_atr:
            return []
        if context.side is Side.LONG and fast > slow and last.close > value_vwap and last.close > previous.close:
            return _signal(self.name, context, adx(list(bars), config.atr_period) + 15,
                           ("EMA recovery", "above VWAP", "positive momentum", context.higher_timeframe_evidence),
                           min(last.low, last.close - config.stop_atr * value_atr), value_atr, config, execution)
        if context.side is Side.SHORT and fast < slow and last.close < value_vwap and last.close < previous.close:
            return _signal(self.name, context, adx(list(bars), config.atr_period) + 15,
                           ("EMA recovery", "below VWAP", "negative momentum", context.higher_timeframe_evidence),
                           max(last.high, last.close + config.stop_atr * value_atr), value_atr, config, execution)
        return []


@dataclass(frozen=True)
class Fill:
    signal: ResearchSignal
    entry_time: int
    entry_price: float


@dataclass(frozen=True)
class SimulatedTrade:
    signal: ResearchSignal
    entry_time: int
    exit_time: int
    entry_price: float
    exit_price: float
    exit_reason: str
    net_pnl: float


def simulate_entry(signal: ResearchSignal, next_bar: Bar, execution: ExecutionAssumptions) -> Fill | None:
    plan = signal.price_plan
    if plan.order_style is OrderStyle.MARKET:
        return Fill(signal, next_bar.ts, _adverse_price(signal.side, next_bar.open, execution.entry_slippage_bps))
    limit = plan.ideal_entry
    touched = next_bar.low <= limit <= next_bar.high
    if not touched:
        return None
    if plan.order_style is OrderStyle.IOC and not touched:
        return None
    return Fill(signal, next_bar.ts, limit)


def _net_pnl(side: Side, entry: float, exit_price: float, execution: ExecutionAssumptions) -> float:
    quantity = execution.notional_usdt / entry
    gross = (exit_price - entry) * quantity if side is Side.LONG else (entry - exit_price) * quantity
    fees = (entry + exit_price) * quantity * execution.fee_rate
    return gross - fees


def simulate_exit(fill: Fill, bars: Sequence[Bar], execution: ExecutionAssumptions) -> SimulatedTrade:
    plan, side = fill.signal.price_plan, fill.signal.side
    for bar in bars:
        stop_hit = bar.low <= plan.invalidation_price if side is Side.LONG else bar.high >= plan.invalidation_price
        target_hit = bar.high >= plan.target_price if side is Side.LONG else bar.low <= plan.target_price
        if stop_hit and (execution.conservative_same_bar_stop_first or not target_hit):
            price = _adverse_price(side, plan.invalidation_price, execution.exit_slippage_bps)
            return SimulatedTrade(fill.signal, fill.entry_time, bar.ts, fill.entry_price, price, "INITIAL_STOP", _net_pnl(side, fill.entry_price, price, execution))
        if target_hit:
            price = _adverse_price(side, plan.target_price, execution.exit_slippage_bps)
            return SimulatedTrade(fill.signal, fill.entry_time, bar.ts, fill.entry_price, price, "TARGET", _net_pnl(side, fill.entry_price, price, execution))
    final = bars[-1].close if bars else fill.entry_price
    price = _adverse_price(side, final, execution.exit_slippage_bps)
    return SimulatedTrade(fill.signal, fill.entry_time, bars[-1].ts if bars else fill.entry_time, fill.entry_price, price,
                          "WINDOW_END", _net_pnl(side, fill.entry_price, price, execution))


@dataclass(frozen=True)
class WalkForwardWindow:
    name: str
    start: int
    end: int
    purpose: str


def walk_forward_windows(start: int, end: int, train_ratio: float = .50, validation_ratio: float = .25) -> List[WalkForwardWindow]:
    if not 0 < train_ratio < 1 or not 0 < validation_ratio < 1 or train_ratio + validation_ratio >= 1:
        raise ValueError("train and validation ratios must be positive and leave an out-of-sample window")
    if end <= start + 2:
        raise ValueError("window is too short")
    span = end - start
    train_end = start + int(span * train_ratio)
    validation_end = train_end + int(span * validation_ratio)
    return [WalkForwardWindow("TRAIN", start, train_end, "parameter research only"),
            WalkForwardWindow("VALIDATION", train_end, validation_end, "frozen parameter selection"),
            WalkForwardWindow("OUT_OF_SAMPLE", validation_end, end, "final unbiased acceptance test")]


def summarize(trades: Iterable[SimulatedTrade]) -> dict:
    rows = list(trades)
    pnl = [row.net_pnl for row in rows]
    wins, losses = [value for value in pnl if value > 0], [-value for value in pnl if value < 0]
    gross_profit, gross_loss = sum(wins), sum(losses)
    equity = peak = drawdown = 0.0
    for value in pnl:
        equity += value; peak = max(peak, equity); drawdown = min(drawdown, equity - peak)
    return {"trades": len(rows), "netPnl": sum(pnl), "profitFactor": gross_profit / gross_loss if gross_loss else (999.0 if gross_profit else 0.0),
            "expectancy": sum(pnl) / len(pnl) if pnl else 0.0, "winRate": len(wins) / len(pnl) if pnl else 0.0,
            "maxDrawdown": drawdown, "feesIncluded": True}


def evaluate_walk_forward(windows: Sequence[WalkForwardWindow], runner: Callable[[WalkForwardWindow], Iterable[SimulatedTrade]],
                          integrity: DatasetIntegrity) -> dict:
    if integrity is not DatasetIntegrity.COMPLETE:
        return {"integrity": integrity.value, "decision": "INSUFFICIENT_EVIDENCE", "windows": []}
    values = [{"name": window.name, "purpose": window.purpose, "start": window.start, "end": window.end,
               "metrics": summarize(runner(window))} for window in windows]
    oos = next(value["metrics"] for value in values if value["name"] == "OUT_OF_SAMPLE")
    decision = "PAPER_CANDIDATE" if oos["netPnl"] > 0 and oos["profitFactor"] >= 1.2 and oos["expectancy"] > 0 else "REJECT"
    return {"integrity": integrity.value, "decision": decision, "windows": values}
