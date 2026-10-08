import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parent; sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("msar", ROOT / "htx_trend_regime_msar.py")
MODULE = importlib.util.module_from_spec(SPEC); assert SPEC and SPEC.loader; sys.modules[SPEC.name] = MODULE; SPEC.loader.exec_module(MODULE)


class MsarTest(unittest.TestCase):
    def test_fit_emits_normalized_two_state_probabilities(self):
        values = [(.002 if index < 80 else -.001) + (.0005 if index % 2 else -.0005) for index in range(180)]
        model = MODULE.MarkovSwitchingAr(MODULE.MsarConfig(max_iterations=20)).fit(values)
        self.assertEqual(2, len(model.transitions)); self.assertTrue(model.iterations > 0)
        for row in model.filter(values): self.assertAlmostEqual(1.0, sum(row), places=8)

    def test_mapping_has_interpretable_transition_and_stable_states(self):
        model = MODULE.MarkovSwitchingAr(MODULE.MsarConfig())
        model.variances = [0.01, 0.0001]
        self.assertEqual({"HIGH_VOL_TRANSITION", "STABLE_CONTEXT"}, set(MODULE._mapping(model).values()))

    def test_runtime_manifest_requires_only_high_timeframe_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "dataset-manifest.json"
            path.write_text(json.dumps({"schema": "htx-trend-research-dataset-manifest-v1", "sourceKind": "FROZEN_RUNTIME_KLINE",
                                        "inputIntegrity": "COMPLETE", "contracts": ["BTC-USDT"], "inputs": [
                                            {"contract": "BTC-USDT", "period": "4hour", "coverage": "COMPLETE"},
                                            {"contract": "BTC-USDT", "period": "1day", "coverage": "COMPLETE"}]}))
            self.assertEqual("FROZEN_RUNTIME_KLINE", MODULE._read_msar_manifest(path)["sourceKind"])


if __name__ == "__main__": unittest.main()
