"""仓位计算和风险控制"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


@dataclass
class PositionSize:
    symbol: str
    side: Literal["LONG", "SHORT"]
    size: float
    leverage: float
    stop_loss: float
    risk_pct: float
    skip_reason: str | None = None


class PositionSizer:
    def __init__(
        self,
        risk_per_trade_pct: float = 0.005,
        max_total_risk_pct: float = 0.015,
        max_positions: int = 3,
        target_leverage: float = 5.0,
    ):
        self.risk_per_trade_pct = risk_per_trade_pct
        self.max_total_risk_pct = max_total_risk_pct
        self.max_positions = max_positions
        self.target_leverage = target_leverage
    
    def calculate_position(
        self,
        symbol: str,
        side: Literal["LONG", "SHORT"],
        entry_price: float,
        stop_loss: float,
        account_equity: float,
        contract_size: float,
        min_order_size: int,
        existing_positions: list[dict],
    ) -> PositionSize:
        if len(existing_positions) >= self.max_positions:
            return PositionSize(
                symbol=symbol,
                side=side,
                size=0,
                leverage=self.target_leverage,
                stop_loss=stop_loss,
                risk_pct=0,
                skip_reason=f"Max positions reached ({self.max_positions})",
            )
        
        existing_risk = 0.0
        for pos in existing_positions:
            if "risk_amount" not in pos or pos["risk_amount"] is None:
                raise ValueError(
                    "existing position missing risk_amount; refusing to default risk to 0"
                )
            existing_risk += float(pos["risk_amount"]) / account_equity
        
        if existing_risk >= self.max_total_risk_pct:
            return PositionSize(
                symbol=symbol,
                side=side,
                size=0,
                leverage=self.target_leverage,
                stop_loss=stop_loss,
                risk_pct=0,
                skip_reason=f"Total risk limit reached ({existing_risk*100:.2f}%)",
            )
        
        stop_distance_pct = abs(entry_price - stop_loss) / entry_price
        
        risk_amount = account_equity * self.risk_per_trade_pct
        
        notional_value = risk_amount / stop_distance_pct
        
        num_contracts = notional_value / (entry_price * contract_size)
        num_contracts = int(num_contracts)
        
        if num_contracts < min_order_size:
            return PositionSize(
                symbol=symbol,
                side=side,
                size=0,
                leverage=self.target_leverage,
                stop_loss=stop_loss,
                risk_pct=0,
                skip_reason=f"Min order size {min_order_size} exceeds risk budget",
            )
        
        actual_notional = num_contracts * entry_price * contract_size
        actual_risk_pct = actual_notional * stop_distance_pct / account_equity
        
        return PositionSize(
            symbol=symbol,
            side=side,
            size=num_contracts,
            leverage=self.target_leverage,
            stop_loss=stop_loss,
            risk_pct=actual_risk_pct,
            skip_reason=None,
        )
