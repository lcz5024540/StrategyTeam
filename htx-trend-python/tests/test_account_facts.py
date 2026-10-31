from decimal import Decimal

import pytest

from htx_trend.account_facts import HtxAccountFactsAdapter, _account_equity, _available_margin
from htx_trend.market_data import OrderBook
from htx_trend.models import TrendSide


class Client:
    def swap_fee(self, _): return [{"taker_fee_rate": "0.0004"}]
    def positions(self, _): return [{"position_side": "long", "volume": "3"}, {"position_side": "short", "available": "2"}]
    def open_orders(self, _): return []
    def account_balance(self): return {"state": "normal", "available_margin": "50", "margin_balance": "75", "details": []}


def test_account_facts_use_v5_java_wire_names_for_fee_position_and_margin() -> None:
    snapshot = HtxAccountFactsAdapter(Client()).snapshot("BTC-USDT", TrendSide.LONG,
        OrderBook(((Decimal("99"), Decimal("10")),), ((Decimal("100"), Decimal("11")),)),
        active_position_count=0, max_active_positions=3, reserved_notional_usdt=Decimal("0"), risk_capacity_usdt=Decimal("300"))
    assert snapshot.facts.actual_taker_fee_rate == Decimal("0.0004")
    assert snapshot.same_position_contracts == Decimal("3")
    assert snapshot.opposite_position_contracts == Decimal("2")
    assert snapshot.facts.available_margin_usdt == Decimal("50")
    assert snapshot.facts.account_equity_usdt == Decimal("75")
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


_USDT_DETAILS_ROW = {
    "currency": "USDT",
    "equity": "270.532857",
    "available_margin": "270.532857",
}


def test_account_equity_falls_back_when_top_level_equity_is_zero_t1() -> None:
    payload = {"equity": "0", "details": [_USDT_DETAILS_ROW]}
    assert _account_equity(payload) == Decimal("270.532857")


def test_available_margin_falls_back_when_top_level_is_zero_t2() -> None:
    payload = {"available_margin": "0", "details": [_USDT_DETAILS_ROW]}
    assert _available_margin(payload) == Decimal("270.532857")


def test_account_equity_prefers_positive_top_level_over_details_t3() -> None:
    payload = {"equity": "100.5", "details": [_USDT_DETAILS_ROW]}
    assert _account_equity(payload) == Decimal("100.5")


def test_account_equity_raises_when_no_valid_positive_values_t4() -> None:
    payload = {"details": [{"currency": "USDT", "equity": "0"}, {"currency": "BTC", "equity": None}]}
    with pytest.raises(ValueError, match="account equity"):
        _account_equity(payload)


def test_account_equity_fail_closed_when_top_zero_and_details_empty_t5() -> None:
    with pytest.raises(ValueError, match="account equity"):
        _account_equity({"equity": "0", "details": []})
    with pytest.raises(ValueError, match="account equity"):
        _account_equity({"equity": "0", "details": [{"currency": "USDT", "equity": "0"}]})
