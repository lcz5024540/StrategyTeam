"""预注册文件的 SHA256 是运行口令，不一致就拒绝。"""
from decimal import Decimal
from pathlib import Path

import pytest

from calibration import ThresholdLoweringForbidden
from prereg import PreregRejected, assert_prereg_sha, file_sha256, main

ROOT = Path(__file__).resolve().parents[1]
PREREG = ROOT / "prereg.yaml"


def test_matching_sha_loads_registered_protocol():
    digest = file_sha256(PREREG)
    cfg = assert_prereg_sha(PREREG, digest)
    assert cfg["tick_size"] == Decimal("0.1")
    assert cfg["k_ticks"] == [1, 2, 3]
    assert cfg["horizon_s"] == [5, 15, 30]
    assert cfg["start_rule"] == "next_whole_second_after_signal"
    assert cfg["prior"]["name"] == "queue_race"
    assert cfg["prior"]["p_up"] == "Qb/(Qb+Qa)"
    assert cfg["imbalance_tilt"]["windows_min"] == [1, 5, 15]
    assert cfg["imbalance_tilt"]["link"] == "logit_additive"
    assert cfg["calibration"]["min_bin_count"] == 400
    assert cfg["split"]["method"] == "time"
    assert cfg["fees"]["taker_bps"] == 12
    assert cfg["fees"]["maker_bps"] == "measured"
    assert cfg["fill_miss"]["value"] == "measured"
    assert cfg["fill_miss"]["unfilled_pnl_bps"] == 0


def test_sha_mismatch_refuses_to_run():
    with pytest.raises(PreregRejected):
        assert_prereg_sha(PREREG, "0" * 64)


def test_cli_requires_matching_prereg_sha():
    with pytest.raises(SystemExit) as missing:
        main(["--prereg", str(PREREG)])
    assert missing.value.code == 2
    assert main(["--prereg", str(PREREG), "--prereg-sha", "0" * 64]) == 2
    assert main(["--prereg", str(PREREG), "--prereg-sha", file_sha256(PREREG)]) == 0


def test_lowered_bin_count_rejected_even_when_sha_matches(tmp_path):
    text = PREREG.read_text(encoding="utf-8").replace(
        "min_bin_count: 400", "min_bin_count: 10", 1
    )
    path = tmp_path / "prereg.yaml"
    path.write_text(text, encoding="utf-8")
    with pytest.raises(ThresholdLoweringForbidden):
        assert_prereg_sha(path, file_sha256(path))
