"""Restartable archive packs and explicit, verified replay restoration."""
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import gzip
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile

from .backend import ArchiveError, check_hash, digest, file_hash
from .snapshot import prepare, safe_relative, stable_read, validate


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic(path, data):
    path = Path(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.archive-', delete=False) as f:
        temp = Path(f.name)
        try:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
            os.replace(temp, path)
            sync_directory(path.parent)
        finally:
            temp.unlink(missing_ok=True)


@contextmanager
def exclusive(work):
    work.mkdir(parents=True, exist_ok=True)
    with (work / 'archive.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise ArchiveError('another archive writer holds this workspace') from exc
        yield


def free_guard(path, minimum, needed=0):
    if shutil.disk_usage(path).free < minimum + needed:
        raise ArchiveError('archive workspace free-space guard')


def archive(backend, work, name, spec, *, pack_bytes=32 * 1024**2,
            max_member=512 * 1024**2, min_free=5 * 1024**3, progress=None):
    safe_relative(name)
    if '/' in name or pack_bytes < 1 or max_member < 1:
        raise ArchiveError('invalid archive job or size')
    work = Path(work).absolute()
    source_root = Path(spec['root']).absolute()
    if work.is_relative_to(source_root) or source_root.is_relative_to(work):
        raise ArchiveError('archive workspace and source must be disjoint')
    with exclusive(work):
        free_guard(work, min_free, max_member)
        with sqlite3.connect(work / 'blobs.sqlite3') as cache:
            cache.execute('CREATE TABLE IF NOT EXISTS scope(identity TEXT PRIMARY KEY)')
            old = cache.execute('SELECT identity FROM scope').fetchone()
            if old and old[0] != backend.identity:
                raise ArchiveError('archive cache belongs to another COS namespace')
            cache.execute('INSERT OR IGNORE INTO scope VALUES (?)', (backend.identity,))
            cache.execute('''CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY,
                pack TEXT,offset INTEGER,length INTEGER,encoding TEXT,stored_hash TEXT)''')
            cache.commit()
            job = work / name
            db = prepare(job, spec)
            db.row_factory = sqlite3.Row
            try:
                receipt = job / 'receipt.json'
                if receipt.exists():
                    return json.loads(receipt.read_bytes())
                pending = []
                pending_blobs = {}
                verified_packs = set()
                pack_path = job / 'pack.pending'
                pack = pack_path.open('wb')
                done = db.execute('SELECT count(*) FROM files WHERE raw_hash IS NOT NULL').fetchone()[0]
                total = db.execute('SELECT count(*) FROM files').fetchone()[0]

                def flush():
                    nonlocal pack, done
                    if not pending:
                        return
                    pack.flush()
                    os.fsync(pack.fileno())
                    pack.close()
                    key = 'packs/' + file_hash(pack_path) + '.pack'
                    backend.publish(key, pack_path)
                    verified_packs.add(key)
                    # Cache acknowledgement first; a crash before job commit reuses it.
                    with cache:
                        for path, raw_hash, size, offset, length, encoding, stored_hash, refs in pending:
                            cache.execute('INSERT OR IGNORE INTO blobs VALUES (?,?,?,?,?,?)',
                                          (raw_hash, key, offset, length, encoding, stored_hash))
                    with db:
                        for path, raw_hash, size, offset, length, encoding, stored_hash, refs in pending:
                            db.execute('UPDATE files SET raw_hash=?,raw_size=?,pack=?,offset=?,length=?,encoding=?,stored_hash=? WHERE path=?',
                                       (raw_hash, size, key, offset, length, encoding, stored_hash, path))
                            db.executemany('INSERT OR IGNORE INTO refs VALUES (?,?)', ((path, ref) for ref in refs))
                    done += len(pending)
                    pending.clear()
                    pending_blobs.clear()
                    if progress:
                        progress({'job': name, 'processed': done, 'total': total, 'verified_pack': key})
                    pack = pack_path.open('wb')
                    free_guard(work, min_free, max_member)

                try:
                    while True:
                        # Cursor sees this snapshot's initial NULL rows; checkpoint per pack.
                        for row in db.execute('SELECT * FROM files WHERE raw_hash IS NULL ORDER BY path'):
                            origin = Path(row['origin'])
                            source_root = job if origin.is_relative_to(job) else Path(spec['root']).absolute()
                            if spec['kind'] == 'tree':
                                if 'fingerprint' not in row.keys() or not row['fingerprint']:
                                    raise ArchiveError('legacy tree job requires a new snapshot')
                                info = origin.stat()
                                if json.dumps([info.st_ino, info.st_size, info.st_mtime_ns]) != row['fingerprint']:
                                    raise ArchiveError('tree file changed since inventory; start a new job')
                            data = stable_read(origin, source_root, max_member)
                            refs = validate(data, row, spec['kind'])
                            raw_hash = digest(data)
                            cached = cache.execute('SELECT pack,offset,length,encoding,stored_hash FROM blobs WHERE hash=?', (raw_hash,)).fetchone()
                            if cached:
                                if cached[0] not in verified_packs:
                                    from hashlib import sha256
                                    h = sha256()
                                    with backend.stream(cached[0]) as remote:
                                        for block in iter(lambda: remote.read(1024 * 1024), b''):
                                            h.update(block)
                                    if h.hexdigest() != check_hash(Path(cached[0]).stem):
                                        raise ArchiveError('cached remote pack hash mismatch')
                                    verified_packs.add(cached[0])
                                db.execute('UPDATE files SET raw_hash=?,raw_size=?,pack=?,offset=?,length=?,encoding=?,stored_hash=? WHERE path=?',
                                           (raw_hash, len(data), *cached, row['path']))
                                db.executemany('INSERT OR IGNORE INTO refs VALUES (?,?)', ((row['path'], ref) for ref in refs))
                                done += 1
                                if done % 5000 == 0:
                                    db.commit()
                                    if progress:
                                        progress({'job': name, 'processed': done, 'total': total, 'reused': True})
                                continue
                            encoding = 'identity' if row['path'].endswith(('.gz', '.parquet')) else 'gzip'
                            stored = data if encoding == 'identity' else gzip.compress(data, compresslevel=6, mtime=0)
                            if pack.tell() and pack.tell() + len(stored) > pack_bytes:
                                flush()
                            if raw_hash in pending_blobs:
                                offset, length, encoding, stored_hash = pending_blobs[raw_hash]
                            else:
                                offset, length, stored_hash = pack.tell(), len(stored), digest(stored)
                                pack.write(stored)
                                pending_blobs[raw_hash] = (offset, length, encoding, stored_hash)
                            pending.append((row['path'], raw_hash, len(data), offset, length, encoding, stored_hash, refs))
                            if len(pending) >= 10000:
                                flush()
                        flush()
                        db.commit()
                        missing = [r[0] for r in db.execute('SELECT DISTINCT child FROM refs WHERE NOT EXISTS (SELECT 1 FROM files WHERE semantic=child) LIMIT 1000')]
                        if not missing:
                            break
                        # Roots remain pinned. Add only explicitly referenced immutable hashes,
                        # including dependencies published in an already-scanned shard.
                        for key in missing:
                            check_hash(key)
                            plain = Path(spec['root']) / 'objects' / key[:2] / (key + '.json')
                            found = False
                            for path in (plain, plain.with_suffix('.json.gz')):
                                if path.exists():
                                    from .snapshot import regular
                                    regular(path, Path(spec['root']))
                                    db.execute('INSERT OR IGNORE INTO files(path,origin,semantic) VALUES (?,?,?)',
                                        (path.relative_to(Path(spec['root'])).as_posix(), str(path), key))
                                    found = True
                            if not found:
                                raise ArchiveError(f'missing CAS reference: {key}')
                        db.commit()
                        total = db.execute('SELECT count(*) FROM files').fetchone()[0]
                finally:
                    pack.close()
                if db.execute('SELECT count(*) FROM files WHERE raw_hash IS NULL').fetchone()[0]:
                    raise ArchiveError('incomplete archive snapshot')
                # A standalone SQLite catalogue is small enough to fetch once; member data is ranged.
                catalogue = job / 'catalogue.sqlite3.gz'
                with (job / 'snapshot.sqlite3').open('rb') as src, catalogue.open('wb') as dst:
                    with gzip.GzipFile(fileobj=dst, mode='wb', mtime=0, compresslevel=6) as z:
                        shutil.copyfileobj(src, z, 1024 * 1024)
                    dst.flush()
                    os.fsync(dst.fileno())
                catalogue_hash = file_hash(catalogue)
                catalogue_key = 'catalogues/' + catalogue_hash + '.sqlite3.gz'
                backend.publish(catalogue_key, catalogue)
                stats = db.execute('SELECT count(*),sum(raw_size),sum(length),count(distinct pack) FROM files').fetchone()
                manifest = {'schema': 'htx-cos-archive-v1', 'namespace': backend.identity,
                    'job': name, 'source': spec, 'snapshot': dict(db.execute('SELECT key,value FROM meta')),
                    'catalogue': catalogue_key, 'catalogue_sha256': catalogue_hash,
                    'catalogue_bytes': catalogue.stat().st_size,
                    'catalogue_raw_bytes': (job / 'snapshot.sqlite3').stat().st_size,
                    'files': stats[0], 'source_bytes': stats[1] or 0, 'member_bytes': stats[2] or 0,
                    'packs': stats[3], 'references': db.execute('SELECT count(*) FROM refs').fetchone()[0],
                    'source_retained': True, 'storage_class': 'STANDARD', 'explicit_reference_closure': 'VERIFIED'}
                manifest_data = canonical(manifest)
                manifest_key = 'manifests/' + digest(manifest_data) + '.json'
                atomic(job / 'manifest.json', manifest_data)
                backend.publish(manifest_key, job / 'manifest.json')
                result = dict(manifest, manifest=manifest_key, verified_at=datetime.now(timezone.utc).isoformat())
                atomic(receipt, canonical(result))
                pack_path.unlink(missing_ok=True)
                return result
            finally:
                db.close()


def bounded_download(backend, key, path, expected_hash, maximum, *, start=None, length=None):
    check_hash(expected_hash)
    with backend.stream(key, start, length) as stream, path.open('wb') as dst:
        total = 0
        while block := stream.read(min(1024 * 1024, maximum + 1 - total)):
            total += len(block)
            if total > maximum:
                raise ArchiveError('remote response exceeds declared length')
            dst.write(block)
    if file_hash(path) != expected_hash or (length is not None and total != length):
        raise ArchiveError('download hash/length mismatch')


@contextmanager
def open_catalogue(backend, manifest_key, scratch, min_free=1024**3):
    if not manifest_key.startswith('manifests/') or not manifest_key.endswith('.json'):
        raise ArchiveError('invalid manifest key')
    expected = check_hash(Path(manifest_key).stem)
    scratch = Path(scratch)
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='archive-read-', dir=scratch) as temp:
        temp = Path(temp)
        bounded_download(backend, manifest_key, temp / 'manifest.json', expected, 1024**2)
        manifest = json.loads((temp / 'manifest.json').read_bytes())
        if manifest['schema'] != 'htx-cos-archive-v1' or manifest['namespace'] != backend.identity:
            raise ArchiveError('manifest schema/namespace mismatch')
        free_guard(scratch, min_free, manifest['catalogue_bytes'] + manifest['catalogue_raw_bytes'])
        bounded_download(backend, manifest['catalogue'], temp / 'catalogue.gz', manifest['catalogue_sha256'], manifest['catalogue_bytes'])
        size = 0
        with gzip.open(temp / 'catalogue.gz', 'rb') as src, (temp / 'catalogue.sqlite3').open('wb') as dst:
            while block := src.read(1024 * 1024):
                size += len(block)
                if size > manifest['catalogue_raw_bytes']:
                    raise ArchiveError('catalogue size mismatch')
                dst.write(block)
        db = sqlite3.connect((temp / 'catalogue.sqlite3').as_uri() + '?mode=ro', uri=True)
        db.row_factory = sqlite3.Row
        try:
            yield manifest, db
        finally:
            db.close()


def restore(backend, manifest_key, destination, *, scratch, paths=(), cas=(),
            all_files=False, min_free=1024**3):
    """Restore full snapshot, selected paths, or a reference-closed CAS subgraph."""
    destination = Path(destination).absolute()
    destination.mkdir(parents=True, exist_ok=True)
    if destination.resolve() != destination:
        raise ArchiveError('restore destination cannot contain symlinks')
    if not (all_files or paths or cas):
        raise ArchiveError('explicit --all, --path or --cas is required')
    with open_catalogue(backend, manifest_key, scratch, min_free) as (manifest, db), tempfile.TemporaryFile(dir=scratch) as pack_cache:
        if all_files:
            rows = db.execute('SELECT * FROM files ORDER BY pack,offset,path')
        else:
            selected = set(safe_relative(p) for p in paths)
            todo, seen = list(cas), set()
            while todo:
                key = check_hash(todo.pop())
                if key in seen:
                    continue
                seen.add(key)
                found = list(db.execute('SELECT path FROM files WHERE semantic=?', (key,)))
                if not found:
                    raise ArchiveError('CAS root/reference missing from catalogue')
                for row in found:
                    selected.add(row['path'])
                    todo.extend(x[0] for x in db.execute('SELECT child FROM refs WHERE parent=?', (row['path'],)))
            def selected_rows():
                for path in sorted(selected):
                    row = db.execute('SELECT * FROM files WHERE path=?', (path,)).fetchone()
                    if row is None:
                        raise ArchiveError('requested path absent from catalogue')
                    yield row
            rows = selected_rows()
        restored, reused, total = 0, 0, 0
        cached_pack = None
        for row in rows:
            relative = safe_relative(row['path'])
            target = destination / relative
            # Check parents before mkdir; do not follow attacker-provided symlinks.
            for p in (target, *target.parents):
                if p.is_symlink():
                    raise ArchiveError('symlink restore path')
                if p == destination:
                    break
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                if not target.is_file() or file_hash(target) != row['raw_hash']:
                    raise ArchiveError('restore target conflicts with archived bytes')
                reused += 1
                continue
            free_guard(destination, min_free, row['raw_size'] + row['length'])
            with tempfile.TemporaryDirectory(prefix='.restore-', dir=target.parent) as temp:
                temp = Path(temp)
                if all_files:
                    if cached_pack != row['pack']:
                        free_guard(scratch, min_free, 512 * 1024**2)
                        check_hash(Path(row['pack']).stem)
                        with backend.stream(row['pack']) as src:
                            from hashlib import sha256
                            h, size = sha256(), 0
                            pack_cache.seek(0)
                            pack_cache.truncate()
                            while block := src.read(1024 * 1024):
                                size += len(block)
                                if size > 512 * 1024**2:
                                    raise ArchiveError('pack exceeds reader limit')
                                h.update(block)
                                pack_cache.write(block)
                        if h.hexdigest() != Path(row['pack']).stem:
                            raise ArchiveError('pack hash mismatch')
                        cached_pack = row['pack']
                    pack_cache.seek(row['offset'])
                    with (temp / 'member').open('wb') as dst:
                        remaining = row['length']
                        while remaining:
                            block = pack_cache.read(min(1024 * 1024, remaining))
                            if not block:
                                raise ArchiveError('truncated pack member')
                            dst.write(block)
                            remaining -= len(block)
                    if file_hash(temp / 'member') != row['stored_hash']:
                        raise ArchiveError('pack member hash mismatch')
                else:
                    bounded_download(backend, row['pack'], temp / 'member', row['stored_hash'], row['length'],
                                     start=row['offset'], length=row['length'])
                if row['encoding'] == 'gzip':
                    size = 0
                    with gzip.open(temp / 'member', 'rb') as src, (temp / 'raw').open('wb') as dst:
                        while block := src.read(1024 * 1024):
                            size += len(block)
                            if size > row['raw_size']:
                                raise ArchiveError('restored size exceeds catalogue')
                            dst.write(block)
                    raw = temp / 'raw'
                elif row['encoding'] == 'identity':
                    raw = temp / 'member'
                else:
                    raise ArchiveError('unknown member encoding')
                if raw.stat().st_size != row['raw_size'] or file_hash(raw) != row['raw_hash']:
                    raise ArchiveError('original file hash/size mismatch')
                with raw.open('rb') as f:
                    os.fsync(f.fileno())
                # Publish without replacement, including races after initial target check.
                os.link(raw, target)
                sync_directory(target.parent)
                restored += 1
                total += row['raw_size']
        result = {'manifest': manifest_key, 'restored': restored, 'reused': reused, 'bytes': total,
                  'source_retained': True, 'status': 'COMPLETE',
                  'scope': 'full_snapshot' if all_files else 'selection',
                  'paths': list(paths), 'cas': list(cas)}
        marker = destination / ('.cos-restore-complete-' + Path(manifest_key).stem + '.json')
        if marker.is_symlink():
            raise ArchiveError('symlink restore marker')
        atomic(marker, canonical(result))
        return result
