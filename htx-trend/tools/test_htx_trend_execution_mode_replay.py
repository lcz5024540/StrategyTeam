import importlib.util, sys, unittest
from pathlib import Path
ROOT=Path(__file__).parent; spec=importlib.util.spec_from_file_location('mode_replay',ROOT/'htx_trend_execution_mode_replay.py')
M=importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[spec.name]=M; spec.loader.exec_module(M)
class ExecutionModeReplayTest(unittest.TestCase):
    def plan(self): return M.Plan('LONG',100,101,100,1,105)
    def test_compares_fill_and_miss_without_faking_limit_fill(self):
        bars=[M.Bar(100,100.5,99.5,100.2),M.Bar(102,103,101.5,102)]
        self.assertEqual('FILLED',M.simulate(self.plan(),bars,'MARKET_BASELINE').state)
        self.assertEqual('FILLED',M.simulate(self.plan(),bars,'PASSIVE_LIMIT').state)
        self.assertEqual('EXPIRED',M.simulate(M.Plan('LONG',99,100,100,1,105),bars,'PASSIVE_LIMIT').state)
    def test_reprice_never_crosses_immutable_worst_price(self):
        plan=M.Plan('LONG',99,100,100,1,105); bars=[M.Bar(101,102,100.5,101),M.Bar(101,101,99.8,100)]
        result=M.simulate(plan,bars,'ONE_REPRICE'); self.assertEqual('FILLED',result.state); self.assertLessEqual(result.fill_price,100)
if __name__=='__main__': unittest.main()
