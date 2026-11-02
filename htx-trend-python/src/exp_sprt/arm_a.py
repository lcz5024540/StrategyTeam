"""Arm A research ledger: the 15m close that owns the structure plan."""
from __future__ import annotations

from htx_trend.market_data import Candle, completed_candles, period_seconds

from .schema import EXP_ID, money


def arm_a_for(plan: dict, candles: tuple[Candle, ...] | list[Candle], *, as_of_epoch: int) -> dict[str, object]:
    """Record the control trigger at the completed structure 15m close.

    The record is research bookkeeping. It does not call an execution path.
    """
    timeframe = str(plan["timeframe_structure"])
    completed = completed_candles(candles, timeframe, now_epoch=as_of_epoch, close_grace_seconds=0)
    if not completed:
        raise ValueError("arm A requires a completed 15m bar")
    signal = completed[-1]
    close_time = signal.open_time + period_seconds(timeframe)
    return {
        "exp_id": EXP_ID,
        "track": "探索",
        "phase": "phase-0",
        "description_only": True,
        "formal_m2_counted": False,
        "record_type": "arm_a_close_trigger",
        "plan_id": plan["plan_id"],
        "symbol": plan["symbol"],
        "timeframe": timeframe,
        "bar_open_time": signal.open_time,
        "bar_close_time": close_time,
        "trigger_price": money(signal.close),
        "plan_generated_at": plan["generated_at"],
        "orders_placed": 0,
        "research_ledger_only": True,
    }
