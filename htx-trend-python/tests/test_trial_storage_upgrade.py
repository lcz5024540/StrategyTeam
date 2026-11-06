from copy import deepcopy
from datetime import UTC, datetime
from hashlib import sha256

import pytest

from htx_trend.admission import canonical, seal
from htx_trend.realtime.trial import SqliteTrialStore, Trial
from htx_trend.realtime.trial_admission import BINDINGS
from htx_trend.realtime.trial_upgrade import execute, package_scope, transition, validate_pair
from test_realtime_trial_admission import artifact


def approvals(tmp_path):
    old = artifact(tmp_path)
    old.update(permission="TRIAL", candidate="ORIGINAL_20_32_V1",
               candidate_confirmation_ref=old["confirmation_ref"],
               candidate_confirmation_sha256=old["confirmation_sha256"])
    old = seal(old)
    old_path = tmp_path / "old.json"
    old_path.write_text(canonical(old))
    new = deepcopy(old)
    new.update(source_commit="2" * 40, package_sha256="3" * 64)
    new["storage_upgrade"] = {
        "purpose": "CAPTURE_LOCAL_ARCHIVE",
        "previous_approval_sha256": sha256(old_path.read_bytes()).hexdigest(),
        "confirmation_ref": old["confirmation_ref"],
        "confirmation_sha256": old["confirmation_sha256"],
        "changes": [{"path": "src/htx_trend/realtime/local_archive.py",
                     "before": None, "after": {"sha256": "4" * 64}}],
    }
    new_path = tmp_path / "new.json"
    new_path.write_text(canonical(seal(new)))
    old, new = validate_pair(old_path, new_path)
    return old_path, new_path, old, new


def active(store, old):
    trial = Trial(store, old["trial_id"], {key: old[key] for key in BINDINGS})
    now_ms = int(datetime.now(UTC).timestamp() * 1000)
    trial.activate(now_ms)
    state = deepcopy(trial.state)
    state["intents"] = {"closed": {"status": "CLOSED", "risk": "1.5"},
                        "unknown": {"status": "UNKNOWN", "risk": "1"}}
    state["snapshot"] = {"loss": "7.5", "coverage": False, "observed_ms": now_ms}
    trial._save(state)
    return trial


def operational(state):
    return {k: v for k, v in state.items()
            if k not in {"binding", "revision", "storage_upgrade_history"}}


def test_apply_restart_idempotence_rollback_never_resets_trial(tmp_path):
    old_path, new_path, old, new = approvals(tmp_path)
    store = SqliteTrialStore(tmp_path / "trial.sqlite")
    try:
        original = deepcopy(active(store, old).state)
        planned = execute(store, lambda: None, old_path, new_path, dry_run=True)
        assert planned["dry_run"] and store.load(old["trial_id"]) == original
        result = execute(store, lambda: None, old_path, new_path)
        saved = store.load(old["trial_id"])
        assert result["changed"] and operational(saved) == operational(original)
        resumed = Trial(store, old["trial_id"], {key: new[key] for key in BINDINGS})
        assert resumed.state == saved
        assert not execute(store, lambda: None, old_path, new_path)["changed"]
        assert store.load(old["trial_id"]) == saved
        resumed.stop("OPERATOR_STOP")
        stopped = deepcopy(resumed.state)
        execute(store, lambda: None, old_path, new_path, rollback=True)
        restored = Trial(store, old["trial_id"], {key: old[key] for key in BINDINGS})
        assert operational(restored.state) == operational(stopped)
        assert restored.state["storage_upgrade_history"][-1]["action"] == "ROLLBACK"
        assert not execute(store, lambda: None, old_path, new_path, rollback=True)["changed"]
    finally:
        store.db.close()


@pytest.mark.parametrize("field", sorted(set(BINDINGS) - {"source_commit", "package_sha256"})
                         + ["limits", "expires_at", "issued_at", "candidate", "approved_by"])
