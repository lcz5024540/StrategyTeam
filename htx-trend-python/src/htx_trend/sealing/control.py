"""Immutable generation membership, durable pins and cross-process local leases."""
from contextlib import contextmanager, closing
from hashlib import sha256
import fcntl
import gzip
import json
import os
from pathlib import Path
import re
import sqlite3
from threading import RLock
import time

SCHEMA = 'htx-local-sealing-v1'
HASH = re.compile(r'[0-9a-f]{64}')


class SealingError(ValueError):
    pass


class NeedsRestore(SealingError):
    pass


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def digest_file(path):
    h = sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''):
            h.update(b)
    return h.hexdigest()


def stamp(path):
    s = path.stat()
    return json.dumps([s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns])


@contextmanager
def lease(root, *, exclusive=False, blocking=True):
    """One FD per scope: nested/parallel shared leases cannot unlock each other."""
    directory = Path(root) / '.sealing'
    if directory.resolve() != directory.absolute():
        raise SealingError('symlink sealing directory')
    lock_path = directory / 'source.lock'
    if lock_path.is_symlink():
        raise SealingError('symlink source lock')
    with lock_path.open('a') as f:
        try:
            fcntl.flock(f, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) |
                        (0 if blocking else fcntl.LOCK_NB))
        except BlockingIOError as e:
            raise SealingError('source is in use; retry local maintenance') from e
        yield f


def safe_file(root, relative):
    p = Path(relative)
    if p.is_absolute() or not relative or any(x in ('', '.', '..') for x in relative.split('/')):
        raise SealingError('unsafe segment path')
    target = Path(root) / p
    for part in (target, *target.parents):
        if part.is_symlink():
            raise SealingError('symlink segment file')
        if part == Path(root):
            break
    if not target.is_file():
        raise NeedsRestore('missing local segment member')
    return target


