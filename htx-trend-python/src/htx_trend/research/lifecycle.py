"""Deterministic next-event lifecycle simulator for frozen strategy evidence."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_DOWN
from math import ceil
from typing import Sequence

from ..market_data import Candle
from ..models import TrendSide
from .features import Performance, performance

ZERO = Decimal("0")


@dataclass(frozen=True, slots=True)
class ExecutionAssumptions:
    fee_rate: Decimal = Decimal("0.0004")
    slippage_rate: Decimal = Decimal("0.0003")
    funding_rate_per_bar: Decimal = Decimal("0.0001")
    max_volume_participation: Decimal = Decimal("0.01")
    conservative_intrabar_ordering: bool = True


@dataclass(frozen=True, slots=True)
class BacktestIntent:
    intent_id: str
    signal_index: int
    timeframe_profile: str
    model: str
    side: TrendSide
    quantity: int
    contract_size: Decimal
    stop_price: Decimal
    target_price: Decimal
    order_type: str = "MARKET"
    limit_price: Decimal | None = None


@dataclass(frozen=True, slots=True)
class BacktestTrade:
    intent_id: str
    timeframe_profile: str
    model: str
    side: str
    signal_time: int
    entry_time: int
    exit_time: int
    requested_quantity: int
    filled_quantity: int
    entry_price: Decimal
    exit_price: Decimal
    gross_pnl: Decimal
    fees: Decimal
    funding: Decimal
    net_pnl: Decimal
    mfe: Decimal
    mae: Decimal
    exit_reason: str
    partial_fill: bool


@dataclass(frozen=True, slots=True)
class LifecycleBacktestResult:
    trades: tuple[BacktestTrade, ...]
    missed_intents: tuple[str, ...]
    expired_intents: tuple[str, ...]
    metrics: Performance
    effective_days: int
    completed_trades_per_day: Decimal
    execution_assumptions: ExecutionAssumptions
    result_kind: str = "STRATEGY_SIMULATION"


class LifecycleBacktestRun:
    """Simulate intents without claiming that synthetic fills are historical facts."""

    def __init__(self, assumptions: ExecutionAssumptions = ExecutionAssumptions()) -> None:
        if not ZERO < assumptions.max_volume_participation <= Decimal("1"):
            raise ValueError("volume participation must be in (0,1]")
        self.assumptions = assumptions

    def run(self, candles: Sequence[Candle], intents: Sequence[BacktestIntent]) -> LifecycleBacktestResult:
        ordered = tuple(sorted(candles, key=lambda item: item.open_time))
        if len(ordered) < 2:
            raise ValueError("lifecycle backtest requires at least two candles")
        trades: list[BacktestTrade] = []
        missed: list[str] = []
        expired: list[str] = []
        for intent in intents:
            if intent.quantity < 1 or intent.contract_size <= ZERO:
                raise ValueError("intent quantity and contract size must be positive")
            entry_index = intent.signal_index + 1
            if intent.signal_index < 0 or entry_index >= len(ordered):
                expired.append(intent.intent_id)
                continue
            entry_candle = ordered[entry_index]
            entry_price = self._entry_price(intent, entry_candle)
            if entry_price is None:
                expired.append(intent.intent_id)
                continue
            capacity = self._capacity(entry_candle)
            filled = min(intent.quantity, capacity)
            if filled < 1:
                missed.append(intent.intent_id)
                continue
            trade = self._complete(intent, ordered, entry_index, entry_price, filled)
            trades.append(trade)
        first, last = ordered[0].open_time, ordered[-1].open_time
        days = max(1, ceil((last - first + 1) / 86_400))
        rows = tuple({"net_pnl": item.net_pnl, "fees": item.fees,
                      "mfe": item.mfe, "mae": item.mae} for item in trades)
        return LifecycleBacktestResult(
            tuple(trades), tuple(missed), tuple(expired), performance(rows), days,
            Decimal(len(trades)) / Decimal(days), self.assumptions,
        )

    def _entry_price(self, intent: BacktestIntent, candle: Candle) -> Decimal | None:
        if intent.order_type == "MARKET":
            multiplier = (Decimal("1") + self.assumptions.slippage_rate
                          if intent.side is TrendSide.LONG
                          else Decimal("1") - self.assumptions.slippage_rate)
            return candle.open * multiplier
        if intent.order_type != "LIMIT" or intent.limit_price is None:
            raise ValueError("order_type must be MARKET or a priced LIMIT")
        if candle.low <= intent.limit_price <= candle.high:
            return intent.limit_price
        return None

    def _capacity(self, candle: Candle) -> int:
        volume = candle.volume
        if volume is None or volume <= ZERO:
            return 0
        return int((volume * self.assumptions.max_volume_participation).to_integral_value(rounding=ROUND_DOWN))

    def _complete(self, intent: BacktestIntent, candles: tuple[Candle, ...], entry_index: int,
                  entry_price: Decimal, quantity: int) -> BacktestTrade:
        favorable = adverse = ZERO
        exit_price, exit_time, reason = candles[-1].close, candles[-1].open_time, "END_OF_DATA"
        bars_held = 0
        for candle in candles[entry_index:]:
            bars_held += 1
            if intent.side is TrendSide.LONG:
                favorable = max(favorable, candle.high - entry_price)
                adverse = max(adverse, entry_price - candle.low)
                stop_hit, target_hit = candle.low <= intent.stop_price, candle.high >= intent.target_price
            else:
                favorable = max(favorable, entry_price - candle.low)
                adverse = max(adverse, candle.high - entry_price)
                stop_hit, target_hit = candle.high >= intent.stop_price, candle.low <= intent.target_price
            if stop_hit:
                exit_price = self._stop_fill(intent.side, candle.open, intent.stop_price)
                exit_time, reason = candle.open_time, "STOP"
                break
            if target_hit:
                exit_price, exit_time, reason = intent.target_price, candle.open_time, "TARGET"
                break
        multiplier = intent.contract_size * Decimal(quantity)
        gross = ((exit_price - entry_price) if intent.side is TrendSide.LONG
                 else (entry_price - exit_price)) * multiplier
        fees = (entry_price + exit_price) * multiplier * self.assumptions.fee_rate
        funding = entry_price * multiplier * self.assumptions.funding_rate_per_bar * Decimal(bars_held)
        return BacktestTrade(
            intent.intent_id, intent.timeframe_profile, intent.model, intent.side.value,
            candles[intent.signal_index].open_time, candles[entry_index].open_time, exit_time,
            intent.quantity, quantity, entry_price, exit_price, gross, fees, funding,
            gross - fees - funding, favorable * multiplier, adverse * multiplier, reason,
            quantity < intent.quantity,
        )

    @staticmethod
    def _stop_fill(side: TrendSide, candle_open: Decimal, stop: Decimal) -> Decimal:
        if side is TrendSide.LONG:
            return min(candle_open, stop)
        return max(candle_open, stop)
