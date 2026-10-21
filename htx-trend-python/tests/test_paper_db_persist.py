"""Offline PAPER projection against the real repository and SQLite adapter."""
from datetime import UTC, datetime
from decimal import Decimal as D
import json
import pytest
from test_order_accounting import store
from htx_trend.paper_db_persist import ACCOUNT, STRATEGY, LIVE_POSITION_SUMMARY_SQL, backfill_paper_state_to_mysql
from htx_trend.paper_worker import PaperFileLedger, WorkerPaperExchange
from htx_trend.paper import PaperOrder
from htx_trend.market_data import Candle

@pytest.fixture(autouse=True)
def forbid_live_executor(monkeypatch):
    from htx_trend.live import HtxLiveExecutor
    monkeypatch.setattr(HtxLiveExecutor, '__init__',
                        lambda *a, **k: pytest.fail('LIVE executor invoked'))


@pytest.fixture
def paper_store(store):
    db, repo = store
    db.raw.execute('ALTER TABLE htx_trend_order ADD COLUMN reduce_only TEXT')
    db.raw.execute('DELETE FROM htx_trend_order')
    db.raw.execute('DELETE FROM htx_trend_position')
    db.raw.execute('CREATE UNIQUE INDEX pk_unique ON htx_trend_position(position_key)')
    db.raw.execute('CREATE UNIQUE INDEX co_unique ON htx_trend_order(client_order_id)')
    db.raw.execute('CREATE TABLE htx_trade_account(id INTEGER PRIMARY KEY,account_label TEXT UNIQUE,account_role TEXT,enabled INTEGER,trend_enabled INTEGER,mirror_enabled INTEGER,sync_leverage INTEGER,note TEXT)')
    db.raw.execute("INSERT INTO htx_trade_account VALUES(7,'primary','PRIMARY',1,1,0,1,'untouched')")
    db.commit()
    return db, repo

def make_state(tmp_path, count=3, client_id_length=None):
    ledger = PaperFileLedger(tmp_path/'paper.json', {'instance': ACCOUNT.split(':')[1], 'strategy': STRATEGY}, mirror=lambda _: None)
    exchange = WorkerPaperExchange(ledger)
    exchange.contract_size = D('.01')
    when = datetime(2026,10,1,tzinfo=UTC)
    for i in range(count):
        run, side = f'paper-run-{i}', 'LONG' if i%2 == 0 else 'SHORT'
        request = PaperOrder(ACCOUNT,'BTC-USDT',side,'BUY' if side=='LONG' else 'SELL',10,'MARKET',when,f'paper-entry-{i}',strategy_run_id=run)
        exchange.submit(request,Candle(1,D(100),D(100),D(100),D(100)))
        p = exchange.open_position(ACCOUNT,'BTC-USDT',side,10,D(100),when,run)
        ledger.save_position_context(p.position_id,{'contract_size':'.01','model':'DONCHIAN_BREAKOUT'})
        exchange.reduce_position(p,quantity=10,exit_price=D(110) if side=='LONG' else D(90),contract_size=D('.01'),when=when,run_id=f'paper-close-{i}')
    ledger.checkpoint()
    state = json.loads(ledger.path.read_text())
    ledger.close()
    if client_id_length is not None:
        # Synthetic fixture only; never rewrite an existing runtime checkpoint.
        for i, order in enumerate(state['orders'].values()):
            prefix = 'paper-entry-' if order['position_id'] is None else 'paper-close-'
            order['client_order_id'] = (prefix + str(i) + '-').ljust(client_id_length, 'a')
    return state

