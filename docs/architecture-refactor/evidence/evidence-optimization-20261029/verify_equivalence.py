"""Compare the fixed production replay between c00cbac6 and the working tree."""
import json,os,subprocess,sys
from pathlib import Path
base=Path('/Users/lichangzheng.ray/Ray/ray-agent/.worktrees/support_htx_python/htx-trend-python')
current=Path(__file__).resolve().parents[4]/'htx-trend-python'
program='from pathlib import Path; import json; from htx_trend.scan_replay import replay; print(json.dumps(replay(Path("replay_inputs/20261021")),sort_keys=True,default=str))'
reports=[]
for root in (base,current):
 result=subprocess.run([sys.executable,'-c',program],cwd=root,env={**os.environ,'PYTHONPATH':str(root/'src')},capture_output=True,text=True,check=True)
 reports.append(json.loads(result.stdout))
source_hashes = [r['header'].pop('source_sha256') for r in reports]
assert reports[0]==reports[1], 'baseline replay business output differs'
print(json.dumps({'baseline':'c00cbac653d7cd204f4af2f21781f6ffe4bf38f1','fixed_replay':'replay_inputs/20261021','all_business_report_fields_equal':True,'excluded_metadata':['header.source_sha256'],'source_hashes':source_hashes,'network':'none'},indent=2))
