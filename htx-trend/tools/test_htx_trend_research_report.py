import importlib.util
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).parent; sys.path.insert(0,str(ROOT))
spec=importlib.util.spec_from_file_location('research_report',ROOT/'htx_trend_research_report.py')
MODULE=importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[spec.name]=MODULE; spec.loader.exec_module(MODULE)


class ResearchReportTest(unittest.TestCase):

    def test_epoch_accepts_explicit_and_legacy_shanghai_timestamps(self):
        self.assertEqual(MODULE.epoch("2026-01-01T00:00:00+08:00"), MODULE.epoch("2026-01-01T00:00:00"))
    def manifest(self, integrity='COMPLETE'):
        return {'datasetKey':'research:test','inputIntegrity':integrity,'inputStartAt':'2026-01-01T00:00:00','inputEndAt':'2026-01-09T00:00:00'}

    def quality_gate(self, decision='PASS'):
        return {'schema':'htx-trend-research-quality-gate-v1','datasetKey':'research:test',
                'inputIntegrity':'COMPLETE','decision':decision}

    def test_partial_input_cannot_produce_a_model_conclusion(self):
        report=MODULE.build_report(self.manifest('PARTIAL'),{'datasetKey':'research:test','inputIntegrity':'PARTIAL','labels':[]})
        self.assertEqual('INSUFFICIENT_EVIDENCE',report['decision'])

    def test_report_uses_out_of_sample_labels_and_marks_low_samples(self):
        label={'modelCode':'DONCHIAN_BREAKOUT','side':'LONG','marketState':'TREND_UP','contractCode':'BTC-USDT',
               'entryBarTime':MODULE.epoch('2026-01-08T00:00:00'),'netProfit':1.0,'estimatedEntryFee':.1,'estimatedExitFee':.1,'dataIntegrity':'COMPLETE'}
        report=MODULE.build_report(self.manifest(),{'datasetKey':'research:test','inputIntegrity':'COMPLETE','labels':[label]},self.quality_gate())
        self.assertEqual(1,len(report['rows'])); self.assertEqual('INSUFFICIENT_SAMPLE',report['rows'][0]['decision'])
        self.assertEqual('REJECT',report['decision'])

    def test_report_requires_a_passing_quality_gate_for_the_same_dataset(self):
        report=MODULE.build_report(self.manifest(),{'datasetKey':'research:test','inputIntegrity':'COMPLETE','labels':[]})
        self.assertEqual('INSUFFICIENT_EVIDENCE',report['decision'])
        report=MODULE.build_report(self.manifest(),{'datasetKey':'research:test','inputIntegrity':'COMPLETE','labels':[]},self.quality_gate('REJECT'))
        self.assertEqual('INSUFFICIENT_EVIDENCE',report['decision'])


if __name__=='__main__': unittest.main()
