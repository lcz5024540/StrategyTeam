"""条件基础接口和数据结构"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Literal, Protocol

import pandas as pd


class ConditionStage(str, Enum):
    ENTRY = "ENTRY"
    FILTER = "FILTER"
    SIZING = "SIZING"
    EXIT = "EXIT"


@dataclass(frozen=True)
class ConditionResult:
    cond_id: str
    status: Literal["PASS", "FAIL", "UNKNOWN", "SKIPPED"]
    value: float | None = None
    threshold: float | None = None
    reason: str = ""
    
    evidence: dict[str, Any] | None = None
    
    def to_dict(self) -> dict[str, Any]:
        result = {
            "cond_id": self.cond_id,
            "status": self.status,
            "value": self.value,
            "threshold": self.threshold,
            "reason": self.reason,
        }
        if self.evidence:
            result["evidence"] = self.evidence
        return result


@dataclass
class DecisionContext:
    symbol: str
    bar_time: pd.Timestamp
    df: pd.DataFrame
    current_idx: int
    current_position: dict[str, Any] | None = None
    
    def get_current_row(self) -> pd.Series:
        return self.df.iloc[self.current_idx]
    
    def get_history(self, lookback: int) -> pd.DataFrame:
        start_idx = max(0, self.current_idx - lookback + 1)
        return self.df.iloc[start_idx : self.current_idx + 1]


class Condition(Protocol):
    id: str
    version: str
    stage: ConditionStage
    
    def evaluate(
        self, ctx: DecisionContext, params: dict[str, Any]
    ) -> ConditionResult:
        ...
