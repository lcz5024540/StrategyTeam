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

from .backend import ArchiveError, SnapshotChanged, check_hash, digest, file_hash
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
        yield lock


def free_guard(path, minimum, needed=0):
    if shutil.disk_usage(path).free < minimum + needed:
        raise ArchiveError('archive workspace free-space guard')


def archive(backend, work, name, spec, **kwargs):
    root = Path(spec['root'])
    marker = root / ('segments/.sealing/config.json' if spec['kind'] == 'capture' else '.sealing/config.json')
    if spec['kind'] in {'cas','capture'} and marker.exists():
        raise ArchiveError('segmented active source requires the segments scheduler/sealed-generation adapter')
    if spec['kind'] in {'sealed-cas', 'sealed-capture'}:
        from ..sealing.control import lease
        if kwargs.get('previous'):
            raise ArchiveError('sealed generations are immutable independent snapshots')
        # SH lease protects local files during upload; does not block writers.
        with lease(spec['root']):
            return _archive(backend, work, name, spec, **kwargs)
    return _archive(backend, work, name, spec, **kwargs)


def _archive(backend, work, name, spec, *, pack_bytes=32 * 1024**2,
            max_member=512 * 1024**2, min_free=5 * 1024**3, progress=None, previous=None):
    safe_relative(name)
    if '/' in name or pack_bytes < 1 or max_member < 1:
        raise ArchiveError('invalid archive job or size')
    work = Path(work).absolute()
    source_root = Path(spec['root']).absolute()
    if work.is_relative_to(source_root) or source_root.is_relative_to(work):
        raise ArchiveError('archive workspace and source must be disjoint')
    with exclusive(work):
        receipt_path = work / name / 'receipt.json'
        if receipt_path.exists():
            completed = json.loads(receipt_path.read_bytes())
            if completed.get('job') != name:
                raise ArchiveError('receipt job identity mismatch')
            if completed['source'] != spec:
                raise ArchiveError('job belongs to another source/specification')
            if completed['namespace'] != backend.identity:
                raise ArchiveError('archive cache belongs to another COS namespace')
            return completed
        free_guard(work, min_free)
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
                if previous:
                    inherit_verified(backend, previous, db, spec, work, min_free)
                receipt = job / 'receipt.json'
                if receipt.exists():
                    return json.loads(receipt.read_bytes())
                pending = []
                pending_blobs = {}
                verified_packs = set()
                pack_path = job / 'pack.pending'
                pack = pack_path.open('wb', buffering=0)
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
                    pack = pack_path.open('wb', buffering=0)
                    free_guard(work, min_free)

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
                                if json.dumps([info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns]) != row['fingerprint']:
                                    raise SnapshotChanged('tree file changed since inventory; reset the pending source after investigation')
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
                                # max_member bounds accepted input, not required scratch space.
                                # Check actual bytes before each unbuffered write instead.
                                free_guard(work, min_free, len(stored))
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
                if previous and unchanged(backend, previous, db, work, min_free):
                    with open_catalogue(backend, previous, work, min_free) as (old, _):
                        result = dict(old, job=name, manifest=previous, unchanged=True,
                                      verified_at=datetime.now(timezone.utc).isoformat())
                    atomic(receipt, canonical(result))
                    pack_path.unlink(missing_ok=True)
                    return result
                # A standalone SQLite catalogue is small enough to fetch once; member data is ranged.
                catalogue_source = job / 'snapshot.sqlite3'
                parent_meta = None
                if previous:
                    catalogue_source, parent_meta = delta_catalogue(backend, previous, db, job, work, min_free)
                catalogue = job / 'catalogue.sqlite3.gz'
                # Gzip can slightly expand incompressible bytes; reserve a conservative bound.
                free_guard(work, min_free, catalogue_source.stat().st_size * 2 + 1024)
                with catalogue_source.open('rb') as src, catalogue.open('wb') as dst:
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
                    'catalogue_raw_bytes': catalogue_source.stat().st_size,
                    'files': stats[0], 'source_bytes': stats[1] or 0, 'member_bytes': stats[2] or 0,
                    'packs': stats[3], 'references': db.execute('SELECT count(*) FROM refs').fetchone()[0],
                    'source_retained': True, 'storage_class': 'STANDARD', 'explicit_reference_closure': 'VERIFIED'}
                if parent_meta is not None:
                    manifest.update(schema='htx-cos-archive-v2', parent_manifest=previous,
                        chain_depth=parent_meta.get('chain_depth', 0) + 1,
                        materialized_bytes=(job / 'snapshot.sqlite3').stat().st_size)
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
    """Resolve a bounded delta chain into one authenticated, self-contained view."""
    scratch = Path(scratch)
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='archive-read-', dir=scratch) as directory:
        temp = Path(directory)
        chain, seen, key = [], set(), manifest_key
        while key:
            if not key.startswith('manifests/') or not key.endswith('.json') or key in seen or len(chain) >= 128:
                raise ArchiveError('invalid or overlong manifest chain')
            seen.add(key)
            expected = check_hash(Path(key).stem)
            bounded_download(backend, key, temp / 'manifest.json', expected, 1024**2)
            manifest = json.loads((temp / 'manifest.json').read_bytes())
            if manifest['schema'] not in {'htx-cos-archive-v1', 'htx-cos-archive-v2'} or manifest['namespace'] != backend.identity:
                raise ArchiveError('manifest schema/namespace mismatch')
            if chain and chain[0]['source'] != manifest['source']:
                raise ArchiveError('delta source mismatch')
            chain.append(manifest)
            key = manifest.get('parent_manifest') if manifest['schema'] == 'htx-cos-archive-v2' else None
            if manifest['schema'] == 'htx-cos-archive-v2' and not key:
                raise ArchiveError('delta missing parent')
        if not chain or chain[0].get('chain_depth', 0) != len(chain) - 1:
            raise ArchiveError('manifest chain depth mismatch')
        db = None
        try:
            for position, manifest in enumerate(reversed(chain)):
                free_guard(scratch, min_free, manifest['catalogue_bytes'] + manifest['catalogue_raw_bytes'] +
                           manifest.get('materialized_bytes', 0))
                bounded_download(backend, manifest['catalogue'], temp / 'catalogue.gz',
                                 manifest['catalogue_sha256'], manifest['catalogue_bytes'])
                size = 0
                raw_path = temp / ('catalogue.sqlite3' if position == 0 else 'delta.sqlite3')
                with gzip.open(temp / 'catalogue.gz', 'rb') as src, raw_path.open('wb') as dst:
                    while block := src.read(1024 * 1024):
                        size += len(block)
                        if size > manifest['catalogue_raw_bytes']:
                            raise ArchiveError('catalogue size mismatch')
                        dst.write(block)
                if size != manifest['catalogue_raw_bytes']:
                    raise ArchiveError('catalogue size mismatch')
                if position == 0:
                    db = sqlite3.connect(raw_path)
                    db.row_factory = sqlite3.Row
                else:
                    db.execute('ATTACH DATABASE ? AS delta', (str(raw_path),))
                    try:
                        db.execute('DELETE FROM refs WHERE parent IN (SELECT path FROM delta.files UNION SELECT path FROM delta.deleted)')
                        db.execute('DELETE FROM files WHERE path IN (SELECT path FROM delta.deleted)')
                        db.execute('INSERT OR REPLACE INTO files SELECT * FROM delta.files')
                        db.execute('INSERT OR REPLACE INTO refs SELECT * FROM delta.refs')
                        db.execute('DELETE FROM meta')
                        db.execute('INSERT INTO meta SELECT * FROM delta.meta')
                        db.commit()
                    finally:
                        db.execute('DETACH DATABASE delta')
                    raw_path.unlink()
            if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise ArchiveError('invalid catalogue SQLite')
            if db.execute('SELECT count(*) FROM files').fetchone()[0] != chain[0]['files']:
                raise ArchiveError('catalogue file count mismatch')
            if db.execute('SELECT 1 FROM refs WHERE NOT EXISTS(SELECT 1 FROM files WHERE semantic=child) LIMIT 1').fetchone():
                raise ArchiveError('catalogue reference closure missing')
            yield chain[0], db
        finally:
            if db is not None:
                db.close()


