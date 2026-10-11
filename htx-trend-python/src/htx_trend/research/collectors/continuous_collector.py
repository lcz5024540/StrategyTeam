"""独立持续采集进程

特性：
1. 独立运行，不依赖策略
2. 断点续传（重启后自动恢复），状态按 (symbol, interval) 键控
3. 缺口检测与补齐（跨批次缺口会触发回填）
4. 未关闭 bar 在写入前过滤（存储层仍作兜底）
5. HTX 请求同时发送 from 与 to（经固定适配器）
6. 状态文件原子写入（temp + rename）
"""

from __future__ import annotations

import asyncio
import json
import os
import signal
import sys
import tempfile
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import pandas as pd

from ..adapters.htx.market_data import HtxMarketDataClient, Kline
from ..data.immutable_storage import (
    ImmutableKlineStorage,
    _is_bar_closed,
    _parse_interval_seconds,
)
from ..ops import log_event, LogComponent, LogLevel


INTERVAL_MAP = {
    "1m": "1min",
    "5m": "5min",
    "15m": "15min",
    "30m": "30min",
    "1h": "60min",
    "4h": "4hour",
    "4hour": "4hour",
    "1d": "1day",
    "1day": "1day",
    "1w": "1week",
}


def _as_utc_datetime(value: datetime | pd.Timestamp | str) -> datetime:
    """Normalize bar timestamps to timezone-aware UTC datetimes."""
    if isinstance(value, str):
        value = pd.Timestamp(value)
    if isinstance(value, pd.Timestamp):
        value = value.to_pydatetime()
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _state_key(symbol: str, interval: str) -> str:
    return f"{symbol}::{interval}"


@dataclass
class CollectorState:
    """采集器状态"""
    symbol: str
    interval: str
    last_bar_time: str | None
    last_collection_at: str
    total_bars_collected: int
    gaps_detected: int
    gaps_filled: int


