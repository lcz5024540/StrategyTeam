"""LivePositionManager confirm-v1 exit path (not env parsing only)."""
from datetime import UTC, datetime
from decimal import Decimal as D

import pytest

from htx_trend.continuation import CONFIRM, ContinuationConfig, decide_continuation
from htx_trend.market_data import Candle, KlineWindow, OrderBook, FundingRate, period_seconds
from htx_trend.mysql_store import RuntimeInstance, TradeAccount
from htx_trend.position_manager import LivePositionManager, ExitConfig
from htx_trend.strategy_context import MarketContext
from htx_trend.exits import ExitAction, ExitPhase

T = int(datetime(2026, 1, 1, tzinfo=UTC).timestamp())


class Repo:
    def __init__(self):
        self.state = {}
        self.orders = []
        self.decisions = []
        self.pending = []

    def pending_orders(self, **_):
        return self.pending

    def exit_state(self, _):
        return dict(self.state)

    def save_exit_state(self, _, state, decision):
        self.state = dict(state)

    def record_exit_decision(self, **value):
        self.decisions.append(value)

    def record_order_intent(self, value):
        self.orders.append(value)

    def mark_order_accepted(self, **_):
        pass

    def mark_order_unknown(self, **_):
        pass

    def mark_position_status(self, **_):
        pass

    def _write(self, *_):
        pass


class Market:
    close = D('102')

    def order_book(self, _):
        return OrderBook(((self.close, D(100)),), ((self.close + D('.01'), D(100)),))

    def funding_rate(self, _):
        return FundingRate('BTC-USDT', D(0))

    def kline_window(self, code, period, size, **kwargs):
        step = period_seconds(period)
        end = kwargs['now_epoch'] // step * step - step
        return KlineWindow(
            code, period,
            tuple(Candle(end - (size - i) * step, D(100), D(101), D(99), D(100), turnover=D(1000))
                  for i in range(1, size + 1)),
            size,
        )


class Client:
    volume = 4

    def positions(self, _):
        return [{'position_side': 'long', 'volume': self.volume}]

    def swap_fee(self, _):
        return [{'taker_fee_rate': '.0004'}]


class Executor:
    def __init__(self):
        self.sent = []

    def place(self, request):
        self.sent.append(request)
        return {'order_id': 99}

    def cancel(self, *_):
        pass


def _row():
    return dict(
        id=1, position_key='p', account_id=7, contract_code='BTC-USDT', side='LONG',
        primary_model='EMA_MOMENTUM_CONTINUATION', remaining_contract_volume=4, entry_filled_contracts=4,
        entry_avg_price='100', contract_size='.01', entry_fee='.02', stop_price='95', invalidation_price='94',
        target_zone_low='120', target_zone_high='130', timeframe_profile='INTRADAY_15M',
        highest_price='110', lowest_price='99', profit_protection_price='103',
        trailing_armed_at=datetime.fromtimestamp(T, UTC).isoformat(),
        trailing_arm_reason='RISK_REWARD_MILESTONE', max_favorable_r='2',
    )


def test_live_manager_routes_confirm_v1_through_decide_continuation(monkeypatch):
    repo = Repo()
    repo.state = {'continuation_version': CONFIRM, 'continuation_breach_count': 1,
                  'continuation_last_bar': T + 900, 'highest': '110', 'lowest': '99', 'protection': '103',
                  'armed_at': datetime.fromtimestamp(T, UTC).isoformat(), 'arm_reason': 'RISK_REWARD_MILESTONE'}
    calls = []

    def tracked(input_, count):
        calls.append(count)
        from dataclasses import replace
        return replace(decide_continuation(input_, count),
                       action=ExitAction.FULL_CLOSE, phase=ExitPhase.REVERSAL_CONFIRMED,
                       target_remaining_contracts=0, reason='continuation-protection-confirmed')

    monkeypatch.setattr('htx_trend.position_manager.decide_continuation', tracked)
    live = Executor()
    inst = RuntimeInstance('live', 'TREND', 'HTX_TREND_15M_V2', 7, 'primary', 'LIVE', True, 'unit', None)
    mgr = LivePositionManager(
        repo, inst, TradeAccount(7, 'primary', 'PRIMARY', True, False, 'test'),
        Market(), Client(), live,
        continuation_config=ContinuationConfig(CONFIRM),
        config=ExitConfig(engine_enabled=True, engine_live=True),
    )
    mgr._manage(_row(), T + 2700, MarketContext())
    assert calls, 'confirm-v1 position must use decide_continuation, not decide_v4 alone'
    assert repo.decisions[-1]['engine_version'] == 'legacy'
    assert repo.decisions[-1]['suggested_action'] == ExitAction.FULL_CLOSE.value
    assert len(live.sent) == 1
