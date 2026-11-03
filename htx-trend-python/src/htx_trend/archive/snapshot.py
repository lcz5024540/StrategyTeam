"""Freeze explicitly selected replay inventories without changing source data."""
from contextlib import closing
from datetime import datetime, timezone
import gzip
import json
import os
import re
from pathlib import Path, PurePosixPath
import sqlite3

from .backend import ArchiveError, check_hash, digest
from ..realtime.segment_codec import segment_events


def safe_relative(value):
    p = PurePosixPath(value)
    if not value or p.is_absolute() or any(x in ('', '.', '..') for x in value.split('/')) or '\\' in value:
        raise ArchiveError('unsafe relative path')
    return value


def regular(path, root):
    path, root = Path(path), Path(root)
    if not path.is_relative_to(root):
        raise ArchiveError('source outside root')
    for p in (path, *path.parents):
        if p.is_symlink():
            raise ArchiveError('symlink source')
        if p == root:
            break
    if not path.is_file():
        raise ArchiveError('source is not a regular file')
    return path


def stable_read(path, root, max_bytes):
    regular(path, root)
    before = path.stat()
    if before.st_size > max_bytes:
        raise ArchiveError(f'file exceeds member limit: {path.name}')
    data = path.read_bytes()
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
        raise ArchiveError('source changed while reading')
    return data


def sqlite_backup(source, target):
    regular(source, source.parent)
    with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True, timeout=30)) as src:
        with closing(sqlite3.connect(target)) as dst:
            src.backup(dst, pages=256, sleep=0.05)
            if dst.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise ArchiveError('inconsistent SQLite snapshot')


