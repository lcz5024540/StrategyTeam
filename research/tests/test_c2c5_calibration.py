"""Fast evidence/guard coverage; full C2 remains in test_calibration and CLI."""
import hashlib
import json

import pytest

from coinresearch.calibration import (
    calibrate_c2c5, canonical_sha256, deff_calibration, power_calibration,
)
from coinresearch.cli import main, hashes


def test_evidence_bitwise_schema_and_guards(tmp_path):
    outputs = [tmp_path/'a', tmp_path/'b']
    for out in outputs:
        result = calibrate_c2c5(out, tip_sha='synthetic-test-tip', R=2,
                               allow_small_R_for_tests=True)
        assert result['tip_sha'] == 'synthetic-test-tip'
        assert result['master_seed'] == 20261019
        assert result['C2']['R'] == 2 and result['C2']['power_window'] == [.75, .85]
        assert result['C2']['pass'] is False and result['all_pass'] is False
        assert result['C3']['B'] >= 2000
        assert result['C3']['block_lengths'] == [30, 60]
        for case in result['C3']['cases']:
            assert case['joint_var'] > 2*case['per_coin_var']
            assert abs(case['joint_var']-case['truth_var']) < abs(case['per_coin_var']-case['truth_var'])
            assert case['pass'] is True
        assert result['C4']['synthetic']['relative_error'] <= .06  # preserve stricter legacy bound
        assert result['C4']['synthetic']['estimated_icc'] == pytest.approx(.4, abs=.025)
        assert result['C4']['m2']['pass'] is True
        assert result['C5']['B'] >= 5000 and result['C5']['diagnostic_only'] is True
        assert result['C5']['alternative'] == 'greater'
        assert result['C5']['no_admission_fields'] and result['C5']['structure_preserved']
        assert all(result['C5']['guards'].values())
        assert all(result[k]['pass'] for k in ('C3', 'C4', 'C5'))
        assert 'unlock' not in json.dumps(result).lower()
        assert result['parameters_sha256'] == canonical_sha256(result['parameters'])
        assert result['prereg_sha256'] == hashlib.sha256((out/'prereg.json').read_bytes()).hexdigest()
        assert json.loads((out/'prereg.json').read_text())['parameters'] == result['parameters']
        record = json.loads((out/'run_record.json').read_text())
        assert record['seed'] == 20261019
        assert record['input_sha256'] == result['input_sha256']
        assert record['parameters'] == dict(result['parameters'], tip_sha=result['tip_sha'])
        assert json.loads((out/'c2c5_calibration.json').read_text()) == result
    assert hashes(outputs[0]) == hashes(outputs[1])


@pytest.mark.parametrize('kwargs', [dict(R=4999), dict(R=0, allow_small_R_for_tests=True),
                                    dict(c3_B=1999), dict(c5_B=4999)])
def test_public_minima_before_output(tmp_path, kwargs):
    with pytest.raises(ValueError):
        calibrate_c2c5(tmp_path/'absent', **kwargs)
    assert not (tmp_path/'absent').exists()


def test_shared_power_minimum(prereg):
    with pytest.raises(ValueError, match='R must be >=5000'):
        power_calibration(prereg, R=1000)


@pytest.mark.parametrize('flags', [['--R', '1000'], ['--c3-B', '1999'],
                                  ['--c5-B', '4999'], ['--allow-small-R-for-tests']])
def test_cli_rejects_small_counts(tmp_path, flags):
    with pytest.raises(SystemExit) as exc:
        main(['calibrate-c2c5', '--out-dir', str(tmp_path/'absent'), *flags])
    assert exc.value.code != 0
    assert not (tmp_path/'absent').exists()


def test_cli_defaults_and_failure_exit(tmp_path, monkeypatch, capsys):
    import coinresearch.calibration as module
    calls = []
    def fake(out, **kwargs):
        calls.append(kwargs)
        return dict(all_pass=len(calls) == 1)
    monkeypatch.setattr(module, 'calibrate_c2c5', fake)
    args = ['calibrate-c2c5', '--out-dir', str(tmp_path), '--tip-sha', 'test']
    assert main(args) == 0
    assert main(args) == 1
    assert calls[0] == dict(tip_sha='test', master_seed=20261019, R=5000, c3_B=2000, c5_B=5000)


def test_c4_estimates_icc_instead_of_injecting_truth(monkeypatch):
    import coinresearch.calibration as module
    original = module.deff_neff
    def checked(values, timestamps, **kwargs):
        assert 'icc' not in kwargs
        return original(values, timestamps, **kwargs)
    monkeypatch.setattr(module, 'deff_neff', checked)
    result = deff_calibration()
    assert result['synthetic']['relative_error'] <= .06
    assert result['pass']
