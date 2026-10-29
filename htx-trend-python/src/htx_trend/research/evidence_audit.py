"""Read-only evidence inventory and archival preview. No apply/delete operation.

Never opens the live SQLite index: even a read connection can create WAL sidecars.
A bounded scan reports unknown objects rather than assuming they are garbage.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from datetime import UTC, datetime
from decimal import Decimal
import gzip
import json
import os
from pathlib import Path
import re

from ..market_data import Candle, completed_candles
from .store import canonical, digest, read_object

ROOT_KINDS = {'dataset_manifest', 'decision_trace', 'entry_gate_trace', 'exit_decision_trace',
              'compact_entry_decision', 'compact_exit_decision', 'compact_execution_result'}


def semantic_window(obj):
    p = obj['payload']; kind = obj['kind']
    if kind == 'runtime_kline_window':
        bars = tuple(Candle(**{k: (Decimal(str(v)) if v is not None and k not in ('open_time','trade_count') else v)
                              for k,v in row.items()}) for row in p['candles'])
    elif kind == 'runtime_raw_kline':
        at = p.get('decision_as_of') or p.get('observed_at')
        if at is None:return None
        bars = completed_candles((Candle.from_htx(r) for r in p['response']['data']), p['period'],
                                 now_epoch=at, close_grace_seconds=10)[-max(1,p['requested_size']-2):]
    else:
        return None
    return digest([kind,p['contract_code'],p['period'],[b.content_hash for b in bars]])


def inventory(root: Path, *, max_objects=10000):
    root = Path(root)
    if root.is_symlink() or not root.is_dir() or (root/'objects').is_symlink() or not (root/'objects').is_dir():
        raise ValueError('explicit evidence directory with real objects directory required')
    if max_objects < 0:raise ValueError('negative object limit')
    totals = Counter(); kinds = defaultdict(Counter); records = {}; refs = set(); errors = []
    migrations = []; semantic = {}; candle_ids = set(); windows = set(); times = []
    duplicate_bytes = 0; compression_bytes = 0; protected_files = []; symlinks = 0; truncated = False
    for folder, directories, names in os.walk(root, followlinks=False):
        for directory in list(directories):
            if (Path(folder)/directory).is_symlink():
                directories.remove(directory);symlinks += 1
        for name in sorted(names):
            path = Path(folder)/name
            if path.is_symlink():symlinks += 1;continue
            stat = path.stat()
            totals.update(files=1, logical_bytes=stat.st_size, allocated_bytes=getattr(stat,'st_blocks',0)*512)
            relative = path.relative_to(root).as_posix()
            match = re.fullmatch(r'objects/([0-9a-f]{2})/([0-9a-f]{64})\.json(?:\.gz)?', relative)
            if not match or match[1] != match[2][:2]:
                if len(protected_files)<100:protected_files.append(relative)
                totals['non_object_files'] += 1
                continue
            totals['object_files'] += 1
            if max_objects and totals['parsed_object_files'] >= max_objects:
                truncated = True;continue
            totals['parsed_object_files'] += 1
            key = match[2]
            try:
                obj = read_object(root,key)
                raw = canonical(obj)
                kind = obj['kind']; p = obj['payload']
                kinds[kind].update(files=1, stored_bytes=stat.st_size, uncompressed_bytes=len(raw))
                records[key] = {'kind':kind,'refs':obj['refs']}
                refs.update(obj['refs'])
                for field in ('observed_at','at','decision_as_of'):
                    value = p.get(field) if isinstance(p,dict) else None
                    if value is not None:
                        try:
                            epoch = float(value) if isinstance(value,(int,float)) else datetime.fromisoformat(value).timestamp()
                            times.append(epoch)
                        except (ValueError,TypeError):pass
                if kind == 'compact_candle':
                    candle_ids.add((p['contract_code'],p['period'],p['candle']['open_time'],key))
                if kind == 'compact_kline_window':windows.add(key)
                fingerprint = semantic_window(obj)
                if fingerprint:
                    if fingerprint in semantic:duplicate_bytes += stat.st_size
                    else:semantic[fingerprint]=key
                    if kind == 'runtime_kline_window':
                        windows.add(fingerprint)
                        for bar in p['candles']:
                            candle_ids.add((p['contract_code'],p['period'],bar['open_time'],digest(bar)))
                estimated = len(gzip.compress(raw,mtime=0))
                saving = max(0,stat.st_size-estimated) if name.endswith('.json') else 0
                compression_bytes += saving
                migrations.append({'hash':key,'kind':kind,'source':relative,
                    'archival_path':'objects/'+key[:2]+'/'+key+'.json.gz',
                    'uncompressed_sha256':key,'semantic_equivalent_to':semantic.get(fingerprint) if fingerprint else None,
                    'estimated_lossless_compression_saving_bytes':saving,
                    'action':'PREVIEW_ONLY_KEEP_SOURCE',
                    'deletion_eligible':False,
                    'blockers':['USER_APPROVAL_REQUIRED','RESTORE_AND_OLD_PACKAGE_ROLLBACK_NOT_VERIFIED',
                                'LEGACY_PAPER_INPUT_LINKS_INCOMPLETE' if kind.startswith('runtime_') else 'RETAIN_REQUIRED_OR_UNKNOWN']})
            except Exception as error:
                errors.append({'path':relative,'reason':type(error).__name__})
    missing = [key for key in refs if not (root/'objects'/key[:2]/(key+'.json')).exists()
               and not (root/'objects'/key[:2]/(key+'.json.gz')).exists()]
    roots = {key for key,record in records.items() if record['kind'] in ROOT_KINDS}
    reachable = set(); stack=list(roots)
    while stack:
        key=stack.pop()
        if key in reachable:continue
        reachable.add(key)
        if key in records:stack.extend(records[key]['refs'])
    return {'mode':'READ_ONLY_PREVIEW','root':str(root.resolve()),'totals':dict(totals),
        'complete_object_scan':not truncated and not errors and not symlinks,
        'scan_limit':max_objects,'sample_method':'bounded directory traversal, not random',
        'kinds':{k:dict(v) for k,v in kinds.items()},'symlinks_skipped':symlinks,
        'parsed_unique_candle_versions':len(candle_ids),'parsed_unique_windows':len(windows),
        'parsed_time_range_utc':([datetime.fromtimestamp(min(times),UTC).isoformat(),datetime.fromtimestamp(max(times),UTC).isoformat()] if times else None),
        'parsed_root_count':len(roots),'parsed_frozen_roots':[k for k in roots if records[k]['kind']=='dataset_manifest'],
        'parsed_reachable_objects':len(reachable),'parsed_unattributed_objects':len(set(records)-reachable),
        'missing_references':missing,'errors':errors,'protected_non_object_examples':protected_files,
        'observed_duplicate_closed_window_bytes':duplicate_bytes,
        'estimated_lossless_compression_saving_bytes':compression_bytes,
        'saving_estimates_overlap_do_not_add':True,'approved_reclaimable_bytes':0,
        'all_unattributed_objects_protected':True,'migration_preview':migrations}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--max-objects',type=int,default=10000,help='0 scans all; default limits deep parsing to 10000 objects')
    args=parser.parse_args(argv)
    print(json.dumps(inventory(args.evidence,max_objects=args.max_objects),ensure_ascii=False,indent=2))


if __name__ == '__main__':main()
