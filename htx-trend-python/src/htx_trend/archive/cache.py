"""Research-only verified LRU cache. Hold the lease for the entire replay."""
from contextlib import contextmanager
import json
from pathlib import Path
import shutil
import time

from .backend import ArchiveError, check_hash, digest, file_hash
from .snapshot import safe_relative
from .transfer import atomic, canonical, exclusive, free_guard, open_catalogue, restore


def selected_paths(db, paths, cas, all_files):
    if all_files:
        return [r[0] for r in db.execute('SELECT path FROM files ORDER BY path')]
    selected = set(safe_relative(p) for p in paths)
    todo, seen = list(cas), set()
    while todo:
        key = check_hash(todo.pop())
        if key in seen:
            continue
        seen.add(key)
        rows = list(db.execute('SELECT path FROM files WHERE semantic=?', (key,)))
        if not rows:
            raise ArchiveError('CAS root/reference missing from catalogue')
        for row in rows:
            selected.add(row[0])
            todo.extend(r[0] for r in db.execute('SELECT child FROM refs WHERE parent=?', (row[0],)))
    if not selected:
        raise ArchiveError('explicit replay selection required')
    return sorted(selected)


class ReplayCache:
    def __init__(self, backend, root, *, max_bytes=2 * 1024**3, min_free=5 * 1024**3):
        self.backend, self.root = backend, Path(root).absolute()
        self.maximum, self.minimum = int(max_bytes), int(min_free)
        if self.maximum <= 0 or self.minimum < 0:
            raise ArchiveError('invalid cache capacity')
        self.root.mkdir(parents=True, exist_ok=True)
        if self.root.resolve() != self.root:
            raise ArchiveError('cache root cannot contain symlinks')

    def _valid(self, entry, request):
        try:
            meta = json.loads((entry / 'cache.json').read_bytes())
            if meta['request'] != request:
                return None
            data = entry / 'data'
            expected = set(meta['files'])
            actual = set()
            for p in data.rglob('*'):
                if p.is_symlink():
                    return None
                if p.is_file() and not p.name.startswith('.cos-restore-complete-'):
                    actual.add(p.relative_to(data).as_posix())
            if expected != actual:
                return None
            for path, info in meta['files'].items():
                p = data / safe_relative(path)
                if p.stat().st_size != info['size'] or file_hash(p) != info['hash']:
                    return None
            return meta
        except (ValueError, KeyError, OSError):
            return None

    def _entries(self):
        for p in self.root.iterdir():
            if len(p.name) == 64:
                check_hash(p.name)
                if p.is_symlink() or not p.is_dir():
                    raise ArchiveError('unsafe cache entry')
                yield p

    def _size(self, p):
        total = 0
        for item in p.rglob('*'):
            if item.is_symlink():
                raise ArchiveError('unsafe cache child')
            if item.is_file():
                total += item.stat().st_size
        return total

    def _make_room(self, needed, keep):
        entries = sorted(self._entries(), key=lambda p: p.stat().st_mtime_ns)
        used = sum(self._size(p) for p in entries)
        for p in entries:
            if used + needed <= self.maximum:
                break
            if p == keep:
                continue
            size = self._size(p)
            shutil.rmtree(p)  # Only derived cache entries, under the lifetime lease lock.
            used -= size
        if used + needed > self.maximum:
            raise ArchiveError('selection exceeds replay cache quota; use a smaller selection or larger research cache')

    @contextmanager
    def lease(self, manifest, *, paths=(), cas=(), all_files=False):
        request = {'namespace': self.backend.identity, 'manifest': manifest,
                   'paths': sorted(set(paths)), 'cas': sorted(set(cas)), 'all': bool(all_files)}
        key = digest(canonical(request))
        with exclusive(self.root) as lock:
            owner = self.root / 'owner.json'
            if owner.exists():
                if json.loads(owner.read_bytes()) != {'schema': 'htx-replay-cache-v1'}:
                    raise ArchiveError('not an archive replay cache')
            else:
                if any(p.name != 'archive.lock' for p in self.root.iterdir()):
                    raise ArchiveError('cache must start in an empty private directory')
                atomic(owner, canonical({'schema': 'htx-replay-cache-v1'}))
            entry = self.root / key
            if entry.is_symlink():
                raise ArchiveError('symlink cache entry')
            meta = self._valid(entry, request) if entry.exists() else None
            if meta is None:
                if entry.exists():
                    self._size(entry)  # Reject links before touching an invalid derived entry.
                    shutil.rmtree(entry)
                with open_catalogue(self.backend, manifest, self.root, self.minimum) as (_, db):
                    files = {}
                    for path in selected_paths(db, paths, cas, all_files):
                        row = db.execute('SELECT raw_size,raw_hash FROM files WHERE path=?', (path,)).fetchone()
                        if row is None:
                            raise ArchiveError('requested path absent from catalogue')
                        files[path] = {'size': row[0], 'hash': row[1]}
                meta = {'request': request, 'files': files, 'created': time.time()}
                reserve = sum(r['size'] for r in files.values()) + len(canonical(meta)) + 16384
                self._make_room(reserve, entry)
                free_guard(self.root, self.minimum, reserve)
                entry.mkdir()
                try:
                    restore(self.backend, manifest, entry / 'data', scratch=self.root,
                            paths=paths, cas=cas, all_files=all_files, min_free=self.minimum)
                    atomic(entry / 'cache.json', canonical(meta))
                    if self._valid(entry, request) is None:
                        raise ArchiveError('cache restore validation failed')
                    self._make_room(0, entry)
                except BaseException:
                    shutil.rmtree(entry)
                    raise
            else:
                self._make_room(0, entry)
            entry.touch()
            # The lock stays held across subprocess replay; eviction cannot race a reader.
            self.lease_fd = lock.fileno()
            try:
                yield entry / 'data'
            finally:
                self.lease_fd = None
