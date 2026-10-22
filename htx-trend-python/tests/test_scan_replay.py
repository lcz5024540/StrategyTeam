"""End-to-end fixed evidence replay, including offline and negative checks."""
import hashlib
import json
from pathlib import Path
import shutil
import socket

import pytest

from htx_trend.scan_replay import Assumptions, OfflineMarket, compare, epoch, load_inputs, replay

INPUTS = Path(__file__).parents[1] / 'replay_inputs' / '20261021'


@pytest.fixture(scope='module')
def report():
    return replay(INPUTS)


@pytest.mark.parametrize('input_name', ['klines.csv', 'trades_closed_window.csv'])
def test_locked_hash_rejects_modified_input_and_forged_manifest(tmp_path, input_name):
    for name in ('klines.csv', 'trades_closed_window.csv', 'MANIFEST.json'):
        shutil.copyfile(INPUTS / name, tmp_path / name)
    target = tmp_path / input_name
    target.write_bytes(target.read_bytes() + b'\n')
    with pytest.raises(ValueError, match='SHA256 mismatch'):
        replay(tmp_path)
    manifest = json.loads((tmp_path / 'MANIFEST.json').read_text())
    manifest['sha256'][input_name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (tmp_path / 'MANIFEST.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match='SHA256 mismatch'):
        replay(tmp_path)


def test_deterministic_offline_and_ignores_ambient_settings(report, monkeypatch):
    import pymysql
    from htx_trend.paper_worker import PaperFileLedger

    def forbidden(*args, **kwargs):
        raise AssertionError('offline replay attempted external IO')

    monkeypatch.setattr(socket, 'create_connection', forbidden)
    monkeypatch.setattr(socket.socket, 'connect', forbidden)
    monkeypatch.setattr(pymysql, 'connect', forbidden)
    monkeypatch.setattr(PaperFileLedger, '__init__', forbidden)
    monkeypatch.setattr(Path, 'write_text', forbidden)
    monkeypatch.setenv('HTX_TREND_15M_MIN_ADX', '99999')
    monkeypatch.setenv('HTX_TREND_MAX_ENTRY_SIGNAL_AGE_SECONDS', '1')
    assert replay(INPUTS) == report


def test_real_trade_alignment_and_reverse_evidence(report):
    assert report['summary']['live_count'] == 12
    assert report['summary']['entry_matches'] == 12
    assert report['summary']['extra_count'] == 0
    assert report['summary']['missing_count'] == 0
    eth = next(a for a in report['alignment'] if '93cef710' in a['live']['position_key'])
    assert eth['entry_match'] and eth['replay']['side'] == 'LONG'
    assert report['header']['execution_config']['require_execution_facts'] is False
    matches = [a for a in report['alignment'] if a['entry_match']]
    assert matches
    actual = next(a for a in matches if a['live']['opened_at_cst'] == '2026-10-20 03:15:40')
    assert actual['replay']['model'] == 'EMA_MOMENTUM_CONTINUATION'
    assert actual['replay']['exit_reason'] == 'trend-weakened-protection-breach'
    assert actual['signal_bar_open_cst'] == '2026-10-20T03:00:00+08:00'
    assert actual['bar_evidence']['signal_age_seconds'] == 30
    assert not report['missing'] and not report['extras']
    assert all('replay_observation' in d for t in report['trades'] for d in t['exit_decisions'])


def test_reverse_comparison_detects_omission_extra_and_wrong_model(report):
    live = [a['live'] for a in report['alignment']]
    aligned, extras, missing = compare(live, [], report['bars'])
    assert len(missing) == len(live) and not extras and not any(a['match'] for a in aligned)
    aligned, extras, missing = compare([], report['trades'], report['bars'])
    assert not aligned and not missing and len(extras) == len(report['trades'])
    a = next(a for a in report['alignment'] if a['entry_match'])
    changed = {**a['replay'], 'model': 'WRONG_MODEL'}
    aligned, extras, missing = compare([a['live']], [changed], report['bars'])
    assert not aligned[0]['entry_match'] and not aligned[0]['match']
    assert any('MODEL_ROUTING' in reason for reason in aligned[0]['reasons'])
    assert not extras and not missing  # Same entry bucket exists; it is a mismatch.


def test_no_lookahead_and_hourly_close_grace():
    candles, _, _ = load_inputs(INPUTS)
    market = OfflineMarket(candles, Assumptions())
    for time in ('2026-10-20 09:00:09', '2026-10-20 09:00:30', '2026-10-20 09:15:30'):
        now = epoch(time)
        for period, seconds in (('15min', 900), ('60min', 3600)):
            window = market.kline_window('BTC-USDT', period, 160, now_epoch=now)
            assert window.is_complete
            assert all(c.open_time + seconds <= now - 10 for c in window.candles)


def test_real_stale_gate_is_not_bypassed():
    stale = replay(INPUTS, Assumptions(scan_delay_seconds=601,
        start_cst='2026-10-20 03:00:00', end_cst='2026-10-20 03:30:59'))
    gates = [g for b in stale['bars'] for g in b['gates']]
    assert gates and not stale['trades']
    assert all(g['stage'] == 'DATA_FRESHNESS' for g in gates)
    assert all(any(c['condition'] == 'data.signal_freshness' and c['status'] == 'FAIL'
                   for c in g['conditions']) for g in gates)
    assert all('risk.total_margin' in g['not_evaluated_or_disabled'] for g in gates)


def test_worker_eth_seven_contracts_blocks_btc_two_before_eth_exit(monkeypatch):
    """Counterfactual fixture verifies the real worker, not a forced replay result."""
    from datetime import datetime, UTC
    from decimal import Decimal as D
    from htx_trend.ledger import Position
    from htx_trend.scan_replay import MemoryLedger

    original = MemoryLedger.__init__

    def seed(self):
        original(self)
        self.open_position(Position('eth-fixture', 'offline', 'ETH-USDT', 'SHORT',
            7, D('2400.74'), D('0'), 'OPEN', datetime(2026, 10, 20, 0, 15, tzinfo=UTC)))
        self.save_position_context('eth-fixture', dict(model='EMA_MOMENTUM_CONTINUATION',
            contract_size='0.01', initial_stop='2407.64', invalidation='2407.64',
            structure_target='2386.58', highest='2401.76', lowest='2391.76',
            protection='2398.26', structure_key='', entry_fee='0.06722072',
            trailing_armed_at='2026-10-20T00:30:00+00:00',
            trailing_arm_reason='RISK_REWARD_MILESTONE', max_favorable_r='1.4'))

    monkeypatch.setattr(MemoryLedger, '__init__', seed)
    r = replay(INPUTS, Assumptions(start_cst='2026-10-20 09:00:00', end_cst='2026-10-20 09:00:59'))
    btc = next(b for b in r['bars'] if b['contract'] == 'BTC-USDT')
    gate = next(g for g in btc['gates'] if g['candidate']['model'] == 'EMA_MOMENTUM_CONTINUATION')
    assert gate['size_plan']['contracts'] == 2
    assert not gate['allowed'] and gate['stage'] == 'RISK_LIMIT'
    assert gate['reason'] == 'total margin exposure exceeds 10% capital'
    assert D(gate['execution_facts']['used_margin_usdt']) == D('16.80518')
    assert gate['positions_before_scan'][0]['quantity'] == 7
    assert any(c['condition'] == 'risk.total_margin' and c['status'] == 'FAIL' for c in gate['conditions'])
    assert not any(t['contract'] == 'BTC-USDT' for t in r['trades'])
    # ETH management follows BTC scan in the same real tick.
    assert r['trades'][0]['status'] == 'CLOSED'


def test_forming_book_preserves_eth_margin_until_real_worker_exit(report):
    eth = next(t for t in report['trades'] if t['opened_at_cst'].startswith('2026-10-20T08:15'))
    assert eth['closed_at_cst'] == '2026-10-20T09:00:30+08:00'
    assert eth['exit_reason'] == 'armed-protection-breach-atr-impulse'
    assert eth['exit_legs'][0]['quantity'] == 7
    gate = next(b for b in report['bars'] if b['contract'] == 'BTC-USDT'
                and b['at_cst'].startswith('2026-10-20T09:00'))['gates'][0]
    assert gate['positions_before_scan'][0]['contract'] == 'ETH-USDT'
    assert gate['positions_before_scan'][0]['quantity'] == 7
    from decimal import Decimal as D
    assert D(gate['execution_facts']['used_margin_usdt']) == D('16.80819')
    assert not gate['allowed'] and gate['stage'] == 'RISK_LIMIT'
    assert any(c['condition'] == 'risk.total_margin' and c['status'] == 'FAIL'
               for c in gate['conditions'])


def test_position_alignment_checks_each_exit_leg_and_missing_evidence(report):
    from copy import deepcopy
    a = next(a for a in report['alignment'] if '36733c1f' in a['live']['position_key'])
    assert a['entry_match'] and not a['exit_match']
    assert len(a['exit_leg_alignment']) == 2
    assert [x['quantity'] for x in a['replay']['exit_legs']] == [1, 1]
    assert any(reason.startswith('EXIT_LEGS_MISMATCH') for reason in a['reasons'])
    assert [(leg['at_cst'], leg['quantity'], leg['reason']) for leg in
            json.loads(a['live']['exit_legs_json'])] == [
        ('2026-10-21 09:00:21', 1, 'profit-at-risk-with-deteriorating-continuation'),
        ('2026-10-21 09:15:54', 1, 'trend-weakened-protection-breach'),
    ]
    t = deepcopy(a['replay'])
    t['exit_legs'] = json.loads(a['live']['exit_legs_json'])
    aligned, extras, missing = compare([a['live']], [t], report['bars'])
    assert aligned[0]['match'] and not extras and not missing
    for field, value in (('quantity', 2), ('reason', 'wrong'), ('at_cst', '2026-10-21 08:45:00')):
        changed = deepcopy(t)
        changed['exit_legs'][0][field] = value
        aligned, _, _ = compare([a['live']], [changed], report['bars'])
        assert aligned[0]['entry_match'] and not aligned[0]['exit_match']
    t['exit_legs'].pop()
    aligned, _, _ = compare([a['live']], [t], report['bars'])
    assert not aligned[0]['exit_match']
    assert aligned[0]['exit_leg_alignment'][1]['replay'] is None
    unknown = {**a['live'], 'exit_legs_json': ''}
    aligned, _, _ = compare([unknown], [t], report['bars'])
    assert not aligned[0]['exit_match']
    assert 'EXIT_REFERENCE_INCOMPLETE' in aligned[0]['reasons'][0]


def test_duplicate_position_rows_cannot_inflate_entry_count(report):
    row = report['alignment'][0]['live']
    with pytest.raises(ValueError, match='duplicate reference position'):
        compare([row, row], report['trades'], report['bars'])


@pytest.mark.parametrize('now,mid', [(900, '101'), (930, '101'), (1799, '101'),
                                    (1810, '202'), (2730, '202')])
def test_book_uses_only_containing_bar_open_or_closed_fallback(now, mid):
    from decimal import Decimal as D
    from htx_trend.market_data import Candle, TopOfBook

    # Deliberately different OHLC and a future bar detect lookahead or gap filling.
    candles = {('BTC-USDT', '15min'): [
        Candle(0, D('90'), D('100'), D('80'), D('99')),
        Candle(900, D('101'), D('250'), D('50'), D('202')),
        Candle(3600, D('999'), D('1000'), D('900'), D('950')),
    ]}
    for spread in (D('0'), D('.02')):
        market = OfflineMarket(candles, Assumptions(spread_rate=spread))
        market.now = now
        half = D(mid) * spread / 2
        assert market.top_of_book('BTC-USDT') == TopOfBook(D(mid) - half, D(mid) + half)


def test_complete_state_reference_and_honest_exit_boundaries(report):
    evidence = json.loads((INPUTS / '_evidence_notes/exit_legs_from_state.json').read_text())
    assert report['summary']['full_matches'] == 9
    assert report['summary']['outside_coverage_count'] == 1
    assert report['summary']['exit_mismatch_count'] == 2
    for a in report['alignment']:
        assert json.loads(a['live']['exit_legs_json']) == evidence[a['live']['position_key']]
        assert a['live']['exit_reference_scope'] == 'LEGS_FROM_STATE_ORDERS_AND_EXIT_DECISION'
        assert not any('EXIT_REFERENCE_INCOMPLETE' in r for r in a['reasons'])
        assert a['match'] == (a['entry_match'] and a['exit_match'])
    single = next(a for a in report['alignment'] if '20d87b03' in a['live']['position_key'])
    assert single['exit_match'] is True and single['match'] is True
    outside = next(a for a in report['alignment'] if '93cef710' in a['live']['position_key'])
    assert outside['entry_match'] and not outside['match']
    assert outside['replay']['exit_legs'] == []
    assert outside['exit_outside_coverage']
    assert any(r.startswith('EXIT_OUTSIDE_KLINE_COVERAGE') for r in outside['reasons'])
    f56 = next(a for a in report['alignment'] if 'f56d3d17' in a['live']['position_key'])
    assert not f56['exit_match'] and not f56['exit_outside_coverage']
    assert any(r.startswith('EXIT_LEGS_MISMATCH') for r in f56['reasons'])


def test_exit_buckets_and_coverage_do_not_hide_missing_legs(report):
    from copy import deepcopy
    a = next(a for a in report['alignment'] if '20d87b03' in a['live']['position_key'])
    trade = deepcopy(a['replay'])
    trade['exit_legs'] = json.loads(a['live']['exit_legs_json'])
    trade['exit_legs'][0]['at_cst'] = '2026-10-20 04:29:59'
    aligned, _, _ = compare([a['live']], [trade], report['bars'])
    assert aligned[0]['match']  # Same 15m bucket, different seconds.
    trade['exit_legs'] = []
    aligned, _, _ = compare([a['live']], [trade], report['bars'],
                           coverage_end={'ETH-USDT': epoch('2026-10-21 10:30:59')})
    assert not aligned[0]['exit_outside_coverage']
    assert any(r.startswith('EXIT_LEGS_MISMATCH') for r in aligned[0]['reasons'])
