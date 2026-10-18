"""Exercise real repository transactions against SQLite; MySQL locks need separate acceptance."""
import re
import sqlite3
from decimal import Decimal as D
from pathlib import Path

import pytest
from htx_trend.mysql_store import HtxTrendMySqlRepository, RuntimeInstance, TradeAccount


class Cursor:
    def __init__(self, connection): self.db = connection; self.cursor = connection.raw.cursor()
    def __enter__(self): return self
    def __exit__(self, *_): self.cursor.close()
    def execute(self, sql, args=()):
        if self.db.fail_ledger and 'INSERT IGNORE INTO coin_strategy_ledger_event' in sql:
            raise RuntimeError('injected ledger failure')
        sql = sql.replace(' FOR UPDATE', '').replace('%s', '?').replace('CURRENT_TIMESTAMP(3)', 'CURRENT_TIMESTAMP').replace('INSERT IGNORE', 'INSERT OR IGNORE')
        self.cursor.execute(sql, tuple(str(x) if isinstance(x,D) else x for x in args))
        return self.cursor.rowcount
    def fetchone(self):
        row = self.cursor.fetchone(); return dict(row) if row else None
    def fetchall(self): return [dict(row) for row in self.cursor.fetchall()]


class Connection:
    def __init__(self):
        self.raw = sqlite3.connect(':memory:'); self.raw.row_factory = sqlite3.Row; self.fail_ledger = False
    def cursor(self): return Cursor(self)
    def commit(self): self.raw.commit()
    def rollback(self): self.raw.rollback()
    def close(self): self.raw.close()


@pytest.fixture
def store():
    db = Connection()
    # Use actual production position/order column names, not a second model.
    fixtures = Path(__file__).resolve().parent / 'fixtures' / 'java'
    schema = (fixtures / 'schema.sql').read_text()
    schema += (fixtures / '2026-10-07b__htx_trend_structure_lifecycle_and_final_pnl.sql').read_text()
    for table in ('htx_trend_position', 'htx_trend_order', 'htx_trend_structure_lifecycle'):
        block = schema.split(f'CREATE TABLE IF NOT EXISTS {table} (',1)[1].split('\n)',1)[0]
        columns = []
        for line in block.splitlines():
            match = re.match(r'\s*(\w+)\s+(BIGINT|VARCHAR|DECIMAL|DATETIME|INT|BOOLEAN|TEXT|LONGTEXT)',line)
            if match:
                name, kind = match.groups(); columns.append(name + (' INTEGER PRIMARY KEY' if name=='id' else ' TEXT'))
        db.raw.execute(f'CREATE TABLE {table} ({",".join(columns)})')
    columns = 'event_key event_type account_id account_label exchange market_type strategy_family strategy_name model_name run_id factor_snapshot_id symbol contract_code position_id order_id client_order_id side execution_price_usdt entry_price_usdt close_price_usdt filled_contracts contract_size amount_usdt fee_usdt funding_fee_usdt gross_pnl_usdt net_pnl_after_fee reason'.split()
    db.raw.execute('CREATE TABLE coin_strategy_ledger_event (' + ','.join(c+' TEXT'+(' UNIQUE' if c=='event_key' else '') for c in columns) + ')')
    db.raw.execute("INSERT INTO htx_trend_position(id,position_key,account_id,strategy_family,strategy_name,model_name,contract_code,side,contract_size,contract_volume,invalidation_price,status) VALUES(1,'p',7,'TREND','HTX_TREND_V1','DONCHIAN_BREAKOUT','BTC-USDT','LONG','0.01',10,90,'ENTRY_SENT')")
    db.raw.execute("INSERT INTO htx_trend_order(id,position_id,leg,client_order_id,request_volume,status,filled_contracts,fee) VALUES(1,1,'ENTRY','123',10,'ACCEPTED',0,0)")
    db.commit()
    return db, HtxTrendMySqlRepository(db)


