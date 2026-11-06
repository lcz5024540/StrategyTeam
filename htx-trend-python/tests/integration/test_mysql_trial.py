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


def test_storage_version_upgrade_cas_restart_and_rollback(database, tmp_path):
    from test_trial_storage_upgrade import approvals, active, operational
    from htx_trend.realtime.trial_upgrade import execute
    from htx_trend.realtime.trial_admission import BINDINGS
    from htx_trend.writer_lock import TrendWriterLock
    old_path, new_path, old, new = approvals(tmp_path)
    db = connect(database)
    sql = (Path(__file__).parents[3] / "sql/htx/2026-11-03__htx_realtime_live_trial.sql").read_text()
    with db.cursor() as cursor:
        cursor.execute(sql)
    db.commit()
    lock = TrendWriterLock(db)
    lock.acquire()
    try:
        store = MySqlTrialStore(db)
        stale = active(store, old)
        original = operational(stale.state)
        result = execute(store, lock.assert_owned, old_path, new_path)
        assert result["changed"]
        assert operational(store.load(old["trial_id"])) == original
        with pytest.raises(ValueError, match="stale"):
            stale.stop("OLD_WRITER")
        assert not execute(store, lock.assert_owned, old_path, new_path)["changed"]
    finally:
        lock.close()
    db = connect(database)
    lock = TrendWriterLock(db)
    lock.acquire()
    try:
        store = MySqlTrialStore(db)
        resumed = Trial(store, old["trial_id"], {key: new[key] for key in BINDINGS})
        assert operational(resumed.state) == original
        execute(store, lock.assert_owned, old_path, new_path, rollback=True)
        assert operational(Trial(store, old["trial_id"], {key: old[key] for key in BINDINGS}).state) == original
    finally:
        lock.close()
