"""A/B plan restore through the existing explicitly migrated test table."""
from pathlib import Path
from decimal import Decimal as D
from integration.test_mysql_continuity import connect, database, pytestmark
from htx_trend.realtime.storage import MySqlPlanStore
from htx_trend.realtime.plans import Parameters, create_engine
from test_regime import ab_frame, NOW
from test_realtime_plans import book


def test_regime_scenarios_and_brackets_survive_mysql_restart(database):
    db=connect(database)
    sql=(Path(__file__).parents[3]/'sql/htx/2026-11-02__htx_realtime_structure_plan.sql').read_text()
    with db.cursor() as c:c.execute(sql)
    db.commit()
    identities=[]
    for scenario in ('A','B'):
        for mode in ('ATR_1_1','ATR_2_1','ATR_2_2'):
            instance=scenario+mode
            p=Parameters(candidate='REGIME_AB_V1',min_net_r=D(0),target_mode=mode)
            e=create_engine(MySqlPlanStore(db),instance,parameters=p);f=ab_frame(scenario)
            e.refresh_union_frame(f,now_ms=NOW);px=f['entry'].candles[-1].close
            e.accept(book(NOW,str(px),str(px+D('.001'))),now_ms=NOW)
            px+=D('.02') if scenario=='A' else D('-.02')
            t,=e.accept(book(NOW+1,str(px),str(px+D('.001'))),now_ms=NOW+1)
            e.terminal(t.plan.plan_id,'SUBMITTED',client_order_id='same-client')
            identities.append((instance,p,t.plan.plan_id,scenario))
    db.close();db=connect(database)
    for instance,p,key,scenario in identities:
        e=create_engine(MySqlPlanStore(db),instance,parameters=p)
        assert len(e.rows)==1 and e.rows[key]['phase']=='SUBMITTED'
        assert e.rows[key]['scenario']==scenario and e.rows[key]['client_order_id']=='same-client'
        assert e.rows[key]['plan']['model']=='RT_REGIME_AB'
        e.terminal(key,'PARTIAL',filled_contracts=1)
    db.close()


def test_selected_exit_and_evidence_survive_mysql_restart(database):
    from htx_trend.realtime.plans import BAYES_EXIT
    from htx_trend.realtime.regime_bayes import Snapshot
    from test_regime_exit_selection import snapshot_for, params

    value=snapshot_for('ATR_2_2','A');p=params(value)
    db=connect(database)
    sql=(Path(__file__).parents[3]/'sql/htx/2026-11-02__htx_realtime_structure_plan.sql').read_text()
    with db.cursor() as c:c.execute(sql)
    db.commit()
    engine=create_engine(MySqlPlanStore(db),'selected',parameters=p)
    engine.snapshot=Snapshot(value);frame=ab_frame('A')
    engine.refresh_union_frame(frame,now_ms=NOW);price=frame['entry'].candles[-1].close
    engine.accept(book(NOW,str(price),str(price+D('.001'))),now_ms=NOW)
    price+=D('.02')
    trigger,=engine.accept(book(NOW+1,str(price),str(price+D('.001'))),now_ms=NOW+1)
    engine.terminal(trigger.plan.plan_id,'SUBMITTED',client_order_id='selected-client')
    expected=engine.rows[trigger.plan.plan_id]['bayes_assessment']
    db.close()

    db=connect(database)
    restored=create_engine(MySqlPlanStore(db),'selected',parameters=p)
    row=restored.rows[trigger.plan.plan_id]
    assert p.target_mode==BAYES_EXIT and row['phase']=='SUBMITTED'
    assert row['plan']['target_kind']=='ATR_2_2'
    assert row['bayes_assessment']==expected
    assert row['bayes_assessment']['selected_exit']=='ATR_2_2'
    db.close()


def test_cost_reader_sql_closed_scope_and_decimal_context_restart(database):
    import json
    from htx_trend.mysql_store import HtxTrendMySqlRepository
    from htx_trend.realtime.cost_sources import LiveCostReader
    from htx_trend.realtime.cost_observation import reconcile
    from htx_trend.research.store import digest
    from test_cost_observation import LiveClient, bundle

    db = connect(database)
    sql = (Path(__file__).parents[3]/'sql/htx/2026-11-02__htx_realtime_structure_plan.sql').read_text()
    plan = bundle(closed=10)['plan']
    with db.cursor() as c:
        c.execute(sql)
        c.execute("UPDATE htx_trend_position SET contract_code='ETH-USDT', contract_size=1, "
                  "primary_model='RT_REGIME_AB', status='DONE', entry_filled_contracts=10, remaining_contract_volume=0 WHERE id=1")
        c.execute("UPDATE htx_trend_order SET contract_code='ETH-USDT', client_order_id='1', exchange_order_id='11', status='FILLED' WHERE id=1")
        c.execute("INSERT INTO htx_trend_order(id,position_id,position_key,contract_code,leg,trend_side,order_side,position_side,"
                  "client_order_id,exchange_order_id,request_volume,status,account_id) "
                  "VALUES(2,1,'p','ETH-USDT','CLOSE','LONG','SELL','LONG','2','12',10,'FILLED',7)")
        c.execute("INSERT INTO python_trend_exit_state(position_id,state_json) VALUES(1,%s)",
                  (json.dumps({'cost_plan': plan}),))
    db.commit()
    MySqlPlanStore(db).save('observation-test', 'a'*64, {'phase': 'FILLED', 'position_id': 1})
    db.close()
    reader = LiveCostReader(lambda: HtxTrendMySqlRepository(connect(database)), lambda _: LiveClient(),
                            7, 'observation-test')
    value, = reader(5000)
    assert digest(value['plan']) == digest(plan)
    assert D(reconcile(value)['actual']['realized_net_before_funding_usdt']) == 7
    assert LiveCostReader(lambda: HtxTrendMySqlRepository(connect(database)), lambda _: LiveClient(),
                          8, 'observation-test')(5000) == []
