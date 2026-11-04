"""Same-trial-id reseal of an already activated realtime trial approval.

The seal function is the official issuance path in ``htx_trend.admission``.
It is a canonical SHA-256 content seal, not a separate HMAC key: trial seals
detect corruption, and trusted issuance stays this operator entrypoint.
This module does not mint a trial id, skip ``validate``, or edit ``seal_hash``
by hand.

Expires rule (preserve):
    Copy ``issued_at`` and ``expires_at`` on the seal, and ``started_ms`` and
    ``expires_ms`` on the trial state. ``remaining_ms`` stays
    ``expires_ms - now``. Reseal does not add ``duration_ms`` and does not
    renew the 24h window.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import sys
import tempfile
from copy import deepcopy
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from ..admission import canonical, read_sealed, seal
from .plans import SBOX
from .trial_admission import BINDINGS, SCHEMA, validate

EXPIRES_RULE = "preserve"
EXPIRES_RULE_DETAIL = (
    "Preserve the seal issued_at and expires_at and the trial-state "
    "started_ms and expires_ms. remaining_ms stays expires_ms minus now. "
    "Reseal does not add duration_ms and does not renew the 24h window."
)
ENV_KEYS = (
    "HTX_RT_ENTRY_ENABLED",
    "HTX_PYTHON_SOURCE_COMMIT",
    "HTX_PYTHON_PACKAGE_SHA256",
)
_HEX = set("0123456789abcdef")


@dataclass(frozen=True)
class ResealAuthorization:
    trial_id: str
    source_commit: str
    package_sha256: str
    previous_source_commit: str
    previous_package_sha256: str


PRODUCTION_AUTHORIZATION = ResealAuthorization(
    trial_id="primary-15m-sbox-20261104-5fd066de",
    source_commit="f57c409f816092c306cefb44a71769d41a8814b6",
    package_sha256="f9557fd2d6fedeeec701b6010217200fc1fcfdecf5a817dc69b2e7626392070b",
    previous_source_commit="5fd066de4b44c1e6de77120abfb163150bebd006",
    previous_package_sha256="107302a4a436eac8cbe47d5d825e9d36337273e9255b51a8018c96ae5d413150",
)


def reseal_same_trial(
    approval_path,
    state,
    *,
    authorization: ResealAuthorization,
    entry_enabled: bool,
    apply: bool,
    now: datetime,
    store=None,
) -> dict:
    """Rebind one activated trial onto a new tip and package, or preview it.

    ``apply`` false leaves the approval file and ``store`` untouched.
    ``apply`` true publishes only after ``validate`` passes for the new
    binding and fails for the previous binding. A store error restores the
    previous approval bytes.
    """
    if entry_enabled:
        raise ValueError("HTX_RT_ENTRY_ENABLED must be false before reseal")
    if now.tzinfo is None:
        raise ValueError("reseal now must be timezone-aware")
    _check_authorization(authorization)
    current = read_sealed(approval_path, SCHEMA)
    _check_identity(current, state, authorization)
    sealed = _resealed_document(current, authorization)
    new_binding = {key: sealed[key] for key in BINDINGS}
    old_binding = {key: current[key] for key in BINDINGS}
    pre_error = _expect_binding_failure(approval_path, new_binding, now)
    old_error = _validate_new_bytes(approval_path, sealed, old_binding, new_binding, now)
    report = _report(
        current,
        sealed,
        state,
        pre_error=pre_error,
        old_error=old_error,
        applied=False,
        backup_dir=None,
    )
    if not apply:
        return report
    original = Path(approval_path).read_bytes()
    mode = Path(approval_path).stat().st_mode & 0o777
    backup = _write_backup(Path(approval_path).parent, original, state, current, sealed)
    try:
        _atomic_write(Path(approval_path), canonical(sealed).encode(), 0o600)
        validate(approval_path, new_binding, entry=True, now=now, candidate=SBOX)
        if store is not None:
            store.save(_rebind_state(state, authorization), state["revision"])
    except Exception:
        _atomic_write(Path(approval_path), original, mode)
        raise
    report["applied"] = True
    report["action"] = "apply"
    report["backup_dir"] = str(backup)
    report["state_revision"] = state["revision"] + 1
    return report


def rollback_reseal(backup_dir, approval_path, *, store, expected_trial_id: str) -> dict:
    """Restore a successful reseal when the approval still matches that backup."""
    backup = Path(backup_dir)
    manifest = json.loads((backup / "manifest.json").read_text())
    if manifest.get("kind") != "same-id-reseal-backup":
        raise ValueError("not a same-id reseal backup")
    if manifest["trial_id"] != expected_trial_id:
        raise ValueError("refusing rollback for a different trial-id")
    original_state = json.loads((backup / "trial-state.json").read_text())
    if original_state["trial_id"] != expected_trial_id:
        raise ValueError("backup state trial-id mismatch")
    current = read_sealed(approval_path, SCHEMA)
    if current["trial_id"] != expected_trial_id:
        raise ValueError("refusing trial-id change during rollback")
    if current["seal_hash"] != manifest["new_seal_hash"]:
        raise ValueError("approval on disk is not the resealed artifact")
    current_state = store.load(expected_trial_id)
    _assert_only_binding_moved(current_state, original_state)
    original_approval = (backup / "trial-approval.json").read_bytes()
    mode = Path(approval_path).stat().st_mode & 0o777
    published = Path(approval_path).read_bytes()
    _atomic_write(Path(approval_path), original_approval, mode)
    try:
        restored_doc = read_sealed(approval_path, SCHEMA)
        if restored_doc["seal_hash"] != manifest["old_seal_hash"]:
            raise ValueError("rollback approval failed the seal check")
        if restored_doc["trial_id"] != expected_trial_id:
            raise ValueError("refusing trial-id change during rollback")
        restored = deepcopy(original_state)
        restored["revision"] = current_state["revision"] + 1
        store.save(restored, current_state["revision"])
    except Exception:
        _atomic_write(Path(approval_path), published, mode)
        raise
    return {
        "rolled_back": True,
        "trial_id": expected_trial_id,
        "source_commit": restored_doc["source_commit"],
        "package_sha256": restored_doc["package_sha256"],
        "expires_rule": EXPIRES_RULE,
    }


def read_runtime_env(path: Path) -> dict:
    """Return the three reseal env keys. All other lines are ignored."""
    found = {}
    for raw in Path(path).read_text().splitlines():
        line = raw.strip().removeprefix("export ")
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or key not in ENV_KEYS:
            continue
        tokens = shlex.split(value)
        found[key] = tokens[0] if len(tokens) == 1 else value
    return found


class JsonStateSink:
    """CAS writer for a fixture or operator state JSON file."""

    def __init__(self, path: Path):
        self.path = Path(path)

    def load(self, trial_id):
        state = json.loads(self.path.read_text())
        if state.get("trial_id") != trial_id:
            raise ValueError("refusing trial-id change")
        return state

    def save(self, state, previous):
        current = self.load(state["trial_id"])
        if current["revision"] != previous:
            raise ValueError("stale trial writer")
        _atomic_write(self.path, canonical(state).encode(), 0o600)


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        _run_cli(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--rollback", action="store_true")
    parser.add_argument("--trial-id", required=True)
    parser.add_argument("--source-commit")
    parser.add_argument("--package-sha256")
    parser.add_argument("--approval", type=Path, required=True)
    parser.add_argument("--runtime-env", type=Path, required=True)
    parser.add_argument("--state-json", type=Path)
    parser.add_argument("--mysql", action="store_true")
    parser.add_argument("--backup", type=Path)
    parser.add_argument("--now", help="timezone-aware ISO timestamp used for the seal window")
    return parser


def _run_cli(args) -> None:
    auth = PRODUCTION_AUTHORIZATION
    if args.trial_id != auth.trial_id:
        raise ValueError(
            "refusing trial-id change; this entrypoint reseals one authorized trial-id only"
        )
    if not args.rollback and (
        args.source_commit != auth.source_commit
        or args.package_sha256 != auth.package_sha256
    ):
        raise ValueError(
            "refusing target binding that is not the authorized source_commit and package_sha256"
        )
    env = read_runtime_env(args.runtime_env)
    if env.get("HTX_RT_ENTRY_ENABLED") != "false":
        raise ValueError("HTX_RT_ENTRY_ENABLED must be false before reseal")
    if bool(args.state_json) == bool(args.mysql):
        raise ValueError("pass exactly one of --state-json or --mysql")
    now = _parse_now(args.now)
    if args.rollback:
        if args.backup is None:
            raise ValueError("rollback requires --backup")
        if args.mysql:
            _rollback_mysql(args, auth)
            return
        print(json.dumps(rollback_reseal(
            args.backup, args.approval, store=_sink(args, apply=True),
            expected_trial_id=auth.trial_id,
        ), ensure_ascii=False, sort_keys=True, indent=2))
        return
    if args.mysql:
        _run_mysql(args, auth, now, env)
        return
    state = json.loads(args.state_json.read_text())
    report = reseal_same_trial(
        args.approval,
        state,
        authorization=auth,
        entry_enabled=False,
        apply=args.apply,
        now=now,
        store=_sink(args, apply=args.apply),
    )
    _emit(report, env)


def _rollback_mysql(args, auth) -> None:
    from ..mysql_store import MySqlSettings, connect
    from ..writer_lock import TrendWriterLock
    from .trial import MySqlTrialStore

    db = connect(MySqlSettings.from_env())
    lock = None
    try:
        lock = TrendWriterLock(connect(MySqlSettings.from_env()))
        lock.acquire()
        print(json.dumps(rollback_reseal(
            args.backup, args.approval, store=MySqlTrialStore(db),
            expected_trial_id=auth.trial_id,
        ), ensure_ascii=False, sort_keys=True, indent=2))
    finally:
        if lock is not None:
            lock.close()
        db.close()


def _run_mysql(args, auth, now, env) -> None:
    from ..mysql_store import MySqlSettings, connect
    from ..writer_lock import TrendWriterLock
    from .trial import MySqlTrialStore

    db = connect(MySqlSettings.from_env())
    lock = None
    try:
        store = MySqlTrialStore(db)
        if args.apply:
            lock = TrendWriterLock(connect(MySqlSettings.from_env()))
            lock.acquire()
        state = store.load(auth.trial_id)
        if state is None:
            raise ValueError("trial state is missing for the authorized trial-id")
        report = reseal_same_trial(
            args.approval,
            state,
            authorization=auth,
            entry_enabled=False,
            apply=args.apply,
            now=now,
            store=store if args.apply else None,
        )
        _emit(report, env)
    finally:
        if lock is not None:
            lock.close()
        db.close()


def _sink(args, *, apply: bool):
    if not apply or args.state_json is None:
        return None
    return JsonStateSink(args.state_json)


def _emit(report: dict, env: dict) -> None:
    report["runtime_env_source_commit"] = env.get("HTX_PYTHON_SOURCE_COMMIT")
    report["runtime_env_package_sha256"] = env.get("HTX_PYTHON_PACKAGE_SHA256")
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))


def _parse_now(value: str | None) -> datetime:
    now = datetime.fromisoformat(value) if value else datetime.now(UTC)
    if now.tzinfo is None:
        raise ValueError("reseal now must be timezone-aware")
    return now.astimezone(UTC)


def _check_authorization(authorization: ResealAuthorization) -> None:
    _hex("source_commit", authorization.source_commit, 40)
    _hex("package_sha256", authorization.package_sha256, 64)
    _hex("previous_source_commit", authorization.previous_source_commit, 40)
    _hex("previous_package_sha256", authorization.previous_package_sha256, 64)
    if (
        authorization.source_commit == authorization.previous_source_commit
        or authorization.package_sha256 == authorization.previous_package_sha256
    ):
        raise ValueError("reseal target must differ from the previous binding")
    if not authorization.trial_id:
        raise ValueError("refusing trial-id change")


def _check_identity(current: dict, state: dict, authorization: ResealAuthorization) -> None:
    if current.get("trial_id") != authorization.trial_id or state.get("trial_id") != authorization.trial_id:
        raise ValueError("refusing trial-id change; same-id reseal only")
    if state.get("started_ms") is None:
        raise ValueError("activated trial required; started_ms is empty")
    if current.get("permission") != "TRIAL":
        raise ValueError("reseal keeps permission TRIAL")
    if current.get("formal_admission") != "NOT_ELIGIBLE":
        raise ValueError("reseal keeps formal_admission NOT_ELIGIBLE")
    if current.get("candidate") != SBOX:
        raise ValueError("reseal keeps the SBOX candidate")
    if (
        current.get("source_commit") != authorization.previous_source_commit
        or current.get("package_sha256") != authorization.previous_package_sha256
    ):
        raise ValueError("current seal is not the authorized previous binding")
    binding = state.get("binding") or {}
    for name in BINDINGS:
        if binding.get(name) != current.get(name):
            raise ValueError("trial state binding does not match the seal: " + name)


def _resealed_document(current: dict, authorization: ResealAuthorization) -> dict:
    body = {key: value for key, value in current.items() if key != "seal_hash"}
    body["source_commit"] = authorization.source_commit
    body["package_sha256"] = authorization.package_sha256
    if body["trial_id"] != current["trial_id"] or body["permission"] != "TRIAL":
        raise ValueError("refusing trial-id change or permission edit")
    if body["issued_at"] != current["issued_at"] or body["expires_at"] != current["expires_at"]:
        raise ValueError("reseal must preserve the seal window")
    sealed = seal(body)
    for key, value in current.items():
        if key in ("source_commit", "package_sha256", "seal_hash"):
            continue
        if sealed.get(key) != value:
            raise ValueError("reseal changed a field it must preserve: " + key)
    return sealed


def _validate_new_bytes(approval_path, sealed, old_binding, new_binding, now) -> str:
    fd, name = tempfile.mkstemp(dir=Path(approval_path).parent, prefix=".reseal-", suffix=".tmp")
    temp = Path(name)
    try:
        os.write(fd, canonical(sealed).encode())
        os.close(fd)
        fd = None
        os.chmod(temp, 0o600)
        old_error = _expect_binding_failure(temp, old_binding, now)
        try:
            validate(temp, new_binding, entry=True, now=now, candidate=SBOX)
        except ValueError as exc:
            raise ValueError("new binding validate failed: " + str(exc)) from exc
        if read_sealed(temp, SCHEMA) != sealed:
            raise ValueError("resealed bytes failed seal recomputation")
        return old_error
    finally:
        if fd is not None:
            os.close(fd)
        temp.unlink(missing_ok=True)


def _expect_binding_failure(path, binding, now) -> str:
    try:
        validate(path, binding, entry=True, now=now, candidate=SBOX)
    except ValueError as exc:
        message = str(exc)
        if "binding mismatch" not in message:
            raise ValueError(
                "refusing reseal: validation failed closed for an unexpected reason: " + message
            ) from exc
        return message
    raise ValueError("refusing reseal: old binding did not fail")


def _rebind_state(state: dict, authorization: ResealAuthorization) -> dict:
    new_state = deepcopy(state)
    new_state["binding"] = dict(state["binding"])
    new_state["binding"]["source_commit"] = authorization.source_commit
    new_state["binding"]["package_sha256"] = authorization.package_sha256
    new_state["revision"] = state["revision"] + 1
    if new_state["trial_id"] != state["trial_id"] or new_state["started_ms"] != state["started_ms"]:
        raise ValueError("reseal must preserve trial-id and started_ms")
    if new_state["expires_ms"] != state["expires_ms"] or new_state["intents"] != state["intents"]:
        raise ValueError("reseal must preserve expires_ms and intents")
    return new_state


def _report(current, sealed, state, *, pre_error, old_error, applied, backup_dir) -> dict:
    return {
        "action": "apply" if applied else "dry-run",
        "applied": applied,
        "trial_id": sealed["trial_id"],
        "permission": sealed["permission"],
        "formal_admission": sealed["formal_admission"],
        "candidate": sealed["candidate"],
        "selection_note": (
            "SBOX_V1_20261104 is package selection metadata, not a seal field. "
            "Reseal does not change it. financial_admission stays NOT_ELIGIBLE."
        ),
        "old_source_commit": current["source_commit"],
        "old_package_sha256": current["package_sha256"],
        "new_source_commit": sealed["source_commit"],
        "new_package_sha256": sealed["package_sha256"],
        "pre_reseal_against_target": "FAIL",
        "pre_reseal_error": pre_error,
        "old_binding_validate": "FAIL",
        "old_binding_error": old_error,
        "new_binding_validate": "PASS",
        "started_ms": state["started_ms"],
        "expires_ms": state["expires_ms"],
        "issued_at": sealed["issued_at"],
        "expires_at": sealed["expires_at"],
        "expires_rule": EXPIRES_RULE,
        "expires_rule_detail": EXPIRES_RULE_DETAIL,
        "entry_enabled": False,
        "proc_env_mutated": False,
        "proc_env_keys": {
            "HTX_PYTHON_SOURCE_COMMIT": sealed["source_commit"],
            "HTX_PYTHON_PACKAGE_SHA256": sealed["package_sha256"],
        },
        "backup_dir": backup_dir,
        "seal_hash": sealed["seal_hash"],
        "state_revision": state["revision"] + (1 if applied else 0),
    }


def _write_backup(directory: Path, original: bytes, state: dict, current: dict, sealed: dict) -> Path:
    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    backup = directory / f"reseal-backup-{stamp}"
    backup.mkdir()
    os.chmod(backup, 0o700)
    (backup / "trial-approval.json").write_bytes(original)
    (backup / "trial-state.json").write_bytes(canonical(state).encode())
    os.chmod(backup / "trial-approval.json", 0o600)
    os.chmod(backup / "trial-state.json", 0o600)
    manifest = {
        "kind": "same-id-reseal-backup",
        "trial_id": current["trial_id"],
        "old_seal_hash": current["seal_hash"],
        "new_seal_hash": sealed["seal_hash"],
        "old_source_commit": current["source_commit"],
        "new_source_commit": sealed["source_commit"],
        "old_package_sha256": current["package_sha256"],
        "new_package_sha256": sealed["package_sha256"],
        "expires_rule": EXPIRES_RULE,
    }
    manifest_path = backup / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    )
    os.chmod(manifest_path, 0o600)
    return backup


def _assert_only_binding_moved(current: dict, original: dict) -> None:
    if current["trial_id"] != original["trial_id"]:
        raise ValueError("refusing trial-id change during rollback")
    if current["revision"] != original["revision"] + 1:
        raise ValueError("trial revision moved beyond the reseal; refusing automatic rollback")

    def norm(value):
        clone = deepcopy(value)
        clone["revision"] = 0
        clone["binding"]["source_commit"] = ""
        clone["binding"]["package_sha256"] = ""
        return clone

    if norm(current) != norm(original):
        raise ValueError("trial state changed beyond the binding reseal; refusing rollback")


def _atomic_write(path: Path, data: bytes, mode: int) -> None:
    fd, name = tempfile.mkstemp(dir=path.parent, prefix=".reseal-", suffix=".tmp")
    temp = Path(name)
    try:
        os.write(fd, data)
        os.fsync(fd)
        os.close(fd)
        fd = None
        os.chmod(temp, mode & 0o777)
        os.replace(temp, path)
    finally:
        if fd is not None:
            os.close(fd)
        if temp.exists():
            temp.unlink()


def _hex(name: str, value: str, length: int) -> None:
    if len(value) != length or any(char not in _HEX for char in value):
        raise ValueError("invalid trial digest: " + name)


if __name__ == "__main__":
    sys.exit(main())
