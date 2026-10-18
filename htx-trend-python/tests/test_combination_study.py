from pathlib import Path

import pytest

from htx_trend.research.combination_study import CombinationStudy
from htx_trend.research.store import FileEvidenceStore
from htx_trend.timeframes import TimeframeProfile


def _result(store: FileEvidenceStore, kind: str, conclusion: str = "REJECT",
            *, test_segment: str = "NOT_READ", nominee=None) -> str:
    return store.put(kind, {
        "profile": TimeframeProfile.INTRADAY_15M.value,
        "test_segment": test_segment,
        "conclusion": conclusion,
        "model_results": [{"model": "DONCHIAN_BREAKOUT", "conclusion": conclusion,
                           "selected_by_train": nominee}],
    })


def test_p7_records_terminal_rejection_without_eligible_inputs(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        entry = _result(store, "intraday_model_optimization_result")
        exit_ = _result(store, "exit_optimization_result")
        ref = CombinationStudy(store, TimeframeProfile.INTRADAY_15M, (entry, exit_)).run()
        payload = store.get(ref)["payload"]
        assert payload["status"] == "NO_ELIGIBLE_INPUTS"
        assert payload["conclusion"] == "REJECT"
        assert payload["nested_walk_forward"]["status"] == "NOT_RUN_GATE_FAILED"
        assert payload["combination_variants"] == []
        assert payload["test_segment"] == "NOT_READ"
        assert store.verify(ref)["integrity"] == "VERIFIED"


def test_p7_rejects_any_upstream_that_consumed_test(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        entry = _result(store, "intraday_model_optimization_result", test_segment="CONSUMED")
        exit_ = _result(store, "exit_optimization_result")
        with pytest.raises(ValueError, match="read TEST"):
            CombinationStudy(store, TimeframeProfile.INTRADAY_15M, (entry, exit_)).run()


def test_p7_does_not_mislabel_eligibility_gate_as_nested_result(tmp_path: Path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        entry = _result(store, "intraday_model_optimization_result",
                        conclusion="ELIGIBLE_FOR_P7", nominee="ENTRY_X")
        exit_ = _result(store, "exit_optimization_result")
        with pytest.raises(ValueError, match="separately frozen combination grid"):
            CombinationStudy(store, TimeframeProfile.INTRADAY_15M, (entry, exit_)).run()
