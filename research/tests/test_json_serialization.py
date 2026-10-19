import json

import numpy as np
import pytest

from coinresearch.data import build_dataset, write_json
from coinresearch.g0 import run_g0
from coinresearch.g2 import run_g2
from coinresearch.guard import SealedDataset
from coinresearch.records import write_run_record


def test_nested_numpy_scalars(tmp_path):
    path = tmp_path / 'scalars.json'
    payload = {'flags': [np.bool_(True), np.bool_(False)],
               'numbers': [np.int64(-3), np.uint64(2**64-1),
                           np.float32(1.25), np.float64(2.5), np.longdouble(3.5)]}
    write_json(path, payload)
    assert json.loads(path.read_text()) == {
        'flags': [True, False], 'numbers': [-3, 2**64-1, 1.25, 2.5, 3.5]}


@pytest.mark.parametrize('value', [object(), np.array([1]), np.complex128(1j)])
def test_unknown_types_rejected(tmp_path, value):
    with pytest.raises(TypeError, match='not JSON serializable'):
        write_json(tmp_path / 'unknown.json', {'value': value})


@pytest.mark.parametrize('value', [np.float32('nan'), np.float64('inf'), np.longdouble('-inf')])
def test_nonfinite_scalars_rejected(tmp_path, value):
    with pytest.raises(ValueError):
        write_json(tmp_path / 'nonfinite.json', {'value': value})


def test_gate_and_run_record_json(synthetic, prereg, tmp_path):
    root, cfg = synthetic
    g0 = run_g0(build_dataset(root, cfg), target_bps=np.float32(20), cfg=cfg)
    sealed = SealedDataset(np.ones((40, 1)), np.arange(40)).seal(**prereg)
    g2 = run_g2(sealed, **prereg, neff=np.float32(40), neff_threshold=30,
                hard_floor=10, nominal_floor=1, B=np.int64(2000),
                circular=np.bool_(True))
    for name, result in [('g0', g0), ('g2', g2)]:
        path = tmp_path / f'{name}_results.json'
        write_json(path, result)
        loaded = json.loads(path.read_text())
        assert json.loads(json.dumps(loaded, allow_nan=False)) == loaded
    assert json.loads((tmp_path / 'g0_results.json').read_text())['full_ge10']['mde_le_target'] is False
    assert json.loads((tmp_path / 'g2_results.json').read_text())['circular'] is True
    write_run_record(tmp_path, 'g2', {}, {'g0': g0, 'g2': g2}, np.int64(20261019))
    record = json.loads((tmp_path / 'run_record.json').read_text())
    assert type(record['seed']) is int
    assert record['parameters']['g2']['B'] == 2000
