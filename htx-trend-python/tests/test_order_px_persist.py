"""Offline new-order persistence. No migration scripts or external DBs are used."""
from dataclasses import replace
from datetime import UTC, datetime, timedelta, timezone
from decimal import Decimal as D
from pathlib import Path
import sqlite3

import pytest
from htx_trend.order_px_fields import order_px_fields, mysql_time
from htx_trend.ledger import TrendLedger, Account
from htx_trend.paper import PaperExchange, PaperOrder
from htx_trend.market_data import Candle
from htx_trend.mysql_store import HtxTrendMySqlRepository, TrendOrderIntent
from test_mysql_store_platform import Connection, account


@pytest.mark.parametrize('kind,limit,mid,last,price,source', [
    ('LIMIT', '101', '102', '103', '101', 'limit'),
    ('market', '101', '102', '103', '102', 'mid'),
    ('MARKET', '101', None, '103', '103', 'last'),
    ('market', '101', None, None, None, None),
    ('limit', None, '102', '103', None, None),
    ('market', None, 'NaN', '103', '103', 'last'),
    ('market', None, '-1', 'Infinity', None, None),
    ('unknown', '101', '102', '103', None, None),
])
def test_observation_selection(kind, limit, mid, last, price, source):
    assert order_px_fields(kind, limit=limit, mid=mid, last=last) == {
        'order_px': D(price) if price else None, 'order_px_source': source}


@pytest.mark.parametrize('forbidden', ['signal', 'average_price', 'fill_price', 'desired_entry'])
def test_selector_does_not_accept_forbidden_fallback(forbidden):
    with pytest.raises(TypeError):
        order_px_fields('market', **{forbidden: D('999')})


@pytest.mark.parametrize('next_event', [False, True])
@pytest.mark.parametrize('kind,mid,last,expected,source', [
    ('LIMIT', None, None, '101', 'limit'),
    ('MARKET', D('102'), D('103'), '102', 'mid'),
    ('MARKET', None, D('103'), '103', 'last'),
    ('MARKET', None, None, None, None),
])
def test_paper_sqlite_round_trip_no_signal_or_fill_fallback(tmp_path, next_event, kind, mid, last, expected, source):
    path = tmp_path / 'test.sqlite'
    TrendLedger.initialize(path, Path(__file__).parents[1] / 'sql/schema.sql')
    ledger = TrendLedger(path)
    ledger.account(Account('a', 'test', 'PAPER'))
    at = datetime(2026, 10, 1, tzinfo=UTC)
    request = PaperOrder('a', 'BTC-USDT', 'LONG', 'BUY', 1, kind, at, 'test',
                         limit_price=D('101') if kind == 'LIMIT' else None,
                         signal_px=D('999'), signal_ts=at, mid=mid, last=last)
    candle = Candle(int(at.timestamp()), D('110'), D('120'), D('90'), D('111'), D('1000'))
    exchange = PaperExchange(ledger)
    before = datetime.now(UTC)
    order = exchange.submit_next_event(request, candle) if next_event else exchange.submit(request, candle)
    after = datetime.now(UTC)
    with sqlite3.connect(path) as db:
        db.row_factory = sqlite3.Row
        row = dict(db.execute('SELECT * FROM trend_order').fetchone())
    assert row['order_px'] == expected and row['order_px_source'] == source
    assert row['signal_px'] == '999'
    assert row['cancel_count'] is None and row['amend_count'] is None
    assert before <= datetime.fromisoformat(row['order_ts']) <= after
    assert row['order_ts'] != row['created_at']
    with pytest.raises(ValueError):
        ledger.record_order(replace(order, order_id='bad', order_px=D(1), order_px_source='fill'))
    ledger.close()


def test_mysql_insert_nullable_metadata_and_submission_time():
    connection = Connection()
    repo = HtxTrendMySqlRepository(connection)
    at = datetime(2026, 10, 22, 8, tzinfo=timezone(timedelta(hours=8)))
    intent = TrendOrderIntent(1, 'p', 'r', 'BTC-USDT', 'ENTRY', 'LONG', 'BUY', 'LONG', False,
                              'client', 1, account(), 'MODEL', None, 'test', signal_px=D('999'),
                              signal_ts=at, **order_px_fields('market'))
    repo.record_order_intent(intent)
    query, args = next((q, a) for q, a in connection.queries if q.startswith('INSERT'))
    assert query.count('%s') == len(args)
    assert args[-7:] == (D('999'), datetime(2026, 10, 22), None, None, None, None, None)
    repo.mark_order_accepted(position_id=1, leg='ENTRY', client_order_id='client', exchange_order_id=1,
                             exchange_client_order_id=None, message='sent', order_ts=at)
    assert connection.queries[-1][1][0] == datetime(2026, 10, 22)
    assert mysql_time(None) is None
    with pytest.raises(ValueError):
        repo.record_order_intent(replace(intent, order_px=D(1), order_px_source='average_price'))


def test_worker_paper_uses_real_book_for_market_close(tmp_path):
    from htx_trend.paper_worker import WorkerPaperExchange
    from htx_trend.market_data import TopOfBook
    path = tmp_path / 'worker.sqlite'
    TrendLedger.initialize(path, Path(__file__).parents[1] / 'sql/schema.sql')
    ledger = TrendLedger(path)
    ledger.account(Account('a', 'test', 'PAPER'))
    exchange = WorkerPaperExchange(ledger)
    exchange.book = TopOfBook(D('90'), D('92'))
    request = PaperOrder('a', 'BTC-USDT', 'LONG', 'SELL', 1, 'MARKET', datetime.now(UTC), 'close')
    order = exchange.submit(request, Candle(1, D('999'), D('999'), D('999'), D('999')))
    assert order.order_px == D('91') and order.order_px_source == 'mid'
    assert order.signal_px is None
    ledger.close()
