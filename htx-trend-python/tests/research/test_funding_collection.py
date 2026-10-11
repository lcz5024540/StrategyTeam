"""HTX funding paginates to exhaustion; Binance Vision backfills before stitch."""

from __future__ import annotations

from datetime import UTC, datetime

import pandas as pd

from htx_trend.research.adapters.htx.market_data import FundingRate, HtxMarketDataClient
from htx_trend.research.data.collector import DataCollector
from htx_trend.research.data.quality import DEFAULT_STITCH_POINT


END_TS = 1_704_067_200  # 2024-01-01 UTC
STITCH = DEFAULT_STITCH_POINT


def _htx_pages(page_count: int, per_page: int = 2):
    def funding_history(contract_code, page_size=50, page_index=1):
        if page_index > page_count:
            return []
        base = END_TS - (page_index - 1) * 8 * 3600 * per_page
        return [
            {
                "symbol": "BTC-USDT",
                "funding_time": (base - i * 8 * 3600) * 1000,
                "funding_rate": "0.0001",
            }
            for i in range(per_page)
        ]

    return funding_history


def test_collect_funding_history_paginates_until_exhausted(tmp_path):
    page_count = 25
    per_page = 2
    market = HtxMarketDataClient()
    market.client.funding_history = _htx_pages(page_count, per_page)
    df = DataCollector(tmp_path).collect_funding_history("BTC-USDT", client=market)
    assert len(df) == page_count * per_page
    assert (tmp_path / "BTC-USDT_funding.parquet").exists()


def test_collect_funding_history_does_not_use_single_page(tmp_path):
    seen = {}

    class FakeClient:
        def fetch_funding_history(self, *args, **kwargs):
            raise AssertionError("single-page fetch cannot reach ~6544 HTX records")

        def fetch_funding_history_paginated(self, contract_code, **kwargs):
            seen.update(kwargs)
            return [
                FundingRate(
                    symbol=contract_code,
                    funding_time=datetime(2020, 10, 21, 16, tzinfo=UTC),
                    funding_rate=0.0001,
                    realized_rate=0.0001,
                )
            ]

        def close(self) -> None:
            return None

    df = DataCollector(tmp_path).collect_funding_history("BTC-USDT", client=FakeClient())
    assert len(df) == 1
    max_pages = seen.get("max_pages")
    assert max_pages is None or max_pages >= 131
    assert df.iloc[0]["source"] == "htx"


class _Rate:
    def __init__(self, symbol, funding_time, funding_rate, source="htx", realized_rate=None):
        self.symbol = symbol
        self.funding_time = funding_time
        self.funding_rate = funding_rate
        self.realized_rate = realized_rate
        self.source = source


def test_collect_funding_backfills_binance_before_stitch(tmp_path):
    htx_rows = [
        _Rate("BTC-USDT", datetime(2020, 10, 21, 16, tzinfo=UTC), 0.0003, "htx"),
        _Rate("BTC-USDT", datetime(2020, 11, 1, 8, tzinfo=UTC), 0.0004, "htx"),
    ]
    binance_rows = [
        _Rate("BTCUSDT", datetime(2020, 9, 1, 0, tzinfo=UTC), 0.0001, "binance_external"),
        _Rate("BTCUSDT", datetime(2020, 10, 21, 16, tzinfo=UTC), 0.0002, "binance_external"),
        _Rate("BTCUSDT", datetime(2020, 11, 1, 8, tzinfo=UTC), 0.0099, "binance_external"),
    ]

    class FakeHtx:
        def fetch_funding_history_paginated(self, contract_code, **kwargs):
            return htx_rows

        def close(self) -> None:
            return None

    class FakeBinance:
        def download_funding_history(self, symbol, start_date, end_date):
            assert symbol == "BTCUSDT"
            assert end_date == STITCH
            return binance_rows

        def close(self) -> None:
            return None

    df = DataCollector(tmp_path).collect_funding_history(
        "BTC-USDT",
        client=FakeHtx(),
        binance_client=FakeBinance(),
        stitch_point=STITCH,
    )
    assert set(df["source"]) == {"htx", "binance_external"}
    before = df[pd.to_datetime(df["funding_time"], utc=True) < pd.Timestamp(STITCH)]
    after = df[pd.to_datetime(df["funding_time"], utc=True) >= pd.Timestamp(STITCH)]
    assert (before["source"] == "binance_external").all()
    assert (after["source"] == "htx").all()
    assert float(before.iloc[0]["funding_rate"]) == 0.0001
    assert 0.0099 not in set(after["funding_rate"].astype(float))
    assert len(df) == 3