@pytest.mark.parametrize('client_id_length', [None, 65, 93, 128])
def test_backfill_old_window_six_fills_three_closed_idempotent(tmp_path,paper_store,monkeypatch,client_id_length):
    db,repo=paper_store
    from htx_trend.live import HtxLiveExecutor
    monkeypatch.setattr(HtxLiveExecutor,'__init__',lambda *a,**k: pytest.fail('LIVE executor invoked'))
    state=make_state(tmp_path, client_id_length=client_id_length)
    original = json.dumps(state, sort_keys=True)
    expected_ids = {o['client_order_id'] for o in state['orders'].values()}
    if client_id_length is not None:
        assert all(len(client) == client_id_length for client in expected_ids)
    assert len(state['fills'])==6 and len(state['orders'])==6
    assert all(p['status']=='CLOSED' for p in state['positions'].values())
    assert all(o['status']=='FILLED' for o in state['orders'].values())
    assert backfill_paper_state_to_mysql(json.dumps(state),repo)=={'fills_added':6,'positions':3,'orders':6}
    for table in ('htx_trend_order', 'coin_strategy_ledger_event'):
        assert {r[0] for r in db.raw.execute(f'SELECT client_order_id FROM {table}')} == expected_ids
    assert json.dumps(state, sort_keys=True) == original
    paper_id=db.raw.execute("SELECT id FROM htx_trade_account WHERE account_label='paper'").fetchone()[0]
    for table,count in [('htx_trend_order',6),('htx_trend_position',3),('coin_strategy_ledger_event',6)]:
        rows=list(db.raw.execute(f'SELECT * FROM {table}'))
        assert len(rows)==count
        assert all(r['account_label']=='paper' and int(r['account_id'])==paper_id and r['strategy_name']==STRATEGY for r in rows)
    for r in db.raw.execute('SELECT * FROM htx_trend_position'):
        assert r['position_key'].startswith('paper-') and r['status']=='DONE' and D(r['remaining_contract_volume'])==0
        assert r['final_pnl_source']=='PAPER_SIMULATED' and D(r['final_gross_profit'])==1
        fee=D('.0084') if r['side']=='LONG' else D('.0076')
        assert D(r['final_total_fee'])==fee and D(r['final_net_profit'])==1-fee
    for r in db.raw.execute('SELECT * FROM coin_strategy_ledger_event'):
        assert D(r['amount_usdt'])==D(r['execution_price_usdt'])*D(r['filled_contracts'])*D('.01')
        assert D(r['fee_usdt'])==D(r['amount_usdt'])*D('.0004')
    snapshot=list(db.raw.iterdump())
    assert backfill_paper_state_to_mysql(state,repo)['fills_added']==0
    assert list(db.raw.iterdump())==snapshot
    assert tuple(db.raw.execute('SELECT * FROM htx_trade_account WHERE id=7').fetchone())==(7,'primary','PRIMARY',1,1,0,1,'untouched')

def test_open_then_close_and_atomic_retry(tmp_path,paper_store):
    db,repo=paper_store
    state=make_state(tmp_path,1)
    opened=json.loads(json.dumps(state))
    opened['fills']=opened['fills'][:1]
    opened['orders']={k:o for k,o in opened['orders'].items() if o['position_id'] is None}
    next(iter(opened['positions'].values())).update(status='OPEN',quantity=10,closed_at=None)
    assert backfill_paper_state_to_mysql(opened,repo)['fills_added']==1
    assert db.raw.execute('SELECT status FROM htx_trend_position').fetchone()[0]=='ACTIVE'
    db.fail_ledger=True
    with pytest.raises(RuntimeError,match='ledger failure'): backfill_paper_state_to_mysql(state,repo)
    assert db.raw.execute('SELECT count(*) FROM htx_trend_order').fetchone()[0]==1
    db.fail_ledger=False
    assert backfill_paper_state_to_mysql(state,repo)['fills_added']==1
    assert db.raw.execute('SELECT status FROM htx_trend_position').fetchone()[0]=='DONE'

def test_json_committed_before_failed_mirror(tmp_path,caplog):
    path=tmp_path/'state.json'
    def fail(state):
        assert json.loads(path.read_text())==state
        raise RuntimeError('offline')
    ledger=PaperFileLedger(path,{'test':True},mirror=fail)
    ledger.data['bars']['BTC-USDT']=123
    ledger.checkpoint()
    assert json.loads(path.read_text())['bars']['BTC-USDT']==123
    assert 'JSON remains authoritative' in caplog.text
    ledger.close()

def test_default_live_summary_excludes_paper(tmp_path,paper_store):
    db,repo=paper_store
    backfill_paper_state_to_mysql(make_state(tmp_path),repo)
    db.raw.execute("INSERT INTO htx_trend_position(position_key,account_label,final_net_profit) VALUES('live-p','primary',12)")
    row=db.raw.execute(LIVE_POSITION_SUMMARY_SQL).fetchone()
    assert row['position_count']==1 and D(row['net_profit'])==12

def test_no_config_never_connects(monkeypatch):
    import htx_trend.paper_db_persist as module
    monkeypatch.delenv('HTX_PAPER_DB_HOST',raising=False)
    monkeypatch.setattr(module,'connect',lambda *_: pytest.fail('unexpected network'))
    module.mirror_checkpoint({})

