from datetime import UTC, datetime
from decimal import Decimal as D
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import Mock
import json
import sys

import pytest

from htx_trend.admission import runtime_guard
from htx_trend.maker_execution import MakerExecutionConfig, MakerTimeoutAction
from htx_trend.market_data import Candle, KlineWindow, ContractSpec, TopOfBook, FundingRate, period_seconds
from htx_trend.paper import PaperOrder
from htx_trend.paper_worker import PaperFileLedger, PaperTrendWorker, PaperWorkerConfig, WorkerPaperExchange
from htx_trend.strategy_decision import HtxStrategyDecisionEngine
from htx_trend.strategy_profiles import STRATEGY_15M_V2


UNIVERSE_83 = ",".join([
    "BTC-USDT", "ETH-USDT", "BNB-USDT", "XRP-USDT", "SOL-USDT", "TRX-USDT",
    "ZEC-USDT", "DOGE-USDT", "XMR-USDT", "LINK-USDT", "ADA-USDT", "XLM-USDT",
    "NEAR-USDT", "BCH-USDT", "UNI-USDT", "SUI-USDT", "AAPL-USDT", "ABBV-USDT",
    "ACNSTOCK-USDT", "ADBE-USDT", "AMAT-USDT", "AMD-USDT", "AMGN-USDT",
    "AMZNX-USDT", "ANET-USDT", "AVGO-USDT", "BAC-USDT", "BKNG-USDT",
    "BRKB-USDT", "CATSTOCK-USDT", "CMCSA-USDT", "COST-USDT", "CRM-USDT",
    "CSCO-USDT", "CVXSTOCK-USDT", "DELL-USDT", "DIS-USDT", "GE-USDT",
    "GEV-USDT", "GILD-USDT", "GOOGL-USDT", "GS-USDT", "HD-USDT", "IBM-USDT",
    "INTCX-USDT", "INTU-USDT", "ISRG-USDT", "JNJ-USDT", "JPM-USDT", "KO-USDT",
    "LIN-USDT", "LLY-USDT", "LMT-USDT", "LRCX-USDT", "MCD-USDT", "MDT-USDT",
    "META-USDT", "MO-USDT", "MRK-USDT", "MSFT-USDT", "MU-USDT", "NFLX-USDT",
    "NOW-USDT", "NVDA-USDT", "ORCL-USDT", "PANW-USDT", "PEP-USDT", "PG-USDT",
    "PLTRX-USDT", "QCOM-USDT", "SBUX-USDT", "SNDK-USDT", "TSLAX-USDT",
    "TXN-USDT", "UBER-USDT", "UNH-USDT", "V-USDT", "WMT-USDT", "XOM-USDT",
    "SPY-USDT", "SPX500-USDT", "QQQ-USDT", "NASDAQ100-USDT",
])


def instance(mode='PAPER', key='paper-primary'):
    return NS(instance_key=key, strategy_family='TREND', strategy_name=STRATEGY_15M_V2,
              execution_mode=mode, enabled=True)


