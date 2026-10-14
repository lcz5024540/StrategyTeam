from decimal import Decimal

import pytest

from htx_trend.credentials import decrypt_if_encrypted
from htx_trend.live_worker import LiveTrendWorker, LiveTrendWorkerConfig, MultiTimeframeLiveTrendWorker
from htx_trend.mysql_store import RuntimeInstance
from htx_trend.strategy_platform import ExecutionMode, WorkerHealth
from htx_trend.timeframes import TimeframeProfile


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
    monkeypatch.setenv("HTX_TREND_PERIOD", "15min")
    monkeypatch.setenv("HTX_TREND_CANDIDATE_SCAN_CONCURRENCY", "9")
    monkeypatch.setenv("HTX_TREND_MARKET_BREADTH_LOOKBACK_MINUTES", "42")
    monkeypatch.setenv("HTX_TREND_ADAPTIVE_SIZING_ENABLED", "false")
    monkeypatch.setenv("HTX_TREND_MARKET_ANCHOR_ENABLED", "false")
    monkeypatch.setenv("HTX_TREND_COMBINATION_ENTRY_ENABLED", "true")
    config = LiveTrendWorkerConfig.from_env()
    assert config.contracts == ("BTC-USDT", "ETH-USDT")
    assert config.enabled and not config.observe_only and config.entry_enabled
    assert config.entry_period == "15min"
    assert config.max_concurrency == 9
    assert config.market_breadth_lookback_seconds == 42 * 60
    assert not config.adaptive_sizing_enabled and not config.market_anchor_enabled
    assert config.combination_entry_enabled


def test_dual_profile_configuration_has_independent_live_switches(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_PROFILES", "INTRADAY_15M,SWING_4H")
    monkeypatch.setenv("HTX_TREND_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_ENTRY_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_INTRADAY_15M_LIVE_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_SWING_4H_LIVE_ENABLED", "false")
    configs = LiveTrendWorkerConfig.profiles_from_env()
    assert [(item.timeframe_profile, item.entry_period, item.trend_period, item.entry_enabled) for item in configs] == [
        (TimeframeProfile.INTRADAY_15M, "15min", "60min", True),
        (TimeframeProfile.SWING_4H, "4hour", "1day", False),
    ]


def test_profile_period_mismatch_fails_at_startup() -> None:
    with pytest.raises(ValueError, match="SWING_4H requires"):
        LiveTrendWorker(instance(), Repository(), object(), object(), LiveTrendWorkerConfig(
            ("BTC-USDT",), enabled=True, combination_entry_enabled=True,
            timeframe_profile=TimeframeProfile.SWING_4H, entry_period="15min", trend_period="60min"))


def test_legacy_v1_cannot_enable_swing_live_orders() -> None:
    with pytest.raises(ValueError, match="sealed admission"):
        LiveTrendWorker(instance(), Repository(), object(), object(), LiveTrendWorkerConfig(
            ("BTC-USDT",), enabled=True, observe_only=False, entry_enabled=True,
            combination_entry_enabled=True, timeframe_profile=TimeframeProfile.SWING_4H,
            entry_period="4hour", trend_period="1day"))


def test_multi_timeframe_worker_aggregates_independent_profile_health() -> None:
    class Child:
        def __init__(self, profile, health):
            self._config = type("Config", (), {"timeframe_profile": profile})()
            self.health = health
        def tick(self): return self.health
        def stop(self): pass
    worker = MultiTimeframeLiveTrendWorker((
        Child(TimeframeProfile.INTRADAY_15M, WorkerHealth("HEALTHY", "15m", 2, 1)),
        Child(TimeframeProfile.SWING_4H, WorkerHealth("HEALTHY", "4h", 3, 1)),
    ))
    health = worker.tick()
    assert health.status == "HEALTHY" and health.candidate_count == 5 and health.active_position_count == 1
    assert "INTRADAY_15M[15m]" in health.detail and "SWING_4H[4h]" in health.detail


def test_live_worker_treats_zero_position_cap_as_unlimited_only_for_java_combination_mode() -> None:
    worker = LiveTrendWorker(instance(), Repository(), object(), object(),
                             LiveTrendWorkerConfig(("BTC-USDT",), enabled=True, combination_entry_enabled=True,
                                                   max_active_positions=0))
    assert worker._remaining_slots(4, 3) == 4


def test_plaintext_credential_compatibility_does_not_require_or_log_key_path() -> None:
    assert decrypt_if_encrypted("plain-test-key") == "plain-test-key"
