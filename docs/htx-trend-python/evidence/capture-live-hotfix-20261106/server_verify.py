"""Read-only post-deployment audit; never opens a recorder or emits credentials."""
from collections import Counter
from datetime import datetime
from hashlib import sha256
import gzip
import json
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
STATE = Path("/opt/coinstrategy/htx-trend-py-state/deploy-20261106-capture-73a43e66")
UNIT = "htx-trend-worker@htx-trend-live-primary.service"
INSTANCE = "htx-trend-live-primary"


def command(*args):
    return subprocess.check_output(args, text=True, timeout=30).strip()


def read(name):
    return json.loads((STATE / name).read_bytes())


prepared = read("prepared.json")
pid = command("systemctl", "show", UNIT, "-p", "MainPID", "--value")
assert pid.isdecimal() and pid != "0", "LIVE has no process"
proc = Path("/proc") / pid
release = (proc / "cwd").resolve()
env = dict(x.split("=", 1) for x in (proc / "environ").read_text().split("\0") if "=" in x)
assert str(release) == prepared["new_release"], "wrong release"
assert env["HTX_PYTHON_SOURCE_COMMIT"] == prepared["source"]
assert env["HTX_PYTHON_PACKAGE_SHA256"] == prepared["package_sha256"]
assert env["HTX_RT_CAPTURE_LOCAL_ARCHIVE"] == "true"
assert env["HTX_RT_CAPTURE_MIN_FREE_BYTES"] == str(5 * 1024**3)
assert env["PYTHONPATH"] == str(release / "src")
old_env = read("effective-env.json")
changed_env = sorted(k for k in set(old_env) | set(env) if old_env.get(k) != env.get(k))
expected_changes = {
    "PYTHONPATH", "HTX_PYTHON_SOURCE_COMMIT", "HTX_PYTHON_PACKAGE_SHA256",
    "HTX_RT_TRIAL_APPROVAL_PATH", "HTX_HOLDING_ADOPTION_PATH",
    "HTX_RT_CAPTURE_LOCAL_ARCHIVE", "HTX_RT_CAPTURE_MIN_FREE_BYTES",
    "INVOCATION_ID", "JOURNAL_STREAM", "SYSTEMD_EXEC_PID",
}
unexpected_changes = sorted(set(changed_env) - expected_changes)
assert not unexpected_changes, "unexpected environment keys: " + ",".join(unexpected_changes)
root = Path(env["HTX_RT_CAPTURE_DIR"])
cold = root / ".capture-cold"
hot_bytes = cold_bytes = retained_bytes = aliases = cold_members = 0
seen = set()
for path in root.iterdir():
    if path.is_symlink():
        assert os.readlink(path) == ".capture-cold/" + path.name
        assert (cold / path.name).is_file() and not (cold / path.name).is_symlink()
        hot_bytes += path.lstat().st_size
        retained_bytes += path.lstat().st_size
        aliases += 1
    elif path.is_file():
        stat = path.stat()
        hot_bytes += stat.st_size
        retained_bytes += stat.st_size
        seen.add((stat.st_dev, stat.st_ino))
for path in cold.iterdir():
    assert not path.is_symlink(), "leftover or unsafe cold alias"
    stat = path.stat()
    if path.name.endswith(".json.gz"):
        assert (root / path.name).exists(), "missing stable capture reference"
        cold_bytes += stat.st_size
        cold_members += 1
    else:
        hot_bytes += stat.st_size
    if (stat.st_dev, stat.st_ino) not in seen:
        retained_bytes += stat.st_size
        seen.add((stat.st_dev, stat.st_ino))
before_capture = read("capture-before.json")
manifest = (root / "segments.jsonl").read_bytes()
assert sha256(manifest[:before_capture["manifest_bytes"]]).hexdigest() == before_capture["manifest_sha256"]
complete = manifest[:manifest.rfind(b"\n") + 1]
rows = [json.loads(line) for line in complete.splitlines()]
missing = [r["hash"] for r in rows if not (root / (r["hash"] + ".json.gz")).is_file()]
assert not missing, "missing capture payloads"
new_rows = rows[before_capture["manifest_records"]:]
assert new_rows, "no fresh capture segments"
cold_rows = [r for r in rows[:before_capture["manifest_records"]]
             if (root / (r["hash"] + ".json.gz")).is_symlink()]
