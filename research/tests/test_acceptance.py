"""D2-acceptance-checklist-v1: additional exact arithmetic and guard evidence."""
import hashlib
import json
from dataclasses import asdict
from decimal import Decimal
import numpy as np
import pandas as pd
import pytest
from coinresearch.cli import main, hashes
from coinresearch.config import Config
from coinresearch.g0 import m2_deff
from coinresearch.g2 import run_g2, permutation_test
from coinresearch.guard import SealedDataset, BlindGuardError
from coinresearch.ledger import build_ledger


def test_m2_closed_form():
    result = m2_deff(6019, .5588, 14.35, 530)
    for key, expected in dict(deff=8.46, neff=712, mde=49).items():
        assert result[key] == pytest.approx(expected, rel=.01)
    print('M2', result)


def test_exact_ledger_and_no_proxy():
    trades = [dict(id='long', coin='A', side=1, entry_ts=0, exit_ts=16,
                   entry_open='100', exit_open='101', funding_timestamps=[8,16]),
              dict(id='short', coin='A', side=-1, entry_ts=0, exit_ts=16,
                   gross_bps='100.125', funding_timestamps=[8,16]),
              dict(id='gap', coin='A', side=1, entry_ts=0, exit_ts=24,
                   gross_bps='100', funding_timestamps=[8,16,24]),
              dict(id='absent', coin='B', side=1, entry_ts=0, exit_ts=16,
                   gross_bps='100', funding_timestamps=[8,16])]
    rates = {'A': {8: '1.25', 16: '-0.125'}}
    result = build_ledger(trades, rates)
    assert [r['net_bps'] for r in result['ledger']] == [Decimal('86.875'), Decimal('89.250')]
    assert [r['funding_cost_bps'] for r in result['ledger']] == [Decimal('1.125'), Decimal('-1.125')]
    assert [r['id'] for r in result['excluded']] == ['gap', 'absent']
    assert [r['reason'] for r in result['excluded']] == ['MISSING_FUNDING_PERIOD', 'MISSING_FUNDING_COIN']
    assert result['excluded'][0]['timestamps'] == [24]
    assert build_ledger(trades[:1], rates, rt_cost='10.1')['ledger'][0]['net_bps'] == Decimal('88.775')
    assert build_ledger([dict(trades[0], funding_timestamps=None)], rates)['excluded']
    for invalid in ('NaN', 'bad'):
        assert build_ledger(trades[:1], {'A': {8: invalid, 16: '0'}})['excluded']
    price_short = dict(trades[0], side=-1, exit_open='99')
    assert build_ledger([price_short], rates)['ledger'][0]['net_bps'] == Decimal('89.125')


@pytest.mark.parametrize('value,neff,n,status', [(1,4400,1000,'PASS'), (1,1500,1000,'SMALL_SAMPLE_PASS'), (-1,4400,1000,'FAIL/NO_EDGE'), (1,1499,1000,'REFUSED_BELOW_FLOOR'), (1,4400,999,'REFUSED_BELOW_FLOOR')])
def test_defaults_and_cli_four_states(prereg, tmp_path, monkeypatch, value, neff, n, status):
    d = SealedDataset(np.full((n,5), value), np.arange(n)).seal(**prereg)
    # Use scalar nominal N=999 to isolate the nominal-floor boundary.
    if n == 999:
        d = SealedDataset(np.ones((n,1)), np.arange(n)).seal(**prereg)
    if status == 'REFUSED_BELOW_FLOOR':
        monkeypatch.setattr(d, 'directional_observations', lambda **kw: pytest.fail('floor must refuse before release'))
    result = run_g2(d, **prereg, neff=neff)
    assert result['status'] == status
    assert result['unlock_g3_request'] == (status == 'PASS')
    assert result['statistical_decision'] == ('H1' if status in ('PASS','SMALL_SAMPLE_PASS') else None if status == 'REFUSED_BELOW_FLOOR' else 'NO_EDGE')
    if status == 'REFUSED_BELOW_FLOOR':
        assert result['ci'] is None
    path = tmp_path/'observations.csv'
    pd.DataFrame({'period_index': np.arange(n), 'coin': np.full(n,value)}).to_csv(path,index=False)
    args = ['g2', '--observations', str(path), '--prereg-path', str(prereg['prereg_path']), '--prereg-sha256', prereg['prereg_sha256'], '--seal', '--neff', str(neff)]
    for i in (1,2):
        assert main(args+['--out-dir',str(tmp_path/f'out{i}')]) == 0
    assert hashes(tmp_path/'out1') == hashes(tmp_path/'out2')
    assert json.loads((tmp_path/'out1/g2_results.json').read_text())['status'] == status
    record = json.loads((tmp_path/'out1/run_record.json').read_text())
    assert record['input_sha256'][str(path.resolve())] == hashlib.sha256(path.read_bytes()).hexdigest()
    assert record['input_sha256'][str(prereg['prereg_path'].resolve())] == prereg['prereg_sha256']
    assert record['parameters']['nominal_floor'] == 1000
    assert record['parameters']['hard_floor'] == 1500
    assert record['parameters']['neff_threshold'] == 4400
    assert record['seed'] == 20261019


