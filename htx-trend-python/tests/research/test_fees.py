"""测试费率提供者"""

import pytest

from htx_trend.research.adapters.htx.fees import (
    DefaultFeeProvider,
    FeeRates,
    create_fee_provider,
)


def test_default_fee_provider():
    provider = DefaultFeeProvider(maker_rate=0.0002, taker_rate=0.0006)
    
    rates = provider.get_fee_rates("BTC-USDT")
    
    assert rates.maker_rate == 0.0002
    assert rates.taker_rate == 0.0006
    assert rates.source == "default_config"
    assert "Prime 0" in rates.account_level


def test_custom_fee_rates():
    provider = DefaultFeeProvider(maker_rate=0.0001, taker_rate=0.0005)
    
    rates = provider.get_fee_rates("ETH-USDT")
    
    assert rates.maker_rate == 0.0001
    assert rates.taker_rate == 0.0005


def test_factory_function():
    provider = create_fee_provider(
        use_authenticated=False,
        maker_rate=0.00015,
        taker_rate=0.00055,
    )
    
    rates = provider.get_fee_rates("BTC-USDT")
    
    assert rates.maker_rate == 0.00015
    assert rates.taker_rate == 0.00055


def test_fee_rates_to_dict():
    rates = FeeRates(
        maker_rate=0.0002,
        taker_rate=0.0006,
        source="test",
        account_level="VIP 1",
        timestamp="2024-01-01T00:00:00Z",
    )
    
    d = rates.to_dict()
    
    assert d["maker_rate"] == 0.0002
    assert d["taker_rate"] == 0.0006
    assert d["source"] == "test"
    assert d["account_level"] == "VIP 1"
