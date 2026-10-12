"""数据采集器 - 从 HTX 和 Binance 采集历史数据"""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

import pandas as pd

from ..adapters.binance import BinanceVisionClient
from ..adapters.htx.market_data import HtxMarketDataClient, Kline
from ..ops.logging import LogComponent, log_event
from .quality import require_source_column, require_source_value

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

    def collect_funding_history(
        self,
        contract_code: str,
        client: HtxMarketDataClient | None = None,
        binance_client: BinanceVisionClient | None = None,
        stitch_point: datetime | None = None,
        allow_missing_funding: bool = False,
    ) -> pd.DataFrame:
        log_event(
            LogComponent.DATA,
            f"Collecting funding history: {contract_code}",
            contract=contract_code,
        )

        owned = client is None
        market = client or HtxMarketDataClient()
        try:
            rates = market.fetch_funding_history_paginated(
                contract_code,
                page_size=50,
                page_sleep=0.2 if owned else 0.0,
                strict=True,
            )
        finally:
            if owned:
                market.close()

        htx_df = _funding_frame(rates, contract_code)
        if stitch_point is not None:
            owned_bn = binance_client is None
            vision = binance_client or BinanceVisionClient()
            try:
                bn_symbol = contract_code.replace("-", "")
                bn_rates = vision.download_funding_history(
                    bn_symbol,
                    BINANCE_VISION_START,
                    stitch_point,
                )
            finally:
                if owned_bn:
                    vision.close()
            bn_df = _funding_frame(bn_rates, contract_code)
            df = stitch_funding_history(htx_df, bn_df, stitch_point)
            htx_after = 0 if df.empty else int((df["source"] == "htx").sum())
            if htx_after == 0:
                raise ValueError(
                    f"HTX funding after stitch is empty for {contract_code}; "
                    "paginated HTX fetch must return post-stitch history"
                )
        else:
            df = htx_df

        if df.empty:
            if allow_missing_funding:
                return df
            raise ValueError(
                f"funding history is empty for {contract_code}; "
                "collect funding or set allow_missing_funding=true"
            )

        output_path = self.raw_data_dir / f"{contract_code}_funding.parquet"
        df.to_parquet(output_path, index=False)
        source_counts = (
            {str(key): int(value) for key, value in df["source"].value_counts().items()}
            if "source" in df.columns
            else {"htx": int(len(df))}
        )

        log_event(
            LogComponent.DATA,
            f"Funding history saved: {len(df)} records",
            contract=contract_code,
            records=len(df),
            htx=source_counts.get("htx", 0),
            binance_external=source_counts.get("binance_external", 0),
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


def _funding_frame(
    rates,
    contract_code: str,
) -> pd.DataFrame:
    if not rates:
        return pd.DataFrame(
            columns=["symbol", "funding_time", "funding_rate", "realized_rate", "source"]
        )
    return pd.DataFrame(
        [
            {
                "symbol": contract_code,
                "funding_time": row.funding_time,
                "funding_rate": row.funding_rate,
                "realized_rate": getattr(row, "realized_rate", None),
                "source": require_source_value(
                    getattr(row, "source", None),
                    context=f"{contract_code} funding",
                ),
            }
            for row in rates
        ]
    )


def stitch_funding_history(
    htx: pd.DataFrame,
    binance: pd.DataFrame,
    stitch_point: datetime,
) -> pd.DataFrame:
    stitch = pd.Timestamp(stitch_point)
    if stitch.tzinfo is None:
        stitch = stitch.tz_localize("UTC")
    else:
        stitch = stitch.tz_convert("UTC")

    frames = []
    if binance is not None and not binance.empty:
        bn = binance.copy()
        bn["funding_time"] = pd.to_datetime(bn["funding_time"], utc=True)
        require_source_column(bn, context="Binance funding stitch")
        frames.append(bn[bn["funding_time"] < stitch])
    if htx is not None and not htx.empty:
        hx = htx.copy()
        hx["funding_time"] = pd.to_datetime(hx["funding_time"], utc=True)
        require_source_column(hx, context="HTX funding stitch")
        frames.append(hx[hx["funding_time"] >= stitch])
    if not frames:
        return pd.DataFrame(
            columns=["symbol", "funding_time", "funding_rate", "realized_rate", "source"]
        )
    combined = pd.concat(frames, ignore_index=True)
    combined = combined.drop_duplicates(subset=["funding_time"]).sort_values("funding_time")
    return combined.reset_index(drop=True)


def funding_source_timeline(raw_dir, contracts: list[str]) -> dict:
    """Per-source settlement count and earliest/latest time for smoke summaries."""
    payload: dict[str, dict[str, dict[str, Any]]] = {}
    for contract in contracts:
        path = raw_dir / f"{contract}_funding.parquet"
        if not path.exists():
            continue
        fund = pd.read_parquet(path)
        if fund.empty:
            continue
        frame = fund.copy()
        require_source_column(frame, context=f"{contract} funding timeline")
        frame["funding_time"] = pd.to_datetime(frame["funding_time"], utc=True)
        by_source: dict[str, dict[str, Any]] = {}
        for source, group in frame.groupby(frame["source"].astype(str)):
            stamps = group["funding_time"]
            by_source[str(source)] = {
                "count": int(len(group)),
                "earliest": stamps.min().isoformat(),
                "latest": stamps.max().isoformat(),
            }
        payload[contract] = by_source
    return payload


def summarize_funding_sources(raw_dir, contracts: list[str]) -> dict:
    payload: dict[str, dict[str, int]] = {}
    for contract in contracts:
        path = raw_dir / f"{contract}_funding.parquet"
        if not path.exists():
            continue
        fund = pd.read_parquet(path)
        if fund.empty:
            continue
        require_source_column(fund, context=f"{contract} funding sources")
        counts = {str(key): int(value) for key, value in fund["source"].value_counts().items()}
        payload[contract] = counts
    return payload


def hash_funding_files(raw_dir, contracts: list[str]) -> dict:
    payload: dict[str, dict[str, str]] = {}
    for contract in contracts:
        path = raw_dir / f"{contract}_funding.parquet"
        if not path.exists():
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        payload[contract] = {
            "path": f"{contract}_funding.parquet",
            "sha256": f"sha256:{digest}",
        }
    return payload


def hash_spec_files(raw_dir, contracts: list[str] | None = None) -> dict:
    payload: dict[str, dict[str, str]] = {}
    if contracts is None:
        contracts = [path.name[: -len("_spec.json")] for path in sorted(raw_dir.glob("*_spec.json"))]
    for contract in contracts:
        path = raw_dir / f"{contract}_spec.json"
        if not path.exists():
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        payload[contract] = {
            "path": f"{contract}_spec.json",
            "sha256": f"sha256:{digest}",
        }
    return payload