class Market:
    def kline_window(self, contract, period, size, *, now_epoch):
        step = period_seconds(period)
        end = (now_epoch // step - 1) * step
        candles = tuple(Candle(end-(size-1-i)*step, D('100'), D('101'), D('99'), D('100')) for i in range(size))
        return KlineWindow(contract, period, candles, size)

    def contract_spec(self, contract):
        return ContractSpec(contract, D('.01'), D('.1'), 1)

    def top_of_book(self, contract):
        return TopOfBook(D('99.99'), D('100.01'))

    def funding_rate(self, contract):
        return FundingRate(contract, D('0'))


def test_fixture_and_rejected_limits(monkeypatch):
    for line in (Path(__file__).parent/'fixtures/paper_worker.env').read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            monkeypatch.setenv(key, value)
    config = PaperWorkerConfig.from_env()
    assert config.equity == 300 and config.max_active_positions == 5
    assert config.contracts == ('BTC-USDT', 'ETH-USDT')
    for key, value in [('HTX_PAPER_MAX_MARGIN_RATE', '.11'), ('HTX_PAPER_EQUITY_USDT', 'NaN'),
                       ('HTX_PAPER_MAX_ACTIVE_POSITIONS', '6')]:
        with monkeypatch.context() as patch:
            patch.setenv(key, value)
            with pytest.raises(ValueError):
                PaperWorkerConfig.from_env()


def test_paper_without_live_artifacts_and_live_still_rejected(monkeypatch):
    for key in ('HTX_TREND_STRATEGY_ADMISSION_PATH', 'HTX_TREND_LIVE_APPROVAL_PATH', 'HTX_TREND_APPROVAL_CONTEXT_PATH'):
        monkeypatch.delenv(key, raising=False)
    assert runtime_guard(instance(), None, 'INTRADAY_15M') == (None, None)
    HtxStrategyDecisionEngine(strategy_version=STRATEGY_15M_V2).require('PAPER')
    with pytest.raises(ValueError, match='sealed admission'):
        HtxStrategyDecisionEngine(strategy_version=STRATEGY_15M_V2).require('LIVE')
    with pytest.raises(ValueError, match='LIVE requires G2'):
        runtime_guard(instance('LIVE'), None, 'INTRADAY_15M')


def test_healthy_restart_dedupe_and_isolation(tmp_path):
    worker = PaperTrendWorker(instance(), Market(), tmp_path, PaperWorkerConfig())
    assert worker.tick().status == 'HEALTHY'
    assert len(worker.ledger.data['decisions']) == 2
    worker.stop()
    worker = PaperTrendWorker(instance(), Market(), tmp_path, PaperWorkerConfig())
    assert worker.tick().status == 'HEALTHY'
    assert len(worker.ledger.data['decisions']) == 2
    with pytest.raises(BlockingIOError):
        PaperTrendWorker(instance(), Market(), tmp_path, PaperWorkerConfig())
    other = PaperTrendWorker(instance(key='paper-other'), Market(), tmp_path, PaperWorkerConfig())
    assert other.ledger.data['decisions'] == []
    other.stop()
    worker.stop()


def test_simulated_fills_contract_fees_and_recovery(tmp_path):
    path = tmp_path/'ledger.json'
    ledger = PaperFileLedger(path, {'instance': 'test'})
    exchange = WorkerPaperExchange(ledger)
    exchange.contract_size = D('.01')
    now = datetime.now(UTC)
    candle = Candle(0, D('100'), D('101'), D('99'), D('100'))
    request = PaperOrder('paper:test', 'BTC-USDT', 'LONG', 'BUY', 2, 'MARKET', now, 'test')
    assert exchange.submit(request, candle).status == 'FILLED'
    p = exchange.open_position('paper:test', 'BTC-USDT', 'LONG', 2, D('100'), now, 'run')
    exchange.reduce_position(p, quantity=2, exit_price=D('110'), contract_size=D('.01'), when=now, run_id='exit')
    assert ledger.position(p.position_id).realized_pnl == D('.19912')
    assert ledger.data['fills'][0]['fee'] == D('.0008')
    ledger.checkpoint()
    ledger.close()
    ledger = PaperFileLedger(path, {'instance': 'test'})
    assert ledger.active_positions('paper:test') == []
    ledger.close()


def test_worker_main_paper_never_loads_live_or_credentials(monkeypatch, tmp_path):
    from htx_trend import worker as entry
    repo = Mock()
    repo.runtime_instance.return_value = instance()
    monkeypatch.setattr(entry, 'connect', lambda _: None)
    monkeypatch.setattr(entry.MySqlSettings, 'from_env', lambda: None)
    monkeypatch.setattr(entry, 'HtxTrendMySqlRepository', lambda _: repo)
    forbidden = Mock(side_effect=AssertionError('LIVE path reached'))
    for name in ('_load_admission', '_account_client', '_validate_live_approval', 'HtxLiveExecutor', 'TrendWriterLock'):
        monkeypatch.setattr(entry, name, forbidden)
    monkeypatch.setattr(entry, 'HtxMarketData', lambda *a, **k: Market())
    client = Mock()
    monkeypatch.setattr(entry, 'HtxV5Client', lambda **kw: client)
    monkeypatch.setenv('HTX_PAPER_STATE_DIR', str(tmp_path/'state'))
    monkeypatch.setenv('HTX_PYTHON_EVIDENCE_DIR', str(tmp_path/'evidence'))
    monkeypatch.setattr(sys, 'argv', ['worker', '--strategy-instance', 'paper-primary', '--once'])
    entry.main()
    assert repo.heartbeat.call_args.kwargs['status'] == 'HEALTHY'
    assert repo.heartbeat.call_args.kwargs['instance_key'] == 'paper-primary'
    forbidden.assert_not_called()
    repo.mark_runtime_stopped.assert_called_once_with('paper-primary')
    client.close.assert_called_once()


def test_paper_capacity_and_margin_are_hard_gates(tmp_path):
    from dataclasses import replace
    from test_execution import candidate, inputs
    from htx_trend.execution import ExecutionFacts, evaluate_gate
    worker = PaperTrendWorker(instance(), Market(), tmp_path, PaperWorkerConfig())
    facts = ExecutionFacts(account_equity_usdt=D('300'), leverage=D('10'),
        used_margin_usdt=D('0'), reserved_margin_usdt=D('0'), active_position_count=5, max_active_positions=5)
    values = inputs()
    values['config'] = replace(values['config'], hard_risk_limits_enabled=True,
        max_active_positions=worker.config.max_active_positions, max_total_margin_rate=worker.config.margin_rate)
    assert evaluate_gate(candidate(), execution_facts=facts, **values).stage == 'POSITION_CAPACITY'
    facts = replace(facts, active_position_count=0, used_margin_usdt=D('30'))
    result = evaluate_gate(candidate(), execution_facts=facts, **values)
    assert not result.allowed and 'margin' in result.reason
    worker.stop()


def test_failed_contract_is_skipped_and_retried_next_tick(tmp_path):
    market = Market()
    worker = PaperTrendWorker(instance(), market, tmp_path, PaperWorkerConfig())
    original = market.funding_rate
    market.funding_rate = Mock(side_effect=RuntimeError('market unavailable'))
    health = worker.tick()
    assert health.status == 'DEGRADED'
    assert 'skipped=' in health.detail
    assert worker.ledger.data['bars'] == {}
    assert worker.ledger.data['orders'] == {}
    market.funding_rate = original
    assert worker.tick().status == 'HEALTHY'
    worker.stop()


def test_worker_reuses_real_gate_and_paper_fill_pipeline(tmp_path, monkeypatch):
    from dataclasses import replace
    from test_execution import candidate, inputs
    worker = PaperTrendWorker(instance(), Market(), tmp_path, PaperWorkerConfig(contracts=('BTC-USDT',)))
    epoch = int(datetime.now(UTC).timestamp()) // 900 * 900 + 30
    class Clock:
        @staticmethod
        def now(tz):
            return datetime.fromtimestamp(epoch, tz)
    monkeypatch.setattr("htx_trend.paper_worker.datetime", Clock)
    bar = (epoch // 900 - 1) * 900
    item = replace(candidate(), bar_time=bar)
    worker.runtime._execution_config = replace(inputs()['config'], hard_risk_limits_enabled=True,
        max_active_positions=5, max_total_margin_rate=D('.10'))
    worker.runtime._decision.decide = Mock(return_value=NS(
        signal=NS(reason='fixture', side=item.side, indicators=NS(atr=D('2'), ema_fast=D('100'))),
        candidates=(item,), strategy_version=STRATEGY_15M_V2, decision_hash='fixture'))
    health = worker.tick()
    assert health.status == 'HEALTHY'
    assert health.active_position_count == 1
    position = worker.ledger.active_positions(worker.account)[0]
    assert position.entry_price == D('100.01')
    assert worker.ledger.data['fills'][0]['fee'] == D('100.01') * position.quantity * D('.01') * D('.0004')
    worker.stop()


def test_paper_candidate_package(tmp_path):
    from test_g3_release import package
    archive = package.build(tmp_path, paper=True)
    receipt = package.verify(archive)
    assert receipt['version'].startswith('0.1.6-paper-db-')
    assert receipt['frozen_path'] == str(archive.parent.resolve())
    template = package.approval_template(archive)
    assert template['synthetic'] is True and template['seal_hash'] == ''


def test_paper_worker_reads_maker_config_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("HTX_TREND_MAKER_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_MAKER_OFFSET_BPS", "0")
    monkeypatch.setenv("HTX_TREND_MAKER_MAX_WAIT_BARS", "1")
    monkeypatch.setenv("HTX_TREND_MAKER_MAX_AMENDS", "0")
    monkeypatch.setenv("HTX_TREND_MAKER_PARTIAL_ACCEPT", "false")
    monkeypatch.setenv("HTX_TREND_MAKER_TIMEOUT_ACTION", "CANCEL")
    worker = PaperTrendWorker(instance(), Market(), tmp_path, PaperWorkerConfig())
    assert worker.runtime._maker_config.enabled is True
    assert worker.runtime._maker_config.offset_bps == D("0")
    assert worker.runtime._maker_config.max_wait_bars == 1
    assert worker.runtime._maker_config.max_amends == 0
    assert worker.runtime._maker_config.partial_accept is False
    assert worker.runtime._maker_config.timeout_action is MakerTimeoutAction.CANCEL
    worker.stop()


def test_paper_contracts_env_carries_83_universe(monkeypatch):
    monkeypatch.setenv('HTX_PAPER_CONTRACTS', UNIVERSE_83)
    config = PaperWorkerConfig.from_env()
    expected = tuple(x.strip().upper() for x in UNIVERSE_83.split(',') if x.strip())
    assert config.contracts == expected
    assert len(config.contracts) == 83
    assert 'HYPE-USDT' not in config.contracts


def test_paper_config_accepts_stock_and_index_symbols(monkeypatch):
    monkeypatch.setenv('HTX_PAPER_CONTRACTS', 'SPX500-USDT,NASDAQ100-USDT,ACNSTOCK-USDT,BRKB-USDT')
    config = PaperWorkerConfig.from_env()
    assert config.contracts == ('SPX500-USDT', 'NASDAQ100-USDT', 'ACNSTOCK-USDT', 'BRKB-USDT')


def test_paper_worker_skips_failed_contract_without_stopping_healthy_peer(tmp_path, caplog):
    market = Market()
    original = market.kline_window
    def kline_with_failure(contract, period, size, *, now_epoch):
        if contract == 'UNKNOWN-USDT':
            raise RuntimeError('market data unavailable')
        return original(contract, period, size, now_epoch=now_epoch)
    market.kline_window = kline_with_failure
    worker = PaperTrendWorker(
        instance(), market, tmp_path,
        PaperWorkerConfig(contracts=('BTC-USDT', 'UNKNOWN-USDT')))
    with caplog.at_level('WARNING', logger='htx_trend.paper_worker'):
        health = worker.tick()
    assert health.status == 'DEGRADED'
    assert 'UNKNOWN-USDT' in health.detail
    assert 'BTC-USDT' in worker.ledger.data['bars']
    assert 'UNKNOWN-USDT' not in worker.ledger.data['bars']
    assert any('UNKNOWN-USDT' in record.message for record in caplog.records)
    worker.stop()


