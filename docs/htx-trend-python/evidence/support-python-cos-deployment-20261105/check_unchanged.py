"""Read-only deployment checks. Hash protected environments, never print them."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

STAGE = Path('/home/ubuntu/coinstrategy-cos-archive/deploy-20261105-4fa6ccee')
BASE = Path('/opt/coinstrategy/15m-market-data')
NEW = BASE / 'releases/0.1.7-realtime-candidate-4fa6ccee'

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def show(unit):
    raw = subprocess.check_output(['systemctl','show',unit,'-p','MainPID',
        '-p','ActiveState','-p','NRestarts','-p','UnitFileState'], text=True)
    return dict(line.split('=',1) for line in raw.splitlines())

baseline = json.loads((STAGE/'predeploy.json').read_bytes())
manifest = json.loads((NEW/'MANIFEST.json').read_bytes())
assert manifest['bindings']['source_commit'] == '4fa6ccee67aae7635aa2e3393f314977912ddc14'
installed_files = 0
for path, entry in manifest['files'].items():
    if entry['hash_mode'] == 'raw':
        assert sha(NEW/path) == entry['sha256'], 'installed package member changed: ' + path
        installed_files += 1
for path, expected in baseline['unchanged_files'].items():
    assert sha(path) == expected, 'protected file changed: ' + path
for unit, expected in baseline['protected'].items():
    state = show(unit)
    for name in ('MainPID','ActiveState','NRestarts','UnitFileState'):
        assert state[name] == expected[name], 'protected process changed: ' + unit
    assert sha('/proc/'+state['MainPID']+'/environ') == expected['process_env_sha256']
    assert hashlib.sha256(subprocess.check_output(['systemctl','cat',unit])).hexdigest() == expected['unit_sha256']
for unit, expected in baseline['cos_units'].items():
    assert hashlib.sha256(subprocess.check_output(['systemctl','cat',unit])).hexdigest() == expected['unit_sha256']
assert str(Path('/opt/coinstrategy/htx-trend-py/current').resolve()) == baseline['live_current']

# The pinned maintenance API and archive/storage formats remain byte-identical.
pinned = BASE / 'releases/0.1.7-realtime-candidate-869660ab/src/htx_trend'
checked = {}
for module in ('archive','sealing'):
    old = {str(p.relative_to(pinned)):sha(p) for p in (pinned/module).rglob('*.py')}
    newroot = NEW/'src/htx_trend'
    new = {str(p.relative_to(newroot)):sha(p) for p in (newroot/module).rglob('*.py')}
    assert old and old == new, 'pinned API changed: ' + module
    checked[module] = len(old)
after = '--after' in sys.argv
expected_current = NEW if after else Path(baseline['old_current'])
assert (BASE/'current').resolve() == expected_current
if after:
    assert Path('/etc/systemd/system/coinstrategy-15m-market-data.service').read_bytes() == (NEW/'release/cos/coinstrategy-15m-market-data.service').read_bytes()
    assert Path('/etc/coinstrategy/15m-cos-release.env').read_bytes() == (STAGE/'15m-cos-release.env').read_bytes()
    for unit in ('coinstrategy-15m-cos-archive.timer','coinstrategy-15m-cos-release.timer'):
        assert show(unit)['ActiveState'] == 'active'
else:
    assert sha('/etc/systemd/system/coinstrategy-15m-market-data.service') == baseline['old_data_unit_sha256']
    assert sha('/etc/coinstrategy/15m-cos-release.env') == baseline['old_env_sha256']
print(json.dumps({'status':'PASS','observed_ms':int(time.time()*1000),'after':after,
    'current':str(expected_current),'pinned_api_files':checked,'installed_files_verified':installed_files,
    'protected_pids':{u:v['MainPID'] for u,v in baseline['protected'].items()},
    'data_service':show('coinstrategy-15m-market-data.service')}))
