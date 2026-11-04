"""SBOX namespaces and UNKNOWN recovery on the existing isolated MySQL fixture."""

from pathlib import Path

from htx_trend.models import TrendSide
from htx_trend.realtime.plans import create_engine
from htx_trend.realtime.storage import MySqlPlanStore
from integration.test_mysql_continuity import connect, database, pytestmark  # noqa: F401
from test_sbox import P, inputs, trigger


def test_sbox_facts_and_unknown_intent_survive_mysql_restart(database):  # noqa: F811
    db = connect(database)
    sql = (
        Path(__file__).parents[3]
        / "sql/htx/2026-11-02__htx_realtime_structure_plan.sql"
    ).read_text()
    with db.cursor() as c:
        c.execute(sql)
    db.commit()
    e = create_engine(MySqlPlanStore(db), "sbox-test", parameters=P)
    entry, hour, spec, at = inputs()
    e.refresh_frame(entry, hour, spec, now_ms=at, signal_side=TrendSide.FLAT)
    t = trigger(e, at)
    e.terminal(t.plan.plan_id, "UNKNOWN", client_order_id="same-id")
    fact_id = e.rows[t.plan.plan_id]["fact_id"]
    db.close()
    db = connect(database)
    r = create_engine(MySqlPlanStore(db), "sbox-test", parameters=P)
    assert r.rows[t.plan.plan_id]["phase"] == "UNKNOWN"
    assert r.observer.rows[fact_id]["phase"] == "OBSERVED"
    r.terminal(t.plan.plan_id, "PARTIAL", position_id=999)
    assert r.store.all("sbox-test")[t.plan.plan_id]["phase"] == "PARTIAL"
    assert r.store.all("sbox-test:SBOX_FACT_V1")[fact_id]["phase"] == "OBSERVED"
    db.close()
