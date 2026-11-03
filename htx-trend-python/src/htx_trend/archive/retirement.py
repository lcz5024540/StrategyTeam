"""Exact-list retirement of frozen datasets. Never age-only deletion of active data."""
from contextlib import contextmanager
import shutil
import json
from pathlib import Path
import sqlite3
import tempfile

from .backend import ArchiveError, file_hash
from .snapshot import regular, safe_relative, prepare
from .transfer import atomic, canonical, exclusive, open_catalogue, restore, sync_directory


def fingerprint(path):
    s = path.stat()
    return canonical([s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns]).decode()


def reason(path, kind, policy):
    parts = Path(path).parts
    name = parts[-1].lower()
    if (any(p.startswith('.') for p in parts) or
            any(token in name for token in ('env', 'credential', 'secret', 'paper-state', 'ledger')) or
            name.endswith(('.sqlite3', '.db', '-wal', '-shm', '.lock', '.partial'))):
        return 'protected-state-or-metadata'
    if policy.get('state') != 'FROZEN' or policy.get('no_local_readers') is not True:
        return 'active-or-unproven-source'
    if not policy.get('attestation'):
        return 'missing-operator-freeze-attestation'
    if kind == 'cas' and path.startswith('objects/'):
        return None
    if kind == 'capture' and path.startswith('segments/') and name.endswith('.json.gz'):
        return None
    if kind == 'tree' and Path(path).suffix in {'.json', '.parquet', '.yaml', '.md', '.log'}:
        return None
    return 'protected-index-or-unclassified'


def preview(backend, manifest_key, spec, policy, output, *, scratch, min_free=5 * 1024**3):
    output = Path(output).absolute()
    if output.exists():
        raise ArchiveError('retirement plan output already exists')
    root = Path(spec['root']).absolute()
    if root.resolve() != root or output.is_relative_to(root):
        raise ArchiveError('plan must be outside a real source root')
    output.parent.mkdir(parents=True, exist_ok=True)
    with open_catalogue(backend, manifest_key, scratch, min_free) as (manifest, catalogue):
        if manifest['source'] != spec:
            raise ArchiveError('retirement source mismatch')
        # Freeze a fresh inventory for frozen sources. Old archived boundaries must
        # not authorize retiring files referenced by newer local metadata/segments.
        frozen = policy.get('state') == 'FROZEN' and policy.get('no_local_readers') is True
        boundary_error = None
        if frozen:
            with tempfile.TemporaryDirectory(prefix='retirement-inventory-', dir=scratch) as temp:
                current = prepare(Path(temp), spec)
                try:
                    if current.execute('SELECT count(*) FROM files').fetchone()[0] != manifest['files']:
                        boundary_error = 'source-boundary-changed'
                    for item in current.execute('SELECT path,origin FROM files'):
                        old = catalogue.execute('SELECT raw_hash FROM files WHERE path=?', (item[0],)).fetchone()
                        if old is None or file_hash(item[1]) != old[0]:
                            boundary_error = 'source-boundary-changed'
                            break
                finally:
                    current.close()
        with sqlite3.connect(output) as plan:
            plan.executescript('''CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT);
                CREATE TABLE files(path TEXT PRIMARY KEY,hash TEXT,size INTEGER,fingerprint TEXT,blocked TEXT);''')
            plan.executemany('INSERT INTO meta VALUES (?,?)', [(k, canonical(v).decode()) for k, v in {
                'schema': 'htx-retirement-v1', 'namespace': backend.identity, 'manifest': manifest_key,
                'source': spec, 'policy': policy}.items()])
            for row in catalogue.execute('SELECT path,raw_hash,raw_size FROM files ORDER BY path'):
                path = root / safe_relative(row['path'])
                blocked = boundary_error or reason(row['path'], spec['kind'], policy)
                stamp = fingerprint(path) if path.is_file() and not path.is_symlink() else None
                if not blocked:
                    try:
                        regular(path, root)
                        stamp = fingerprint(path)
                        if file_hash(path) != row['raw_hash'] or fingerprint(path) != stamp:
                            blocked = 'source-changed'
                    except (OSError, ArchiveError):
                        blocked = 'source-missing-or-unsafe'
                plan.execute('INSERT INTO files VALUES (?,?,?,?,?)',
                             (row['path'], row['raw_hash'], row['raw_size'], stamp, blocked))
            counts = dict(plan.execute("SELECT coalesce(blocked,'eligible'),count(*) FROM files GROUP BY blocked"))
            amount = plan.execute('SELECT coalesce(sum(size),0) FROM files WHERE blocked IS NULL').fetchone()[0]
    return {'plan': str(output), 'approval_sha256': file_hash(output), 'counts': counts,
            'eligible_bytes': amount, 'source_deleted': False}



@contextmanager
def approved_plan(path, approval, scratch):
    path = Path(path).absolute()
    if path.is_symlink() or any(Path(str(path) + suffix).exists() for suffix in ('-wal', '-shm', '-journal')):
        raise ArchiveError('retirement plan must be a sealed standalone SQLite file')
    with tempfile.TemporaryDirectory(prefix='approved-plan-', dir=scratch) as temp:
        copy = Path(temp) / 'plan.sqlite3'
        shutil.copyfile(path, copy)
        if file_hash(copy) != approval:
            raise ArchiveError('retirement plan approval hash mismatch')
        db = sqlite3.connect(copy.as_uri() + '?mode=ro&immutable=1', uri=True)
        db.row_factory = sqlite3.Row
        try:
            if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise ArchiveError('invalid retirement plan database')
            yield db
        finally:
            db.close()

