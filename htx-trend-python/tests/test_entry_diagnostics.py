from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal as D
from hashlib import sha256
import json
import random
from types import SimpleNamespace as NS
from unittest.mock import Mock

import pytest

from htx_trend.diagnostics import RejectCode as R, heartbeat_detail
from htx_trend.java_entry_contract import JavaEntryConfig, candidates, evaluate, explain_candidates
from htx_trend.live_worker import LiveTrendWorker, LiveTrendWorkerConfig
from htx_trend.models import ModelCode, TrendSide
from htx_trend.mysql_store import HtxTrendMySqlRepository, RuntimeInstance
from htx_trend.policy import explain_policy, select_policy
from htx_trend.strategy_context import MarketContext
from htx_trend.strategy_decision import HtxStrategyDecisionEngine
from htx_trend.strategy_admission import (DecisionSurface, ResearchStage, StageEvidence, StageStatus, build_artifact)
from htx_trend.timeframes import TimeframeProfile
from test_java_entry_contract import _uptrend, _downtrend, _flat
from test_policy import candidate, policy


@pytest.mark.parametrize('period', ['15min', '4hour', '30min'])
@pytest.mark.parametrize('bars', [_uptrend(), _downtrend(), _flat(), ()])
def test_explain_count_matches_unchanged_candidates(bars, period):
    signal = evaluate(bars)
    explanation = explain_candidates(bars, signal, period=period)
    assert explanation.candidate_count == len(candidates(bars, signal, period=period))
    if period == '30min':
        assert explanation.code == R.TIMEFRAME_UNSUPPORTED
    else:
        assert explanation.code != R.TIMEFRAME_UNSUPPORTED


def test_signal_regime_and_structure_reasons():
    bars = _uptrend()
    signal = evaluate(bars)
    assert explain_candidates((), evaluate(())).code == R.INSUFFICIENT_BARS
    assert explain_candidates(bars[:2], signal).code == R.INSUFFICIENT_BARS
    for score, gap, expected in [(D(0), D(20), 'score_below_threshold'), (D(90), D(0), 'gap_below_threshold')]:
        result = explain_candidates(bars, replace(signal, side=TrendSide.FLAT, score=score, score_gap=gap))
        assert result.code == R.NO_DIRECTION_SIGNAL and result.flags[expected]
    assert explain_candidates(bars, replace(signal, regime='RANGE')).code == R.REGIME_NOT_TRENDING
    flat = replace(evaluate(_flat()), side=TrendSide.LONG, regime='TRENDING_UP')
    result = explain_candidates(_flat(), flat)
    assert result.code == R.NO_STRUCTURE
    assert set(result.flags['subcodes']) == {R.NO_BOX_BREAKOUT, R.NO_DONCHIAN_BREAKOUT,
                                           R.NO_FIB_RETEST, R.EMA_MOMENTUM_CONFLICT, R.ADX_BELOW_20}


def test_randomized_structure_count_parity():
    rng = random.Random(42)
    for _ in range(100):
        price = D(100)
        bars = []
        for bar in _uptrend():
            price += D(rng.randrange(-100, 101)) / 100
            bars.append(replace(bar, open=price, close=price, high=price + D('.2'), low=price - D('.2')))
        config = JavaEntryConfig(combination_require_trending_regime=False, entry_score_threshold=D(0), min_score_gap=D(0))
        signal = evaluate(bars, config)
        assert explain_candidates(bars, signal, config).candidate_count == len(candidates(bars, signal, config))


def test_policy_explanations_respect_exact_confirmations_and_any_passing_policy():
    item = candidate(ModelCode.DONCHIAN_BREAKOUT, '85')
    assert explain_policy([item], [])[0].code == R.NO_ENABLED_POLICY
    assert explain_policy([item], [policy(item.model.value, 'TREND_CONTEXT')])[0].code == R.POLICY_NOT_MATCHED
    high = policy(item.model.value, 'BOX_BREAKOUT', '90')
    result = explain_policy([item], [high])[0]
    assert result.code == R.POLICY_SCORE_BELOW_THRESHOLD and result.thresholds == (D(90),)
    enabled = [high, policy(item.model.value, 'BOX_BREAKOUT', '85')]
    assert explain_policy([item], enabled)[0].code is None
    assert len(select_policy([item], enabled, MarketContext.neutral())) == 1


