"""Bounded-concurrency public-market scanner for the Python paper runtime."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Iterable
import uuid

from .evidence import CaptureRequest, RuntimeKlineArchive
from .market_data import HtxMarketData
from .runtime import PaperTrendRuntime, ScanOutcome


@dataclass(frozen=True, slots=True)
class ScannerConfig:
    entry_period: str = "15min"
    trend_period: str = "60min"
    kline_size: int = 160
    max_concurrency: int = 6


class PaperTrendScanner:
    """Runs public data scans without private keys or live execution authority."""
    def __init__(self, market: HtxMarketData, runtime: PaperTrendRuntime, archive: RuntimeKlineArchive,
                 config: ScannerConfig = ScannerConfig()) -> None:
        self._market, self._runtime, self._archive, self._config = market, runtime, archive, config

    def run_once(self, contracts: Iterable[str], *, now: datetime | None = None) -> dict[str, ScanOutcome | Exception]:
        at = now or datetime.now(UTC)
        run_id = f"paper-{uuid.uuid4()}"
        values = tuple(dict.fromkeys(contracts))
        results: dict[str, ScanOutcome | Exception] = {}
        with ThreadPoolExecutor(max_workers=max(1, self._config.max_concurrency), thread_name_prefix="htx-paper-scan") as executor:
            futures = {executor.submit(self._scan_one, run_id, contract, at): contract for contract in values}
            for future in as_completed(futures):
                contract = futures[future]
                try: results[contract] = future.result()
                except Exception as error: results[contract] = error
        return results

    def _scan_one(self, run_id: str, contract: str, at: datetime) -> ScanOutcome:
        now_epoch = int(at.timestamp())
        entry = self._market.kline_window(contract, self._config.entry_period, self._config.kline_size, now_epoch=now_epoch)
        trend = self._market.kline_window(contract, self._config.trend_period, self._config.kline_size, now_epoch=now_epoch)
        self._archive.capture(CaptureRequest("ENTRY_SCAN", entry, run_id=run_id))
        self._archive.capture(CaptureRequest("TREND_CONFIRMATION", trend, run_id=run_id))
        return self._runtime.scan(run_id=run_id, entry_window=entry, trend_window=trend, now_epoch=now_epoch, at=at,
                                  book=self._market.top_of_book(contract), contract=self._market.contract_spec(contract),
                                  funding=self._market.funding_rate(contract))
