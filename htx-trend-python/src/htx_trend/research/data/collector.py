"""数据采集器 - 从 HTX 和 Binance 采集历史数据"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from typing import Literal

import pandas as pd

from ..adapters.binance import BinanceVisionClient
from ..adapters.htx.market_data import HtxMarketDataClient, Kline
from ..ops.logging import LogComponent, log_event

BINANCE_VISION_START = datetime(2020, 1, 1, tzinfo=UTC)
MAX_KLINE_PAGES = 100


def parse_lookback_days(value: str | int | None) -> int | Literal["max"]:
    if value is None:
        raise ValueError("lookback_days is required (positive int or 'max')")
    text = str(value).strip().lower()
    if text == "max":
        return "max"
    try:
        days = int(text)
    except (TypeError, ValueError) as exc:
        raise ValueError("lookback_days must be a positive integer or 'max'") from exc
    if days <= 0:
        raise ValueError("lookback_days must be a positive integer or 'max'")
    return days


class DataCollector:
    def __init__(self, raw_data_dir):
        self.raw_data_dir = raw_data_dir
        self.raw_data_dir.mkdir(parents=True, exist_ok=True)

    def collect_htx_klines(
        self,
        contract_code: str,
        period: Literal["4hour", "1day"],
        lookback_days: int | str,
        client: HtxMarketDataClient | None = None,
    ) -> pd.DataFrame:
        window = parse_lookback_days(lookback_days)
        log_event(
            LogComponent.DATA,
            f"Collecting HTX klines: {contract_code} {period}",
            contract=contract_code,
            period=period,
            lookback_days=window,
        )

        owned = client is None
        market = client or HtxMarketDataClient()
        try:
            if window == "max":
                klines = market.fetch_klines_paginated(
                    contract_code,
                    period,
                    max_pages=MAX_KLINE_PAGES,
                    page_size=HtxMarketDataClient.MAX_KLINE_SIZE,
                )
            else:
                end = datetime.now(UTC)
                start = end - timedelta(days=int(window))
                klines = market.fetch_klines_paginated(
                    contract_code,
                    period,
                    start_time=start,
                    end_time=end,
                    max_pages=MAX_KLINE_PAGES,
                    page_size=HtxMarketDataClient.MAX_KLINE_SIZE,
                )
        finally:
            if owned:
                market.close()

        if not klines:
            return pd.DataFrame()

        df = pd.DataFrame(
            [
                {
                    "bar_time": k.bar_time,
                    "open": k.open,
                    "high": k.high,
                    "low": k.low,
                    "close": k.close,
                    "volume": k.volume,
                    "amount": k.amount,
                    "source": k.source,
                    "first_seen_at": k.first_seen_at,
                }
                for k in klines
            ]
        )

        output_path = self.raw_data_dir / f"{contract_code}_{period}_htx.parquet"
        df.to_parquet(output_path, index=False)

        log_event(
            LogComponent.DATA,
            f"HTX klines saved: {len(df)} bars",
            contract=contract_code,
            period=period,
            bars=len(df),
            path=str(output_path),
        )

        return df

    def collect_binance_klines(
        self,
        symbol: str,
        interval: Literal["4h", "1d"],
        start_date: datetime,
        end_date: datetime,
        client: BinanceVisionClient | None = None,
    ) -> pd.DataFrame:
        log_event(
            LogComponent.DATA,
            f"Collecting Binance klines: {symbol} {interval}",
            symbol=symbol,
            interval=interval,
            start=start_date.isoformat(),
            end=end_date.isoformat(),
        )

        owned = client is None
        vision = client or BinanceVisionClient()
        try:
            all_klines = vision.download_klines(
                symbol=symbol,
                interval=interval,
                start_date=start_date,
                end_date=end_date,
            )
        finally:
            if owned:
                vision.close()

        if not all_klines:
            return pd.DataFrame()

        df = pd.DataFrame(
            [
                {
                    "bar_time": k.bar_time,
                    "open": k.open,
                    "high": k.high,
                    "low": k.low,
                    "close": k.close,
                    "volume": k.volume,
                    "amount": k.amount,
                    "source": k.source,
                    "first_seen_at": k.first_seen_at,
                }
                for k in all_klines
            ]
        )

        df = df.drop_duplicates(subset=["bar_time"]).sort_values("bar_time")

        output_path = self.raw_data_dir / f"{symbol}_{interval}_binance.parquet"
        df.to_parquet(output_path, index=False)

        log_event(
            LogComponent.DATA,
            f"Binance klines saved: {len(df)} bars",
            symbol=symbol,
            interval=interval,
            bars=len(df),
            path=str(output_path),
        )

        return df

    def collect_funding_history(self, contract_code: str) -> pd.DataFrame:
        log_event(
            LogComponent.DATA,
            f"Collecting funding history: {contract_code}",
            contract=contract_code,
        )

        with HtxMarketDataClient() as client:
            rates = client.fetch_funding_history(contract_code, page_size=200)

        if not rates:
            return pd.DataFrame()

        df = pd.DataFrame(
            [
                {
                    "symbol": r.symbol,
                    "funding_time": r.funding_time,
                    "funding_rate": r.funding_rate,
                    "realized_rate": r.realized_rate,
                }
                for r in rates
            ]
        )

        output_path = self.raw_data_dir / f"{contract_code}_funding.parquet"
        df.to_parquet(output_path, index=False)

        log_event(
            LogComponent.DATA,
            f"Funding history saved: {len(df)} records",
            contract=contract_code,
            records=len(df),
            path=str(output_path),
        )

        return df

    def collect_contract_spec(self, contract_code: str) -> dict:
        log_event(
            LogComponent.DATA,
            f"Collecting contract spec: {contract_code}",
            contract=contract_code,
        )

        with HtxMarketDataClient() as client:
            spec = client.fetch_contract_info(contract_code)

        if spec is None:
            return {}

        spec_dict = {
            "contract_code": spec.contract_code,
            "symbol": spec.symbol,
            "contract_size": spec.contract_size,
            "price_tick": spec.price_tick,
            "min_order_size": spec.min_order_size,
            "create_date": spec.create_date,
        }

        output_path = self.raw_data_dir / f"{contract_code}_spec.json"
        output_path.write_text(json.dumps(spec_dict, indent=2))

        log_event(
            LogComponent.DATA,
            f"Contract spec saved",
            contract=contract_code,
            path=str(output_path),
        )

        return spec_dict