def test_decision_hash_has_original_payload_only():
    decision = HtxStrategyDecisionEngine().decide(contract_code='BTC-USDT', period='4hour', candles=_uptrend(), surface='RANKING')
    payload = dict(strategy_version=decision.strategy_version, contract_code=decision.contract_code, period=decision.period,
                   input_hash=decision.input_hash, config_hash=decision.config_hash, bar_time=decision.signal.bar_time,
                   side=decision.signal.side.value, candidate_models=[c.model.value for c in decision.candidates])
    assert decision.decision_hash == sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    assert decision.diagnosis.code is None
    assert replace(decision, diagnosis=None).decision_hash == decision.decision_hash


def test_heartbeat_prefix_counts_and_truncation():
    prefix = 'scan=fresh contracts=52/52 pending=0 entriesAttempted=0 entriesAccepted=0 recovered=0 entryWindow=OPEN anchor=MIXED'
    detail = heartbeat_detail(prefix, period='4hour', raw_candidates=0, policy_selected=0,
                              rejections={f'C{i}-USDT': R.TIMEFRAME_UNSUPPORTED for i in range(52)})
    assert detail.startswith(prefix + ' period=4hour rawCandidates=0 policySelected=0')
    assert 'rejCounts=TIMEFRAME_UNSUPPORTED:52' in detail and len(detail) <= 1024
    assert 'rej=BTC-USDT:TIMEFRAME_UNSUPPORTED' in heartbeat_detail(prefix, period='4hour', raw_candidates=0,
        policy_selected=0, rejections={'BTC-USDT': R.TIMEFRAME_UNSUPPORTED})
    assert len(heartbeat_detail(prefix, period='15min', raw_candidates=0, policy_selected=0,
        rejections={str(i): str(i) * 100 for i in range(52)})) == 1024


class MemoryConnection:
    """Mock MySQL's unique key and first-rejection upsert semantics."""
    def __init__(self):
        self.rows = {}
        self.queries = []
    def cursor(self):
        cursor = Mock()
        cursor.__enter__ = Mock(return_value=cursor)
        cursor.__exit__ = Mock(return_value=None)
        cursor.execute = self.execute
        return cursor
    def execute(self, query, params):
        self.queries.append(query)
        columns = query.split('(', 1)[1].split(')', 1)[0].split(',')
        row = dict(zip(columns, params))
        key = tuple(row[name] for name in ('instance_key', 'contract_code', 'period', 'bar_time'))
        if (key not in self.rows or self.rows[key]['reject_code'] in (None, R.CAPACITY_FULL, R.EVALUATION_QUEUED)
                or (row['stage'] == 'EXEC' and row['reject_code'] is None)):
            self.rows[key] = row
    def commit(self):
        pass


def test_repository_idempotency_first_rejection_and_valid_bounded_json():
    connection = MemoryConnection()
    repo = HtxTrendMySqlRepository(connection)
    row = dict(instance_key='one', run_id='run', contract_code='BTC-USDT', period='15min', bar_time=900)
    assert repo.record_entry_diagnosis(**row)
    assert repo.record_entry_diagnosis(**row, stage='QUEUE', reject_code=R.CAPACITY_FULL, flags={'long': 'x' * 1000})
    assert repo.record_entry_diagnosis(**row, stage='EXEC', reject_code='gate.model_score')
    assert len(connection.rows) == 1
    stored = next(iter(connection.rows.values()))
    assert stored['reject_code'] == 'gate.model_score'
    assert len(stored['flags_json']) <= 512
    queued_flags = json.loads(connection.rows[next(iter(connection.rows))]['flags_json'])
    assert isinstance(queued_flags, dict)
    assert "reject_code IN ('CAPACITY_FULL','EVALUATION_QUEUED')" in connection.queries[-1]
    assert connection.queries[-1].endswith('VALUES(reject_code),reject_code)')
    assert repo.record_entry_diagnosis(**{**row, 'instance_key': 'two'})
    assert len(connection.rows) == 2


def test_missing_table_degrades_without_leaking_error_details(caplog):
    repo = HtxTrendMySqlRepository(Mock())
    repo._write = Mock(side_effect=RuntimeError(1146, 'secret DB connection details'))
    assert not repo.record_entry_diagnosis(instance_key='one', run_id='run', contract_code='BTC-USDT', period='4hour', bar_time=0)
    assert 'entry diagnosis write unavailable' in caplog.text and 'secret' not in caplog.text


