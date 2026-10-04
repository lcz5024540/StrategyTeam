#!/usr/bin/env python3
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).parent; sys.path.insert(0,str(ROOT))
spec=importlib.util.spec_from_file_location('quality_gate',ROOT/'htx_trend_research_quality_gate.py')
MODULE=importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[spec.name]=MODULE; spec.loader.exec_module(MODULE)


def bars(count=400):
    return [MODULE.Bar(index * 900, 100 + index * .1 - .2, 100 + index * .1 + .3,
                       100 + index * .1 - .4, 100 + index * .1, 10, 1000)
            for index in range(count)]


class ResearchQualityGateTest(unittest.TestCase):
    def test_future_mutation_cannot_change_completed_prefix_indicators_or_signals(self):
        source=bars(); index=300
        before=source
        after=MODULE._mutate_future(source,index)
        self.assertEqual(MODULE._indicator_snapshot(before,index),MODULE._indicator_snapshot(after,index))
        self.assertEqual(MODULE._signal_snapshot(before,'BTC-USDT',index),MODULE._signal_snapshot(after,'BTC-USDT',index))

    def test_recursive_required_warmup_has_explicit_stability_thresholds(self):
        self.assertEqual(256,MODULE.REQUIRED_WARMUP)
        stable={'fastEma':0.0,'slowEma':0.0004,'atr':0.0005,'vwap':0.0002,'adx':0.04}
        unstable={**stable,'adx':0.06}
        self.assertTrue(MODULE._within_tolerance(stable))
        self.assertFalse(MODULE._within_tolerance(unstable))


if __name__=='__main__': unittest.main()
