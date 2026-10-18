"""CLI --stitch-point is SYMBOL=TIME only; runner uses frozen stitch points."""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest
import yaml

from htx_trend.research.data.quality import (
    load_stitch_points,
    parse_stitch_point_overrides,
    stitch_point_for,
)
from tests.research.helpers import create_test_dataset, runner_config, write_runner


def _stitch_yaml(path: Path) -> Path:
    path.write_text(
        yaml.safe_dump(
            {
                "points": {
                    "BTC-USDT": "2020-11-01T00:00:00Z",
                    "ETH-USDT": "none",
                    "SOL-USDT": "none",
                }
            }
        )
    )
    return path


def test_bare_stitch_point_time_raises():
    with pytest.raises(ValueError, match="SYMBOL=TIME"):
        parse_stitch_point_overrides("2020-11-01T00:00:00Z")
    with pytest.raises(ValueError, match="SYMBOL=TIME"):
        parse_stitch_point_overrides(datetime(2020, 11, 1, tzinfo=UTC))


def test_unknown_stitch_point_symbol_raises(tmp_path):
    yaml_path = _stitch_yaml(tmp_path / "stitch_points.yaml")
    with pytest.raises(ValueError, match="unknown/undeclared"):
        load_stitch_points(yaml_path, override=["DOGE-USDT=2020-11-01T00:00:00Z"])


def test_repeatable_symbol_time_overrides_declared_none(tmp_path):
    yaml_path = _stitch_yaml(tmp_path / "stitch_points.yaml")
    points = load_stitch_points(
        yaml_path,
        override=[
            "BTC-USDT=2020-12-01T00:00:00Z",
            "ETH-USDT=2021-01-01T00:00:00Z",
        ],
    )
    assert points["BTC-USDT"] == datetime(2020, 12, 1, tzinfo=UTC)
    assert points["ETH-USDT"] == datetime(2021, 1, 1, tzinfo=UTC)
    assert points["SOL-USDT"] is None
    assert stitch_point_for("SOL-USDT", points) is None


def test_stitch_point_for_bare_override_raises():
    with pytest.raises(ValueError, match="SYMBOL=TIME"):
        stitch_point_for(
            "BTC-USDT",
            points={"BTC-USDT": None},
            override="2020-11-01T00:00:00Z",
        )


def test_runner_refuses_config_fallback_when_frozen_lacks_stitch_points(tmp_path):
    create_test_dataset(tmp_path, "no-stitch", n_bars=48, symbols=["BTC-USDT"])
    cfg = runner_config(
        tmp_path,
        "NOSTITCH",
        "no-stitch",
        symbols=["BTC-USDT"],
        stitch_points={"BTC-USDT": None},
    )
    runner = write_runner(tmp_path, cfg)

    class _Frozen:
        stitch_points = None

    runner._frozen = _Frozen()
    with pytest.raises(ValueError, match="frozen manifest missing stitch_points"):
        runner._load_stitch_points()
