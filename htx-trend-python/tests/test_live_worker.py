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


def test_plaintext_credential_compatibility_does_not_require_or_log_key_path() -> None:
    assert decrypt_if_encrypted("plain-test-key") == "plain-test-key"