def test_reject_live_identity_before_write(tmp_path,paper_store):
    db,repo=paper_store
    state=make_state(tmp_path,1)
    state['fills'][0]['account_id']='primary'
    before=list(db.raw.iterdump())
    with pytest.raises(ValueError,match='non-paper'): backfill_paper_state_to_mysql(state,repo)
    assert list(db.raw.iterdump())==before


def test_mirror_cannot_mutate_authoritative_state(tmp_path, paper_store):
    _, repo = paper_store
    state = make_state(tmp_path, 1)
    path = tmp_path / 'checkpoint.json'
    expected = json.dumps(state, default=str, sort_keys=True)
    def mirror(copy):
        backfill_paper_state_to_mysql(copy, repo)
        copy['fills'].clear()
    ledger = PaperFileLedger(path, state['identity'], mirror=mirror)
    ledger.data = state
    ledger.checkpoint()
    assert path.read_text() == expected
    assert json.dumps(ledger.data, default=str, sort_keys=True) == expected
    ledger.close()


@pytest.mark.parametrize('invalid', ['paper-close-' + 'x' * 117, '', None, 123])
def test_invalid_client_id_fails_before_any_write(tmp_path, paper_store, monkeypatch, invalid):
    db, repo = paper_store
    state = make_state(tmp_path, 1)
    next(iter(state['orders'].values()))['client_order_id'] = invalid
    original = json.dumps(state, sort_keys=True)
    before = list(db.raw.iterdump())
    monkeypatch.setattr(repo, '_write', lambda *a, **k: pytest.fail('write before validation'))
    message = 'client_order_id length 129 exceeds maximum 128' if isinstance(invalid, str) and invalid else 'client_order_id must be a non-empty str'
    with pytest.raises(ValueError, match=message):
        backfill_paper_state_to_mysql(state, repo)
    assert json.dumps(state, sort_keys=True) == original
    assert list(db.raw.iterdump()) == before


def test_close_net_includes_allocated_entry_fee_and_replay_preserves_history(tmp_path, paper_store):
    db, repo = paper_store
    state = make_state(tmp_path, 1)
    close = next(f for f in state['fills'] if state['orders'][f['order_id']]['position_id'])
    # 同一平仓订单的两次成交；分摊 40% 和 60% 的开仓费。
    close['quantity'] = 4
    second = dict(close, fill_id='second-close', quantity=6)
    state['fills'].append(second)
    backfill_paper_state_to_mysql(state, repo)
    events = list(db.raw.execute("SELECT * FROM coin_strategy_ledger_event WHERE event_type='CLOSE_FILL'"))
    assert len(events) == 2
    for event in events:
        q = D(event['filled_contracts'])
        assert D(event['net_pnl_after_fee']) == D(event['gross_pnl_usdt'])-D(event['fee_usdt'])-D('.004')*q/10
    assert sum(D(e['net_pnl_after_fee']) for e in events) == D(db.raw.execute('SELECT final_net_profit FROM htx_trend_position').fetchone()[0])
    db.raw.execute("UPDATE coin_strategy_ledger_event SET net_pnl_after_fee='123' WHERE event_type='CLOSE_FILL'")
    db.commit()
    assert backfill_paper_state_to_mysql(state, repo)['fills_added'] == 0
    assert all(r[0]=='123' for r in db.raw.execute("SELECT net_pnl_after_fee FROM coin_strategy_ledger_event WHERE event_type='CLOSE_FILL'"))


def test_partial_checkpoint_then_final_close_allocates_entry_fee_once(tmp_path, paper_store):
    db, repo = paper_store
    final = make_state(tmp_path, 1)
    partial = json.loads(json.dumps(final))
    close = next(f for f in partial['fills'] if partial['orders'][f['order_id']]['position_id'])
    close['quantity'] = 4
    next(iter(partial['positions'].values())).update(quantity=6, status='OPEN', closed_at=None)
    backfill_paper_state_to_mysql(partial, repo)
    first = db.raw.execute("SELECT net_pnl_after_fee FROM coin_strategy_ledger_event WHERE event_type='CLOSE_FILL'").fetchone()[0]
    assert D(first)==D('.4')-D('.00176')-D('.0016')
    final['fills'] = partial['fills']+[dict(close, fill_id='remaining-six', quantity=6)]
    backfill_paper_state_to_mysql(final, repo)
    nets=[D(r[0]) for r in db.raw.execute("SELECT net_pnl_after_fee FROM coin_strategy_ledger_event WHERE event_type='CLOSE_FILL'")]
    assert sum(nets)==D('.9916')
    assert D(db.raw.execute('SELECT final_net_profit FROM htx_trend_position').fetchone()[0])==sum(nets)
