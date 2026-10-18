"""C01: Donchian 突破集成条件

所有参数必须从 YAML 显式传递，无隐藏默认值。
突破方向和每个窗口的投票结果记录在 evidence 中。
"""

from __future__ import annotations

from typing import Any, Literal

import pandas as pd

from .base import ConditionResult, ConditionStage, DecisionContext


class C01DonchianEnsemble:
    id = "C01"
    version = "1.1.0"  # 更新版本：显式参数 + 投票记录
    stage = ConditionStage.ENTRY
    
    def __init__(
        self,
        periods: list[int],
        threshold: float,
    ):
        """
        Args:
            periods: Donchian 周期列表（必须显式传递）
            threshold: 集成投票阈值（必须显式传递）
        
        Raises:
            ValueError: 如果参数缺失或无效
        """
        if not periods:
            raise ValueError("C01: 'periods' must be provided and non-empty")
        
        if threshold is None:
            raise ValueError("C01: 'threshold' must be provided")
        
        if not (0 <= threshold <= 1):
            raise ValueError(f"C01: 'threshold' must be in [0, 1], got {threshold}")
        
        self.periods = periods
        self.threshold = threshold
    
    def evaluate(
        self, ctx: DecisionContext, params: dict[str, Any]
    ) -> ConditionResult:
        row = ctx.get_current_row()
        close = row["close"]
        
        # 每个窗口的投票：1 (LONG), -1 (SHORT), 0 (neutral)
        votes: dict[int, int] = {}
        
        for period in self.periods:
            high_col = f"donchian_high_{period}"
            low_col = f"donchian_low_{period}"
            
            if high_col not in row or low_col not in row:
                votes[period] = 0
                continue
            
            donchian_high = row[high_col]
            donchian_low = row[low_col]
            
            if pd.isna(donchian_high) or pd.isna(donchian_low):
                votes[period] = 0
                continue
            
            # 突破检测：
            # LONG: close > prior N bars max(high)
            # SHORT: close < prior N bars min(low)
            if close > donchian_high:
                votes[period] = 1
            elif close < donchian_low:
                votes[period] = -1
            else:
                votes[period] = 0
        
        if not votes:
            return ConditionResult(
                cond_id=self.id,
                status="UNKNOWN",
                reason="No Donchian channels available",
                evidence={"votes": {}, "direction": None},
            )
        
        # 集成信号：所有投票的平均值
        vote_values = list(votes.values())
        avg_signal = sum(vote_values) / len(vote_values)
        
        # 判断方向和是否通过
        direction: Literal["LONG", "SHORT"] | None = None
        
        if avg_signal > self.threshold:
            status = "PASS"
            direction = "LONG"
        elif avg_signal < -self.threshold:
            status = "PASS"
            direction = "SHORT"
        else:
            status = "FAIL"
            direction = None
        
        # 证据：记录每个窗口的投票和最终方向
        evidence = {
            "votes": votes,  # {42: 1, 84: 1, 126: 0, 168: 1}
            "direction": direction,
            "avg_signal": round(avg_signal, 4),
            "threshold": self.threshold,
            "close": float(close),
        }
        
        reason = (
            f"Donchian ensemble: {avg_signal:.3f} ({'>' if avg_signal > 0 else '<'} {self.threshold}), "
            f"direction={direction}, votes={votes}"
            if status == "PASS"
            else f"No clear breakout: avg_signal={avg_signal:.3f}, threshold={self.threshold}"
        )
        
        return ConditionResult(
            cond_id=self.id,
            status=status,
            value=avg_signal,
            threshold=self.threshold,
            reason=reason,
            evidence=evidence,
        )
