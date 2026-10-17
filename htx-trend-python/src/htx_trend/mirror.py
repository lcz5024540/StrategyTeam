"""Mirror only confirmed primary fills, matching Java's follower semantics."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from hashlib import sha256
import uuid

from .account_facts import HtxAccountFactsAdapter
from .execution_service import _client_id, _numeric_client_order_id, _order_id
from .htx_v5 import SwapOrderRequest
from .live import HtxLiveExecutor
from .market_data import ContractSpec, OrderBook
from .models import TrendSide
from .mysql_store import HtxTrendMySqlRepository, PositionIntent, RuntimeInstance, TradeAccount, TrendOrderIntent


@dataclass(frozen=True, slots=True)
class ConfirmedPrimaryFill:
    source_position_id: int
    source_position_key: str
    run_id: str
    contract_code: str
    side: TrendSide
    filled_contracts: int
    fill_price: Decimal
    contract_size: Decimal
    primary_model: str
    factor_snapshot_id: int | None
    timeframe_profile: str = "INTRADAY_15M"


@dataclass(frozen=True, slots=True)
class MirrorResult:
    accepted: bool
    mirrored_contracts: int
    mirror_position_id: int | None
    reason: str


@dataclass(frozen=True, slots=True)
class ConfirmedPrimaryReduction:
    source_position_id: int
    run_id: str
    contract_code: str
    side: TrendSide
    filled_contracts: int
    primary_model: str
    factor_snapshot_id: int | None
    full_close: bool
    timeframe_profile: str = "INTRADAY_15M"


class HtxTrendMirrorFollower:
    """Follower never sees a signal and can only copy a confirmed fill."""

    def __init__(self, *, repository: HtxTrendMySqlRepository, instance: RuntimeInstance, account: TradeAccount,
                 facts: HtxAccountFactsAdapter, executor: HtxLiveExecutor, leverage: int = 5) -> None:
        if instance.account_id != account.account_id:
            raise ValueError("mirror instance and mirror account differ")
        self._repository, self._instance, self._account = repository, instance, account
        self._facts, self._executor, self._leverage = facts, executor, leverage

    def sync_entry(self, primary: ConfirmedPrimaryFill, book: OrderBook, contract: ContractSpec) -> MirrorResult:
        if primary.filled_contracts <= 0:
            return MirrorResult(False, 0, None, "primary has no confirmed fill")
        allocation = self._repository.capital_allocation(account_id=self._account.account_id,
                                                         strategy_family=self._instance.strategy_family,
                                                         strategy_name=self._instance.strategy_name)
        limit = self._repository.account_risk_limit(self._account.account_id)
        if allocation is None or limit is None:
            return MirrorResult(False, 0, None, "mirror account allocation or risk limit missing")
        reserved = self._repository.active_risk_reservation_totals(self._account.account_id)
        snapshot = self._facts.snapshot(primary.contract_code, primary.side, book,
            active_position_count=self._repository.active_position_count(account_id=self._account.account_id),
            max_active_positions=min(allocation.max_active_positions, limit.max_active_positions),
            reserved_notional_usdt=reserved.notional_usdt, risk_capacity_usdt=None,
            known_strategy_side=primary.side)
        if snapshot.opposite_position_contracts > 0:
            return MirrorResult(False, 0, None, "mirror has opposite exchange position")
        if snapshot.open_order_count:
            return MirrorResult(False, 0, None, "mirror has open exchange order")
        delta = primary.filled_contracts - int(snapshot.same_position_contracts)
        if delta <= 0:
            return MirrorResult(True, 0, None, "mirror already matches confirmed primary fill")
        reference = book.top.ask if primary.side is TrendSide.LONG else book.top.bid
        notional = Decimal(delta) * contract.contract_size * reference
        position_key = f"python-mirror-{uuid.uuid4()}"
        client_order_id = _numeric_client_order_id()
        reservation_key = sha256(f"{self._instance.instance_key}:{position_key}".encode()).hexdigest()[:160]
        position_id = None
        try:
            self._executor.set_leverage(primary.contract_code, "long" if primary.side is TrendSide.LONG else "short", self._leverage)
            position_id = self._repository.record_position_intent(PositionIntent(position_key, primary.run_id, primary.contract_code,
                primary.side.value, primary.contract_size, delta, self._leverage, self._account, primary.primary_model,
                primary.factor_snapshot_id, "mirror confirmed primary fill", "mirror follows confirmed primary fill", None, None,
                None, None, primary.timeframe_profile, None, self._instance.strategy_family, self._instance.strategy_name,
                primary.source_position_id))
            self._repository.reserve_risk(reservation_key=reservation_key, instance=self._instance, position_key=position_key,
                                          notional_usdt=notional, risk_usdt=Decimal("0"),
                                          max_total_risk_usdt=Decimal("0"),
                                          max_active_positions=min(allocation.max_active_positions,
                                                                   limit.max_active_positions),
                                          reason=f"legacy mirror source={primary.source_position_id}")
            request = SwapOrderRequest(primary.contract_code, "long" if primary.side is TrendSide.LONG else "short",
                "buy" if primary.side is TrendSide.LONG else "sell", "market", client_order_id, str(delta))
            self._repository.record_order_intent(TrendOrderIntent(position_id, position_key, primary.run_id, primary.contract_code,
                "ENTRY", primary.side.value, request.side.upper(), request.position_side.upper(), False, client_order_id, delta,
                self._account, primary.primary_model, primary.factor_snapshot_id, "mirror confirmed primary fill",
                primary.timeframe_profile, self._instance.strategy_family, self._instance.strategy_name))
            response = self._executor.place(request)
            order_id = _order_id(response)
            if order_id is None:
                raise RuntimeError("HTX accepted mirror response lacks order id")
            self._repository.mark_order_accepted(position_id=position_id, leg="ENTRY", client_order_id=client_order_id,
                exchange_order_id=order_id, exchange_client_order_id=_client_id(response), message="mirror entry accepted")
            self._repository.mark_position_status(position_id=position_id, status="ENTRY_SENT", message="mirror entry sent")
            return MirrorResult(True, delta, position_id, "mirror entry accepted")
        except Exception as error:
            if position_id is not None:
                self._repository.mark_order_rejected(position_id=position_id, leg="ENTRY", client_order_id=client_order_id,
                                                     message=f"mirror entry rejected: {type(error).__name__}")
                self._repository.mark_position_status(position_id=position_id, status="REJECTED", message="mirror entry rejected")
            self._repository.release_risk(reservation_key, f"mirror entry failure: {type(error).__name__}")
            return MirrorResult(False, 0, position_id, f"mirror entry rejected: {type(error).__name__}")

    def sync_reduction(self, primary: ConfirmedPrimaryReduction, book: OrderBook) -> MirrorResult:
        """Send reduce-only volume only after the primary close fill is confirmed."""
        mirror = self._repository.mirror_position(source_position_id=primary.source_position_id, account_id=self._account.account_id)
        if mirror is None:
            return MirrorResult(False, 0, None, "mirror position not found for confirmed primary reduction")
        snapshot = self._facts.snapshot(primary.contract_code, primary.side, book,
            active_position_count=self._repository.active_position_count(account_id=self._account.account_id),
            max_active_positions=999_999, reserved_notional_usdt=Decimal("0"), risk_capacity_usdt=Decimal("999999999"),
            known_strategy_side=primary.side)
        if snapshot.open_order_count:
            return MirrorResult(False, 0, int(mirror["id"]), "mirror has open exchange order")
        close_volume = min(primary.filled_contracts, int(snapshot.same_position_contracts))
        if close_volume <= 0:
            return MirrorResult(True, 0, int(mirror["id"]), "mirror already flat")
        client_order_id = _numeric_client_order_id()
        leg = "CLOSE" if primary.full_close and close_volume == int(snapshot.same_position_contracts) else "PARTIAL_CLOSE"
        try:
            request = SwapOrderRequest(primary.contract_code, "long" if primary.side is TrendSide.LONG else "short",
                "sell" if primary.side is TrendSide.LONG else "buy", "market", client_order_id, str(close_volume), reduce_only=True)
            self._repository.record_order_intent(TrendOrderIntent(int(mirror["id"]), str(mirror["position_key"]), primary.run_id,
                primary.contract_code, leg, primary.side.value, request.side.upper(), request.position_side.upper(), True,
                client_order_id, close_volume, self._account, primary.primary_model, primary.factor_snapshot_id,
                "mirror confirmed primary reduction", primary.timeframe_profile, self._instance.strategy_family, self._instance.strategy_name))
            response = self._executor.place(request)
            order_id = _order_id(response)
            if order_id is None:
                raise RuntimeError("HTX accepted mirror close response lacks order id")
            self._repository.mark_order_accepted(position_id=int(mirror["id"]), leg=leg, client_order_id=client_order_id,
                exchange_order_id=order_id, exchange_client_order_id=_client_id(response), message="mirror reduction accepted")
            self._repository.mark_position_status(position_id=int(mirror["id"]),
                status="CLOSE_SENT" if leg == "CLOSE" else "PARTIAL_CLOSE_SENT", message="mirror reduction sent")
            return MirrorResult(True, close_volume, int(mirror["id"]), "mirror reduction accepted")
        except Exception as error:
            self._repository.mark_order_rejected(position_id=int(mirror["id"]), leg=leg, client_order_id=client_order_id,
                                                 message=f"mirror reduction rejected: {type(error).__name__}")
            return MirrorResult(False, 0, int(mirror["id"]), f"mirror reduction rejected: {type(error).__name__}")
