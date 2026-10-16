"""Read-only HTX account facts for Java-compatible trend entry preflight."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Mapping

from .execution import ExecutionFacts
from .htx_v5 import HtxV5Client
from .market_data import OrderBook
from .models import TrendSide

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class AccountFactConfig:
    leverage: int = 5
    depth_levels: int = 5
    margin_safety_ratio: Decimal = Decimal("1.20")
    minimum_available_margin: Decimal = Decimal("20")
    configured_taker_fee_rate: Decimal = Decimal("0.0004")


@dataclass(frozen=True, slots=True)
class AccountSnapshot:
    facts: ExecutionFacts
    fee_source: str
    available_margin: Decimal
    open_order_count: int
    same_position_contracts: Decimal
    opposite_position_contracts: Decimal


class HtxAccountFactsAdapter:
    """Converts private HTX responses into explicit, auditable execution facts.

    It performs no mutation.  An unavailable or malformed private response is
    a preflight error, not an invitation to fall back to a configured fee.
    """

    def __init__(self, client: HtxV5Client, config: AccountFactConfig = AccountFactConfig()) -> None:
        self._client, self._config = client, config

    def snapshot(self, contract_code: str, side: TrendSide, book: OrderBook, *, active_position_count: int,
                 max_active_positions: int, reserved_notional_usdt: Decimal, risk_capacity_usdt: Decimal | None,
                 known_strategy_side: TrendSide | None = None,
                 same_direction_position_count: int | None = None) -> AccountSnapshot:
        # Java's HtxSwapFeeRateResolver preserves execution continuity with a
        # labelled configured fallback when HTX omits the fee profile.
        try:
            fee = _taker_fee(self._client.swap_fee(contract_code))
            fee_source = "HTX_ACCOUNT"
        except Exception:
            fee = self._config.configured_taker_fee_rate
            fee_source = "CONFIGURED_FALLBACK"
        positions = self._client.positions(contract_code)
        orders = self._client.open_orders(contract_code)
        account_balance = self._client.account_balance()
        same, opposite = _position_volumes(positions, side)
        # The entry service cannot take over a human/external position.  A
        # caller may explicitly identify a managed strategy position only when
        # reconciliation has proved the DB identity matches the exchange.
        external = None
        if same > ZERO and known_strategy_side is not side:
            external = side
        elif opposite > ZERO:
            external = TrendSide.SHORT if side is TrendSide.LONG else TrendSide.LONG
        margin = _available_margin(account_balance)
        facts = ExecutionFacts(actual_taker_fee_rate=fee, contract_open_order=bool(orders),
            external_position_side=external, exchange_position_consistent=True,
            executable_depth_contracts=book.executable_contracts(side.value, self._config.depth_levels),
            available_margin_usdt=margin, leverage=Decimal(max(1, self._config.leverage)),
            active_position_count=active_position_count, max_active_positions=max_active_positions,
            same_direction_position_count=same_direction_position_count,
            reserved_notional_usdt=reserved_notional_usdt, risk_capacity_usdt=risk_capacity_usdt)
        return AccountSnapshot(facts, fee_source, margin, len(orders), same, opposite)


def _decimal(row: dict[str, Any], *names: str) -> Decimal | None:
    for name in names:
        value = row.get(name)
        if value is not None:
            try:
                return Decimal(str(value))
            except Exception:
                return None
    return None


def _taker_fee(rows: list[dict[str, Any]]) -> Decimal:
    for row in rows:
        fee = _decimal(row, "taker_fee_rate", "takerFeeRate")
        if fee is not None and fee >= ZERO:
            return fee
    raise ValueError("HTX account fee profile lacks taker fee rate")


def _position_volumes(rows: list[dict[str, Any]], side: TrendSide) -> tuple[Decimal, Decimal]:
    same = opposite = ZERO
    expected = "long" if side is TrendSide.LONG else "short"
    for row in rows:
        volume = _decimal(row, "volume", "position_volume", "available") or ZERO
        if volume <= ZERO:
            continue
        position_side = str(row.get("position_side") or row.get("direction") or "").lower()
        if position_side == expected:
            same += volume
        else:
            opposite += volume
    return same, opposite


def _available_margin(value: Mapping[str, Any] | list[dict[str, Any]]) -> Decimal:
    """Read V5's account-wide margin, with a detail-list compatibility path."""
    if isinstance(value, Mapping):
        direct = _decimal(dict(value), "available_margin", "margin_available", "marginAvailable")
        if direct is not None:
            return direct
        details = value.get("details")
        if isinstance(details, list):
            return _available_margin(details)
        raise ValueError("HTX V5 account balance lacks available margin")
    values = [_decimal(row, "margin_available", "marginAvailable", "available_margin") or ZERO for row in value]
    if not values:
        raise ValueError("HTX account margin response is empty")
    return sum(values, ZERO)
