"""Independent bounded archive scheduling. Never imports a trading worker or ledger."""
import json
from pathlib import Path
import shutil
import time

from .backend import ArchiveError, SnapshotChanged, digest
from .transfer import archive, atomic, canonical, exclusive, compact_staging


class Scheduler:
    def __init__(self, backend, work, config, *, min_free=5 * 1024**3):
        self.backend, self.work, self.config = backend, Path(work).absolute(), config
        self.min_free = min_free
        self.control = self.work / 'scheduler'
        self.control.mkdir(parents=True, exist_ok=True)

    def tick(self, now=None):
        now = time.time() if now is None else now
        schedule = self.config.get('schedule', {})
        if schedule.get('enabled') is not True:
            return {'status': 'DISABLED', 'sources': {}}
        interval = int(schedule.get('interval_seconds', 21600))
        audit = int(schedule.get('audit_seconds', 604800))
        if interval < 900 or audit < interval:
            raise ArchiveError('schedule requires interval >=900s and audit >= interval')
        results = {}
        with exclusive(self.control):
            state_path = self.control / 'state.json'
            state = json.loads(state_path.read_bytes()) if state_path.exists() else {}
            identity = digest(canonical({'namespace': self.backend.identity, 'sources': self.config['sources']}))
            if state.get('identity', identity) != identity:
                raise ArchiveError('scheduler source/namespace changed; use a new workspace')
            state['identity'] = identity
            sources = state.setdefault('sources', {})
            for name, spec in self.config['sources'].items():
                entry = sources.setdefault(name, {'generation': 0, 'next_run': 0})
                if entry.get('blocked') or now < entry['next_run']:
                    results[name] = {'status': 'BLOCKED' if entry.get('blocked') else 'WAITING',
                        'next_run': entry['next_run'], 'failures': entry.get('failures', 0),
                        'error_type': entry.get('error_type'), 'first_failure': entry.get('first_failure'),
                        'cleanup_error': entry.get('cleanup_error')}
                    continue
                if not entry.get('pending'):
                    entry['generation'] += 1
                    entry['pending'] = 'scheduled-' + digest(name.encode())[:16] + '-' + str(entry['generation'])
                    entry['full_audit'] = (not entry.get('manifest') or now - entry.get('last_audit', 0) >= audit
                                           or entry.get('chain_depth', 0) >= 32)
                    atomic(state_path, canonical(state))
                try:
                    receipt = archive(self.backend, self.work, entry['pending'], spec,
                        min_free=self.min_free,
                        previous=None if entry['full_audit'] else entry.get('manifest'))
                    # Compact only tool-generated inventories. A no-change round has no new catalogue.
                    if not receipt.get('unchanged'):
                        compact_staging(self.backend, self.work, entry['pending'])
                    finished = entry['pending']
                    if entry['full_audit']:
                        entry['last_audit'] = now
                    recovered = entry.get('failures', 0)
                    entry.update(manifest=receipt['manifest'], chain_depth=receipt.get('chain_depth', 0), pending=None, last_job=finished,
                        last_success=now, next_run=now + interval, failures=0,
                        first_failure=None, error_type=None)
                    atomic(state_path, canonical(state))
                    # Only newest generated job per source stays locally. Older receipts and
                    # catalogues remain on COS; source files and blobs.sqlite3 are untouched.
                    cleanup_error = None
                    try:
                        self._release_job(finished, keep_receipt=True)
                        group = finished.rsplit('-', 1)[0] + '-'
                        for candidate in self.work.glob(group + '*'):
                            if candidate.name != finished and (candidate / 'receipt.json').exists():
                                self._release_job(candidate.name)
                    except Exception as exc:
                        cleanup_error = type(exc).__name__
                    entry['cleanup_error'] = cleanup_error
                    results[name] = {'status': 'UNCHANGED' if receipt.get('unchanged') else 'VERIFIED',
                        'manifest': receipt['manifest'], 'recovered_failures': recovered, 'cleanup_error': cleanup_error}
                except Exception as exc:
                    entry['failures'] = entry.get('failures', 0) + 1
                    entry['first_failure'] = entry.get('first_failure') or now
                    entry['next_run'] = now + min(interval, 60 * 2 ** min(entry['failures'] - 1, 10))
                    entry['blocked'] = isinstance(exc, SnapshotChanged)
                    entry['error_type'] = type(exc).__name__  # SDK messages may contain signed URLs.
                    atomic(state_path, canonical(state))
                    results[name] = {'status': 'FAILED', 'error_type': entry['error_type'],
                        'failures': entry['failures'], 'first_failure': entry['first_failure'],
                        'next_run': entry['next_run']}
            atomic(state_path, canonical(state))
        return {'status': 'DEGRADED' if any(r['status'] in {'FAILED', 'BLOCKED'} or r.get('failures', 0) or r.get('cleanup_error') for r in results.values()) else 'OK',
                'sources': results}

    def _release_job(self, name, keep_receipt=False):
        # Never accept arbitrary cleanup paths or delete failed job evidence.
        import re
        if not re.fullmatch(r'scheduled-[a-f0-9]{16}-[0-9]+', name):
            raise ArchiveError('not a scheduler-owned job')
        target = self.work / name
        with exclusive(self.work):
            if target.is_symlink() or target.resolve().parent != self.work.resolve():
                raise ArchiveError('unsafe scheduler staging path')
            receipt = json.loads((target / 'receipt.json').read_bytes())
            if receipt['job'] != name or receipt['namespace'] != self.backend.identity:
                raise ArchiveError('staging receipt identity mismatch')
            allowed = {'receipt.json', 'manifest.json', 'catalogue.sqlite3.gz', 'snapshot.sqlite3',
                       'delta.sqlite3', 'index.sqlite3', 'segments.jsonl', 'capture.json', 'status.json', 'pool-provenance.json'}
            if any(p.name not in allowed or p.is_symlink() or not p.is_file() for p in target.iterdir()):
                raise ArchiveError('unknown scheduler staging file; preserve')
            if keep_receipt:
                for p in target.iterdir():
                    if p.name != 'receipt.json':
                        p.unlink()
            else:
                shutil.rmtree(target)


    def reset_pending(self, source, reason):
        """Explicit supersede of a bad snapshot, retaining failed job evidence."""
        if not reason.strip():
            raise ArchiveError('reset requires an investigation reason')
        with exclusive(self.control):
            path = self.control / 'state.json'
            state = json.loads(path.read_bytes())
            entry = state['sources'][source]
            if not entry.get('pending'):
                raise ArchiveError('source has no pending job')
            atomic(self.control / (entry['pending'] + '.superseded.json'),
                   canonical({'source': source, 'job': entry['pending'], 'reason': reason, 'time': time.time()}))
            entry.update(pending=None, blocked=False, next_run=0)
            atomic(path, canonical(state))
            return {'status': 'RESET', 'failed_inventory_retained': True, 'source': source}
