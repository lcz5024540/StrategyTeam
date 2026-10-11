"""数据采集器 - 从 HTX 和 Binance 采集历史数据"""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Literal

import pandas as pd

from ..adapters.binance import BinanceVisionClient
from ..adapters.htx.market_data import HtxMarketDataClient, Kline
from ..ops.logging import LogComponent, log_event


class DataCollector:
    def __init__(self, raw_data_dir: Path):
        self.raw_data_dir = raw_data_dir
        self.raw_data_dir.mkdir(parents=True, exist_ok=True)
    
    def collect_htx_klines(
        self,
        contract_code: str,
        period: Literal["4hour", "1day"],
        lookback_days: int = 1095,
    ) -> pd.DataFrame:
        log_event(
            LogComponent.DATA,
            f"Collecting HTX klines: {contract_code} {period}",
            contract=contract_code,
            period=period,
            lookback_days=lookback_days,
        )
        
        with HtxMarketDataClient() as client:
            size = min(2000, (lookback_days * 24) // (4 if period == "4hour" else 24))
            klines = client.fetch_klines(contract_code, period, size)
        
        if not klines:
            return pd.DataFrame()
        
        df = pd.DataFrame([
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
        ])
        
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
    ) -> pd.DataFrame:
        log_event(
            LogComponent.DATA,
            f"Collecting Binance klines: {symbol} {interval}",
            symbol=symbol,
            interval=interval,
            start=start_date.isoformat(),
            end=end_date.isoformat(),
        )
        
        with BinanceVisionClient() as client:
            all_klines = client.download_klines(
                symbol=symbol,
                interval=interval,
                start_date=start_date,
                end_date=end_date,
            )

        if not all_klines:
            return pd.DataFrame()
        
        df = pd.DataFrame([
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
        ])
        
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
        
        df = pd.DataFrame([
            {
                "symbol": r.symbol,
                "funding_time": r.funding_time,
                "funding_rate": r.funding_rate,
                "realized_rate": r.realized_rate,
            }
            for r in rates
        ])
        
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