class ContinuousCollector:
    """持续 K 线采集器"""

    def __init__(
        self,
        storage_dir: Path,
        state_file: Path,
        symbols: list[str],
        interval: str = "4h",
        collection_interval_seconds: int = 300,
        intervals: list[str] | None = None,
        market_client: Any | None = None,
    ):
        self.storage_dir = storage_dir
        self.state_file = state_file
        self.symbols = symbols
        self.intervals = [str(item) for item in (intervals if intervals is not None else [interval])]
        self.interval = self.intervals[0]
        self.collection_interval = collection_interval_seconds
        self.market_client = market_client

        self.storage = ImmutableKlineStorage(storage_dir)
        self.running = False
        self.states: dict[str, CollectorState] = {}

        self._load_state()

    @classmethod
    def from_config(cls, config_path: Path) -> "ContinuousCollector":
        import yaml

        with open(config_path) as f:
            raw = yaml.safe_load(f) or {}
        cfg = raw.get("collector", raw)
        symbols = cfg.get("symbols")
        intervals = cfg.get("intervals")
        if not symbols:
            raise ValueError("Config missing 'collector.symbols'")
        if not intervals:
            raise ValueError("Config missing 'collector.intervals'")
        if "1day" not in intervals and "1d" not in intervals:
            raise ValueError(
                "Collector must support 1day interval. "
                "Add '1day' to collector.intervals in config."
            )
        storage_cfg = cfg.get("storage") or {}
        state_cfg = cfg.get("state") or {}
        return cls(
            storage_dir=Path(storage_cfg.get("base_path", "./data/klines")),
            state_file=Path(state_cfg.get("file_path", "./collector_state.json")),
            symbols=list(symbols),
            interval=str(intervals[0]),
            intervals=[str(item) for item in intervals],
            collection_interval_seconds=int(cfg.get("poll_interval_seconds", 300)),
        )

    def _empty_state(self, symbol: str, interval: str) -> CollectorState:
        return CollectorState(
            symbol=symbol,
            interval=interval,
            last_bar_time=None,
            last_collection_at=datetime.now(timezone.utc).isoformat(),
            total_bars_collected=0,
            gaps_detected=0,
            gaps_filled=0,
        )

    def _ensure_state(self, symbol: str, interval: str) -> CollectorState:
        key = _state_key(symbol, interval)
        if key not in self.states:
            self.states[key] = self._empty_state(symbol, interval)
        return self.states[key]

    def _load_state(self) -> None:
        if not self.state_file.exists():
            for symbol in self.symbols:
                for interval in self.intervals:
                    self.states[_state_key(symbol, interval)] = self._empty_state(
                        symbol, interval
                    )
            return

        try:
            with open(self.state_file) as f:
                data = json.load(f)
            for raw_key, state_data in data.items():
                if "::" in raw_key:
                    key = raw_key
                    state = CollectorState(**state_data)
                else:
                    interval = state_data.get("interval", self.interval)
                    symbol = state_data.get("symbol", raw_key)
                    state = CollectorState(**state_data)
                    key = _state_key(symbol, interval)
                self.states[key] = state
            for symbol in self.symbols:
                for interval in self.intervals:
                    self._ensure_state(symbol, interval)
            log_event(
                LogComponent.DATA,
                "Collector state loaded (resume after restart)",
                symbols=list(self.states.keys()),
            )
        except Exception as e:
            log_event(
                LogComponent.DATA,
                f"Failed to load collector state: {e}",
                level=LogLevel.ERROR,
            )
            for symbol in self.symbols:
                for interval in self.intervals:
                    self.states[_state_key(symbol, interval)] = self._empty_state(
                        symbol, interval
                    )

    def _save_state(self) -> None:
        """原子写入：临时文件 + os.replace。"""
        try:
            self.state_file.parent.mkdir(parents=True, exist_ok=True)
            data = {key: asdict(state) for key, state in self.states.items()}
            fd, tmp_path = tempfile.mkstemp(
                prefix=".collector_state.",
                suffix=".tmp",
                dir=str(self.state_file.parent),
            )
            try:
                with os.fdopen(fd, "w") as handle:
                    json.dump(data, handle, indent=2)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(tmp_path, self.state_file)
            except Exception:
                if os.path.exists(tmp_path):
                    os.unlink(tmp_path)
                raise
        except Exception as e:
            log_event(
                LogComponent.DATA,
                f"Failed to save collector state: {e}",
                level=LogLevel.ERROR,
            )

    def _closed_bars_only(self, df: pd.DataFrame, interval: str) -> pd.DataFrame:
        if df.empty:
            return df
        check_time = datetime.now(timezone.utc)
        mask = df["bar_time"].map(
            lambda value: _is_bar_closed(_as_utc_datetime(value), interval, check_time)
        )
        return df.loc[mask].reset_index(drop=True)

    def _klines_to_df(self, klines: Any) -> pd.DataFrame:
        if klines is None:
            return pd.DataFrame()
        if isinstance(klines, pd.DataFrame):
            return klines
        if not klines:
            return pd.DataFrame()
        rows = []
        for item in klines:
            if isinstance(item, Kline):
                rows.append(
                    {
                        "bar_time": item.bar_time,
                        "open": item.open,
                        "high": item.high,
                        "low": item.low,
                        "close": item.close,
                        "volume": item.volume,
                        "amount": item.amount,
                        "source": item.source,
                    }
                )
            elif isinstance(item, dict):
                rows.append(item)
            else:
                raise TypeError(f"Unsupported kline type: {type(item)}")
        df = pd.DataFrame(rows)
        df["bar_time"] = pd.to_datetime(df["bar_time"], utc=True)
        return df.sort_values("bar_time").reset_index(drop=True)

    def _fetch_klines(
        self,
        symbol: str,
        interval: str,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
        limit: int = 2000,
    ) -> pd.DataFrame:
        """Fetch via the fixed HTX adapter; always send from AND to."""
        del limit  # size is mutually exclusive with from/to
        owns_client = self.market_client is None
        client = self.market_client or HtxMarketDataClient()
        now = datetime.now(timezone.utc)
        start = start_time or (now - timedelta(days=90))
        end = end_time or now
        period = INTERVAL_MAP.get(interval, interval)
        try:
            klines = client.fetch_klines(
                contract_code=symbol,
                period=period,
                from_timestamp=int(_as_utc_datetime(start).timestamp()),
                to_timestamp=int(_as_utc_datetime(end).timestamp()),
            )
            return self._klines_to_df(klines)
        except Exception as e:
            log_event(
                LogComponent.DATA,
                f"Failed to fetch klines for {symbol} {interval}: {e}",
                level=LogLevel.ERROR,
            )
            return pd.DataFrame()
        finally:
            if owns_client and hasattr(client, "close"):
                client.close()

    async def _fetch_klines_async(
        self,
        symbol: str,
        interval: str,
        start_time: datetime | None = None,
        end_time: datetime | None = None,
        limit: int = 2000,
    ) -> pd.DataFrame:
        return self._fetch_klines(symbol, interval, start_time, end_time, limit)

    def _detect_gaps(
        self, df: pd.DataFrame, interval: str
    ) -> list[tuple[pd.Timestamp, pd.Timestamp]]:
        if len(df) < 2:
            return []
        expected_delta = timedelta(seconds=_parse_interval_seconds(interval))
        gaps = []
        for i in range(len(df) - 1):
            current_time = df["bar_time"].iloc[i]
            next_time = df["bar_time"].iloc[i + 1]
            if next_time - current_time > expected_delta * 1.5:
                gaps.append((current_time, next_time))
        return gaps

    async def _fill_gap(
        self,
        symbol: str,
        gap_start: pd.Timestamp,
        gap_end: pd.Timestamp,
        interval: str | None = None,
    ) -> int:
        interval = interval or self.interval
        log_event(
            LogComponent.DATA,
            f"Filling gap for {symbol} {interval}",
            gap_start=pd.Timestamp(gap_start).isoformat(),
            gap_end=pd.Timestamp(gap_end).isoformat(),
        )
        df = await self._fetch_klines_async(
            symbol,
            interval,
            start_time=_as_utc_datetime(gap_start),
            end_time=_as_utc_datetime(gap_end),
        )
        if df.empty:
            return 0
        df = df[(df["bar_time"] > gap_start) & (df["bar_time"] < gap_end)]
        df = self._closed_bars_only(df, interval)
        if df.empty:
            return 0
        batch = self.storage.append_batch(symbol, interval, df, source="htx")
        log_event(
            LogComponent.DATA,
            f"Gap filled for {symbol} {interval}",
            bars_filled=len(df),
            batch_id=batch.batch_id,
        )
        return len(df)

    def _load_resume_time(
        self, symbol: str, interval: str | None = None
    ) -> datetime | None:
        interval = interval or self.interval
        state = self.states.get(_state_key(symbol, interval))
        if state is None or not state.last_bar_time:
            return None
        last_bar_time = _as_utc_datetime(state.last_bar_time)
        return last_bar_time + timedelta(seconds=_parse_interval_seconds(interval))

    def _detect_cross_batch_gap(
        self,
        symbol: str,
        new_batch: pd.DataFrame,
        interval: str | None = None,
    ) -> list[dict]:
        interval = interval or self.interval
        if new_batch.empty:
            return []
        latest_bar_time = self.storage.get_latest_bar_time(symbol, interval)
        if latest_bar_time is None:
            return []
        latest_bar_time = _as_utc_datetime(latest_bar_time)
        first_new_bar_time = _as_utc_datetime(new_batch["bar_time"].iloc[0])
        interval_seconds = _parse_interval_seconds(interval)
        expected_next = latest_bar_time + timedelta(seconds=interval_seconds)
        tolerance = timedelta(seconds=interval_seconds * 0.1)
        if first_new_bar_time <= expected_next + tolerance:
            return []
        gap_seconds = (first_new_bar_time - expected_next).total_seconds()
        gap_event = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "symbol": symbol,
            "interval": interval,
            "last_stored_bar_time": latest_bar_time.isoformat(),
            "expected_next": expected_next.isoformat(),
            "actual_first_new": first_new_bar_time.isoformat(),
            "gap_hours": gap_seconds / 3600,
            "missing_bars": int(gap_seconds / interval_seconds),
        }
        gap_log_file = self.storage_dir / "gap_events.jsonl"
        try:
            with open(gap_log_file, "a") as handle:
                handle.write(json.dumps(gap_event) + "\n")
        except Exception as e:
            log_event(
                LogComponent.DATA,
                f"Failed to write gap event: {e}",
                level=LogLevel.ERROR,
            )
        log_event(
            LogComponent.DATA,
            "Cross-batch gap detected",
            level=LogLevel.WARNING,
            **gap_event,
        )
        return [gap_event]

    async def _collect_symbol(self, symbol: str, interval: str | None = None) -> None:
        interval = interval or self.interval
        state = self._ensure_state(symbol, interval)
        start_time = self._load_resume_time(symbol, interval)
        if start_time is None:
            start_time = datetime.now(timezone.utc) - timedelta(days=90)

        df = await self._fetch_klines_async(symbol, interval, start_time=start_time)
        if df.empty:
            log_event(
                LogComponent.DATA,
                f"No new data for {symbol} {interval}",
                last_bar_time=state.last_bar_time,
            )
            return

        cross_batch_gaps = self._detect_cross_batch_gap(symbol, df, interval)
        if cross_batch_gaps:
            state.gaps_detected += len(cross_batch_gaps)
            for gap in cross_batch_gaps:
                filled = await self._fill_gap(
                    symbol,
                    pd.Timestamp(gap["last_stored_bar_time"]),
                    pd.Timestamp(gap["actual_first_new"]),
                    interval=interval,
                )
                if filled > 0:
                    state.gaps_filled += 1

        gaps = self._detect_gaps(df, interval)
        if gaps:
            log_event(
                LogComponent.DATA,
                f"Detected {len(gaps)} gaps for {symbol} {interval}",
                gaps=[(g[0].isoformat(), g[1].isoformat()) for g in gaps],
                level=LogLevel.WARNING,
            )
            state.gaps_detected += len(gaps)
            for gap_start, gap_end in gaps:
                filled = await self._fill_gap(symbol, gap_start, gap_end, interval)
                if filled > 0:
                    state.gaps_filled += 1

        closed = self._closed_bars_only(df, interval)
        if closed.empty:
            log_event(
                LogComponent.DATA,
                f"All fetched bars still open for {symbol} {interval}",
            )
            return

        batch = self.storage.append_batch(symbol, interval, closed, source="htx")
        state.last_bar_time = closed["bar_time"].iloc[-1].isoformat()
        state.last_collection_at = datetime.now(timezone.utc).isoformat()
        state.total_bars_collected += len(closed)
        log_event(
            LogComponent.DATA,
            f"Collected {len(closed)} bars for {symbol} {interval}",
            batch_id=batch.batch_id,
            last_bar_time=state.last_bar_time,
            total_collected=state.total_bars_collected,
            filtered_unclosed=len(df) - len(closed),
        )

    async def collect_once(self) -> None:
        log_event(
            LogComponent.DATA,
            "Starting collection cycle",
            symbols=self.symbols,
            intervals=self.intervals,
        )
        for symbol in self.symbols:
            for interval in self.intervals:
                try:
                    await self._collect_symbol(symbol, interval)
                except Exception as e:
                    log_event(
                        LogComponent.DATA,
                        f"Error collecting {symbol} {interval}: {e}",
                        level=LogLevel.ERROR,
                    )
        self._save_state()

    async def run_forever(self) -> None:
        self.running = True
        log_event(
            LogComponent.DATA,
            "Continuous collector started",
            symbols=self.symbols,
            intervals=self.intervals,
            collection_interval_seconds=self.collection_interval,
        )
        while self.running:
            try:
                await self.collect_once()
            except Exception as e:
                log_event(
                    LogComponent.DATA,
                    f"Collection cycle error: {e}",
                    level=LogLevel.ERROR,
                )
            await asyncio.sleep(self.collection_interval)

    def stop(self) -> None:
        self.running = False
        self._save_state()
        log_event(LogComponent.DATA, "Continuous collector stopped")


async def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="HTX Continuous Kline Collector")
    parser.add_argument("--config", type=Path, help="Collector YAML config")
    parser.add_argument("--storage-dir", type=Path, help="Storage directory")
    parser.add_argument("--state-file", type=Path, help="State file path")
    parser.add_argument("--symbols", nargs="+", help="Symbols to collect")
    parser.add_argument("--interval", default="4h", help="Legacy single interval")
    parser.add_argument("--intervals", nargs="+", help="Intervals to collect")
    parser.add_argument("--collection-interval", type=int, default=300)
    args = parser.parse_args()

    if args.config:
        collector = ContinuousCollector.from_config(args.config)
    else:
        if not args.storage_dir or not args.state_file or not args.symbols:
            parser.error("--storage-dir, --state-file and --symbols are required without --config")
        collector = ContinuousCollector(
            storage_dir=args.storage_dir,
            state_file=args.state_file,
            symbols=args.symbols,
            interval=args.interval,
            intervals=args.intervals,
            collection_interval_seconds=args.collection_interval,
        )

    def signal_handler(sig, frame):
        print("\nStopping collector...")
        collector.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    await collector.run_forever()


if __name__ == "__main__":
    asyncio.run(main())
