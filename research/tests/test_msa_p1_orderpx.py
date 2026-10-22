"""逐笔下单价账：只读映射，缺失不回填，S3 才对 12bps。"""
import json
from decimal import Decimal
from pathlib import Path

from msa_p1_execution_cost.cost import analyze_bundle, load_ledger_pnl_export, write_report
from msa_p1_execution_cost.orderpx import (
    CI_METHOD,
    CONCLUSION_INSUFFICIENT,
    CONCLUSION_SINGLE_CLUSTER,
    MIN_N_FOR_INFERENCE,
    cluster_mean_ci,
)

REPO = Path(__file__).resolve().parents[2]
LEDGER_PNL = REPO / 'htx-trend-python/tests/fixtures/ledger_pnl'


def _order(**overrides):
    row = dict(
        position_id='P', contract_code='ETH-USDT', side='LONG', leg='ENTRY',
        client_order_id='P-open', signal_price='10000', order_price='10010',
        order_px_source='limit', order_type='LIMIT', order_qty='2',
        signal_ts='2026-10-01T00:00:00+00:00', order_sent_at='2026-10-01T00:00:01+00:00',
        next_bar_open='10000', cancel_count=0, amend_count=1, contract_size='1', book='PAPER',
    )
    row.update(overrides)
    return row


def _fill(**overrides):
    row = dict(
        position_id='P', client_order_id='P-open', fill_id='f1', quantity='1',
        price='10020', fee='1', filled_at='2026-10-01T00:00:02+00:00',
    )
    row.update(overrides)
    return row


def _bundle(orders, fills, funding=None, include_funding=True):
    bundle = {'book': 'PAPER', 'orders': orders, 'fills': fills}
    if include_funding:
        bundle['funding'] = [] if funding is None else funding
    return bundle


def _rows(bundle):
    report = analyze_bundle(bundle)
    return {row['client_order_id']: row for row in report['orderpx']}, report


def _priced_legs(n_eth, eth_fill, n_btc, btc_fill, book='PAPER'):
    orders, fills = [], []
    serial = 0
    for contract, count, fill in (
            ('ETH-USDT', n_eth, eth_fill),
            ('BTC-USDT', n_btc, btc_fill)):
        for _ in range(count):
            serial += 1
            oid = f'{book}-{serial}'
            orders.append(dict(
                book=book, position_id=oid, contract_code=contract, side='LONG', leg='ENTRY',
                client_order_id=oid, signal_price='10000', order_price='10000',
                order_px_source='limit', order_type='LIMIT', order_qty='1',
                signal_ts='2026-10-01T00:00:00+00:00',
                order_sent_at='2026-10-01T00:00:01+00:00',
                next_bar_open='10000', cancel_count=0, amend_count=0, contract_size='1',
            ))
            fills.append(dict(
                position_id=oid, client_order_id=oid, fill_id=oid, quantity='1',
                price=fill, fee='0', filled_at='2026-10-01T00:00:02+00:00',
            ))
    return _bundle(orders, fills)


def test_order_px_source_maps_limit_mid_and_last():
    orders = [
        _order(client_order_id='limit', order_px_source='limit', order_type='LIMIT',
               order_price='10010', mid='9999', signal_price='10000'),
        _order(client_order_id='mid', position_id='M', order_px_source='mid', order_type='MARKET',
               order_price='9999', mid='10005', signal_price='10000'),
        _order(client_order_id='last', position_id='L', order_px_source='last', order_type='MARKET',
               order_price=None, last='10008', mid=None, signal_price='10000'),
        _order(client_order_id='infer-mid', position_id='I', order_px_source=None, order_type='MARKET',
               order_price='9999', mid='10007', signal_price='10000'),
    ]
    fills = [
        _fill(client_order_id='limit', fill_id='fl', price='10020'),
        _fill(client_order_id='mid', position_id='M', fill_id='fm', price='10015'),
        _fill(client_order_id='last', position_id='L', fill_id='fa', price='10030'),
        _fill(client_order_id='infer-mid', position_id='I', fill_id='fi', price='10040'),
    ]
    rows, _ = _rows(_bundle(orders, fills))
    assert rows['limit']['order_px'] == Decimal('10010')
    assert rows['limit']['order_px_source'] == 'limit'
    assert rows['limit']['signal_px'] == Decimal('10000')
    assert rows['mid']['order_px'] == Decimal('10005')
    assert rows['mid']['order_px_source'] == 'mid'
    assert rows['mid']['order_px'] != rows['mid']['fill_vwap']
    assert rows['mid']['order_px'] != rows['mid']['signal_px']
    assert rows['last']['order_px'] == Decimal('10008')
    assert rows['last']['order_px_source'] == 'last'
    assert rows['infer-mid']['order_px'] == Decimal('10007')
    assert rows['infer-mid']['order_px_source'] == 'mid'


