"""from_env must not read slots dataclass fields via cls.<name> (member descriptors)."""

import os

import pytest

from htx_trend.execution import ExecutionConfig
from htx_trend.execution_service import EntryExecutionConfig


@pytest.fixture
def clear_htx_trend_env(monkeypatch):
    for key in list(os.environ):
        if key.startswith("HTX_TREND_") or key == "HTX_CONTRACT_MARGIN_SAFETY_RATIO":
            monkeypatch.delenv(key, raising=False)


def test_entry_execution_config_from_env_without_leverage_env_uses_defaults(clear_htx_trend_env) -> None:
    config = EntryExecutionConfig.from_env()
    assert config == EntryExecutionConfig()
    assert config.leverage == 5


def test_entry_execution_config_from_env_reads_leverage_when_set(clear_htx_trend_env, monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_LEVERAGE", "10")
    config = EntryExecutionConfig.from_env()
    assert config.leverage == 10


def test_execution_config_from_env_without_cooldown_env_uses_defaults(clear_htx_trend_env) -> None:
    config = ExecutionConfig.from_env()
    expected = ExecutionConfig()
    assert config.loss_cooldown_seconds == expected.loss_cooldown_seconds
    assert config.breakeven_cooldown_seconds == expected.breakeven_cooldown_seconds
    assert config.profit_cooldown_seconds == expected.profit_cooldown_seconds
    assert config.strong_reentry_seconds == expected.strong_reentry_seconds


def test_execution_config_from_env_converts_loss_cooldown_ms(clear_htx_trend_env, monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_LOSS_COOLDOWN_MS", "7200000")
    config = ExecutionConfig.from_env()
    assert config.loss_cooldown_seconds == 7200
