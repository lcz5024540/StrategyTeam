"""关键配置参数必须显式提供，无静默默认值。"""

from pathlib import Path

import pytest

from tests.research.helpers import create_test_dataset, runner_config, write_runner


def test_missing_required_risk_cost_sizing_data_fields_raise(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "req")
    for field in (
        "initial_equity",
        "risk_per_trade",
        "max_total_risk",
        "max_positions",
        "cost_scenario",
        "manifest_hash",
        "symbols",
        "split",
        "leverage_target",
    ):
        cfg = runner_config(workspace, f"REQ_{field}", "req")
        del cfg[field]
        runner = write_runner(workspace, cfg)
        with pytest.raises(ValueError, match=field):
            runner.run()
