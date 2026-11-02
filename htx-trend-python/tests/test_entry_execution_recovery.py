from dataclasses import replace
from decimal import Decimal as D
from unittest.mock import Mock
import pytest

from htx_trend.execution_service import EntryExecutionConfig, _complete_exit_facts
from htx_trend.continuation import LINKED, ContinuationConfig, reentry_gate
from htx_trend.market_data import Candle, OrderBook, TopOfBook, ContractSpec, FundingRate
from htx_trend.models import TrendSide
from htx_trend.strategy_context import MarketContext, MarketRegime
from test_execution_service import Repository, Executor, executor, candidate, full_admission


def test_explicit_limit_env_is_effective_and_market_conflicts_fail(monkeypatch):
    monkeypatch.setenv('HTX_TREND_ENTRY_ORDER_TYPE', 'limit')
    monkeypatch.delenv('HTX_TREND_MAKER_ENABLED', raising=False)
    cfg = EntryExecutionConfig.from_env()
    assert cfg.entry_order_type == 'limit' and cfg.maker_config.enabled
    assert cfg.maker_config.timeout_action.value == 'CANCEL' and cfg.maker_config.max_amends == 0
    monkeypatch.setenv('HTX_TREND_MAKER_ENABLED', 'false')
    with pytest.raises(ValueError, match='conflicts'):
        EntryExecutionConfig.from_env()


@pytest.mark.parametrize('state,filled,final', [('new','0','canceled'), ('partially_filled','1','partially_canceled'), ('new','0','filled')])
def test_expiry_reconciles_before_cancel_and_queries_again(state, filled, final):
    repo, api = Repository(), Executor()
    api.order = Mock(side_effect=[dict(order_id='99',state=state,trade_volume=filled,trade_avg_price='100',fee='0'),
                                 dict(order_id='99',state=final,trade_volume='2' if final=='filled' else filled,trade_avg_price='100',fee='0')])
    api.cancel = Mock(return_value={'order_id':'99'})
    ex = executor(repo,api)
    result = ex.reconcile(client_order_id='1',contract_code='BTC-USDT',position_id=7,leg='ENTRY',
        factor_snapshot_id=None, model='DONCHIAN_BREAKOUT',contract_size=D('.01'),
        entry_execution={'client_order_id':'1','cancel_at_epoch':1000},now_epoch=1001)
    assert result in ('CANCELLED','PARTIALLY_CANCELLED','FILLED')
    assert len([r for k,r in repo.calls if k=='atomic_fill']) == 2
    api.cancel.assert_called_once_with('BTC-USDT','99')


def test_cancel_unknown_keeps_pending_and_does_not_recreate_order():
    repo, api = Repository(), Executor()
    api.order = Mock(return_value=dict(order_id='99',state='partially_filled',trade_volume='1',trade_avg_price='100',fee='0'))
    api.cancel = Mock(side_effect=TimeoutError('unknown cancel'))
    with pytest.raises(TimeoutError):
        executor(repo,api).reconcile(client_order_id='1',contract_code='BTC-USDT',position_id=7,leg='ENTRY',
            factor_snapshot_id=None,model='DONCHIAN_BREAKOUT',contract_size=D('.01'),
            entry_execution={'client_order_id':'1','cancel_at_epoch':1000},now_epoch=1001)
    assert repo.calls[0][1]['status'] == 'PARTIALLY_FILLED'
    assert not any(k in ('release','order','place') for k,_ in repo.calls+api.calls)


def test_restart_reads_original_deadline_and_disabling_new_maker_does_not_disable_recovery(monkeypatch):
    repo, api = Repository(), Executor()
    row=dict(position_id=7,position_key='p',run_id='r',client_order_id='1',contract_code='BTC-USDT',leg='ENTRY',model_name='DONCHIAN_BREAKOUT',contract_size='.01')
    repo.pending_orders=lambda **_: [row]
    repo.exit_state=lambda _: {'entry_execution':{'client_order_id':'1','cancel_at_epoch':1000}}
    api.order=Mock(side_effect=[dict(order_id='99',state='new'), dict(order_id='99',state='canceled')])
    api.cancel=Mock(return_value={})
    monkeypatch.setattr('htx_trend.execution_service.time.time',lambda:1001)
    recovered=executor(repo,api)  # maker disabled in current config
    assert recovered.reconcile_pending()=={'1':'CANCELLED'}
    assert api.cancel.call_count==1 and not api.calls


