"""Local-only, lossless capture tiering with stable legacy hash paths.

No payload deletion, TTL, recompression, COS state or trading state mutation.
Cold gzip members remain online. A relative alias atomically replaces a hot
name only after the identical cold inode is durable on the same filesystem.
"""
from __future__ import annotations

import fcntl
import gzip
import io
from hashlib import sha256
import json
import os
import re
import stat
import threading
import time
import uuid

DIRECTORY = ".capture-cold"
SCHEMA = "htx-capture-local-alias-v1"
NAME = re.compile(r"[0-9a-f]{64}\.json\.gz")
MAX_MEMBER_BYTES = 8 * 1024**2


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class LocalCaptureArchive:
    """One recorder-owned background mover; new-mode writers are exclusive."""

    def __init__(self, recorder, *, min_free_bytes, trigger=0.8, target=0.6):
        if min_free_bytes is None or min_free_bytes < 0:
            raise ValueError("local capture archive requires an explicit disk reserve")
        if not 0 < target < trigger < 1:
            raise ValueError("local capture archive requires 0 < target < trigger < 1")
        self.recorder = recorder
        self.root = recorder.root.absolute()
        self.cold = self.root / DIRECTORY
        if self.root.resolve() != self.root or self.cold.is_symlink():
            raise ValueError("local capture archive requires a non-symlink root")
        if (self.root / ".sealing").exists():
            raise ValueError("local capture archive and segmented/COS layout are exclusive")
        self.cold.mkdir(exist_ok=True)
        if self.cold.stat().st_dev != self.root.stat().st_dev:
            raise ValueError("local cold directory must share the source filesystem")
        sync_directory(self.root)
        self.min_free_bytes = min_free_bytes
        self.trigger = int(recorder.max_bytes * trigger)
        self.target = int(recorder.max_bytes * target)
        lock_path = self.cold / "writer.lock"
        if lock_path.is_symlink() or (lock_path.exists() and not lock_path.is_file()):
            raise ValueError("symlink capture archive writer lock")
        self.writer_lock = lock_path.open("a")
        try:
            fcntl.flock(self.writer_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            marker = self.cold / "config.json"
            if marker.is_symlink() or (marker.exists() and not marker.is_file()):
                raise ValueError("symlink capture archive config")
            if marker.exists():
                if json.loads(marker.read_text()) != {"schema": SCHEMA}:
                    raise ValueError("unsupported local capture archive layout")
            else:
                if any(p.name != "writer.lock" for p in self.cold.iterdir()):
                    raise ValueError("unmarked local capture archive requires recovery")
                with marker.open("x") as f:
                    json.dump({"schema": SCHEMA}, f)
                    f.flush()
                    os.fsync(f.fileno())
                sync_directory(self.cold)
            self.cold_bytes = 0
            self.retained_bytes = 0
            seen_inodes = set()
            hot_bytes = 0
            for p in self.root.iterdir():
                if p.is_symlink():
                    self.validate_alias(p)
                    hot_bytes += p.lstat().st_size
                    self.retained_bytes += p.lstat().st_size
                elif p.is_file():
                    stat = p.stat()
                    hot_bytes += stat.st_size
                    self.retained_bytes += stat.st_size
                    seen_inodes.add((stat.st_dev, stat.st_ino))
            for p in self.cold.iterdir():
                if not NAME.fullmatch(p.name):
                    # Configuration, lock and crash-left alias metadata are hot.
                    if p.is_file() or p.is_symlink():
                        hot_bytes += p.lstat().st_size
                        self.retained_bytes += p.lstat().st_size
                    continue
                if p.is_symlink() or not p.is_file():
                    raise ValueError("invalid local cold member")
                stat = p.stat()
                self.cold_bytes += stat.st_size
                if (stat.st_dev, stat.st_ino) not in seen_inodes:
                    self.retained_bytes += stat.st_size
                    seen_inodes.add((stat.st_dev, stat.st_ino))
                if not (self.root / p.name).exists():
                    raise ValueError("cold capture member has no stable source path")
            recorder.used = hot_bytes
        except BaseException:
            self.writer_lock.close()
            raise
        self.cursor = 0
        self.moved = 0
        self.max_member_lock_ms = 0.0
        self.wake = threading.Event()
        self.stopping = threading.Event()
        self.thread = threading.Thread(
            target=self._run, name="capture-local-archive", daemon=True
        )

    def start(self):
        self.thread.start()
        self.wake.set()

    def validate_alias(self, path):
        if self.cold.is_symlink():
            raise ValueError("invalid local capture alias target directory")
        if not NAME.fullmatch(path.name):
            raise ValueError("unexpected symlink in capture root")
        expected = f"{DIRECTORY}/{path.name}"
        target = self.cold / path.name
        if os.readlink(path) != expected or target.is_symlink() or not target.is_file():
            raise ValueError("invalid or missing local capture alias target")
        return target

    def ensure_reserve(self, required_bytes=0):
        disk = os.statvfs(self.root)
        if disk.f_bavail * disk.f_frsize - required_bytes < self.min_free_bytes:
            raise OSError("local capture disk reserve exhausted; no automatic deletion")

    def note_write(self, count):
        self.retained_bytes += count
        self.wake.set()

    def _promote(self, record):
        """Caller holds recorder.lock. Crash points preserve original hash paths."""
        name = record["hash"] + ".json.gz"
        if not NAME.fullmatch(name):
            raise ValueError("invalid capture member identity")
        source = self.root / name
        if source.is_symlink():
            self.validate_alias(source)
            return
        if self.cold.is_symlink() or self.cold.stat().st_dev != self.root.stat().st_dev:
            raise ValueError("local cold directory must remain on the source filesystem")
        if (self.root / ".sealing").exists():
            raise ValueError("local archive cannot run alongside segmented/COS control")
        before = source.stat()
        if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_MEMBER_BYTES:
            raise ValueError("local capture archival requires a bounded regular member")
        packed = source.read_bytes()
        with gzip.GzipFile(fileobj=io.BytesIO(packed)) as f:
            raw = f.read(MAX_MEMBER_BYTES + 1)
        if len(raw) > MAX_MEMBER_BYTES:
            raise ValueError("capture member exceeds bounded local archive decode")
        if len(packed) != record["bytes"] or sha256(raw).hexdigest() != record["hash"]:
            raise ValueError("capture member integrity failure before local archival")
        self.ensure_reserve(4096)
        target = self.cold / name
        new_target = not target.exists()
        if target.is_symlink() or (target.exists() and not target.is_file()):
            raise ValueError("symlink cold target")
        if new_target:
            os.link(source, target, follow_symlinks=False)
        elif target.stat().st_size != len(packed) or target.read_bytes() != packed:
            raise ValueError("conflicting cold capture member")
        cold_stat = target.stat()
        if new_target and (before.st_dev, before.st_ino) != (cold_stat.st_dev, cold_stat.st_ino):
            raise ValueError("capture source changed during archival")
        with target.open("rb") as f:
            os.fsync(f.fileno())
        sync_directory(self.cold)
        current = source.stat()
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
            current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns
        ):
            raise ValueError("capture source changed before alias publication")
        temporary = self.cold / ("alias-" + uuid.uuid4().hex)
        os.symlink(f"{DIRECTORY}/{name}", temporary)
        os.replace(temporary, source)
        sync_directory(self.root)
        sync_directory(self.cold)
        alias_size = source.lstat().st_size
        self.recorder.used += alias_size - before.st_size
        if new_target:
            self.cold_bytes += before.st_size
        self.retained_bytes += alias_size
        if not new_target and (before.st_dev, before.st_ino) != (cold_stat.st_dev, cold_stat.st_ino):
            self.retained_bytes -= before.st_size
        self.moved += 1

    def _run(self):
        try:
            while not self.stopping.is_set():
                self.wake.wait(1)
                self.wake.clear()
                with self.recorder.lock:
                    if self.recorder.failure:
                        return
                    if self.recorder.used < self.trigger:
                        continue
                while not self.stopping.is_set():
                    with self.recorder.lock:
                        if self.recorder.failure or self.recorder.used <= self.target:
                            break
                        if self.cursor >= len(self.recorder.records):
                            break
                        record = self.recorder.records[self.cursor]
                        if record["hash"] == self.recorder.last_ref:
                            break
                        started = time.perf_counter()
                        self._promote(record)
                        self.max_member_lock_ms = max(
                            self.max_member_lock_ms, (time.perf_counter() - started) * 1000
                        )
                        self.cursor += 1
                    self.stopping.wait(0.001)
        except Exception as error:
            with self.recorder.lock:
                if not self.recorder.failure:
                    self.recorder.failure = "CAPTURE_ARCHIVE_IO:" + type(error).__name__

    def status(self):
        with self.recorder.lock:
            return {
                "schema": SCHEMA,
                "hot_bytes": self.recorder.used,
                "hot_max_bytes": self.recorder.max_bytes,
                "cold_payload_bytes": self.cold_bytes,
                "retained_bytes": self.retained_bytes,
                "min_free_bytes": self.min_free_bytes,
                "moved_this_process": self.moved,
                "max_member_lock_ms": self.max_member_lock_ms,
                "failure": self.recorder.failure,
            }

    def close(self):
        self.stopping.set()
        self.wake.set()
        if self.thread.ident is not None:
            self.thread.join(5)
        if self.thread.is_alive():
            raise RuntimeError("capture local archive has not stopped")
        self.writer_lock.close()
