"""Explicitly authorized live executor.  It is not used by the paper runtime."""
from __future__ import annotations

from dataclasses import dataclass

from .htx_v5 import HtxV5Client, LiveTradingDisabled, SwapOrderRequest


@dataclass(frozen=True, slots=True)
class LiveAuthority:
    enabled: bool = False
    approved_account_id: str | None = None


class HtxLiveExecutor:
    """Requires both a runtime authority and a client constructed for live HTX mutations."""
    def __init__(self, client: HtxV5Client, authority: LiveAuthority, account_id: str) -> None:
        self._client, self._authority, self._account_id = client, authority, account_id

    def place(self, request: SwapOrderRequest) -> dict:
        if not self._authority.enabled or self._authority.approved_account_id != self._account_id:
            raise LiveTradingDisabled("Python live executor is not explicitly approved for this account")
        return self._client.place_order(request)

    def cancel(self, contract_code: str, order_id: str) -> dict:
        if not self._authority.enabled or self._authority.approved_account_id != self._account_id:
            raise LiveTradingDisabled("Python live executor is not explicitly approved for this account")
        return self._client.cancel_order(contract_code, order_id)

    def set_leverage(self, contract_code: str, position_side: str, leverage: int) -> dict:
        if not self._authority.enabled or self._authority.approved_account_id != self._account_id:
            raise LiveTradingDisabled("Python live executor is not explicitly approved for this account")
        return self._client.set_leverage(contract_code, position_side, leverage)

    def order(self, contract_code: str, *, order_id: str | None = None,
              client_order_id: str | None = None) -> dict:
        """Private read for reconciliation; available without mutation authority."""
        return self._client.order(contract_code, order_id=order_id, client_order_id=client_order_id)
