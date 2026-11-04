"""Same-trial-id reseal: activated SBOX trials rebind tip/package without renewal."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import UTC, datetime, timedelta
from hashlib import sha256
from pathlib import Path

import pytest

from htx_trend.admission import canonical, read_sealed, seal
from htx_trend.realtime import VERSION
from htx_trend.realtime.plans import SBOX
from htx_trend.realtime.trial import SCHEMA as STATE_SCHEMA
from htx_trend.realtime.trial import Limits, SqliteTrialStore
from htx_trend.realtime.trial_admission import BINDINGS, SCHEMA, validate
from htx_trend.realtime.trial_reseal import (
    PRODUCTION_AUTHORIZATION,
    reseal_same_trial,
    rollback_reseal,
)

ROOT = Path(__file__).resolve().parents[1]
TRIAL = "primary-15m-sbox-20261104-5fd066de"
OLD_COMMIT = "5fd066de4b44c1e6de77120abfb163150bebd006"
OLD_SHA = "107302a4a436eac8cbe47d5d825e9d36337273e9255b51a8018c96ae5d413150"
NEW_COMMIT = "f57c409f816092c306cefb44a71769d41a8814b6"
NEW_SHA = "f9557fd2d6fedeeec701b6010217200fc1fcfdecf5a817dc69b2e7626392070b"
ISSUED = datetime(2026, 11, 4, 6, 0, tzinfo=UTC)
EXPIRES = ISSUED + timedelta(hours=24)
NOW = datetime(2026, 11, 4, 7, 30, tzinfo=UTC)
STARTED_MS = 1_762_240_800_000
EXPIRES_MS = STARTED_MS + 86_400_000


def test_production_pin_is_the_authorized_sbox_rebind():
    auth = PRODUCTION_AUTHORIZATION
    assert auth.trial_id == TRIAL
    assert auth.source_commit == NEW_COMMIT
    assert auth.package_sha256 == NEW_SHA
    assert auth.previous_source_commit == OLD_COMMIT
    assert auth.previous_package_sha256 == OLD_SHA


def _artifact(tmp_path: Path, *, trial_id: str = TRIAL) -> dict:
    confirmation = tmp_path / "confirmation.txt"
    confirmation.write_text(
        "Operator confirmed SBOX trial. formal admission remains NOT_ELIGIBLE.\n"
    )
    digest = sha256(confirmation.read_bytes()).hexdigest()
    value = dict(
        schema=SCHEMA,
        synthetic=False,
        formal_admission="NOT_ELIGIBLE",
        permission="TRIAL",
        trial_id=trial_id,
        instance_key="htx-trend-live-primary",
        account_id=7,
        strategy_version=VERSION,
        profile="INTRADAY_15M",
        source_commit=OLD_COMMIT,
        package_sha256=OLD_SHA,
        config_fingerprint="c" * 64,
        parameters_hash="d" * 64,
        exit_policy_hash="e" * 64,
        contracts_hash="f" * 64,
        execution_hash="1" * 64,
        candidate=SBOX,
        limits=Limits().payload(),
        waived_conditions=["FORMAL_PROFITABILITY", "PAPER_14_DAYS"],
        approved_by="operator:test",
        confirmation_ref="confirmation.txt",
        confirmation_sha256=digest,
        candidate_confirmation_ref="confirmation.txt",
        candidate_confirmation_sha256=digest,
        issued_at=ISSUED.isoformat(),
        expires_at=EXPIRES.isoformat(),
    )
    return value


def _state(value: dict, *, started_ms=STARTED_MS) -> dict:
    binding = {key: value[key] for key in BINDINGS}
    return dict(
        schema=STATE_SCHEMA,
        trial_id=value["trial_id"],
        binding=binding,
        limits=value["limits"],
        revision=4,
        started_ms=started_ms,
        expires_ms=None if started_ms is None else EXPIRES_MS,
        stop_reason=None,
        intents={
            "cid-1": {
                "plan_id": "plan-1",
                "contract": "BTC-USDT",
                "risk": "1",
                "status": "OPEN",
                "position_id": 11,
                "submitted_ms": STARTED_MS,
            }
        },
        snapshot={"coverage": True, "observed_ms": STARTED_MS, "loss": "0"},
        last_clock_ms=STARTED_MS,
    )


def _save_approval(tmp_path: Path, value: dict) -> Path:
    path = tmp_path / "trial-approval.json"
    path.write_text(canonical(seal(value)))
    os.chmod(path, 0o600)
    return path


def _run(tmp_path: Path, value: dict, state: dict, **kwargs):
    path = _save_approval(tmp_path, value)
    report = reseal_same_trial(
        path,
        state,
        authorization=PRODUCTION_AUTHORIZATION,
        entry_enabled=kwargs.pop("entry_enabled", False),
        apply=kwargs.pop("apply", False),
        now=kwargs.pop("now", NOW),
        store=kwargs.pop("store", None),
    )
    return path, report


def test_activated_same_id_reseal_preserves_remaining_and_passes_new_binding(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    original_bytes = canonical(seal(value))
    path, report = _run(tmp_path, value, state, apply=True)
    sealed = read_sealed(path, SCHEMA)
    assert sealed["trial_id"] == TRIAL
    assert sealed["permission"] == "TRIAL"
    assert sealed["formal_admission"] == "NOT_ELIGIBLE"
    assert sealed["candidate"] == SBOX
    assert sealed["source_commit"] == NEW_COMMIT
    assert sealed["package_sha256"] == NEW_SHA
    assert sealed["issued_at"] == value["issued_at"]
    assert sealed["expires_at"] == value["expires_at"]
    assert sealed["seal_hash"] != json.loads(original_bytes)["seal_hash"]
    assert sealed == seal(sealed)
    for key in BINDINGS:
        if key not in ("source_commit", "package_sha256"):
            assert sealed[key] == value[key]
    validated, _limits = validate(
        path, {key: sealed[key] for key in BINDINGS}, entry=True, now=NOW, candidate=SBOX
    )
    assert validated["trial_id"] == TRIAL
    with pytest.raises(ValueError, match="binding mismatch: source_commit"):
        validate(
            path, {key: value[key] for key in BINDINGS}, entry=True, now=NOW, candidate=SBOX
        )
    assert report["expires_rule"] == "preserve"
    assert report["started_ms"] == STARTED_MS
    assert report["expires_ms"] == EXPIRES_MS
    assert report["new_binding_validate"] == "PASS"
    assert report["old_binding_validate"] == "FAIL"
    assert report["pre_reseal_against_target"] == "FAIL"
    assert report["applied"] is True
    assert report["issued_at"] == value["issued_at"]
    assert report["expires_at"] == value["expires_at"]
    detail = report["expires_rule_detail"].lower()
    assert "preserve" in detail
    assert "does not add" in detail
    assert "does not renew" in detail


def test_apply_updates_store_without_touching_activation_clock(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    store = SqliteTrialStore(tmp_path / "trial.sqlite")
    store.save(state, None)
    path, _report = _run(tmp_path, value, state, apply=True, store=store)
    loaded = store.load(TRIAL)
    assert loaded["started_ms"] == STARTED_MS
    assert loaded["expires_ms"] == EXPIRES_MS
    assert loaded["intents"] == state["intents"]
    assert loaded["limits"] == state["limits"]
    assert loaded["stop_reason"] is None
    assert loaded["snapshot"] == state["snapshot"]
    assert loaded["revision"] == state["revision"] + 1
    assert loaded["binding"]["trial_id"] == TRIAL
    assert loaded["binding"]["source_commit"] == NEW_COMMIT
    assert loaded["binding"]["package_sha256"] == NEW_SHA
    assert loaded["binding"]["config_fingerprint"] == value["config_fingerprint"]
    validate(
        path,
        {key: loaded["binding"][key] for key in BINDINGS},
        entry=True,
        now=NOW,
        candidate=SBOX,
    )


def test_reseal_rejects_a_different_trial_id(tmp_path):
    value = _artifact(tmp_path, trial_id="primary-15m-sbox-20261104-newid")
    state = _state(value)
    original = _save_approval(tmp_path, value).read_bytes()
    with pytest.raises(ValueError, match="trial-id"):
        reseal_same_trial(
            tmp_path / "trial-approval.json",
            state,
            authorization=PRODUCTION_AUTHORIZATION,
            entry_enabled=False,
            apply=True,
            now=NOW,
        )
    assert (tmp_path / "trial-approval.json").read_bytes() == original
    assert not list(tmp_path.glob("reseal-backup-*"))


def test_reseal_rejects_hand_edited_seal_hash(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    path = _save_approval(tmp_path, value)
    edited = json.loads(path.read_text())
    edited["seal_hash"] = "ab" + edited["seal_hash"][2:]
    path.write_text(canonical(edited))
    original = path.read_bytes()
    with pytest.raises(ValueError, match="seal hash"):
        reseal_same_trial(
            path,
            state,
            authorization=PRODUCTION_AUTHORIZATION,
            entry_enabled=False,
            apply=True,
            now=NOW,
        )
    assert path.read_bytes() == original


def test_reseal_rejects_state_that_skips_the_binding_mismatch(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    state["binding"]["source_commit"] = NEW_COMMIT
    state["binding"]["package_sha256"] = NEW_SHA
    path = _save_approval(tmp_path, value)
    original = path.read_bytes()
    with pytest.raises(ValueError, match="state binding"):
        reseal_same_trial(
            path,
            state,
            authorization=PRODUCTION_AUTHORIZATION,
            entry_enabled=False,
            apply=True,
            now=NOW,
        )
    assert path.read_bytes() == original


def test_validate_failure_does_not_publish_a_reseal(tmp_path, monkeypatch):
    value = _artifact(tmp_path)
    state = _state(value)
    path = _save_approval(tmp_path, value)
    original = path.read_bytes()

    def boom(approval, current, **kwargs):
        raise ValueError("trial approval binding mismatch: source_commit")

    monkeypatch.setattr("htx_trend.realtime.trial_reseal.validate", boom)
    with pytest.raises(ValueError, match="binding mismatch"):
        reseal_same_trial(
            path,
            state,
            authorization=PRODUCTION_AUTHORIZATION,
            entry_enabled=False,
            apply=True,
            now=NOW,
        )
    assert path.read_bytes() == original
    assert not list(tmp_path.glob("reseal-backup-*"))


def test_empty_started_ms_is_not_a_reseal(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value, started_ms=None)
    with pytest.raises(ValueError, match="started_ms"):
        _run(tmp_path, value, state, apply=True)


def test_entry_enabled_refuses_before_any_write(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    path = _save_approval(tmp_path, value)
    original = path.read_bytes()
    with pytest.raises(ValueError, match="HTX_RT_ENTRY_ENABLED"):
        reseal_same_trial(
            path,
            state,
            authorization=PRODUCTION_AUTHORIZATION,
            entry_enabled=True,
            apply=True,
            now=NOW,
        )
    assert path.read_bytes() == original


def test_dry_run_reports_fail_then_pass_without_writing(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    path = _save_approval(tmp_path, value)
    original = path.read_bytes()
    _path, report = _run(tmp_path, value, state, apply=False)
    assert path.read_bytes() == original
    assert report["applied"] is False
    assert report["pre_reseal_against_target"] == "FAIL"
    assert report["old_binding_validate"] == "FAIL"
    assert report["new_binding_validate"] == "PASS"
    assert report["trial_id"] == TRIAL
    assert report["proc_env_mutated"] is False
    assert not list(tmp_path.glob("reseal-backup-*"))


def test_store_failure_rolls_back_the_approval_bytes(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    path = _save_approval(tmp_path, value)
    original = path.read_bytes()

    class BoomStore:
        def save(self, new_state, previous):
            raise ValueError("stale trial writer")

    with pytest.raises(ValueError, match="stale trial writer"):
        reseal_same_trial(
            path,
            state,
            authorization=PRODUCTION_AUTHORIZATION,
            entry_enabled=False,
            apply=True,
            now=NOW,
            store=BoomStore(),
        )
    assert path.read_bytes() == original
    read_sealed(path, SCHEMA)


def test_rollback_restores_previous_binding_only(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    store = SqliteTrialStore(tmp_path / "trial.sqlite")
    store.save(state, None)
    path, report = _run(tmp_path, value, state, apply=True, store=store)
    rollback_reseal(
        Path(report["backup_dir"]),
        path,
        store=store,
        expected_trial_id=TRIAL,
    )
    restored = read_sealed(path, SCHEMA)
    assert restored["source_commit"] == OLD_COMMIT
    assert restored["package_sha256"] == OLD_SHA
    assert restored["trial_id"] == TRIAL
    assert restored["issued_at"] == value["issued_at"]
    loaded = store.load(TRIAL)
    assert loaded["binding"]["source_commit"] == OLD_COMMIT
    assert loaded["binding"]["package_sha256"] == OLD_SHA
    assert loaded["started_ms"] == STARTED_MS
    assert loaded["expires_ms"] == EXPIRES_MS
    assert loaded["intents"] == state["intents"]
    validate(
        path,
        {key: loaded["binding"][key] for key in BINDINGS},
        entry=True,
        now=NOW,
        candidate=SBOX,
    )
    with pytest.raises(ValueError, match="binding mismatch"):
        validate(
            path,
            {**{key: loaded["binding"][key] for key in BINDINGS}, "source_commit": NEW_COMMIT},
            entry=True,
            now=NOW,
            candidate=SBOX,
        )


def _write_env(tmp_path: Path, entry: str = "false") -> Path:
    path = tmp_path / "runtime.env"
    path.write_text(
        "\n".join(
            [
                f"HTX_RT_ENTRY_ENABLED={entry}",
                f"HTX_PYTHON_SOURCE_COMMIT={OLD_COMMIT}",
                f"HTX_PYTHON_PACKAGE_SHA256={OLD_SHA}",
                "HTX_API_SECRET=do-not-print",
            ]
        )
        + "\n"
    )
    return path


def _cli(tmp_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(ROOT / "src")
    return subprocess.run(
        [sys.executable, "-m", "htx_trend.realtime.trial_reseal", *args],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


def test_cli_rejects_a_new_trial_id(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    approval = _save_approval(tmp_path, value)
    state_path = tmp_path / "trial-state.json"
    state_path.write_text(json.dumps(state))
    result = _cli(
        tmp_path,
        "--dry-run",
        "--trial-id",
        "primary-15m-sbox-20261104-other",
        "--source-commit",
        NEW_COMMIT,
        "--package-sha256",
        NEW_SHA,
        "--approval",
        str(approval),
        "--runtime-env",
        str(_write_env(tmp_path)),
        "--state-json",
        str(state_path),
        "--now",
        NOW.isoformat(),
    )
    assert result.returncode != 0
    assert "trial-id" in (result.stderr + result.stdout)
    assert "do-not-print" not in (result.stderr + result.stdout)
    assert approval.read_bytes() == canonical(seal(value)).encode()


def test_cli_dry_run_old_binding_fails_and_target_passes(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    approval = _save_approval(tmp_path, value)
    state_path = tmp_path / "trial-state.json"
    state_path.write_text(json.dumps(state))
    before = state_path.read_bytes()
    result = _cli(
        tmp_path,
        "--dry-run",
        "--trial-id",
        TRIAL,
        "--source-commit",
        NEW_COMMIT,
        "--package-sha256",
        NEW_SHA,
        "--approval",
        str(approval),
        "--runtime-env",
        str(_write_env(tmp_path)),
        "--state-json",
        str(state_path),
        "--now",
        NOW.isoformat(),
    )
    assert result.returncode == 0, result.stderr
    report = json.loads(result.stdout)
    assert report["pre_reseal_against_target"] == "FAIL"
    assert report["old_binding_validate"] == "FAIL"
    assert report["new_binding_validate"] == "PASS"
    assert report["applied"] is False
    assert report["trial_id"] == TRIAL
    assert report["expires_rule"] == "preserve"
    assert "do-not-print" not in result.stdout
    assert state_path.read_bytes() == before


def test_cli_apply_then_rollback_keeps_the_same_activated_trial(tmp_path):
    value = _artifact(tmp_path)
    state = _state(value)
    approval = _save_approval(tmp_path, value)
    state_path = tmp_path / "trial-state.json"
    state_path.write_text(json.dumps(state))
    env = _write_env(tmp_path)
    common = [
        "--trial-id",
        TRIAL,
        "--approval",
        str(approval),
        "--runtime-env",
        str(env),
        "--state-json",
        str(state_path),
        "--now",
        NOW.isoformat(),
    ]
    applied = _cli(
        tmp_path,
        "--apply",
        "--source-commit",
        NEW_COMMIT,
        "--package-sha256",
        NEW_SHA,
        *common,
    )
    assert applied.returncode == 0, applied.stderr
    report = json.loads(applied.stdout)
    assert report["applied"] is True
    assert report["new_binding_validate"] == "PASS"
    assert report["trial_id"] == TRIAL
    assert "do-not-print" not in applied.stdout
    assert read_sealed(approval, SCHEMA)["source_commit"] == NEW_COMMIT
    loaded = json.loads(state_path.read_text())
    assert loaded["binding"]["source_commit"] == NEW_COMMIT
    assert loaded["binding"]["package_sha256"] == NEW_SHA
    assert loaded["started_ms"] == STARTED_MS
    assert loaded["expires_ms"] == EXPIRES_MS
    assert loaded["intents"] == state["intents"]
    rolled = _cli(tmp_path, "--rollback", "--backup", report["backup_dir"], *common)
    assert rolled.returncode == 0, rolled.stderr
    assert read_sealed(approval, SCHEMA)["source_commit"] == OLD_COMMIT
    restored = json.loads(state_path.read_text())
    assert restored["binding"]["source_commit"] == OLD_COMMIT
    assert restored["started_ms"] == STARTED_MS
    assert restored["expires_ms"] == EXPIRES_MS
    assert restored["intents"] == state["intents"]


def test_committed_local_dry_run_shows_old_fail_and_target_pass():
    evidence = ROOT / "release/evidence/sbox-sameid-reseal-f57c409f"
    result = _cli(
        evidence,
        "--dry-run",
        "--trial-id",
        TRIAL,
        "--source-commit",
        NEW_COMMIT,
        "--package-sha256",
        NEW_SHA,
        "--approval",
        str(evidence / "trial-approval.json"),
        "--runtime-env",
        str(evidence / "runtime.env"),
        "--state-json",
        str(evidence / "trial-state.json"),
        "--now",
        NOW.isoformat(),
    )
    assert result.returncode == 0, result.stderr
    fresh = json.loads(result.stdout)
    committed = json.loads((evidence / "dry-run.json").read_text())
    assert fresh == committed
    assert fresh["pre_reseal_against_target"] == "FAIL"
    assert fresh["old_binding_validate"] == "FAIL"
    assert fresh["new_binding_validate"] == "PASS"
    assert fresh["old_source_commit"] == OLD_COMMIT
    assert fresh["new_source_commit"] == NEW_COMMIT
    assert fresh["new_package_sha256"] == NEW_SHA
    assert fresh["trial_id"] == TRIAL
    assert fresh["applied"] is False
    assert fresh["expires_rule"] == "preserve"
