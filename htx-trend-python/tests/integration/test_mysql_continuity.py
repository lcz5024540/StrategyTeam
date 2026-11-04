"""Opt-in isolated MySQL 8 acceptance. Never reads production DB settings."""
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal as D
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import uuid
import sys
sys.path.insert(0,str(Path(__file__).parents[1]))

import pytest

pytestmark=pytest.mark.skipif(not os.environ.get('HTX_TEST_MYSQL_PORT'),reason='isolated MySQL port not supplied')


def connect(database=None):
    import pymysql
    port=int(os.environ['HTX_TEST_MYSQL_PORT'])
    if port==3306:raise ValueError('refuse default production MySQL port')
    return pymysql.connect(host='127.0.0.1',port=port,user='root',password='',database=database,
        autocommit=False,cursorclass=pymysql.cursors.DictCursor)


def ddl(text,table):
    return re.search(r'CREATE TABLE IF NOT EXISTS '+table+r' \([\s\S]*?\n\)[^\n]*;',text).group()


@pytest.fixture
def database():
    name='continuity_'+uuid.uuid4().hex
    admin=connect()
    with admin.cursor() as c:c.execute('CREATE DATABASE '+name)
    admin.close()
    root=Path(__file__).parents[3]
    business=(root/'coin-strategy-htx/src/main/resources/db/schema.sql').read_text()
    platform=(root/'htx-trend-python/sql/2026-10-08__python_strategy_platform.sql').read_text()
    structure=(root/'sql/htx/2026-10-07b__htx_trend_structure_lifecycle_and_final_pnl.sql').read_text()
    db=connect(name)
    with db.cursor() as c:
        for table in ('htx_trend_position','htx_trend_order','coin_strategy_ledger_event'):
            c.execute(ddl(business,table))
        c.execute(ddl(platform,'strategy_risk_reservation'))
        c.execute(ddl(structure,'htx_trend_structure_lifecycle'))
        c.execute((root/'htx-trend-python/sql/2026-10-09__python_exit_state.sql').read_text())
        c.execute('ALTER TABLE strategy_risk_reservation ADD COLUMN risk_usdt DECIMAL(36,18) NOT NULL DEFAULT 0')
        c.execute('ALTER TABLE strategy_risk_reservation ADD COLUMN margin_usdt DECIMAL(36,18) NULL')
        c.execute("INSERT INTO htx_trend_position(id,position_key,contract_code,status,side,contract_size,contract_volume,leverage,account_id,model_name,primary_model,invalidation_price) VALUES(1,'p','BTC-USDT','ENTRY_SENT','LONG',.01,10,10,7,'DONCHIAN_BREAKOUT','DONCHIAN_BREAKOUT',90)")
        c.execute("INSERT INTO htx_trend_order(id,position_id,position_key,contract_code,leg,trend_side,order_side,position_side,client_order_id,request_volume,status,account_id) VALUES(1,1,'p','BTC-USDT','ENTRY','LONG','BUY','LONG','123',10,'UNKNOWN',7)")
        c.execute("INSERT INTO strategy_risk_reservation(reservation_key,account_id,account_label,strategy_family,strategy_name,instance_key,position_key,notional_usdt,position_count,status,risk_usdt,margin_usdt) VALUES(%s,7,'primary','TREND','HTX_TREND_V1','p','p',100,1,'RESERVED',10,5)",(sha256(b'p:p').hexdigest(),))
    db.commit();db.close()
    yield name
    # Kept only inside the disposable local container for reproducible evidence.


def test_mysql_writer_lock_transfer_and_loss(database):
    from htx_trend.writer_lock import TrendWriterLock
    old=TrendWriterLock(connect(database));new=TrendWriterLock(connect(database))
    old.acquire();old.assert_owned()
    with pytest.raises(RuntimeError,match='another'):new.acquire()
    old.close();new.acquire();new.assert_owned();new.close()
    with pytest.raises(Exception):old.assert_owned()
    assert old.lost


