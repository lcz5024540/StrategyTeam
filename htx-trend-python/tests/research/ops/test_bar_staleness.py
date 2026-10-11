"""Health check: bar staleness per timeframe."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from htx_trend.research.cli import main
from htx_trend.research.ops.heartbeat import check_health


def _write_state(path: Path, last_4h: datetime, last_1d: datetime) -> None:
    path.write_text(
        json.dumps(
            {
                "BTC-USDT_4hour": {
                    "symbol": "BTC-USDT",
                    "interval": "4hour",
                    "last_bar_time": last_4h.isoformat(),
                },
                "ETH-USDT_1day": {
                    "symbol": "ETH-USDT",
                    "interval": "1day",
                    "last_bar_time": last_1d.isoformat(),
                },
            }
        )
    )


def test_health_check_fresh_bars(tmp_path):
    now = datetime(2026, 1, 2, 4, 5, tzinfo=timezone.utc)
    state = tmp_path / "collector_state.json"
    _write_state(
        state,
        last_4h=datetime(2026, 1, 2, 0, 0, tzinfo=timezone.utc),
        last_1d=datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc),
    )
    # 4h bar closed at 04:00, now 04:05, lag=5 < 10
    # 1d bar closed at 2026-01-02 00:00, now 04:05, lag ~245 > 10 — wait that would be stale
    # Use 1d last bar so expected close is now-ish
    _write_state(
        state,
        last_4h=datetime(2026, 1, 2, 0, 0, tzinfo=timezone.utc),
        last_1d=datetime(2026, 1, 1, 18, 0, tzinfo=timezone.utc),
    )
    # 1d expected close = 2026-01-02 18:00 which is in the future → lag negative → not stale
    ok, details = check_health(
        collector_state_path=state,
        bar_staleness_threshold_minutes=10,
        now=now,
    )
    assert ok
    assert all(not item["is_stale"] for item in details["bar_staleness"])


def test_health_check_stale_4hour_bar(tmp_path):
    now = datetime(2026, 1, 2, 8, 30, tzinfo=timezone.utc)
    state = tmp_path / "state.json"
    state.write_text(
        json.dumps(
            {
                "BTC-USDT": {
                    "symbol": "BTC-USDT",
                    "interval": "4hour",
                    "last_bar_time": "2026-01-02T00:00:00+00:00",
                }
            }
        )
    )
    # expected close 04:00, now 08:30, lag=270min
    ok, details = check_health(
        collector_state_path=state,
        bar_staleness_threshold_minutes=10,
        now=now,
    )
    assert not ok
    assert details["bar_staleness"][0]["interval"] == "4hour"
    assert details["bar_staleness"][0]["is_stale"]


def test_health_check_stale_1day_bar(tmp_path):
    now = datetime(2026, 1, 3, 1, 0, tzinfo=timezone.utc)
    state = tmp_path / "state.json"
    state.write_text(
        json.dumps(
            {
                "ETH-USDT": {
                    "symbol": "ETH-USDT",
                    "interval": "1day",
                    "last_bar_time": "2026-01-01T00:00:00+00:00",
                }
            }
        )
    )
    ok, details = check_health(
        collector_state_path=state,
        bar_staleness_threshold_minutes=10,
        now=now,
    )
    assert not ok
    assert details["bar_staleness"][0]["interval"] == "1day"
    assert details["bar_staleness"][0]["is_stale"]


def test_health_cli_exit_code_healthy(tmp_path, monkeypatch):
    now = datetime.now(timezone.utc)
    last = now - timedelta(hours=4, minutes=2)
    state = tmp_path / "run" / "collector_state.json"
    state.parent.mkdir(parents=True)
    state.write_text(
        json.dumps(
            {
                "BTC-USDT": {
                    "symbol": "BTC-USDT",
                    "interval": "4hour",
                    "last_bar_time": last.isoformat(),
                }
            }
        )
    )
    monkeypatch.setattr(
        "sys.argv",
        [
            "htx-trend-research",
            "--workspace",
            str(tmp_path),
            "health",
            "--threshold-minutes",
            "30",
        ],
    )
    with pytest.raises(SystemExit) as exc:
        main()
    assert exc.value.code == 0


def test_health_cli_exit_code_unhealthy(tmp_path, monkeypatch):
    last = datetime.now(timezone.utc) - timedelta(hours=12)
    state = tmp_path / "run" / "collector_state.json"
    state.parent.mkdir(parents=True)
    state.write_text(
        json.dumps(
            {
                "BTC-USDT": {
                    "symbol": "BTC-USDT",
                    "interval": "4hour",
                    "last_bar_time": last.isoformat(),
                }
            }
        )
    )
    monkeypatch.setattr(
        "sys.argv",
        [
            "htx-trend-research",
            "--workspace",
            str(tmp_path),
            "health",
            "--threshold-minutes",
            "10",
        ],
    )
    with pytest.raises(SystemExit) as exc:
        main()
    assert exc.value.code == 1


def test_health_last_bar_open_plus_two_periods_is_stale_deadline(tmp_path):
    last_open = datetime(2026, 1, 2, 12, 0, tzinfo=timezone.utc)
    state = tmp_path / "state.json"
    state.write_text(
        json.dumps(
            {
                "BTC-USDT": {
                    "symbol": "BTC-USDT",
                    "interval": "4hour",
                    "last_bar_time": last_open.isoformat(),
                }
            }
        )
    )
    threshold = 10
    ok_mid, details_mid = check_health(
        collector_state_path=state,
        bar_staleness_threshold_minutes=threshold,
        now=datetime(2026, 1, 2, 17, 13, tzinfo=timezone.utc),
    )
    assert ok_mid
    assert not details_mid["bar_staleness"][0]["is_stale"]
    assert details_mid["bar_staleness"][0]["expected_next_close"] == datetime(
        2026, 1, 2, 20, 0, tzinfo=timezone.utc
    ).isoformat()

    ok_stale, details_stale = check_health(
        collector_state_path=state,
        bar_staleness_threshold_minutes=threshold,
        now=datetime(2026, 1, 2, 20, 11, tzinfo=timezone.utc),
    )
    assert not ok_stale
    assert details_stale["bar_staleness"][0]["is_stale"]


def test_bar_staleness_per_timeframe(tmp_path):
    now = datetime(2026, 1, 2, 8, 30, tzinfo=timezone.utc)
    state = tmp_path / "state.json"
    _write_state(
        state,
        last_4h=datetime(2026, 1, 2, 0, 0, tzinfo=timezone.utc),  # stale
        last_1d=datetime(2026, 1, 1, 20, 0, tzinfo=timezone.utc),  # expected 2026-01-02 20:00 future
    )
    ok, details = check_health(
        collector_state_path=state,
        bar_staleness_threshold_minutes=10,
        now=now,
    )
    by_tf = {item["interval"]: item for item in details["bar_staleness"]}
    assert by_tf["4hour"]["is_stale"]
    assert not by_tf["1day"]["is_stale"]
    assert not ok
