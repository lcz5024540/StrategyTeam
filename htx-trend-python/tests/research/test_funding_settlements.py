"""Funding is charged only on real settlements in (bar_open, bar_close]."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pandas as pd
import pytest

from htx_trend.research.engine.backtest.sim_broker import CostScenario, SimBroker
from htx_trend.research.engine.contract_value import coin_notional
from htx_trend.research.engine.interfaces import Order
from htx_trend.research.features.funding_asof import apply_bar_settlements, settlements_in_bar
from tests.research.helpers import DEFAULT_SPECS, create_test_dataset, write_runner
from tests.research.test_bad_tick_kinds import _stop_runner
from tests.research.test_review_round3 import _mark_bar, _ohlc_workspace


RATE = 0.0001  # 0.01%
CS = 0.001
PRICE = 100.0


def _funding_day(start: datetime) -> pd.DataFrame:
    """Fixture records with their own timestamps (not a production schedule)."""
    times = [
        start + timedelta(hours=8),
        start + timedelta(hours=16),
        start + timedelta(hours=24),
    ]
    return pd.DataFrame(
        {
            "funding_time": pd.to_datetime(times, utc=True),
            "funding_rate": [RATE, RATE, RATE],
        }
    )


def _broker() -> SimBroker:
    return SimBroker(10_000.0, CostScenario.ZERO, contract_sizes={"BTC-USDT": CS})


def _open_long(broker: SimBroker, when: datetime, price: float = PRICE) -> None:
    broker.submit_order(Order(symbol="BTC-USDT", side="LONG", size=1, order_type="MARKET"))
    broker.process_next_bar(
        "BTC-USDT",
        {"open": price, "high": price, "low": price, "close": price, "bar_time": when},
    )


def test_settlements_in_bar_are_left_open_right_closed():
    start = datetime(2024, 1, 1, tzinfo=UTC)
    funding = _funding_day(start)
    none = settlements_in_bar(funding, start, start + timedelta(hours=4))
    assert none.empty
    at_eight = settlements_in_bar(
        funding, start + timedelta(hours=4), start + timedelta(hours=8)
    )
    assert list(at_eight["funding_rate"]) == [RATE]
    assert pd.Timestamp(at_eight.iloc[0]["funding_time"]) == pd.Timestamp(
        start + timedelta(hours=8)
    )
    daily = settlements_in_bar(funding, start, start + timedelta(days=1))
    assert len(daily) == 3


def test_position_held_24h_charged_exactly_three_settlements_4h_and_1d():
    start = datetime(2024, 1, 1, tzinfo=UTC)
    funding = _funding_day(start)
    expected = 3 * coin_notional(PRICE, 1, CS, "BTC-USDT") * RATE

    broker_4h = _broker()
    _open_long(broker_4h, start)
    for i in range(6):
        open_t = start + timedelta(hours=4 * i)
        close_t = open_t + timedelta(hours=4)
        bar = {
            "open": PRICE,
            "high": PRICE,
            "low": PRICE,
            "close": PRICE,
            "bar_time": open_t,
        }
        apply_bar_settlements(broker_4h, "BTC-USDT", bar, "4hour", funding)
        assert close_t == open_t + timedelta(hours=4)
    assert len(broker_4h.funding_events) == 3
    assert broker_4h.positions["BTC-USDT"].accumulated_funding == pytest.approx(expected)

    broker_1d = _broker()
    _open_long(broker_1d, start)
    apply_bar_settlements(
        broker_1d,
        "BTC-USDT",
        {"open": PRICE, "high": PRICE, "low": PRICE, "close": PRICE, "bar_time": start},
        "1day",
        funding,
    )
    assert len(broker_1d.funding_events) == 3
    assert broker_1d.positions["BTC-USDT"].accumulated_funding == pytest.approx(expected)
    assert broker_4h.positions["BTC-USDT"].accumulated_funding == pytest.approx(
        broker_1d.positions["BTC-USDT"].accumulated_funding
    )


def test_charges_follow_history_when_frequency_changes_8h_to_4h():
    start = datetime(2024, 1, 1, tzinfo=UTC)
    eight_hour = [start + timedelta(hours=offset) for offset in (8, 16, 24)]
    four_hour = [
        start + timedelta(days=1, hours=offset) for offset in (4, 8, 12, 16, 20, 24)
    ]
    times = eight_hour + four_hour
    funding = pd.DataFrame(
        {
            "funding_time": pd.to_datetime(times, utc=True),
            "funding_rate": [RATE] * len(times),
        }
    )
    broker = _broker()
    _open_long(broker, start)
    for i in range(12):
        open_t = start + timedelta(hours=4 * i)
        apply_bar_settlements(
            broker,
            "BTC-USDT",
            {
                "open": PRICE,
                "high": PRICE,
                "low": PRICE,
                "close": PRICE,
                "bar_time": open_t,
            },
            "4hour",
            funding,
        )
    charged = [pd.Timestamp(item["settlement_time"]) for item in broker.funding_events]
    expected_times = list(pd.to_datetime(times, utc=True))
    assert charged == expected_times
    assert len(broker.funding_events) == 9
    expected = 9 * coin_notional(PRICE, 1, CS, "BTC-USDT") * RATE
    assert broker.positions["BTC-USDT"].accumulated_funding == pytest.approx(expected)


def test_short_with_positive_rate_receives_funding():
    broker = _broker()
    start = datetime(2024, 1, 1, tzinfo=UTC)
    broker.submit_order(Order(symbol="BTC-USDT", side="SHORT", size=1, order_type="MARKET"))
    broker.process_next_bar(
        "BTC-USDT",
        {"open": PRICE, "high": PRICE, "low": PRICE, "close": PRICE, "bar_time": start},
    )
    expected = coin_notional(PRICE, 1, CS, "BTC-USDT") * RATE
    broker.apply_funding("BTC-USDT", RATE, mark_price=PRICE)
    pos = broker.positions["BTC-USDT"]
    assert pos.side == "SHORT"
    assert pos.accumulated_funding == pytest.approx(-expected)
    broker.submit_order(Order(symbol="BTC-USDT", side="LONG", size=1, order_type="MARKET"))
    broker.process_next_bar(
        "BTC-USDT",
        {
            "open": PRICE,
            "high": PRICE,
            "low": PRICE,
            "close": PRICE,
            "bar_time": start + timedelta(hours=8),
        },
    )
    assert broker.equity == pytest.approx(10_000.0 + expected)
    assert broker.fills[-1].funding == pytest.approx(-expected)


def test_flat_at_settlement_is_not_charged():
    start = datetime(2024, 1, 1, tzinfo=UTC)
    funding = _funding_day(start)
    broker = _broker()
    apply_bar_settlements(
        broker,
        "BTC-USDT",
        {"open": PRICE, "high": PRICE, "low": PRICE, "close": PRICE, "bar_time": start},
        "1day",
        funding,
    )
    assert broker.funding_events == []
    assert broker.positions == {}


def test_settlement_on_frozen_bar_is_still_charged(tmp_path):
    workspace = _ohlc_workspace(tmp_path, "fundfrz")
    target = pd.read_parquet(workspace / "data" / "datasets" / "ohlc" / "BTC-USDT.parquet")
    from tests.research.helpers import record_funding_files, runner_config, write_nominal_funding

    write_nominal_funding(workspace, ["BTC-USDT"], dataset_id="ohlc", rate=RATE)
    funding_times = set(
        pd.to_datetime(
            pd.read_parquet(workspace / "data" / "raw" / "BTC-USDT_funding.parquet")[
                "funding_time"
            ],
            utc=True,
        )
    )
    idx = None
    frozen_close = None
    for i, open_t in enumerate(pd.to_datetime(target["bar_time"], utc=True)):
        if i < 212:
            continue
        close_t = open_t + pd.Timedelta(hours=4)
        if close_t in funding_times:
            idx = i
            frozen_close = close_t
            break
    assert idx is not None
    _mark_bar(workspace, idx, bad_tick=True, bad_tick_kind="frozen")

    manifest_hash = record_funding_files(workspace, "ohlc", ["BTC-USDT"])
    cfg = runner_config(
        workspace,
        "FUNDFRZ",
        "ohlc",
        symbols=["BTC-USDT"],
        allow_missing_funding=False,
        initial_equity=10_000.0,
        conditions={
            "C01": {"enabled": True, "periods": [5], "threshold": 0.0},
            "C02": {
                "enabled": True,
                "atr_period": 14,
                "atr_multiplier": 2.0,
                "tick_size": 0.1,
                "stop_slippage_ticks": 1,
            },
        },
    )
    cfg["manifest_hash"] = manifest_hash
    results = write_runner(workspace, cfg).run()
    events = results["funding_events"]
    times = [
        pd.Timestamp(item["settlement_time"])
        if pd.Timestamp(item["settlement_time"]).tzinfo
        else pd.Timestamp(item["settlement_time"], tz="UTC")
        for item in events
    ]
    want = pd.Timestamp(frozen_close)
    if want.tzinfo is None:
        want = want.tz_localize("UTC")
    else:
        want = want.tz_convert("UTC")
    assert any(stamp.tz_convert("UTC") == want for stamp in times)
    assert any(item["funding_rate"] == RATE for item in events)


def test_runner_does_not_charge_every_4h_asof_rate(tmp_path):
    workspace = tmp_path / "asof"
    workspace.mkdir()
    n = 230
    create_test_dataset(
        workspace,
        "ohlc",
        n_bars=n,
        train_end_idx=n,
        val_end_idx=n,
        symbols=["BTC-USDT"],
        specs={"BTC-USDT": DEFAULT_SPECS["BTC-USDT"]},
    )
    from tests.research.helpers import record_funding_files, runner_config, write_nominal_funding

    write_nominal_funding(workspace, ["BTC-USDT"], dataset_id="ohlc", rate=RATE)

    manifest_hash = record_funding_files(workspace, "ohlc", ["BTC-USDT"])
    cfg = runner_config(
        workspace,
        "ASOF6X",
        "ohlc",
        symbols=["BTC-USDT"],
        allow_missing_funding=False,
    )
    cfg["manifest_hash"] = manifest_hash
    results = write_runner(workspace, cfg).run()
    events = results["funding_events"]
    # One calendar day of 4h processing must not produce 6 charges at the as-of rate.
    by_day = {}
    for item in events:
        key = pd.Timestamp(item["settlement_time"]).floor("D")
        by_day.setdefault(key, 0)
        by_day[key] += 1
    assert all(count <= 3 for count in by_day.values())
