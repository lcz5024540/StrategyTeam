"""Observational entry diagnostics; never used to authorize trading."""
from collections import Counter
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class RejectCode(StrEnum):
    SCAN_FAILED = "SCAN_FAILED"
    EVIDENCE_UNAVAILABLE = "EVIDENCE_UNAVAILABLE"
    INSUFFICIENT_BARS = "INSUFFICIENT_BARS"
    NO_DIRECTION_SIGNAL = "NO_DIRECTION_SIGNAL"
    TIMEFRAME_UNSUPPORTED = "TIMEFRAME_UNSUPPORTED"
    REGIME_NOT_TRENDING = "REGIME_NOT_TRENDING"
    NO_STRUCTURE = "NO_STRUCTURE"
    NO_BOX_BREAKOUT = "NO_BOX_BREAKOUT"
    NO_DONCHIAN_BREAKOUT = "NO_DONCHIAN_BREAKOUT"
    NO_FIB_RETEST = "NO_FIB_RETEST"
    EMA_MOMENTUM_CONFLICT = "EMA_MOMENTUM_CONFLICT"
    ADX_BELOW_20 = "ADX_BELOW_20"
    NO_ENABLED_POLICY = "NO_ENABLED_POLICY"
    POLICY_NOT_MATCHED = "POLICY_NOT_MATCHED"
    POLICY_SCORE_BELOW_THRESHOLD = "POLICY_SCORE_BELOW_THRESHOLD"
    EXPIRED = "EXPIRED"
    OUT_OF_WINDOW = "OUT_OF_WINDOW"
    CAPACITY_FULL = "CAPACITY_FULL"
    OBSERVE_ONLY_DISCARDED = "OBSERVE_ONLY_DISCARDED"
    ENTRY_DISABLED = "ENTRY_DISABLED"


@dataclass(frozen=True, slots=True)
class CandidateDiagnosis:
    stage: str | None = None
    code: str | None = None
    candidate_count: int = 0
    flags: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class PolicyDiagnosis:
    model: str
    confirmations: str
    code: str | None
    score: Any = None
    thresholds: tuple[Any, ...] = ()


def heartbeat_detail(prefix: str, *, period: str, raw_candidates: int,
                     policy_selected: int, rejections: dict[str, str]) -> str:
    """Preserve the operational prefix; bound the appended rejection suffix."""
    detail = (f"{prefix} period={period} rawCandidates={raw_candidates} "
              f"policySelected={policy_selected}")
    rejects = ",".join(f"{contract}:{code}" for contract, code in sorted(rejections.items()))
    if len(rejections) > 8 or len(detail) + len(rejects) + 5 > 1024:
        counts = Counter(rejections.values())
        detail += " rejCounts=" + ",".join(f"{code}:{count}" for code, count in sorted(counts.items()))
    else:
        detail += " rej=" + (rejects or "NONE")
    return detail[:1024]
