"""Explicit storage-only trial version upgrade; no activation, renewal or orders.

The runtime admission/Trial checks remain unchanged. The operator must stop the
writer; apply/rollback take its existing MySQL lock and use the existing row CAS.
Seals are integrity checks, not signatures: the confirmation is operator input.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import UTC, datetime
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import sys

from ..admission import canonical, file_hash, read_sealed, seal
from ..research.store import digest
from .trial_admission import BINDINGS, SCHEMA, validate

VERSIONS = frozenset(("source_commit", "package_sha256"))
METADATA = "storage_upgrade"
ALLOWED_FILES = frozenset({
    "src/htx_trend/realtime/storage.py",
    "src/htx_trend/realtime/local_archive.py",
    "src/htx_trend/realtime/factory.py",
    "src/htx_trend/realtime/worker.py",
    "src/htx_trend/realtime/trial_upgrade.py",
    "src/htx_trend/sealing/control.py",
    "deployment/realtime-live.env.example",
})


def exclusive_write(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    from .local_archive import sync_directory
    sync_directory(Path(path).parent)


def package_scope(old_manifest, new_manifest):
    """Reject changes outside the reviewed storage patch, including trial gates."""
    changed = []
    for name in sorted(set(old_manifest["files"]) | set(new_manifest["files"])):
        if name == "MANIFEST.json":
            continue
        before = old_manifest["files"].get(name)
        after = new_manifest["files"].get(name)
        if before == after:
            continue
        if name not in ALLOWED_FILES or after is None:
            raise ValueError("non-storage package change: " + name)
        changed.append({"path": name, "before": before, "after": after})
    if not changed:
        raise ValueError("no storage package changes")
    return changed


def prepare(old_path, old_manifest_path, archive, confirmation, output):
    """Create new bound authority from explicit confirmation; preserve validity."""
    old_path, output = Path(old_path), Path(output)
    old = read_sealed(old_path, SCHEMA)
    validate(old_path, old, entry=True, candidate=old.get("candidate"))
    if METADATA in old:
        raise ValueError("chained storage upgrades require separate review")
    old_manifest = json.loads(Path(old_manifest_path).read_bytes())
    if old_manifest["source_commit"] != old["source_commit"]:
        raise ValueError("old manifest source mismatch")
    builder = Path(__file__).resolve().parents[3] / "release/package.py"
    verified = json.loads(subprocess.check_output(
        [sys.executable, str(builder), "verify", str(archive)], text=True
    ))
    if verified["status"] != "PASS":
        raise ValueError("target package verification failed")
    import tarfile
    with tarfile.open(archive, "r:gz") as tar:
        target_manifest = json.loads(tar.extractfile("MANIFEST.json").read())
    if target_manifest["build"]["working_tree_dirty"]:
        raise ValueError("dirty target source")
    changes = package_scope(old_manifest, target_manifest)
    confirmation_raw = Path(confirmation).read_bytes()
    if not confirmation_raw.strip():
        raise ValueError("empty operator confirmation")
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    # Preserve old confirmation paths and bytes; never manufacture old consent.
    for key in ("confirmation_ref", "candidate_confirmation_ref"):
        reference = old[key]
        origin = file_hash(old_path.parent, reference, old[key.replace("_ref", "_sha256")])
        target = output / reference
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            exclusive_write(target, origin.read_bytes())
    exclusive_write(output / "old-approval.json", old_path.read_bytes())
    exclusive_write(output / "storage-upgrade-confirmation.txt", confirmation_raw)
    new = deepcopy(old)
    new.update({key: verified["bindings"][key] for key in VERSIONS})
    new[METADATA] = {
        "purpose": "CAPTURE_LOCAL_ARCHIVE",
        "previous_approval_sha256": sha256(old_path.read_bytes()).hexdigest(),
        "confirmation_ref": "storage-upgrade-confirmation.txt",
        "confirmation_sha256": sha256(confirmation_raw).hexdigest(),
        "old_manifest_sha256": sha256(Path(old_manifest_path).read_bytes()).hexdigest(),
        "new_manifest_sha256": sha256(canonical(target_manifest).encode()).hexdigest(),
        "changes": changes,
    }
    exclusive_write(output / "approval.json", canonical(seal(new)).encode())
    return validate_pair(output / "old-approval.json", output / "approval.json")


def validate_pair(old_path, new_path, *, now=None):
    now = now or datetime.now(UTC)
    old, new = (read_sealed(p, SCHEMA) for p in (old_path, new_path))
    for path, value in ((old_path, old), (new_path, new)):
        validate(path, value, entry=True, candidate=value.get("candidate"), now=now)
    excluded = VERSIONS | {"seal_hash", METADATA}
    if {k: v for k, v in old.items() if k not in excluded} != {
        k: v for k, v in new.items() if k not in excluded
    }:
        raise ValueError("storage upgrade must preserve all non-version authority")
    if METADATA in old or any(old[k] == new[k] for k in VERSIONS):
        raise ValueError("storage upgrade must change exactly source and package")
    receipt = new.get(METADATA, {})
    if (receipt.get("purpose") != "CAPTURE_LOCAL_ARCHIVE"
            or receipt.get("previous_approval_sha256") != sha256(Path(old_path).read_bytes()).hexdigest()):
        raise ValueError("storage upgrade predecessor mismatch")
    file_hash(Path(new_path).parent, receipt["confirmation_ref"], receipt["confirmation_sha256"])
    changes = receipt.get("changes", [])
    if not changes or any(c["path"] not in ALLOWED_FILES or c.get("after") is None for c in changes):
        raise ValueError("non-storage receipt changes")
    return old, new


def transition(saved, old, new, *, now_ms, rollback=False):
    """Pure CAS proposal: preserve every operational field and append audit only."""
    if saved is None:
        raise ValueError("cannot create or reset a trial during upgrade")
    from .trial import SCHEMA as STATE_SCHEMA
    if saved.get("schema") != STATE_SCHEMA:
        raise ValueError("unsupported trial state schema")
    if saved["trial_id"] != old["trial_id"] or saved["limits"] != old["limits"]:
        raise ValueError("trial identity/limits mismatch")
    if saved.get("started_ms") is None:
        raise ValueError("cannot activate a trial during upgrade")
    if not rollback and (saved.get("stop_reason") or now_ms >= saved["expires_ms"]):
        raise ValueError("cannot renew or restart a stopped trial")
    before, after = (new, old) if rollback else (old, new)
    source = {key: before[key] for key in BINDINGS}
    target = {key: after[key] for key in BINDINGS}
    action = "ROLLBACK" if rollback else "APPLY"
    history = saved.get("storage_upgrade_history", [])
    receipt_id = new["seal_hash"]
    if saved["binding"] == target:
        if history and history[-1]["receipt"] == receipt_id and history[-1]["action"] == action:
            return deepcopy(saved), False
        raise ValueError("target binding has no matching audit")
    if saved["binding"] != source:
        raise ValueError("trial predecessor binding mismatch")
    if rollback and (not history or history[-1]["receipt"] != receipt_id
                     or history[-1]["action"] != "APPLY"):
        raise ValueError("rollback requires the matching applied upgrade")
    result = deepcopy(saved)
    result["binding"] = target
    result["revision"] += 1
    result["storage_upgrade_history"] = history + [{
        "action": action, "receipt": receipt_id, "at_ms": now_ms,
        "previous_revision": saved["revision"],
        "from_binding": source, "to_binding": target,
        "preserved_state_hash": digest({
            k: v for k, v in saved.items()
            if k not in {"binding", "revision", "storage_upgrade_history"}
        }),
    }]
    return result, True


def execute(store, guard, old_path, new_path, *, rollback=False, now=None, dry_run=False):
    now = now or datetime.now(UTC)
    old, new = validate_pair(old_path, new_path, now=now)
    guard()
    saved = store.load(old["trial_id"])
    result, changed = transition(saved, old, new, now_ms=int(now.timestamp() * 1000), rollback=rollback)
    if changed and not dry_run:
        guard()
        store.save(result, saved["revision"])
    return {
        "trial_id": old["trial_id"], "action": "ROLLBACK" if rollback else "APPLY",
        "dry_run": dry_run, "changed": changed, "before_hash": digest(saved),
        "after_hash": digest(result), "before_revision": saved["revision"],
        "after_revision": result["revision"], "receipt": new["seal_hash"],
        "started_ms": result["started_ms"], "expires_ms": result["expires_ms"],
        "intent_count": len(result["intents"]), "limits": result["limits"],
        "stop_reason": result["stop_reason"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "plan", "apply", "rollback"))
    parser.add_argument("--old-approval", type=Path, required=True)
    parser.add_argument("--new-approval", type=Path)
    parser.add_argument("--old-manifest", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--confirmation", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        if not all((args.old_manifest, args.archive, args.confirmation, args.output)):
            parser.error("prepare requires old-manifest, archive, confirmation and new output directory")
        _, new = prepare(args.old_approval, args.old_manifest, args.archive, args.confirmation, args.output)
        print(json.dumps({"output": str(args.output), "receipt": new["seal_hash"], "trial_id": new["trial_id"]}))
        return
    if args.new_approval is None:
        parser.error("new-approval required")
    from ..mysql_store import MySqlSettings, connect
    from ..writer_lock import TrendWriterLock
    from .trial import MySqlTrialStore
    db = connect(MySqlSettings.from_env())
    lock = TrendWriterLock(db)
    try:
        # Same connection: loss of the execution lock also prevents the CAS write.
        lock.acquire()
        result = execute(
            MySqlTrialStore(db), lock.assert_owned, args.old_approval, args.new_approval,
            rollback=args.command == "rollback", dry_run=args.command == "plan",
        )
        print(json.dumps(result))
    finally:
        lock.close()


if __name__ == "__main__":
    main()
