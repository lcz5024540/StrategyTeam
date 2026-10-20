from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace

import pytest

from htx_trend.credentials import materialize
from htx_trend.execution import ExecutionConfig, ExecutionFacts, evaluate_gate
from htx_trend.exits import ExitAction, ExitPhase, decide
from htx_trend.live_approval import projected_margin, validate_margin_config
from htx_trend.models import ModelCode
from htx_trend.research.baseline import CONDITIONS
from htx_trend.research.store import FileEvidenceStore
from htx_trend.strategy_admission import DecisionSurface
from htx_trend.strategy_decision import HtxStrategyDecisionEngine
from htx_trend.strategy_profiles import STRATEGY_15M_V2, STRATEGY_4H_V2

from test_dual_profile_v2 import _candles
from test_exits import base
from test_strategy_admission import artifact
from test_position_manager import Repo, Market, manager, position
from test_mysql_store_platform import Connection, Cursor, instance
from htx_trend.mysql_store import HtxTrendMySqlRepository
from htx_trend.strategy_context import MarketContext


@pytest.mark.parametrize('version,period,seconds', [(STRATEGY_15M_V2, '15min', 900), (STRATEGY_4H_V2, '4hour', 14400)])
def test_v2_persists_replayable_full_trace(tmp_path, version, period, seconds):
    original = artifact(surfaces=(DecisionSurface.RANKING,))
    admission = replace(original, strategy_version=version, artifact_hash='').sealed()
    store = FileEvidenceStore(tmp_path)
    engine = HtxStrategyDecisionEngine(strategy_version=version, admission=admission, evidence_store=store)
    kwargs = dict(contract_code='BTC-USDT', period=period, candles=_candles(seconds), surface='RANKING')
    result = engine.decide(**kwargs)
    trace = store.get(result.trace_ref)['payload']
    assert trace['condition_results'] and trace['state_after'] and trace['state_before']
    conditions = {item['condition_id']: item for item in trace['condition_results']}
    assert 'entry.period_15m' not in conditions
    assert conditions['entry.profile_contract']['status'] == 'PASS'
    assert trace['strategy_spec_hash'] == result.strategy_spec_hash
    assert trace['context']['decision_id'] == result.decision_hash
    for name in ('timeframe_profile', 'strategy_spec_hash', 'feature_hash', 'condition_hash'):
        assert trace['context']['market'][name] == getattr(result, name)
    replay = engine.decide(**kwargs)
    assert replay.decision_hash == result.decision_hash and replay.trace_ref == result.trace_ref


@pytest.mark.parametrize('model', [ModelCode.DONCHIAN_BREAKOUT, ModelCode.EMA_MOMENTUM_CONTINUATION])
def test_all_primary_models_route_to_v4_in_kernel_and_manager(model):
    decision = decide(base(primary_model=model))
    assert decision.action is ExitAction.ARM_TRAILING
    assert decision.evidence['schema'] == 'trend-exit-decision-v4'
    repo = Repo()
    market = Market()
    market.price = D(100)
    manager(repo=repo, market=market)._manage({**position(), 'primary_model': model.value}, 144900, MarketContext())
    assert repo.decisions[-1]['engine_version'] == 'exit-engine-v4'
    assert repo.decisions[-1]['evidence']['schema'] == 'trend-exit-decision-v4'


def test_funding_rate_action_is_separate_from_settled_pnl_catalog():
    conditions = {item.condition_id: item for item in CONDITIONS}
    assert 'holding.funding_cost' not in conditions
    assert conditions['holding.funding_rate_exit'].usage.value == 'ACTION_TRIGGER'
    assert conditions['attribution.cumulative_funding_pnl'].usage.value == 'RECORD'
    decision = decide(base(funding_rate_exit_reason='current adverse rate above threshold'))
    assert decision.phase is ExitPhase.FUNDING_RATE
    assert decision.action is ExitAction.FULL_CLOSE


@pytest.mark.parametrize('config', [SimpleNamespace(), replace(ExecutionConfig(), account_equity_usdt=D(0)),
    replace(ExecutionConfig(), account_equity_usdt=D('NaN')), replace(ExecutionConfig(), max_total_margin_rate=None),
    replace(ExecutionConfig(), max_total_margin_rate=D('.11'))])
def test_live_margin_config_fails_closed(config):
    with pytest.raises(ValueError): validate_margin_config(config)


def test_margin_basis_is_thirty_margin_not_thirty_notional():
    assert validate_margin_config(ExecutionConfig()) == D(30)
    assert projected_margin(D(0), D(0), D(300), D(10)) == D(30)
    assert projected_margin(D(2), D(10), D(200), D(10)) > D(30)


