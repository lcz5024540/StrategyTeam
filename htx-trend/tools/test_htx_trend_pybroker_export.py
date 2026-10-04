import importlib.util,json,sys,tempfile,unittest
from hashlib import sha256
from pathlib import Path
ROOT=Path(__file__).parent; spec=importlib.util.spec_from_file_location('pybroker_export',ROOT/'htx_trend_pybroker_export.py'); M=importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[spec.name]=M; spec.loader.exec_module(M)
class PyBrokerExportTest(unittest.TestCase):
 def test_exports_verified_frozen_bars_without_runtime_dependency(self):
  with tempfile.TemporaryDirectory() as raw:
   root=Path(raw); payload='{"status":"ok","data":[{"id":1,"open":1,"high":2,"low":0.5,"close":1.5,"vol":9}]}' ; source=root/'BTC-USDT_15min.json'; source.write_text(payload)
   manifest={"schema":"htx-trend-research-dataset-manifest-v1","datasetKey":"research:test","inputIntegrity":"COMPLETE","inputs":[{"contract":"BTC-USDT","period":"15min","file":source.name,"sha256":sha256(payload.encode()).hexdigest(),"coverage":"COMPLETE","sourceKind":"RAW_PUBLIC"}]}; mp=root/'manifest.json'; mp.write_text(json.dumps(manifest)); report=M.export(root,mp,root/'out.csv')
   self.assertEqual(('COMPLETE','NOT_BUNDLED',1),(report['inputIntegrity'],report['pybrokerRuntime'],report['rowCount']))
if __name__=='__main__': unittest.main()
