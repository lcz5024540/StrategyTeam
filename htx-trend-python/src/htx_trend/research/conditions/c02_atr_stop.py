"""C02: ATR 初始止损 + 只朝有利方向移动的跟踪止损。

无前视：bar t+1 的触发使用截至 t 收盘已确定的止损，对照 t+1 的 high/low。
触发价不加滑点；滑点只不利地作用于成交价（由 runner/broker 处理）。
止损状态按 position_id 键控，入场时创建、平仓时 reset。
"""

from __future__ import annotations

from typing import Any

from .base import ConditionResult, ConditionStage, DecisionContext


class C02ATRStop:
    id = "C02"
    version = "1.2.0"
    stage = ConditionStage.EXIT

    def __init__(
        self,
        atr_period: int,
        atr_multiplier: float,
        stop_slippage_ticks: int,
        tick_size: float,
    ):
        if atr_period is None:
            raise ValueError("C02: 'atr_period' must be provided")
        if atr_multiplier is None:
            raise ValueError("C02: 'atr_multiplier' must be provided")
        if stop_slippage_ticks is None:
            raise ValueError("C02: 'stop_slippage_ticks' must be provided")
        if tick_size is None:
            raise ValueError("C02: 'tick_size' must be provided")
        self.atr_period = atr_period
        self.atr_multiplier = atr_multiplier
        self.stop_slippage_ticks = stop_slippage_ticks
        self.tick_size = tick_size
        self._position_stops: dict[str, float] = {}

    def initialize_position_stop(self, position_id: str, stop_loss: float) -> None:
        """Create stop state at entry from the sized Position.stop_loss."""
        self._position_stops[position_id] = stop_loss

    def reset_position_stop(self, position_id: str) -> None:
        self._position_stops.pop(position_id, None)

    def slippage_amount(self) -> float:
        return self.tick_size * self.stop_slippage_ticks

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
        position_id = ctx.current_position.get("position_id")
        if not position_id:
            raise ValueError("C02 requires current_position['position_id']")
        if entry_price is None or side is None:
            return ConditionResult(
                cond_id=self.id,
                status="UNKNOWN",
                reason="Position missing entry_price or side",
            )

        if position_id not in self._position_stops:
            seeded = ctx.current_position.get("stop_loss")
            if seeded is None:
                seeded = self.calculate_stop_loss(entry_price, row[atr_col], side)
            self._position_stops[position_id] = float(seeded)

        current_stop = self._position_stops[position_id]
        high = row["high"]
        low = row["low"]

        if side == "LONG":
            hit = low <= current_stop
            extreme_price = low
        else:
            hit = high >= current_stop
            extreme_price = high

        if hit:
            return ConditionResult(
                cond_id=self.id,
                status="PASS",
                value=extreme_price,
                threshold=current_stop,
                reason=(
                    f"Stop loss hit: {side} "
                    f"{'low' if side == 'LONG' else 'high'}={extreme_price:.2f}, "
                    f"stop={current_stop:.2f} (trigger, no slippage)"
                ),
            )

        atr = row[atr_col]
        stop_distance = atr * self.atr_multiplier
        close = row["close"]
        if side == "LONG":
            new_stop = close - stop_distance
            if new_stop > current_stop:
                self._position_stops[position_id] = new_stop
                current_stop = new_stop
        else:
            new_stop = close + stop_distance
            if new_stop < current_stop:
                self._position_stops[position_id] = new_stop
                current_stop = new_stop

        return ConditionResult(
            cond_id=self.id,
            status="FAIL",
            value=extreme_price,
            threshold=current_stop,
            reason=(
                f"Stop not hit: {side} "
                f"{'low' if side == 'LONG' else 'high'}={extreme_price:.2f}, "
                f"stop={current_stop:.2f}"
            ),
        )

    def calculate_stop_loss(
        self, entry_price: float, atr: float, side: str
    ) -> float:
        stop_distance = atr * self.atr_multiplier
        if side == "LONG":
            return entry_price - stop_distance
        return entry_price + stop_distance
