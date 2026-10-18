"""强平距离检查 - 单仓位和账户级"""

from __future__ import annotations

from typing import Any, Literal

from ..engine.contract_value import coin_notional, require_contract_size


class LiquidationChecker:
    def __init__(
        self,
        maintenance_margin_rate: float = 0.004,
        safety_buffer: float = 1.5,
    ):
        self.maintenance_margin_rate = maintenance_margin_rate
        self.safety_buffer = safety_buffer
    
    def check_liquidation_distance(
        self,
        entry_price: float,
        stop_loss: float,
        side: Literal["LONG", "SHORT"],
        leverage: float,
    ) -> tuple[bool, float, str]:
        stop_distance_pct = abs(entry_price - stop_loss) / entry_price
        
        if side == "LONG":
            liq_price = entry_price * (1 - 1 / leverage + self.maintenance_margin_rate)
            liq_distance_pct = (entry_price - liq_price) / entry_price
        else:
            liq_price = entry_price * (1 + 1 / leverage - self.maintenance_margin_rate)
            liq_distance_pct = (liq_price - entry_price) / entry_price
        
        required_distance = stop_distance_pct * self.safety_buffer
        
        if liq_distance_pct < required_distance:
            return (
                False,
                liq_distance_pct,
                f"Liquidation distance {liq_distance_pct*100:.2f}% < required {required_distance*100:.2f}%",
            )
        
        return (
            True,
            liq_distance_pct,
            f"Liquidation distance {liq_distance_pct*100:.2f}% >= required {required_distance*100:.2f}%",
        )
    
    def suggest_leverage(
        self, entry_price: float, stop_loss: float, side: Literal["LONG", "SHORT"]
    ) -> float:
        stop_distance_pct = abs(entry_price - stop_loss) / entry_price
        required_liq_distance = stop_distance_pct * self.safety_buffer
        
        suggested_lev = 1 / (required_liq_distance + self.maintenance_margin_rate)
        
        return max(1.0, min(suggested_lev, 20.0))


class AccountLiquidationChecker:
    """账户级强平检查 - HTX MAIN 账户使用全仓模式
    
    趋势策略运行在 MAIN 账户（cross margin），套利策略使用独立子账户，
    因此不需要跨策略的保证金共享逻辑。
    
    但需要考虑：
    1. 账户上可能有其他非本策略开的仓位
    2. 所有仓位同时达到止损时，账户不能被强平
    3. 需要预留安全 buffer
    """
    
    def __init__(
        self,
        maintenance_margin_rate: float = 0.004,
        safety_buffer_multiplier: float = 1.5,
    ):
        self.mmr = maintenance_margin_rate
        self.safety_buffer = safety_buffer_multiplier
    
    def check_account_liquidation_risk(
        self,
        account_equity: float,
        all_positions: list[dict[str, Any]],
        strategy_positions: list[dict[str, Any]],
    ) -> tuple[bool, float, str]:
        """检查账户级强平风险
        
        Args:
            account_equity: 账户权益（含未实现盈亏）
            all_positions: 账户所有仓位（包括非策略仓位）
            strategy_positions: 本策略管理的仓位（带止损信息）
        
        Returns:
            (is_safe, liquidation_distance_pct, message)
        """
        
        if not all_positions:
            return True, 1.0, "No positions"
        
        total_notional = 0.0
        for pos in all_positions:
            symbol = pos.get("symbol")
            if not symbol:
                raise ValueError("liquidation check position missing symbol")
            contract_size = require_contract_size(pos.get("contract_size"), symbol)
            if pos.get("entry_price") is None or pos.get("size") is None:
                raise ValueError(
                    f"liquidation check position {symbol} missing entry_price or size; "
                    "refusing to treat notional as 0"
                )
            total_notional += abs(
                coin_notional(pos["entry_price"], pos["size"], contract_size)
            )
        
        if total_notional == 0:
            return True, 1.0, "Zero notional"
        
        total_maintenance_margin = total_notional * self.mmr
        
        max_simultaneous_loss = 0.0
        
        for pos in strategy_positions:
            symbol = pos.get("symbol")
            stop_loss = pos.get("stop_loss")
            if stop_loss is None:
                raise ValueError(
                    f"strategy position {symbol} missing stop_loss; "
                    "refusing to skip it in the liquidation check"
                )
            if pos.get("entry_price") is None or pos.get("size") is None:
                raise ValueError(
                    f"strategy position {symbol} missing entry_price or size; "
                    "refusing to treat loss as 0"
                )
            side = pos.get("side")
            if side not in {"LONG", "SHORT"}:
                raise ValueError(
                    f"strategy position {symbol} missing side; refusing to default LONG"
                )
            entry_price = pos["entry_price"]
            size = pos["size"]
            contract_size = require_contract_size(pos.get("contract_size"), symbol or "")
            price_move = (entry_price - stop_loss) if side == "LONG" else (stop_loss - entry_price)
            loss = max(0.0, coin_notional(price_move, size, contract_size))
            
            max_simultaneous_loss += loss
        
        available_after_stops = account_equity - max_simultaneous_loss
        
        if available_after_stops <= total_maintenance_margin:
            return (
                False,
                available_after_stops / account_equity if account_equity > 0 else 0,
                f"Account would be liquidated if all stops hit: "
                f"equity ${account_equity:.2f} - max_loss ${max_simultaneous_loss:.2f} "
                f"= ${available_after_stops:.2f} <= MM ${total_maintenance_margin:.2f}",
            )
        
        required_buffer = total_maintenance_margin * self.safety_buffer
        
        if available_after_stops < required_buffer:
            return (
                False,
                available_after_stops / account_equity if account_equity > 0 else 0,
                f"Insufficient buffer: available ${available_after_stops:.2f} "
                f"< required ${required_buffer:.2f}",
            )
        
        liq_distance_pct = (available_after_stops - total_maintenance_margin) / account_equity
        
        return (
            True,
            liq_distance_pct,
            f"Account safe: buffer {liq_distance_pct*100:.2f}% after all stops",
        )
    
    def get_non_strategy_positions(
        self,
        all_positions: list[dict[str, Any]],
        strategy_symbols: set[str],
    ) -> list[dict[str, Any]]:
        """识别非本策略开的仓位
        
        Args:
            all_positions: 账户所有仓位
            strategy_symbols: 本策略交易的品种集合
        
        Returns:
            非策略仓位列表
        """
        
        return [
            p for p in all_positions
            if p.get("symbol") not in strategy_symbols
        ]