def apply(backend, plan_path, approval, policy, *, scratch, min_free=5 * 1024**3, expected_spec=None):
    plan_path = Path(plan_path).absolute()
    with exclusive(Path(scratch) / 'retirement'), approved_plan(plan_path, approval, scratch) as plan:
        plan.row_factory = sqlite3.Row
        meta = {r[0]: json.loads(r[1]) for r in plan.execute('SELECT key,value FROM meta')}
        if meta['namespace'] != backend.identity or meta['schema'] != 'htx-retirement-v1' or meta['policy'] != policy:
            raise ArchiveError('retirement policy or namespace changed')
        if policy.get('state') != 'FROZEN' or policy.get('no_local_readers') is not True or not policy.get('attestation'):
            raise ArchiveError('only explicitly frozen sources without local readers may retire')
        if expected_spec is not None and expected_spec != meta['source']:
            raise ArchiveError('approved source differs from configured source')
        root = Path(meta['source']['root']).absolute()
        if root.resolve() != root or not root.is_dir():
            raise ArchiveError('unsafe retirement root')
        eligible = plan.execute('SELECT count(*) FROM files WHERE blocked IS NULL').fetchone()[0]
        if not eligible:
            raise ArchiveError('no eligible files in approved plan')
        with open_catalogue(backend, meta['manifest'], scratch, min_free) as (remote, catalogue):
            if remote['source'] != meta['source']:
                raise ArchiveError('approved source differs from archived source')
            for item in plan.execute('SELECT path,hash,size FROM files WHERE blocked IS NULL'):
                member = catalogue.execute('SELECT raw_hash,raw_size FROM files WHERE path=?', (item['path'],)).fetchone()
                if member is None or tuple(member) != (item['hash'], item['size']):
                    raise ArchiveError('approved member is not recoverable from this manifest')
        journal_path = Path(scratch) / 'retirement' / (approval + '.sqlite3')
        with sqlite3.connect(journal_path) as journal:
            journal.execute('CREATE TABLE IF NOT EXISTS removed(path TEXT PRIMARY KEY,hash TEXT,size INTEGER)')
            journal.execute('CREATE TABLE IF NOT EXISTS pending(path TEXT PRIMARY KEY,hash TEXT,size INTEGER)')
            expected = {r[0] for r in plan.execute('SELECT path FROM files')}
            if meta['source']['kind'] == 'cas':
                actual = {p.relative_to(root).as_posix() for p in (root / 'objects').rglob('*') if p.is_file()}
            elif meta['source']['kind'] == 'capture':
                actual = {p.relative_to(root).as_posix() for p in (root / 'segments').glob('*.json.gz')}
            else:
                actual = {p.relative_to(root).as_posix() for p in root.rglob('*')
                          if p.is_file() and p.suffix in meta['source']['extensions'] and not p.name.startswith('.cos-')}
            if actual - expected:
                raise ArchiveError('new source files after approved preview')
            for row in plan.execute('SELECT * FROM files WHERE blocked IS NOT NULL AND fingerprint IS NOT NULL'):
                path = regular(root / safe_relative(row['path']), root)
                if fingerprint(path) != row['fingerprint']:
                    raise ArchiveError('source metadata changed after approved preview')
            # Check all sources before the first unlink; recover a crash after unlink
            # only when a durable intent exists for this exact path and hash.
            for row in plan.execute('SELECT * FROM files WHERE blocked IS NULL'):
                path = root / safe_relative(row['path'])
                prior = journal.execute('SELECT hash FROM removed WHERE path=? UNION SELECT hash FROM pending WHERE path=?',
                                        (row['path'], row['path'])).fetchone()
                if prior and not path.exists():
                    if prior[0] != row['hash']:
                        raise ArchiveError('retirement journal mismatch')
                    continue
                regular(path, root)
                if fingerprint(path) != row['fingerprint'] or file_hash(path) != row['hash']:
                    raise ArchiveError('source changed since approved preview')
                if reason(row['path'], meta['source']['kind'], policy):
                    raise ArchiveError('file no longer eligible')
            # Verify every remote byte, including protected index/metadata. One pack
            # and member at a time, avoiding a second full copy on the production disk.
            with tempfile.TemporaryDirectory(prefix='retirement-verify-', dir=scratch) as temp:
                restore(backend, meta['manifest'], temp, scratch=scratch, all_files=True,
                        verify_only=True, min_free=min_free)
            marker = root / '.cos-retired.json'
            if marker.is_symlink():
                raise ArchiveError('unsafe retirement marker')
            atomic(marker, canonical({'state': 'RETIRING', 'manifest': meta['manifest'],
                'plan_sha256': approval, 'restore_before_reuse': True}))
            released = 0
            for row in plan.execute('SELECT * FROM files WHERE blocked IS NULL ORDER BY path'):
                path = root / safe_relative(row['path'])
                if path.exists():
                    regular(path, root)
                    if fingerprint(path) != row['fingerprint'] or file_hash(path) != row['hash']:
                        raise ArchiveError('source changed during retirement')
                    with journal:
                        journal.execute('INSERT OR REPLACE INTO pending VALUES (?,?,?)',
                                        (row['path'], row['hash'], row['size']))
                    path.unlink()
                    sync_directory(path.parent)
                    released += row['size']
                with journal:
                    journal.execute('INSERT OR REPLACE INTO removed VALUES (?,?,?)',
                                    (row['path'], row['hash'], row['size']))
                    journal.execute('DELETE FROM pending WHERE path=?', (row['path'],))
            result = {'state': 'RETIRED', 'manifest': meta['manifest'], 'plan_sha256': approval,
                      'released_this_run': released, 'retired_files': eligible, 'restore_before_reuse': True}
            atomic(marker, canonical(result))
            atomic(Path(scratch) / 'retirement' / (approval + '.receipt.json'), canonical(result))
            return result