def prepare(job, spec):
    """Job DB is published only after a complete inventory; retry discards temp DB."""
    root = Path(spec['root']).absolute()
    if root.resolve() != root or not root.is_dir():
        raise ArchiveError('source root must be a real directory without symlinks')
    if (root / '.cos-retired.json').exists():
        raise ArchiveError('retired source requires full restoration to a fresh directory')
    job.mkdir(parents=True, exist_ok=True)
    db_path = job / 'snapshot.sqlite3'
    identity = json.dumps(spec, sort_keys=True)
    if db_path.exists():
        db = sqlite3.connect(db_path)
        if db.execute("SELECT value FROM meta WHERE key='spec'").fetchone()[0] != identity:
            db.close()
            raise ArchiveError('job belongs to another source/specification')
        return db
    temp = job / 'inventory.pending.sqlite3'
    temp.unlink(missing_ok=True)
    db = sqlite3.connect(temp)
    db.executescript('''
      CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
      CREATE TABLE files(path TEXT PRIMARY KEY,origin TEXT NOT NULL,semantic TEXT,
        count INTEGER,raw_hash TEXT,raw_size INTEGER,pack TEXT,offset INTEGER,
        length INTEGER,encoding TEXT,stored_hash TEXT,fingerprint TEXT);
      CREATE INDEX semantic_idx ON files(semantic);
      CREATE TABLE refs(parent TEXT NOT NULL,child TEXT NOT NULL,PRIMARY KEY(parent,child));
    ''')
    db.execute('INSERT INTO meta VALUES (?,?)', ('spec', identity))
    db.execute('INSERT INTO meta VALUES (?,?)', ('snapshot_started', datetime.now(timezone.utc).isoformat()))

    def add(relative, source, semantic=None, count=None):
        safe_relative(relative)
        info = regular(source, job if source.is_relative_to(job) else root).stat()
        fingerprint = json.dumps([info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns])
        db.execute('INSERT INTO files(path,origin,semantic,count,fingerprint) VALUES (?,?,?,?,?)',
                   (relative, str(source), semantic, count, fingerprint))

    kind = spec['kind']
    if kind == 'cas':
        backup = job / 'index.sqlite3'
        sqlite_backup(root / 'index.sqlite3', backup)
        with closing(sqlite3.connect(backup)) as index:
            indexed = {check_hash(row[0]) for row in index.execute('SELECT hash FROM evidence')}
        found = set()
        # Include valid unindexed CAS objects too: unknown historical ownership is not garbage.
        for directory in sorted((root / 'objects').iterdir()):
            if directory.is_symlink():
                raise ArchiveError('symlink CAS shard')
            if not directory.is_dir():
                continue
            for p in sorted(directory.iterdir()):
                match = re.fullmatch(r'([0-9a-f]{64})\.json(?:\.gz)?', p.name)
                if not match:
                    continue
                key = match[1]
                if directory.name != key[:2]:
                    raise ArchiveError('CAS object in wrong shard')
                regular(p, root)
                add(p.relative_to(root).as_posix(), p, key)
                found.add(key)
        missing = indexed - found
        if missing:
            raise ArchiveError(f'indexed object absent: {min(missing)}')
        add('index.sqlite3', backup)
    elif kind == 'capture':
        index_path = regular(root / 'segments/segments.jsonl', root)
        # A size-limited read cannot consume appends beyond this snapshot boundary.
        with index_path.open('rb') as src:
            limit = os.fstat(src.fileno()).st_size
            raw = src.read(limit)
        raw = raw[:raw.rfind(b'\n') + 1]
        snapshot = job / 'segments.jsonl'
        snapshot.write_bytes(raw)
        seen = set()
        for line in raw.splitlines():
            row = json.loads(line)
            key = check_hash(row['hash'])
            if key in seen:
                raise ArchiveError('duplicate segment in capture index')
            seen.add(key)
            add(f'segments/{key}.json.gz', root / f'segments/{key}.json.gz', key, row['count'])
        unindexed = []
        for p in sorted((root / 'segments').glob('*.json.gz')):
            key = check_hash(p.name.removesuffix('.json.gz'))
            if key not in seen:
                add(f'segments/{key}.json.gz', p, key)
                unindexed.append(key)
        db.execute('INSERT INTO meta VALUES (?,?)', ('unindexed_capture_segments', json.dumps(unindexed)))
        add('segments/segments.jsonl', snapshot)
        capture = json.loads(stable_read(root / 'capture.json', root, 16 * 1024**2))
        if not isinstance(capture.get('contracts'), list) or not capture['contracts'] or not all(isinstance(x, str) and x for x in capture['contracts']):
            raise ArchiveError('capture.json requires explicit contracts')
        for name in ('capture.json', 'status.json'):
            if (root / name).exists():
                target = job / name
                target.write_bytes(stable_read(root / name, root, 16 * 1024**2))
                add(name, target)
        # Explicit public pool provenance only; no arbitrary sibling files.
        if spec.get('pool'):
            p = Path(spec['pool']).absolute()
            target = job / 'pool-provenance.json'
            target.write_bytes(stable_read(p, p.parent, 16 * 1024**2))
            add('pool-provenance.json', target)
    elif kind == 'tree':
        extensions = set(spec['extensions'])
        if not extensions <= {'.json', '.parquet', '.yaml', '.md', '.log'}:
            raise ArchiveError('unapproved tree extension')
        for current, dirs, names in os.walk(root, followlinks=False):
            dirs.sort()
            for directory in dirs:
                if (Path(current) / directory).is_symlink():
                    raise ArchiveError('symlink directory')
            for name in sorted(names):
                p = Path(current) / name
                if p.suffix not in extensions:
                    continue
                if any(part.startswith('.') for part in p.relative_to(root).parts) or 'env' in name.lower() or 'credential' in name.lower():
                    raise ArchiveError('sensitive/hidden file in selected tree')
                regular(p, root)
                add(p.relative_to(root).as_posix(), p)
    else:
        raise ArchiveError('unknown source kind')
    db.execute('INSERT INTO meta VALUES (?,?)', ('snapshot_finished', datetime.now(timezone.utc).isoformat()))
    db.commit()
    db.close()
    os.replace(temp, db_path)
    return sqlite3.connect(db_path)


def validate(data, row, kind):
    """Validate source semantic hash, schema, count and explicit CAS edges."""
    semantic = row['semantic']
    if not semantic:
        return ()
    if row['path'].endswith('.gz'):
        from io import BytesIO
        with gzip.GzipFile(fileobj=BytesIO(data)) as z:
            raw = z.read(64 * 1024**2 + 1)
        if len(raw) > 64 * 1024**2:
            raise ArchiveError('decoded source exceeds member limit')
    else:
        raw = data
    if digest(raw) != semantic:
        raise ArchiveError(f'source hash mismatch: {semantic}')
    value = json.loads(raw)
    if kind == 'cas':
        if value.get('schema') != 'htx-python-evidence-v1':
            raise ArchiveError('unknown CAS schema')
        return tuple(check_hash(x) for x in value['refs'])
    events = segment_events(value)
    if row['count'] is not None and len(events) != row['count']:
        raise ArchiveError('invalid capture segment')
    return ()
