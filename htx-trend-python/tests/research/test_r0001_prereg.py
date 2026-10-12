"""R0001 registration matches prereg-batch1; TODO placeholders are rejected."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from htx_trend.research.runner import BacktestRunner
from tests.research.helpers import create_test_dataset, runner_config


def _r0001_path() -> Path:
    return Path(__file__).resolve().parents[2] / "configs" / "runs" / "R0001.yaml"


def test_r0001_registration_matches_prereg_batch1():
    cfg = yaml.safe_load(_r0001_path().read_text())
    reg = cfg["registration"]
    assert "PF" not in reg["keep_rule"]
    assert "PF" not in reg["drop_rule"]
    assert "1.3" not in reg["keep_rule"]
    assert "盈利" in reg["keep_rule"] or "不要求盈利" in reg["expected_effect"]
    assert "时间序列动量" in reg["hypothesis"]
    assert "TSMOM" in reg["evidence_ref"] or "参数依据" in reg["evidence_ref"]
    assert "流程" in reg["keep_rule"] or "流程" in reg["expected_effect"]
    assert "记录不全" in reg["drop_rule"]
    assert cfg["timeframe"] == "4hour"
    assert cfg["symbols"] == ["BTC-USDT", "ETH-USDT"]
    assert cfg["initial_equity"] == 300
    assert cfg["risk_per_trade"] == 0.02
    assert cfg["max_total_risk"] == 0.10
    assert cfg["max_positions"] == 5
    assert "force_one_contract" not in cfg
    assert "force_min_contract" not in cfg
    assert "stage_acceptance" not in cfg
    assert "TODO" in str(cfg["dataset_id"])
    assert "TODO" in str(cfg["manifest_hash"])


def test_todo_placeholders_are_rejected_until_data_frozen(tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    create_test_dataset(workspace, "ds")
    cfg = runner_config(workspace, "R0001", "ds")
    cfg["dataset_id"] = "TODO_FREEZE_DATASET_ID"
    cfg["manifest_hash"] = "TODO_FREEZE_MANIFEST_HASH"
    path = workspace / "R0001.yaml"
    path.write_text(yaml.dump(cfg))
    runner = BacktestRunner(path, workspace)
    with pytest.raises(ValueError, match="TODO|placeholder|freeze"):
        runner.run()
    assert runner.ledger.get_all_trials() == []
    assert runner.ledger.count_trials(runner.run_id) == 0
