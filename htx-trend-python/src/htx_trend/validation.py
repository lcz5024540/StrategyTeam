"""Evidence-based replay summaries and Java/Python parity comparison."""
from __future__ import annotations

from dataclasses import asdict, dataclass, is_dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable
import json

from .java_entry_contract import JavaEntryConfig
from .models import route_candidates
from .replay import ReplaySnapshot
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact
from .strategy_decision import HtxStrategyDecisionEngine, LEGACY_STRATEGY_VERSION


@dataclass(frozen=True, slots=True)
class ReplayRow:
    snapshot_key: str
    contract_code: str
    period: str
    integrity: str
    side: str | None
    candidates: tuple[str, ...]
    reason: str


@dataclass(frozen=True, slots=True)
class ReplayReport:
    schema: str
    snapshots: int
    complete_windows: int
    insufficient_windows: int
    rows: tuple[ReplayRow, ...]


def replay_report(snapshots: Iterable[ReplaySnapshot], config: JavaEntryConfig = JavaEntryConfig(), *,
                  entry_period: str = "15min", strategy_version: str = LEGACY_STRATEGY_VERSION,
                  admission: StrategyAdmissionArtifact | None = None) -> ReplayReport:
    rows: list[ReplayRow] = []
    complete = insufficient = 0
    snapshots = tuple(snapshots)
    for item in (s for s in snapshots if s.window.period == entry_period):
        # Replay the same bounded completed-bar input consumed by LIVE. Market
        # breadth and anchor evidence remain separate runtime context inputs.
        required = max(config.slow_ema_period, config.breakout_lookback, config.momentum_lookback,
                       config.atr_period, config.adx_period, config.volume_lookback) + 5
        if not item.window.is_complete or len(item.window.candles) < required:
            insufficient += 1
            rows.append(ReplayRow(item.key, item.window.contract_code, item.window.period, "PARTIAL_OR_INSUFFICIENT", None, (),
                                  "not enough complete frozen candles for signal evaluation"))
            continue
        complete += 1
        decision = HtxStrategyDecisionEngine(strategy_version=strategy_version, config=config,
            admission=admission).decide(contract_code=item.window.contract_code, period=item.window.period,
                                        candles=item.window.candles, surface=DecisionSurface.BACKTEST)
        signal, choices = decision.signal, decision.candidates
        route = route_candidates(choices)
        rows.append(ReplayRow(item.key, item.window.contract_code, item.window.period, "COMPLETE",
                              route.primary.side.value if route.primary else signal.side.value,
                              tuple(choice.model.value for choice in choices), route.reason))
    return ReplayReport("htx-trend-python-same-source-replay-v3", len(rows), complete, insufficient, tuple(rows))


def to_json(value: Any) -> Any:
    if isinstance(value, Decimal): return str(value)
    if is_dataclass(value): return {key: to_json(item) for key, item in asdict(value).items()}
    if isinstance(value, dict): return {str(key): to_json(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)): return [to_json(item) for item in value]
    return value


def compare_json(java: Any, python: Any, *, path: str = "$") -> list[dict[str, Any]]:
    """Structural comparison for an exported Java decision and a Python decision."""
    differences: list[dict[str, Any]] = []
    if type(java) is not type(python):
        return [{"path": path, "java": java, "python": python, "reason": "type mismatch"}]
    if isinstance(java, dict):
        for key in sorted(set(java) | set(python)):
            if key not in java or key not in python:
                differences.append({"path": f"{path}.{key}", "java": java.get(key), "python": python.get(key), "reason": "missing key"})
            else:
                differences.extend(compare_json(java[key], python[key], path=f"{path}.{key}"))
    elif isinstance(java, list):
        if len(java) != len(python): differences.append({"path": path, "java": len(java), "python": len(python), "reason": "length mismatch"})
        for index, (left, right) in enumerate(zip(java, python)):
            differences.extend(compare_json(left, right, path=f"{path}[{index}]"))
    elif java != python:
        differences.append({"path": path, "java": java, "python": python, "reason": "value mismatch"})
    return differences


def save_report(path: Path, report: ReplayReport) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json(report), ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
