from decimal import Decimal
from pathlib import Path

import httpx
import pytest

from htx_trend.htx_v5 import HtxV5Client, LiveTradingDisabled, SwapOrderRequest
from htx_trend.live import HtxLiveExecutor, LiveAuthority
from htx_trend.market_data import Candle, KlineWindow
from htx_trend.replay import ReplaySnapshot
from htx_trend.validation import compare_json, replay_report


def test_replay_marks_small_window_as_insufficient_without_fake_performance() -> None:
    window = KlineWindow("BTC-USDT", "15min", (Candle(0, Decimal("1"), Decimal("2"), Decimal("0.5"), Decimal("1.5")),), 1)
    report = replay_report([ReplaySnapshot("s", "ENTRY_SCAN", window, None, None)])
    assert report.insufficient_windows == 1
    assert report.rows[0].candidates == ()


def test_replay_uses_the_same_entry_contract_without_a_second_trend_scorer() -> None:
    candles = tuple(Candle(900 * index, Decimal("100"), Decimal("101"), Decimal("99"), Decimal("100"))
                    for index in range(1, 161))
    entry = KlineWindow("BTC-USDT", "15min", candles, 160)
    report = replay_report([ReplaySnapshot("entry", "ENTRY_SCAN", entry, "run", None)])
    assert report.complete_windows == 1
    assert report.schema == "htx-trend-python-same-source-replay-v3"


def test_parity_comparator_reports_only_real_difference() -> None:
    assert compare_json({"side": "LONG", "score": "80"}, {"side": "LONG", "score": "80"}) == []
    diff = compare_json({"side": "LONG"}, {"side": "SHORT"})
    assert diff[0]["path"] == "$.side"


def test_live_executor_requires_second_explicit_authority() -> None:
    client = HtxV5Client("access", "secret", base_url="https://example.test", live_trading_enabled=True,
                         client=httpx.Client(transport=httpx.MockTransport(lambda _: pytest.fail("must not call HTX"))))
    executor = HtxLiveExecutor(client, LiveAuthority(), "main")
    with pytest.raises(LiveTradingDisabled):
        executor.place(SwapOrderRequest("BTC-USDT", "long", "buy", "market", "x", "1"))
