from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256

import pytest

from htx_trend.market_data import Candle
from htx_trend.runtime_kline_business import (
    MySqlBusinessKlineSink, COLUMNS, BUSINESS_KLINE_PERIODS, business_kline_health,
)
from htx_trend.runtime_kline_backfill import backfill, backfill_plan
from htx_trend.research.collector import ContinuousCollector
from htx_trend.research.store import FileEvidenceStore
from test_research_collector import Client, row


class Connection:
    def __init__(self):
        self.rows = {}
        self.commits = self.rollbacks = 0
        self.fail = False

    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def execute(self, sql, args):
        assert sql.startswith('INSERT IGNORE INTO htx_trend_runtime_kline_candle (')
        assert '(' + ','.join(COLUMNS) + ')' in sql
        if self.fail: raise RuntimeError('fixture outage')
        if args[0] in self.rows: return 0
        self.rows[args[0]] = dict(zip(COLUMNS, args))
        return 1
    def commit(self): self.commits += 1
    def rollback(self): self.rollbacks += 1


def test_hash_schema_idempotence_revision():
    conn = Connection()
    sink = MySqlBusinessKlineSink(conn)
    bar = Candle.from_htx(row(900))
    captured = datetime(2026, 10, 20, tzinfo=timezone.utc)
    kwargs = dict(contract_code='BTC-USDT', period='15min', captured_at=captured)
    assert bar.content_hash == sha256(b'900|100|102|99|101|10|20|2000|4').hexdigest()
    assert sink.write([bar], **kwargs) == dict(attempted=1, inserted=1, ignored=0)
    assert sink.write([Candle.from_htx({**row(900), 'open': '100.000'})], **kwargs) == dict(attempted=1, inserted=0, ignored=1)
    assert sink.write([Candle.from_htx(row(900, '101.5'))], **kwargs)['inserted'] == 1
    stored = conn.rows[bar.content_hash]
    assert stored == dict(zip(COLUMNS, (bar.content_hash, 'BTC-USDT', '15min', 900,
        Decimal('100'), Decimal('102'), Decimal('99'), Decimal('101'), Decimal('10'),
        Decimal('20'), Decimal('2000'), 4, 'HTX_PUBLIC_RUNTIME', datetime(2026, 10, 20, 8))))
    conn.fail = True
    with pytest.raises(RuntimeError): sink.write([bar], **kwargs)
    assert conn.rollbacks == 1
    with pytest.raises(ValueError): sink.write([bar], **{**kwargs, 'period': '30min'})
    with pytest.raises(ValueError): sink.write([bar], **{**kwargs, 'captured_at': datetime(2026, 10, 20)})


def test_cached_file_page_backfills_and_retries_without_refetch(tmp_path):
    client, conn = Client([row(0), row(900)]), Connection()
    with FileEvidenceStore(tmp_path) as store:
        args = dict(start=0, end=1800, periods=('15min',))
        ContinuousCollector(client, store, clock=lambda: 10000).collect(('BTC-USDT',), **args)
        collector = ContinuousCollector(client, store, clock=lambda: 10000, business_sink=MySqlBusinessKlineSink(conn))
        conn.fail = True
        failed = collector.collect(('BTC-USDT',), **args)
        assert store.get(failed)['payload']['integrity'] == 'PARTIAL'
        conn.fail = False
        for _ in range(2):
            key = collector.collect(('BTC-USDT',), **args)
            assert store.get(key)['payload']['integrity'] == 'COMPLETE'
        assert client.calls == 1
        assert len(conn.rows) == 2
        assert collector.business_counts == dict(attempted=4, inserted=2, ignored=2)


@pytest.mark.parametrize('symbol,period,rows,end', [
    ('BTC-USDT', '15min', [row(0)], 1800),
    ('SOL-USDT', '15min', [row(0), row(900)], 1800),
    ('BTC-USDT', '30min', [row(0), row(1800)], 3600),
])
def test_only_complete_locked_pages_write(tmp_path, symbol, period, rows, end):
    conn = Connection()
    with FileEvidenceStore(tmp_path) as store:
        collector = ContinuousCollector(Client(rows), store, clock=lambda: 10000,
                                        business_sink=MySqlBusinessKlineSink(conn))
        collector.page(symbol, period, 0, end, source='FIXTURE')
    assert not conn.rows and conn.commits == 0


def test_tick_failure_preserves_file_and_does_not_advance_cursor(tmp_path):
    conn = Connection()
    conn.fail = True
    with FileEvidenceStore(tmp_path) as store:
        collector = ContinuousCollector(Client([row(0), row(900)]), store, clock=lambda: 1810,
                                        business_sink=MySqlBusinessKlineSink(conn))
        assert collector.tick(('BTC-USDT',), periods=('15min',))['status'] == 'DEGRADED'
        assert len(store.keys('kline_page')) == 1
        assert store.checkpoint('continuous:BTC-USDT:15min') is None
        conn.fail = False
        assert collector.tick(('BTC-USDT',), periods=('15min',))['status'] == 'HEALTHY'
        assert store.checkpoint('continuous:BTC-USDT:15min')['end'] == 1800


