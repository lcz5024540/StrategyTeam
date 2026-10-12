"""Funding as-of: only settlements at or before bar close."""

from __future__ import annotations

import pandas as pd

from htx_trend.research.features.funding_asof import attach_funding_as_of


def test_funding_as_of_uses_settlement_at_or_before_close():
    bars = pd.DataFrame(
        {
            "bar_time": [
                pd.Timestamp("2024-01-01 00:00", tz="UTC"),
                pd.Timestamp("2024-01-01 04:00", tz="UTC"),
            ],
            "close": [100.0, 101.0],
        }
    )
    funding = pd.DataFrame(
        {
            "funding_time": [
                pd.Timestamp("2024-01-01 00:00", tz="UTC"),
                pd.Timestamp("2024-01-01 08:00", tz="UTC"),
                pd.Timestamp("2024-01-01 16:00", tz="UTC"),
            ],
            "funding_rate": [0.001, 0.002, 0.099],
        }
    )
    out = attach_funding_as_of(bars, funding, "4hour", symbol="BTC-USDT", gaps=[])
    # first bar closes 04:00, may use 00:00, not 08:00/16:00
    assert float(out.loc[0, "funding_rate"]) == 0.001
    # second bar closes 08:00, may use 08:00
    assert float(out.loc[1, "funding_rate"]) == 0.002


def test_funding_after_bar_close_is_not_used():
    bars = pd.DataFrame(
        {
            "bar_time": [pd.Timestamp("2024-01-01 00:00", tz="UTC")],
            "close": [100.0],
        }
    )
    funding = pd.DataFrame(
        {
            "funding_time": [pd.Timestamp("2024-01-01 04:00:01", tz="UTC")],
            "funding_rate": [0.5],
        }
    )
    out = attach_funding_as_of(bars, funding, "4hour", symbol="BTC-USDT", gaps=[])
    assert float(out.loc[0, "funding_rate"]) == 0.0
    assert bool(out.loc[0, "pre_first_funding"]) is True


def test_runner_attaches_funding_as_of_bar_close(tmp_path, monkeypatch):
    from htx_trend.research.features.funding_asof import attach_funding_as_of as real_attach
    from tests.research.helpers import (
        create_test_dataset,
        record_funding_files,
        runner_config,
        write_nominal_funding,
        write_runner,
    )

    seen = {}

    def _wrap(bars, funding, interval, **kwargs):
        out = real_attach(bars, funding, interval, **kwargs)
        seen["interval"] = interval
        seen["rates"] = list(out["funding_rate"])
        return out

    monkeypatch.setattr(
        "htx_trend.research.runner.attach_funding_as_of", _wrap
    )
    create_test_dataset(tmp_path, "fund-asof", n_bars=48, symbols=["BTC-USDT"])
    write_nominal_funding(tmp_path, ["BTC-USDT"], dataset_id="fund-asof", rate=0.001)
    funding = pd.read_parquet(tmp_path / "data" / "raw" / "BTC-USDT_funding.parquet")
    last = pd.to_datetime(funding["funding_time"], utc=True).max()
    extra = pd.DataFrame(
        {
            "symbol": ["BTC-USDT"],
            "funding_time": [last + pd.Timedelta(hours=8)],
            "funding_rate": [0.5],
            "source": ["htx"],
        }
    )
    pd.concat([funding, extra], ignore_index=True).to_parquet(
        tmp_path / "data" / "raw" / "BTC-USDT_funding.parquet", index=False
    )
    manifest_hash = record_funding_files(tmp_path, "fund-asof", ["BTC-USDT"])
    cfg = runner_config(
        tmp_path,
        "FUND1",
        "fund-asof",
        symbols=["BTC-USDT"],
        allow_missing_funding=False,
    )
    cfg["manifest_hash"] = manifest_hash
    runner = write_runner(tmp_path, cfg)
    runner.run()
    assert seen["interval"] == "4hour"
    assert 0.5 not in {float(x) for x in seen["rates"] if pd.notna(x)}
