"""Explicit migration on disposable loopback MySQL; no production configuration."""

import json
from pathlib import Path
import pytest
from integration.test_mysql_continuity import connect, database, pytestmark
from htx_trend.realtime.storage import MySqlPlanStore


def test_realtime_migration_restart_isolation_and_no_startup_ddl(database):
    import pymysql

    db = connect(database)
    with pytest.raises(pymysql.err.ProgrammingError):
        MySqlPlanStore(db)
    sql = (
        Path(__file__).parents[3]
        / "sql/htx/2026-11-02__htx_realtime_structure_plan.sql"
    ).read_text()
    with db.cursor() as cursor:
        cursor.execute(sql)
        cursor.execute(sql)
    db.commit()
    store = MySqlPlanStore(db)
    value = {"phase": "SUBMITTED", "client_order_id": "same-intent", "position_id": 1}
    store.save("15m", "a" * 64, value)
    store.save("4h", "a" * 64, {"phase": "REJECTED"})
    db.close()
    resumed = connect(database)
    store = MySqlPlanStore(resumed)
    assert store.all("15m") == {"a" * 64: value}
    assert store.all("4h")["a" * 64]["phase"] == "REJECTED"
    store.save("15m", "a" * 64, {**value, "phase": "PARTIAL", "filled_contracts": 2})
    with resumed.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) AS n FROM htx_trend_realtime_plan")
        assert cursor.fetchone()["n"] == 2
        cursor.execute(
            "SELECT state_json FROM htx_trend_realtime_plan WHERE instance_key=%s",
            ("15m",),
        )
        assert (
            json.loads(cursor.fetchone()["state_json"])["client_order_id"]
            == "same-intent"
        )
    resumed.close()
