"""仓位守卫 - 检查非策略持仓并阻止开仓"""

from __future__ import annotations

from ..ops import log_event, LogComponent, LogLevel
from .liquidation_check import AccountLiquidationChecker


class PositionGuard:
    """仓位守卫 - 防止在存在非策略持仓时开新仓"""
    
    def __init__(self):
        self.liq_checker = AccountLiquidationChecker()
    
    def check_non_strategy_positions(
        self,
        all_positions: list[dict],
        strategy_symbols: list[str],
    ) -> tuple[bool, list[dict], str]:
        """检查是否存在非策略持仓
        
        Args:
            all_positions: 账户所有持仓
            strategy_symbols: 策略管理的交易对列表
        
        Returns:
            (is_safe, non_strategy_positions, reason)
            - is_safe: True 表示可以开仓，False 表示应阻止
            - non_strategy_positions: 非策略持仓列表
            - reason: 原因说明
        """
        non_strategy = self.liq_checker.get_non_strategy_positions(
            all_positions, strategy_symbols
        )
        
        if not non_strategy:
            return (True, [], "No non-strategy positions")
        
        # 有非策略持仓，阻止开新仓
        log_event(
            LogComponent.RISK,
            "Non-strategy positions detected, blocking new entries",
            level=LogLevel.WARNING,
            non_strategy_count=len(non_strategy),
            non_strategy_symbols=[p["symbol"] for p in non_strategy],
            total_positions=len(all_positions),
        )
        
        reason = (
            f"Detected {len(non_strategy)} non-strategy position(s): "
            f"{', '.join(p['symbol'] for p in non_strategy)}. "
            f"New entries blocked until these positions are closed."
        )
        
        return (False, non_strategy, reason)
    
    def should_allow_entry(
        self,
        all_positions: list[dict],
        strategy_symbols: list[str],
    ) -> tuple[bool, str]:
        """判断是否允许开仓
        
        Args:
            all_positions: 账户所有持仓
            strategy_symbols: 策略管理的交易对列表
        
        Returns:
            (allowed, reason)
        """
        is_safe, non_strategy, reason = self.check_non_strategy_positions(
            all_positions, strategy_symbols
        )
        
        if not is_safe:
            return (False, reason)
        
        return (True, "Entry allowed: no non-strategy positions")
