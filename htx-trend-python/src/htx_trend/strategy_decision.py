"""One HTX entry decision core shared by research, replay and runtimes."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from decimal import Decimal
from hashlib import sha256
from typing import Sequence
import json

from .java_entry_contract import JavaEntryConfig, JavaTrendSignal, candidates, evaluate
from .market_data import Candle
from .models import ModelCandidate
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact

LEGACY_STRATEGY_VERSION = "HTX_TREND_V1"


@dataclass(frozen=True, slots=True)
class StrategyDecision:
    strategy_version: str
    contract_code: str
    period: str
    signal: JavaTrendSignal
    candidates: tuple[ModelCandidate, ...]
    input_hash: str
    config_hash: str
    decision_hash: str
    trace_ref: str | None = None


class HtxStrategyDecisionEngine:
    """Deterministic HTX decision boundary.

    The grandfathered V1 contract keeps the current production behavior. Any
    newly named strategy version must present an admission artifact for the
    requested surface.
    """
    def __init__(self, *, strategy_version: str = LEGACY_STRATEGY_VERSION,
                 config: JavaEntryConfig = JavaEntryConfig(),
                 admission: StrategyAdmissionArtifact | None = None, evidence_store=None) -> None:
        self.strategy_version, self.config, self.admission = strategy_version, config, admission
        self.evidence_store = evidence_store

    def decide(self, *, contract_code: str, period: str, candles: Sequence[Candle],
               surface: DecisionSurface | str, evidence_refs: tuple[str, ...] = ()) -> StrategyDecision:
        self._authorize(DecisionSurface(surface))
        ordered = tuple(sorted(candles, key=lambda item: item.open_time))
        signal = evaluate(ordered, self.config)
        output = candidates(ordered, signal, self.config, period)
        input_hash = sha256("\n".join(item.content_hash for item in ordered).encode()).hexdigest()
        config_hash = _config_hash(self.config)
        payload = {"strategy_version": self.strategy_version, "contract_code": contract_code, "period": period,
                   "input_hash": input_hash, "config_hash": config_hash, "bar_time": signal.bar_time,
                   "side": signal.side.value, "candidate_models": [item.model.value for item in output]}
        decision_hash = sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        trace_ref = None
        if self.evidence_store is not None and self.strategy_version == LEGACY_STRATEGY_VERSION:
            from .market_data import period_seconds
            from .research.baseline import trace_entry
            from .research.contracts import DecisionContext
            evaluated = datetime.fromtimestamp(
                (ordered[-1].open_time + period_seconds(period)) if ordered else 0, UTC).isoformat()
            context = DecisionContext(decision_hash, self.strategy_version, DecisionSurface(surface).value,
                evaluated, {"contract_code": contract_code, "period": period, "input_hash": input_hash},
                {}, {}, evidence_refs)
            trace = trace_entry(context, ordered, period, signal, output, self.config)
            trace_ref = self.evidence_store.put("decision_trace", trace, refs=evidence_refs)
        return StrategyDecision(self.strategy_version, contract_code, period, signal, output,
                                input_hash, config_hash, decision_hash, trace_ref)

    def require(self, surface: DecisionSurface | str) -> None:
        self._authorize(DecisionSurface(surface))

    def _authorize(self, surface: DecisionSurface) -> None:
        if self.strategy_version == LEGACY_STRATEGY_VERSION:
            return
        if self.admission is None:
            raise ValueError(f"new strategy {self.strategy_version} requires a sealed admission artifact")
        if self.admission.strategy_version != self.strategy_version:
            raise ValueError("strategy version and admission artifact differ")
        self.admission.require(surface)


def _config_hash(config: JavaEntryConfig) -> str:
    payload = {key: str(value) if isinstance(value, Decimal) else value for key, value in asdict(config).items()}
    return sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
