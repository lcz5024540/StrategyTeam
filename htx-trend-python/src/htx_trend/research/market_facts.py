"""Immutable HTX contract and historical funding facts for strategy research."""
from __future__ import annotations

from bisect import bisect_right
from dataclasses import dataclass
from decimal import Decimal
from typing import Callable, Mapping, Sequence

from ..market_data import Candle, period_seconds
from ..models import TrendSide
from .store import FileEvidenceStore

ZERO = Decimal("0")
FUNDING_ENDPOINT = "/linear-swap-api/v1/swap_historical_funding_rate"
CONTRACT_ENDPOINT = "/linear-swap-api/v1/swap_contract_info"
ELEMENTS_ENDPOINT = "/linear-swap-api/v1/swap_query_elements"


def _epoch_seconds(value: object) -> int:
    timestamp = int(str(value))
    return timestamp // 1000 if timestamp >= 10**12 else timestamp


@dataclass(frozen=True, slots=True)
class ContractMarketFact:
    contract_code: str
    contract_size: Decimal
    price_tick: Decimal
    minimum_order_contracts: int
    settlement_period_hours: int | None
    contract_status: int | None
    observed_at: int
    evidence_ref: str


@dataclass(frozen=True, slots=True)
class HistoricalFundingEvent:
    contract_code: str
    funding_time: int
    funding_rate: Decimal
    realized_rate: Decimal | None
    effective_rate: Decimal
    rate_source: str
    fee_asset: str
    funding_fee_type: str | None
    evidence_ref: str


@dataclass(frozen=True, slots=True)
class FundingCharge:
    amount: Decimal | None
    event_count: int
    event_times: tuple[int, ...]
    evidence_status: str


class FrozenMarketFacts:
    """Verified market facts bound to one content-addressed manifest."""

    def __init__(self, manifest_hash: str, start: int, end: int,
                 contracts: Mapping[str, ContractMarketFact],
                 funding: Mapping[str, Sequence[HistoricalFundingEvent]]) -> None:
        self.manifest_hash, self.start, self.end = manifest_hash, start, end
        self._contracts = dict(contracts)
        self._funding = {key: tuple(sorted(value, key=lambda item: item.funding_time))
                         for key, value in funding.items()}
        self._funding_times = {key: tuple(item.funding_time for item in value)
                               for key, value in self._funding.items()}

    @classmethod
    def load(cls, store: FileEvidenceStore, manifest_hash: str) -> "FrozenMarketFacts":
        store.verify(manifest_hash)
        envelope = store.get(manifest_hash)
        payload = envelope["payload"]
        if envelope["kind"] != "market_facts_manifest" or payload.get("integrity") != "COMPLETE":
            raise ValueError("research requires a COMPLETE market facts manifest")
        contracts: dict[str, ContractMarketFact] = {}
        funding: dict[str, list[HistoricalFundingEvent]] = {}
        seen_events: dict[tuple[str, int], HistoricalFundingEvent] = {}
        for ref in payload["contract_fact_refs"]:
            item = store.get(ref)
            if item["kind"] != "contract_market_fact":
                raise ValueError("market facts manifest references a non-contract fact")
            row = item["payload"]
            fact = ContractMarketFact(
                row["contract_code"], Decimal(row["contract_size"]), Decimal(row["price_tick"]),
                int(row["minimum_order_contracts"]),
                None if row.get("settlement_period_hours") is None else int(row["settlement_period_hours"]),
                None if row.get("contract_status") is None else int(row["contract_status"]),
                int(row["observed_at"]), ref,
            )
            contracts[fact.contract_code] = fact
        for ref in payload["funding_page_refs"]:
            item = store.get(ref)
            if item["kind"] != "historical_funding_page":
                raise ValueError("market facts manifest references a non-funding page")
            for row in item["payload"]["events"]:
                event = HistoricalFundingEvent(
                    row["contract_code"], int(row["funding_time"]), Decimal(row["funding_rate"]),
                    None if row.get("realized_rate") is None else Decimal(row["realized_rate"]),
                    Decimal(row["effective_rate"]), row["rate_source"], row.get("fee_asset") or "USDT",
                    row.get("funding_fee_type"), ref,
                )
                key = (event.contract_code, event.funding_time)
                previous = seen_events.get(key)
                comparable = lambda value: (value.funding_rate, value.realized_rate, value.effective_rate,
                                            value.rate_source, value.fee_asset, value.funding_fee_type)
                if previous is not None and comparable(previous) != comparable(event):
                    raise ValueError(f"conflicting funding revision in manifest: {key}")
                if previous is None:
                    seen_events[key] = event
                    funding.setdefault(event.contract_code, []).append(event)
        expected = set(payload["symbols"])
        if set(contracts) != expected or set(funding) != expected:
            raise ValueError("market facts manifest does not cover every symbol")
        return cls(manifest_hash, int(payload["start"]), int(payload["end_exclusive"]), contracts, funding)

    def contract(self, contract_code: str) -> ContractMarketFact:
        try:
            return self._contracts[contract_code]
        except KeyError as error:
            raise ValueError(f"contract fact unavailable for {contract_code}") from error

    def events(self, contract_code: str) -> tuple[HistoricalFundingEvent, ...]:
        try:
            return self._funding[contract_code]
        except KeyError as error:
            raise ValueError(f"funding history unavailable for {contract_code}") from error

    def rate_at(self, contract_code: str, as_of: int) -> HistoricalFundingEvent:
        rows = self.events(contract_code)
        index = bisect_right(self._funding_times[contract_code], as_of) - 1
        if index < 0:
            raise ValueError(f"no observed funding event at {contract_code}:{as_of}")
        return rows[index]

    def charge(self, contract_code: str, side: TrendSide, *, quantity: int,
               entry_time: int, exit_time: int, candles: Sequence[Candle], period: str) -> FundingCharge:
        if not self.start <= entry_time <= exit_time < self.end:
            return FundingCharge(None, 0, (), "UNKNOWN_OUTSIDE_MANIFEST")
        if quantity < 1:
            raise ValueError("funding charge quantity must be positive")
        contract = self.contract(contract_code)
        rows = self.events(contract_code)
        times = self._funding_times[contract_code]
        selected = rows[bisect_right(times, entry_time):bisect_right(times, exit_time)]
        if not selected:
            return FundingCharge(ZERO, 0, (), "COMPLETE")
        ordered = tuple(sorted(candles, key=lambda item: item.open_time))
        close_times = [item.open_time + period_seconds(period) for item in ordered]
        total = ZERO
        for event in selected:
            candle_index = bisect_right(close_times, event.funding_time) - 1
            if candle_index < 0:
                return FundingCharge(None, 0, (), "UNKNOWN_MARK_PRICE")
            notional = ordered[candle_index].close * contract.contract_size * Decimal(quantity)
            signed_rate = event.effective_rate if side is TrendSide.LONG else -event.effective_rate
            total += notional * signed_rate
        return FundingCharge(total, len(selected), tuple(item.funding_time for item in selected), "COMPLETE")