def test_missing_prices_are_flagged_and_never_backfilled():
    orders = [
        _order(client_order_id='bare', order_price=None, order_px_source=None, order_type=None,
               mid=None, last=None, signal_price='10000', next_bar_open=None,
               order_qty=None, signal_ts=None, order_sent_at=None, cancel_count=None, amend_count=None),
        _order(client_order_id='legacy', order_px_source=None, order_type=None, order_price='10010',
               mid=None, last=None),
        _order(client_order_id='bad-source', order_px_source='fill', order_price='10020',
               signal_price='10000'),
        _order(client_order_id='limit-gap', order_px_source='limit', order_price=None, limit_price=None,
               signal_price='10000', mid='10000'),
    ]
    fills = [
        _fill(client_order_id='bare', fill_id='b1', price='10100'),
        _fill(client_order_id='legacy', fill_id='g1', price='10020'),
        _fill(client_order_id='bad-source', fill_id='d1', price='10020'),
        _fill(client_order_id='limit-gap', fill_id='e1', price='10020'),
    ]
    bundle = _bundle(orders, fills, include_funding=False)
    rows, _ = _rows(bundle)
    bare = rows['bare']
    assert bare['order_px'] is None
    assert bare['signal_px'] == Decimal('10000')
    assert bare['fill_vwap'] == Decimal('10100')
    assert bare['s1_bps'] is None and bare['s2_bps'] is None and bare['s3_bps'] is None
    assert bare['total_cost_bps'] is None
    for flag in (
            'missing_order_px', 'missing_order_px_source', 'missing_next_bar_open',
            'missing_order_qty', 'missing_partial_fill_ratio', 'missing_full_fill_ts',
            'missing_signal_ts', 'missing_order_ts', 'missing_cancel_count',
            'missing_amend_count', 'missing_funding'):
        assert flag in bare['missing_flags']
    assert bare['funding_paid_usdt'] is None and bare['funding_received_usdt'] is None
    legacy = rows['legacy']
    assert legacy['order_px'] == Decimal('10010')
    assert legacy['order_px_source'] is None
    assert 'missing_order_px_source' in legacy['missing_flags']
    assert 'missing_order_px' not in legacy['missing_flags']
    refused = rows['bad-source']
    assert refused['order_px'] is None
    assert refused['order_px'] != refused['fill_vwap']
    assert 'missing_order_px' in refused['missing_flags']
    gap = rows['limit-gap']
    assert gap['order_px'] is None
    assert gap['order_px_source'] == 'limit'
    assert gap['order_px'] != gap['signal_px']
    assert 'missing_order_px' in gap['missing_flags']


