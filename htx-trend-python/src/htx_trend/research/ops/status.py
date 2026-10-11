"""只读状态接口 - 用于运维监控"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class HealthStatus(str, Enum):
    HEALTHY = "healthy"
    DEGRADED = "degraded"
    ERROR = "error"
    UNKNOWN = "unknown"


@dataclass
class PositionStatus:
    symbol: str
    side: str
    size: float
    entry_price: float
    current_price: float
    unrealized_pnl: float
    stop_loss: float | None = None
    is_strategy_position: bool = True


@dataclass
class CircuitBreakerState:
    active: bool
    reason: str | None = None
    triggered_at: datetime | None = None
    daily_loss: float = 0.0
    consecutive_losses: int = 0
    drawdown_pct: float = 0.0


@dataclass
class ApprovalStatus:
    gate: str
    decision: str
    approver: str | None = None
    timestamp: datetime | None = None
    rationale: str | None = None


@dataclass
class StatusInterface:
    run_id: str
    component: str
    timestamp: datetime
    health: HealthStatus
    
    positions: list[PositionStatus] = field(default_factory=list)
    total_pnl: float = 0.0
    account_equity: float = 0.0
    account_liquidation_distance_pct: float | None = None
    
    circuit_breaker: CircuitBreakerState = field(
        default_factory=lambda: CircuitBreakerState(active=False)
    )
    
    recent_decisions: list[dict[str, Any]] = field(default_factory=list)
    
    approvals: dict[str, ApprovalStatus] = field(default_factory=dict)
    
    fee_rates: dict[str, Any] | None = None
    
    message: str | None = None
    
    def to_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "component": self.component,
            "timestamp": self.timestamp.isoformat(),
            "health": self.health.value,
            "positions": [
                {
                    "symbol": p.symbol,
                    "side": p.side,
                    "size": p.size,
                    "entry_price": p.entry_price,
                    "current_price": p.current_price,
                    "unrealized_pnl": p.unrealized_pnl,
                    "stop_loss": p.stop_loss,
                    "is_strategy_position": p.is_strategy_position,
                }
                for p in self.positions
            ],
            "total_pnl": self.total_pnl,
            "account_equity": self.account_equity,
            "account_liquidation_distance_pct": self.account_liquidation_distance_pct,
            "circuit_breaker": {
                "active": self.circuit_breaker.active,
                "reason": self.circuit_breaker.reason,
                "triggered_at": (
                    self.circuit_breaker.triggered_at.isoformat()
                    if self.circuit_breaker.triggered_at
                    else None
                ),
                "daily_loss": self.circuit_breaker.daily_loss,
                "consecutive_losses": self.circuit_breaker.consecutive_losses,
                "drawdown_pct": self.circuit_breaker.drawdown_pct,
            },
            "recent_decisions": self.recent_decisions,
            "approvals": {
                gate: {
                    "gate": approval.gate,
                    "decision": approval.decision,
                    "approver": approval.approver,
                    "timestamp": approval.timestamp.isoformat() if approval.timestamp else None,
                    "rationale": approval.rationale,
                }
                for gate, approval in self.approvals.items()
            },
            "fee_rates": self.fee_rates,
            "message": self.message,
        }


_current_status: StatusInterface | None = None


def update_status(status: StatusInterface) -> None:
    global _current_status
    _current_status = status


def get_status() -> StatusInterface | None:
    return _current_status
