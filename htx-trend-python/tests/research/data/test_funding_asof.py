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
                pd.Timestamp("2024-01-01 04:00", tz="UTC"),
                pd.Timestamp("2024-01-01 08:00", tz="UTC"),
            ],
            "funding_rate": [0.001, 0.002, 0.099],
        }
    )
    out = attach_funding_as_of(bars, funding, "4hour")
    # first bar closes 04:00, may use 00:00 or 04:00, not 08:00
    assert float(out.loc[0, "funding_rate"]) in {0.001, 0.002}
    assert float(out.loc[0, "funding_rate"]) != 0.099
    # second bar closes 08:00, may use 08:00
    assert float(out.loc[1, "funding_rate"]) == 0.099


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
    out = attach_funding_as_of(bars, funding, "4hour")
    assert pd.isna(out.loc[0, "funding_rate"])
