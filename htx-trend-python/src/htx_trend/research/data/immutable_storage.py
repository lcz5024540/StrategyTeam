"""不可变 K 线存储 - 追加式批次写入，版本化管理

设计原则：
1. 只追加，不覆盖
2. 每批带内容 hash
3. bar_time（K 线时间）与 first_seen_at（首次采集时间）分离
4. 交易所修订的 K 线存为新版本，旧版本保持可读
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import pandas as pd


@dataclass
class KlineBatch:
    """K 线批次元数据"""
    batch_id: str  # 批次唯一 ID（时间戳）
    symbol: str
    interval: str
    source: str  # "htx" or "binance"
    batch_hash: str  # 批次内容 SHA256
    start_bar_time: str
    end_bar_time: str
    num_bars: int
    collected_at: str  # 采集时间（ISO format）
    version: int  # 同一 bar_time 的版本号（0 = 原始，1+ = 修订）


@dataclass
class KlineRevision:
    """K 线修订记录"""
    symbol: str
    interval: str
    bar_time: str
    version: int
    revised_at: str  # 修订时间
    reason: str  # 修订原因（如 "exchange_correction"）
    old_values: dict[str, Any]  # 旧值（OHLCV）
    new_values: dict[str, Any]  # 新值（OHLCV）


def _parse_interval_seconds(interval: str) -> int:
    """解析时间周期为秒数"""
    mapping = {
        "1min": 60,
        "5min": 300,
        "15min": 900,
        "30min": 1800,
        "1hour": 3600,
        "4hour": 14400,
        "1day": 86400,
    }
    if interval not in mapping:
        raise ValueError(f"未知时间周期: {interval}")
    return mapping[interval]


def _is_bar_closed(bar_time: datetime | pd.Timestamp, interval: str, check_time: datetime) -> bool:
    """判断 bar 是否已关闭
    
    Args:
        bar_time: K线开始时间
        interval: 时间周期
        check_time: 检查时间（通常是当前时间）
    
    Returns:
        True 如果 bar_time + interval <= check_time
    """
    if isinstance(bar_time, pd.Timestamp):
        bar_time = bar_time.to_pydatetime()
    
    interval_seconds = _parse_interval_seconds(interval)
    bar_close_time = bar_time + timedelta(seconds=interval_seconds)
    return bar_close_time <= check_time


class ImmutableKlineStorage:
    """不可变 K 线存储管理器"""
    
    def __init__(self, storage_dir: Path):
        """
        Args:
            storage_dir: 存储根目录
        """
        self.storage_dir = storage_dir
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        
        # 子目录结构
        self.batches_dir = storage_dir / "batches"
        self.metadata_dir = storage_dir / "metadata"
        self.revisions_dir = storage_dir / "revisions"
        
        for d in [self.batches_dir, self.metadata_dir, self.revisions_dir]:
            d.mkdir(parents=True, exist_ok=True)
    
    def append_batch(
        self,
        symbol: str,
        interval: str,
        df: pd.DataFrame,
        source: str = "htx",
    ) -> KlineBatch:
        """追加一批 K 线数据（仅存储已关闭的 bar）
        
        Args:
            symbol: 交易对（如 "BTC-USDT"）
            interval: 周期（如 "4h", "1d"）
            df: DataFrame，必须包含 bar_time, open, high, low, close, volume
                可选 first_seen_at（如无则使用当前时间）
            source: 数据源
        
        Returns:
            KlineBatch 元数据
        
        Raises:
            ValueError: 如果 DataFrame 缺少必需列，或包含未关闭的 bar
        """
        required_cols = ["bar_time", "open", "high", "low", "close", "volume"]
        missing = [c for c in required_cols if c not in df.columns]
        if missing:
            raise ValueError(f"DataFrame missing required columns: {missing}")
        
        # ✅ P0-1: 检查所有 bar 是否已关闭
        check_time = datetime.now(timezone.utc)
        for idx, row in df.iterrows():
            bar_time = row["bar_time"]
            if not _is_bar_closed(bar_time, interval, check_time):
                raise ValueError(
                    f"未关闭的 bar (bar_time={bar_time}, interval={interval}) "
                    f"不应存储为最终数据。请等待 bar 关闭后再写入。"
                )
        
        # 确保有 first_seen_at
        if "first_seen_at" not in df.columns:
            df = df.copy()
            df["first_seen_at"] = datetime.now(timezone.utc).isoformat()
        
        # 生成批次 ID（当前时间戳）
        batch_id = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
        
        # 计算批次内容 hash（不包含 first_seen_at，只用 OHLCV）
        content_cols = ["bar_time", "open", "high", "low", "close", "volume"]
        content_df = df[content_cols].copy()
        content_str = content_df.to_json(orient="records", date_format="iso")
        batch_hash = hashlib.sha256(content_str.encode()).hexdigest()[:16]
        
        # 保存批次数据（parquet）
        batch_file = self.batches_dir / f"{symbol}_{interval}_{batch_id}.parquet"
        df.to_parquet(batch_file, index=False)
        
        # 创建批次元数据
        batch_meta = KlineBatch(
            batch_id=batch_id,
            symbol=symbol,
            interval=interval,
            source=source,
            batch_hash=batch_hash,
            start_bar_time=df["bar_time"].iloc[0].isoformat() if isinstance(df["bar_time"].iloc[0], pd.Timestamp) else str(df["bar_time"].iloc[0]),
            end_bar_time=df["bar_time"].iloc[-1].isoformat() if isinstance(df["bar_time"].iloc[-1], pd.Timestamp) else str(df["bar_time"].iloc[-1]),
            num_bars=len(df),
            collected_at=datetime.now(timezone.utc).isoformat(),
            version=0,  # 原始版本
        )
        
        # 保存批次元数据
        meta_file = self.metadata_dir / f"{symbol}_{interval}_{batch_id}.json"
        with open(meta_file, "w") as f:
            json.dump(asdict(batch_meta), f, indent=2)
        
        return batch_meta
    
    def append_revision(
        self,
        symbol: str,
        interval: str,
        bar_time: pd.Timestamp | str,
        new_values: dict[str, Any],
        reason: str = "exchange_correction",
    ) -> KlineRevision:
        """追加 K 线修订版本（不覆盖旧版本）
        
        Args:
            symbol: 交易对
            interval: 周期
            bar_time: K 线时间
            new_values: 新的 OHLCV 值
            reason: 修订原因
        
        Returns:
            KlineRevision 记录
        """
        if isinstance(bar_time, pd.Timestamp):
            bar_time_str = bar_time.isoformat()
        else:
            bar_time_str = str(bar_time)
        
        # 查找最新版本
        latest_version, old_values = self._get_latest_version(symbol, interval, bar_time_str)
        
        # 创建新版本
        new_version = latest_version + 1
        
        # 创建修订记录
        revision = KlineRevision(
            symbol=symbol,
            interval=interval,
            bar_time=bar_time_str,
            version=new_version,
            revised_at=datetime.now(timezone.utc).isoformat(),
            reason=reason,
            old_values=old_values,
            new_values=new_values,
        )
        
        # 保存修订记录
        revision_file = self.revisions_dir / f"{symbol}_{interval}_{bar_time_str.replace(':', '-')}_{new_version}.json"
        with open(revision_file, "w") as f:
            json.dump(asdict(revision), f, indent=2)
        
        # 创建新版本的批次（单条记录）
        df = pd.DataFrame([{
            "bar_time": bar_time,
            "open": new_values["open"],
            "high": new_values["high"],
            "low": new_values["low"],
            "close": new_values["close"],
            "volume": new_values["volume"],
            "first_seen_at": datetime.now(timezone.utc).isoformat(),
        }])
        
        batch_id = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
        batch_file = self.batches_dir / f"{symbol}_{interval}_{batch_id}_v{new_version}.parquet"
        df.to_parquet(batch_file, index=False)
        
        # 保存版本元数据
        batch_meta = KlineBatch(
            batch_id=batch_id,
            symbol=symbol,
            interval=interval,
            source="revision",
            batch_hash=hashlib.sha256(json.dumps(new_values).encode()).hexdigest()[:16],
            start_bar_time=bar_time_str,
            end_bar_time=bar_time_str,
            num_bars=1,
            collected_at=datetime.now(timezone.utc).isoformat(),
            version=new_version,
        )
        
        meta_file = self.metadata_dir / f"{symbol}_{interval}_{batch_id}_v{new_version}.json"
        with open(meta_file, "w") as f:
            json.dump(asdict(batch_meta), f, indent=2)
        
        return revision
    
    def load_all_batches(
        self,
        symbol: str,
        interval: str,
        include_revisions: bool = True,
    ) -> pd.DataFrame:
        """加载所有批次并合并
        
        Args:
            symbol: 交易对
            interval: 周期
            include_revisions: 是否包含修订版本（True = 最新版本，False = 仅原始）
        
        Returns:
            合并后的 DataFrame
        """
        pattern = f"{symbol}_{interval}_*.parquet"
        batch_files = sorted(self.batches_dir.glob(pattern))
        
        if not batch_files:
            return pd.DataFrame()
        
        # 加载所有批次
        dfs = []
        for batch_file in batch_files:
            df = pd.read_parquet(batch_file)
            dfs.append(df)
        
        # 合并
        merged = pd.concat(dfs, ignore_index=True)
        
        if include_revisions:
            # 去重，保留最新版本（根据 first_seen_at）
            merged = merged.sort_values("first_seen_at").drop_duplicates(
                subset=["bar_time"], keep="last"
            )
        else:
            # 只保留首次采集的版本
            merged = merged.sort_values("first_seen_at").drop_duplicates(
                subset=["bar_time"], keep="first"
            )
        
        # 按 bar_time 排序
        merged = merged.sort_values("bar_time").reset_index(drop=True)
        
        return merged
    
    def get_revision_history(
        self,
        symbol: str,
        interval: str,
        bar_time: str,
    ) -> list[KlineRevision]:
        """获取某个 K 线的所有修订历史
        
        Args:
            symbol: 交易对
            interval: 周期
            bar_time: K 线时间
        
        Returns:
            修订记录列表（按版本升序）
        """
        pattern = f"{symbol}_{interval}_{bar_time.replace(':', '-')}_*.json"
        revision_files = sorted(self.revisions_dir.glob(pattern))
        
        revisions = []
        for rev_file in revision_files:
            with open(rev_file) as f:
                rev_data = json.load(f)
                revisions.append(KlineRevision(**rev_data))
        
        return sorted(revisions, key=lambda r: r.version)
    
    def _get_latest_version(
        self,
        symbol: str,
        interval: str,
        bar_time: str,
    ) -> tuple[int, dict[str, Any]]:
        """获取最新版本号和值
        
        Returns:
            (latest_version, old_values)
        """
        # 查找修订历史
        revisions = self.get_revision_history(symbol, interval, bar_time)
        
        if revisions:
            latest = revisions[-1]
            return (latest.version, latest.new_values)
        
        # 没有修订，查找原始批次
        df = self.load_all_batches(symbol, interval, include_revisions=False)
        
        if df.empty:
            return (0, {})
        
        # 查找指定 bar_time 的记录
        bar_df = df[df["bar_time"] == bar_time]
        
        if bar_df.empty:
            return (0, {})
        
        row = bar_df.iloc[0]
        old_values = {
            "open": float(row["open"]),
            "high": float(row["high"]),
            "low": float(row["low"]),
            "close": float(row["close"]),
            "volume": float(row["volume"]),
        }
        
        return (0, old_values)
    
    def list_batches(
        self,
        symbol: str | None = None,
        interval: str | None = None,
    ) -> list[KlineBatch]:
        """列出所有批次元数据
        
        Args:
            symbol: 可选，筛选交易对
            interval: 可选，筛选周期
        
        Returns:
            批次元数据列表
        """
        pattern = "*.json"
        if symbol and interval:
            pattern = f"{symbol}_{interval}_*.json"
        elif symbol:
            pattern = f"{symbol}_*.json"
        
        meta_files = sorted(self.metadata_dir.glob(pattern))
        
        batches = []
        for meta_file in meta_files:
            with open(meta_file) as f:
                meta_data = json.load(f)
                batches.append(KlineBatch(**meta_data))
        
        return batches