def test_s1_s2_s3_use_adverse_direction_and_only_s3_enters_cost():
    orders = [
        _order(client_order_id='long', signal_price='10000', order_price='10010',
               next_bar_open='10000', order_qty='1'),
        _order(client_order_id='short', position_id='S', side='SHORT', signal_price='200',
               order_price='190', next_bar_open='200', order_qty='1', contract_code='BTC-USDT'),
        _order(client_order_id='cover', position_id='C', side='SHORT', leg='CLOSE',
               signal_price='100', order_price='100', next_bar_open='100', order_qty='1'),
        _order(client_order_id='s1-only', position_id='K', signal_price='100', order_price='110',
               next_bar_open='110', order_qty='1'),
    ]
    fills = [
        _fill(client_order_id='long', fill_id='l1', quantity='1', price='10020', fee='1.002'),
        _fill(client_order_id='short', position_id='S', fill_id='s1', quantity='1', price='180', fee='0'),
        _fill(client_order_id='cover', position_id='C', fill_id='c1', quantity='1', price='101', fee='0'),
        _fill(client_order_id='s1-only', position_id='K', fill_id='k1', quantity='1', price='110', fee='0'),
    ]
    rows, _ = _rows(_bundle(orders, fills))
    assert rows['long']['s1_bps'] == Decimal('10')
    assert rows['long']['s2_bps'] == Decimal(10000) / Decimal(1001)
    assert rows['long']['s3_bps'] == Decimal('20')
    assert rows['long']['fee_bps'] == Decimal('1')
    assert rows['long']['total_cost_bps'] == Decimal('21')
    assert rows['short']['s1_bps'] == Decimal('500')
    assert rows['short']['s2_bps'] == Decimal(10000) / Decimal(19)
    assert rows['short']['s3_bps'] == Decimal('1000')
    assert rows['cover']['s2_bps'] == Decimal('100')
    assert rows['cover']['s3_bps'] == Decimal('100')
    assert rows['s1-only']['s1_bps'] == Decimal('1000')
    assert rows['s1-only']['s2_bps'] == Decimal('0')
    assert rows['s1-only']['s3_bps'] == Decimal('0')
    assert rows['s1-only']['total_cost_bps'] == Decimal('0')
    assert rows['s1-only']['total_cost_minus_assumed_bps'] == Decimal('-12')


def test_partial_ratio_latency_segments_and_position_funding():
    orders = [
        _order(client_order_id='part', order_qty='4'),
        _order(client_order_id='full', position_id='F', order_qty='2',
               signal_ts='2026-10-01T00:00:00+00:00',
               order_sent_at='2026-10-01T00:00:01+00:00'),
    ]
    fills = [
        _fill(client_order_id='part', fill_id='p1', quantity='1',
              filled_at='2026-10-01T00:00:02+00:00'),
        _fill(client_order_id='part', fill_id='p2', quantity='1', price='10020',
              filled_at='2026-10-01T00:00:04+00:00'),
        _fill(client_order_id='full', position_id='F', fill_id='u1', quantity='1',
              filled_at='2026-10-01T00:00:02+00:00'),
        _fill(client_order_id='full', position_id='F', fill_id='u2', quantity='1', price='10020',
              filled_at='2026-10-01T00:00:04+00:00'),
    ]
    funding = [
        dict(position_id='P', event_key='once', occurred_at='2026-10-01T00:00:03+00:00',
             funding_fee_usdt='1.5'),
        dict(position_id='P', event_key='once', occurred_at='2026-10-01T00:00:03+00:00',
             funding_fee_usdt='9'),
        dict(position_id='P', event_key='recv', occurred_at='2026-10-01T00:00:03.500000+00:00',
             funding_fee_usdt='-0.5'),
    ]
    rows, _ = _rows(_bundle(orders, fills, funding=funding))
    part = rows['part']
    assert part['fill_qty'] == Decimal('2')
    assert part['order_qty'] == Decimal('4')
    assert part['partial_fill_ratio'] == Decimal('0.5')
    assert part['full_fill_ts'] is None
    assert 'missing_full_fill_ts' in part['missing_flags']
    assert part['signal_ts'] == '2026-10-01T00:00:00+00:00'
    assert part['order_ts'] == '2026-10-01T00:00:01+00:00'
    assert part['first_fill_ts'] == '2026-10-01T00:00:02+00:00'
    assert part['latency_signal_to_order_ms'] == Decimal('1000')
    assert part['latency_order_to_first_fill_ms'] == Decimal('1000')
    assert part['latency_first_to_full_fill_ms'] is None
    assert part['cancel_count'] == 0 and part['amend_count'] == 1
    assert part['funding_paid_usdt'] == Decimal('1.5')
    assert part['funding_received_usdt'] == Decimal('0.5')
    assert 'missing_funding' not in part['missing_flags']
    full = rows['full']
    assert full['partial_fill_ratio'] == Decimal('1')
    assert full['full_fill_ts'] == '2026-10-01T00:00:04+00:00'
    assert full['latency_first_to_full_fill_ms'] == Decimal('2000')
    assert 'missing_full_fill_ts' not in full['missing_flags']
    assert full['funding_paid_usdt'] == Decimal('0')
    assert full['funding_received_usdt'] == Decimal('0')


