"""P1 execution-cost ledger: read-only, not an admission gate."""
from decimal import Decimal
from pathlib import Path

import pytest

from msa_p1_execution_cost.cost import (
    ASSUMED_RT_BPS,
    analyze_bundle,
    load_ledger_pnl_export,
    quantile,
    render_markdown,
    write_report,
)
from msa_p1_execution_cost.__main__ import main

REPO = Path(__file__).resolve().parents[2]
LEDGER_PNL = REPO / 'htx-trend-python/tests/fixtures/ledger_pnl'
PACKAGE = REPO / 'research/msa_p1_execution_cost'


def _bundle():
    """Hand-checked prices. Positive slippage is adverse to the order.

    ETH LONG A: two entry prints VWAP 10010 on order 10000 → 10 bps,
    close fill 10989 on order 11000 → 10 bps. Fee 2 bps each leg.
    Round trip = 24 bps.
    ETH LONG B: open 20 bps, close 0 bps, fee 2+2 → 24 bps.
    ETH LONG C: open 40 bps, still open, excluded from the 12 bps table.
    BTC SHORT D: 0 slippage, fee 4+4 → 8 bps.
    """
    return {
        'book': 'PAPER',
        'orders': [
            dict(position_id='A', contract_code='ETH-USDT', side='LONG', leg='ENTRY',
                 client_order_id='A-open', signal_price='10000', order_price='10000',
                 order_sent_at='2026-10-01T00:00:00+00:00', cancel_count=1, amend_count=2,
                 contract_size='1'),
            dict(position_id='A', contract_code='ETH-USDT', side='LONG', leg='CLOSE',
                 client_order_id='A-close', signal_price='11000', order_price='11000',
                 order_sent_at='2026-10-01T08:00:00+00:00', cancel_count=0, amend_count=0,
                 contract_size='1'),
            dict(position_id='B', contract_code='ETH-USDT', side='LONG', leg='ENTRY',
                 client_order_id='B-open', signal_price='10000', order_price='10000',
                 order_sent_at='2026-10-02T00:00:00+00:00', cancel_count=0, amend_count=0,
                 contract_size='1'),
            dict(position_id='B', contract_code='ETH-USDT', side='LONG', leg='CLOSE',
                 client_order_id='B-close', signal_price='10020', order_price='10020',
                 order_sent_at='2026-10-02T01:00:00+00:00', cancel_count=0, amend_count=0,
                 contract_size='1'),
            dict(position_id='C', contract_code='ETH-USDT', side='LONG', leg='ENTRY',
                 client_order_id='C-open', signal_price='10000', order_price='10000',
                 order_sent_at='2026-10-03T00:00:00+00:00', cancel_count=0, amend_count=0,
                 contract_size='1'),
            dict(position_id='D', contract_code='BTC-USDT', side='SHORT', leg='ENTRY',
                 client_order_id='D-open', signal_price='50000', order_price='50000',
                 order_sent_at='2026-10-04T00:00:00+00:00', cancel_count=0, amend_count=0,
                 contract_size='1'),
            dict(position_id='D', contract_code='BTC-USDT', side='SHORT', leg='CLOSE',
                 client_order_id='D-close', signal_price='50000', order_price='50000',
                 order_sent_at='2026-10-04T02:00:00+00:00', cancel_count=0, amend_count=0,
                 contract_size='1'),
        ],
        'fills': [
            dict(position_id='A', client_order_id='A-open', fill_id='a1', quantity='1',
                 price='10000', fee='2.002', filled_at='2026-10-01T00:00:01.500000+00:00'),
            dict(position_id='A', client_order_id='A-open', fill_id='a2', quantity='1',
                 price='10020', fee='2.002', filled_at='2026-10-01T00:00:03+00:00'),
            dict(position_id='A', client_order_id='A-close', fill_id='a3', quantity='2',
                 price='10989', fee='4.3956', filled_at='2026-10-01T08:00:00+00:00'),
            dict(position_id='B', client_order_id='B-open', fill_id='b1', quantity='1',
                 price='10020', fee='2.004', filled_at='2026-10-02T00:00:01+00:00'),
            dict(position_id='B', client_order_id='B-close', fill_id='b2', quantity='1',
                 price='10020', fee='2.004', filled_at='2026-10-02T01:00:01+00:00'),
            dict(position_id='C', client_order_id='C-open', fill_id='c1', quantity='1',
                 price='10040', fee='2.008', filled_at='2026-10-03T00:00:01+00:00'),
            dict(position_id='D', client_order_id='D-open', fill_id='d1', quantity='1',
                 price='50000', fee='20', filled_at='2026-10-04T00:00:02+00:00'),
            dict(position_id='D', client_order_id='D-close', fill_id='d2', quantity='1',
                 price='50000', fee='20', filled_at='2026-10-04T02:00:02+00:00'),
        ],
        'funding': [
            dict(position_id='A', event_key='before', occurred_at='2026-10-01T00:00:01.500000+00:00',
                 funding_fee_usdt='9'),
            dict(position_id='A', event_key='mid', occurred_at='2026-10-01T04:00:00+00:00',
                 funding_fee_usdt='1.25'),
            dict(position_id='A', event_key='mid', occurred_at='2026-10-01T04:00:00+00:00',
                 funding_fee_usdt='1.25'),
            dict(position_id='A', event_key='at-close', occurred_at='2026-10-01T08:00:00+00:00',
                 funding_fee_usdt='-0.25'),
            dict(position_id='A', event_key='after', occurred_at='2026-10-01T12:00:00+00:00',
                 funding_fee_usdt='8'),
        ],
        'order_events': [
            dict(position_id='A', client_order_id='A-open', event_key='extra-cancel', event_type='CANCEL'),
        ],
    }


