"""C02 止损只朝有利方向移动。"""

from __future__ import annotations

from datetime import datetime, timezone

import pandas as pd
import pytest

from htx_trend.research.conditions.base import DecisionContext
from htx_trend.research.conditions.c02_atr_stop import C02ATRStop


def _ctx(df: pd.DataFrame, idx: int, side: str, position_id: str, entry_price: float) -> DecisionContext:
    return DecisionContext(
        symbol="BTC-USDT",
        bar_time=pd.Timestamp(datetime.now(timezone.utc)),
        df=df,
        current_idx=idx,
        current_position={
            "entry_price": entry_price,
            "side": side,
            "position_id": position_id,
        },
    )


def test_c02_long_stop_only_moves_up():
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    position_id = "LONG_BTC"
    entry_price = 100.0
    initial_atr = 5.0
    initial_stop = entry_price - (initial_atr * 2.0)

    df = pd.DataFrame(
        [
            {"close": 100.0, "high": 101.0, "low": 99.0, "atr14": initial_atr},
            {"close": 110.0, "high": 111.0, "low": 109.0, "atr14": 3.0},
            {"close": 108.0, "high": 110.0, "low": 107.0, "atr14": 6.0},
        ]
    )

    c02.initialize_position_stop(position_id, initial_stop)
    c02.evaluate(_ctx(df, 0, "LONG", position_id, entry_price), {})
    assert c02._position_stops[position_id] == initial_stop

    new_stop = 110.0 - (3.0 * 2.0)
    c02.evaluate(_ctx(df, 1, "LONG", position_id, entry_price), {})
    assert c02._position_stops[position_id] == new_stop
    assert c02._position_stops[position_id] > initial_stop

    c02.evaluate(_ctx(df, 2, "LONG", position_id, entry_price), {})
    assert c02._position_stops[position_id] == new_stop
    assert c02._position_stops[position_id] > 108.0 - (6.0 * 2.0)


def test_c02_short_stop_only_moves_down():
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    position_id = "SHORT_BTC"
    entry_price = 100.0
    initial_atr = 5.0
    initial_stop = entry_price + (initial_atr * 2.0)

    df = pd.DataFrame(
        [
            {"close": 100.0, "high": 101.0, "low": 99.0, "atr14": initial_atr},
            {"close": 90.0, "high": 91.0, "low": 89.0, "atr14": 3.0},
            {"close": 95.0, "high": 96.0, "low": 94.0, "atr14": 6.0},
        ]
    )

    c02.initialize_position_stop(position_id, initial_stop)
    c02.evaluate(_ctx(df, 0, "SHORT", position_id, entry_price), {})
    assert c02._position_stops[position_id] == initial_stop

    new_stop = 90.0 + (3.0 * 2.0)
    c02.evaluate(_ctx(df, 1, "SHORT", position_id, entry_price), {})
    assert c02._position_stops[position_id] == new_stop
    assert c02._position_stops[position_id] < initial_stop

    c02.evaluate(_ctx(df, 2, "SHORT", position_id, entry_price), {})
    assert c02._position_stops[position_id] == new_stop
    assert c02._position_stops[position_id] < 95.0 + (6.0 * 2.0)


def test_c02_trigger_uses_prior_stop_not_same_bar_update():
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    position_id = "NOLOOK"
    df = pd.DataFrame(
        [
            {"close": 100.0, "high": 101.0, "low": 99.0, "atr14": 5.0},
            {"close": 120.0, "high": 121.0, "low": 80.0, "atr14": 5.0},
        ]
    )
    c02.initialize_position_stop(position_id, 90.0)
    result = c02.evaluate(_ctx(df, 1, "LONG", position_id, 100.0), {})
    assert result.status == "PASS"
    assert result.threshold == 90.0


def test_c02_stop_initialized_at_entry():
    c02 = C02ATRStop(atr_period=14, atr_multiplier=2.0, tick_size=0.1, stop_slippage_ticks=1)
    position_id = "NEW_POSITION"
    entry_price = 100.0
    atr = 5.0
    df = pd.DataFrame(
        [{"close": 100.0, "high": 101.0, "low": 99.0, "atr14": atr}]
    )
    assert position_id not in c02._position_stops
    with pytest.raises(ValueError, match="current bar ATR"):
        c02.evaluate(_ctx(df, 0, "LONG", position_id, entry_price), {})
    ctx = _ctx(df, 0, "LONG", position_id, entry_price)
    ctx.current_position["stop_loss"] = entry_price - (atr * 2.0)
    c02.evaluate(ctx, {})
    assert c02._position_stops[position_id] == entry_price - (atr * 2.0)
