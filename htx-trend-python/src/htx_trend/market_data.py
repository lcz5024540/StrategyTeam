"""Typed public HTX market inputs used by the trend runtime.

All strategy calculations consume :class:`Candle` values returned by this module.
The module deliberately removes the currently-forming bar before it reaches any
indicator or execution path, which keeps live and replay semantics identical.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from hashlib import sha256
from typing import Any, Iterable
import re

from .htx_v5 import HtxV5Client


_PERIOD_RE = re.compile(r"^(?P<count>\d+)(?P<unit>min|hour|day)$", re.IGNORECASE)


@dataclass(frozen=True, slots=True)
class Candle:
    """One HTX perpetual K-line, normalized independently of HTTP payload order."""

    open_time: int
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    amount: Decimal | None = None
    volume: Decimal | None = None
    turnover: Decimal | None = None
    trade_count: int | None = None

    @classmethod
    def from_htx(cls, raw: dict[str, Any]) -> "Candle":
        def decimal(name: str) -> Decimal | None:
            value = raw.get(name)
            return None if value is None else Decimal(str(value))

        required = {name: decimal(name) for name in ("open", "high", "low", "close")}
        if raw.get("id") is None or any(value is None for value in required.values()):
            raise ValueError("HTX K-line lacks id or OHLC values")
        return cls(
            open_time=int(raw["id"]),
            open=required["open"], high=required["high"], low=required["low"], close=required["close"],
            amount=decimal("amount"), volume=decimal("vol"), turnover=decimal("trade_turnover"),
            trade_count=None if raw.get("count") is None else int(raw["count"]),
        )

    def canonical(self) -> str:
        def value(item: Decimal | int | None) -> str:
            if isinstance(item, Decimal):
                return format(item.normalize(), "f")
            return "" if item is None else str(item)
        return "|".join(value(item) for item in (
            self.open_time, self.open, self.high, self.low, self.close, self.amount, self.volume, self.turnover,
            self.trade_count,
        ))

    @property
    def content_hash(self) -> str:
        return sha256(self.canonical().encode()).hexdigest()


@dataclass(frozen=True, slots=True)
class KlineWindow:
    contract_code: str
    period: str
    candles: tuple[Candle, ...]
    expected_size: int

    @property
    def is_complete(self) -> bool:
        if len(self.candles) < self.expected_size:
            return False
        seconds = period_seconds(self.period)
        return all(
            following.open_time - previous.open_time == seconds
            for previous, following in zip(self.candles, self.candles[1:])
        )

    @property
    def window_hash(self) -> str:
        return sha256("\n".join(item.content_hash for item in self.candles).encode()).hexdigest()


def period_seconds(period: str) -> int:
    matched = _PERIOD_RE.fullmatch(period.strip())
    if not matched:
        raise ValueError(f"unsupported HTX K-line period: {period}")
    scale = {"min": 60, "hour": 3_600, "day": 86_400}[matched.group("unit").lower()]
    return int(matched.group("count")) * scale


def completed_candles(candles: Iterable[Candle], period: str, *, now_epoch: int | None = None,
                      close_grace_seconds: int = 0) -> tuple[Candle, ...]:
    """Return sorted, de-duplicated bars whose close has passed the grace period."""
    now = int(datetime.now(UTC).timestamp()) if now_epoch is None else now_epoch
    cutoff = now - max(0, close_grace_seconds)
    deduplicated = {item.open_time: item for item in candles}
    return tuple(item for item in sorted(deduplicated.values(), key=lambda item: item.open_time)
                 if item.open_time + period_seconds(period) <= cutoff)


@dataclass(frozen=True, slots=True)
class TopOfBook:
    bid: Decimal
    ask: Decimal
    bid_size: Decimal | None = None
    ask_size: Decimal | None = None

    @property
    def mid(self) -> Decimal:
        return (self.bid + self.ask) / Decimal("2")

    @property
    def spread_rate(self) -> Decimal:
        return (self.ask - self.bid) / self.mid


@dataclass(frozen=True, slots=True)
class ContractSpec:
    contract_code: str
    contract_size: Decimal
    price_tick: Decimal
    contract_status: int | None


@dataclass(frozen=True, slots=True)
class FundingRate:
    contract_code: str
    rate: Decimal
    funding_time: int | None = None


class HtxMarketData:
    """Public market adapter.  No private credential or execution path is used here."""

    def __init__(self, client: HtxV5Client, *, close_grace_seconds: int = 3) -> None:
        self._client = client
        self._close_grace_seconds = close_grace_seconds

    def kline_window(self, contract_code: str, period: str, size: int, *, now_epoch: int | None = None) -> KlineWindow:
        if size < 1:
            raise ValueError("K-line size must be positive")
        raw = self._client.history_kline(contract_code, period, size + 1)
        candles = completed_candles((Candle.from_htx(item) for item in raw), period, now_epoch=now_epoch,
                                   close_grace_seconds=self._close_grace_seconds)
        return KlineWindow(contract_code, period, candles[-size:], size)

    def top_of_book(self, contract_code: str) -> TopOfBook:
        payload = self._client.depth(contract_code, 0)
        bids, asks = payload.get("bids"), payload.get("asks")
        if not isinstance(bids, list) or not isinstance(asks, list) or not bids or not asks:
            raise ValueError(f"HTX depth has no best bid/ask for {contract_code}")
        def level(value: object) -> tuple[Decimal, Decimal | None]:
            if not isinstance(value, list) or not value:
                raise ValueError(f"HTX malformed depth level for {contract_code}")
            return Decimal(str(value[0])), None if len(value) < 2 else Decimal(str(value[1]))
        bid, bid_size = level(bids[0])
        ask, ask_size = level(asks[0])
        if bid <= 0 or ask <= 0 or bid > ask:
            raise ValueError(f"HTX invalid best bid/ask for {contract_code}")
        return TopOfBook(bid, ask, bid_size, ask_size)

    def contract_spec(self, contract_code: str) -> ContractSpec:
        values = self._client.contract_info(contract_code)
        if not values:
            raise ValueError(f"HTX contract metadata missing for {contract_code}")
        value = values[0]
        return ContractSpec(
            contract_code=str(value.get("contract_code") or contract_code),
            contract_size=Decimal(str(value["contract_size"])),
            price_tick=Decimal(str(value["price_tick"])),
            contract_status=None if value.get("contract_status") is None else int(value["contract_status"]),
        )

    def funding_rate(self, contract_code: str) -> FundingRate:
        value = self._client.funding_rate(contract_code)
        rate = value.get("funding_rate")
        if rate is None:
            raise ValueError(f"HTX funding rate missing for {contract_code}")
        time = value.get("funding_time") or value.get("funding_time_ts")
        return FundingRate(contract_code, Decimal(str(rate)), None if time is None else int(time))
