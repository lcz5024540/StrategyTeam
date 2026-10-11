"""独立持续采集进程

特性：
1. 独立运行，不依赖策略
2. 断点续传（重启后自动恢复）
3. 缺口检测与补齐
4. 状态持久化
"""

from __future__ import annotations

import asyncio
import json
import signal
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import httpx
import pandas as pd

from ..data.immutable_storage import ImmutableKlineStorage, _parse_interval_seconds
from ..ops import log_event, LogComponent, LogLevel


def _as_utc_datetime(value: datetime | pd.Timestamp | str) -> datetime:
    """Normalize bar timestamps to timezone-aware UTC datetimes."""
    if isinstance(value, str):
        value = pd.Timestamp(value)
    if isinstance(value, pd.Timestamp):
        value = value.to_pydatetime()
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


@dataclass
class CollectorState:
    """采集器状态"""
    symbol: str
    interval: str
    last_bar_time: str | None  # 最后采集的 K 线时间
    last_collection_at: str  # 最后采集时间
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
        collection_interval_seconds: int = 300,  # 5 分钟一次
    ):
        """
        Args:
            storage_dir: 不可变存储目录
            state_file: 状态文件路径（用于断点续传）
            symbols: 采集的交易对列表
            interval: K 线周期
            collection_interval_seconds: 采集间隔（秒）
        """
        self.storage_dir = storage_dir
        self.state_file = state_file
        self.symbols = symbols
        self.interval = interval
        self.intervals = [interval]
        self.collection_interval = collection_interval_seconds
        
        self.storage = ImmutableKlineStorage(storage_dir)
        self.running = False
        self.states: dict[str, CollectorState] = {}
        
        # 加载状态
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
        inst = cls(
            storage_dir=Path(storage_cfg.get("base_path", "./data/klines")),
            state_file=Path(state_cfg.get("file_path", "./collector_state.json")),
            symbols=list(symbols),
            interval=str(intervals[0]),
            collection_interval_seconds=int(cfg.get("poll_interval_seconds", 300)),
        )
        inst.intervals = [str(item) for item in intervals]
        return inst
    
    def _load_state(self) -> None:
        """从状态文件加载断点信息"""
        if not self.state_file.exists():
            # 初始化状态
            for symbol in self.symbols:
                self.states[symbol] = CollectorState(
                    symbol=symbol,
                    interval=self.interval,
                    last_bar_time=None,
                    last_collection_at=datetime.now(timezone.utc).isoformat(),
                    total_bars_collected=0,
                    gaps_detected=0,
                    gaps_filled=0,
                )
            return
        
        try:
            with open(self.state_file) as f:
                data = json.load(f)
                for symbol, state_data in data.items():
                    self.states[symbol] = CollectorState(**state_data)
            
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
            # 初始化空状态
            for symbol in self.symbols:
                self.states[symbol] = CollectorState(
                    symbol=symbol,
                    interval=self.interval,
                    last_bar_time=None,
                    last_collection_at=datetime.now(timezone.utc).isoformat(),
                    total_bars_collected=0,
                    gaps_detected=0,
                    gaps_filled=0,
                )
    
    def _save_state(self) -> None:
        """保存状态到文件（断点续传）"""
        try:
            self.state_file.parent.mkdir(parents=True, exist_ok=True)
            
            data = {symbol: asdict(state) for symbol, state in self.states.items()}
            
            with open(self.state_file, "w") as f:
                json.dump(data, f, indent=2)
        except Exception as e:
            log_event(
                LogComponent.DATA,
                f"Failed to save collector state: {e}",
                level=LogLevel.ERROR,
            )
    
    async def _fetch_klines(
        self,
        symbol: str,
        start_time: datetime | None = None,
        limit: int = 2000,
    ) -> pd.DataFrame:
        """从 HTX 获取 K 线数据
        
        Args:
            symbol: 交易对（如 "BTC-USDT"）
            start_time: 起始时间（可选）
            limit: 最大数量
        
        Returns:
            DataFrame with bar_time, open, high, low, close, volume
        """
        # 转换 symbol 格式：BTC-USDT → btcusdt
        htx_symbol = symbol.replace("-", "").lower()
        
        # 转换 interval 格式：4h → 4hour, 1d → 1day
        interval_map = {
            "1m": "1min", "5m": "5min", "15m": "15min", "30m": "30min",
            "1h": "60min", "4h": "4hour", "1d": "1day", "1w": "1week",
        }
        htx_interval = interval_map.get(self.interval, self.interval)
        
        url = "https://api.hbdm.com/linear-swap-ex/market/history/kline"
        
        params: dict[str, Any] = {
            "contract_code": htx_symbol.upper(),
            "period": htx_interval,
            "size": limit,
        }
        
        if start_time:
            params["from"] = int(start_time.timestamp())
        
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                response = await client.get(url, params=params)
                response.raise_for_status()
                data = response.json()
                
                if data.get("status") != "ok":
                    raise ValueError(f"HTX API error: {data}")
                
                klines = data.get("data", [])
                
                if not klines:
                    return pd.DataFrame()
                
                # 转换为 DataFrame
                df = pd.DataFrame(klines)
                df = df.rename(columns={
                    "id": "bar_time",
                    "vol": "volume",
                })
                
                # 转换时间戳
                df["bar_time"] = pd.to_datetime(df["bar_time"], unit="s", utc=True)
                
                # 选择需要的列
                df = df[["bar_time", "open", "high", "low", "close", "volume"]]
                
                # 排序
                df = df.sort_values("bar_time").reset_index(drop=True)
                
                return df
        
        except Exception as e:
            log_event(
                LogComponent.DATA,
                f"Failed to fetch klines for {symbol}: {e}",
                level=LogLevel.ERROR,
            )
            return pd.DataFrame()
    
    def _detect_gaps(self, df: pd.DataFrame) -> list[tuple[pd.Timestamp, pd.Timestamp]]:
        """检测 K 线缺口
        
        Args:
            df: K 线 DataFrame（已排序）
        
        Returns:
            缺口列表 [(gap_start, gap_end), ...]
        """
        if len(df) < 2:
            return []
        
        expected_delta = timedelta(seconds=_parse_interval_seconds(self.interval))
        
        gaps = []
        
        for i in range(len(df) - 1):
            current_time = df["bar_time"].iloc[i]
            next_time = df["bar_time"].iloc[i + 1]
            
            actual_delta = next_time - current_time
            
            # 如果实际间隔 > 期望间隔 * 1.5，认为有缺口
            if actual_delta > expected_delta * 1.5:
                gaps.append((current_time, next_time))
        
        return gaps
    
    async def _fill_gap(
        self,
        symbol: str,
        gap_start: pd.Timestamp,
        gap_end: pd.Timestamp,
    ) -> int:
        """填补缺口
        
        Args:
            symbol: 交易对
            gap_start: 缺口开始时间
            gap_end: 缺口结束时间
        
        Returns:
            填补的 K 线数量
        """
        log_event(
            LogComponent.DATA,
            f"Filling gap for {symbol}",
            gap_start=gap_start.isoformat(),
            gap_end=gap_end.isoformat(),
        )
        
        # 获取缺口期间的数据
        df = await self._fetch_klines(symbol, start_time=gap_start, limit=2000)
        
        if df.empty:
            return 0
        
        # 筛选缺口内的数据
        df = df[(df["bar_time"] > gap_start) & (df["bar_time"] < gap_end)]
        
        if df.empty:
            return 0
        
        # 追加到存储
        batch = self.storage.append_batch(symbol, self.interval, df, source="htx")
        
        log_event(
            LogComponent.DATA,
            f"Gap filled for {symbol}",
            bars_filled=len(df),
            batch_id=batch.batch_id,
        )
        
        return len(df)
    
    def _load_resume_time(self, symbol: str) -> datetime | None:
        """加载恢复时间（时间周期感知）
        
        逻辑:
        1. 读取状态文件中的 last_bar_time
        2. 根据 interval 计算下一个起始时间:
           - 4hour: last_bar_time + 4h
           - 1day: last_bar_time + 1d
        3. 如果无状态，返回 None（从90天前开始）
        
        Returns:
            下一个起始时间，或 None（如果无历史状态）
        """
        state = self.states.get(symbol)
        if not state or not state.last_bar_time:
            return None
        
        last_bar_time = _as_utc_datetime(state.last_bar_time)
        interval_seconds = _parse_interval_seconds(self.interval)
        next_start = last_bar_time + timedelta(seconds=interval_seconds)
        return next_start
    
    def _detect_cross_batch_gap(
        self,
        symbol: str,
        new_batch: pd.DataFrame,
    ) -> list[dict]:
        """检测跨批次间隙
        
        逻辑:
        1. 加载该 symbol+interval 的最后一个已存储 bar 的 bar_time
        2. 取新批次的第一个 bar_time
        3. 计算期望的下一个时间: last_stored + interval
        4. 如果 new_first_bar_time > expected_next + tolerance:
           - 记录到 gap_events.jsonl
           - 返回间隙详情
        
        Returns:
            间隙详情列表
        """
        if new_batch.empty:
            return []
        
        # 获取最后存储的 bar_time
        latest_bar_time = self.storage.get_latest_bar_time(symbol, self.interval)
        if latest_bar_time is None:
            # 首次采集，无历史数据
            return []
        
        latest_bar_time = _as_utc_datetime(latest_bar_time)
        first_new_bar_time = _as_utc_datetime(new_batch["bar_time"].iloc[0])
        
        interval_seconds = _parse_interval_seconds(self.interval)
        expected_next = latest_bar_time + timedelta(seconds=interval_seconds)
        
        # 容差：允许 10% 误差
        tolerance = timedelta(seconds=interval_seconds * 0.1)
        
        # 检测间隙
        if first_new_bar_time > expected_next + tolerance:
            gap_seconds = (first_new_bar_time - expected_next).total_seconds()
            missing_bars = int(gap_seconds / interval_seconds)
            
            gap_event = {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "symbol": symbol,
                "interval": self.interval,
                "last_stored_bar_time": latest_bar_time.isoformat(),
                "expected_next": expected_next.isoformat(),
                "actual_first_new": first_new_bar_time.isoformat(),
                "gap_hours": gap_seconds / 3600,
                "missing_bars": missing_bars,
            }
            
            gap_log_file = self.storage_dir / "gap_events.jsonl"
            try:
                with open(gap_log_file, "a") as f:
                    f.write(json.dumps(gap_event) + "\n")
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
        
        return []
    
    async def _collect_symbol(self, symbol: str) -> None:
        """采集单个交易对的数据"""
        state = self.states[symbol]
        
        # 时间周期感知的恢复
        start_time = self._load_resume_time(symbol)
        if start_time is None:
            # 首次采集，从90天前开始
            start_time = datetime.now(timezone.utc) - timedelta(days=90)
        
        df = await self._fetch_klines(symbol, start_time=start_time, limit=100)
        
        if df.empty:
            log_event(
                LogComponent.DATA,
                f"No new data for {symbol}",
                last_bar_time=state.last_bar_time,
            )
            return
        
        # 检测跨批次间隙（在检测批次内间隙之前）
        cross_batch_gaps = self._detect_cross_batch_gap(symbol, df)
        if cross_batch_gaps:
            state.gaps_detected += len(cross_batch_gaps)
        
        # 检测批次内缺口
        gaps = self._detect_gaps(df)
        
        if gaps:
            log_event(
                LogComponent.DATA,
                f"Detected {len(gaps)} gaps for {symbol}",
                gaps=[(g[0].isoformat(), g[1].isoformat()) for g in gaps],
                level=LogLevel.WARNING,
            )
            
            state.gaps_detected += len(gaps)
            
            # 填补缺口
            for gap_start, gap_end in gaps:
                filled = await self._fill_gap(symbol, gap_start, gap_end)
                if filled > 0:
                    state.gaps_filled += 1
        
        # 追加新数据
        batch = self.storage.append_batch(symbol, self.interval, df, source="htx")
        
        # 更新状态
        state.last_bar_time = df["bar_time"].iloc[-1].isoformat()
        state.last_collection_at = datetime.now(timezone.utc).isoformat()
        state.total_bars_collected += len(df)
        
        log_event(
            LogComponent.DATA,
            f"Collected {len(df)} bars for {symbol}",
            batch_id=batch.batch_id,
            last_bar_time=state.last_bar_time,
            total_collected=state.total_bars_collected,
        )
    
    async def collect_once(self) -> None:
        """执行一次采集（所有交易对）"""
        log_event(
            LogComponent.DATA,
            "Starting collection cycle",
            symbols=self.symbols,
            interval=self.interval,
        )
        
        for symbol in self.symbols:
            try:
                await self._collect_symbol(symbol)
            except Exception as e:
                log_event(
                    LogComponent.DATA,
                    f"Error collecting {symbol}: {e}",
                    level=LogLevel.ERROR,
                )
        
        # 保存状态
        self._save_state()
    
    async def run_forever(self) -> None:
        """持续运行采集器"""
        self.running = True
        
        log_event(
            LogComponent.DATA,
            "Continuous collector started",
            symbols=self.symbols,
            interval=self.interval,
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
            
            # 等待下一次采集
            await asyncio.sleep(self.collection_interval)
    
    def stop(self) -> None:
        """停止采集器"""
        self.running = False
        self._save_state()
        
        log_event(
            LogComponent.DATA,
            "Continuous collector stopped",
        )


async def main() -> None:
    """主入口（用于 systemd 服务）"""
    import argparse
    
    parser = argparse.ArgumentParser(description="HTX Continuous Kline Collector")
    parser.add_argument("--config", type=Path, help="Collector YAML config")
    parser.add_argument("--storage-dir", type=Path, help="Storage directory")
    parser.add_argument("--state-file", type=Path, help="State file path")
    parser.add_argument("--symbols", nargs="+", help="Symbols to collect")
    parser.add_argument("--interval", default="4h", help="Kline interval")
    parser.add_argument("--collection-interval", type=int, default=300, help="Collection interval (seconds)")
    
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
            collection_interval_seconds=args.collection_interval,
        )
    
    # 信号处理
    def signal_handler(sig, frame):
        print("\nStopping collector...")
        collector.stop()
        sys.exit(0)
    
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    # 运行
    await collector.run_forever()


if __name__ == "__main__":
    asyncio.run(main())
