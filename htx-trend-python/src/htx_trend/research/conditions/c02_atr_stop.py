"""C02: ATR 初始止损条件"""

from __future__ import annotations

from typing import Any

from .base import Condition, ConditionResult, ConditionStage, DecisionContext


class C02ATRStop:
    id = "C02"
    version = "1.0.0"
    stage = ConditionStage.EXIT
    
    def __init__(self, atr_period: int = 14, atr_multiplier: float = 2.0):
        self.atr_period = atr_period
        self.atr_multiplier = atr_multiplier
    
    def evaluate(
        self, ctx: DecisionContext, params: dict[str, Any]
    ) -> ConditionResult:
        row = ctx.get_current_row()
        
        atr_col = f"atr{self.atr_period}"
        if atr_col not in row:
            return ConditionResult(
                cond_id=self.id,
                status="UNKNOWN",
                reason=f"ATR{self.atr_period} not available",
            )
        
        atr = row[atr_col]
        close = row["close"]
        
        if ctx.current_position is None:
            return ConditionResult(
                cond_id=self.id,
                status="SKIPPED",
                reason="No position",
            )
        
        entry_price = ctx.current_position.get("entry_price")
        side = ctx.current_position.get("side")
        
        if entry_price is None or side is None:
            return ConditionResult(
                cond_id=self.id,
                status="UNKNOWN",
                reason="Position missing entry_price or side",
            )
        
        stop_distance = atr * self.atr_multiplier
        
        if side == "LONG":
            stop_loss = entry_price - stop_distance
            hit = close <= stop_loss
        else:
            stop_loss = entry_price + stop_distance
            hit = close >= stop_loss
        
        if hit:
            return ConditionResult(
                cond_id=self.id,
                status="PASS",
                value=close,
                threshold=stop_loss,
                reason=f"Stop loss hit: close={close:.2f}, stop={stop_loss:.2f}",
            )
        else:
            return ConditionResult(
                cond_id=self.id,
                status="FAIL",
                value=close,
                threshold=stop_loss,
                reason=f"Stop not hit: close={close:.2f}, stop={stop_loss:.2f}",
            )
    
    def calculate_stop_loss(
        self, entry_price: float, atr: float, side: str
    ) -> float:
        stop_distance = atr * self.atr_multiplier
        
        if side == "LONG":
            return entry_price - stop_distance
        else:
            return entry_price + stop_distance
