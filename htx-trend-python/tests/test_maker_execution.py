from decimal import Decimal

from htx_trend.maker_execution import (
    MakerExecutionConfig, MakerTimeoutAction, MakerLifecycleResult,
    maker_limit_price, maker_reference_price, should_amend,
    evaluate_maker_fill, evaluate_maker_lifecycle,
    shadow_evidence, p_shadow_taker_evidence,
)
from htx_trend.market_data import Candle, OrderBook
from htx_trend.models import TrendSide


def test_default_maker_config_is_disabled() -> None:
    cfg = MakerExecutionConfig.from_env()
    assert cfg.enabled is False


def test_maker_limit_price_is_better_than_reference() -> None:
    # LONG buy limit should be below reference
    assert maker_limit_price(TrendSide.LONG, Decimal("100"), Decimal("10")) < Decimal("100")
    # SHORT sell limit should be above reference
    assert maker_limit_price(TrendSide.SHORT, Decimal("100"), Decimal("10")) > Decimal("100")


def test_maker_limit_price_respects_offset() -> None:
    price = maker_limit_price(TrendSide.LONG, Decimal("10000"), Decimal("5"))
    # 5 bps below reference, quantized to 0.01
    assert price == Decimal("9995.00")


def test_maker_limit_price_with_zero_offset_equals_reference() -> None:
    assert maker_limit_price(TrendSide.LONG, Decimal("10000"), Decimal("0")) == Decimal("10000.00")
    assert maker_limit_price(TrendSide.SHORT, Decimal("10000"), Decimal("0")) == Decimal("10000.00")


def test_maker_reference_price_uses_best_same_side_book_price() -> None:
    book = OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),))
    assert maker_reference_price(TrendSide.LONG, book) == Decimal("99.99")
    assert maker_reference_price(TrendSide.SHORT, book) == Decimal("100.01")


def test_maker_reference_price_returns_none_for_empty_book() -> None:
    empty = OrderBook((), ())
    assert maker_reference_price(TrendSide.LONG, empty) is None


def test_should_amend_when_drift_exceeds_threshold() -> None:
    # drift = 0.2 / 100 = 20 bps
    assert should_amend(Decimal("100"), Decimal("100.2"), Decimal("25")) is False
    assert should_amend(Decimal("100"), Decimal("100.2"), Decimal("15")) is True


def test_should_amend_is_false_when_threshold_is_zero() -> None:
    assert should_amend(Decimal("100"), Decimal("110"), Decimal("0")) is False


def test_evaluate_maker_fill_for_long_limit() -> None:
    result = evaluate_maker_fill(
        side=TrendSide.LONG, quantity=10, limit_price=Decimal("99"),
        candle_low=Decimal("98"), candle_high=Decimal("101"))
    assert result.filled_quantity == 10
    assert result.status == "FILLED"


def test_evaluate_maker_fill_expires_when_limit_not_touched() -> None:
    result = evaluate_maker_fill(
        side=TrendSide.LONG, quantity=10, limit_price=Decimal("97"),
        candle_low=Decimal("98"), candle_high=Decimal("101"))
    assert result.filled_quantity == 0
    assert result.status == "OPEN"


def test_evaluate_maker_fill_can_return_partial() -> None:
    result = evaluate_maker_fill(
        side=TrendSide.SHORT, quantity=10, limit_price=Decimal("101"),
        candle_low=Decimal("98"), candle_high=Decimal("101"), partial_accept=True)
    assert result.filled_quantity == 5
    assert result.remaining_quantity == 5
    assert result.status == "PARTIAL"


def test_evaluate_maker_lifecycle_fills_within_one_bar() -> None:
    # Quantity=1 lets the deterministic helper fill completely in one bar.
    candles = (Candle(0, Decimal("100"), Decimal("101"), Decimal("98"), Decimal("100")),)
    result = evaluate_maker_lifecycle(
        side=TrendSide.LONG, quantity=1, placed_price=Decimal("99"),
        candles=candles, max_wait_bars=1, max_amends=0,
        amend_threshold_bps=Decimal("1"), partial_accept=False,
        timeout_action=MakerTimeoutAction.CANCEL)
    assert isinstance(result, MakerLifecycleResult)
    assert result.final_status == "FILLED"
    assert result.filled_quantity == 1
    assert result.bars_held == 1
    assert result.amends_done == 0


def test_evaluate_maker_lifecycle_cancel_on_timeout_never_falls_back_to_taker() -> None:
    candles = (Candle(0, Decimal("100"), Decimal("101"), Decimal("98"), Decimal("100")),)
    result = evaluate_maker_lifecycle(
        side=TrendSide.LONG, quantity=10, placed_price=Decimal("97"),
        candles=candles, max_wait_bars=1, max_amends=0,
        amend_threshold_bps=Decimal("1"), partial_accept=False,
        timeout_action=MakerTimeoutAction.CANCEL)
    assert result.final_status == "CANCELLED"
    assert result.filled_quantity == 0
    assert result.remaining_quantity == 10
    assert "CANCEL" in result.reason
    assert "TAKER" not in result.reason


