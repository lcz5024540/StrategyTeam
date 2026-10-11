"""Collector symbols/timeframes loaded from YAML config."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from htx_trend.research.collectors.continuous_collector import ContinuousCollector


def _write_config(path: Path, payload: dict) -> Path:
    path.write_text(yaml.dump(payload))
    return path


def test_collector_loads_symbols_from_config(tmp_path):
    cfg = _write_config(
        tmp_path / "collector.yaml",
        {
            "collector": {
                "symbols": ["BTC-USDT", "ETH-USDT"],
                "intervals": ["4hour", "1day"],
                "storage": {"base_path": str(tmp_path / "klines")},
                "state": {"file_path": str(tmp_path / "state.json")},
            }
        },
    )
    collector = ContinuousCollector.from_config(cfg)
    assert collector.symbols == ["BTC-USDT", "ETH-USDT"]


def test_collector_loads_intervals_from_config(tmp_path):
    cfg = _write_config(
        tmp_path / "collector.yaml",
        {
            "collector": {
                "symbols": ["BTC-USDT"],
                "intervals": ["4hour", "1day"],
                "storage": {"base_path": str(tmp_path / "klines")},
                "state": {"file_path": str(tmp_path / "state.json")},
            }
        },
    )
    collector = ContinuousCollector.from_config(cfg)
    assert collector.intervals == ["4hour", "1day"]


def test_collector_requires_1day_interval(tmp_path):
    cfg = _write_config(
        tmp_path / "collector.yaml",
        {
            "collector": {
                "symbols": ["BTC-USDT"],
                "intervals": ["4hour"],
                "storage": {"base_path": str(tmp_path / "klines")},
                "state": {"file_path": str(tmp_path / "state.json")},
            }
        },
    )
    with pytest.raises(ValueError, match="1day"):
        ContinuousCollector.from_config(cfg)


def test_collector_missing_symbols_raises(tmp_path):
    cfg = _write_config(
        tmp_path / "collector.yaml",
        {
            "collector": {
                "intervals": ["4hour", "1day"],
                "storage": {"base_path": str(tmp_path / "klines")},
                "state": {"file_path": str(tmp_path / "state.json")},
            }
        },
    )
    with pytest.raises(ValueError, match="symbols"):
        ContinuousCollector.from_config(cfg)


def test_collector_uses_configured_paths(tmp_path):
    storage = tmp_path / "custom-klines"
    state = tmp_path / "custom-state.json"
    cfg = _write_config(
        tmp_path / "collector.yaml",
        {
            "collector": {
                "symbols": ["ETH-USDT"],
                "intervals": ["1day"],
                "storage": {"base_path": str(storage)},
                "state": {"file_path": str(state)},
            }
        },
    )
    collector = ContinuousCollector.from_config(cfg)
    assert collector.storage_dir == storage
    assert collector.state_file == state


def test_systemd_units_use_unified_paths():
    root = Path(__file__).resolve().parents[3]
    collector = (root / "systemd" / "htx-trend-collector.service").read_text()
    heartbeat = (root / "systemd" / "htx-trend-heartbeat.service").read_text()
    for text in (collector, heartbeat):
        assert "User=ubuntu" in text
        assert "Group=ubuntu" in text
        assert "WorkingDirectory=/opt/coinstrategy/htx-trend-py" in text
        assert "ReadWritePaths=/opt/coinstrategy/htx-trend-py/data" in text
        assert "ReadWritePaths=/opt/coinstrategy/htx-trend-py/status.json" in text
        assert "ReadWritePaths=/opt/coinstrategy/htx-trend-py/heartbeat.txt" in text
    assert "--config /opt/coinstrategy/htx-trend-py/configs/collector.yaml" in collector