def test_cluster_mean_ci_matches_hand_calculation():
    result = cluster_mean_ci(
        [Decimal('1'), Decimal('3'), Decimal('5')],
        ['ETH', 'ETH', 'BTC'],
    )
    assert result['n'] == 3
    assert result['g'] == 2
    assert result['mean'] == Decimal('3')
    assert result['se'] == Decimal('4') / Decimal('3')
    half = Decimal('1.96') * (Decimal('4') / Decimal('3'))
    assert result['ci_low'] == Decimal('3') - half
    assert result['ci_high'] == Decimal('3') + half
    assert cluster_mean_ci([Decimal('1'), Decimal('1')], ['ETH', 'ETH']) is None


def _summary_map(bundle):
    report = analyze_bundle(bundle)
    return {(row['book'], row['contract']): row for row in report['orderpx_summary']}


def test_summary_below_200_is_descriptive_only():
    assert MIN_N_FOR_INFERENCE == 200
    summary = _summary_map(_priced_legs(100, '10010', 99, '10030'))
    pooled = summary[('PAPER', '*')]
    assert pooled['n'] == 199
    assert pooled['n_total_cost'] == 199
    assert pooled['mean_total_cost_bps'] is None
    assert pooled['total_cost_ci_low_bps'] is None
    assert pooled['total_cost_ci_high_bps'] is None
    assert pooled['conclusion'] == CONCLUSION_INSUFFICIENT == '样本不足不下结论'
    assert pooled['total_cost_p50'] is not None
    assert pooled['s1_p50'] == Decimal('0')
    assert pooled['s3_p50'] is not None
    eth = summary[('PAPER', 'ETH-USDT')]
    assert eth['conclusion'] == CONCLUSION_INSUFFICIENT
    assert eth['mean_total_cost_bps'] is None
    assert eth['s3_p50'] == Decimal('10')


def test_summary_at_200_emits_cluster_ci_and_single_cluster_does_not():
    summary = _summary_map(_priced_legs(100, '10010', 100, '10030'))
    pooled = summary[('PAPER', '*')]
    assert pooled['n'] == 200
    assert pooled['g_clusters'] == 2
    assert pooled['mean_total_cost_bps'] == Decimal('20')
    assert pooled['total_cost_se_bps'] == Decimal('10')
    assert pooled['total_cost_ci_low_bps'] == Decimal('20') - Decimal('1.96') * Decimal('10')
    assert pooled['total_cost_ci_high_bps'] == Decimal('20') + Decimal('1.96') * Decimal('10')
    assert pooled['total_cost_median_minus_assumed_bps'] == Decimal('8')
    assert pooled['assumed_rt_bps'] == Decimal('12')
    assert pooled['ci_method'] == CI_METHOD
    assert pooled['conclusion'] == ''
    assert 'S3' in CI_METHOD
    eth = summary[('PAPER', 'ETH-USDT')]
    assert eth['n'] == 100
    assert eth['conclusion'] == CONCLUSION_INSUFFICIENT
    assert eth['mean_total_cost_bps'] is None
    lone = _summary_map(_priced_legs(200, '10010', 0, '10030'))
    single = lone[('PAPER', '*')]
    assert single['n'] == 200
    assert single['g_clusters'] == 1
    assert single['mean_total_cost_bps'] is None
    assert single['total_cost_se_bps'] is None
    assert single['conclusion'] == CONCLUSION_SINGLE_CLUSTER
    assert single['total_cost_p50'] == Decimal('10')
    books = _summary_map(_priced_legs(100, '10010', 100, '10030', book='LIVE'))
    assert ('LIVE', '*') in books
    assert ('PAPER', '*') not in books


