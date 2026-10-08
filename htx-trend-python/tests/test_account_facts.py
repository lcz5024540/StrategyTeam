from decimal import Decimal

from htx_trend.account_facts import HtxAccountFactsAdapter
from htx_trend.market_data import OrderBook
from htx_trend.models import TrendSide


class Client:
    def swap_fee(self, _): return [{"taker_fee_rate": "0.0004"}]
    def positions(self, _): return [{"position_side": "long", "volume": "3"}, {"position_side": "short", "available": "2"}]
    def open_orders(self, _): return []
    def swap_account_info(self, _): return [{"margin_available": "50", "lever_rate": "5", "position_mode": "dual_side"}]


def test_account_facts_use_same_java_wire_names_for_fee_position_and_margin() -> None:
    snapshot = HtxAccountFactsAdapter(Client()).snapshot("BTC-USDT", TrendSide.LONG,
        OrderBook(((Decimal("99"), Decimal("10")),), ((Decimal("100"), Decimal("11")),)),
        active_position_count=0, max_active_positions=3, reserved_notional_usdt=Decimal("0"), risk_capacity_usdt=Decimal("300"))
    assert snapshot.facts.actual_taker_fee_rate == Decimal("0.0004")
    assert snapshot.same_position_contracts == Decimal("3")
    assert snapshot.opposite_position_contracts == Decimal("2")
    assert snapshot.facts.available_margin_usdt == Decimal("50")
    assert snapshot.facts.executable_depth_contracts == Decimal("11")


def test_account_facts_labels_java_configured_fee_fallback_when_fee_profile_is_unavailable() -> None:
    class FeeUnavailableClient(Client):
        def swap_fee(self, _):
            raise RuntimeError("fee endpoint unavailable")

    snapshot = HtxAccountFactsAdapter(FeeUnavailableClient()).snapshot("BTC-USDT", TrendSide.LONG,
        OrderBook(((Decimal("99"), Decimal("10")),), ((Decimal("100"), Decimal("11")),)),
        active_position_count=0, max_active_positions=3, reserved_notional_usdt=Decimal("0"), risk_capacity_usdt=Decimal("300"))
    assert snapshot.facts.actual_taker_fee_rate == Decimal("0.0004")
    assert snapshot.fee_source == "CONFIGURED_FALLBACK"
