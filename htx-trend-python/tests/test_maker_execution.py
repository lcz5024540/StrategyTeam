from decimal import Decimal

from htx_trend.maker_execution import (
    MakerExecutionConfig, MakerTimeoutAction,
    maker_limit_price, should_amend, evaluate_maker_fill, shadow_evidence,
)
from htx_trend.models import TrendSide


def test_default_maker_config_is_disabled() -> None:
    cfg = MakerExecutionConfig.from_env()
    assert cfg.enabled is False


def test_maker_limit_price_is_better_than_mid() -> None:
    # LONG buy limit should be below mid
    assert maker_limit_price(TrendSide.LONG, Decimal("100"), Decimal("10")) < Decimal("100")
    # SHORT sell limit should be above mid
    assert maker_limit_price(TrendSide.SHORT, Decimal("100"), Decimal("10")) > Decimal("100")


def test_maker_limit_price_respects_offset() -> None:
    price = maker_limit_price(TrendSide.LONG, Decimal("10000"), Decimal("5"))
    # 5 bps below mid, quantized to 0.01
    assert price == Decimal("9995.00")


def test_should_amend_when_drift_exceeds_threshold() -> None:
    # drift = 0.2 / 100 = 20 bps
    assert should_amend(Decimal("100"), Decimal("100.2"), Decimal("25")) is False
    assert should_amend(Decimal("100"), Decimal("100.2"), Decimal("15")) is True


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


def test_shadow_evidence_computes_positive_diff_when_maker_cheaper() -> None:
    evidence = shadow_evidence(
        run_id="run", contract_code="BTC-USDT", side=TrendSide.LONG,
        signal_price=Decimal("100"), taker_price=Decimal("100.05"), maker_price=Decimal("99.98"),
        maker_fee_rate=Decimal("0.0002"), taker_fee_rate=Decimal("0.0004"),
        fill_status="FILLED")
    # taker slippage ~5 bps + fee ~4 bps = 9 bps; maker slippage ~-2 bps + 2 bps = 0
    assert evidence.diff_bps > Decimal("0")


def test_maker_timeout_action_default_is_taker_fallback() -> None:
    cfg = MakerExecutionConfig()
    assert cfg.timeout_action is MakerTimeoutAction.TAKER