def test_real_ledger_pnl_orderpx_stays_missing_and_read_only(tmp_path):
    before = {path: path.stat().st_mtime_ns for path in LEDGER_PNL.iterdir()}
    report = load_ledger_pnl_export(LEDGER_PNL)
    after = {path: path.stat().st_mtime_ns for path in LEDGER_PNL.iterdir()}
    assert before == after
    rows = {row['client_order_id']: row for row in report['orderpx']}
    close = rows['5800:CLOSE']
    assert close['book'] == 'PAPER'
    assert close['order_px'] is None
    assert close['fill_vwap'] is not None
    assert close['s1_bps'] is None and close['s2_bps'] is None and close['s3_bps'] is None
    assert close['funding_paid_usdt'] is None
    for flag in ('missing_order_px', 'missing_order_px_source', 'missing_signal_px',
                 'missing_next_bar_open', 'missing_funding', 'missing_order_qty'):
        assert flag in close['missing_flags']
    assert close['first_fill_ts'] is not None
    pooled = {(row['book'], row['contract']): row for row in report['orderpx_summary']}
    assert pooled[('PAPER', '*')]['conclusion'] == CONCLUSION_INSUFFICIENT
    assert pooled[('PAPER', '*')]['n'] < 200
    write_report(report, tmp_path)
    header = (tmp_path / 'orderpx.csv').read_text(encoding='utf-8').splitlines()[0]
    assert 'order_px_source' in header and 's1_bps' in header and 'missing_flags' in header
    payload = [json.loads(line) for line in (tmp_path / 'orderpx.jsonl').read_text(encoding='utf-8').splitlines()]
    matched = next(row for row in payload if row['client_order_id'] == '5800:CLOSE')
    assert matched['order_px'] is None
    assert 'missing_order_px' in matched['missing_flags']
    assert '样本不足不下结论' in (tmp_path / 'orderpx_summary.csv').read_text(encoding='utf-8')


def test_optional_exported_order_metadata_and_historical_nulls(tmp_path):
    import shutil
    for name in ('positions.json', 'fills.json', 'trades_closed_window.csv'):
        shutil.copyfile(LEDGER_PNL / name, tmp_path / name)
    fills = json.loads((tmp_path / 'fills.json').read_text())
    first = fills[0]
    row = dict(position_id=first['position_id'], leg=first['leg'], client_order_id='actual-order',
               signal_px='2400', signal_ts='2026-10-19T19:15:00+00:00', order_px='2401',
               order_px_source='mid', order_ts='2026-10-19T19:15:01+00:00', request_volume=7,
               cancel_count=None, amend_count=None)
    (tmp_path / 'orders.json').write_text(json.dumps([row]))
    report = load_ledger_pnl_export(tmp_path)
    observed = next(r for r in report['orderpx'] if r['client_order_id'] == 'actual-order')
    assert observed['order_px'] == Decimal('2401')
    assert observed['signal_px'] == Decimal('2400')
    assert observed['order_px_source'] == 'mid'
    assert observed['order_qty'] == 7
    assert observed['cancel_count'] is None and observed['amend_count'] is None
    assert 'missing_cancel_count' in observed['missing_flags']
    row.update(order_px=None, order_px_source=None, average_price='999', signal_px='999')
    (tmp_path / 'orders.json').write_text(json.dumps([row]))
    observed = next(r for r in load_ledger_pnl_export(tmp_path)['orderpx'] if r['client_order_id'] == 'actual-order')
    assert observed['order_px'] is None
    assert 'missing_order_px' in observed['missing_flags']
    # Multiple orders on a historical leg cannot be attributed without order identity.
    (tmp_path / 'orders.json').write_text(json.dumps([row, dict(row, client_order_id='second')]))
    assert all(r['order_px'] is None for r in load_ledger_pnl_export(tmp_path)['orderpx'])
