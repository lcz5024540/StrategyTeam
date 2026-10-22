"""Fixed-seed acceptance: ≥5000 experiments, test-only B=500, no seed search."""
import hashlib
import pytest
from coinresearch.calibration import power_calibration, bootstrap_calibration


@pytest.fixture(scope='module')
def calibration(tmp_path_factory):
    path = tmp_path_factory.mktemp('calibration')/'prereg.json'
    path.write_text('{"split": {"test": "all synthetic replicates"}}')
    seal = dict(prereg_path=path, prereg_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    return power_calibration(seal, R=5000)


def test_nominal_5pct_one_sided_false_positive(calibration):
    print('CALIBRATION', calibration)
    assert calibration['repetitions'] >= 5000
    assert .035 <= calibration['false_positive'] <= .065, calibration


def test_g0_mde_nominal_80pct_power(calibration):
    assert calibration['repetitions'] >= 5000
    assert .75 <= calibration['power'] <= .85, calibration


@pytest.mark.parametrize('block_length', [30, 60])
def test_independent_coin_resampling_underestimates_variance(block_length):
    result = bootstrap_calibration(block_length)
    vjoint, vind, truth = (result[k] for k in ('joint_var', 'per_coin_var', 'truth_var'))
    assert vjoint > 2*vind
    assert abs(vjoint-truth) < abs(vind-truth)
    print('BOOTSTRAP_VARIANCE', block_length, dict(joint=vjoint, per_coin=vind, truth=truth))
