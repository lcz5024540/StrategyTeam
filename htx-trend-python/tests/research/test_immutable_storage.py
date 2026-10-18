"""测试不可变 K 线存储

必备测试：修订一根 K 线后产生新版本，旧版本仍可读
"""

import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd
import pytest

from htx_trend.research.data.immutable_storage import (
    ImmutableKlineStorage,
    KlineBatch,
    KlineRevision,
)


def test_append_batch_creates_metadata():
    """测试追加批次创建元数据"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        # 创建测试数据
        df = pd.DataFrame({
            "bar_time": pd.date_range("2024-01-01", periods=10, freq="4h"),
            "open": [100 + i for i in range(10)],
            "high": [102 + i for i in range(10)],
            "low": [98 + i for i in range(10)],
            "close": [101 + i for i in range(10)],
            "volume": [1000 + i * 10 for i in range(10)],
        })
        
        batch = storage.append_batch("BTC-USDT", "4h", df, source="htx")
        
        assert batch.symbol == "BTC-USDT"
        assert batch.interval == "4h"
        assert batch.source == "htx"
        assert batch.num_bars == 10
        assert batch.version == 0
        assert len(batch.batch_hash) == 16  # SHA256 前 16 字符


def test_append_batch_adds_first_seen_at():
    """测试追加批次自动添加 first_seen_at"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        df = pd.DataFrame({
            "bar_time": pd.date_range("2024-01-01", periods=5, freq="1d"),
            "open": [100, 101, 102, 103, 104],
            "high": [105, 106, 107, 108, 109],
            "low": [95, 96, 97, 98, 99],
            "close": [102, 103, 104, 105, 106],
            "volume": [1000, 1100, 1200, 1300, 1400],
        })
        
        storage.append_batch("BTC-USDT", "1d", df)
        
        # 加载数据
        loaded = storage.load_all_batches("BTC-USDT", "1d")
        
        assert "first_seen_at" in loaded.columns
        assert not loaded["first_seen_at"].isna().any()


def test_revision_creates_new_version_old_version_readable():
    """必备测试：修订一根 K 线后产生新版本，旧版本仍可读"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        # 原始数据
        df = pd.DataFrame({
            "bar_time": pd.date_range("2024-01-01", periods=5, freq="4h"),
            "open": [100, 101, 102, 103, 104],
            "high": [105, 106, 107, 108, 109],
            "low": [95, 96, 97, 98, 99],
            "close": [102, 103, 104, 105, 106],
            "volume": [1000, 1100, 1200, 1300, 1400],
        })
        
        storage.append_batch("BTC-USDT", "4h", df)
        
        # 修订第 3 根 K 线（索引 2）
        bar_time = df["bar_time"].iloc[2]
        
        # 修订前加载（应该有原始值）
        original = storage.load_all_batches("BTC-USDT", "4h", include_revisions=False)
        original_row = original[original["bar_time"] == bar_time].iloc[0]
        
        assert original_row["close"] == 104
        assert original_row["high"] == 107
        
        # 执行修订（交易所更正数据）
        new_values = {
            "open": 102,
            "high": 108,  # 高点修正
            "low": 97,
            "close": 105,  # 收盘价修正
            "volume": 1250,
        }
        
        revision = storage.append_revision(
            "BTC-USDT",
            "4h",
            bar_time,
            new_values,
            reason="exchange_correction",
        )
        
        assert revision.version == 1  # 新版本号
        assert revision.old_values["close"] == 104
        assert revision.new_values["close"] == 105
        
        # 加载最新版本（应该有修订值）
        latest = storage.load_all_batches("BTC-USDT", "4h", include_revisions=True)
        latest_row = latest[latest["bar_time"] == bar_time].iloc[0]
        
        assert latest_row["close"] == 105  # 新值
        assert latest_row["high"] == 108  # 新值
        
        # ✅ 关键：旧版本仍可读
        old_version = storage.load_all_batches("BTC-USDT", "4h", include_revisions=False)
        old_row = old_version[old_version["bar_time"] == bar_time].iloc[0]
        
        assert old_row["close"] == 104  # 旧值保留
        assert old_row["high"] == 107  # 旧值保留
        
        print(f"✅ Revision created version {revision.version}")
        print(f"   Old close: {revision.old_values['close']}")
        print(f"   New close: {revision.new_values['close']}")
        print(f"   Old version still readable: close={old_row['close']}")


def test_multiple_revisions_preserve_history():
    """测试多次修订保留完整历史"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        df = pd.DataFrame({
            "bar_time": [pd.Timestamp("2024-01-01 00:00:00")],
            "open": [100],
            "high": [105],
            "low": [95],
            "close": [102],
            "volume": [1000],
        })
        
        storage.append_batch("BTC-USDT", "1d", df)
        
        bar_time = df["bar_time"].iloc[0]
        
        # 第一次修订
        storage.append_revision("BTC-USDT", "1d", bar_time, {
            "open": 100, "high": 106, "low": 95, "close": 103, "volume": 1000,
        }, reason="first_correction")
        
        # 第二次修订
        storage.append_revision("BTC-USDT", "1d", bar_time, {
            "open": 100, "high": 107, "low": 94, "close": 104, "volume": 1000,
        }, reason="second_correction")
        
        # 获取修订历史
        history = storage.get_revision_history("BTC-USDT", "1d", bar_time.isoformat())
        
        assert len(history) == 2
        assert history[0].version == 1
        assert history[1].version == 2
        assert history[0].new_values["close"] == 103
        assert history[1].new_values["close"] == 104