def restore(backend, manifest_key, destination, *, scratch, paths=(), cas=(),
            all_files=False, min_free=1024**3, verify_only=False):
    """Restore full snapshot, selected paths, or a reference-closed CAS subgraph."""
    destination = Path(destination).absolute()
    destination.mkdir(parents=True, exist_ok=True)
    if destination.resolve() != destination:
        raise ArchiveError('restore destination cannot contain symlinks')
    if not (all_files or paths or cas):
        raise ArchiveError('explicit --all, --path or --cas is required')
    with open_catalogue(backend, manifest_key, scratch, min_free) as (manifest, db), tempfile.TemporaryFile(dir=scratch, buffering=0) as pack_cache:
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
            if target.exists() and not verify_only:
                if not target.is_file() or file_hash(target) != row['raw_hash']:
                    raise ArchiveError('restore target conflicts with archived bytes')
                reused += 1
                continue
            free_guard(destination, min_free, row['raw_size'] + row['length'])
            with tempfile.TemporaryDirectory(prefix='.restore-', dir=target.parent) as temp:
                temp = Path(temp)
                if all_files:
                    if cached_pack != row['pack']:
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
                                free_guard(scratch, min_free, len(block))
                                h.update(block)
                                pack_cache.write(block)
                        if h.hexdigest() != Path(row['pack']).stem:
                            raise ArchiveError('pack hash mismatch')
                        cached_pack = row['pack']
                    # The full pack may share the destination filesystem. Recheck after
                    # downloading it, before allocating the member and decoded bytes.
                    free_guard(destination, min_free, row['raw_size'] + row['length'])
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
                if not verify_only:
                    os.link(raw, target)
                    sync_directory(target.parent)
                restored += 1
                total += row['raw_size']
        result = {'manifest': manifest_key, 'restored': restored, 'reused': reused, 'bytes': total,
                  'source_retained': True, 'status': 'COMPLETE',
                  'scope': ('verification_only' if verify_only else ('full_snapshot' if all_files else 'selection')),
                  'paths': list(paths), 'cas': list(cas)}
        marker = destination / ('.cos-restore-complete-' + Path(manifest_key).stem + '.json')
        if marker.is_symlink():
            raise ArchiveError('symlink restore marker')
        atomic(marker, canonical(result))
        return result