def admitted_v2():
    digest = 'a' * 64
    return build_artifact(strategy_id='htx-trend', strategy_version='HTX_TREND_V2', experiment_id='EXP-20261015-001',
        source_commit='test', policy_hash=digest, config_hash=digest, dataset_manifest_hash=digest,
        stages=tuple(StageEvidence(stage, StageStatus.PASS, f'e/{stage.name}', digest, 'passed')
                     for stage in ResearchStage),
        allowed_surfaces=(DecisionSurface.RANKING, DecisionSurface.GATE, DecisionSurface.SIZING),
        failure_conditions=('negative expectancy',), pause_conditions=('unknown exchange state',))


def make_worker(monkeypatch, *, period='15min', policies=None, active=0,
                strategy_name='HTX_TREND_V1', admission=None, **config):
    import htx_trend.live_worker as module
    class Clock:
        @staticmethod
        def now(tz): return datetime.fromtimestamp(117015, UTC)
        fromtimestamp = datetime.fromtimestamp
    monkeypatch.setattr(module, 'datetime', Clock)
    repo = Mock()
    repo.enabled_strategy_policies.return_value = policies if policies is not None else [policy('DONCHIAN_BREAKOUT', 'BOX_BREAKOUT')]
    repo.active_position_count.return_value = active
    repo.record_factor_snapshot.return_value = 1
    connection = MemoryConnection()
    repo.record_entry_diagnosis.side_effect = HtxTrendMySqlRepository(connection).record_entry_diagnosis
    market = Mock(evidence_store=None)
    market.kline_window.return_value = NS(candles=_uptrend(), evidence_status='OK', evidence_refs=())
    market.funding_rate.return_value = None
    market.order_book.return_value = NS(top=NS(spread_rate=D(0)))
    executor = Mock()
    executor.reconcile_pending.return_value = {}
    executor.submit.return_value = NS(accepted=False, gate=NS(stage='gate.model_score'), reason='score')
    manager = Mock()
    manager.manage.return_value = []
    profile = TimeframeProfile.for_period(period)
    worker = LiveTrendWorker(RuntimeInstance('one', 'TREND', strategy_name, 7, 'test', 'LIVE', True, 'test', None),
        repo, market, executor, LiveTrendWorkerConfig(('BTC-USDT',), enabled=True, combination_entry_enabled=True,
        entry_period=period, trend_period=profile.contract.trend_period, timeframe_profile=profile,
        max_active_positions=1,
        adaptive_sizing_enabled=False, market_anchor_enabled=False, **config),
        position_manager=manager, writer_guard=lambda: None, admission=admission)
    return worker, repo, market, executor, connection


def test_live_4hour_generates_shadow_candidates_and_cached_diagnosis(monkeypatch):
    worker, repo, market, executor, connection = make_worker(monkeypatch, period='4hour')
    first, second = worker.tick(), worker.tick()
    assert len(connection.rows) == 1 and market.kline_window.call_count == 2
    row = next(iter(connection.rows.values()))
    assert row['reject_code'] == R.OBSERVE_ONLY_DISCARDED and row['decision_hash']
    assert row['policy_selected_count'] > 0 and row['raw_candidate_count'] > 0
    assert 'TIMEFRAME_UNSUPPORTED' not in first.detail + second.detail
    assert 'period=4hour' in first.detail
    repo.record_signal.assert_called_once()
    repo.record_factor_snapshot.assert_called_once()
    assert repo.record_candidate.call_args.kwargs['shadow'] is True
    executor.submit.assert_not_called()


def test_4hour_profile_live_switch_is_the_only_path_to_real_executor(monkeypatch):
    shadow, repo, _, executor, _ = make_worker(
        monkeypatch, period='4hour', observe_only=False, entry_enabled=False)
    shadow.tick()
    assert repo.record_candidate.call_args.kwargs['shadow'] is True
    executor.submit.assert_not_called()

    live, repo, _, executor, _ = make_worker(
        monkeypatch, period='4hour', observe_only=False, entry_enabled=True,
        strategy_name='HTX_TREND_V2', admission=admitted_v2())
    live.tick()
    assert repo.record_candidate.call_args.kwargs['shadow'] is False
    assert executor.submit.call_count == 1
    assert executor.submit.call_args.kwargs['period'] == '4hour'
    assert executor.submit.call_args.kwargs['timeframe_profile'] == 'SWING_4H'