def test_mysql_adoption_is_locked_idempotent_and_preserves_state(database):
    from htx_trend.mysql_store import HtxTrendMySqlRepository
    from htx_trend.holding_adoption import HoldingAdoption, holding_identity
    from test_holding_policy import policy
    repo=HtxTrendMySqlRepository(connect(database))
    row=repo._one('SELECT * FROM htx_trend_position WHERE id=1',())
    repo.initialize_entry_state(1,dict(highest='111',protection='104',continuation_breach_count=1))
    manifest=HoldingAdoption.create(instance_key='p',account_id=7,mode='LIVE',observed_at='2026-11-02T12:00:00+00:00',
        source_commit='a'*40,source_package='old',holdings=[dict(identity=holding_identity(row,mode='LIVE'),policy=policy().to_dict())])
    one=repo.adopt_holding_policy(1,manifest,instance_key='p')
    assert one==repo.adopt_holding_policy(1,manifest,instance_key='p')
    assert one['highest']=='111' and one['continuation_breach_count']==1
    with pytest.raises(ValueError):repo.adopt_holding_policy(1,manifest,instance_key='wrong')
    assert repo.exit_state(1)==one
    repo.close()


def test_mysql_concurrent_cumulative_fill_recovery_has_one_delta(database):
    from htx_trend.mysql_store import HtxTrendMySqlRepository
    from test_order_accounting import apply
    def reconcile(_):
        repo=HtxTrendMySqlRepository(connect(database))
        try:apply(repo)
        finally:repo.close()
    with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(reconcile,range(2)))
    repo=HtxTrendMySqlRepository(connect(database))
    assert repo._one('SELECT COUNT(*) AS n FROM coin_strategy_ledger_event',())['n']==1
    assert repo._one('SELECT remaining_contract_volume FROM htx_trend_position WHERE id=1',())['remaining_contract_volume']==4
    repo.close()


def test_mysql_restart_partial_close_rollback_and_full_close_budget(database,monkeypatch):
    from htx_trend.mysql_store import HtxTrendMySqlRepository
    from test_order_accounting import apply
    repo=HtxTrendMySqlRepository(connect(database))
    write=repo.record_ledger_event
    monkeypatch.setattr(repo,'record_ledger_event',lambda *_:(_ for _ in ()).throw(OSError('injected ledger failure')))
    with pytest.raises(OSError):apply(repo)
    assert repo._one('SELECT filled_contracts FROM htx_trend_order WHERE id=1',())['filled_contracts']==0
    monkeypatch.setattr(repo,'record_ledger_event',write)
    apply(repo,status='PARTIALLY_CANCELLED')
    repo._write("INSERT INTO htx_trend_order(id,position_id,position_key,contract_code,leg,trend_side,order_side,position_side,client_order_id,request_volume,status,account_id) VALUES(2,1,'p','BTC-USDT','CLOSE','LONG','SELL','LONG','456',4,'UNKNOWN',7)",())
    apply(repo,leg='CLOSE',client_order_id='456',filled_contracts=D(2),average_price=D(110),fee=D('.01'),realized_profit=D('.2'))
    repo.close()
    resumed=HtxTrendMySqlRepository(connect(database))
    final=dict(leg='CLOSE',client_order_id='456',filled_contracts=D(4),average_price=D(110),fee=D('.02'),realized_profit=D('.4'),status='FILLED')
    apply(resumed,**final);apply(resumed,**final)
    row=resumed._one('SELECT * FROM htx_trend_position WHERE id=1',())
    assert row['status']=='DONE' and row['remaining_contract_volume']==0 and row['final_net_profit']==D('.36')
    assert resumed._one('SELECT status FROM strategy_risk_reservation',())['status']=='RELEASED'
    resumed.close()


