"""HTX 费率提供者接口和实现"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from ....htx_v5 import HtxV5Client


@dataclass(frozen=True)
class FeeRates:
    maker_rate: float
    taker_rate: float
    source: str
    account_level: str | None = None
    timestamp: str | None = None
    
    def to_dict(self) -> dict:
        return {
            "maker_rate": self.maker_rate,
            "taker_rate": self.taker_rate,
            "source": self.source,
            "account_level": self.account_level,
            "timestamp": self.timestamp,
        }


class FeeProvider(Protocol):
    """费率提供者接口
    
    真实实现会调用 HTX 认证接口获取账户实际费率。
    回测使用默认费率或配置的固定费率。
    """
    
    def get_fee_rates(self, symbol: str) -> FeeRates:
        """获取指定品种的费率
        
        Returns:
            FeeRates: 包含 maker/taker 费率及来源信息
        """
        ...


class DefaultFeeProvider:
    """默认费率提供者 - 使用固定费率（未认证场景）"""
    
    def __init__(
        self,
        maker_rate: float = 0.0002,
        taker_rate: float = 0.0006,
    ):
        self.maker_rate = maker_rate
        self.taker_rate = taker_rate
    
    def get_fee_rates(self, symbol: str) -> FeeRates:
        return FeeRates(
            maker_rate=self.maker_rate,
            taker_rate=self.taker_rate,
            source="default_config",
            account_level="Prime 0 (assumed, not authenticated)",
            timestamp=None,
        )


class HtxAuthenticatedFeeProvider:
    """HTX 认证费率提供者 - 从账户获取实际费率（需要 API key）
    
    调用 HTX 的费率查询接口获取账户的真实 maker/taker 费率。
    MVP-1 中这是 stub，因为回测不需要 API key。
    """
    
    def __init__(self, client: HtxV5Client):
        self.client = client
        self._cached_rates: dict[str, FeeRates] = {}
    
    def get_fee_rates(self, symbol: str) -> FeeRates:
        """获取账户实际费率（需要认证）
        
        HTX API 端点（示例，需验证）:
        - POST /linear-swap-api/v3/unified_account_info
        - 或专门的费率查询接口
        
        当前为 stub 实现，返回默认值并标记来源。
        """
        
        if symbol in self._cached_rates:
            return self._cached_rates[symbol]
        
        try:
            from datetime import UTC, datetime
            
            response = self._fetch_account_fee_rates(symbol)
            
            maker_rate = float(response.get("maker_fee", 0.0002))
            taker_rate = float(response.get("taker_fee", 0.0006))
            account_level = response.get("level", "unknown")
            
            rates = FeeRates(
                maker_rate=maker_rate,
                taker_rate=taker_rate,
                source="htx_authenticated_api",
                account_level=account_level,
                timestamp=datetime.now(UTC).isoformat(),
            )
            
            self._cached_rates[symbol] = rates
            return rates
        
        except Exception as e:
            return FeeRates(
                maker_rate=0.0002,
                taker_rate=0.0006,
                source=f"fallback_after_error: {type(e).__name__}",
                account_level=None,
                timestamp=None,
            )
    
    def _fetch_account_fee_rates(self, symbol: str) -> dict:
        """调用 HTX 费率查询接口（stub）
        
        TODO: 实现真实的 API 调用
        参考 HTX 文档：
        - https://www.htx.com/en-us/opend/newApiPages/?id=662
        
        需要的端点可能是：
        - GET /linear-swap-api/v1/swap_fee
        - 或账户信息接口中的费率字段
        """
        
        raise NotImplementedError(
            "Authenticated fee rate fetching requires API key. "
            "Implement _fetch_account_fee_rates with actual HTX API call. "
            "For now, use DefaultFeeProvider in backtest."
        )


def create_fee_provider(
    use_authenticated: bool = False,
    client: HtxV5Client | None = None,
    maker_rate: float | None = None,
    taker_rate: float | None = None,
) -> FeeProvider:
    """工厂函数：创建费率提供者
    
    Args:
        use_authenticated: 是否使用认证接口（需要 API key）
        client: HTX 客户端（认证模式需要）
        maker_rate: 自定义 maker 费率（默认 0.02%）
        taker_rate: 自定义 taker 费率（默认 0.06%）
    
    Returns:
        FeeProvider 实例
    """
    
    if use_authenticated:
        if client is None:
            raise ValueError("Authenticated fee provider requires HtxV5Client")
        return HtxAuthenticatedFeeProvider(client)
    else:
        return DefaultFeeProvider(
            maker_rate=maker_rate or 0.0002,
            taker_rate=taker_rate or 0.0006,
        )
