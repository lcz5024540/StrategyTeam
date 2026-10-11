"""C02: ATR 初始止损条件

✅ 改进（审查报告 080d 第5项）：
1. 止损固定在入场时计算，之后只朝有利方向移动（trailing stop）
2. 止损判断使用 high/low 而非 close（intrabar check）
3. 止损压力增加滑点（默认1个tick）
"""

from __future__ import annotations

from typing import Any

from .base import Condition, ConditionResult, ConditionStage, DecisionContext


class C02ATRStop:
    id = "C02"
    version = "1.1.0"  # ✅ 版本升级（trailing stop + intrabar check）
    stage = ConditionStage.EXIT
    
    def __init__(
        self,
        atr_period: int,  # ✅ 移除默认值
        atr_multiplier: float,  # ✅ 移除默认值
        stop_slippage_ticks: int = 1,  # 止损滑点（tick数）
        tick_size: float = 0.01,  # tick大小
    ):
        self.atr_period = atr_period
        self.atr_multiplier = atr_multiplier
        self.stop_slippage_ticks = stop_slippage_ticks
        self.tick_size = tick_size
        
        # ✅ 存储每个持仓的止损（只朝有利方向移动）
        self._position_stops: dict[str, float] = {}
    
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
        
        if ctx.current_position is None:
            return ConditionResult(
                cond_id=self.id,
                status="SKIPPED",
                reason="No position",
            )
        
        entry_price = ctx.current_position.get("entry_price")
        side = ctx.current_position.get("side")
        position_id = ctx.current_position.get("position_id", ctx.symbol)
        
        if entry_price is None or side is None:
            return ConditionResult(
                cond_id=self.id,
                status="UNKNOWN",
                reason="Position missing entry_price or side",
            )
        
        # ✅ 初始化或更新 trailing stop
        if position_id not in self._position_stops:
            # 入场时计算初始止损
            atr = row[atr_col]
            stop_distance = atr * self.atr_multiplier
            
            if side == "LONG":
                initial_stop = entry_price - stop_distance
            else:
                initial_stop = entry_price + stop_distance
            
            self._position_stops[position_id] = initial_stop
        
        current_stop = self._position_stops[position_id]
        
        # ✅ 尝试更新止损（只朝有利方向移动）
        atr = row[atr_col]
        stop_distance = atr * self.atr_multiplier
        close = row["close"]
        
        if side == "LONG":
            # 多头：止损只能上移
            new_stop = close - stop_distance
            if new_stop > current_stop:
                self._position_stops[position_id] = new_stop
                current_stop = new_stop
        else:
            # 空头：止损只能下移
            new_stop = close + stop_distance
            if new_stop < current_stop:
                self._position_stops[position_id] = new_stop
                current_stop = new_stop
        
        # ✅ 止损压力：加上滑点
        slippage = self.tick_size * self.stop_slippage_ticks
        
        if side == "LONG":
            effective_stop = current_stop - slippage
        else:
            effective_stop = current_stop + slippage
        
        # ✅ 使用 high/low 判断止损（intrabar check）
        high = row["high"]
        low = row["low"]
        
        if side == "LONG":
            hit = low <= effective_stop
            extreme_price = low
        else:
            hit = high >= effective_stop
            extreme_price = high
        
        if hit:
            return ConditionResult(
                cond_id=self.id,
                status="PASS",
                value=extreme_price,
                threshold=effective_stop,
                reason=f"Stop loss hit: {side} {'low' if side == 'LONG' else 'high'}={extreme_price:.2f}, stop={effective_stop:.2f} (incl. {slippage:.4f} slippage)",
            )
        else:
            return ConditionResult(
                cond_id=self.id,
                status="FAIL",
                value=extreme_price,
                threshold=effective_stop,
                reason=f"Stop not hit: {side} {'low' if side == 'LONG' else 'high'}={extreme_price:.2f}, stop={effective_stop:.2f}",
            )
    
    def calculate_stop_loss(
        self, entry_price: float, atr: float, side: str
    ) -> float:
        """计算初始止损（入场时调用）"""
        stop_distance = atr * self.atr_multiplier
        
        if side == "LONG":
            return entry_price - stop_distance
        else:
            return entry_price + stop_distance
    
    def reset_position_stop(self, position_id: str) -> None:
        """清除持仓止损（平仓时调用）"""
        if position_id in self._position_stops:
            del self._position_stops[position_id]