@pytest.mark.parametrize('command', ['d1','g0'])
def test_run_records_all_inputs_parameters_and_bitwise_repeat(synthetic, tmp_path, command):
    root, cfg = synthetic
    config_path = tmp_path/'config.json'
    config_path.write_text(json.dumps(asdict(cfg)))
    args = [command,'--data-dir',str(root),'--config',str(config_path)]
    if command == 'g0': args += ['--target-bps','20']
    for i in (1,2):
        assert main(args+['--out-dir',str(tmp_path/f'out{i}')]) == 0
    assert hashes(tmp_path/'out1') == hashes(tmp_path/'out2')
    record = json.loads((tmp_path/'out1/run_record.json').read_text())
    assert record['input_sha256'] == dict(hashes(root), **{str(config_path.resolve()): hashlib.sha256(config_path.read_bytes()).hexdigest()})
    assert record['seed'] == cfg.seed
    params = record['parameters']['config'] if command == 'g0' else record['parameters']
    assert params == asdict(cfg)
    if command == 'g0': assert record['parameters']['target_bps'] == 20


def test_permutation_preserves_structure_diagnostic_only(prereg):
    sides = np.tile([-1,1], 40)
    holding = np.repeat([1,2],40)
    costs = np.tile(np.repeat([12,24],20),2)
    # An explicit synthetic price/side association; balanced signs per stratum.
    d = SealedDataset((sides*10.)[:,None], np.arange(80)).seal(**prereg)
    args = dict(**prereg, sides=sides, holding_lengths=holding, costs=costs)
    result = permutation_test(d, **args)
    assert result == permutation_test(d, **args)
    assert result['diagnostic_only'] is True and 'unlock_g3_request' not in result
    assert result['B'] >= 5000 and result['pvalue'] <= .01
    assert result['N'] == 80 and result['long_count'] == 40
    assert result['gross_edge_bps'] == 10  # no cost subtraction in gross diagnostic
    assert result['strata'] == [dict(holding_length=h,cost=c,count=20,long_count=10) for h in (1,2) for c in (12,24)]
    with pytest.raises(ValueError): permutation_test(d, **args, B=4999)
    with pytest.raises(BlindGuardError): permutation_test(SealedDataset(np.ones((80,1)),np.arange(80)), **args)


def test_unregistered_sha_rejects_direction(prereg):
    d = SealedDataset(np.ones((40,1)), np.arange(40))
    missing = dict(prereg, prereg_sha256=None)
    with pytest.raises(BlindGuardError): d.seal(**missing)
    with pytest.raises(BlindGuardError): d.directional_observations(**missing)
    with pytest.raises(BlindGuardError): run_g2(d, **missing, neff=4400)


def test_config_defaults():
    assert (Config().neff_threshold, Config().hard_floor, Config().nominal_floor) == (4400,1500,1000)
    with pytest.raises(ValueError): Config(nominal_floor=0)