def test_health_frozen_clock_independent_maxima():
    now = datetime(2026, 10, 20, tzinfo=timezone.utc)
    epoch = int(now.timestamp())
    rows = [dict(contract_code='BTC-USDT', period='15min', bar_time=epoch-1000,
                 captured_at=now),
            dict(contract_code='BTC-USDT', period='15min', bar_time=epoch-900,
                 captured_at=datetime(2026, 10, 19, 8))]
    result = business_kline_health(rows, now=now)
    assert len(result) == 8
    assert result[0]['status'] == 'HEALTHY'
    assert result[0]['bar_lag_seconds'] == 900
    assert result[0]['capture_lag_seconds'] == 0
    assert result[1]['status'] == 'MISSING'
    rows[0]['captured_at'] = rows[1]['captured_at']
    assert business_kline_health(rows, now=now)[0]['status'] == 'STALE'
    rows[0]['bar_time'] = epoch + 1
    assert business_kline_health(rows, now=now)[0]['status'] == 'INVALID_FUTURE'


class PagedClient:
    def history_kline_envelope(self, symbol, period, *, start, end):
        from htx_trend.market_data import period_seconds
        # Distinct content per symbol/period, as hash is globally unique in legacy schema.
        rows = [{**row(t), 'amount': str(10 + len(period) + (symbol == 'ETH-USDT'))}
                for t in range(start, end+1, period_seconds(period))]
        return dict(status='ok', data=rows)


def test_backfill_plan_dry_run_and_guard(tmp_path, monkeypatch):
    monkeypatch.delenv('HTX_KLINE_BACKFILL_CONFIRM', raising=False)
    result = backfill(None, None, start=0, end=200000, dry_run=True, page_bars=2, clock=lambda: 300000)
    assert result['database_writes'] == 0
    assert {p['period'] for p in result['pages']} == set(BUSINESS_KLINE_PERIODS)
    assert all(p['planned_bars'] <= 2 for p in result['pages'])
    daily = [p for p in result['pages'] if p['period'] == '1day']
    assert all(p['start'] % 86400 == 57600 for p in daily)
    with pytest.raises(ValueError, match='CONFIRM'): backfill(None, None, start=0, end=200000, clock=lambda: 300000)
    with pytest.raises(ValueError): backfill_plan(start=2, end=1)
    monkeypatch.setenv('HTX_KLINE_BACKFILL_CONFIRM', 'YES')
    conn = Connection()
    with FileEvidenceStore(tmp_path) as store:
        args = dict(start=0, end=200000, clock=lambda: 300000, sink=MySqlBusinessKlineSink(conn))
        assert backfill(PagedClient(), store, **args)['status'] == 'COMPLETE'
        result = backfill(PagedClient(), store, **args)
        assert result['counts']['inserted'] == 0
        assert result['counts']['ignored'] == result['counts']['attempted'] > 0


def test_cli_dry_run_and_guard_never_connect(tmp_path, monkeypatch, capsys):
    import sys
    from htx_trend import cli, runtime_kline_business
    def forbidden(*a, **kw): raise AssertionError('connection forbidden')
    monkeypatch.setattr(runtime_kline_business, 'connect', forbidden)
    monkeypatch.setattr(cli, 'HtxV5Client', forbidden)
    argv = ['htx-trend', 'backfill-runtime-kline-business', '--evidence', str(tmp_path/'unused')]
    monkeypatch.setattr(sys, 'argv', argv + ['--dry-run'])
    cli.main()
    assert 'DRY_RUN' in capsys.readouterr().out
    assert not (tmp_path/'unused').exists()
    monkeypatch.delenv('HTX_KLINE_BACKFILL_CONFIRM', raising=False)
    monkeypatch.setattr(sys, 'argv', argv)
    with pytest.raises(ValueError, match='CONFIRM'): cli.main()


def test_cli_tick_default_off_and_opt_in(tmp_path, monkeypatch, capsys):
    import sys
    import json
    from contextlib import contextmanager
    from htx_trend import cli
    conn = Connection()
    enabled_values = []
    @contextmanager
    def factory(enabled):
        enabled_values.append(enabled)
        yield MySqlBusinessKlineSink(conn) if enabled else None
    class ContextClient(PagedClient):
        def __enter__(self): return self
        def __exit__(self, *_): pass
    monkeypatch.setattr(cli, 'business_sink', factory)
    monkeypatch.setattr(cli, 'HtxV5Client', ContextClient)
    argv = ['htx-trend', 'collect-tick', '--evidence', str(tmp_path), '--symbols', 'BTC-USDT',
            '--periods', '15min']
    monkeypatch.setattr(sys, 'argv', argv)
    cli.main()
    assert json.loads(capsys.readouterr().out)['status'] == 'HEALTHY'
    assert not conn.rows
    monkeypatch.setattr(sys, 'argv', argv + ['--business-db'])
    cli.main()
    assert json.loads(capsys.readouterr().out)['business_counts']['inserted'] == 2
    assert enabled_values == [False, True]


def test_factory_disabled_and_connection_cleanup(monkeypatch):
    from htx_trend import runtime_kline_business as module
    events = []
    class Settings:
        @classmethod
        def from_env(cls, prefix):
            assert prefix == 'HTX_DB_'
            return 'fixture'
    class Conn:
        def close(self): events.append('close')
    def connect(settings):
        assert settings == 'fixture'
        events.append('connect')
        return Conn()
    monkeypatch.setattr(module, 'MySqlSettings', Settings)
    monkeypatch.setattr(module, 'connect', connect)
    with module.business_sink(False) as sink:
        assert sink is None
    assert events == []
    with pytest.raises(RuntimeError):
        with module.business_sink(True):
            raise RuntimeError('fixture')
    assert events == ['connect', 'close']
