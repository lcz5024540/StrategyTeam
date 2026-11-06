"""One-instance capture hotfix. Run prepare, inspect receipt, then cutover.

Credentials stay in server-only 0600 snapshots. Never sends an exchange mutation.
The original unit/env/shared current remain untouched; one new drop-in is used.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime
from hashlib import sha256
import json
import os
from pathlib import Path
import pwd
import subprocess
import sys
import tarfile
import time

UNIT = "htx-trend-worker@htx-trend-live-primary.service"
INSTANCE = "htx-trend-live-primary"
OLD_SOURCE = "7a9620bfb5b3261b3bb88ba46d0ed85e43bd8729"
OLD_PACKAGE = "3b84f108d47c2c796591ba47096b710314817ca1f8d815de1ffd6f6fdb3cf4f0"
SOURCE = "73a43e665595a7df39506b0f9f3023f1700cbe28"
PACKAGE = "8fe339fca784a0b00ca0246143d0e6fdcb83de3fed2143881555f8322aef0d45"
VERSION = "0.1.7-realtime-live-73a43e66"
STATE = Path("/opt/coinstrategy/htx-trend-py-state/deploy-20261106-capture-73a43e66")
RELEASE = Path("/opt/coinstrategy/htx-trend-py/releases") / VERSION
DROPIN = Path("/etc/systemd/system") / (UNIT + ".d") / "60-capture-hotfix.conf"
RESERVE = 5 * 1024**3


def command(*args, **kwargs):
    return subprocess.check_output(args, text=True, timeout=kwargs.pop("timeout", 40), **kwargs).strip()


def write(path, value, *, mode=0o600):
    raw = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False, indent=2).encode()
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, mode)
    with os.fdopen(fd, "wb") as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    fd = os.open(Path(path).parent, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def ownership():
    user = pwd.getpwnam("ubuntu")
    for parent in (STATE,):
        os.chown(parent, user.pw_uid, user.pw_gid)
        for path in parent.rglob("*"):
            os.chown(path, user.pw_uid, user.pw_gid)


@contextmanager
def worker_identity():
    """New capture control files must be writable by the actual service user."""
    user = pwd.getpwnam("ubuntu")
    original_uid, original_gid = os.geteuid(), os.getegid()
    os.setegid(user.pw_gid)
    os.seteuid(user.pw_uid)
    try:
        yield
    finally:
        os.seteuid(original_uid)
        os.setegid(original_gid)


def current():
    pid = command("systemctl", "show", UNIT, "-p", "MainPID", "--value")
    if not pid.isdecimal() or pid == "0":
        raise ValueError("old LIVE not running")
    proc = Path("/proc") / pid
    env = dict(x.split("=", 1) for x in (proc / "environ").read_text().split("\0") if "=" in x)
    if env.get("HTX_PYTHON_SOURCE_COMMIT") != OLD_SOURCE or env.get("HTX_PYTHON_PACKAGE_SHA256") != OLD_PACKAGE:
        raise ValueError("LIVE changed; refuse stale cutover")
    return pid, (proc / "cwd").resolve(), env


def verify_installed(root, manifest):
    for name, member in manifest["files"].items():
        if name == "MANIFEST.json":
            from copy import deepcopy
            clean = deepcopy(manifest)
            clean["files"]["MANIFEST.json"]["sha256"] = ""
            raw = json.dumps(clean, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
        else:
            path = root / name
            if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
                raise ValueError("unsafe installed member")
            raw = path.read_bytes()
        if sha256(raw).hexdigest() != member["sha256"]:
            raise ValueError("installed package hash mismatch: " + name)


def exchange_empty(env):
    from htx_trend.mysql_store import MySqlSettings, connect
    from htx_trend.credentials import decrypt_if_encrypted
    from htx_trend.htx_v5 import HtxV5Client
    os.environ.update(env)
    db = connect(MySqlSettings.from_env())
    try:
        with db.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT api_key_cipher,secret_key_cipher,base_url FROM htx_trade_account WHERE id=7")
            account = cursor.fetchone()
    finally:
        db.rollback()
        db.close()

    class ReadOnlyClient(HtxV5Client):
        def _signed(self, method, path, *args, **kwargs):
            if method != "GET" or path not in {"/v5/trade/position/opens", "/v5/trade/order/opens"}:
                raise ValueError("request outside read-only scope")
            return super()._signed(method, path, *args, **kwargs)

    with ReadOnlyClient(
        decrypt_if_encrypted(account["api_key_cipher"], private_key_path=env["APP_RSA_PRIVATE_KEY_PATH"]),
        decrypt_if_encrypted(account["secret_key_cipher"], private_key_path=env["APP_RSA_PRIVATE_KEY_PATH"]),
        base_url=account["base_url"], live_trading_enabled=False,
    ) as client:
        values = [client._signed("GET", "/v5/trade/position/opens")]
        values += [client._signed("GET", "/v5/trade/order/opens", {"margin_mode": mode, "limit": "100"})
                   for mode in ("isolated", "cross")]
    if any(value.get("code") != 200 or value.get("data") != [] for value in values):
        raise ValueError("exchange is not verified empty; leave the existing service in control")
    return {"positions": 0, "isolated_orders": 0, "cross_orders": 0,
            "checked_at": datetime.now().astimezone().isoformat()}


def prepare(archive):
    pid, old_release, env = current()
    if sha256(archive.read_bytes()).hexdigest() != PACKAGE:
        raise ValueError("archive SHA mismatch")
    old_manifest = json.loads((old_release / "MANIFEST.json").read_bytes())
    verify_installed(old_release, old_manifest)
    receipt = json.loads(command(sys.executable, str(old_release / "release/package.py"), "verify", str(archive)))
    if receipt["status"] != "PASS" or receipt["bindings"]["source_commit"] != SOURCE:
        raise ValueError("target package mismatch")
    if STATE.exists() or RELEASE.exists() or DROPIN.exists():
        raise ValueError("existing deployment output; inspect instead of overwriting")
    capture = Path(env["HTX_RT_CAPTURE_DIR"])
    if capture.resolve() != capture or int(env["HTX_RT_CAPTURE_MAX_BYTES"]) != 2 * 1024**3:
        raise ValueError("unexpected capture layout or budget")
    disk = os.statvfs(capture)
    if disk.f_bavail * disk.f_frsize < RESERVE + 128 * 1024**2:
        raise ValueError("insufficient deployment disk reserve")
    STATE.mkdir(mode=0o700)
    RELEASE.mkdir()
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            target = RELEASE / member.name
            if not member.isfile() or not target.resolve().is_relative_to(RELEASE):
                raise ValueError("unsafe package member")
            target.parent.mkdir(parents=True, exist_ok=True)
            write(target, tar.extractfile(member).read(), mode=0o644)
    verify_installed(RELEASE, json.loads((RELEASE / "MANIFEST.json").read_bytes()))
    sys.path.insert(0, str(RELEASE / "src"))
    from htx_trend.realtime.trial_upgrade import prepare as authority_prepare
    from htx_trend.realtime.storage import SegmentRecorder
    from htx_trend.tools.holding_export import process_observation
    observed = process_observation(int(pid), INSTANCE)
    if observed["holdings"] or observed.get("pending_orders"):
        raise ValueError("DB holding/pending inventory is not empty")
    exchange = exchange_empty(env)
    write(STATE / "effective-env.json", env)
    write(STATE / "operator-confirmation.txt", (
        "Human request in this Codex conversation on 2026-11-06:\n"
        "如果不发布，那么live可能会被填充满，很紧迫，我需要的是打包解决live的问题\n"
        "Scope: capture-only LIVE hotfix, same trial/account/limits/expiry; no activation or renewal.\n"
        f"Operator target source: {SOURCE}\nOperator target package: {PACKAGE}\n"
    ).encode())
    authority_prepare(
        Path(env["HTX_RT_TRIAL_APPROVAL_PATH"]), old_release / "MANIFEST.json", archive,
        STATE / "operator-confirmation.txt", STATE / "authority",
    )
    # Exercise the real package and filesystem before stopping the old worker.
    sample = STATE / "linux-smoke"
    sample.mkdir()
    records = [json.loads(line) for line in (capture / "segments.jsonl").read_text().splitlines()[-14:-2]]
    for row in records:
        write(sample / (row["hash"] + ".json.gz"), (capture / (row["hash"] + ".json.gz")).read_bytes())
    write(sample / "segments.jsonl", b"".join((json.dumps(row) + "\n").encode() for row in records))
    original = SegmentRecorder(sample)
    events = original.verify()
    maximum = int(original.used / 0.9)
    original.close()
    recorder = SegmentRecorder(sample, max_bytes=maximum, local_archive=True, local_archive_min_free_bytes=RESERVE)
    try:
        deadline = time.monotonic() + 30
        while recorder.used > recorder.local_archive.target and not recorder.failure:
            if time.monotonic() > deadline:
                raise TimeoutError("Linux smoke archival")
            time.sleep(.02)
        if recorder.failure or recorder.verify() != events:
            raise ValueError("Linux smoke failed")
        smoke = recorder.storage_status()
    finally:
        recorder.close()
    result = {"phase": "PREPARED", "pid": pid, "old_release": str(old_release),
              "new_release": str(RELEASE), "source": SOURCE, "package_sha256": PACKAGE,
              "exchange": exchange, "linux_smoke": smoke, "linux_events": len(events),
              "prepared_at": datetime.now().astimezone().isoformat()}
    write(STATE / "prepared.json", result)
    ownership()
    print(json.dumps(result), flush=True)


def cutover():
    prepared = json.loads((STATE / "prepared.json").read_bytes())
    pid, old_release, env = current()
    if pid != prepared["pid"] or str(old_release) != prepared["old_release"]:
        raise ValueError("old process changed since preparation")
    sys.path.insert(0, str(RELEASE / "src"))
    from htx_trend.realtime.trial_upgrade import execute
    from htx_trend.realtime.trial import MySqlTrialStore
    from htx_trend.mysql_store import MySqlSettings, connect
    from htx_trend.writer_lock import TrendWriterLock
    from htx_trend.tools.holding_export import _probe, from_observation
    from htx_trend.realtime.storage import SegmentRecorder
    before_exchange = exchange_empty(env)
    print(json.dumps({"phase": "STOPPING_OLD", "pid": pid, "exchange": before_exchange}), flush=True)
    applied = False
    try:
        command("systemctl", "stop", UNIT, timeout=110)
        if command("systemctl", "show", UNIT, "-p", "MainPID", "--value") != "0":
            raise ValueError("old worker has not stopped")
        observed = _probe(old_release, env, INSTANCE)
        if observed["holdings"] or observed.get("pending_orders"):
            raise ValueError("inventory changed before final handoff")
        write(STATE / "exchange-after-stop.json", exchange_empty(env))
        adoption = from_observation(observed).to_dict()
        write(STATE / "adoption-final.json", adoption)
        capture_root = Path(env["HTX_RT_CAPTURE_DIR"])
        manifest_before = (capture_root / "segments.jsonl").read_bytes()
        write(STATE / "capture-before.json", {
            "hot_bytes": sum(p.stat().st_size for p in capture_root.iterdir() if p.is_file()),
            "manifest_bytes": len(manifest_before),
            "manifest_sha256": sha256(manifest_before).hexdigest(),
            "manifest_records": len(manifest_before.splitlines()),
            "last_record": json.loads(manifest_before.splitlines()[-1]),
            "checked_at": datetime.now().astimezone().isoformat(),
        })
        db = connect(MySqlSettings.from_env())
        lock = TrendWriterLock(db)
        try:
            lock.acquire()
            store = MySqlTrialStore(db)
            approval = json.loads((STATE / "authority/old-approval.json").read_bytes())
            saved = store.load(approval["trial_id"])
            write(STATE / "trial-before.json", saved)
            result = execute(store, lock.assert_owned, STATE / "authority/old-approval.json", STATE / "authority/approval.json")
            applied = True
            write(STATE / "trial-upgrade-result.json", result)
            write(STATE / "trial-after.json", store.load(approval["trial_id"]))
        finally:
            lock.close()
        with worker_identity():
            recorder = SegmentRecorder(
                env["HTX_RT_CAPTURE_DIR"], max_bytes=int(env["HTX_RT_CAPTURE_MAX_BYTES"]),
                local_archive=True, local_archive_min_free_bytes=RESERVE,
            )
            try:
                deadline = time.monotonic() + 600
                next_progress = time.monotonic() + 20
                while recorder.used > recorder.local_archive.target and not recorder.failure:
                    if time.monotonic() > deadline:
                        raise TimeoutError("initial archival exceeded cutover budget")
                    if time.monotonic() >= next_progress:
                        print(json.dumps({"phase": "ARCHIVING", **recorder.storage_status()}), flush=True)
                        next_progress = time.monotonic() + 20
                    time.sleep(.2)
                if recorder.failure:
                    raise ValueError("initial archival failed: " + recorder.failure)
                write(STATE / "capture-after-archive.json", recorder.storage_status())
                print(json.dumps({"phase": "ARCHIVED", **recorder.storage_status()}), flush=True)
            finally:
                recorder.close()
        # Append only explicit overrides; retain every original EnvironmentFile
        # and unit environment setting (including proxies/certificate settings).
        selected = dict(
            PYTHONPATH=str(RELEASE / "src"), HTX_PYTHON_SOURCE_COMMIT=SOURCE,
            HTX_PYTHON_PACKAGE_SHA256=PACKAGE,
            HTX_RT_TRIAL_APPROVAL_PATH=str(STATE / "authority/approval.json"),
            HTX_HOLDING_ADOPTION_PATH=str(STATE / "adoption-final.json"),
            HTX_RT_CAPTURE_LOCAL_ARCHIVE="true", HTX_RT_CAPTURE_MIN_FREE_BYTES=str(RESERVE),
        )
        write(STATE / "runtime-live.env", ("\n".join(k + "=" + json.dumps(v) for k, v in sorted(selected.items())) + "\n").encode())
        ownership()
        write(DROPIN, (
            "[Service]\nWorkingDirectory=" + str(RELEASE)
            + "\nEnvironmentFile=" + str(STATE / "runtime-live.env") + "\n"
        ).encode(), mode=0o644)
        command("systemctl", "daemon-reload")
        command("systemctl", "start", UNIT, timeout=40)
        print(json.dumps({"phase": "START_REQUESTED", "source": SOURCE, "state": str(STATE)}), flush=True)
    except BaseException as error:
        # Never revert a running new worker or its state.
        if command("systemctl", "show", UNIT, "-p", "MainPID", "--value") != "0":
            print(json.dumps({"phase": "NEEDS_INSPECTION", "error_type": type(error).__name__}), flush=True)
            raise
        if applied:
            db = connect(MySqlSettings.from_env())
            lock = TrendWriterLock(db)
            try:
                lock.acquire()
                execute(MySqlTrialStore(db), lock.assert_owned, STATE / "authority/old-approval.json",
                        STATE / "authority/approval.json", rollback=True)
            finally:
                lock.close()
        if DROPIN.exists():
            DROPIN.rename(STATE / "failed-dropin.conf")
        command("systemctl", "daemon-reload")
        command("systemctl", "start", UNIT, timeout=40)
        print(json.dumps({"phase": "RESTORED_OLD", "error_type": type(error).__name__}), flush=True)
        raise


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("phase", choices=("prepare", "cutover"))
parser.add_argument("--archive", type=Path)
args = parser.parse_args()
if args.phase == "prepare":
    if not args.archive:
        parser.error("archive required")
    prepare(args.archive)
else:
    cutover()
