"""Download/hash verification only. No source writes or COS PUT/delete."""
from collections import Counter
import gzip
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
from htx_trend.archive.backend import CosBackend
from htx_trend.archive.transfer import open_catalogue, restore

class ReadOnlyCos(CosBackend):
    def publish(self, *args, **kwargs):
        raise RuntimeError('verification may not publish')

stage = Path('/home/ubuntu/coinstrategy-cos-archive/deploy-20261105-4fa6ccee')
source = Path('/home/ubuntu/coinstrategy-data/15m-cos/capture/segments')
minimum_epoch = int(sys.argv[1])
try:
    with sqlite3.connect((source/'.sealing/control.sqlite3').as_uri()+'?mode=ro',uri=True) as db:
        row = db.execute("SELECT id,state,created,manifest FROM generations WHERE state IN ('COS_VERIFIED','RETIRED') AND manifest IS NOT NULL AND id>=? ORDER BY id DESC LIMIT 1",(minimum_epoch,)).fetchone()
    if row is None:
        print(json.dumps({'status':'WAITING_FOR_POST_DEPLOY_GENERATION','minimum_epoch':minimum_epoch}))
        sys.exit(3)
    epoch,state,created,manifest_key = row
    backend = ReadOnlyCos(json.loads(Path('/etc/coinstrategy/15m-cos-archive.json').read_bytes()),
        Path('/home/ubuntu/coinstrategy-cos-pilot/credentials.json'))
    with tempfile.TemporaryDirectory(prefix='cos-verify-',dir=stage) as scratch:
        scratch = Path(scratch)
        verified = restore(backend,manifest_key,scratch/'verify',scratch=scratch,
            all_files=True,verify_only=True,min_free=5*1024**3)
        with open_catalogue(backend,manifest_key,scratch,5*1024**3) as (manifest,catalogue):
            member = catalogue.execute("SELECT path,raw_hash,raw_size FROM files WHERE path LIKE '%.json.gz' ORDER BY path LIMIT 1").fetchone()
            assert manifest['source']['root']==str(source) and manifest['source']['epoch']==epoch
            assert member is not None, 'no public gzip segment'
            path,raw_hash,raw_size = member
        restore(backend,manifest_key,scratch/'sample',scratch=scratch,paths=[path],min_free=5*1024**3)
        with gzip.open(scratch/'sample'/path,'rt') as stream:
            body = json.load(stream)
        events = body['events']
        assert events, 'empty downloaded observation segment'
        clocks = [e['received_ms'] for e in events if 'received_ms' in e]
        assert min(clocks)>=1793878330991, 'sample predates new capture process'
        print(json.dumps({'status':'PASS','epoch':epoch,'state':state,'generation_created':created,
            'verification':verified,'downloaded_sample':{'path':path,'sha256':raw_hash,'bytes':raw_size,
            'events':len(events),'kinds':dict(Counter(e['kind'] for e in events)),
            'received_ms_min':min(clocks),'received_ms_max':max(clocks)},
            'cos_writes':False,'source_writes':False}))
except Exception as error:
    # SDK errors may embed signed URLs; do not echo their text.
    print(json.dumps({'status':'FAILED','error_type':type(error).__name__}))
    sys.exit(1)
