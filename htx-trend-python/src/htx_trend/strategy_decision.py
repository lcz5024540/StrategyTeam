"""One HTX entry decision core shared by research, replay and runtimes."""
from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal
from hashlib import sha256
from typing import Sequence
import json

from .diagnostics import CandidateDiagnosis
from .java_entry_contract import JavaEntryConfig, JavaTrendSignal, candidates, evaluate, explain_candidates
from .market_data import Candle
from .models import ModelCandidate
from .strategy_admission import DecisionSurface, StrategyAdmissionArtifact
from .strategy_profiles import profile_for_version, profile_strategy
from .timeframes import TimeframeProfile

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
    diagnosis: CandidateDiagnosis | None = None
    timeframe_profile: str | None = None
    strategy_spec_hash: str | None = None
    feature_hash: str | None = None
    condition_hash: str | None = None


class HtxStrategyDecisionEngine:
    """Deterministic HTX decision boundary.

    The grandfathered V1 contract keeps the current production behavior. Any
    newly named strategy version must present an admission artifact for the
    requested surface.
    """
    def __init__(self, *, strategy_version: str = LEGACY_STRATEGY_VERSION,
                 config: JavaEntryConfig | None = None,
                 admission: StrategyAdmissionArtifact | None = None, evidence_store=None) -> None:
        profile = profile_for_version(strategy_version)
        self.profile_spec = profile_strategy(profile, strategy_version=strategy_version) if profile is not None else None
        self.strategy_version = strategy_version
        self.config = config or (self.profile_spec.entry_config if self.profile_spec else JavaEntryConfig())
        self.admission = admission
        self.evidence_store = evidence_store

    def decide(self, *, contract_code: str, period: str, candles: Sequence[Candle],
               surface: DecisionSurface | str, evidence_refs: tuple[str, ...] = ()) -> StrategyDecision:
        from .realtime import VERSION as REALTIME_VERSION
        if self.strategy_version == REALTIME_VERSION:
            raise ValueError("realtime strategy requires StructurePlan/RealtimeTrigger, not candle candidates")
        self._authorize(DecisionSurface(surface))
        period_profile = TimeframeProfile.for_period(period)
        if self.profile_spec is not None and period_profile is not self.profile_spec.timeframe_profile:
            raise ValueError(
                f"strategy {self.strategy_version} cannot evaluate {period_profile.value}"
            )
        ordered = tuple(sorted(candles, key=lambda item: item.open_time))
        signal = evaluate(ordered, self.config)
        output = candidates(ordered, signal, self.config, period)
        input_hash = sha256("\n".join(item.content_hash for item in ordered).encode()).hexdigest()
        config_hash = _config_hash(self.config)
        payload = {"strategy_version": self.strategy_version, "contract_code": contract_code, "period": period,
                   "input_hash": input_hash, "config_hash": config_hash, "bar_time": signal.bar_time,
                   "side": signal.side.value, "candidate_models": [item.model.value for item in output]}
        if self.profile_spec is not None:
            payload.update({"timeframe_profile": period_profile.value,
                            "strategy_spec_hash": self.profile_spec.spec_hash,
                            "feature_hash": self.profile_spec.feature_hash,
                            "condition_hash": self.profile_spec.condition_hash})
        decision_hash = sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        trace_ref = None
        if self.evidence_store is not None:
            from .market_data import period_seconds
            from .research.baseline import trace_entry
            from .research.contracts import DecisionContext
            evaluated = datetime.fromtimestamp(
                (ordered[-1].open_time + period_seconds(period)) if ordered else 0, UTC).isoformat()
            context = DecisionContext(decision_hash, self.strategy_version, DecisionSurface(surface).value,
                evaluated, dict(payload),
                {}, {}, evidence_refs)
            trace = trace_entry(context, ordered, period, signal, output, self.config,
                                strategy=self.profile_spec.strategy if self.profile_spec else None)
            if self.profile_spec is not None:
                trace = replace(trace, strategy_spec_hash=self.profile_spec.spec_hash)
            trace_ref = self.evidence_store.put("decision_trace", trace, refs=evidence_refs)
        return StrategyDecision(self.strategy_version, contract_code, period, signal, output,
                                input_hash, config_hash, decision_hash, trace_ref,
                                explain_candidates(ordered, signal, self.config, period),
                                period_profile.value,
                                self.profile_spec.spec_hash if self.profile_spec else None,
                                self.profile_spec.feature_hash if self.profile_spec else None,
                                self.profile_spec.condition_hash if self.profile_spec else None)

    def require(self, surface: DecisionSurface | str) -> None:
        self._authorize(DecisionSurface(surface))

    def _authorize(self, surface: DecisionSurface) -> None:
        if surface is DecisionSurface.PAPER and (self.profile_spec is not None or self.strategy_version == LEGACY_STRATEGY_VERSION):
            return  # PAPER simulation does not confer LIVE admission.
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
