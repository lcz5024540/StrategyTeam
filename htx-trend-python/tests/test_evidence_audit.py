import hashlib
import json
from pathlib import Path

import pytest
from htx_trend.research.evidence_audit import inventory
from htx_trend.research.store import FileEvidenceStore


def snapshot(root):
    return {p.relative_to(root).as_posix():(hashlib.sha256(p.read_bytes()).hexdigest(),p.stat().st_mtime_ns)
            for p in root.rglob('*') if p.is_file() and not p.is_symlink()}


def test_audit_does_not_open_database_or_mutate_and_protects_unknown(tmp_path):
    with FileEvidenceStore(tmp_path) as store:
        raw=store.put('raw_kline',{'response':'x'*10000})
        root=store.put('dataset_manifest',{'pages':[raw]},refs=(raw,))
        unknown=store.put('unrecognized',{'value':1})
    before=snapshot(tmp_path)
    result=inventory(tmp_path,max_objects=0)
    assert snapshot(tmp_path)==before
    assert result['parsed_frozen_roots']==[root]
    assert result['parsed_reachable_objects']==2
    assert result['parsed_unattributed_objects']==1
    assert result['all_unattributed_objects_protected']
    assert result['approved_reclaimable_bytes']==0
    assert result['estimated_lossless_compression_saving_bytes']>0
    assert all(not x['deletion_eligible'] for x in result['migration_preview'])
    assert any(x['hash']==unknown for x in result['migration_preview'])


def test_bounded_scan_never_claims_full_coverage(tmp_path):
    with FileEvidenceStore(tmp_path,compress=True) as store:
        for i in range(3):store.put('x',{'i':i})
    result=inventory(tmp_path,max_objects=1)
    assert result['totals']['object_files']==3
    assert not result['complete_object_scan']
    assert result['approved_reclaimable_bytes']==0


def test_missing_reference_corruption_and_symlink_are_not_deletable(tmp_path):
    with FileEvidenceStore(tmp_path) as store:
        raw=store.put('raw',{})
        root=store.put('dataset_manifest',{},refs=(raw,))
        store._path(raw).unlink()
        broken=store.put('x',{'bad':1})
        store._path(broken).write_text('bad')
    (tmp_path/'objects'/'linked').symlink_to(tmp_path)
    result=inventory(tmp_path,max_objects=0)
    assert raw in result['missing_references']
    assert result['errors'] and result['symlinks_skipped']==1
    assert not result['complete_object_scan']
    assert result['approved_reclaimable_bytes']==0
