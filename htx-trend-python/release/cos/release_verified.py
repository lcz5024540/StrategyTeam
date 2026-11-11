"""Root-bound, explicitly authorized maintenance; never deletes COS objects.

Uses the deployed sealing API. Durable plans are reused across interruption;
network, hashes, reachability and writer leases remain enforced by that API.
"""
import argparse
import json
from pathlib import Path
import sqlite3
import time
import uuid

from htx_trend.archive.backend import CosBackend
from htx_trend.archive.transfer import atomic, exclusive
from htx_trend.sealing.control import Control, SealingError, digest_file, encoded
from htx_trend.sealing.lifecycle import preview_release, apply_release


def transient(exc):
    return isinstance(exc, SealingError) and any(s in str(exc) for s in (
        'source is in use', 'handoff incomplete'))


def outstanding(control, descriptor, namespace, epoch):
    """A fresh plan is allowed only after all eligible work is reconciled."""
    path = Path(descriptor['plan'])
    if digest_file(path) != descriptor['approval_sha256']:
        raise ValueError('saved release plan hash mismatch')
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True) as db:
        meta = {k: json.loads(v) for k, v in db.execute('SELECT name,value FROM meta')}
        generation = control.db.execute('SELECT manifest FROM generations WHERE id=?', (epoch,)).fetchone()
        if (meta.get('schema') != 'htx-segment-release-v1' or
                meta.get('root') != str(control.root) or meta.get('namespace') != namespace or
                meta.get('epoch') != epoch or meta.get('config') != control.config or
                not generation or meta.get('manifest') != generation[0]):
            raise ValueError('saved release plan is outside authorized root/generation')
        for (member,) in db.execute('SELECT path FROM files WHERE blocked IS NULL'):
            row = control.db.execute('SELECT local FROM objects WHERE path=?', (member,)).fetchone()
            pending = control.db.execute(
                "SELECT 1 FROM deletes WHERE plan=? AND path=? AND state='INTENT'",
                (descriptor['approval_sha256'], member)).fetchone()
            if row is None or row[0] or pending:
                return True
    return False


def blocked_without_new_plan(control, epoch):
    """An unchanged pinned tail needs no new immutable approval database."""
    with control.lease(), control.guard:
        _, pinned = control.pin_snapshot()  # Includes handoff/failure fences.
        local = {r[0] for r in control.db.execute(
            'SELECT o.key FROM objects o JOIN members m ON o.path=m.path '
            'WHERE m.generation=? AND o.local=1', (epoch,))}
        if local - pinned:
            return None  # New preview must revalidate bytes, pins and COS.
        return 'PINNED' if local else 'NO_LOCAL'