def apply(repo, **overrides):
    values=dict(account=TradeAccount(7,'primary','PRIMARY',True,False,'test'),
        instance=RuntimeInstance('p','TREND','HTX_TREND_V1',7,'primary','LIVE',True,'unit',None),
        position_id=1,leg='ENTRY',client_order_id='123',status='PARTIALLY_FILLED',exchange_order_id=99,
        exchange_client_order_id=123,exchange_state='partially_filled',filled_contracts=D(4),
        average_price=D(100),fee=D('.02'),fee_currency='USDT',realized_profit=None)
    values.update(overrides); repo.apply_exchange_order(**values)


def test_partial_cancel_and_duplicate_callbacks_are_atomic_and_idempotent(store):
    db, repo=store
    apply(repo); apply(repo)
    p=dict(db.raw.execute('SELECT * FROM htx_trend_position').fetchone())
    assert D(p['remaining_contract_volume'])==4 and p['status']=='ENTRY_SENT'
    assert db.raw.execute('SELECT count(*) FROM coin_strategy_ledger_event').fetchone()[0]==1
    apply(repo,status='PARTIALLY_CANCELLED',exchange_state='partially_canceled')
    assert db.raw.execute('SELECT status FROM htx_trend_position').fetchone()[0]=='ACTIVE'
    assert D(db.raw.execute('SELECT entry_fee FROM htx_trend_position').fetchone()[0])==D('.02')


def test_incomplete_duplicate_snapshot_preserves_confirmed_cumulative_values(store):
    db, repo=store
    apply(repo)
    apply(repo, average_price=None, fee=None, fee_currency=None, realized_profit=None)
    order=dict(db.raw.execute('SELECT * FROM htx_trend_order').fetchone())
    assert D(order['average_price'])==D('100')
    assert D(order['fee'])==D('.02')
    assert order['fee_currency']=='USDT'
    assert db.raw.execute('SELECT count(*) FROM coin_strategy_ledger_event').fetchone()[0]==1


def test_ledger_failure_rolls_back_order_and_position_then_retry_succeeds(store):
    db,repo=store; db.fail_ledger=True
    with pytest.raises(RuntimeError,match='ledger failure'): apply(repo)
    assert D(db.raw.execute('SELECT filled_contracts FROM htx_trend_order').fetchone()[0])==0
    db.fail_ledger=False; apply(repo)
    assert D(db.raw.execute('SELECT remaining_contract_volume FROM htx_trend_position').fetchone()[0])==4


def test_remaining_and_final_pnl_follow_fills_not_close_intent(store):
    db,repo=store; apply(repo,status='PARTIALLY_CANCELLED')
    db.raw.execute("INSERT INTO htx_trend_order(id,position_id,leg,client_order_id,request_volume,status,filled_contracts,fee) VALUES(2,1,'CLOSE','456',4,'ACCEPTED',0,0)"); db.commit()
    apply(repo,leg='CLOSE',client_order_id='456',filled_contracts=D(2),average_price=D(110),fee=D('.01'),realized_profit=D('.2'))
    assert D(db.raw.execute('SELECT remaining_contract_volume FROM htx_trend_position').fetchone()[0])==2
    apply(repo,leg='CLOSE',client_order_id='456',filled_contracts=D(4),average_price=D(110),fee=D('.02'),realized_profit=D('.4'),status='FILLED')
    p=dict(db.raw.execute('SELECT * FROM htx_trend_position').fetchone())
    assert p['status']=='DONE' and D(p['remaining_contract_volume'])==0 and D(p['final_net_profit'])==D('.36')


def test_stale_or_invalid_fills_do_not_corrupt_projection(store):
    db,repo=store; apply(repo)
    with pytest.raises(ValueError,match='stale'): apply(repo,filled_contracts=D(2))
    with pytest.raises(ValueError,match='positive average'): apply(repo,filled_contracts=D(5),average_price=None)
    assert D(db.raw.execute('SELECT remaining_contract_volume FROM htx_trend_position').fetchone()[0])==4