sample = cold_rows[::max(1, len(cold_rows) // 48)][:48] + cold_rows[-1:] + new_rows[-3:]
verified_events = 0
latest = []
for row in sample:
    packed = (root / (row["hash"] + ".json.gz")).read_bytes()
    raw = gzip.decompress(packed)
    events = json.loads(raw)["events"]
    assert sha256(raw).hexdigest() == row["hash"]
    assert len(packed) == row["bytes"] and len(events) == row["count"]
    verified_events += len(events)
    if row in new_rows[-3:]:
        latest.append({
            "hash": row["hash"], "events": len(events),
            "first_received_ms": events[0]["received_ms"],
            "last_received_ms": events[-1]["received_ms"],
            "kinds": dict(Counter(e["kind"] for e in events)),
        })
before_trial, after_trial = read("trial-before.json"), read("trial-after.json")
excluded = {"binding", "revision", "storage_upgrade_history"}
assert {k: v for k, v in before_trial.items() if k not in excluded} == {
    k: v for k, v in after_trial.items() if k not in excluded
}, "cutover changed operational trial state"
assert after_trial["revision"] == before_trial["revision"] + 1
sys.path.insert(0, str(release / "src"))
os.environ.update(env)
from htx_trend.mysql_store import MySqlSettings, connect
from htx_trend.realtime.trial import MySqlTrialStore
from htx_trend.realtime.trial_upgrade import validate_pair
validate_pair(STATE / "authority/old-approval.json", STATE / "authority/approval.json")
db = connect(MySqlSettings.from_env())
try:
    with db.cursor() as cursor:
        cursor.execute("START TRANSACTION READ ONLY")
    current_trial = MySqlTrialStore(db).load(after_trial["trial_id"])
    assert current_trial["binding"] == after_trial["binding"]
    for key in ("schema", "trial_id", "started_ms", "expires_ms", "limits", "storage_upgrade_history"):
        assert current_trial[key] == after_trial[key], "trial invariant changed: " + key
    with db.cursor() as cursor:
        cursor.execute(
            "SELECT heartbeat_at,status,detail FROM strategy_runtime_heartbeat "
            "WHERE instance_key=%s ORDER BY id DESC LIMIT 3", (INSTANCE,),
        )
        heartbeats = cursor.fetchall()
        cursor.execute(
            "SELECT status,COUNT(*) n,MAX(updated_at) last_at FROM htx_trend_order "
            "WHERE account_id=7 AND created_at>=%s GROUP BY status",
            ("2026-11-06 13:49:00",),
        )
        orders = cursor.fetchall()
finally:
    db.rollback()
    db.close()
assert heartbeats and all("CAPTURE_" not in row["detail"] for row in heartbeats), "capture runtime failure"
assert any(row["kinds"].get("BBO", 0) for row in latest), "no BBO in fresh capture tail"
disk = os.statvfs(root)
result = {
    "checked_at": datetime.now().astimezone().isoformat(),
    "pid": pid, "release": str(release),
    "source_commit": env["HTX_PYTHON_SOURCE_COMMIT"],
    "package_sha256": env["HTX_PYTHON_PACKAGE_SHA256"],
    "unit": command("systemctl", "show", UNIT, "-p", "ActiveState", "-p", "SubState",
                    "-p", "NRestarts", "-p", "ExecMainStartTimestamp", "-p", "EnvironmentFiles"),
    "changed_env_keys": changed_env,
    "shared_current_unchanged": str(Path("/opt/coinstrategy/htx-trend-py/current").resolve()) == prepared["old_release"],
    "capture": {
        "hot_before": before_capture["hot_bytes"], "hot_bytes": hot_bytes,
        "hot_max_bytes": int(env["HTX_RT_CAPTURE_MAX_BYTES"]),
        "hot_percent": hot_bytes / int(env["HTX_RT_CAPTURE_MAX_BYTES"]) * 100,
        "hot_free_bytes": int(env["HTX_RT_CAPTURE_MAX_BYTES"]) - hot_bytes,
        "cold_payload_bytes": cold_bytes, "retained_bytes": retained_bytes,
        "cold_members": cold_members, "stable_aliases": aliases,
        "disk_available_bytes": disk.f_bavail * disk.f_frsize,
        "reserve_bytes": int(env["HTX_RT_CAPTURE_MIN_FREE_BYTES"]),
        "historical_manifest_prefix_unchanged": True,
        "historical_records": before_capture["manifest_records"],
        "all_manifest_payloads_present": len(rows), "new_segments": len(new_rows),
        "sample_verified_segments": len(sample), "sample_verified_events": verified_events,
        "last_before_received_ms": before_capture["last_record"]["last_received_ms"],
        "first_new_received_ms": new_rows[0]["first_received_ms"],
        "latest": latest,
    },
    "trial": {
        "trial_id": current_trial["trial_id"], "cutover_operational_state_unchanged": True,
        "started_ms": current_trial["started_ms"], "expires_ms": current_trial["expires_ms"],
        "limits": current_trial["limits"], "stop_reason": current_trial["stop_reason"],
        "intent_count": len(current_trial["intents"]),
        "revision_before": before_trial["revision"], "revision_after_upgrade": after_trial["revision"],
        "current_revision": current_trial["revision"],
    },
    "heartbeats": heartbeats, "orders_since_cutover": orders,
}
print(json.dumps(result, default=str, ensure_ascii=False, indent=2))
