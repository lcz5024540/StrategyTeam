"""Frozen HTX K-line validation and same-source decision replay."""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
import json

from .java_entry_contract import JavaEntryConfig
from .market_data import Candle, period_seconds
from .models import TrendSide
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact
from .strategy_decision import HtxStrategyDecisionEngine, LEGACY_STRATEGY_VERSION


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
    manifest_hash: str
    strategy_version: str
    decision_contract: str = "htx-python-decision-v1"


def load_htx_envelope(path: Path, *, period: str | None = None) -> tuple[Candle, ...]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    data = payload.get("data")
    if payload.get("status") != "ok" or not isinstance(data, list):
        raise ValueError(f"invalid frozen HTX envelope: {path}")
    raw = tuple(Candle.from_htx(item) for item in data)
    if not raw:
        raise ValueError(f"empty frozen HTX envelope: {path}")
    times = [item.open_time for item in raw]
    if len(set(times)) != len(times):
        raise ValueError(f"duplicate HTX K-line timestamp: {path}")
    if times != sorted(times) and times != sorted(times, reverse=True):
        raise ValueError(f"HTX K-line timestamps are neither ascending nor descending: {path}")
    for item in raw:
        if item.high < max(item.open, item.close) or item.low > min(item.open, item.close) or item.low > item.high:
            raise ValueError(f"invalid OHLC at {item.open_time}: {path}")
        if any(value is not None and value < 0 for value in (item.amount, item.volume, item.turnover)):
            raise ValueError(f"negative volume at {item.open_time}: {path}")
    ordered = tuple(sorted(raw, key=lambda item: item.open_time))
    if period is not None:
        step = period_seconds(period)
        gaps = [(a.open_time, b.open_time) for a, b in zip(ordered, ordered[1:]) if b.open_time - a.open_time != step]
        if gaps:
            raise ValueError(f"HTX K-line continuity gap count={len(gaps)} first={gaps[0]}: {path}")
    return ordered


def validate_manifest(manifest_path: Path, data_dir: Path, contract_code: str,
                      periods: tuple[str, ...]) -> str:
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    if manifest.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported frozen dataset manifest schema")
    if manifest.get("inputIntegrity") != "COMPLETE" or manifest.get("missingOrPartial"):
        raise ValueError("frozen dataset manifest is not COMPLETE")
    inputs = manifest.get("inputs")
    if not isinstance(inputs, list):
        raise ValueError("frozen dataset manifest inputs are missing")
    for period in periods:
        matches = [item for item in inputs if item.get("contract") == contract_code and item.get("period") == period]
        if len(matches) != 1 or matches[0].get("coverage") != "COMPLETE":
            raise ValueError(f"manifest lacks one COMPLETE input for {contract_code}:{period}")
        item = matches[0]
        path = data_dir / item["file"]
        if not path.is_file() or sha256(path.read_bytes()).hexdigest() != item.get("sha256"):
            raise ValueError(f"frozen input hash mismatch for {contract_code}:{period}")
    return sha256(raw).hexdigest()


def replay_frozen_contract(data_dir: Path, contract_code: str, *, manifest_path: Path,
                           entry_period: str = "15min", trend_period: str = "60min",
                           config: JavaEntryConfig = JavaEntryConfig(), runtime_window_size: int = 160,
                           strategy_version: str = LEGACY_STRATEGY_VERSION,
                           admission: StrategyAdmissionArtifact | None = None) -> DatasetReplaySummary:
    manifest_hash = validate_manifest(manifest_path, data_dir, contract_code, (entry_period, trend_period))
    entry = load_htx_envelope(data_dir / f"{contract_code}_{entry_period}.json", period=entry_period)
    load_htx_envelope(data_dir / f"{contract_code}_{trend_period}.json", period=trend_period)
    required = max(config.slow_ema_period, config.breakout_lookback, config.momentum_lookback,
                   config.atr_period, config.adx_period, config.volume_lookback) + 5
    engine = HtxStrategyDecisionEngine(strategy_version=strategy_version, config=config, admission=admission)
    evaluated = long_count = short_count = 0
    model_counts: dict[str, int] = {}
    for index in range(required, len(entry) + 1):
        prefix = entry[max(0, index - runtime_window_size):index]
        decision = engine.decide(contract_code=contract_code, period=entry_period, candles=prefix,
                                 surface=DecisionSurface.BACKTEST)
        if decision.signal.bar_time is None:
            continue
        evaluated += 1
        long_count += decision.signal.side is TrendSide.LONG
        short_count += decision.signal.side is TrendSide.SHORT
        for candidate in decision.candidates:
            model_counts[candidate.model.value] = model_counts.get(candidate.model.value, 0) + 1
    return DatasetReplaySummary(contract_code, entry_period, trend_period, len(entry), evaluated, long_count, short_count,
                                dict(sorted(model_counts.items())), evaluated == 0, manifest_hash, strategy_version)
