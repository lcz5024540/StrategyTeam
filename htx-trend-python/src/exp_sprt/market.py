"""Read-only HTX public market adapter for the shadow listener.

Reuses :class:`htx_trend.market_data.HtxMarketData` for completed candles and
the order book. Latest trades use the public trade endpoint. Empty trade
payloads stay empty: this path does not invent prints.
"""
from __future__ import annotations

from decimal import Decimal

import httpx

from htx_trend.htx_v5 import HtxV5Client
from htx_trend.market_data import Candle, HtxMarketData

from .evaluate import BookSnap, MarketObservation, TradePrint


class ShadowBoundaryError(RuntimeError):
    """Phase-0 refused a client that is allowed to mutate the account."""


class PublicTradeReader:
    """Public latest-trade read. No credentials and no account calls."""

    def __init__(self, client: httpx.Client, base_url: str) -> None:
        self._client = client
        self._base = base_url.rstrip("/")

    def trades(self, contract_code: str) -> list[dict]:
        response = self._client.get(
            f"{self._base}/linear-swap-ex/market/trade",
            params={"contract_code": contract_code},
        )
        response.raise_for_status()
        payload = response.json()
        data = (payload.get("tick") or {}).get("data") or []
        if not isinstance(data, list):
            raise ValueError("public trade payload is not a list")
        return data


def _parse_trade(raw: dict) -> TradePrint:
    price = raw.get("price")
    if price is None:
        raise ValueError("public trade print is missing a price")
    qty = raw.get("amount")
    if qty is None:
        qty = raw.get("quantity") if raw.get("quantity") is not None else raw.get("vol", "0")
    return TradePrint(
        int(raw.get("ts") or 0),
        Decimal(str(price)),
        Decimal(str(qty)),
        str(raw.get("direction") or ""),
        str(raw.get("id") or ""),
    )


class ReadOnlyHtxShadowFeed:
    """One-shot public read used by the shadow listener."""

    def __init__(self, market: HtxMarketData, trades: PublicTradeReader) -> None:
        self._market = market
        self._trades = trades

    @classmethod
    def from_client(cls, client: HtxV5Client) -> "ReadOnlyHtxShadowFeed":
        if getattr(client, "_live_trading_enabled", True):
            raise ShadowBoundaryError("EXP-SPRT Phase-0 refuses a mutation-enabled client")
        market = HtxMarketData(client, close_grace_seconds=10)
        reader = PublicTradeReader(client._client, client._base_url)
        return cls(market, reader)

    def completed_candles(self, symbol: str, now_epoch: int) -> tuple[Candle, ...]:
        return self._market.kline_window(symbol, "15min", 21, now_epoch=now_epoch).candles

    def latest_observation(self, symbol: str) -> MarketObservation:
        book = self._market.order_book(symbol)
        raw = self._trades.trades(symbol)
        trades = tuple(_parse_trade(item) for item in raw)
        snap = BookSnap(bids=book.bids, asks=book.asks)
        ts_ms = trades[-1].ts_ms if trades else 0
        return MarketObservation(ts_ms, trades, snap, True, "htx_public_readonly")
