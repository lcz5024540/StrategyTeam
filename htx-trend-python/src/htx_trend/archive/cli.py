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
    args = p.parse_args()
    try:
        config = json.loads(args.config.read_bytes())
        backend = CosBackend(config, args.credentials)
        minimum = int(args.min_free_gib * 1024**3)
        if minimum < 0:
            raise ValueError('negative minimum')
        if args.command == 'archive':
            result = archive(backend, args.workspace, args.job, config['sources'][args.source], min_free=minimum,
                progress=lambda value: print(json.dumps(value), flush=True))
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
    except Exception as exc:
        # SDK exception text may include signed URLs. Operational diagnostics remain redacted.
        print(json.dumps({'status': 'FAILED', 'error_type': type(exc).__name__,
            'detail': str(exc) if isinstance(exc, ValueError) else 'See source availability, credentials, COS permissions and workspace capacity; retry same job.'}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