def initialize(root, *, kind, contract, max_bytes=64*1024**2, max_files=20000,
               delivery_consumer=False, enroll_existing=False, writer_stopped=False):
    """Explicit local layout migration. Existing writers must be stopped by operator."""
    root = Path(root).absolute()
    if kind not in {'cas', 'capture'} or contract not in {'paper', 'collector', 'capture-public', 'capture-runtime'}:
        raise SealingError('unsupported sealing contract')
    if (kind == 'cas') != (contract in {'paper', 'collector'}):
        raise SealingError('source kind and consumer contract mismatch')
    if max_bytes < 1 or max_files < 1:
        raise SealingError('positive generation limits required')
    root.mkdir(parents=True, exist_ok=True)
    if root.resolve() != root:
        raise SealingError('source root must be real')
    control = root / '.sealing'
    control.mkdir(exist_ok=True)
    if control.resolve() != control:
        raise SealingError('unsafe sealing directory')
    for name in ('config.json','control.sqlite3','control.sqlite3-wal','control.sqlite3-shm'):
        if (control / name).is_symlink():
            raise SealingError('unsafe sealing metadata')
    config_path = control / 'config.json'
    expected = {'schema': SCHEMA, 'kind': kind, 'contract': contract, 'max_bytes': max_bytes,
                'max_files': max_files, 'delivery_consumer': bool(delivery_consumer)}
    if config_path.exists() and json.loads(config_path.read_bytes()) != expected:
        raise SealingError('sealing configuration differs; no implicit reinitialization')
    def candidates():
        return (root / 'objects').glob('*/*.json*') if kind == 'cas' else root.glob('*.json.gz')
    has_candidates = next(candidates(), None) is not None
    if has_candidates and not (enroll_existing and writer_stopped) and not config_path.exists():
        raise SealingError('existing source requires explicit stopped-writer enrollment')
    with lease(root, exclusive=True, blocking=False):
        db = sqlite3.connect(control / 'control.sqlite3')
        try:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('PRAGMA synchronous=FULL')
            db.executescript('''
              CREATE TABLE IF NOT EXISTS state(name TEXT PRIMARY KEY,value INTEGER);
              INSERT OR IGNORE INTO state VALUES('revision',0),('current',1),('initialized',0),('layout_revision',0);
              CREATE TABLE IF NOT EXISTS generations(id INTEGER PRIMARY KEY,state TEXT,created REAL,manifest TEXT);
              INSERT OR IGNORE INTO generations VALUES(1,'OPEN',0,NULL);
              CREATE TABLE IF NOT EXISTS objects(path TEXT PRIMARY KEY,key TEXT,raw_hash TEXT,size INTEGER,
                  kind TEXT,metadata TEXT,local INTEGER NOT NULL,owner INTEGER);
              CREATE INDEX IF NOT EXISTS objects_key ON objects(key);
              CREATE TABLE IF NOT EXISTS members(generation INTEGER,path TEXT,PRIMARY KEY(generation,path));
              CREATE INDEX IF NOT EXISTS members_path ON members(path,generation);
              CREATE TABLE IF NOT EXISTS edges(parent TEXT,child TEXT,PRIMARY KEY(parent,child));
              CREATE TABLE IF NOT EXISTS consumers(owner TEXT PRIMARY KEY,ready INTEGER,error TEXT);
              CREATE TABLE IF NOT EXISTS pins(owner TEXT,key TEXT,PRIMARY KEY(owner,key));
              CREATE TABLE IF NOT EXISTS pin_changes(owner TEXT,key TEXT,action TEXT,reason TEXT,at REAL);
              CREATE TABLE IF NOT EXISTS mutations(id TEXT PRIMARY KEY);
              CREATE TABLE IF NOT EXISTS deletes(plan TEXT,path TEXT,hash TEXT,stamp TEXT,state TEXT,
                  PRIMARY KEY(plan,path));
            ''')
            complete = db.execute("SELECT value FROM state WHERE name='initialized'").fetchone()[0]
            if not config_path.exists():
                from ..archive.transfer import atomic
                atomic(config_path, encoded(expected))
            if not complete or (enroll_existing and writer_stopped):
                if has_candidates and not (enroll_existing and writer_stopped):
                    raise SealingError('incomplete enrollment; stopped-writer resume required')
                db.execute("UPDATE state SET value=0 WHERE name='initialized'")
                db.commit()
                current = db.execute("SELECT value FROM state WHERE name='current'").fetchone()[0]
                count,size = db.execute('SELECT count(*),coalesce(sum(o.size),0) FROM members m JOIN objects o ON o.path=m.path WHERE generation=?',(current,)).fetchone()
                for n, p in enumerate(candidates(), 1):
                    relative = p.relative_to(root).as_posix()
                    safe_file(root, relative)
                    if kind == 'cas':
                        key = p.name.removesuffix('.gz').removesuffix('.json')
                        if not HASH.fullmatch(key) or p.parent.name != key[:2]:
                            raise SealingError('unknown CAS member; preserve and investigate')
                        raw = gzip.decompress(p.read_bytes()) if p.suffix == '.gz' else p.read_bytes()
                        value = json.loads(raw)
                        if sha256(raw).hexdigest() != key or value.get('schema') != 'htx-python-evidence-v1':
                            raise SealingError('invalid enrolled CAS member')
                        refs, obj_kind, meta = value['refs'], value['kind'], {}
                    else:
                        from ..realtime.segment_codec import segment_events
                        key = p.name.removesuffix('.json.gz')
                        raw = gzip.decompress(p.read_bytes())
                        value = json.loads(raw)
                        events = segment_events(value)
                        if not HASH.fullmatch(key) or sha256(raw).hexdigest() != key:
                            raise SealingError('invalid enrolled capture segment')
                        refs, obj_kind = (), 'capture_segment'
                        meta = {'hash': key, 'bytes': p.stat().st_size, 'count': len(events),
                                'first_received_ms': events[0].get('received_ms') if events else None,
                                'last_received_ms': events[-1].get('received_ms') if events else None}
                    if _track(db, root, key, relative, refs, obj_kind, meta):
                        count += 1
                        size += p.stat().st_size
                    if count >= max_files or size >= max_bytes:
                        # Offline enrollment has no active consumer. Bound historical generations;
                        # missing handoffs still prohibit any release until the writer restarts.
                        db.execute("UPDATE generations SET state='SEALED',created=? WHERE id=?",(time.time(),current))
                        current += 1
                        db.execute("INSERT INTO generations VALUES(?,'OPEN',?,NULL)",(current,time.time()))
                        db.execute("UPDATE state SET value=? WHERE name='current'",(current,))
                        count,size = 0,0
                    if n % 1000 == 0:
                        db.commit()  # Bounded, resumable enrollment.
                if kind == 'cas' and (root / 'index.sqlite3').exists():
                    db.execute('ATTACH DATABASE ? AS source_index', (str(root / 'index.sqlite3'),))
                    missing = db.execute('SELECT 1 FROM source_index.evidence e WHERE NOT EXISTS (SELECT 1 FROM objects o WHERE o.key=e.hash) LIMIT 1').fetchone()
                    if missing:
                        raise SealingError('index contains unknown missing objects; recovery required')
                if db.execute('SELECT 1 FROM edges e WHERE NOT EXISTS (SELECT 1 FROM objects o WHERE o.key=e.child) LIMIT 1').fetchone():
                    raise SealingError('unresolved local reference; recovery required')
                if has_candidates:
                    # Legacy data may belong to frozen research manifests outside this root.
                    # Never infer that absent runtime refs make historical ownership disposable.
                    db.execute("INSERT OR REPLACE INTO consumers VALUES('external:legacy-enrollment',1,NULL)")
                    db.execute("INSERT OR IGNORE INTO pins SELECT 'external:legacy-enrollment',key FROM objects WHERE local=1")
                db.execute('DELETE FROM mutations')
                db.execute("UPDATE consumers SET ready=0 WHERE owner NOT LIKE 'external:%'")
                db.execute("UPDATE state SET value=value+1 WHERE name='revision'")
                db.execute("UPDATE state SET value=1 WHERE name='initialized'")
                db.commit()
                fence = control / 'BLOCK_RELEASE'
                if fence.exists():
                    fence.unlink()
                    from ..archive.transfer import sync_directory
                    sync_directory(control)
        finally:
            db.close()
    return expected