@pytest.mark.parametrize('options,code,pending,attempts', [
    ({}, R.OBSERVE_ONLY_DISCARDED, False, 0),
    ({'active': 1}, R.CAPACITY_FULL, True, 0),
    ({'observe_only': False, 'entry_enabled': False}, R.ENTRY_DISABLED, False, 0),
    ({'observe_only': False}, 'gate.model_score', False, 1),
    ({'policies': []}, R.NO_ENABLED_POLICY, False, 0),
    ({'signal_freshness_seconds': -1}, R.EXPIRED, False, 0),
])
def test_live_queue_policy_exec_diagnostics_do_not_change_actions(monkeypatch, options, code, pending, attempts):
    worker, repo, market, executor, connection = make_worker(monkeypatch, **options)
    worker.tick()
    assert next(iter(connection.rows.values()))['reject_code'] == code
    assert bool(worker._pending) is pending
    assert executor.submit.call_count == attempts
    assert repo.record_candidate.call_count == (0 if options.get('policies') == [] else 1)


@pytest.mark.parametrize('evidence', [False, True])
def test_scan_failures_are_diagnosed_without_fabricated_signal(monkeypatch, evidence):
    worker, repo, market, executor, connection = make_worker(monkeypatch)
    if evidence:
        market.kline_window.return_value.evidence_status = 'FAILED'
    else:
        market.kline_window.side_effect = ValueError('unavailable')
    health = worker.tick()
    row = next(iter(connection.rows.values()))
    assert row['stage'] == 'SCAN'
    assert row['reject_code'] == (R.EVIDENCE_UNAVAILABLE if evidence else R.SCAN_FAILED)
    assert row['bar_time'] == 116100 and row['signal_side'] is None
    assert row['policy_selected_count'] == 0
    executor.submit.assert_not_called()


def test_worker_diagnostic_store_failure_does_not_block_execution(monkeypatch):
    worker, repo, _, executor, _ = make_worker(monkeypatch, observe_only=False)
    repo.record_entry_diagnosis.side_effect = RuntimeError('missing table')
    health = worker.tick()
    executor.submit.assert_called_once()
    assert 'entriesAttempted=1' in health.detail


def test_fibonacci_explanation_matches_all_java_confirmation_combinations(monkeypatch):
    import test_java_entry_contract as fixtures
    original = fixtures.candidates
    observed = []
    def checked(bars, signal, config):
        result = original(bars, signal, config)
        diagnosis = explain_candidates(bars, signal, config)
        assert diagnosis.flags['fib_retest']
        assert diagnosis.candidate_count == len(result) == 4
        observed.append(diagnosis)
        return result
    monkeypatch.setattr(fixtures, 'candidates', checked)
    fixtures.test_java_fibonacci_contract_preserves_prior_breakout_and_all_policy_confirmations()
    assert len(observed) == 1


def test_diagnosis_ddl_has_bar_identity_and_mock_sql_mogrifies():
    from pathlib import Path
    import pymysql
    ddl = (Path(__file__).parents[1] / 'sql/2026-10-14__htx_trend_entry_diagnosis.sql').read_text()
    assert 'UNIQUE KEY uk_entry_diagnosis_bar (instance_key, contract_code, period, bar_time)' in ddl
    repo = HtxTrendMySqlRepository(Mock())
    repo._write = Mock()
    repo.record_entry_diagnosis(instance_key='one', run_id='run', contract_code='BTC-USDT', period='15min', bar_time=900)
    query, params = repo._write.call_args.args
    assert 'INSERT INTO htx_trend_entry_diagnosis' in query
    assert 'candidate_signal' not in query
    assert 'VALUES' in query % tuple(pymysql.converters.escape_item(arg, 'utf8mb4') for arg in params)


