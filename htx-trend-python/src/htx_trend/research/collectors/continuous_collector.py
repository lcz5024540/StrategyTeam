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

from ..data.immutable_storage import ImmutableKlineStorage
from ..ops import log_event, LogComponent, LogLevel


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
        self.collection_interval = collection_interval_seconds
        
        self.storage = ImmutableKlineStorage(storage_dir)
        self.running = False
        self.states: dict[str, CollectorState] = {}
        
        # 加载状态
        self._load_state()
    
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
        
        # 计算期望的时间间隔
        interval_map = {
            "1m": timedelta(minutes=1),
            "5m": timedelta(minutes=5),
            "15m": timedelta(minutes=15),
            "30m": timedelta(minutes=30),
            "1h": timedelta(hours=1),
            "4h": timedelta(hours=4),
            "1d": timedelta(days=1),
            "1w": timedelta(weeks=1),
        }
        
        expected_delta = interval_map.get(self.interval, timedelta(hours=4))
        
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
    
    async def _collect_symbol(self, symbol: str) -> None:
        """采集单个交易对的数据"""
        state = self.states[symbol]
        
        # 获取最新数据
        start_time = None
        if state.last_bar_time:
            start_time = pd.Timestamp(state.last_bar_time) + timedelta(hours=4)  # 从上次之后开始
        
        df = await self._fetch_klines(symbol, start_time=start_time, limit=100)
        
        if df.empty:
            log_event(
                LogComponent.DATA,
                f"No new data for {symbol}",
                last_bar_time=state.last_bar_time,
            )
            return
        
        # 检测缺口
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
    parser.add_argument("--storage-dir", type=Path, required=True, help="Storage directory")
    parser.add_argument("--state-file", type=Path, required=True, help="State file path")
    parser.add_argument("--symbols", nargs="+", required=True, help="Symbols to collect")
    parser.add_argument("--interval", default="4h", help="Kline interval")
    parser.add_argument("--collection-interval", type=int, default=300, help="Collection interval (seconds)")
    
    args = parser.parse_args()
    
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
