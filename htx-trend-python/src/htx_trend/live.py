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
    def __init__(self, client: HtxV5Client, authority: LiveAuthority, account_id: str, *, writer_guard=None) -> None:
        self._client, self._authority, self._account_id = client, authority, account_id
        self._writer_guard = writer_guard

    def place(self, request: SwapOrderRequest) -> dict:
        if not self._authority.enabled or self._authority.approved_account_id != self._account_id:
            raise LiveTradingDisabled("Python live executor is not explicitly approved for this account")
        self._check_writer()
        return self._client.place_order(request)

    def cancel(self, contract_code: str, order_id: str) -> dict:
        if not self._authority.enabled or self._authority.approved_account_id != self._account_id:
            raise LiveTradingDisabled("Python live executor is not explicitly approved for this account")
        self._check_writer()
        return self._client.cancel_order(contract_code, order_id)

    def set_leverage(self, contract_code: str, position_side: str, leverage: int) -> dict:
        if not self._authority.enabled or self._authority.approved_account_id != self._account_id:
            raise LiveTradingDisabled("Python live executor is not explicitly approved for this account")
        self._check_writer()
        return self._client.set_leverage(contract_code, position_side, leverage)

    def _check_writer(self) -> None:
        if self._writer_guard is None:
            raise LiveTradingDisabled("live mutation requires the shared Java/Python writer lock")
        self._writer_guard()

    def order(self, contract_code: str, *, order_id: str | None = None,
              client_order_id: str | None = None) -> dict:
        """Private read for reconciliation; available without mutation authority."""
        return self._client.order(contract_code, order_id=order_id, client_order_id=client_order_id)

    def order_details(self, contract_code: str, order_id: str, *, from_id="0", limit=100) -> list[dict]:
        """Read execution receipts without order mutation authority."""
        return self._client.order_details(contract_code, order_id, from_id=from_id, limit=limit)
