"""测试只存储已关闭的 K 线"""

import pandas as pd
import pytest
import tempfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

from htx_trend.research.data.immutable_storage import ImmutableKlineStorage


def test_append_batch_rejects_unclosed_bars_4hour():
    """测试 4h 未关闭的 bar 被拒绝"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        now = datetime.now(timezone.utc)
        # 4h timeframe: bar closes 4 hours after bar_time
        unclosed_bar_time = now - timedelta(hours=3)  # 只过去3小时，还没关闭
        
        df = pd.DataFrame([{
            "bar_time": unclosed_bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        with pytest.raises(ValueError, match="未关闭的 bar.*不应存储"):
            storage.append_batch(
                symbol="BTC-USDT",
                interval="4hour",
                df=df,
            )


def test_append_batch_rejects_unclosed_bars_1day():
    """测试 1d 未关闭的 bar 被拒绝"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        now = datetime.now(timezone.utc)
        # 1day timeframe: bar closes 24 hours after bar_time
        unclosed_bar_time = now - timedelta(hours=20)  # 只过去20小时，还没关闭
        
        df = pd.DataFrame([{
            "bar_time": unclosed_bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        with pytest.raises(ValueError, match="未关闭的 bar"):
            storage.append_batch(
                symbol="BTC-USDT",
                interval="1day",
                df=df,
            )


def test_append_batch_accepts_closed_bars():
    """测试已关闭的 bar 被接受"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        now = datetime.now(timezone.utc)
        closed_bar_time = now - timedelta(hours=5)  # 5小时前，已关闭
        
        df = pd.DataFrame([{
            "bar_time": closed_bar_time,
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.5,
            "volume": 1000.0,
        }])
        
        # 应该成功不抛异常
        batch = storage.append_batch(
            symbol="BTC-USDT",
            interval="4hour",
            df=df,
        )
        
        assert batch is not None
        assert batch.symbol == "BTC-USDT"


def test_append_batch_mixed_closed_unclosed_rejects():
    """测试混合包含未关闭 bar 的批次被拒绝"""
    with tempfile.TemporaryDirectory() as tmpdir:
        storage = ImmutableKlineStorage(Path(tmpdir))
        
        now = datetime.now(timezone.utc)
        
        df = pd.DataFrame([
            {
                "bar_time": now - timedelta(hours=12),  # 已关闭
                "open": 100.0,
                "high": 101.0,
                "low": 99.0,
                "close": 100.5,
                "volume": 1000.0,
            },
            {
                "bar_time": now - timedelta(hours=2),  # ❌ 未关闭
                "open": 101.0,
                "high": 102.0,
                "low": 100.0,
                "close": 101.5,
                "volume": 1100.0,
            }
        ])
        
        with pytest.raises(ValueError, match="未关闭的 bar"):
            storage.append_batch(
                symbol="ETH-USDT",
                interval="4hour",
                df=df,
            )
