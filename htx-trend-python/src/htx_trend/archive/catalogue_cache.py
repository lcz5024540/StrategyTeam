"""Explicit, GET-verified eviction/restoration of one completed catalogue cache.

Never deletes source files, frozen indices, packs, manifests or COS objects.
The archive workspace lock coordinates with archive/compaction writers.
"""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import tempfile

from .backend import ArchiveError, CosBackend, check_hash, digest, file_hash
from .snapshot import regular, safe_relative
from .transfer import atomic, canonical, exclusive, free_guard, sync_directory


def fingerprint(path):
    s = path.stat()
    return (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)


def verified_stream(backend, key, expected, size, destination=None):
    total, h = 0, sha256()
    with backend.stream(key) as stream:
        while block := stream.read(min(1024**2, size + 1 - total)):
            total += len(block)
            if total > size:
                raise ArchiveError('remote cache size mismatch')
            h.update(block)
            if destination is not None:
                destination.write(block)
    if total != size or h.hexdigest() != expected:
        raise ArchiveError('remote cache hash/size mismatch')


def catalogue_cache(backend, work, name, *, action='inspect', min_free=5 * 1024**3):
    if action not in {'inspect', 'evict', 'restore'}:
        raise ArchiveError('invalid cache action')
    safe_relative(name)
    if '/' in name:
        raise ArchiveError('job must be a single directory name')
    work = Path(work).absolute()
    if not work.is_dir() or work.resolve() != work:
        raise ArchiveError('workspace must exist without symlinks')
    with exclusive(work):
        job = work / name
        manifest_path = regular(job / 'manifest.json', work)
        receipt_path = regular(job / 'receipt.json', work)
        raw = manifest_path.read_bytes()
        if len(raw) > 1024**2:
            raise ArchiveError('manifest too large')
        manifest, receipt = json.loads(raw), json.loads(receipt_path.read_bytes())
        manifest_key = 'manifests/' + digest(raw) + '.json'
        key = 'catalogues/' + check_hash(manifest['catalogue_sha256']) + '.sqlite3.gz'
        size = manifest['catalogue_bytes']
        if (type(size) is not int or size <= 0 or
                manifest.get('job') != name or receipt.get('job') != name or
                manifest.get('namespace') != backend.identity or
                receipt.get('manifest') != manifest_key or manifest['catalogue'] != key or
                any(receipt.get(k) != v for k, v in manifest.items())):
            raise ArchiveError('completed cache identity mismatch')
        verified_stream(backend, manifest_key, digest(raw), len(raw))
        path = job / 'catalogue.sqlite3.gz'
        if path.is_symlink():
            raise ArchiveError('symlink cache')
        before = None
        if path.exists():
            regular(path, work)
            before = fingerprint(path)
            if before[2] != size or file_hash(path) != manifest['catalogue_sha256']:
                raise ArchiveError('local cache mismatch')
            if fingerprint(path) != before:
                raise ArchiveError('cache changed during verification')
        result = dict(job=name, action=action, path=str(path), manifest=manifest_key,
                      catalogue=key, sha256=manifest['catalogue_sha256'], bytes=size,
                      namespace=backend.identity, released_bytes=0,
                      at=datetime.now(timezone.utc).isoformat())
        audit = job / 'catalogue-cache.json'
        if audit.is_symlink():
            raise ArchiveError('symlink cache audit')
        if action == 'restore' and before is None:
            free_guard(work, min_free, size)
            with tempfile.NamedTemporaryFile(dir=job, prefix='.catalogue-') as temp:
                verified_stream(backend, key, result['sha256'], size, temp)
                temp.flush()
                os.fsync(temp.fileno())
                # Atomic no-clobber publication, even against a noncooperating writer.
                os.link(temp.name, path)
                sync_directory(job)
            result['status'] = 'RESTORED'
            atomic(audit, canonical(result))
            return result
        verified_stream(backend, key, result['sha256'], size)
        result['status'] = 'VERIFIED_PRESENT' if before else 'VERIFIED_ABSENT'
        if action == 'evict' and before is not None:
            atomic(audit, canonical(dict(result, status='EVICT_INTENT')))
            regular(path, work)
            if fingerprint(path) != before:
                raise ArchiveError('cache changed before eviction')
            path.unlink()
            sync_directory(job)
            result.update(status='EVICTED', released_bytes=size)
            atomic(audit, canonical(result))
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--job', required=True)
    parser.add_argument('--action', choices=['inspect', 'evict', 'restore'], default='inspect')
    args = parser.parse_args()
    try:
        backend = CosBackend(json.loads(args.config.read_bytes()), args.credentials)
        print(json.dumps(catalogue_cache(backend, args.workspace, args.job, action=args.action)))
    except Exception as exc:
        # SDK exceptions may contain signed request URLs; expose only the type.
        print(json.dumps({'status': 'FAILED', 'error_type': type(exc).__name__}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