@pytest.mark.parametrize('used,reserved', [(None, D(0)), (D(0), None), (D(29), D(5))])
def test_entry_gate_rejects_missing_or_excess_total_margin(used, reserved):
    from test_execution import candidate, inputs
    from htx_trend.strategy_context import FibonacciQuality
    facts = ExecutionFacts(actual_taker_fee_rate=D('.0004'), executable_depth_contracts=D(500),
        available_margin_usdt=D(100), account_equity_usdt=D(300), leverage=D(5),
        used_margin_usdt=used, reserved_margin_usdt=reserved, same_direction_position_count=0)
    config = ExecutionConfig(min_score=D(70), min_net_r=D(0), min_notional=D(1),
        target_notional=D(100), max_notional=D(100), require_execution_facts=True, hard_risk_limits_enabled=True)
    result = evaluate_gate(candidate(), fibonacci_quality=FibonacciQuality(D('1.2'),D('.5'),D('.1'),D(1),True),
        execution_facts=facts, **{**inputs(), 'config': config})
    assert not result.allowed and result.stage == 'RISK_LIMIT'
    assert 'margin' in result.reason


def test_plaintext_db_and_env_credentials_rejected_before_decryption(monkeypatch):
    record = SimpleNamespace(api_key_cipher='plain', secret_key_cipher='plain')
    with pytest.raises(RuntimeError, match='ENC'):
        materialize(record, require_encrypted=True)
    monkeypatch.setenv('HTX_API_KEY', 'plaintext-test')
    with pytest.raises(RuntimeError, match='environment'):
        materialize(SimpleNamespace(api_key_cipher='ENC(x)', secret_key_cipher='ENC(y)'), require_encrypted=True)


@pytest.mark.parametrize('reserved,accepted', [(D(10), True), (D(11), False)])
def test_atomic_reservation_enforces_margin_under_account_lock(reserved, accepted):
    class MarginCursor(Cursor):
        def fetchone(self):
            row = super().fetchone()
            if row is not None and 'risk_usdt' in row:
                row['margin_usdt'] = reserved
            return row
    class MarginConnection(Connection):
        def cursor(self): return MarginCursor(self)
    connection = MarginConnection()
    repository = HtxTrendMySqlRepository(connection)
    def reserve():
        repository.reserve_risk(reservation_key='g1', instance=instance('LIVE'), position_key='p',
            notional_usdt=D(200), risk_usdt=D(1), max_total_risk_usdt=D('4.5'), max_active_positions=3,
            margin_usdt=D(20), used_margin_usdt=D(0), max_total_margin_usdt=D(30), reason='test')
    if accepted: reserve()
    else:
        with pytest.raises(ValueError, match='margin'): reserve()
        assert connection.rollbacks == 1
        assert not any('INSERT' in query for query, _ in connection.queries)
    assert 'FOR UPDATE' in connection.queries[0][0]


def test_worker_startup_forces_encrypted_credentials(monkeypatch):
    from htx_trend.worker import _account_client
    repo = SimpleNamespace(account_credentials=lambda _: SimpleNamespace(api_key_cipher='plain', secret_key_cipher='plain'))
    with pytest.raises(RuntimeError, match='ENC'):
        _account_client(repo, 7, live=True)


def test_live_startup_rejects_invalid_configured_capital(monkeypatch):
    from htx_trend.worker import _validate_live_approval
    monkeypatch.setenv('HTX_TREND_ACCOUNT_EQUITY_USDT', '0')
    with pytest.raises(ValueError, match='equity'):
        _validate_live_approval(None, None, None, ())


def test_manager_exits_on_current_adverse_rate():
    from htx_trend.market_data import FundingRate
    repo, market = Repo(), Market()
    market.price = D(100)
    market.funding_rate = lambda _: FundingRate('BTC-USDT', D('.002'))
    manager(repo=repo, market=market)._manage(position(), 144900, MarketContext())
    assert repo.decisions[-1]['exit_phase'] == 'FUNDING_RATE'
    assert repo.orders


def test_direct_legacy_swing_submission_is_fail_closed(monkeypatch):
    from test_execution_service import Repository, Executor, executor, candidate
    from htx_trend.execution_service import EntryExecutionConfig
    from htx_trend.live_worker import LiveTrendWorkerConfig
    from htx_trend.market_data import ContractSpec
    monkeypatch.delenv('HTX_TREND_ALLOW_LEGACY_4H_LIVE', raising=False)
    assert not EntryExecutionConfig.from_env().allow_legacy_swing_live
    assert not LiveTrendWorkerConfig.from_env().allow_legacy_swing_live
    repo, live = Repository(), Executor()
    result = executor(repo, live).submit(run_id='blocked', candidate=candidate(), period='4hour',
        atr=D(2), ema_fast=D(101), ema_slow=D(99), adx=D(30), recent_candles=(), book=None,
        contract=ContractSpec('BTC-USDT', D('.01'), D('.1'), 1), funding=None,
        market_context=MarketContext(), timeframe_profile='SWING_4H')
    assert not result.accepted and result.gate.stage == 'LEGACY_4H_DISABLED'
    assert not repo.calls and not live.calls