def test_mysql_three_process_unknown_partial_open_and_partial_close_recovery(database):
    import subprocess
    import select
    script='''import sys,time
from test_order_accounting import apply
from integration.test_mysql_continuity import connect
from htx_trend.mysql_store import HtxTrendMySqlRepository
from htx_trend.writer_lock import TrendWriterLock
from decimal import Decimal as D
name,stage=sys.argv[1:]
lock=TrendWriterLock(connect(name));lock.acquire()
repo=HtxTrendMySqlRepository(connect(name))
apply(repo)
if stage=='entry':
    print('partial-entry-committed',flush=True)
    sys.stdin.read()
else:
    apply(repo,status='PARTIALLY_CANCELLED')
    if stage=='close':
        repo._write("INSERT INTO htx_trend_order(id,position_id,position_key,contract_code,leg,trend_side,order_side,position_side,client_order_id,request_volume,status,account_id) VALUES(2,1,'p','BTC-USDT','CLOSE','LONG','SELL','LONG','456',4,'UNKNOWN',7)",())
        apply(repo,leg='CLOSE',client_order_id='456',filled_contracts=D(2),average_price=D(110),fee=D('.01'),realized_profit=D('.2'))
    else:
        final=dict(leg='CLOSE',client_order_id='456',filled_contracts=D(4),average_price=D(110),fee=D('.02'),realized_profit=D('.4'),status='FILLED')
        apply(repo,**final);apply(repo,**final)
repo.close();lock.close()
'''
    # Each new process queries the same original order identity. It never places
    # an exchange order; HTX cumulative facts are the deterministic test input.
    script=script.replace("apply(repo)\nif stage=='entry':", "if stage in ('entry','close'):apply(repo)\nif stage=='entry':")
    root=Path(__file__).parents[2]
    env={**os.environ,'PYTHONPATH':os.pathsep.join((str(root/'src'),str(root/'tests')))}
    first=subprocess.Popen([sys.executable,'-c',script,database,'entry'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    try:
        ready,_,_=select.select([first.stdout],[],[],10)
        assert ready and first.stdout.readline().strip()=='partial-entry-committed'
        first.kill();first.communicate(timeout=5)
        for phase in ('close','finish'):
            result=subprocess.run([sys.executable,'-c',script,database,phase],env=env,capture_output=True,text=True,timeout=10)
            assert result.returncode==0,result.stderr
        from htx_trend.mysql_store import HtxTrendMySqlRepository
        repo=HtxTrendMySqlRepository(connect(database))
        assert repo._one('SELECT status,final_net_profit FROM htx_trend_position WHERE id=1',())=={'status':'DONE','final_net_profit':D('.36')}
        assert repo._one('SELECT COUNT(*) AS n FROM htx_trend_order',())['n']==2
        assert repo._one('SELECT status FROM strategy_risk_reservation',())['status']=='RELEASED'
        repo.close()
    finally:
        if first.poll() is None:first.kill();first.communicate(timeout=5)


def test_mysql_union_plan_and_frozen_bracket_survive_restart(database,tmp_path):
    from htx_trend.realtime.storage import MySqlPlanStore
    from htx_trend.realtime.plans import create_engine,Parameters
    from htx_trend.realtime.indicator_union import CANDIDATE,MODEL
    from test_indicator_union import scenario
    from htx_trend.mysql_store import HtxTrendMySqlRepository
    s=scenario(tmp_path,'ATR_2_1')
    db=connect(database)
    root=Path(__file__).parents[3]
    with db.cursor() as c:
        c.execute((root/'sql/htx/2026-11-02__htx_realtime_structure_plan.sql').read_text())
    db.commit()
    store=MySqlPlanStore(db);ident=s.trigger.plan.plan_id
    row={**s.engine.rows[ident],'phase':'PARTIAL','client_order_id':'123','position_id':1}
    store.save('p',ident,row);store.save('p',ident,row)
    repo=HtxTrendMySqlRepository(db)
    repo._write('UPDATE htx_trend_position SET primary_model=%s WHERE id=1',(MODEL,))
    repo.initialize_entry_state(1,{'exit_policy_ref':'INDICATOR_BRACKET_V1/ATR_2_1','structure_target':'110','initial_stop':'90',
                                 'matched_modes':['M1','M2'],'pending_realtime_exit':{'target_remaining':0,'reason':'indicator-union-target','run_id':'union-target:1'}})
    repo.close()
    resumed=connect(database);store=MySqlPlanStore(resumed)
    engine=create_engine(store,'p',parameters=Parameters(min_net_r=D(0),candidate=CANDIDATE,target_mode='ATR_1_1'))
    assert len(engine.rows)==1 and engine.rows[ident]['phase']=='PARTIAL'
    assert engine.rows[ident]['plan']['target_kind']=='ATR_2_1'
    assert store.all('other-instance')=={}
    repo=HtxTrendMySqlRepository(resumed)
    state=repo.exit_state(1)
    assert state['exit_policy_ref']=='INDICATOR_BRACKET_V1/ATR_2_1'
    assert state['pending_realtime_exit']['target_remaining']==0
    repo.close();s.ledger.close()