def test_linked_entry_has_price_gate_and_no_time_cooldown(monkeypatch):
    monkeypatch.setenv('HTX_LIVE_CONTINUATION_VERSION',LINKED)
    monkeypatch.setattr('htx_trend.admission.runtime_guard', lambda *args: None)
    repo,api=Repository(),Executor()
    prior=dict(id=99,side='LONG',exit_reason='continuation-protection-confirmed',contract_code='BTC-USDT',updated_at='2099-01-01',realized_profit='-1',entry_fee='.1',close_fee='.1')
    repo.latest_done_trade=lambda **_: prior
    facts=dict(position_id=99,side='LONG',reason=prior['exit_reason'],protection='101',bar_high='101',bar_low='99',bar_time=8100)
    repo.exit_state=lambda _: {'complete_exit':facts}
    admission=full_admission(strategy_version='HTX_TREND_15M_V2')
    ex=executor(repo,api,strategy_name='HTX_TREND_15M_V2',admission=admission)
    args=dict(run_id='r',candidate=candidate(),period='15min',atr=D(2),ema_fast=D(101),ema_slow=D(99),adx=D(30),
        recent_candles=(Candle(9000,D(99),D(101),D(98),D(100)),),
        book=OrderBook(((D('99.99'),D(500)),),((D('100.01'),D(500)),)),
        contract=ContractSpec('BTC-USDT',D('.01'),D('.01'),1),funding=FundingRate('BTC-USDT',D('.0001')),
        market_context=MarketContext(MarketRegime.BROAD_UP,TrendSide.LONG,D('.8')),now_epoch=10000)
    result=ex.submit(**args)
    assert not result.accepted and result.gate.stage=='REENTRY_NEW_PRICE_REQUIRED'
    assert not api.calls
    repo.exit_state=lambda _: {}
    assert ex.submit(**args).gate.stage=='REENTRY_EVIDENCE_MISSING'
    # Lower verified recovery boundary; a prior loss with future timestamp must
    # not activate baseline cooldown in LINKED. Other existing gates still run.
    facts.update(protection='98',bar_high='99',bar_low='97')
    repo.exit_state=lambda _: {'complete_exit':facts}
    result=ex.submit(**args)
    assert result.accepted
    assert next(v for k,v in repo.calls if k=='entry_state')['continuation_version']==LINKED


def test_previous_exit_facts_cannot_be_borrowed_from_different_position_or_reason():
    repo=Repository();repo.exit_state=lambda _: {'complete_exit':{'position_id':1,'side':'LONG','reason':'continuation-protection-confirmed','protection':'100'}}
    row={'id':2,'side':'LONG','exit_reason':'continuation-protection-confirmed'}
    assert _complete_exit_facts(repo,row)=={'side':'LONG','reason':'continuation-protection-confirmed'}


def test_limit_env_plan_matches_request_and_state_failure_never_places(monkeypatch):
    from htx_trend.execution import ExecutionConfig
    monkeypatch.setattr('htx_trend.admission.runtime_guard', lambda *args: None)
    monkeypatch.setenv('HTX_TREND_ENTRY_ORDER_TYPE', 'limit')
    monkeypatch.setenv('HTX_TREND_MAKER_OFFSET_BPS', '0')
    repo, api = Repository(), Executor()
    ex = executor(repo, api, config=EntryExecutionConfig.from_env(),
        execution=ExecutionConfig(entry_plan_version='structure-cost-v1', min_notional=D(1)))
    args=dict(run_id='r',candidate=candidate(),period='15min',atr=D(2),ema_fast=D(101),ema_slow=D(99),adx=D(30),
        recent_candles=(Candle(9000,D(99),D(101),D(98),D(100)),),
        book=OrderBook(((D('99.99'),D(500)),),((D('100.01'),D(500)),)),
        contract=ContractSpec('BTC-USDT',D('.01'),D('.01'),1),funding=FundingRate('BTC-USDT',D('.0001')),
        market_context=MarketContext(MarketRegime.BROAD_UP,TrendSide.LONG,D('.8')),now_epoch=10000)
    result=ex.submit(**args)
    assert result.accepted
    request=next(v for k,v in api.calls if k=='place')
    assert request.type=='limit' and request.time_in_force=='post_only'
    assert D(request.price)==result.gate.price_plan.desired_entry==D('99.99')
    state=next(v for k,v in repo.calls if k=='entry_state')
    assert state['entry_execution']['cancel_at_epoch']-state['entry_execution']['submitted_epoch']==3600
    assert state['entry_plan_version']=='structure-cost-v1'
    repo.initialize_entry_state=Mock(side_effect=OSError('state unavailable'))
    api.calls.clear()
    result=ex.submit(**args)
    assert not result.accepted and not any(k=='place' for k,v in api.calls)