def compact_staging(backend, work, name):
    """Release only the completed job's raw catalogue; retain gzip, receipt and cache.

    The source evidence/index/ledger and the frozen source index are never removed.
    """
    safe_relative(name)
    if '/' in name:
        raise ArchiveError('invalid job name')
    work = Path(work).absolute()
    with exclusive(work):
        job = work / name
        if job.is_symlink():
            raise ArchiveError('symlink job')
        receipt = json.loads((job / 'receipt.json').read_bytes())
        if receipt.get('job') != name:
            raise ArchiveError('receipt job identity mismatch')
        if receipt['namespace'] != backend.identity:
            raise ArchiveError('different COS namespace')
        manifest = job / 'manifest.json'
        compressed = job / 'catalogue.sqlite3.gz'
        raw = job / ('delta.sqlite3' if receipt.get('schema') == 'htx-cos-archive-v2' else 'snapshot.sqlite3')
        from .snapshot import regular
        regular(manifest, job)
        regular(compressed, job)
        if file_hash(manifest) != check_hash(Path(receipt['manifest']).stem):
            raise ArchiveError('manifest hash mismatch')
        declared = json.loads(manifest.read_bytes())
        if (declared['catalogue'] != receipt['catalogue'] or
                declared['catalogue_sha256'] != receipt['catalogue_sha256'] or
                file_hash(compressed) != receipt['catalogue_sha256']):
            raise ArchiveError('catalogue receipt mismatch')
        # Verify remote recoverability before releasing our local derived copy.
        backend.publish(receipt['manifest'], manifest)
        backend.publish(receipt['catalogue'], compressed)
        removed = 0
        if raw.exists():
            regular(raw, job)
            from hashlib import sha256
            h = sha256()
            with gzip.open(compressed, 'rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    h.update(block)
            if h.hexdigest() != file_hash(raw):
                raise ArchiveError('raw catalogue differs from compressed copy')
            removed = raw.stat().st_size
            raw.unlink()
            full = job / 'snapshot.sqlite3'
            if raw != full and full.exists():
                regular(full, job)
                removed += full.stat().st_size
                full.unlink()
            sync_directory(job)
        return {'job': name, 'released_staging_bytes': removed,
                'retained_compressed_catalogue': str(compressed),
                'source_deleted': False, 'status': 'VERIFIED'}


def inherit_verified(backend, previous, db, spec, scratch, min_free):
    """Reuse verified immutable members; metadata is always read from this snapshot.

    A fingerprint is a local optimization, not a fresh remote audit. Use archive
    without previous for a full read/GET audit. Old three-field inventories do
    not qualify. Incremental v2 catalogues use bounded parent chains with periodic full checkpoints.
    """
    with open_catalogue(backend, previous, scratch, min_free) as (manifest, old):
        if manifest['source'] != spec:
            raise ArchiveError('incremental source specification mismatch')
        old_path = old.execute('PRAGMA database_list').fetchone()[2]
        db.execute('ATTACH DATABASE ? AS prior', (old_path,))
        try:
            db.execute("""UPDATE files SET
                (raw_hash,raw_size,pack,offset,length,encoding,stored_hash)=
                (SELECT raw_hash,raw_size,pack,offset,length,encoding,stored_hash
                 FROM prior.files p WHERE p.path=files.path)
                WHERE raw_hash IS NULL AND fingerprint IS NOT NULL
                AND json_array_length(fingerprint)=4
                AND origin NOT LIKE ? AND EXISTS
                (SELECT 1 FROM prior.files p WHERE p.path=files.path
                 AND p.origin=files.origin AND p.semantic IS files.semantic
                 AND p.fingerprint=files.fingerprint AND p.raw_hash IS NOT NULL)""",
                (str(Path(scratch).absolute()) + '/%',))
            db.execute("""INSERT OR IGNORE INTO refs SELECT r.parent,r.child FROM prior.refs r
                JOIN files f ON f.path=r.parent WHERE f.raw_hash IS NOT NULL""")
            db.commit()
        finally:
            db.execute('DETACH DATABASE prior')


def unchanged(backend, previous, db, scratch, min_free):
    with open_catalogue(backend, previous, scratch, min_free) as (_, old):
        old_path = old.execute('PRAGMA database_list').fetchone()[2]
        db.execute('ATTACH DATABASE ? AS prior', (old_path,))
        try:
            # Metadata timestamps/job paths are observations, not new data.
            return (db.execute('SELECT count(*) FROM files').fetchone()[0] ==
                    db.execute('SELECT count(*) FROM prior.files').fetchone()[0] and
                    db.execute("""SELECT 1 FROM files f LEFT JOIN prior.files p ON f.path=p.path
                        WHERE p.path IS NULL OR f.raw_hash IS NOT p.raw_hash LIMIT 1""").fetchone() is None)
        finally:
            db.execute('DETACH DATABASE prior')


def delta_catalogue(backend, previous, db, job, scratch, min_free):
    with open_catalogue(backend, previous, scratch, min_free) as (manifest, old):
        if manifest.get('chain_depth', 0) >= 127:
            raise ArchiveError('delta chain limit reached; run a full audit checkpoint')
        target = job / 'delta.sqlite3'
        target.unlink(missing_ok=True)
        delta = sqlite3.connect(target)
        try:
            for row in db.execute("SELECT sql FROM sqlite_master WHERE type IN ('table','index') AND sql IS NOT NULL"):
                delta.execute(row[0])
            delta.execute('ATTACH DATABASE ? AS prior', (old.execute('PRAGMA database_list').fetchone()[2],))
            delta.execute('ATTACH DATABASE ? AS current', (db.execute('PRAGMA database_list').fetchone()[2],))
            delta.execute('CREATE TABLE deleted(path TEXT PRIMARY KEY)')
            delta.execute('INSERT INTO deleted SELECT path FROM prior.files WHERE path NOT IN (SELECT path FROM current.files)')
            delta.execute("""INSERT INTO files SELECT f.* FROM current.files f LEFT JOIN prior.files p ON f.path=p.path
                WHERE p.path IS NULL OR p.raw_hash IS NOT f.raw_hash OR p.fingerprint IS NOT f.fingerprint
                OR p.origin IS NOT f.origin""")
            delta.execute('INSERT INTO refs SELECT r.* FROM current.refs r JOIN files f ON f.path=r.parent')
            delta.execute('INSERT INTO meta SELECT * FROM current.meta')
            delta.commit()
            delta.execute('DETACH DATABASE prior')
            delta.execute('DETACH DATABASE current')
        finally:
            delta.close()
        return target, manifest
