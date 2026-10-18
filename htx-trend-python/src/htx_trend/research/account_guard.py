"""账户守卫 - 确保只在配置允许的账户上交易

HTX MAIN 账户（cross margin）是唯一允许的交易账户。
其他子账户必须被拒绝以防止误操作。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True)
class AccountConfig:
    """账户配置"""
    account_id: str
    account_type: Literal["MAIN", "SUB"] = "MAIN"
    margin_mode: Literal["cross", "isolated"] = "cross"
    
    def validate(self) -> None:
        """验证账户配置"""
        if self.account_type != "MAIN":
            raise ValueError(
                f"Only MAIN account is allowed for trading. "
                f"Got: {self.account_type}"
            )
        
        if self.margin_mode != "cross":
            raise ValueError(
                f"Only cross margin mode is allowed for MAIN account. "
                f"Got: {self.margin_mode}"
            )


class AccountGuard:
    """账户守卫 - 在下单前验证账户"""
    
    def __init__(self, allowed_account: AccountConfig):
        """
        Args:
            allowed_account: 唯一允许的交易账户配置
        """
        allowed_account.validate()
        self.allowed_account = allowed_account
    
    def check_order_permission(
        self, account_id: str, operation: str = "place_order"
    ) -> tuple[bool, str]:
        """检查是否允许在指定账户上执行订单操作
        
        Args:
            account_id: 要检查的账户 ID
            operation: 操作类型（用于日志）
        
        Returns:
            (is_allowed, reason)
        """
        if account_id != self.allowed_account.account_id:
            return (
                False,
                f"Order {operation} rejected: account '{account_id}' is not the "
                f"configured allowed account '{self.allowed_account.account_id}'. "
                f"Only HTX MAIN account (cross margin) is permitted for trading."
            )
        
        return (True, f"Account '{account_id}' is authorized for {operation}")
    
    def require_permission(self, account_id: str, operation: str = "place_order") -> None:
        """要求账户权限，否则抛出异常
        
        Raises:
            PermissionError: 如果账户未被授权
        """
        allowed, reason = self.check_order_permission(account_id, operation)
        
        if not allowed:
            raise PermissionError(reason)


def create_main_account_guard(account_id: str) -> AccountGuard:
    """创建 MAIN 账户守卫的便捷函数
    
    Args:
        account_id: HTX MAIN 账户 ID
    
    Returns:
        配置好的 AccountGuard
    """
    config = AccountConfig(
        account_id=account_id,
        account_type="MAIN",
        margin_mode="cross",
    )
    
    return AccountGuard(config)
