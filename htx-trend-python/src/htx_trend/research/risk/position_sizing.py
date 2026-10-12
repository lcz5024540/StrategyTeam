"""仓位计算和风险控制"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET = "min_contract_exceeds_risk_budget"
SKIP_MAX_POSITIONS = "max_positions_reached"
SKIP_TOTAL_RISK_CAP = "total_risk_cap_exceeded"


def one_contract_risk_usdt(
    entry_price: float,
    stop_loss: float,
    contract_size: float,
    min_order_size: int = 1,
) -> float:
    return abs(float(entry_price) - float(stop_loss)) * float(contract_size) * int(min_order_size)


@dataclass
class PositionSize:
    symbol: str
    side: Literal["LONG", "SHORT"]
    size: float
    leverage: float
    stop_loss: float
    risk_pct: float
    skip_reason: str | None = None
    one_contract_risk_usdt: float | None = None
    risk_budget_usdt: float | None = None


class PositionSizer:
    def __init__(
        self,
        risk_per_trade_pct: float = 0.02,
        max_total_risk_pct: float = 0.10,
        max_positions: int = 5,
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
        budget = float(account_equity) * self.risk_per_trade_pct
        one_risk = one_contract_risk_usdt(
            entry_price, stop_loss, contract_size, min_order_size
        )
        if len(existing_positions) >= self.max_positions:
            return PositionSize(
                symbol=symbol,
                side=side,
                size=0,
                leverage=self.target_leverage,
                stop_loss=stop_loss,
                risk_pct=0,
                skip_reason=SKIP_MAX_POSITIONS,
                one_contract_risk_usdt=one_risk,
                risk_budget_usdt=budget,
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
                skip_reason=SKIP_TOTAL_RISK_CAP,
                one_contract_risk_usdt=one_risk,
                risk_budget_usdt=budget,
            )
        
        stop_distance_pct = abs(entry_price - stop_loss) / entry_price
        
        risk_amount = budget
        
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
                skip_reason=SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET,
                one_contract_risk_usdt=one_risk,
                risk_budget_usdt=budget,
            )
        
        actual_notional = num_contracts * entry_price * contract_size
        actual_risk_pct = actual_notional * stop_distance_pct / account_equity
        if existing_risk + actual_risk_pct > self.max_total_risk_pct:
            return PositionSize(
                symbol=symbol,
                side=side,
                size=0,
                leverage=self.target_leverage,
                stop_loss=stop_loss,
                risk_pct=0,
                skip_reason=SKIP_TOTAL_RISK_CAP,
                one_contract_risk_usdt=one_risk,
                risk_budget_usdt=budget,
            )

        return PositionSize(
            symbol=symbol,
            side=side,
            size=num_contracts,
            leverage=self.target_leverage,
            stop_loss=stop_loss,
            risk_pct=actual_risk_pct,
            skip_reason=None,
            one_contract_risk_usdt=one_risk,
            risk_budget_usdt=budget,
        )
