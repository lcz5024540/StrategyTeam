"""Model-specific holding evidence derived from a frozen v5 entry thesis."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from .exit_contract_v5 import EntryThesisV5, ModelState, Side

SUPPORTED_MODELS = frozenset({
    "FIB_PULLBACK_CONTINUATION",
    "DONCHIAN_BREAKOUT",
    "EMA_VWAP_MOMENTUM_CONTINUATION",
})


@dataclass(frozen=True, slots=True)
class ModelHoldingEvidenceV5:
    state: ModelState
    evidence_groups: tuple[str, ...]
    reason_codes: tuple[str, ...]


def evaluate_model_holding_v5(contract: EntryThesisV5, close_price: Decimal | None,
                              *, source_complete: bool = True) -> ModelHoldingEvidenceV5:
    """Evaluate one eligible closed bar; confirmation remains the kernel's job."""
    if contract.model_id not in SUPPORTED_MODELS:
        return ModelHoldingEvidenceV5(ModelState.UNKNOWN, (), ("UNSUPPORTED_MODEL",))
    if not source_complete or close_price is None or close_price <= 0:
        return ModelHoldingEvidenceV5(ModelState.UNKNOWN, (), ("MODEL_SOURCE_INCOMPLETE",))
    if contract.failure_zone is None or contract.failure_zone == contract.immutable_initial_stop:
        return ModelHoldingEvidenceV5(ModelState.UNKNOWN, (), ("SOFT_FAILURE_ZONE_DISABLED",))
    failed = close_price <= contract.failure_zone if contract.side is Side.LONG else close_price >= contract.failure_zone
    if failed:
        return ModelHoldingEvidenceV5(ModelState.INVALID, ("E-S",),
                                      (f"{contract.model_id}_FAILURE_ZONE_CROSSED",))
    return ModelHoldingEvidenceV5(ModelState.VALID, (), (f"{contract.model_id}_THESIS_VALID",))
