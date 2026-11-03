"""Verify receipts and restore real replay inputs without changing source directories.

Run in the isolated archive environment. No live API, account or database connection.
"""
import argparse
from datetime import datetime, timezone
import gzip
import json
from pathlib import Path
import sqlite3
import tempfile

from htx_trend.archive.backend import CosBackend, file_hash
from htx_trend.archive.transfer import open_catalogue, restore
from htx_trend.research.store import read_object, digest
from htx_trend.realtime.public_capture import read_reference
from htx_trend.realtime.segment_codec import segment_events


def verify(backend, work, job, destination):
    receipt = json.loads((job / 'receipt.json').read_bytes())
    kind, root = receipt['source']['kind'], Path(receipt['source']['root'])
    with open_catalogue(backend, receipt['manifest'], work) as (_, db):
        files = db.execute('SELECT count(*) FROM files').fetchone()[0]
        assert files == receipt['files']
        if kind == 'cas':
            roots = []
            with sqlite3.connect((job / 'index.sqlite3').as_uri() + '?mode=ro', uri=True) as source_index:
                for candidates in (
                    ('compact_exit_decision', 'exit_decision_trace', 'decision_trace'),
                    ('compact_candle', 'raw_kline', 'runtime_kline_window'),
                ):
                    for source_kind in candidates:
                        row = source_index.execute('SELECT hash FROM evidence WHERE kind=? ORDER BY recorded_at DESC LIMIT 1', (source_kind,)).fetchone()
                        if row:
                            roots.append(row[0])
                            break
            if not roots:
                raise ValueError('no real CAS verification roots')
            selected = dict(cas=roots)
        elif kind == 'capture':
            rows = db.execute('SELECT path FROM files WHERE semantic IS NOT NULL ORDER BY path').fetchall()
            paths = [rows[i]['path'] for i in sorted({0, len(rows)//2, len(rows)-1})]
            selected = dict(paths=paths + ['capture.json', 'segments/segments.jsonl'])
        else:
            rows = db.execute('SELECT path FROM files ORDER BY path').fetchall()
            selected = dict(paths=[rows[i]['path'] for i in sorted({0,len(rows)//2,len(rows)-1})])
    result = restore(backend, receipt['manifest'], destination, scratch=work, **selected)
    checked = 0
    for path in destination.rglob('*'):
        if not path.is_file() or path.name.startswith('.cos-restore-complete-'):
            continue
        relative = path.relative_to(destination)
        # Capture indices/config and CAS indices are frozen in the job, not the live source.
        frozen = job / relative.name
        expected = frozen if relative.as_posix() in ('segments/segments.jsonl','capture.json','status.json','index.sqlite3','pool-provenance.json') and frozen.exists() else root / relative
        if file_hash(path) != file_hash(expected):
            raise ValueError('restored bytes differ from frozen source')
        checked += 1
    if kind == 'cas':
        seen, todo = set(), list(roots)
        while todo:
            key = todo.pop()
            if key in seen:
                continue
            value = read_object(destination, key)
            seen.add(key)
            todo.extend(value['refs'])
        result['cas_roots'] = roots
        result['cas_reader_objects'] = len(seen)
        # A temporary separate location prevents a full index from advertising absent CAS files.
        with tempfile.TemporaryDirectory(prefix='index-verify-', dir=work) as temp:
            restore(backend, receipt['manifest'], temp, scratch=work, paths=['index.sqlite3'])
            with sqlite3.connect(Path(temp).joinpath('index.sqlite3').as_uri()+'?mode=ro', uri=True) as index:
                result['sqlite_quick_check'] = index.execute('PRAGMA quick_check').fetchone()[0]
                result['indexed_objects'] = index.execute('SELECT count(*) FROM evidence').fetchone()[0]
                assert result['sqlite_quick_check'] == 'ok'
    elif kind == 'capture':
        events_checked, schemas = 0, set()
        for path in destination.joinpath('segments').glob('*.json.gz'):
            value = json.loads(gzip.decompress(path.read_bytes()))
            events = segment_events(value)
            schemas.add(value['schema'])
            for i in sorted({0, len(events)//2, len(events)-1}):
                event = events[i]
                assert read_reference(destination, {'segment': path.name.removesuffix('.json.gz'),
                    'index': i, 'event_hash': digest(event)}) == event
                events_checked += 1
        result['capture_reader_observations'] = events_checked
        result['schemas'] = sorted(schemas)
        listed = {json.loads(x)['hash'] for x in (job/'segments.jsonl').read_text().splitlines()}
        sealed = {x.name.removesuffix('.json.gz') for x in (root/'segments').glob('*.json.gz')}
        with sqlite3.connect(job/'snapshot.sqlite3') as db:
            archived = {x[0] for x in db.execute('SELECT semantic FROM files WHERE semantic IS NOT NULL')}
        cutoff = datetime.fromisoformat(receipt['snapshot']['snapshot_finished']).timestamp()
        result['unarchived_sealed_before_inventory_end'] = sum(1 for key in sealed-archived if (root/'segments'/(key+'.json.gz')).stat().st_mtime <= cutoff)
        result['currently_sealed_not_in_snapshot'] = len(sealed-archived)
        result['snapshot_unindexed_segments'] = len(archived-listed)
        result['current_index_scan_is_not_atomic'] = True
    result['source_sha_equal_files'] = checked
    return dict(result, job=job.name, kind=kind)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, required=True)
    p.add_argument('--credentials', type=Path, required=True)
    p.add_argument('--workspace', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--jobs', nargs='+', required=True)
    args=p.parse_args()
    backend=CosBackend(json.loads(args.config.read_bytes()), args.credentials)
    result={'checked_at':datetime.now(timezone.utc).isoformat(),'jobs':[]}
    for name in args.jobs:
        row=verify(backend,args.workspace,args.workspace/name,args.output.parent/'restore-check'/name)
        result['jobs'].append(row)
        args.output.write_text(json.dumps(result,indent=2))
        print(json.dumps({'job':name,'status':'VERIFIED','files':row['source_sha_equal_files']}),flush=True)


if __name__=='__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'FAILED', 'error_type': type(error).__name__}), flush=True)
        raise SystemExit(1)