def _track(db, root, key, relative, refs, kind, metadata):
    if not HASH.fullmatch(key) or any(not HASH.fullmatch(r) for r in refs):
        raise SealingError('invalid member/reference digest')
    p = safe_file(root, relative)
    generation = db.execute("SELECT value FROM state WHERE name='current'").fetchone()[0]
    old = db.execute('SELECT local,owner FROM objects WHERE path=?', (relative,)).fetchone()
    if old == (1, generation):
        return False
    db.execute('INSERT OR REPLACE INTO objects VALUES(?,?,?,?,?,?,1,?)',
               (relative, key, digest_file(p), p.stat().st_size, kind, encoded(metadata).decode(), generation))
    db.execute('INSERT OR IGNORE INTO members VALUES(?,?)', (generation, relative))
    db.executemany('INSERT OR IGNORE INTO edges VALUES(?,?)', ((key,r) for r in refs))
    db.execute("UPDATE state SET value=value+1 WHERE name IN ('revision','layout_revision')")
    return True


class Control:
    def __init__(self, root, *, contract=None):
        self.root = Path(root).absolute()
        self.directory = self.root / '.sealing'
        if self.directory.resolve() != self.directory:
            raise SealingError('unsafe sealing control path')
        for name in ('config.json','control.sqlite3','control.sqlite3-wal','control.sqlite3-shm'):
            if (self.directory / name).is_symlink():
                raise SealingError('unsafe sealing metadata')
        self.config = json.loads((self.directory / 'config.json').read_bytes())
        if self.config.get('schema') != SCHEMA or (contract is not None and self.config['contract'] != contract):
            raise SealingError('sealing contract mismatch')
        self.db = sqlite3.connect(self.directory / 'control.sqlite3', check_same_thread=False)
        self.db.execute('PRAGMA synchronous=FULL')
        self.guard = RLock()
        if self.db.execute("SELECT value FROM state WHERE name='initialized'").fetchone()[0] != 1:
            self.db.close()
            raise SealingError('sealing enrollment incomplete')

    def block_release(self):
        """Independent durable fail-closed fence when the SQLite sidecar fails."""
        from ..archive.transfer import sync_directory
        fd = os.open(self.directory / 'BLOCK_RELEASE', os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        sync_directory(self.directory)

    @contextmanager
    def mutation(self):
        import uuid
        ident = uuid.uuid4().hex
        with self.guard, self.db:
            self.db.execute('INSERT INTO mutations VALUES(?)', (ident,))
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")
        # Failure/crash intentionally leaves this intent; only offline reconciliation clears it.
        yield
        with self.guard, self.db:
            self.db.execute('DELETE FROM mutations WHERE id=?', (ident,))
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")

    def _require_handoff(self):
        required = {'paper': 'paper', 'collector': 'continuous-collector',
                    'capture-public': 'recorder', 'capture-runtime': 'recorder'}[self.config['contract']]
        owners = dict(self.db.execute('SELECT owner,ready FROM consumers'))
        # paper:<instance> is accepted for explicit tools/tests; a backfill alone never qualifies.
        present = any(k == required or (required == 'paper' and k.startswith('paper:')) for k in owners)
        if (not present or not all(owners.values()) or (self.directory / 'BLOCK_RELEASE').exists()
                or self.db.execute('SELECT 1 FROM mutations').fetchone()):
            raise SealingError('consumer handoff incomplete or interrupted mutation; local maintenance blocked')

    def close(self):
        self.db.close()

    def lease(self):
        return lease(self.root)

    def track(self, key, path, refs=(), kind='capture_segment', metadata=None):
        with self.lease(), self.guard, self.db:
            _track(self.db, self.root, key, path, refs, kind, metadata or {})

    def begin(self, owner):
        with self.lease(), self.guard, self.db:
            self.db.execute('INSERT INTO consumers VALUES(?,0,NULL) ON CONFLICT(owner) DO UPDATE SET ready=0', (owner,))
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")

    def ready(self, owner, roots, *, error=None):
        with self.lease(), self.guard, self.db:
            roots = set(roots)
            if error is None:
                for key in roots:
                    self._require_local_key(key)
            self.db.execute('INSERT OR REPLACE INTO consumers VALUES(?,?,?)', (owner, int(error is None), error))
            if error is None:
                self.db.execute('DELETE FROM pins WHERE owner=?', (owner,))
                self.db.executemany('INSERT INTO pins VALUES(?,?)', ((owner,k) for k in roots))
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")

    def external_pin(self, owner, keys, *, remove=False, reason):
        if not owner.startswith('external:') or not reason.strip():
            raise SealingError('external owner and an audit reason required')
        keys = set(keys)
        if not keys or any(not HASH.fullmatch(k) for k in keys):
            raise SealingError('explicit member digests required')
        with self.lease(), self.guard, self.db:
            if not remove:
                for key in self.closure(keys):
                    self._require_local_key(key)
            self.db.execute('INSERT OR REPLACE INTO consumers VALUES(?,1,NULL)',(owner,))
            for key in keys:
                if remove:
                    self.db.execute('DELETE FROM pins WHERE owner=? AND key=?',(owner,key))
                else:
                    self.db.execute('INSERT OR IGNORE INTO pins VALUES(?,?)',(owner,key))
                self.db.execute('INSERT INTO pin_changes VALUES(?,?,?,?,?)',(owner,key,'REMOVE' if remove else 'ADD',reason,time.time()))
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")

    def checkpoint_changed(self):
        with self.lease(), self.guard, self.db:
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")

    def seal(self, *, force=False):
        """Short local transaction only; materializing/archiving is done elsewhere."""
        with lease(self.root, exclusive=True, blocking=False), self.guard, self.db:
            self._require_handoff()
            current = self.db.execute("SELECT value FROM state WHERE name='current'").fetchone()[0]
            count, size = self.db.execute('SELECT count(*),coalesce(sum(o.size),0) FROM members m JOIN objects o ON m.path=o.path WHERE generation=?', (current,)).fetchone()
            if not count or (not force and count < self.config['max_files'] and size < self.config['max_bytes']):
                return None
            self.db.execute("UPDATE generations SET state='SEALED',created=? WHERE id=?", (time.time(), current))
            self.db.execute("INSERT INTO generations VALUES(?,'OPEN',?,NULL)", (current+1,time.time()))
            self.db.execute("UPDATE state SET value=? WHERE name='current'", (current+1,))
            self.db.execute("UPDATE state SET value=value+1 WHERE name='revision'")
            return current

    def layout_revision(self):
        with self.guard:
            return self.db.execute("SELECT value FROM state WHERE name='layout_revision'").fetchone()[0]

    def revision(self):
        with self.guard:
            return self.db.execute("SELECT value FROM state WHERE name='revision'").fetchone()[0]

    def status(self):
        with self.lease(), self.guard:
            return {'config': self.config, 'revision': self.db.execute("SELECT value FROM state WHERE name='revision'").fetchone()[0],
                    'generations': [dict(zip(('id','state','created','manifest'),r)) for r in self.db.execute('SELECT * FROM generations ORDER BY id')],
                    'blocked_release': (self.directory/'BLOCK_RELEASE').exists(),
                    'pending_mutations': self.db.execute('SELECT count(*) FROM mutations').fetchone()[0],
                    'cold_files': self.db.execute('SELECT count(*) FROM objects WHERE local=0').fetchone()[0],
                    'consumers': [dict(zip(('owner','ready','error'),r)) for r in self.db.execute('SELECT * FROM consumers')]}

    def closure(self, roots):
        seen, todo = set(), list(roots)
        while todo:
            key = todo.pop()
            if key in seen:
                continue
            seen.add(key)
            todo.extend(r[0] for r in self.db.execute('SELECT child FROM edges WHERE parent=?', (key,)))
        return seen

    def known_hashes(self, value):
        result = set()
        def visit(v):
            if isinstance(v, dict):
                for child in v.values(): visit(child)
            elif isinstance(v, (list,tuple)):
                for child in v: visit(child)
            elif isinstance(v, str) and HASH.fullmatch(v) and self.db.execute('SELECT 1 FROM objects WHERE key=?', (v,)).fetchone():
                result.add(v)
        with self.guard:
            visit(value)
        return result

    def local_records(self):
        with self.lease(), self.guard:
            return [json.loads(r[0]) for r in self.db.execute('SELECT metadata FROM objects WHERE local=1 ORDER BY rowid')]

    def _require_local_key(self, key):
        paths = [r[0] for r in self.db.execute('SELECT path FROM objects WHERE key=? AND local=1', (key,))]
        if not paths:
            raise NeedsRestore('local pin closure requires recovery before maintenance')
        for path in paths:
            safe_file(self.root,path)

    def pin_snapshot(self):
        """Compute local reachability under SH lease, outside the short delete lock."""
        with self.lease(), self.guard:
            self._require_handoff()
            revision = self.db.execute("SELECT value FROM state WHERE name='revision'").fetchone()[0]
            roots = {r[0] for r in self.db.execute('SELECT key FROM pins')}
            roots.update(r[0] for r in self.db.execute("SELECT DISTINCT o.key FROM members m JOIN objects o ON o.path=m.path JOIN generations g ON g.id=m.generation WHERE g.state IN ('OPEN','SEALED')"))
            if self.config['kind'] == 'cas':
                with closing(sqlite3.connect((self.root / 'index.sqlite3').as_uri() + '?mode=ro', uri=True)) as index:
                    for name, raw in index.execute('SELECT name,value FROM checkpoints'):
                        # Historical coverage is a research lookup, not the continuous cursor.
                        if self.config['contract'] == 'collector' and name.startswith('coverage:'):
                            continue
                        roots.update(self.known_hashes(json.loads(raw)))
                    tables = {r[0] for r in index.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                    if 'compact_latest' in tables:
                        roots.update(r[0] for r in index.execute('SELECT hash FROM compact_latest'))
                    if self.config['delivery_consumer']:
                        roots.update(r[0] for r in index.execute('SELECT hash FROM outbox WHERE delivered=0'))
            keys = self.closure(roots)
            # A pin must never turn an already cold dependency into an apparently safe state.
            for key in keys:
                self._require_local_key(key)
            return revision, keys

    def generation_rows(self, epoch):
        with self.guard:
            state = self.db.execute('SELECT state FROM generations WHERE id=?', (epoch,)).fetchone()
            if not state or state[0] not in {'SEALED', 'COS_VERIFIED'}:
                raise SealingError('generation is not sealed')
            roots = {r[0] for r in self.db.execute('SELECT o.key FROM members m JOIN objects o ON o.path=m.path WHERE generation=?', (epoch,))}
            keys = self.closure(roots)
            rows = []
            for key in sorted(keys):
                variants = list(self.db.execute('SELECT path,key,raw_hash,size,kind,metadata,local,owner FROM objects WHERE key=?', (key,)))
                if not variants:
                    raise SealingError('sealed dependency was never enrolled')
                rows.extend(dict(zip(('path','key','raw_hash','size','kind','metadata','local','owner'), row)) for row in variants)
            return rows