class MarketFactsCollector:
    """Collect public HTX facts without assuming a funding cadence or rate."""

    def __init__(self, client, store: FileEvidenceStore, *, page_size: int = 50,
                 clock: Callable[[], float] | None = None) -> None:
        import time
        if not 1 <= page_size <= 50:
            raise ValueError("HTX funding page size must be in [1,50]")
        self.client, self.store, self.page_size = client, store, page_size
        self.clock = clock or time.time

    def collect(self, symbols: tuple[str, ...], *, start: int, end: int) -> str:
        if not symbols or len(symbols) != len(set(symbols)) or start >= end:
            raise ValueError("unique symbols and a positive interval are required")
        if end > int(self.clock()):
            raise ValueError("market facts interval cannot extend into the future")
        contract_refs, funding_refs, failures = [], [], []
        for symbol in symbols:
            try:
                contract_refs.append(self._contract_fact(symbol))
                refs, complete = self._funding_pages(symbol, start, end)
                funding_refs.extend(refs)
                if not complete:
                    failures.append({"symbol": symbol, "reason": "FUNDING_BOUNDARY_GAP"})
            except Exception as error:
                failures.append({"symbol": symbol, "reason": type(error).__name__})
        refs = tuple(contract_refs + funding_refs)
        return self.store.put("market_facts_manifest", {
            "schema": "htx-python-market-facts-v1", "exchange": "HTX",
            "market_type": "USDT_PERPETUAL", "symbols": symbols,
            "start": start, "end_exclusive": end,
            "observed_at": int(self.clock()),
            "integrity": "PARTIAL" if failures else "COMPLETE", "failures": failures,
            "contract_fact_refs": contract_refs, "funding_page_refs": funding_refs,
            "funding_selection": "REALIZED_RATE_WHEN_PRESENT_ELSE_REPORTED_HISTORICAL_RATE",
            "funding_cadence": "OBSERVED_EVENT_TIMESTAMPS_ONLY",
            "contract_version": "POINT_IN_TIME_OBSERVATION; HTX_HAS_NO_HISTORICAL_SPEC_ENDPOINT",
        }, refs=refs)

    def _contract_fact(self, symbol: str) -> str:
        observed = int(self.clock())
        contract_envelope = self.client.contract_info_envelope(symbol)
        elements_envelope = self.client.contract_elements_envelope(symbol)
        raw_contract = self.store.put("raw_contract_info", {
            "endpoint": CONTRACT_ENDPOINT, "request": {"contract_code": symbol, "business_type": "swap"},
            "observed_at": observed, "response": contract_envelope,
        })
        raw_elements = self.store.put("raw_contract_elements", {
            "endpoint": ELEMENTS_ENDPOINT, "request": {"contract_code": symbol},
            "observed_at": observed, "response": elements_envelope,
        })
        contracts, elements = contract_envelope.get("data"), elements_envelope.get("data")
        if not isinstance(contracts, list) or len(contracts) != 1 or not isinstance(elements, list) or not elements:
            raise ValueError(f"HTX contract facts unavailable for {symbol}")
        contract = contracts[0]
        element = next((item for item in elements if item.get("contract_code") == symbol), elements[0])
        size = contract.get("contract_size", element.get("instrument_value"))
        tick = contract.get("price_tick", element.get("price_tick"))
        if size is None or tick is None or Decimal(str(size)) <= ZERO or Decimal(str(tick)) <= ZERO:
            raise ValueError(f"HTX contract size/tick invalid for {symbol}")
        return self.store.put("contract_market_fact", {
            "contract_code": symbol, "contract_size": Decimal(str(size)), "price_tick": Decimal(str(tick)),
            "minimum_order_contracts": 1,
            "minimum_order_source": "HTX_ORDER_VOLUME_POSITIVE_INTEGER_CONTRACT",
            "settlement_period_hours": element.get("settle_period", contract.get("settlement_period")),
            "contract_status": contract.get("contract_status"), "observed_at": observed,
            "source_endpoints": (CONTRACT_ENDPOINT, ELEMENTS_ENDPOINT),
        }, refs=(raw_contract, raw_elements))

    def _funding_pages(self, symbol: str, start: int, end: int) -> tuple[list[str], bool]:
        refs: list[str] = []
        earliest = latest = None
        page = 1
        while True:
            observed = int(self.clock())
            envelope = self.client.historical_funding_rate_envelope(
                symbol, page_index=page, page_size=self.page_size)
            raw_ref = self.store.put("raw_historical_funding", {
                "endpoint": FUNDING_ENDPOINT,
                "request": {"contract_code": symbol, "page_index": page, "page_size": self.page_size},
                "observed_at": observed, "response": envelope,
            })
            data = envelope.get("data")
            rows = data.get("data") if isinstance(data, dict) else None
            if not isinstance(rows, list) or not rows:
                raise ValueError(f"HTX historical funding page empty for {symbol}:{page}")
            events = []
            for row in rows:
                if row.get("funding_time") is None or row.get("funding_rate") is None:
                    raise ValueError(f"HTX historical funding row incomplete for {symbol}:{page}")
                timestamp = _epoch_seconds(row["funding_time"])
                realized = row.get("realized_rate")
                source = "REALIZED_RATE" if realized is not None else "FUNDING_RATE"
                effective = realized if realized is not None else row["funding_rate"]
                earliest = timestamp if earliest is None else min(earliest, timestamp)
                latest = timestamp if latest is None else max(latest, timestamp)
                if start <= timestamp < end:
                    events.append({
                        "contract_code": str(row.get("contract_code") or symbol),
                        "funding_time": timestamp, "funding_rate": Decimal(str(row["funding_rate"])),
                        "realized_rate": None if realized is None else Decimal(str(realized)),
                        "effective_rate": Decimal(str(effective)), "rate_source": source,
                        "fee_asset": row.get("fee_asset") or "USDT",
                        "funding_fee_type": row.get("funding_fee_type"),
                    })
            refs.append(self.store.put("historical_funding_page", {
                "contract_code": symbol, "page_index": page, "observed_at": observed,
                "events": sorted(events, key=lambda item: item["funding_time"]),
                "raw_ref": raw_ref,
            }, refs=(raw_ref,)))
            total_pages = int(data.get("total_page") or page)
            if earliest is not None and earliest <= start:
                break
            if page >= total_pages:
                break
            page += 1
        # Pages are read from the newest record backwards. Once the oldest row
        # reaches the lower bound, the public history covers the requested
        # interval; there need not be a settlement event exactly at ``end``.
        return refs, bool(earliest is not None and earliest <= start and latest is not None)
