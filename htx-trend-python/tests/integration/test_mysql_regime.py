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
