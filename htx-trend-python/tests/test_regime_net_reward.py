"""DL-081: real cost calculation must gate A/B before sizing or any intent."""
from dataclasses import replace
from decimal import Decimal as D
import pytest

from htx_trend.execution import ExecutionConfig, ExecutionFacts
from htx_trend.market_data import OrderBook, FundingRate
from htx_trend.realtime.risk import evaluate
from test_regime import setup, NOW


def check(trigger, params, frame, event, fee, **configuration):
    facts=ExecutionFacts(actual_taker_fee_rate=D(fee),account_equity_usdt=D(777),
        available_margin_usdt=D(777),leverage=D(3),active_position_count=0,
        same_direction_position_count=0,reserved_margin_usdt=D(0),used_margin_usdt=D(0))
    return evaluate(trigger,now_ms=NOW+1,
        book=OrderBook(((event.bid,D(100000)),),((event.ask,D(100000)),)),
        contract=frame['contract'],funding=FundingRate('ETH-USDT',D(0)),facts=facts,
        instance_key='p',parameters=params,
        config=replace(ExecutionConfig(),min_average_turnover=D(0),account_equity_usdt=D(777),**configuration))


@pytest.mark.parametrize('scenario',['A','B'])
@pytest.mark.parametrize('mode',['ATR_1_1','ATR_2_1','ATR_2_2'])
def test_cost_floor_with_real_prices_both_sides_all_exits(tmp_path,scenario,mode):
    p,l,e,f,ev=setup(tmp_path,scenario,mode)
    try:
        t,=e.accept(ev,now_ms=NOW+1)
        rejected,intent=check(t,p,f,ev,'.004')
        assert not rejected.allowed and rejected.stage=='NET_REWARD' and intent is None
        assert rejected.price_plan.expected_net_reward<0
        accepted,intent=check(t,p,f,ev,'.0004')
        assert accepted.allowed and intent.price.expected_net_reward>0
        # Positive costs do not bypass the original independent capacity checks.
        rejected,intent=check(t,p,f,ev,'.0004',max_active_positions=0)
        assert not rejected.allowed and intent is None
    finally:l.close()


def test_zero_net_reward_is_rejected_with_exact_decimal_costs(tmp_path):
    p,l,e,f,ev=setup(tmp_path)
    try:
        t,=e.accept(ev,now_ms=NOW+1)
        # A synthetic exact-cost boundary: gross target 200, two fees 50+150.
        # This is not an exchange fee assumption or a proposed trading rule.
        t=replace(t,reference=D(100),raw_invalidation=D(99),invalidation_price=D(99),
                  plan=replace(t.plan,target=D(300)))
        ev=replace(ev,bid=D(100),ask=D(100))
        rejected,intent=check(t,p,f,ev,'.5',slippage_atr_fraction=D(0))
        assert rejected.price_plan.expected_net_reward==0
        assert rejected.stage=='NET_REWARD' and not rejected.allowed and intent is None
    finally:l.close()
