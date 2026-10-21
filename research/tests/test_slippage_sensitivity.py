import csv
import hashlib
from decimal import Decimal
import pytest
from coinresearch.slippage_sensitivity import sensitivity, write_report


def make_csv(path, source):
    rows = []
    for side, exit_, gross in [('LONG', '102', '4'), ('SHORT', '99', '2')]:
        r = dict(entry_price='100', exit_price=exit_, qty='2', contract_size='1', side=side)
        if source == 'paper':
            r.update(gross=gross, fee='1', net=str(Decimal(gross)-1), slippage='0',
                     opened_at_cst='2026-10-20', closed_at_cst='2026-10-21')
        else:
            r.update(gross_usdt=gross, fee_usdt='1', funding_usdt='.5',
                     net_usdt_baseline=str(Decimal(gross)-Decimal('1.5')),
                     entry_time_utc='2024-10-12', exit_time_utc='2024-10-13')
        rows.append(r)
    with path.open('w') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def test_cost_and_reproducibility(tmp_path):
    for source, baseline in [('phase1', '3'), ('paper', '4')]:
        p = tmp_path/f'{source}.csv'
        make_csv(p, source)
        result = sensitivity(p, source)
        assert result['input_sha256'] == hashlib.sha256(p.read_bytes()).hexdigest()
        for row in result['scenarios']:
            assert Decimal(row['net_usdt']) == Decimal(baseline)-Decimal('.08')*row['extra_one_way_bp']
            assert row['wins'] == 2
    for folder in ('a', 'b'):
        write_report(tmp_path/'phase1.csv', tmp_path/'paper.csv', tmp_path/folder)
    for name in ('slippage.json', 'slippage.md'):
        assert (tmp_path/'a'/name).read_bytes() == (tmp_path/'b'/name).read_bytes()


def test_bad_baseline(tmp_path):
    p = tmp_path/'paper.csv'
    make_csv(p, 'paper')
    p.write_text(p.read_text().replace('102', '103'))
    with pytest.raises(ValueError, match='reconcile'):
        sensitivity(p, 'paper')


def test_win_crosses_zero(tmp_path):
    p = tmp_path/'paper.csv'
    make_csv(p, 'paper')
    with p.open() as f:
        rows = list(csv.DictReader(f))
    rows[0].update(exit_price='100.51', gross='1.02', net='.02')
    rows[1].update(exit_price='99.5', gross='1', net='0')
    with p.open('w') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    s = sensitivity(p, 'paper')['scenarios']
    assert s[0]['wins'] == 1
    assert s[1]['wins'] == 0
    assert Decimal(s[1]['net_usdt']) < Decimal(s[0]['net_usdt'])
