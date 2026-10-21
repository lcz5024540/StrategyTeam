import hashlib
import numpy as np
import pytest
from coinresearch.cost_ceiling import _cf, _roll, probe_csv, rolling_ci_upper
from coinresearch.cost_ceiling_cli import main


def test_formula_fallback():
    assert _roll(np.array([1., -1., 1., -1.])) == pytest.approx(np.sqrt(8 / 9))
    value, fallback = _cf(np.array([np.pi / 2] * 8), 1.)
    assert fallback and np.isfinite(value)


def test_coverage():
    # 100 Roll paths: c=.02, innovation sd=.02, n=1025, B=400,
    # blocks=16, one-sided 95%, tolerance=.05; no seed selection.
    rng = np.random.default_rng(6132026)
    covered = 0
    for trial in range(100):
        p = 100 + np.cumsum(rng.normal(0, .02, 1025)) + .02 * rng.choice([-1, 1], 1025)
        r = rolling_ci_upper(p, window=1025, block_length=16, bootstrap=400,
                             frequency_scale=10., seed=20261021 + trial)[0]
        covered += r['roll_ci_upper'] >= .02
        assert r['ci_upper'] >= r['roll_ci_upper']
    print(f'P6 Roll coverage: {covered}/100')
    assert covered >= 90


def test_zero():
    assert rolling_ci_upper(np.full(100, 100.), window=100, bootstrap=100)[0]['ci_upper'] == 0


def test_reproducibility(tmp_path):
    p = tmp_path / 'close.csv'
    p.write_text('ts,close\n' + ''.join(f'{i * 900},{100 + i % 3}\n' for i in range(100)))
    for name in ('a', 'b'):
        main(['--input', str(p), '--out-dir', str(tmp_path / name),
              '--window', '50', '--step', '25', '--bootstrap', '100'])
    for name in ('rolling_ci_upper.json', 'rolling_ci_upper.csv'):
        assert (tmp_path / 'a' / name).read_bytes() == (tmp_path / 'b' / name).read_bytes()
    result = probe_csv(p, tmp_path / 'c', window=50, bootstrap=100)
    assert result['input_sha256'] == hashlib.sha256(p.read_bytes()).hexdigest()
    assert result['point_estimate_written_to_prd'] is False


@pytest.mark.parametrize('kw', [
    {'window': 101}, {'window': 50, 'frequency_scale': 0},
    {'window': 50, 'block_length': 49}, {'window': 50, 'level': 1},
])
def test_invalid(kw):
    with pytest.raises(ValueError):
        rolling_ci_upper([100] * 100, **kw)


def test_gap(tmp_path):
    p = tmp_path / 'bad.csv'
    p.write_text('ts,close\n0,100\n900,101\n2700,102\n')
    with pytest.raises(ValueError, match='irregular'):
        probe_csv(p, tmp_path / 'out')