def test_upgrade_refuses_non_version_authority_changes(tmp_path, field):
    old_path, new_path, old, new = approvals(tmp_path)
    if field == "limits":
        new[field]["trades"] = 9
    elif field in ("issued_at", "expires_at"):
        # Remain valid so the immutable-authority check, not just window, is exercised.
        from datetime import timedelta
        new[field] = (datetime.fromisoformat(new[field]) + timedelta(seconds=-1)).isoformat()
    elif field == "account_id":
        new[field] += 1
    else:
        new[field] = ("9" * len(new[field])) if field.endswith("hash") else new[field] + "-changed"
    new_path.write_text(canonical(seal(new)))
    with pytest.raises((ValueError, KeyError)):
        validate_pair(old_path, new_path)


def test_confirmation_tamper_and_predecessor_are_rejected(tmp_path):
    old_path, new_path, old, new = approvals(tmp_path)
    new["storage_upgrade"]["previous_approval_sha256"] = "0" * 64
    new_path.write_text(canonical(seal(new)))
    with pytest.raises(ValueError, match="predecessor"):
        validate_pair(old_path, new_path)
    new["storage_upgrade"]["previous_approval_sha256"] = sha256(old_path.read_bytes()).hexdigest()
    new_path.write_text(canonical(seal(new)))
    (tmp_path / "confirmation.txt").write_text("tampered")
    with pytest.raises(ValueError, match="hash"):
        validate_pair(old_path, new_path)


def test_no_new_trial_activation_renewal_or_unknown_binding(tmp_path):
    _, _, old, new = approvals(tmp_path)
    store = SqliteTrialStore(tmp_path / "trial.sqlite")
    try:
        now_ms = int(datetime.now(UTC).timestamp() * 1000)
        with pytest.raises(ValueError, match="create or reset"):
            transition(None, old, new, now_ms=now_ms)
        saved = active(store, old).state
        for key, value in (("started_ms", None), ("stop_reason", "LOSS_LIMIT"),
                           ("expires_ms", now_ms - 1), ("binding", {"wrong": "identity"})):
            invalid = {**saved, key: value}
            with pytest.raises(ValueError):
                transition(invalid, old, new, now_ms=now_ms)
        invalid = {**saved, "binding": {key: new[key] for key in BINDINGS}}
        with pytest.raises(ValueError, match="no matching audit"):
            transition(invalid, old, new, now_ms=now_ms)
    finally:
        store.db.close()


def test_lost_lock_and_stale_revision_cannot_write(tmp_path):
    old_path, new_path, old, _ = approvals(tmp_path)
    store = SqliteTrialStore(tmp_path / "trial.sqlite")
    try:
        stale = active(store, old)
        before = deepcopy(stale.state)
        calls = []

        def guard():
            calls.append(True)
            if len(calls) == 2:
                raise RuntimeError("lost writer lock")

        with pytest.raises(RuntimeError, match="lost"):
            execute(store, guard, old_path, new_path)
        assert store.load(old["trial_id"]) == before
        execute(store, lambda: None, old_path, new_path)
        with pytest.raises(ValueError, match="stale"):
            stale.stop("WRONG_OLD_WRITER")
    finally:
        store.db.close()


def test_storage_scope_rejects_strategy_admission_and_deletions():
    one = {"files": {"src/htx_trend/realtime/storage.py": {"sha256": "a"}}}
    two = {"files": {"src/htx_trend/realtime/storage.py": {"sha256": "b"}}}
    assert len(package_scope(one, two)) == 1
    for name in ("src/htx_trend/realtime/trial.py",
                 "src/htx_trend/realtime/trial_admission.py",
                 "release/config.example.json"):
        with pytest.raises(ValueError, match="non-storage"):
            package_scope(one, {"files": {**two["files"], name: {"sha256": "c"}}})
    with pytest.raises(ValueError, match="non-storage"):
        package_scope(one, {"files": {}})