def test_quantile_type7_matches_hand_calculation():
    values = [Decimal(x) for x in (1, 2, 3, 4)]
    assert quantile(values, Decimal('0.5')) == Decimal('2.5')
    assert quantile(values, Decimal('0.75')) == Decimal('3.25')
    assert quantile(values, Decimal('0.9')) == Decimal('3.7')


def test_leg_slippage_fee_latency_and_partials_are_exact():
    report = analyze_bundle(_bundle())
    legs = {row['client_order_id']: row for row in report['legs']}
    open_a = legs['A-open']
    assert open_a['fill_vwap'] == Decimal('10010')
    assert open_a['slippage_vs_order_bps'] == Decimal('10')
    assert open_a['slippage_vs_signal_bps'] == Decimal('10')
    assert open_a['fee_usdt'] == Decimal('4.004')
    assert open_a['fee_bps'] == Decimal('2')
    assert open_a['partial_fill_count'] == 1
    assert open_a['order_to_first_fill_ms'] == Decimal('1500')
    assert open_a['cancel_count'] == 1
    assert open_a['amend_count'] == 2
    assert legs['A-close']['slippage_vs_order_bps'] == Decimal('10')
    assert legs['A-close']['fee_bps'] == Decimal('2')
    short_open = dict(
        position_id='S', contract_code='ETH-USDT', side='SHORT', leg='ENTRY',
        client_order_id='S-open', signal_price='100', order_price='100',
        order_sent_at='2026-10-05T00:00:00+00:00', cancel_count=0, amend_count=0,
        contract_size='1')
    worse = analyze_bundle({'book': 'PAPER', 'orders': [short_open], 'fills': [
        dict(position_id='S', client_order_id='S-open', fill_id='s1', quantity='1',
             price='99', fee='0', filled_at='2026-10-05T00:00:01+00:00')]})
    assert worse['legs'][0]['slippage_vs_order_bps'] == Decimal('100')


