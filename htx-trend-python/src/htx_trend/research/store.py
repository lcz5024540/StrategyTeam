"""Durable content-addressed evidence with a transactional delivery outbox.

Files are published without replacement, fsynced before their SQLite index is
committed. Remote delivery is retryable and never the durability boundary.
"""
from __future__ import annotations

from contextlib import nullcontext
from functools import wraps
from dataclasses import asdict, is_dataclass
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
from hashlib import sha256
import json
import gzip
import os
from pathlib import Path
import re
import sqlite3
import tempfile
from threading import RLock
from typing import Any, Protocol


class IntegrityError(ValueError):
    pass


def primitive(value: Any) -> Any:
    if is_dataclass(value):
        return primitive(asdict(value))
    if isinstance(value, Enum):
        return primitive(value.value)
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError('non-finite evidence decimal')
        return format(value.normalize(), 'f')
    if isinstance(value, datetime):
        if value.tzinfo is None:
            raise ValueError('evidence datetime requires timezone')
        return value.astimezone(timezone.utc).isoformat()
    if isinstance(value, dict):
        if not all(isinstance(k, str) for k in value):
            raise ValueError('evidence keys must be strings')
        return {k: primitive(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [primitive(v) for v in value]
    return value


def canonical(value: Any) -> bytes:
    return json.dumps(primitive(value), sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False, allow_nan=False).encode()


def digest(value: Any) -> str:
    return sha256(canonical(value)).hexdigest()


class EvidenceStore(Protocol):
    def put(self, kind: str, payload: Any, *, refs: tuple[str, ...] = ()) -> str: ...
    def get(self, key: str) -> dict: ...



def segment_lease(method):
    @wraps(method)
    def guarded(self, *args, **kwargs):
        control = getattr(self, 'segments', None)
        with control.lease() if control else nullcontext():
            writing = method.__name__ == 'put' or (method.__name__ == 'checkpoint' and (len(args) > 1 and args[1] is not None or kwargs.get('value') is not None))
            with control.mutation() if control and writing else nullcontext():
                return method(self, *args, **kwargs)
    return guarded


class FileEvidenceStore:
    def __init__(self, root: Path, *, compress: bool = False, segmented: bool = False, segment_contract=None):
        self.root = Path(root)
        self.segments = None
        marker = self.root / '.sealing/config.json'
        if marker.exists() and not segmented:
            raise IntegrityError('segmented source requires segment-aware configuration; restore old layout before rollback')
        if segmented:
            from ..sealing.control import Control, initialize
            if segment_contract not in {'paper', 'collector'}:
                raise IntegrityError('explicit paper/collector segment contract required')
            if not marker.exists():
                initialize(self.root, kind='cas', contract=segment_contract)
            self.segments = Control(self.root, contract=segment_contract)
            self.segments.begin('paper' if segment_contract == 'paper' else 'continuous-collector')
        self.compress = compress
        self.root.mkdir(parents=True, exist_ok=True)
        self._lock = RLock()
        self.db = sqlite3.connect(self.root / 'index.sqlite3', check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS evidence (
          hash TEXT PRIMARY KEY, kind TEXT NOT NULL, recorded_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS outbox (
          hash TEXT PRIMARY KEY REFERENCES evidence(hash), attempts INTEGER NOT NULL DEFAULT 0,
          delivered INTEGER NOT NULL DEFAULT 0, last_error TEXT);
        CREATE TABLE IF NOT EXISTS checkpoints (name TEXT PRIMARY KEY, value TEXT NOT NULL);
        ''')

    def close(self):
        with self._lock:
            self.db.close()
            if self.segments:
                self.segments.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _path(self, key: str) -> Path:
        if not re.fullmatch('[0-9a-f]{64}', key):
            raise IntegrityError('invalid evidence digest')
        return self.root / 'objects' / key[:2] / (key + '.json')

    @segment_lease
    def put(self, kind: str, payload: Any, *, refs: tuple[str, ...] = ()) -> str:
        if not kind:
            raise ValueError('evidence kind required')
        with self._lock:
            for ref in refs:
                self.verify(ref)
            value = {'schema': 'htx-python-evidence-v1', 'kind': kind,
                     'refs': sorted(set(refs)), 'payload': primitive(payload)}
            data = canonical(value)
            key = sha256(data).hexdigest()
            plain = self._path(key)
            packed = plain.with_suffix('.json.gz')
            path = packed if self.compress else plain
            path.parent.mkdir(parents=True, exist_ok=True)
            if plain.exists() or packed.exists():
                self.get(key)
            else:
                fd, temp = tempfile.mkstemp(dir=path.parent, prefix='.pending-')
                try:
                    with os.fdopen(fd, 'wb') as f:
                        f.write(gzip.compress(data, mtime=0) if self.compress else data)
                        f.flush()
                        os.fsync(f.fileno())
                    try:
                        os.link(temp, path)
                    except FileExistsError:
                        self.get(key)
                    directory = os.open(path.parent, os.O_RDONLY)
                    try:
                        os.fsync(directory)
                    finally:
                        os.close(directory)
                finally:
                    os.unlink(temp)
            with self.db:
                self.db.execute('INSERT OR IGNORE INTO evidence VALUES (?,?,?)',
                                (key, kind, datetime.now(timezone.utc).isoformat()))
                self.db.execute('INSERT OR IGNORE INTO outbox(hash) VALUES (?)', (key,))
            if self.segments:
                for member in (plain, packed):
                    if member.exists():
                        self.segments.track(key, member.relative_to(self.root).as_posix(), value['refs'], kind)
            return key

    @segment_lease
    def get(self, key: str) -> dict:
        return read_object(self.root, key)

    @segment_lease
    def verify(self, key: str) -> dict:
        seen = set()
        def visit(current):
            if current in seen:
                return
            seen.add(current)
            value = self.get(current)
            for ref in value['refs']:
                visit(ref)
        visit(key)
        return {'root': key, 'objects': len(seen), 'integrity': 'VERIFIED'}

    @segment_lease
    def keys(self, kind: str) -> tuple[str, ...]:
        with self._lock:
            return tuple(row[0] for row in self.db.execute(
                'SELECT hash FROM evidence WHERE kind=? ORDER BY recorded_at, hash', (kind,)))

    @segment_lease
    def checkpoint(self, name: str, value: Any = None) -> Any:
        with self._lock:
            if value is not None:
                with self.db:
                    self.db.execute('INSERT INTO checkpoints VALUES (?,?) ON CONFLICT(name) '
                                    'DO UPDATE SET value=excluded.value', (name, canonical(value).decode()))
                if self.segments:
                    self.segments.checkpoint_changed()
            row = self.db.execute('SELECT value FROM checkpoints WHERE name=?', (name,)).fetchone()
            return json.loads(row[0]) if row else None

    @segment_lease
    def deliver(self, sink, *, limit: int = 100) -> dict:
        """Sink must be idempotent by digest; acknowledgement follows remote commit."""
        with self._lock:
            rows = list(self.db.execute('SELECT hash FROM outbox WHERE delivered=0 ORDER BY rowid LIMIT ?', (limit,)))
            sent = 0
            for (key,) in rows:
                error = None
                try:
                    self.verify(key)
                    sink(key, self.get(key))
                    sent += 1
                except Exception as exc:
                    error = type(exc).__name__
                with self.db:
                    self.db.execute('UPDATE outbox SET attempts=attempts+1,delivered=?,last_error=? WHERE hash=?',
                                    (int(error is None), error, key))
            return {'attempted': len(rows), 'delivered': sent}


def read_object(root: Path, key: str) -> dict:
    from ..sealing.control import lease
    with lease(root) if (Path(root) / '.sealing/config.json').exists() else nullcontext():
        return _read_object(root, key)


def _read_object(root: Path, key: str) -> dict:
    """Read without opening SQLite or creating directories (also used by audit)."""
    if not re.fullmatch('[0-9a-f]{64}', key):
        raise IntegrityError('invalid evidence digest')
    plain = Path(root) / 'objects' / key[:2] / (key + '.json')
    found = None
    for path in (plain, plain.with_suffix('.json.gz')):
        if not path.exists():
            continue
        if path.is_symlink():
            raise IntegrityError('symlink evidence is not allowed')
        try:
            data = path.read_bytes()
            if path.name.endswith('.gz'):
                data = gzip.decompress(data)
            if sha256(data).hexdigest() != key:
                raise IntegrityError(f'evidence hash mismatch {key}')
            value = json.loads(data)
            if value.get('schema') != 'htx-python-evidence-v1':
                raise IntegrityError('unknown evidence schema')
            found = value
        except IntegrityError:
            raise
        except (OSError, EOFError, ValueError) as error:
            raise IntegrityError(f'invalid evidence {key}: {type(error).__name__}') from error
    if found is None:
        if (Path(root) / '.sealing/config.json').exists():
            from ..sealing.control import NeedsRestore
            raise NeedsRestore(f'evidence {key} is not local; use the sealed generation COS manifest to restore')
        raise IntegrityError(f'missing evidence {key}')
    return found
