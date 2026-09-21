#!/usr/bin/env python3
"""Offline HTX trend strategy backtest.

This tool replays the HTX trend signal path from historical kline JSON files.
It intentionally does not call private APIs, place orders, or read production DBs.

Expected input layout:
  <data-dir>/BTC-USDT_15min.json
  <data-dir>/BTC-USDT_60min.json
  ...

The JSON format is the public HTX kline response `data` array saved as a file.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from statistics import median
from typing import Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlencode
from urllib.request import urlopen
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Asia/Shanghai")

DEFAULT_SYMBOLS = [
    "BTC-USDT", "ETH-USDT", "SOL-USDT", "XRP-USDT", "BNB-USDT", "DOGE-USDT", "ADA-USDT",
    "TRX-USDT", "LINK-USDT", "AVAX-USDT", "SUI-USDT", "HYPE-USDT", "HBAR-USDT", "PONS-USDT",
    "BCH-USDT", "LTC-USDT", "DOT-USDT", "UNI-USDT", "AAVE-USDT", "NEAR-USDT", "ETC-USDT",
    "XLM-USDT", "FIL-USDT", "ARB-USDT", "OP-USDT", "ATOM-USDT", "ICP-USDT", "APT-USDT",
    "PEPE-USDT", "SHIB-USDT", "WIF-USDT", "BONK-USDT", "FLOKI-USDT", "TAO-USDT", "WLD-USDT",
    "TIA-USDT", "JUP-USDT", "ENA-USDT", "ONDO-USDT", "ALGO-USDT", "OKB-USDT", "DASH-USDT",
    "ZEC-USDT", "XAUT-USDT", "PAXG-USDT", "PEOPLE-USDT", "ORDI-USDT", "BOME-USDT", "RAY-USDT",
    "CHZ-USDT", "ETHFI-USDT", "ACE-USDT",
]


@dataclass(frozen=True)
class Config:
    period_seconds: int = 900
    trend_period_seconds: int = 3600
    fast_ema: int = 12
    slow_ema: int = 48
    trend_fast_ema: int = 8
    trend_slow_ema: int = 21
    breakout_lookback: int = 20
    atr_period: int = 14
    adx_period: int = 14
    min_adx: float = 25.0
    min_atr_pct: float = 0.0010
    fibonacci_enabled: bool = True
    fibonacci_lower: float = 0.382
    fibonacci_upper: float = 0.618
    fibonacci_min_range_atr: float = 1.2
    target_quote_notional: float = 190.0
    max_quote_notional: float = 190.0
    min_quote_notional: float = 20.0
    risk_per_trade_usdt: float = 1.5
    atr_stop_multiplier: float = 2.5
    trailing_atr_multiplier: float = 2.0
    estimated_taker_fee_rate: float = 0.0004
    min_net_profit_rate: float = 0.0010
    min_expected_move_atr_multiplier: float = 1.25
    max_active_positions: int = 3
    max_entry_signal_chase_atr_multiplier: float = 0.20
    flat_loss_exit_confirmation_bars: int = 3
    max_flat_exit_loss_usdt: float = 0.25
    max_flat_exit_loss_rate: float = 0.0030
    no_extension_exit_bars: int = 2
    no_extension_min_favorable_net_usdt: float = 0.15
    adverse_shock_exit_bars: int = 1
    adverse_shock_atr_multiplier: float = 0.90
    adverse_shock_min_favorable_net_usdt: float = 0.15
    market_breadth_lookback_bars: int = 3
    market_breadth_threshold: float = 0.70
    market_breadth_opposite_max: float = 0.15
    weak_quality_multiplier: float = 0.75
    strong_quality_multiplier: float = 1.15
    breadth_aligned_multiplier: float = 1.50
    breadth_counter_multiplier: float = 0.50
    min_adaptive_multiplier: float = 0.25
    max_adaptive_multiplier: float = 1.50
    quality_lookback_trades: int = 20
    quality_min_closed_trades: int = 6
    quality_min_profit_factor: float = 1.10
    quality_strong_profit_factor: float = 1.30
    min_direction_confidence_score: float = 70.0
    min_mixed_direction_confidence_score: float = 82.0
    min_counter_breadth_direction_confidence_score: float = 95.0
    fibonacci_min_direction_confidence_score: float = 80.0


@dataclass(frozen=True)
class ReplayProfile:
    """A named, reviewable replay policy.

    The recovery profile is deliberately separate from Config: values in this
    object are the production recovery decision, rather than accidental
    defaults inherited by an offline experiment.
    """
    name: str
    direction_gate_enabled: bool
    adverse_shock_exit_enabled: bool
    no_extension_exit_enabled: bool
    target_quote_notional: float
    max_quote_notional: float
    risk_per_trade_usdt: float


RECOVERY_PROFILE = ReplayProfile(
    name="recovery_20260921",
    direction_gate_enabled=True,
    adverse_shock_exit_enabled=False,
    no_extension_exit_enabled=False,
    target_quote_notional=190.0,
    max_quote_notional=190.0,
    risk_per_trade_usdt=1.5,
)

BASELINE_PROFILE = ReplayProfile(
    name="baseline_chase_only",
    direction_gate_enabled=False,
    adverse_shock_exit_enabled=False,
    no_extension_exit_enabled=False,
    target_quote_notional=190.0,
    max_quote_notional=190.0,
    risk_per_trade_usdt=1.5,
)


@dataclass(frozen=True)
class Bar:
    ts: int
    open: float
    high: float
    low: float
    close: float
    amount: float = 0.0
    turnover: float = 0.0


@dataclass(frozen=True)
class Signal:
    side: str
    bar_time: int
    close: float
    ema_fast: float
    ema_slow: float
    breakout_high: float
    breakout_low: float
    atr: float
    atr_pct: float
    adx: float
    reason: str


@dataclass(frozen=True)
class Breadth:
    regime: str
    total: int
    long_count: int
    short_count: int
    long_ratio: float
    short_ratio: float
    median_ret: float


@dataclass(frozen=True)
class Quality:
    regime: str
    closed: int
    wins: int
    losses: int
    pf: float
    net: float


@dataclass
class Position:
    symbol: str
    side: str
    entry_time: int
    entry_price: float
    notional: float
    atr: float
    signal_reason: str
    entry_model: str
    breadth_regime: str
    direction_score: Optional[float]
    highest: float
    lowest: float
    trailing_stop: float
    flat_count: int = 0
    best_net: float = -10**9
    worst_net: float = 10**9


@dataclass
class Trade:
    symbol: str
    side: str
    entry_time: int
    exit_time: int
    entry_price: float
    exit_price: float
    notional: float
    gross_pnl: float
    fees: float
    net_pnl: float
    mfe: float
    mae: float
    entry_model: str
    exit_reason: str
    breadth_regime: str
    direction_score: Optional[float]


@dataclass
class Candidate:
    symbol: str
    signal: Signal
    score: float
    breadth: Breadth
    quality: Quality
    next_open: float
    direction_score: Optional[float] = None
    direction_required: Optional[float] = None
    skip_reason: Optional[str] = None


@dataclass
class RunStats:
    name: str
    trades: List[Trade] = field(default_factory=list)
    skipped: List[Candidate] = field(default_factory=list)
    open_positions: List[Position] = field(default_factory=list)


def parse_day(value: str) -> datetime:
    return datetime.fromisoformat(value).replace(tzinfo=TZ)


def fmt_ts(ts: int) -> str:
    return datetime.fromtimestamp(ts, TZ).strftime("%Y-%m-%d %H:%M")


def day(ts: int) -> str:
    return datetime.fromtimestamp(ts, TZ).strftime("%Y-%m-%d")


def load_bars(data_dir: Path, symbol: str, period: str) -> List[Bar]:
    path = data_dir / f"{symbol}_{period}.json"
    if not path.exists():
        return []
    payload = json.loads(path.read_text())
    rows = payload.get("data", []) if isinstance(payload, dict) else payload
    bars = []
    for r in rows:
        try:
            bars.append(Bar(
                ts=int(r["id"]),
                open=float(r["open"]),
                high=float(r["high"]),
                low=float(r["low"]),
                close=float(r["close"]),
                amount=float(r.get("amount") or 0.0),
                turnover=float(r.get("trade_turnover") or r.get("vol") or 0.0),
            ))
        except (KeyError, TypeError, ValueError):
            continue
    return sorted(bars, key=lambda b: b.ts)


def capture_kline(data_dir: Path, symbol: str, period: str, size: int) -> Path:
    """Save the untouched public HTX response used by a replay."""
    query = urlencode({"contract_code": symbol, "period": period, "size": size})
    url = "https://api.hbdm.com/linear-swap-ex/market/history/kline?" + query
    with urlopen(url, timeout=20) as response:
        payload = json.load(response)
    if payload.get("status") != "ok" or not payload.get("data"):
        raise RuntimeError(f"HTX kline fetch failed for {symbol} {period}: {payload}")
    data_dir.mkdir(parents=True, exist_ok=True)
    path = data_dir / f"{symbol}_{period}.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def ema(values: List[float], period: int) -> float:
    if not values:
        return 0.0
    m = 2.0 / (period + 1.0)
    e = values[0]
    for v in values[1:]:
        e = (v - e) * m + e
    return e


def true_ranges(bars: List[Bar]) -> List[float]:
    out = []
    for i in range(1, len(bars)):
        b = bars[i]
        prev = bars[i - 1]
        out.append(max(abs(b.high - b.low), abs(b.high - prev.close), abs(b.low - prev.close)))
    return out


def average_tail(values: List[float], period: int) -> float:
    if not values:
        return 0.0
    tail = values[-period:]
    return sum(tail) / len(tail)


def adx(bars: List[Bar], period: int) -> float:
    trs = true_ranges(bars)
    plus_dm, minus_dm = [], []
    for i in range(1, len(bars)):
        up = bars[i].high - bars[i - 1].high
        down = bars[i - 1].low - bars[i].low
        plus_dm.append(up if up > down and up > 0 else 0.0)
        minus_dm.append(down if down > up and down > 0 else 0.0)
    if not trs:
        return 0.0
    dx_values = []
    dx_count = min(period, len(trs))
    for end in range(len(trs) - dx_count, len(trs)):
        start = max(0, end - period + 1)
        tr = sum(trs[start:end + 1])
        if tr <= 0:
            dx_values.append(0.0)
            continue
        plus_di = sum(plus_dm[start:end + 1]) * 100.0 / tr
        minus_di = sum(minus_dm[start:end + 1]) * 100.0 / tr
        denom = plus_di + minus_di
        dx_values.append(0.0 if denom <= 0 else abs(plus_di - minus_di) * 100.0 / denom)
    return sum(dx_values) / len(dx_values) if dx_values else 0.0


def completed(bars: List[Bar], period_seconds: int, now_epoch: int) -> List[Bar]:
    return [b for b in bars if b.ts + period_seconds <= now_epoch]


def evaluate_signal(entry_bars: List[Bar], trend_bars: List[Bar], cfg: Config, now_epoch: int) -> Signal:
    e = completed(entry_bars, cfg.period_seconds, now_epoch)
    t = completed(trend_bars, cfg.trend_period_seconds, now_epoch)
    entry_required = max(cfg.slow_ema, cfg.breakout_lookback) + max(cfg.atr_period, cfg.adx_period) + 3
    trend_required = max(cfg.trend_fast_ema, cfg.trend_slow_ema) + 3
    if len(e) < entry_required or len(t) < trend_required:
        return Signal("FLAT", 0, 0, 0, 0, 0, 0, 0, 0, 0, "not enough candles")
    closes = [b.close for b in e]
    trend_closes = [b.close for b in t]
    last = len(e) - 1
    close = closes[-1]
    previous_close = closes[-2]
    ema_fast = ema(closes, cfg.fast_ema)
    ema_slow = ema(closes, cfg.slow_ema)
    trend_fast = ema(trend_closes, cfg.trend_fast_ema)
    trend_slow = ema(trend_closes, cfg.trend_slow_ema)
    trend_side = "LONG" if trend_fast > trend_slow else "SHORT" if trend_fast < trend_slow else "FLAT"
    window = e[max(0, last - cfg.breakout_lookback):last]
    breakout_high = max(b.high for b in window)
    breakout_low = min(b.low for b in window)
    atr_value = average_tail(true_ranges(e), cfg.atr_period)
    atr_pct = atr_value / close if close > 0 else 0.0
    adx_value = adx(e, cfg.adx_period)
    if atr_pct < cfg.min_atr_pct:
        return Signal("FLAT", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, "ATR below threshold")
    if adx_value < cfg.min_adx:
        return Signal("FLAT", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, "ADX below threshold")
    if trend_side == "LONG" and close > breakout_high and ema_fast > ema_slow:
        return Signal("LONG", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, "close broke Donchian high with higher-timeframe EMA uptrend")
    if trend_side == "SHORT" and close < breakout_low and ema_fast < ema_slow:
        return Signal("SHORT", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, "close broke Donchian low with higher-timeframe EMA downtrend")
    if cfg.fibonacci_enabled and breakout_high > breakout_low and atr_value > 0:
        rng = breakout_high - breakout_low
        if rng >= atr_value * cfg.fibonacci_min_range_atr:
            shallow = min(cfg.fibonacci_lower, cfg.fibonacci_upper)
            deep = max(cfg.fibonacci_lower, cfg.fibonacci_upper)
            long_upper = breakout_high - rng * shallow
            long_lower = breakout_high - rng * deep
            short_lower = breakout_low + rng * shallow
            short_upper = breakout_low + rng * deep
            if trend_side == "LONG" and ema_fast > ema_slow and close > previous_close and close > ema_slow and long_lower <= close <= long_upper:
                return Signal("LONG", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, "Fibonacci 38.2%-61.8% pullback continuation with higher-timeframe EMA uptrend")
            if trend_side == "SHORT" and ema_fast < ema_slow and close < previous_close and close < ema_slow and short_lower <= close <= short_upper:
                return Signal("SHORT", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, "Fibonacci 38.2%-61.8% pullback continuation with higher-timeframe EMA downtrend")
    return Signal("FLAT", e[-1].ts, close, ema_fast, ema_slow, breakout_high, breakout_low, atr_value, atr_pct, adx_value, f"no breakout or Fibonacci continuation aligned with higher-timeframe trend={trend_side}")


def model(reason: str) -> str:
    r = reason.lower()
    if "fibonacci" in r:
        return "Fibonacci"
    if "donchian" in r or "breakout" in r:
        return "Donchian"
    if "funding carry" in r:
        return "FundingCarry"
    return "Other"


def breadth_at(symbol_bars: Dict[str, List[Bar]], ts: int, cfg: Config) -> Breadth:
    long_count = short_count = total = 0
    rets = []
    lookback_ts = ts - cfg.market_breadth_lookback_bars * cfg.period_seconds
    by_symbol = {s: {b.ts: b for b in bars} for s, bars in symbol_bars.items()}
    for sym, mapping in by_symbol.items():
        cur = mapping.get(ts)
        prev = mapping.get(lookback_ts)
        if not cur or not prev or prev.close <= 0:
            continue
        ret = (cur.close - prev.close) / prev.close
        rets.append(ret)
        total += 1
        if ret > cfg.min_atr_pct:
            long_count += 1
        elif ret < -cfg.min_atr_pct:
            short_count += 1
    if total <= 0:
        return Breadth("MIXED", 0, 0, 0, 0.0, 0.0, 0.0)
    lr = long_count / total
    sr = short_count / total
    if lr >= cfg.market_breadth_threshold and sr <= cfg.market_breadth_opposite_max:
        regime = "BROAD_UP"
    elif sr >= cfg.market_breadth_threshold and lr <= cfg.market_breadth_opposite_max:
        regime = "BROAD_DOWN"
    else:
        regime = "MIXED"
    return Breadth(regime, total, long_count, short_count, lr, sr, median(rets) if rets else 0.0)


def aligned(side: str, breadth: Breadth) -> bool:
    return (side == "LONG" and breadth.regime == "BROAD_UP") or (side == "SHORT" and breadth.regime == "BROAD_DOWN")


def against(side: str, breadth: Breadth) -> bool:
    return (side == "LONG" and breadth.regime == "BROAD_DOWN") or (side == "SHORT" and breadth.regime == "BROAD_UP")


def quality_from_trades(trades: List[Trade], cfg: Config) -> Quality:
    recent = trades[-cfg.quality_lookback_trades:]
    if len(recent) < cfg.quality_min_closed_trades:
        return Quality("NEUTRAL", len(recent), 0, 0, 999.0, sum(t.net_pnl for t in recent))
    wins = sum(1 for t in recent if t.net_pnl > 0)
    losses = sum(1 for t in recent if t.net_pnl < 0)
    gp = sum(t.net_pnl for t in recent if t.net_pnl > 0)
    gl = -sum(t.net_pnl for t in recent if t.net_pnl < 0)
    pf = 999.0 if gl <= 0 and gp > 0 else gp / gl if gl > 0 else 0.0
    net = gp - gl
    if wins <= losses or pf < cfg.quality_min_profit_factor or net < 0:
        regime = "WEAK"
    elif wins > losses and pf >= cfg.quality_strong_profit_factor and net > 0:
        regime = "STRONG"
    else:
        regime = "NEUTRAL"
    return Quality(regime, len(recent), wins, losses, pf, net)


def sizing_multiplier(side: str, breadth: Breadth, quality: Quality, cfg: Config) -> float:
    m = 1.0
    if quality.regime == "WEAK":
        m *= cfg.weak_quality_multiplier
    elif quality.regime == "STRONG":
        m *= cfg.strong_quality_multiplier
    if aligned(side, breadth):
        m *= cfg.breadth_aligned_multiplier
    elif against(side, breadth):
        m *= cfg.breadth_counter_multiplier
    return min(cfg.max_adaptive_multiplier, max(cfg.min_adaptive_multiplier, m))


def target_notional(signal: Signal, entry_price: float, breadth: Breadth, quality: Quality, cfg: Config) -> float:
    if entry_price <= 0 or signal.atr <= 0:
        return 0.0
    m = sizing_multiplier(signal.side, breadth, quality, cfg)
    stop_rate = (signal.atr * cfg.atr_stop_multiplier) / entry_price
    if stop_rate <= 0:
        return 0.0
    risk_notional = cfg.risk_per_trade_usdt * m / stop_rate
    notional = min(cfg.target_quote_notional * m, cfg.max_quote_notional * m, risk_notional)
    return notional if notional >= cfg.min_quote_notional else 0.0


def candidate_score(signal: Signal, breadth: Breadth) -> float:
    score = signal.adx + signal.atr_pct * 10000.0
    r = signal.reason.lower()
    if "broke donchian" in r:
        score += 30.0
    if "fibonacci" in r:
        score += 20.0
    if "funding carry" in r:
        score -= 15.0
    if aligned(signal.side, breadth):
        score += 10.0
    return score


def direction_confidence(signal: Signal, bars: List[Bar], breadth: Breadth, quality: Quality, cfg: Config) -> Tuple[float, float, str]:
    score = 0.0
    reasons = []
    if signal.adx >= cfg.min_adx:
        score += 25.0 + min(max(signal.adx - cfg.min_adx, 0.0), 15.0)
        reasons.append("adx+")
    else:
        score -= 20.0
        reasons.append("adx-")
    if signal.atr_pct >= cfg.min_atr_pct:
        score += 10.0
        if signal.atr_pct >= cfg.min_atr_pct * 2:
            score += 5.0
        reasons.append("atr+")
    else:
        score -= 15.0
        reasons.append("atr-")
    if (signal.side == "LONG" and signal.ema_fast > signal.ema_slow) or (signal.side == "SHORT" and signal.ema_fast < signal.ema_slow):
        score += 15.0
        reasons.append("ema+")
    else:
        score -= 20.0
        reasons.append("ema-")
    r = signal.reason.lower()
    if "broke donchian" in r:
        score += 20.0
        reasons.append("donchian+")
    elif "fibonacci" in r:
        score += 10.0
        reasons.append("fibonacci+")
    elif "funding carry" in r:
        score += 5.0
        reasons.append("carry+")
    if len(bars) >= 2 and ((signal.side == "LONG" and bars[-1].close > bars[-2].close) or (signal.side == "SHORT" and bars[-1].close < bars[-2].close)):
        score += 10.0
        reasons.append("localMomentum+")
    else:
        score -= 10.0
        reasons.append("localMomentum-")
    if aligned(signal.side, breadth):
        score += 20.0
        reasons.append("breadth+")
    elif against(signal.side, breadth):
        score -= 40.0
        reasons.append("breadthCounter-")
    else:
        score -= 5.0
        reasons.append("breadthMixed-")
    if quality.regime == "STRONG":
        score += 5.0
        reasons.append("quality+")
    elif quality.regime == "WEAK":
        score -= 10.0
        reasons.append("quality-")
    required = cfg.min_direction_confidence_score
    if breadth.regime == "MIXED":
        required = max(required, cfg.min_mixed_direction_confidence_score)
    if against(signal.side, breadth):
        required = max(required, cfg.min_counter_breadth_direction_confidence_score)
    if "fibonacci" in r:
        required = max(required, cfg.fibonacci_min_direction_confidence_score)
    return score, required, " ".join(reasons)


def pnl_for(side: str, entry: float, exit_price: float, notional: float) -> float:
    if entry <= 0:
        return 0.0
    qty = notional / entry
    return (exit_price - entry) * qty if side == "LONG" else (entry - exit_price) * qty


def net_for(side: str, entry: float, exit_price: float, notional: float, cfg: Config) -> float:
    return pnl_for(side, entry, exit_price, notional) - (entry + exit_price) * (notional / entry) * cfg.estimated_taker_fee_rate


def exit_position(pos: Position, exit_time: int, exit_price: float, reason: str, cfg: Config) -> Trade:
    gross = pnl_for(pos.side, pos.entry_price, exit_price, pos.notional)
    qty = pos.notional / pos.entry_price
    fees = (pos.entry_price + exit_price) * qty * cfg.estimated_taker_fee_rate
    net = gross - fees
    return Trade(pos.symbol, pos.side, pos.entry_time, exit_time, pos.entry_price, exit_price, pos.notional,
                 gross, fees, net, pos.best_net, pos.worst_net, pos.entry_model, reason,
                 pos.breadth_regime, pos.direction_score)


def run_backtest(profile: ReplayProfile, symbols: List[str], bars15: Dict[str, List[Bar]], bars60: Dict[str, List[Bar]],
                 start_ts: int, end_ts: int, cfg: Config) -> RunStats:
    cfg = Config(**{
        **cfg.__dict__,
        "target_quote_notional": profile.target_quote_notional,
        "max_quote_notional": profile.max_quote_notional,
        "risk_per_trade_usdt": profile.risk_per_trade_usdt,
    })
    direction_gate = profile.direction_gate_enabled
    stats = RunStats(profile.name)
    cooldown_until: Dict[str, int] = {}
    positions: Dict[str, Position] = {}
    all_times = sorted({b.ts for bars in bars15.values() for b in bars if start_ts <= b.ts < end_ts})
    idx15 = {s: {b.ts: i for i, b in enumerate(bars)} for s, bars in bars15.items()}
    mapping15 = {s: {b.ts: b for b in bars} for s, bars in bars15.items()}

    for ts in all_times:
        now_epoch = ts + cfg.period_seconds
        breadth = breadth_at(bars15, ts, cfg)
        signals: Dict[str, Signal] = {}
        entry_history: Dict[str, List[Bar]] = {}
        for sym in symbols:
            signal = evaluate_signal(bars15.get(sym, []), bars60.get(sym, []), cfg, now_epoch)
            signals[sym] = signal
            entry_history[sym] = completed(bars15.get(sym, []), cfg.period_seconds, now_epoch)

        # manage active positions first
        for sym in list(positions.keys()):
            pos = positions[sym]
            bar = mapping15.get(sym, {}).get(ts)
            if not bar:
                continue
            pos.highest = max(pos.highest, bar.high)
            pos.lowest = min(pos.lowest, bar.low)
            current_net = net_for(pos.side, pos.entry_price, bar.close, pos.notional, cfg)
            best_ref = pos.highest if pos.side == "LONG" else pos.lowest
            worst_ref = pos.lowest if pos.side == "LONG" else pos.highest
            pos.best_net = max(pos.best_net, net_for(pos.side, pos.entry_price, best_ref, pos.notional, cfg))
            pos.worst_net = min(pos.worst_net, net_for(pos.side, pos.entry_price, worst_ref, pos.notional, cfg))
            signal = signals.get(sym)
            if signal and signal.side == "FLAT":
                pos.flat_count += 1
            elif signal and signal.side != pos.side:
                pos.flat_count = 0
            exit_reason = None
            if signal and signal.side == "FLAT" and pos.flat_count >= cfg.flat_loss_exit_confirmation_bars:
                loss_budget = max(cfg.max_flat_exit_loss_usdt, pos.notional * cfg.max_flat_exit_loss_rate)
                if current_net < 0 and abs(current_net) >= loss_budget:
                    exit_reason = "flat-loss"
            if exit_reason is None and profile.adverse_shock_exit_enabled and now_epoch - pos.entry_time >= cfg.period_seconds * cfg.adverse_shock_exit_bars:
                adverse_ref = pos.lowest if pos.side == "LONG" else pos.highest
                adverse_move = pos.entry_price - adverse_ref if pos.side == "LONG" else adverse_ref - pos.entry_price
                required_move = pos.atr * cfg.adverse_shock_atr_multiplier
                if current_net < 0 and pos.best_net < cfg.adverse_shock_min_favorable_net_usdt and adverse_move >= required_move:
                    exit_reason = "adverse-shock"
            if exit_reason is None and profile.no_extension_exit_enabled and now_epoch - pos.entry_time >= cfg.period_seconds * cfg.no_extension_exit_bars:
                if current_net < 0 and pos.best_net < cfg.no_extension_min_favorable_net_usdt:
                    exit_reason = "no-extension"
            if exit_reason is None and signal and signal.side != "FLAT" and signal.side != pos.side:
                exit_reason = "opposite-signal"
            if exit_reason is None:
                distance = pos.atr * cfg.trailing_atr_multiplier
                if pos.side == "LONG":
                    pos.trailing_stop = max(pos.trailing_stop, pos.highest - distance)
                    if bar.close <= pos.trailing_stop:
                        exit_reason = "trailing"
                else:
                    pos.trailing_stop = min(pos.trailing_stop, pos.lowest + distance)
                    if bar.close >= pos.trailing_stop:
                        exit_reason = "trailing"
            if exit_reason:
                trade = exit_position(pos, now_epoch, bar.close, exit_reason, cfg)
                stats.trades.append(trade)
                positions.pop(sym, None)
                if trade.net_pnl < 0:
                    cooldown_until[sym] = now_epoch + 3600
                elif trade.net_pnl >= 1.0:
                    cooldown_until[sym] = now_epoch + 900
                else:
                    cooldown_until[sym] = now_epoch + 1800

        # rank and open candidates at next bar open
        quality = quality_from_trades(stats.trades, cfg)
        candidates: List[Candidate] = []
        for sym in symbols:
            if sym in positions or cooldown_until.get(sym, 0) > now_epoch:
                continue
            signal = signals.get(sym)
            if not signal or signal.side == "FLAT":
                continue
            i = idx15.get(sym, {}).get(ts)
            if i is None or i + 1 >= len(bars15.get(sym, [])):
                continue
            next_bar = bars15[sym][i + 1]
            if next_bar.ts >= end_ts:
                continue
            if signal.atr <= 0 or signal.close <= 0:
                continue
            expected_move = signal.atr * cfg.min_expected_move_atr_multiplier
            required_move = signal.close * (cfg.estimated_taker_fee_rate * 2 + cfg.min_net_profit_rate)
            if expected_move < required_move:
                continue
            worse = next_bar.open - signal.close if signal.side == "LONG" else signal.close - next_bar.open
            if worse > 0 and worse > signal.atr * cfg.max_entry_signal_chase_atr_multiplier:
                skipped = Candidate(sym, signal, candidate_score(signal, breadth), breadth, quality, next_bar.open, skip_reason="chase")
                stats.skipped.append(skipped)
                continue
            dc_score = dc_required = None
            if direction_gate:
                dc_score, dc_required, _ = direction_confidence(signal, entry_history[sym], breadth, quality, cfg)
                if dc_score < dc_required:
                    skipped = Candidate(sym, signal, candidate_score(signal, breadth), breadth, quality, next_bar.open, dc_score, dc_required, "direction")
                    stats.skipped.append(skipped)
                    continue
            notional = target_notional(signal, next_bar.open, breadth, quality, cfg)
            if notional <= 0:
                continue
            candidates.append(Candidate(sym, signal, candidate_score(signal, breadth), breadth, quality, next_bar.open, dc_score, dc_required))
        candidates.sort(key=lambda c: c.score, reverse=True)
        slots = cfg.max_active_positions - len(positions)
        for c in candidates[:max(0, slots)]:
            entry_price = c.next_open
            init_stop = entry_price - c.signal.atr * cfg.atr_stop_multiplier if c.signal.side == "LONG" else entry_price + c.signal.atr * cfg.atr_stop_multiplier
            positions[c.symbol] = Position(c.symbol, c.signal.side, c.signal.bar_time + cfg.period_seconds, entry_price,
                                           target_notional(c.signal, entry_price, c.breadth, c.quality, cfg), c.signal.atr,
                                           c.signal.reason, model(c.signal.reason), c.breadth.regime, c.direction_score,
                                           entry_price, entry_price, init_stop)
    # force close at final close for comparability
    for sym, pos in list(positions.items()):
        bars = [b for b in bars15.get(sym, []) if b.ts < end_ts]
        if not bars:
            continue
        stats.trades.append(exit_position(pos, bars[-1].ts + cfg.period_seconds, bars[-1].close, "end-of-window", cfg))
    stats.open_positions = list(positions.values())
    return stats


def summarize(trades: List[Trade]) -> Dict[str, float]:
    n = len(trades)
    net = sum(t.net_pnl for t in trades)
    gross = sum(t.gross_pnl for t in trades)
    fees = sum(t.fees for t in trades)
    wins = [t for t in trades if t.net_pnl > 0]
    losses = [t for t in trades if t.net_pnl < 0]
    gp = sum(t.net_pnl for t in wins)
    gl = -sum(t.net_pnl for t in losses)
    return {
        "trades": n,
        "net_win_rate": len(wins) / n if n else 0.0,
        "gross_pnl": gross,
        "fees": fees,
        "net_pnl": net,
        "profit_factor": (gp / gl if gl > 0 else 999.0 if gp > 0 else 0.0),
        "avg_win": gp / len(wins) if wins else 0.0,
        "avg_loss": gl / len(losses) if losses else 0.0,
        "max_loss": min((t.net_pnl for t in trades), default=0.0),
    }


def group_by_day(trades: List[Trade]) -> Dict[str, Dict[str, float]]:
    days = sorted({day(t.entry_time) for t in trades})
    return {d: summarize([t for t in trades if day(t.entry_time) == d]) for d in days}


def sleeve_summary(trades: List[Trade], symbols: Optional[List[str]] = None) -> Dict[str, Dict[str, float]]:
    grouped: Dict[str, List[Trade]] = {}
    for trade in trades:
        grouped.setdefault(f"{trade.symbol}:{trade.entry_model}", []).append(trade)
    summary = {key: summarize(rows) for key, rows in sorted(grouped.items())}
    for symbol in symbols or []:
        if not any(key.startswith(f"{symbol}:") for key in summary):
            summary[f"{symbol}:NoSignal"] = summarize([])
    return dict(sorted(summary.items()))


def sleeve_decision(metrics: Dict[str, float]) -> str:
    if metrics["trades"] < 3:
        return "INSUFFICIENT_SAMPLE"
    if metrics["net_pnl"] <= 0 or metrics["profit_factor"] < 1.20 or metrics["max_loss"] < -1.5:
        return "REJECT"
    return "PAPER_CANDIDATE"


def write_outputs(out_dir: Path, legacy: RunStats, repaired: RunStats, symbols: List[str],
                  start_label: str, end_label: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    for run in (legacy, repaired):
        with (out_dir / f"{run.name}_trades.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["symbol", "side", "entry_time", "exit_time", "entry_price", "exit_price", "notional", "gross_pnl", "fees", "net_pnl", "mfe", "mae", "model", "exit_reason", "breadth", "direction_score"])
            for t in run.trades:
                w.writerow([t.symbol, t.side, fmt_ts(t.entry_time), fmt_ts(t.exit_time), f"{t.entry_price:.10g}", f"{t.exit_price:.10g}", f"{t.notional:.4f}", f"{t.gross_pnl:.4f}", f"{t.fees:.4f}", f"{t.net_pnl:.4f}", f"{t.mfe:.4f}", f"{t.mae:.4f}", t.entry_model, t.exit_reason, t.breadth_regime, "" if t.direction_score is None else f"{t.direction_score:.2f}"])
        with (out_dir / f"{run.name}_skipped.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["symbol", "bar_time", "side", "model", "reason", "skip_reason", "score", "required", "breadth"])
            for c in run.skipped:
                w.writerow([c.symbol, fmt_ts(c.signal.bar_time), c.signal.side, model(c.signal.reason), c.signal.reason, c.skip_reason, "" if c.direction_score is None else f"{c.direction_score:.2f}", "" if c.direction_required is None else f"{c.direction_required:.2f}", c.breadth.regime])
    recovery_sleeves = sleeve_summary(repaired.trades, symbols)
    payload = {
        legacy.name: {"total": summarize(legacy.trades), "days": group_by_day(legacy.trades)},
        repaired.name: {"total": summarize(repaired.trades), "days": group_by_day(repaired.trades)},
        "skip_counts": {},
        "recovery_sleeves": {key: {**metrics, "decision": sleeve_decision(metrics)}
                              for key, metrics in recovery_sleeves.items()},
    }
    for c in repaired.skipped:
        key = f"{c.skip_reason}:{model(c.signal.reason)}:{c.breadth.regime}"
        payload["skip_counts"][key] = payload["skip_counts"].get(key, 0) + 1
    (out_dir / "summary.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    md = []
    md.append(f"# HTX trend strategy-level backtest {start_label} to {end_label}")
    md.append("")
    md.append("This replay regenerates signals from historical 15m/60m klines. The recovery profile matches the deployed recovery package: direction-confidence gating remains enabled, adverse-shock/no-extension exits are disabled, and sizing is capped at 190 USDT with a 1.5 USDT risk budget. Funding-carry entries and historical order-book depth are not simulated.")
    md.append("")
    md.append("## Daily comparison")
    md.append("")
    md.append("| mode | day | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |")
    md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for run in (legacy, repaired):
        for d, s in group_by_day(run.trades).items():
            md.append(f"| {run.name} | {d} | {s['trades']} | {s['net_win_rate']*100:.2f}% | {s['gross_pnl']:.4f} | {s['fees']:.4f} | {s['net_pnl']:.4f} | {s['profit_factor']:.2f} | {s['avg_win']:.4f} | {s['avg_loss']:.4f} | {s['max_loss']:.4f} |")
    md.append("")
    md.append("## Total comparison")
    md.append("")
    md.append("| mode | trades | net win | gross PnL | fees | net PnL | PF | avg win | avg loss | max loss |")
    md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for run in (legacy, repaired):
        s = summarize(run.trades)
        md.append(f"| {run.name} | {s['trades']} | {s['net_win_rate']*100:.2f}% | {s['gross_pnl']:.4f} | {s['fees']:.4f} | {s['net_pnl']:.4f} | {s['profit_factor']:.2f} | {s['avg_win']:.4f} | {s['avg_loss']:.4f} | {s['max_loss']:.4f} |")
    md.append("")
    md.append("## Recovery sleeve decisions")
    md.append("")
    md.append("| sleeve | trades | net PnL | PF | max loss | decision |")
    md.append("|---|---:|---:|---:|---:|---|")
    for key, metrics in recovery_sleeves.items():
        md.append(f"| {key} | {metrics['trades']} | {metrics['net_pnl']:.4f} | {metrics['profit_factor']:.2f} | {metrics['max_loss']:.4f} | {sleeve_decision(metrics)} |")
    md.append("")
    md.append("## Repaired skip counts")
    md.append("")
    md.append("| skip bucket | count |")
    md.append("|---|---:|")
    for k, v in sorted(payload["skip_counts"].items(), key=lambda kv: (-kv[1], kv[0]))[:20]:
        md.append(f"| {k} | {v} |")
    md.append("")
    md.append("## Boundary")
    md.append("")
    md.append("This is a deterministic kline replay, not a perfect exchange simulator. It does not include historical funding-rate carry promotion, real order-book depth, partial fills, liquidation margin state, or intrabar polling order. It is useful for comparing strategy logic across market regimes before deploying real trading code.")
    (out_dir / "summary.md").write_text("\n".join(md) + "\n", encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True, type=Path)
    ap.add_argument("--out-dir", type=Path)
    ap.add_argument("--start", default="2026-09-18")
    ap.add_argument("--end", default="2026-09-22")
    ap.add_argument("--symbols", default=",".join(DEFAULT_SYMBOLS))
    ap.add_argument("--validate-data", action="store_true",
                    help="verify frozen inputs cover the requested replay window, then exit")
    ap.add_argument("--capture-data", action="store_true",
                    help="fetch and persist raw public HTX K-lines, then exit")
    args = ap.parse_args()
    cfg = Config()
    symbols = [s.strip() for s in args.symbols.split(",") if s.strip()]
    if args.capture_data:
        sizes = {"15min": 2000, "30min": 1200, "60min": 700, "4hour": 300}
        for symbol in symbols:
            for period, size in sizes.items():
                path = capture_kline(args.data_dir, symbol, period, size)
                print(path)
        return
    bars15 = {s: load_bars(args.data_dir, s, "15min") for s in symbols}
    bars60 = {s: load_bars(args.data_dir, s, "60min") for s in symbols}
    symbols = [s for s in symbols if bars15.get(s) and bars60.get(s)]
    start_ts = int(parse_day(args.start).timestamp())
    end_ts = int(parse_day(args.end).timestamp())
    if args.validate_data:
        missing = []
        for symbol in [s.strip() for s in args.symbols.split(",") if s.strip()]:
            for period, seconds, rows in (("15min", cfg.period_seconds, bars15.get(symbol, [])),
                                          ("60min", cfg.trend_period_seconds, bars60.get(symbol, []))):
                if not rows or rows[0].ts > start_ts or rows[-1].ts < end_ts - seconds:
                    missing.append(f"{symbol} {period}")
                else:
                    print(f"coverage {symbol} {period}: {fmt_ts(rows[0].ts)} -> {fmt_ts(rows[-1].ts)}")
        if missing:
            raise SystemExit("insufficient input coverage: " + ", ".join(missing))
        return
    if args.out_dir is None:
        ap.error("--out-dir is required unless --validate-data is used")
    legacy = run_backtest(BASELINE_PROFILE, symbols, bars15, bars60, start_ts, end_ts, cfg)
    repaired = run_backtest(RECOVERY_PROFILE, symbols, bars15, bars60, start_ts, end_ts, cfg)
    write_outputs(args.out_dir, legacy, repaired, symbols, args.start, args.end)
    print(args.out_dir / "summary.md")


if __name__ == "__main__":
    main()
