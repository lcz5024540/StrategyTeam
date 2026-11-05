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
