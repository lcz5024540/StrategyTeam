from datetime import datetime, timezone
from decimal import Decimal
import json
import pytest
from coinresearch.cli import main, hashes
from coinresearch.report import build_report


def ledger():
    rows = []
    for coin, year, net in [('A', 2022, '10'), ('B', 2022, '10'),
                            ('A', 2023, '40'), ('B', 2023, '60'),
                            ('A', 2024, '10'), ('B', 2024, '10')]:
        ts = int(datetime(year, 6, 1, tzinfo=timezone.utc).timestamp())
        rows.append(dict(coin=coin, entry_ts=ts, exit_ts=ts+10, net_bps=net))
    return rows


@pytest.mark.parametrize('format_', ['json', 'csv'])
def test_one_shot_report_hand_calculation_and_determinism(tmp_path, format_):
    """A=60/3=20; B=80/3; 2022=20,2023=100,2024=20; top2=100/140.
    Remove 2023 -> four 10-bps observations: mean=10, bootstrap CI=[10,10].
    """
    source = tmp_path/f'ledger.{format_}'
    rows = ledger()
    if format_ == 'json': source.write_text(json.dumps(dict(ledger=rows)))
    else:
        import csv
        with source.open('w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(rows)
    for name in ('a', 'b'):
        assert main(['report', '--ledger', str(source), '--out-dir', str(tmp_path/name), '--top-n', '2']) == 0
    assert hashes(tmp_path/'a') == hashes(tmp_path/'b')
    r = json.loads((tmp_path/'a/report.json').read_text())
    assert r['by_coin']['A'] == dict(count=3, net_bps_sum='60', net_bps_mean='20')
    assert Decimal(r['by_coin']['B']['net_bps_mean']) == Decimal(80)/3
    assert {k: v['net_bps_sum'] for k, v in r['by_year'].items()} == {'2022': '20', '2023': '100', '2024': '20'}
    assert r['top_trades']['net_bps_sum'] == '100'
    assert Decimal(r['top_trades']['contribution_ratio']) == Decimal(100)/140
    sensitivity = r['without_best_year']
    assert sensitivity['removed_year'] == '2023'
    assert sensitivity['count'] == 4 and sensitivity['net_bps_mean'] == '10'
    assert sensitivity['mean_ci_bps'] == [10., 10.]
    assert 'IID trade bootstrap' in sensitivity['ci_method']
    md = (tmp_path/'a/report.md').read_text()
    assert all(s in md for s in ['By coin', 'By year', 'top_trades', 'without_best_year', sensitivity['ci_method']])
    assert build_report(rows, top_n=2) == build_report(list(reversed(rows)), top_n=2)
    record = json.loads((tmp_path/'a/run_record.json').read_text())
    assert record['command'] == 'report' and record['parameters']['top_n'] == 2


def test_empty_zero_total_negative_total_and_single_year():
    empty = build_report([])
    assert empty['overall']['count'] == 0
    assert empty['without_best_year']['mean_ci_bps'] is None
    rows = ledger()[:2]
    rows[1]['net_bps'] = '-10'
    r = build_report(rows)
    assert r['top_trades']['contribution_ratio'] is None
    assert r['without_best_year']['count'] == 0
    rows[1]['net_bps'] = '-30'
    assert build_report(rows)['top_trades']['contribution_ratio'] == '-0.5'


def test_tie_earliest_year_and_singleton_ci():
    rows = [ledger()[0], ledger()[-1]]
    r = build_report(rows)['without_best_year']
    assert r['removed_year'] == '2022' and r['count'] == 1
    assert r['mean_ci_bps'] is None and r['status'] == 'INSUFFICIENT_OBSERVATIONS'


@pytest.mark.parametrize('change', [dict(net_bps='NaN'), dict(exit_ts=0), dict(coin=''), dict(entry_ts='.5')])
def test_invalid_report_rows(change):
    with pytest.raises(ValueError): build_report([dict(ledger()[0], **change)])


def test_nondegenerate_percentile_ci():
    # Removing 2023 leaves [10,30]; bootstrap means can only be 10,20,30.
    rows = [ledger()[0], ledger()[2], ledger()[-1]]
    rows[-1]['net_bps'] = '30'
    result = build_report(rows)['without_best_year']
    assert result['removed_year'] == '2023'
    assert result['net_bps_mean'] == '20'
    assert result['mean_ci_bps'] == [10., 30.]
