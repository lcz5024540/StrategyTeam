"""Small real-COS integration drill; only isolated generated source files retire."""
import argparse
import gzip
import json
from pathlib import Path
import tempfile
import time

from htx_trend.archive.backend import CosBackend, digest
from htx_trend.archive.cache import ReplayCache
from htx_trend.archive.retirement import preview, apply
from htx_trend.archive.scheduler import Scheduler
from htx_trend.archive.transfer import canonical, open_catalogue, restore
from htx_trend.realtime.public_capture import read_reference
from htx_trend.realtime.segment_codec import segment_events
from htx_trend.research.store import FileEvidenceStore, read_object, digest as semantic_digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--scratch', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.scratch.mkdir(parents=True, exist_ok=True)
    config = json.loads(args.config.read_bytes())
    backend = CosBackend(config, args.credentials)
    report = {'production_source_deleted': False, 'trading_services_changed': False}
    with tempfile.TemporaryDirectory(prefix='cos-integration-', dir=args.scratch) as directory:
        base = Path(directory)
        source = base / 'generated-source'
        with FileEvidenceStore(source) as store:
            root = store.put('integration_public_sample', {'synthetic': True, 'close': '1.25'})
        cfg = {'sources': {'integration': {'kind': 'cas', 'root': str(source)}},
               'schedule': {'enabled': True, 'interval_seconds': 900, 'audit_seconds': 604800}}
        scheduler = Scheduler(backend, base / 'work', cfg)
        now = time.time()
        first = scheduler.tick(now)
        assert first['status'] == 'OK', first
        second = scheduler.tick(now + 900)
        assert second['sources']['integration']['status'] == 'UNCHANGED', second
        with FileEvidenceStore(source) as store:
            revised = store.put('integration_revision', {'synthetic': True, 'close': '1.26'}, refs=(root,))
        third = scheduler.tick(now + 1800)
        assert third['status'] == 'OK', third
        manifest = third['sources']['integration']['manifest']
        with open_catalogue(backend, manifest, base / 'scratch') as (m, _):
            assert m['schema'] == 'htx-cos-archive-v2' and m['chain_depth'] == 1
            report['delta_catalogue_bytes'] = m['catalogue_bytes']
        cache = ReplayCache(backend, base / 'cache', max_bytes=64*1024**2)
        with cache.lease(manifest, cas=[revised]) as data:
            assert read_object(data, root)['payload']['close'] == '1.25'
        original_stream = backend.stream
        def offline(*args, **kwargs):
            raise OSError('injected COS outage')
        backend.stream = offline
        with cache.lease(manifest, cas=[revised]) as data:
            assert read_object(data, revised)['refs'] == [root]
        failed = scheduler.tick(now + 2700)
        assert failed['status'] == 'DEGRADED'
        assert scheduler.tick(now + 2701)['status'] == 'DEGRADED'
        backend.stream = original_stream
        recovered = scheduler.tick(now + 2760)
        assert recovered['status'] == 'OK', recovered
        # Scope is only a generated, closed test source; no configured production root.
        policy = {'state': 'FROZEN', 'no_local_readers': True, 'attestation': 'isolated integration source, writer closed'}
        plan = preview(backend, manifest, cfg['sources']['integration'], policy,
                       base / 'retirement.sqlite3', scratch=base / 'scratch')
        retired = apply(backend, plan['plan'], plan['approval_sha256'], policy, scratch=base / 'scratch')
        assert retired['retired_files'] == 2
        restore(backend, manifest, base / 'restored', scratch=base / 'scratch', all_files=True)
        assert read_object(base / 'restored', revised)['refs'] == [root]
        # Exercise a REAL historical grouped capture through the existing replay reader.
        historical = 'manifests/3deec6a61a768448ea6bd3a7edc4fcc877ea5d58b48babd0d1654828a6448192.json'
        with open_catalogue(backend, historical, base / 'scratch') as (_, db):
            path = db.execute("SELECT path FROM files WHERE path LIKE 'segments/%.json.gz' ORDER BY path LIMIT 1").fetchone()[0]
        with cache.lease(historical, paths=[path]) as data:
            segment = json.loads(gzip.decompress((data / path).read_bytes()))
            events = segment_events(segment)
            reference = {'segment': Path(path).name.removesuffix('.json.gz'), 'index': 0, 'event_hash': semantic_digest(events[0])}
            assert read_reference(data, reference) == events[0]
        report.update(status='PASS', first=first, unchanged=second, incremental=third,
                      outage=failed, recovery=recovered, isolated_retirement=retired,
                      real_capture_manifest=historical, real_capture_path=path,
                      real_capture_schema=segment['schema'], real_capture_events=len(events), offline_cache_hit=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