def test_funding_sums_holding_window_once_and_distribution_matches_12bps():
    report = analyze_bundle(_bundle())
    funding = {row['position_id']: row for row in report['funding']}
    assert funding['A']['funding_paid_usdt'] == Decimal('1.25')
    assert funding['A']['funding_received_usdt'] == Decimal('0.25')
    assert funding['A']['funding_net_usdt'] == Decimal('1.00')
    assert funding['A']['event_count'] == 2
    assert funding['A']['position_open'] is False
    assert funding['C']['position_open'] is True
    dist = {(row['contract'], row['side'], row['phase'], row['metric']): row
            for row in report['distribution']}
    eth_open = dist[('ETH-USDT', 'LONG', 'OPEN', 'slippage_vs_order_bps')]
    assert eth_open['n'] == 3
    assert eth_open['median'] == Decimal('20')
    assert eth_open['p75'] == Decimal('30')
    assert eth_open['p90'] == Decimal('36')
    compared = {row['scope'] + ':' + row['key']: row for row in report['comparison']}
    assert compared['ALL:*']['n'] == 3
    assert compared['ALL:*']['median_rt_bps'] == Decimal('24')
    assert compared['ALL:*']['assumed_rt_bps'] == ASSUMED_RT_BPS == Decimal('12')
    assert compared['ALL:*']['median_minus_assumed_bps'] == Decimal('12')
    assert compared['ALL:*']['excluded_positions'] == 1
    assert compared['SIDE:SHORT']['median_rt_bps'] == Decimal('8')
    assert 'C' not in {row['position_id'] for row in report['round_trips']}


def test_duplicate_fill_is_rejected_and_text_states_the_boundary():
    bundle = _bundle()
    bundle['fills'].append(dict(bundle['fills'][0]))
    with pytest.raises(ValueError, match='重复成交'):
        analyze_bundle(bundle)
    text = render_markdown(analyze_bundle(_bundle()))
    assert '仅用于校准回测成本' in text
    assert '不改变开单逻辑' in text
    assert '不当作过闸证据' in text
    assert '不回写已封存目标净效应' in text
    assert '12' in text


def test_real_ledger_pnl_export_is_read_only_and_does_not_invent_prices(tmp_path):
    before = {path: path.stat().st_mtime_ns for path in LEDGER_PNL.iterdir()}
    report = load_ledger_pnl_export(LEDGER_PNL)
    after = {path: path.stat().st_mtime_ns for path in LEDGER_PNL.iterdir()}
    assert before == after
    legs = {(row['position_id'], row['phase']): row for row in report['legs']}
    entry = legs[('324', 'OPEN')]
    close = legs[('5800', 'CLOSE')]
    assert entry['book'] == 'PAPER'
    assert entry['contract'] == 'ETH-USDT'
    assert entry['fee_usdt'] == Decimal('0.06717228')
    assert entry['slippage_vs_order_bps'] is None
    assert entry['signal_price'] is None
    assert '缺下单价' in entry['coverage']
    assert close['side'] == 'SHORT'
    assert close['partial_fill_count'] == 1
    assert close['fee_usdt'] == Decimal('0.06678020')
    assert close['fill_vwap'] == Decimal('16695.05') / Decimal('7')
    assert all(row['event_count'] == 0 or row['funding_net_usdt'] == 0
               for row in report['funding'])
    write_report(report, tmp_path)
    assert (tmp_path / 'legs.csv').is_file()
    assert '仅用于校准回测成本' in (tmp_path / 'cost.md').read_text(encoding='utf-8')


def test_assumed_12bps_is_the_research_default_and_package_is_analysis_only():
    config = (REPO / 'research/coinresearch/config.py').read_text(encoding='utf-8')
    assert 'rt_cost: float = 12' in config
    source = '\n'.join(path.read_text(encoding='utf-8') for path in PACKAGE.rglob('*.py'))
    for banned in ('htx_trend', 'INSERT ', 'UPDATE ', 'place_order', 'systemd'):
        assert banned not in source


def test_cli_writes_fixture_and_real_samples(tmp_path):
    fixture = PACKAGE / 'fixtures/synthetic_bundle.json'
    code = main(['--bundle', str(fixture), '--out', str(tmp_path / 'fixture')])
    assert code == 0
    assert (tmp_path / 'fixture/comparison.csv').is_file()
    code = main(['--ledger-pnl-dir', str(LEDGER_PNL), '--out', str(tmp_path / 'real')])
    assert code == 0
    assert 'ETH-USDT' in (tmp_path / 'real/legs.csv').read_text(encoding='utf-8')
