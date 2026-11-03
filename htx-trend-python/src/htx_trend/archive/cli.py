"""Standalone archive CLI; credentials never appear in plans, receipts or errors."""
import argparse
import json
from pathlib import Path
import sys

from .backend import CosBackend
from .transfer import archive, compact_staging, open_catalogue, restore


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, required=True)
    p.add_argument('--credentials', type=Path, required=True)
    p.add_argument('--workspace', type=Path, required=True)
    p.add_argument('--min-free-gib', type=float, default=5)
    sub = p.add_subparsers(dest='command', required=True)
    a = sub.add_parser('archive')
    a.add_argument('--source', required=True)
    a.add_argument('--job', required=True)
    a.add_argument('--previous', help='Previous immutable manifest for incremental reuse')
    r = sub.add_parser('restore')
    r.add_argument('--manifest', required=True)
    r.add_argument('--destination', type=Path, required=True)
    r.add_argument('--path', action='append', default=[])
    r.add_argument('--cas', action='append', default=[])
    r.add_argument('--all', action='store_true')
    compact = sub.add_parser('compact-staging')
    compact.add_argument('--job', required=True)
    ls = sub.add_parser('list')
    ls.add_argument('--manifest', required=True)
    ls.add_argument('--limit', type=int, default=20)
    sub.add_parser('run-once', help='Independent scheduled tick; disabled unless configured')
    reset = sub.add_parser('reset-pending')
    reset.add_argument('--source', required=True)
    reset.add_argument('--reason', required=True)
    c = sub.add_parser('cache-run', help='Hold a verified cache lease throughout an offline replay command')
    c.add_argument('--manifest', required=True)
    c.add_argument('--cache', type=Path, required=True)
    c.add_argument('--max-cache-gib', type=float, default=2)
    c.add_argument('--path', action='append', default=[])
    c.add_argument('--cas', action='append', default=[])
    c.add_argument('--all', action='store_true')
    c.add_argument('replay_command', nargs=argparse.REMAINDER,
                   help='-- executable args; {data} is replaced with the leased directory')
    rp = sub.add_parser('retire-preview')
    rp.add_argument('--source', required=True)
    rp.add_argument('--manifest', required=True)
    rp.add_argument('--output', type=Path, required=True)
    ra = sub.add_parser('retire-apply')
    ra.add_argument('--source', required=True)
    ra.add_argument('--plan', type=Path, required=True)
    ra.add_argument('--approval-sha256', required=True)
    args = p.parse_args()
    try:
        config = json.loads(args.config.read_bytes())
        backend = CosBackend(config, args.credentials)
        minimum = int(args.min_free_gib * 1024**3)
        if minimum < 0:
            raise ValueError('negative minimum')
        if args.command == 'archive':
            result = archive(backend, args.workspace, args.job, config['sources'][args.source], min_free=minimum,
                progress=lambda value: print(json.dumps(value), flush=True), previous=args.previous)
        elif args.command == 'run-once':
            from .scheduler import Scheduler
            result = Scheduler(backend, args.workspace, config, min_free=minimum).tick()
        elif args.command == 'reset-pending':
            from .scheduler import Scheduler
            result = Scheduler(backend, args.workspace, config, min_free=minimum).reset_pending(args.source, args.reason)
        elif args.command == 'cache-run':
            import subprocess
            from .cache import ReplayCache
            command = args.replay_command
            if command and command[0] == '--':
                command = command[1:]
            if not command:
                raise ValueError('cache-run requires an executable replay command')
            cache = ReplayCache(backend, args.cache, max_bytes=int(args.max_cache_gib * 1024**3), min_free=minimum)
            with cache.lease(args.manifest, paths=args.path, cas=args.cas, all_files=args.all) as data:
                code = subprocess.run([part.replace('{data}', str(data)) for part in command], check=False, pass_fds=(cache.lease_fd,)).returncode
            return code
        elif args.command == 'retire-preview':
            from .retirement import preview
            result = preview(backend, args.manifest, config['sources'][args.source],
                config.get('retirement', {}).get(args.source, {}), args.output,
                scratch=args.workspace, min_free=minimum)
        elif args.command == 'retire-apply':
            from .retirement import apply
            result = apply(backend, args.plan, args.approval_sha256,
                config.get('retirement', {}).get(args.source, {}), scratch=args.workspace, min_free=minimum,
                expected_spec=config['sources'][args.source])
        elif args.command == 'compact-staging':
            result = compact_staging(backend, args.workspace, args.job)
        elif args.command == 'restore':
            result = restore(backend, args.manifest, args.destination, scratch=args.workspace,
                paths=args.path, cas=args.cas, all_files=args.all, min_free=minimum)
        else:
            with open_catalogue(backend, args.manifest, args.workspace, minimum) as (manifest, db):
                result = {'manifest': manifest, 'sample': [dict(row) for row in db.execute(
                    'SELECT path,semantic,raw_size,pack,offset,length FROM files ORDER BY path LIMIT ?', (args.limit,))]}
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if result.get('status') == 'DEGRADED':
            return 1
    except Exception as exc:
        # SDK exception text may include signed URLs. Operational diagnostics remain redacted.
        print(json.dumps({'status': 'FAILED', 'error_type': type(exc).__name__,
            'detail': str(exc) if isinstance(exc, ValueError) else 'See source availability, credentials, COS permissions and workspace capacity; retry same job.'}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
