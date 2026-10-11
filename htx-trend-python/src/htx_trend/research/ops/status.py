"""只读状态接口 - 用于运维监控

状态持久化策略：
- 进程通过 update_status() 调用时，原子化写入磁盘 status.json (tempfile + os.replace)
- CLI `status --json` 和 HTTP endpoint 从磁盘读取 status.json
- 支持跨进程状态共享
"""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
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


# ✅ 状态文件路径（可配置）
_status_file_path: Path = Path("/tmp/htx_trend_status.json")


def set_status_file_path(path: Path | str) -> None:
    """设置状态文件路径（用于测试或自定义部署）"""
    global _status_file_path
    _status_file_path = Path(path)


def get_status_file_path() -> Path:
    """获取当前状态文件路径"""
    return _status_file_path


def update_status(status: StatusInterface) -> None:
    """更新状态并原子化写入磁盘
    
    使用 tempfile + os.replace 保证原子性：
    - 先写入临时文件（同一文件系统）
    - 再原子 rename 覆盖目标文件
    - 其他进程读取时要么看到旧版本，要么看到新版本（不会读到半写状态）
    """
    status_dict = status.to_dict()
    
    # 1. 写入临时文件（与目标文件同目录，保证同文件系统）
    target_path = get_status_file_path()
    target_path.parent.mkdir(parents=True, exist_ok=True)
    
    fd, temp_path = tempfile.mkstemp(
        dir=target_path.parent,
        prefix=".status_",
        suffix=".json.tmp"
    )
    
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(status_dict, f, indent=2)
        
        # 2. 原子 rename（同文件系统内是原子操作）
        os.replace(temp_path, target_path)
    except Exception:
        # 清理临时文件
        try:
            os.unlink(temp_path)
        except OSError:
            pass
        raise


def get_status() -> StatusInterface | None:
    """从磁盘读取状态（跨进程安全）
    
    返回 None 如果状态文件不存在或解析失败
    """
    status_path = get_status_file_path()
    
    if not status_path.exists():
        return None
    
    try:
        with open(status_path, "r") as f:
            data = json.load(f)
        
        # 重建 StatusInterface 对象
        return _deserialize_status(data)
    except Exception:
        # 文件损坏或格式错误
        return None


def _deserialize_status(data: dict[str, Any]) -> StatusInterface:
    """从字典重建 StatusInterface 对象"""
    circuit_breaker_data = data.get("circuit_breaker", {})
    circuit_breaker = CircuitBreakerState(
        active=circuit_breaker_data.get("active", False),
        reason=circuit_breaker_data.get("reason"),
        triggered_at=(
            datetime.fromisoformat(circuit_breaker_data["triggered_at"])
            if circuit_breaker_data.get("triggered_at")
            else None
        ),
        daily_loss=circuit_breaker_data.get("daily_loss", 0.0),
        consecutive_losses=circuit_breaker_data.get("consecutive_losses", 0),
        drawdown_pct=circuit_breaker_data.get("drawdown_pct", 0.0),
    )
    
    positions = [
        PositionStatus(
            symbol=p["symbol"],
            side=p["side"],
            size=p["size"],
            entry_price=p["entry_price"],
            current_price=p["current_price"],
            unrealized_pnl=p["unrealized_pnl"],
            stop_loss=p.get("stop_loss"),
            is_strategy_position=p.get("is_strategy_position", True),
        )
        for p in data.get("positions", [])
    ]
    
    approvals = {
        gate: ApprovalStatus(
            gate=approval_data["gate"],
            decision=approval_data["decision"],
            approver=approval_data.get("approver"),
            timestamp=(
                datetime.fromisoformat(approval_data["timestamp"])
                if approval_data.get("timestamp")
                else None
            ),
            rationale=approval_data.get("rationale"),
        )
        for gate, approval_data in data.get("approvals", {}).items()
    }
    
    return StatusInterface(
        run_id=data["run_id"],
        component=data["component"],
        timestamp=datetime.fromisoformat(data["timestamp"]),
        health=HealthStatus(data["health"]),
        positions=positions,
        total_pnl=data.get("total_pnl", 0.0),
        account_equity=data.get("account_equity", 0.0),
        account_liquidation_distance_pct=data.get("account_liquidation_distance_pct"),
        circuit_breaker=circuit_breaker,
        recent_decisions=data.get("recent_decisions", []),
        approvals=approvals,
        fee_rates=data.get("fee_rates"),
        message=data.get("message"),
    )
