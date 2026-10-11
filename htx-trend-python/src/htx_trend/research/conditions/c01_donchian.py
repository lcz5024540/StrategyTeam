"""C01: Donchian 突破集成条件"""

from __future__ import annotations

from typing import Any

from .base import Condition, ConditionResult, ConditionStage, DecisionContext


class C01DonchianEnsemble:
    id = "C01"
    version = "1.0.0"
    stage = ConditionStage.ENTRY
    
    def __init__(self, periods: list[int] | None = None):
        self.periods = periods or [42, 84, 126, 168]
    
    def evaluate(
        self, ctx: DecisionContext, params: dict[str, Any]
    ) -> ConditionResult:
        row = ctx.get_current_row()
        close = row["close"]
        
        signals = []
        
        for period in self.periods:
            high_col = f"donchian_high_{period}"
            low_col = f"donchian_low_{period}"
            
            if high_col not in row or low_col not in row:
                continue
            
            donchian_high = row[high_col]
            donchian_low = row[low_col]
            
            if pd.isna(donchian_high) or pd.isna(donchian_low):
                continue
            
            if close >= donchian_high:
                signals.append(1)
            elif close <= donchian_low:
                signals.append(-1)
            else:
                signals.append(0)
        
        if not signals:
            return ConditionResult(
                cond_id=self.id,
                status="UNKNOWN",
                reason="No Donchian channels available",
            )
        
        avg_signal = sum(signals) / len(signals)
        
        if avg_signal > 0.25:
            status = "PASS"
            side = "LONG"
        elif avg_signal < -0.25:
            status = "PASS"
            side = "SHORT"
        else:
            status = "FAIL"
            side = None
        
        return ConditionResult(
            cond_id=self.id,
            status=status,
            value=avg_signal,
            threshold=0.25,
            reason=f"Ensemble signal: {avg_signal:.2f}, side: {side}" if status == "PASS" else "No clear breakout",
        )


import pandas as pd
