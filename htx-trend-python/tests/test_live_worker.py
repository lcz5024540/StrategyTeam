from decimal import Decimal

import pytest

from htx_trend.credentials import decrypt_if_encrypted
from htx_trend.live_worker import LiveTrendWorker, LiveTrendWorkerConfig
from htx_trend.mysql_store import RuntimeInstance
from htx_trend.strategy_platform import ExecutionMode


class Repository:
    pass


def instance(mode: str = "LIVE") -> RuntimeInstance:
    return RuntimeInstance("python-live", "TREND", "HTX_TREND_V1", 7, "primary", mode, True, "unit", None)


def test_live_worker_rejects_observe_instance_even_when_an_executor_is_supplied() -> None:
    with pytest.raises(ValueError, match="LIVE"):
        LiveTrendWorker(instance("OBSERVE"), Repository(), object(), object(), LiveTrendWorkerConfig(("BTC-USDT",)))


def test_live_worker_refuses_to_run_combination_path_when_java_switch_is_off() -> None:
    with pytest.raises(ValueError, match="COMBINATION_ENTRY_ENABLED"):
        LiveTrendWorker(instance(), Repository(), object(), object(),
                        LiveTrendWorkerConfig(("BTC-USDT",), enabled=True))


def test_live_worker_refuses_to_start_when_java_trend_switch_is_off() -> None:
    with pytest.raises(ValueError, match="HTX_TREND_ENABLED"):
        LiveTrendWorker(instance(), Repository(), object(), object(),
                        LiveTrendWorkerConfig(("BTC-USDT",), combination_entry_enabled=True))


def test_live_worker_configuration_reads_java_environment_names(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_CONTRACTS", "BTC-USDT,ETH-USDT")
    monkeypatch.setenv("HTX_TREND_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_OBSERVE_ONLY", "false")
    monkeypatch.setenv("HTX_TREND_ENTRY_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_PERIOD", "30min")
    monkeypatch.setenv("HTX_TREND_CANDIDATE_SCAN_CONCURRENCY", "9")
    monkeypatch.setenv("HTX_TREND_MARKET_BREADTH_LOOKBACK_MINUTES", "42")
    monkeypatch.setenv("HTX_TREND_ADAPTIVE_SIZING_ENABLED", "false")
    monkeypatch.setenv("HTX_TREND_MARKET_ANCHOR_ENABLED", "false")
    monkeypatch.setenv("HTX_TREND_COMBINATION_ENTRY_ENABLED", "true")
    config = LiveTrendWorkerConfig.from_env()
    assert config.contracts == ("BTC-USDT", "ETH-USDT")
    assert config.enabled and not config.observe_only and config.entry_enabled
    assert config.entry_period == "30min"
    assert config.max_concurrency == 9
    assert config.market_breadth_lookback_seconds == 42 * 60
    assert not config.adaptive_sizing_enabled and not config.market_anchor_enabled
    assert config.combination_entry_enabled


def test_live_worker_treats_zero_position_cap_as_unlimited_only_for_java_combination_mode() -> None:
    worker = LiveTrendWorker(instance(), Repository(), object(), object(),
                             LiveTrendWorkerConfig(("BTC-USDT",), enabled=True, combination_entry_enabled=True,
                                                   max_active_positions=0))
    assert worker._remaining_slots(4, 3) == 4


def test_plaintext_credential_compatibility_does_not_require_or_log_key_path() -> None:
    assert decrypt_if_encrypted("plain-test-key") == "plain-test-key"
