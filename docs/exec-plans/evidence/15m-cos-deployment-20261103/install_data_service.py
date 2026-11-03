"""User-authorized data-only install. No trading-unit, database or evidence deletion."""
from pathlib import Path
import hashlib,json,os,pwd,shutil,subprocess,tarfile
from datetime import datetime
VERSION='0.1.7-realtime-candidate-d2a64d34'
COMMIT='d2a64d34ee031aaa268b5d3480b850043376a7fb'
SHA='e7955e6c3f066a8b64ca07381ace45f75d5b3c8f5008f7e19a2ed1ec9ea1f643'
BASE=Path('/opt/coinstrategy/15m-market-data')
STAGE=Path('/home/ubuntu/coinstrategy-cos-archive/deploy-15m-d2a64d34')
ARCHIVE=STAGE/('htx-trend-python-'+VERSION+'.tar.gz')
uid=pwd.getpwnam('ubuntu').pw_uid;gid=pwd.getpwnam('ubuntu').pw_gid
assert os.geteuid()==0
assert hashlib.sha256(ARCHIVE.read_bytes()).hexdigest()==SHA
assert shutil.disk_usage('/').free > 8*1024**3
for unit,pid in [('htx-trend-worker@htx-trend-live-primary','2213018'),('htx-trend-paper-4h','705510')]:
 assert subprocess.check_output(['systemctl','show',unit,'-p','MainPID','--value'],text=True).strip()==pid, 'runtime baseline changed'

def write(path,data,mode=0o640,owner=True):
 path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists() and path.read_bytes()!=data:raise RuntimeError('refuse replacing previously unreviewed file: '+str(path))
 if not path.exists():
  with path.open('xb') as f:f.write(data);f.flush();os.fsync(f.fileno())
 os.chmod(path,mode)
 if owner:os.chown(path,uid,gid)

release=BASE/'releases'/VERSION
if not release.exists():
 release.mkdir(parents=True)
 with tarfile.open(ARCHIVE) as tf:
  for member in tf.getmembers():
   p=Path(member.name)
   assert not p.is_absolute() and '..' not in p.parts and member.isfile(), 'unsafe package member'
  tf.extractall(release,filter='data')
checked=json.loads(subprocess.check_output(['/opt/coinstrategy/htx-trend-py-venv/bin/python',str(release/'release/package.py'),'verify',str(ARCHIVE)],text=True))
assert checked['package_sha256']==SHA and checked['bindings']['source_commit']==COMMIT
assert checked['build']['working_tree_dirty'] is False
frozen=Path('/workspace/frozen')/VERSION
frozen.mkdir(parents=True,exist_ok=True)
for member in [ARCHIVE.name,ARCHIVE.name+'.sha256','MANIFEST.json','BUILD.json']:
 write(frozen/member,(STAGE/member).read_bytes(),0o444,False)
write(frozen/'SHA256SUMS',(SHA+'  '+ARCHIVE.name+'\n').encode(),0o444,False)
current=BASE/'current'
if current.exists() or current.is_symlink():assert current.resolve()==release
else:
 os.symlink(release,BASE/'.current-next');os.replace(BASE/'.current-next',current)
for path in [Path('/home/ubuntu/coinstrategy-data'),Path('/home/ubuntu/coinstrategy-data/15m-cos'),Path('/home/ubuntu/coinstrategy-cos-archive/15m-work')]:
 path.mkdir(parents=True,exist_ok=True);os.chown(path,uid,gid);os.chmod(path,0o750)
old_pool=Path('/home/ubuntu/coinstrategy-research/long-20261103-aaf3352c/runtime-pool.json')
old=json.loads(old_pool.read_bytes());assert len(old['contracts'])==52
pool={'contracts':old['contracts'],'pool_source':str(old_pool),'pool_source_sha256':hashlib.sha256(old_pool.read_bytes()).hexdigest(),
      'collection_source_commit':COMMIT,'collection_package_sha256':SHA,'scope':'PUBLIC_MARKET_DATA_ONLY_NO_TRADING'}
config={'bucket':'rayquant-strategy-1253235498','region':'na-siliconvalley','prefix':'coinstrategy/archive/v1/',
        'sources':{'market-15m':{'kind':'segments','root':'/home/ubuntu/coinstrategy-data/15m-cos/capture/segments'}},
        'schedule':{'enabled':True,'interval_seconds':900,'audit_seconds':604800}}
write('/etc/coinstrategy/15m-cos-pool.json',(json.dumps(pool,indent=2)+'\n').encode())
write('/etc/coinstrategy/15m-cos-archive.json',(json.dumps(config,indent=2)+'\n').encode())
units=['coinstrategy-15m-market-data.service','coinstrategy-15m-cos-archive.service','coinstrategy-15m-cos-archive.timer']
for name in units:write(Path('/etc/systemd/system')/name,(release/'release/cos'/name).read_bytes(),0o644,False)
subprocess.run(['systemd-analyze','verify',*[str(Path('/etc/systemd/system')/u) for u in units]],check=True)
receipt={'installed_at':datetime.now().astimezone().isoformat(),'version':VERSION,'source_commit':COMMIT,'package_sha256':SHA,
         'frozen_tar':str(frozen/ARCHIVE.name),'release':str(release),'data_only':True,'contracts':len(pool['contracts']),
         'unmodified_management_pid':2213018,'unmodified_4h_pid':705510,'previous_data_release':None,
         'files':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path('/etc/coinstrategy/15m-cos-pool.json'),Path('/etc/coinstrategy/15m-cos-archive.json'),*[Path('/etc/systemd/system')/n for n in units]]}}
write(STAGE/'installation.json',(json.dumps(receipt,indent=2)+'\n').encode())
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','--now','coinstrategy-15m-market-data.service'],check=True)
print(json.dumps(receipt,indent=2))
