"""Upload sealed production public data and verify downloaded observations; never delete source."""
from pathlib import Path
import json,gzip,tempfile
from htx_trend.archive.backend import CosBackend
from htx_trend.archive.transfer import open_catalogue,restore
from htx_trend.sealing.lifecycle import archive_sealed
from htx_trend.sealing.control import Control
from htx_trend.realtime.segment_codec import segment_events
from htx_trend.realtime.public_capture import read_reference
from htx_trend.research.store import digest
config=json.loads(Path('/etc/coinstrategy/15m-cos-archive.json').read_bytes())
backend=CosBackend(config,Path('/home/ubuntu/coinstrategy-cos-pilot/credentials.json'))
root=Path('/home/ubuntu/coinstrategy-data/15m-cos/capture/segments')
work=Path('/home/ubuntu/coinstrategy-cos-archive/15m-work')
report={'source_deleted':False,'scope':'PUBLIC_MARKET_DATA_ONLY'}
try:
 report['upload']=archive_sealed(backend,root,work)
 control=Control(root)
 try:
  row=control.db.execute("SELECT id,manifest FROM generations WHERE state='COS_VERIFIED' ORDER BY id DESC LIMIT 1").fetchone()
  assert row,'no verified generation'
  epoch,manifest=row
  report['epoch']=epoch;report['manifest']=manifest
  selected={}
  with control.lease(),open_catalogue(backend,manifest,work,5*1024**3) as (_,db):
   for (path,) in db.execute("SELECT path FROM files WHERE path LIKE 'segments/%.json.gz' ORDER BY path"):
    data=json.loads(gzip.decompress((root.parent/path).read_bytes()))
    for index,event in enumerate(segment_events(data)):
     kind=event['kind']
     if kind in {'FRAME','DEPTH','BBO','TRADE','COLLECTION_SESSION'} and kind not in selected:
      selected[kind]={'path':path,'ref':{'segment':Path(path).name.removesuffix('.json.gz'),'index':index,'event_hash':digest(event)}}
    if len(selected)==5:break
  assert {'FRAME','DEPTH','BBO','TRADE'}<=selected.keys(), 'archived generation lacks full market types'
 finally:control.close()
 with tempfile.TemporaryDirectory(prefix='acceptance-download-',dir=work) as temp:
  report['download']=restore(backend,manifest,temp,scratch=work,paths=sorted({v['path'] for v in selected.values()}))
  observations={}
  for kind,value in selected.items():
   event=read_reference(temp,value['ref'])
   item={'source_ref':value['ref'],'contract':event.get('contract'),'received_ms':event.get('received_ms'),'exchange_ms':event.get('exchange_ms')}
   if kind=='FRAME':item['windows']={p:{'bars':len(w['candles']),'first':w['candles'][0]['open_time'],'last':w['candles'][-1]['open_time']} for p,w in event['windows'].items()}
   if kind=='COLLECTION_SESSION':item['release']=event.get('release')
   observations[kind]=item
  report['observations']=observations
 report['status']='PASS'
except Exception as exc:
 report['status']='FAIL';report['error_type']=type(exc).__name__
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['status']=='PASS' else 1)