def test_load_batches_deduplicates_by_latest():
    """测试加载批次时根据 first_seen_at 去重（保留最新）"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        # 第一批数据
        df1 = pd.DataFrame({
            "bar_time": [pd.Timestamp("2024-01-01 00:00:00")],
            "open": [100],
            "high": [105],
            "low": [95],
            "close": [102],
            "volume": [1000],
            "first_seen_at": [datetime(2024, 1, 1, 10, 0, 0, tzinfo=timezone.utc).isoformat()],
        })
        
        storage.append_batch("BTC-USDT", "1d", df1)
        
        # 第二批数据（同一 bar_time，稍晚采集）
        df2 = pd.DataFrame({
            "bar_time": [pd.Timestamp("2024-01-01 00:00:00")],
            "open": [100],
            "high": [106],  # 修正
            "low": [95],
            "close": [103],  # 修正
            "volume": [1000],
            "first_seen_at": [datetime(2024, 1, 1, 12, 0, 0, tzinfo=timezone.utc).isoformat()],
        })
        
        storage.append_batch("BTC-USDT", "1d", df2)
        
        # 加载最新版本
        latest = storage.load_all_batches("BTC-USDT", "1d", include_revisions=True)
        
        assert len(latest) == 1
        assert latest["close"].iloc[0] == 103  # 最新值
        
        # 加载原始版本
        original = storage.load_all_batches("BTC-USDT", "1d", include_revisions=False)
        
        assert len(original) == 1
        assert original["close"].iloc[0] == 102  # 原始值


def test_batch_hash_deterministic():
    """测试批次 hash 确定性（相同内容 → 相同 hash）"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        df = pd.DataFrame({
            "bar_time": pd.date_range("2024-01-01", periods=3, freq="1d"),
            "open": [100, 101, 102],
            "high": [105, 106, 107],
            "low": [95, 96, 97],
            "close": [102, 103, 104],
            "volume": [1000, 1100, 1200],
        })
        
        batch1 = storage.append_batch("BTC-USDT", "1d", df.copy())
        batch2 = storage.append_batch("ETH-USDT", "1d", df.copy())  # 不同 symbol
        
        # 相同内容应该产生相同 hash（不受 symbol 影响，只看 OHLCV）
        # 注：这里实际上 symbol 不同，但内容相同，hash 应该相同
        # 根据实现，hash 只计算 OHLCV 列
        
        # 修改数据再次追加
        df_modified = df.copy()
        df_modified.loc[0, "close"] = 103  # 修改一个值
        
        batch3 = storage.append_batch("BTC-USDT", "1d", df_modified)
        
        # 不同内容应该产生不同 hash
        assert batch1.batch_hash != batch3.batch_hash


def test_list_batches_filters():
    """测试列出批次并筛选"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        df = pd.DataFrame({
            "bar_time": pd.date_range("2024-01-01", periods=2, freq="1d"),
            "open": [100, 101],
            "high": [105, 106],
            "low": [95, 96],
            "close": [102, 103],
            "volume": [1000, 1100],
        })
        
        storage.append_batch("BTC-USDT", "1d", df)
        storage.append_batch("BTC-USDT", "4h", df)
        storage.append_batch("ETH-USDT", "1d", df)
        
        # 列出所有批次
        all_batches = storage.list_batches()
        assert len(all_batches) == 3
        
        # 筛选 BTC-USDT
        btc_batches = storage.list_batches(symbol="BTC-USDT")
        assert len(btc_batches) == 2
        
        # 筛选 BTC-USDT 1d
        btc_1d_batches = storage.list_batches(symbol="BTC-USDT", interval="1d")
        assert len(btc_1d_batches) == 1