def tick(backend, policy, work, *, budget_seconds=180, max_generations=12, retries=3,
         min_free=5 * 1024**3):
    if policy.get('enabled') is not True:
        return {'status': 'DISABLED', 'released_bytes': 0}
    root = Path(policy['root']).absolute()
    work = Path(work).absolute()
    if (root.resolve() != root or work.resolve() != work or
            work.is_relative_to(root) or root.is_relative_to(work) or
            policy.get('namespace') != backend.identity or not policy.get('authorization')):
        raise ValueError('release authorization root/namespace mismatch')
    if not 1 <= max_generations <= 32 or not 1 <= retries <= 5 or not 1 <= budget_seconds <= 600:
        raise ValueError('invalid maintenance bounds')
    work.mkdir(parents=True, exist_ok=True)
    state_dir = work / 'release-control'
    state_dir.mkdir(exist_ok=True)
    with exclusive(state_dir):
        control = Control(root)
        try:
            if (control.config['kind'], control.config['contract']) != ('capture', 'capture-public'):
                raise ValueError('release policy only supports public capture')
            binding = {'root': str(root), 'namespace': backend.identity,
                       'authorization': policy['authorization']}
            binding_path = state_dir / 'binding.json'
            if binding_path.exists() and json.loads(binding_path.read_bytes()) != binding:
                raise ValueError('maintenance workspace belongs to another authorization')
            if not binding_path.exists():
                atomic(binding_path, encoded(binding))
            cursor_path = state_dir / 'cursor.json'
            cursor = json.loads(cursor_path.read_bytes()) if cursor_path.exists() else 0
            epochs = [r[0] for r in control.db.execute(
                "SELECT id FROM generations WHERE state='COS_VERIFIED' ORDER BY id")]
            epochs = [e for e in epochs if e > cursor] + [e for e in epochs if e <= cursor]
            deadline = time.monotonic() + budget_seconds
            results = []
            for epoch in epochs[:max_generations]:
                if time.monotonic() >= deadline:
                    break
                atomic(cursor_path, encoded(epoch))  # avoid starvation by pinned old generations
                descriptor_path = state_dir / f'epoch-{epoch}.json'
                descriptor = json.loads(descriptor_path.read_bytes()) if descriptor_path.exists() else None
                before = control.db.execute(
                    'SELECT coalesce(sum(o.size),0) FROM objects o JOIN members m ON o.path=m.path '
                    'WHERE m.generation=? AND o.local=1', (epoch,)).fetchone()[0]
                result = {'epoch': epoch, 'status': 'DEFERRED'}
                for attempt in range(retries):
                    if time.monotonic() >= deadline:
                        break
                    try:
                        if descriptor and not outstanding(control, descriptor, backend.identity, epoch):
                            blocked = blocked_without_new_plan(control, epoch)
                            if blocked:
                                result = {'epoch': epoch, 'status': blocked}
                                break
                            descriptor = None
                        if descriptor is None:
                            plan = state_dir / f'epoch-{epoch}-{uuid.uuid4().hex}.sqlite3'
                            descriptor = preview_release(backend, root, epoch, plan, work=work, min_free=min_free)
                            # Publish the exact SHA before the first possible unlink.
                            atomic(descriptor_path, encoded(descriptor))
                        outstanding(control, descriptor, backend.identity, epoch)
                        if descriptor['eligible_bytes'] == 0:
                            result = {'epoch': epoch, 'status': 'PINNED'}
                            break
                        result = apply_release(backend, descriptor['plan'], descriptor['approval_sha256'],
                                               work=work, min_free=min_free, max_files=2000)
                        if result['status'] == 'RETIRED':
                            break
                    except Exception as exc:
                        if not transient(exc):
                            raise  # no new plan or forced handoff on ambiguous failures
                        result = {'epoch': epoch, 'status': 'DEFERRED', 'reason': str(exc)}
                    if attempt + 1 < retries:
                        time.sleep(0.2)
                after = control.db.execute(
                    'SELECT coalesce(sum(o.size),0) FROM objects o JOIN members m ON o.path=m.path '
                    'WHERE m.generation=? AND o.local=1', (epoch,)).fetchone()[0]
                # Exceptions may follow a committed partial batch; account from durable state.
                result['released_bytes_this_tick'] = max(0, before - after)
                if descriptor:
                    result['plan'] = descriptor['plan']
                    result['approval_sha256'] = descriptor['approval_sha256']
                results.append(result)
            result = {'status': 'COMPLETE' if all(r['status'] == 'RETIRED' for r in results) else 'DEFERRED',
                      'released_bytes': sum(r['released_bytes_this_tick'] for r in results),
                      'results': results, 'cos_deleted': False, 'authorization': policy['authorization'],
                      'observed_ms': int(time.time() * 1000)}
            atomic(state_dir / 'latest.json', encoded(result))
            return result
        finally:
            control.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, required=True)
    p.add_argument('--credentials', type=Path, required=True)
    p.add_argument('--policy', type=Path, required=True)
    p.add_argument('--workspace', type=Path, required=True)
    args = p.parse_args()
    try:
        policy = json.loads(args.policy.read_bytes())
        backend = CosBackend(json.loads(args.config.read_bytes()), args.credentials)
        print(json.dumps(tick(backend, policy, args.workspace)), flush=True)
        return 0
    except Exception as exc:
        # Never echo SDK exceptions containing signed URLs or credential material.
        print(json.dumps({'status': 'FAILED', 'error_type': type(exc).__name__}), flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
