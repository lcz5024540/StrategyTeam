"""测试修订检测：重新抓取时比较已存储版本，变化时追加修订"""

import pandas as pd
import pytest
import tempfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

from htx_trend.research.data.immutable_storage import ImmutableKlineStorage


def test_append_batch_detects_changed_close_price():
    """测试检测到收盘价变化并创建修订"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        bar_time = datetime(2026, 10, 10, 0, 0, tzinfo=timezone.utc)
        
        # 第一次存储
        df1 = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        batch1 = storage.append_batch(
            symbol="BTC-USDT",
            interval="4hour",
            df=df1,
        )
        
        assert batch1 is not None
        
        # 第二次抓取，收盘价改变
        df2 = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.8,  # ✅ 变化
            "volume": 1000.0,
        }])
        
        # 第二次 append 应该检测到变化并创建修订
        batch2 = storage.append_batch(
            symbol="BTC-USDT",
            interval="4hour",
            df=df2,
        )
        
        assert batch2 is not None
        
        # 检查修订日志文件存在
        revision_log = storage.storage_dir / "metadata" / "BTC-USDT_4hour_revisions.jsonl"
        assert revision_log.exists(), "修订日志文件应该被创建"
        
        # 读取日志
        with open(revision_log) as f:
            lines = f.readlines()
        
        assert len(lines) >= 1, "应该有至少一条修订记录"
        
        import json
        log_entry = json.loads(lines[0])
        assert log_entry["bar_time"] == bar_time.isoformat()
        assert "close" in log_entry["changed_fields"]
        assert log_entry["old_values"]["close"] == 100.5
        assert log_entry["new_values"]["close"] == 100.8


def test_append_batch_no_revision_if_identical():
    """测试数据相同时不创建修订"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        bar_time = datetime(2026, 10, 10, 4, 0, tzinfo=timezone.utc)
        
        df = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        storage.append_batch(
            symbol="BTC-USDT",
            interval="4hour",
            df=df,
        )
        
        # 第二次抓取，数据完全相同
        storage.append_batch(
            symbol="BTC-USDT",
            interval="4hour",
            df=df.copy(),
        )
        
        # 修订日志不应该存在（或为空）
        revision_log = storage.storage_dir / "metadata" / "BTC-USDT_4hour_revisions.jsonl"
        if revision_log.exists():
            with open(revision_log) as f:
                lines = f.readlines()
            assert len(lines) == 0, "相同数据不应创建修订"


def test_revision_detection_multiple_fields():
    """测试检测多个字段变化"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        bar_time = datetime(2026, 10, 10, 8, 0, tzinfo=timezone.utc)
        
        df1 = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        df2 = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.2,  # ✅ 变化
            "low": 99.0,
            "close": 100.7,  # ✅ 变化
            "volume": 1050.0,  # ✅ 变化
        }])
        
        storage.append_batch(symbol="BTC-USDT", interval="4hour", df=df1)
        storage.append_batch(symbol="BTC-USDT", interval="4hour", df=df2)
        
        # 检查修订日志
        revision_log = storage.storage_dir / "metadata" / "BTC-USDT_4hour_revisions.jsonl"
        assert revision_log.exists()
        
        with open(revision_log) as f:
            lines = f.readlines()
        
        import json
        log_entry = json.loads(lines[0])
        
        # 应该记录所有变化的字段
        changed = log_entry["changed_fields"]
        assert "high" in changed
        assert "close" in changed
        assert "volume" in changed


def test_revision_detection_tolerates_float_precision():
    """测试浮点精度容差（避免误报）"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        bar_time = datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc)
        
        df1 = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        # 第二次：微小的浮点误差（<1e-9，不应触发修订）
        df2 = pd.DataFrame([{
            "bar_time": bar_time,
            "open": 100.0,
            "high": 101.0 + 1e-10,  # 极小误差
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        storage.append_batch(symbol="BTC-USDT", interval="4hour", df=df1)
        storage.append_batch(symbol="BTC-USDT", interval="4hour", df=df2)
        
        # 不应创建修订
        revision_log = storage.storage_dir / "metadata" / "BTC-USDT_4hour_revisions.jsonl"
        if revision_log.exists():
            with open(revision_log) as f:
                lines = f.readlines()
            assert len(lines) == 0, "浮点精度误差不应触发修订"