def test_evaluate_maker_lifecycle_zero_amends_never_amends() -> None:
    # Price drifts far beyond threshold, but max_amends=0 prevents re-pricing.
    candles = (
        Candle(0, Decimal("100"), Decimal("101"), Decimal("100"), Decimal("100")),
        Candle(0, Decimal("110"), Decimal("111"), Decimal("110"), Decimal("110")),
    )
    result = evaluate_maker_lifecycle(
        side=TrendSide.LONG, quantity=10, placed_price=Decimal("99"),
        candles=candles, max_wait_bars=2, max_amends=0,
        amend_threshold_bps=Decimal("1"), partial_accept=False,
        timeout_action=MakerTimeoutAction.CANCEL)
    assert result.amends_done == 0
    assert result.final_status == "CANCELLED"


def test_evaluate_maker_lifecycle_partial_accept_false_fills_fully_when_touched() -> None:
    # Limit is touched and partial_accept=False -> evaluate_maker_fill fills fully.
    candles = (Candle(0, Decimal("100"), Decimal("101"), Decimal("98"), Decimal("100")),)
    result = evaluate_maker_lifecycle(
        side=TrendSide.LONG, quantity=10, placed_price=Decimal("99"),
        candles=candles, max_wait_bars=1, max_amends=0,
        amend_threshold_bps=Decimal("1"), partial_accept=False,
        timeout_action=MakerTimeoutAction.CANCEL)
    assert result.final_status == "FILLED"
    assert result.filled_quantity == 10
    assert result.remaining_quantity == 0


def test_evaluate_maker_lifecycle_partial_accept_true_keeps_remaining() -> None:
    # Quantity=2 fills 1 in the first bar and the remaining 1 in the second.
    candles = (
        Candle(0, Decimal("100"), Decimal("101"), Decimal("98"), Decimal("100")),
        Candle(0, Decimal("100"), Decimal("101"), Decimal("98"), Decimal("100")),
    )
    result = evaluate_maker_lifecycle(
        side=TrendSide.LONG, quantity=2, placed_price=Decimal("99"),
        candles=candles, max_wait_bars=2, max_amends=0,
        amend_threshold_bps=Decimal("1"), partial_accept=True,
        timeout_action=MakerTimeoutAction.CANCEL)
    assert result.final_status == "FILLED"
    assert result.filled_quantity == 2


def test_freeze_knobs_are_readable_from_env(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_MAKER_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_MAKER_OFFSET_BPS", "0")
    monkeypatch.setenv("HTX_TREND_MAKER_MAX_WAIT_BARS", "1")
    monkeypatch.setenv("HTX_TREND_MAKER_MAX_AMENDS", "0")
    monkeypatch.setenv("HTX_TREND_MAKER_PARTIAL_ACCEPT", "false")
    monkeypatch.setenv("HTX_TREND_MAKER_TIMEOUT_ACTION", "CANCEL")
    cfg = MakerExecutionConfig.from_env()
    assert cfg.enabled is True
    assert cfg.offset_bps == Decimal("0")
    assert cfg.max_wait_bars == 1
    assert cfg.max_amends == 0
    assert cfg.partial_accept is False
    assert cfg.timeout_action is MakerTimeoutAction.CANCEL


def test_max_amends_none_preserves_unlimited_amend_behaviour(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_MAKER_MAX_AMENDS", "none")
    cfg = MakerExecutionConfig.from_env()
    assert cfg.max_amends is None


def test_shadow_evidence_computes_positive_diff_when_maker_cheaper() -> None:
    evidence = shadow_evidence(
        run_id="run", contract_code="BTC-USDT", side=TrendSide.LONG,
        signal_price=Decimal("100"), taker_price=Decimal("100.05"), maker_price=Decimal("99.98"),
        maker_fee_rate=Decimal("0.0002"), taker_fee_rate=Decimal("0.0004"),
        fill_status="FILLED")
    # taker slippage ~5 bps + fee ~4 bps = 9 bps; maker slippage ~-2 bps + 2 bps = 0
    assert evidence.diff_bps > Decimal("0")


def test_p_shadow_taker_evidence_has_distinct_legs() -> None:
    evidence = p_shadow_taker_evidence(
        run_id="run", contract_code="BTC-USDT", side=TrendSide.LONG,
        signal_price=Decimal("100"),
        maker_price=Decimal("99.99"), taker_price=Decimal("100.01"),
        maker_fee_rate=Decimal("0.0002"), taker_fee_rate=Decimal("0.0004"),
        fill_status="INTENT")
    assert evidence.contract_code == "BTC-USDT"
    legs = {leg.leg: leg for leg in evidence.legs}
    assert "maker_live" in legs and "taker_shadow" in legs
    assert legs["maker_live"].price == Decimal("99.99")
    assert legs["taker_shadow"].price == Decimal("100.01")


def test_maker_timeout_action_default_is_taker_fallback() -> None:
    cfg = MakerExecutionConfig()
    assert cfg.timeout_action is MakerTimeoutAction.TAKER
