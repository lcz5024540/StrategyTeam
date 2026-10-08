"""Adapters for frozen HTX public K-line envelopes already used by research tools."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Iterable
import json

from .market_data import Candle, period_seconds
from .models import TrendConfig, candidates, evaluate_signal


@dataclass(frozen=True, slots=True)
class DatasetReplaySummary:
    contract_code: str
    entry_period: str
    trend_period: str
    entry_bars: int
    evaluated_bars: int
    long_signals: int
    short_signals: int
    candidates: dict[str, int]
    incomplete: bool


def load_htx_envelope(path: Path) -> tuple[Candle, ...]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    data = payload.get("data")
    if payload.get("status") != "ok" or not isinstance(data, list):
        raise ValueError(f"invalid frozen HTX envelope: {path}")
    return tuple(sorted((Candle.from_htx(item) for item in data), key=lambda item: item.open_time))


def replay_frozen_contract(data_dir: Path, contract_code: str, *, entry_period: str = "15min", trend_period: str = "60min",
                           config: TrendConfig = TrendConfig(), runtime_window_size: int = 160) -> DatasetReplaySummary:
    entry = load_htx_envelope(data_dir / f"{contract_code}_{entry_period}.json")
    trend = load_htx_envelope(data_dir / f"{contract_code}_{trend_period}.json")
    required = max(config.slow_ema_period, config.breakout_lookback) + max(config.atr_period, config.adx_period) + 3
    evaluated = long_count = short_count = 0
    model_counts: dict[str, int] = {}
    trend_step = period_seconds(trend_period)
    trend_closed = 0
    for index in range(required, len(entry) + 1):
        # Java fetches a bounded K-line size per live calculation; retain the same bounded input so
        # frozen replay has identical no-lookahead and computational semantics.
        entry_prefix = entry[max(0, index - runtime_window_size):index]
        # Advance a monotonic pointer: no higher-timeframe bar is revisited and no future bar leaks in.
        cutoff = entry_prefix[-1].open_time + period_seconds(entry_period)
        while trend_closed < len(trend) and trend[trend_closed].open_time + trend_step <= cutoff:
            trend_closed += 1
        trend_prefix = trend[max(0, trend_closed - runtime_window_size):trend_closed]
        signal = evaluate_signal(entry_prefix, trend_prefix, config)
        if signal.bar_time is None:
            continue
        evaluated += 1
        long_count += signal.side.value == "LONG"; short_count += signal.side.value == "SHORT"
        for candidate in candidates(entry_prefix, signal, config):
            model_counts[candidate.model.value] = model_counts.get(candidate.model.value, 0) + 1
    return DatasetReplaySummary(contract_code, entry_period, trend_period, len(entry), evaluated, long_count, short_count,
                                dict(sorted(model_counts.items())), evaluated == 0)
