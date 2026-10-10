"""One HTX entry decision core shared by research, replay and runtimes."""
from __future__ import annotations

from dataclasses import asdict, dataclass
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


class HtxStrategyDecisionEngine:
    """Deterministic HTX decision boundary.

    The grandfathered V1 contract keeps the current production behavior. Any
    newly named strategy version must present an admission artifact for the
    requested surface.
    """
    def __init__(self, *, strategy_version: str = LEGACY_STRATEGY_VERSION,
                 config: JavaEntryConfig = JavaEntryConfig(),
                 admission: StrategyAdmissionArtifact | None = None) -> None:
        self.strategy_version, self.config, self.admission = strategy_version, config, admission

    def decide(self, *, contract_code: str, period: str, candles: Sequence[Candle],
               surface: DecisionSurface | str) -> StrategyDecision:
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
        return StrategyDecision(self.strategy_version, contract_code, period, signal, output,
                                input_hash, config_hash, decision_hash)

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
