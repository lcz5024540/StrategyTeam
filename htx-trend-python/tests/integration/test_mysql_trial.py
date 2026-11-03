from pathlib import Path
import pytest
from integration.test_mysql_continuity import connect, database, pytestmark
from htx_trend.realtime.trial import MySqlTrialStore, Trial


def test_trial_explicit_migration_restart_and_stale_writer(database):
    import pymysql

    db = connect(database)
    with pytest.raises(pymysql.err.ProgrammingError):
        MySqlTrialStore(db)
    sql = (
        Path(__file__).parents[3] / "sql/htx/2026-11-03__htx_realtime_live_trial.sql"
    ).read_text()
    with db.cursor() as c:
        c.execute(sql)
        c.execute(sql)
    db.commit()
    t = Trial(MySqlTrialStore(db), "one", dict(account_id=7, instance_key="15m"))
    other_db = connect(database)
    other = Trial(
        MySqlTrialStore(other_db), "one", dict(account_id=7, instance_key="15m")
    )
    t.activate(1000)
    with pytest.raises(ValueError, match="stale"):
        other.activate(1001)
    db.close()
    other_db.close()
    resumed_db = connect(database)
    resumed = Trial(
        MySqlTrialStore(resumed_db), "one", dict(account_id=7, instance_key="15m")
    )
    assert resumed.state["started_ms"] == 1000
    resumed.stop("OPERATOR_STOP")
    resumed_db.close()
    last = connect(database)
    assert (
        Trial(
            MySqlTrialStore(last), "one", dict(account_id=7, instance_key="15m")
        ).state["stop_reason"]
        == "OPERATOR_STOP"
    )
    last.close()
