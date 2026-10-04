import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).parent; sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("model_policy", ROOT / "htx_trend_model_policy.py")
MODULE = importlib.util.module_from_spec(spec); assert spec and spec.loader; sys.modules[spec.name] = MODULE; spec.loader.exec_module(MODULE)


class ModelPolicyTest(unittest.TestCase):
    def manifest(self):
        return {"schema": "htx-trend-research-dataset-manifest-v1", "datasetKey": "research:test", "inputIntegrity": "COMPLETE"}

    def quality(self, decision="PASS"):
        return {"schema": MODULE.QUALITY_SCHEMA, "datasetKey": "research:test", "inputIntegrity": "COMPLETE", "decision": decision}

    def acceptance(self, evaluation):
        return {"schema": MODULE.ACCEPTANCE_SCHEMA, "datasetKey": "research:test", "inputIntegrity": "COMPLETE",
                "modelEvaluations": [evaluation]}

    def evaluation(self, **values):
        return {"modelCode": "FIB_PULLBACK_CONTINUATION", "sampleSplit": "OUT_OF_SAMPLE", "evaluationKey": "eval:test",
                "tradeCount": 50, "netProfit": 5, "profitFactor": 1.4, "expectancy": .1, "maxDrawdown": -1,
                "feeTotal": .5, **values}

    def test_failed_out_of_sample_model_is_rejected_and_cannot_turn_on_live_execution(self):
        policy = MODULE.build_policy(self.manifest(), self.quality(), self.acceptance(self.evaluation(profitFactor=1.1)))
        fib = next(row for row in policy["models"] if row["modelCode"] == "FIB_PULLBACK_CONTINUATION")
        self.assertEqual("REJECT", fib["decision"])
        self.assertFalse(fib["liveEnabled"])
        self.assertEqual("FORBIDDEN", policy["liveExecution"])

    def test_paper_candidate_still_cannot_authorize_live_execution(self):
        policy = MODULE.build_policy(self.manifest(), self.quality(), self.acceptance(self.evaluation()))
        fib = next(row for row in policy["models"] if row["modelCode"] == "FIB_PULLBACK_CONTINUATION")
        self.assertEqual("PAPER_CANDIDATE", fib["decision"])
        self.assertFalse(fib["liveEnabled"])
        self.assertEqual("FORBIDDEN", policy["liveExecution"])

    def test_unmatched_quality_report_fails_closed(self):
        policy = MODULE.build_policy(self.manifest(), self.quality("FAIL"), self.acceptance(self.evaluation()))
        self.assertEqual("INSUFFICIENT_EVIDENCE", policy["decision"])
        self.assertEqual([], policy["models"])


if __name__ == "__main__":
    unittest.main()
