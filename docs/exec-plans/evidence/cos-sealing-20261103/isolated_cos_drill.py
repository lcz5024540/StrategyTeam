"""Synthetic isolated COS verification; never uses production evidence/ledger sources."""
import json
from pathlib import Path
import sys
import uuid
from htx_trend.archive.backend import CosBackend
from htx_trend.research.store import FileEvidenceStore,read_object
from htx_trend.realtime.storage import SegmentRecorder
from htx_trend.sealing.control import initialize
from htx_trend.sealing.lifecycle import archive_sealed,preview_release,apply_release,restore_local,rollback_layout

base=Path(sys.argv[1])/('drill-'+uuid.uuid4().hex[:12]);base.mkdir(parents=True)
backend=CosBackend({'bucket':'rayquant-strategy-1253235498','region':'na-siliconvalley','prefix':'coinstrategy/archive/v1/'},Path('/home/ubuntu/coinstrategy-cos-pilot/credentials.json'))
report={'synthetic':True,'production_source_deleted':False,'root':str(base),'cases':{}}
try:
    for kind in ('cas','capture'):
        root=base/kind;work=base/(kind+'-work');contract='paper' if kind=='cas' else 'capture-public'
        initialize(root,kind=kind,contract=contract,max_bytes=1)
        if kind=='cas':
            writer=FileEvidenceStore(root,compress=True,segmented=True,segment_contract=contract)
            first=writer.put('synthetic_raw',{'label':'sealing-drill','v':1})
            second=writer.put('synthetic_decision',{'v':2},refs=(first,))
            c=writer.segments;c.ready('paper',[]);c.seal(force=True)
        else:
            writer=SegmentRecorder(root,segmented=True,segment_bytes=1)
            writer.append({'kind':'DEPTH','received_ms':1,'synthetic':True});writer.segment_boundary()
            writer.append({'kind':'DEPTH','received_ms':2,'synthetic':True});writer.segment_boundary()
            c=writer.segments
        uploaded=archive_sealed(backend,root,work,min_free=0)
        preview=preview_release(backend,root,1,base/(kind+'-plan.sqlite3'),work=work,min_free=0)
        released=apply_release(backend,preview['plan'],preview['approval_sha256'],work=work,min_free=0)
        recovered=restore_local(backend,root,work=work,min_free=0)
        rollback=rollback_layout(root,writer_stopped=True)
        if kind=='cas':assert writer.get(second)['refs']==[first]
        else:assert len(writer.verify())==2
        report['cases'][kind]={'upload':uploaded,'preview':preview,'release':released,'restore':recovered,'rollback':rollback}
        writer.close() if kind=='cas' else c.close()
    report['status']='PASS'
except Exception as exc:
    report['status']='FAIL';report['error_type']=type(exc).__name__
print(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(0 if report['status']=='PASS' else 1)