@pytest.mark.parametrize('bars,period,expected', [
    (_uptrend(), '15min', '6d623a346904d2296e29c6f65ddc2c892aac0134bc6ac98c0b28d081edbf16cc'),
    (_uptrend(), '4hour', '173b37055137169c3ba3edb437f84a2ee9ba801afd86bd6b20cb0935c6e04ce8'),
    (_downtrend(), '15min', 'ca885fbcbef8c5a78779227cd82e78e671c41514874725da4a114f61809942ae'),
    (_downtrend(), '4hour', '268c546ca1359edc84236455f9dd01181f82c3f67e8e6481511a388981a343c9'),
    (_flat(), '15min', 'a822042e11fdb784beb8cfeece7af81f7cfd465abd24737d4b8493040adbf02d'),
    (_flat(), '4hour', '2a6537f9207c4cd07554332aee7263445f37c178961b9a3f33597634e1eea465'),
])
def test_decision_hash_goldens_after_g1_model_rename(bars, period, expected):
    decision = HtxStrategyDecisionEngine().decide(contract_code='BTC-USDT', period=period, candles=bars, surface='RANKING')
    assert decision.decision_hash == expected


def test_closed_window_keeps_existing_fresh_candidate_carryover(monkeypatch):
    worker, _, _, executor, connection = make_worker(monkeypatch, observe_only=False)
    monkeypatch.setattr(worker, '_entry_window_open', lambda *_: False)
    health = worker.tick()
    executor.submit.assert_called_once()
    assert 'entryWindow=CLOSED' in health.detail
    assert next(iter(connection.rows.values()))['reject_code'] == 'gate.model_score'


def test_cached_capacity_then_expiry_records_terminal_result(monkeypatch):
    worker, _, market, executor, connection = make_worker(monkeypatch, active=1)
    worker.tick()
    assert worker._pending
    worker._config = replace(worker._config, signal_freshness_seconds=-1)
    worker.tick()
    assert not worker._pending
    assert len(connection.rows) == 1
    assert next(iter(connection.rows.values()))['reject_code'] == R.EXPIRED
    assert market.kline_window.call_count == 2
    executor.submit.assert_not_called()


def test_missing_history_is_distinct_from_write_failure_and_stays_degraded_on_cached_tick(monkeypatch):
    from htx_trend.live_worker import _ContextUnavailable
    worker, _, market, executor, connection = make_worker(monkeypatch, observe_only=False)
    worker._scan_one = Mock(side_effect=_ContextUnavailable({'1day': tuple(range(63))}, ('1day',)))
    first = worker.tick()
    second = worker.tick()
    row = next(iter(connection.rows.values()))
    assert first.status == second.status == 'DEGRADED'
    assert row['reject_code'] == R.CONTEXT_UNAVAILABLE
    assert json.loads(row['flags_json']) == {'closed_bars': {'1day': 63}, 'missing_periods': ['1day'], 'required_bars': 121}
    assert 'coverage=0/1' in second.detail
    worker._scan_one.assert_called_once()
    executor.submit.assert_not_called()


def test_queue_observation_can_be_replaced_by_actual_execution_after_restart():
    connection = MemoryConnection()
    repo = HtxTrendMySqlRepository(connection)
    row = dict(instance_key='one', run_id='r', contract_code='BTC-USDT', period='15min', bar_time=900)
    repo.record_entry_diagnosis(**row, stage='QUEUE', reject_code=R.EVALUATION_QUEUED)
    recovered = HtxTrendMySqlRepository(connection)
    recovered.record_entry_diagnosis(**row, stage='EXEC', reject_code=None)
    result = next(iter(connection.rows.values()))
    assert result['stage'] == 'EXEC' and result['reject_code'] is None


def test_position_count_uses_account_id_not_historical_label():
    import sqlite3
    conn = sqlite3.connect(':memory:')
    conn.row_factory = sqlite3.Row
    conn.execute('CREATE TABLE htx_trend_position (account_id INT, account_label TEXT, source_position_id INT, status TEXT)')
    conn.executemany('INSERT INTO htx_trend_position VALUES(?,?,?,?)', [
        (7,'primary-live-15m',None,'ACTIVE'), (7,'primary-live-15m',None,'UNKNOWN'),
        (7,'primary-live-15m',None,'DONE'), (7,'mirror-copy',123,'ACTIVE'),
        (1,'primary',None,'ACTIVE'), (3,'paper',None,'ACTIVE'), (7,'paper',None,'ACTIVE')])
    repo = HtxTrendMySqlRepository(None)
    repo._one = lambda query, params: conn.execute(query.replace('%s','?'),params).fetchone()
    assert repo.active_position_count(account_id=7) == 2
    assert repo.active_position_count(account_id=1) == 1
    assert repo.active_position_count(account_id=2) == 0
    conn.close()
