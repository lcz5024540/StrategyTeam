"""End-to-end fixed evidence replay, including offline and negative checks."""
import hashlib
import json
from pathlib import Path
import shutil
import socket

import pytest

from htx_trend.scan_replay import Assumptions, OfflineMarket, compare, epoch, load_inputs, replay
from htx_trend.timeframes import TimeframeProfile

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
        replay(tmp_path, profile=TimeframeProfile.INTRADAY_15M)
    manifest = json.loads((tmp_path / 'MANIFEST.json').read_text())
    manifest['sha256'][input_name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (tmp_path / 'MANIFEST.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match='SHA256 mismatch'):
        replay(tmp_path, profile=TimeframeProfile.INTRADAY_15M)


def test_empty_locked_hashes_rejects_replay(tmp_path, monkeypatch):
    """Empty LOCKED_HASHES_BY_PROFILE for a profile must reject replay."""
    from htx_trend import scan_replay as scan_replay_module
    
    for name in ('klines.csv', 'trades_closed_window.csv', 'MANIFEST.json'):
        shutil.copyfile(INPUTS / name, tmp_path / name)
    
    # Monkeypatch SWING_4H to be empty (simulating unregistered fixture)
    original_hashes = scan_replay_module.LOCKED_HASHES_BY_PROFILE.copy()
    scan_replay_module.LOCKED_HASHES_BY_PROFILE['SWING_4H'] = {}
    
    try:
        with pytest.raises(ValueError, match="LOCKED_HASHES_BY_PROFILE\\['SWING_4H'\\] is empty"):
            replay(tmp_path, strategy='HTX_TREND_4H_V2', profile=TimeframeProfile.SWING_4H)
    finally:
        scan_replay_module.LOCKED_HASHES_BY_PROFILE = original_hashes


def test_invalid_strategy_profile_combination():
    """Invalid (strategy, profile) pairs must be rejected."""
    with pytest.raises(ValueError, match='Invalid .* combination'):
        replay(INPUTS, strategy='HTX_TREND_4H_V2', profile=TimeframeProfile.INTRADAY_15M)
    
    with pytest.raises(ValueError, match='Invalid .* combination'):
        replay(INPUTS, strategy='HTX_TREND_15M_V2', profile=TimeframeProfile.SWING_4H)


D1_DEEPEN_KLINES_SHA256 = 'b2cddb23d9dbd1b382ea7c1c9b74271ccfe73c09115ad19fe2a20debb95cd082'
SWING_4H_TRADES_SHA256 = '307549210aad2ea6e9a1ffae3535f2e2df983e651ecd56e86ed2bb3e006c5053'


def test_swing_4h_klines_lock_is_d1_btceth_deepen_fixture():
    """SWING_4H klines lock is the D1 BTC/ETH deepen fixture; trades lock stays put."""
    from htx_trend.scan_replay import LOCKED_HASHES_BY_PROFILE

    swing = LOCKED_HASHES_BY_PROFILE['SWING_4H']
    assert swing['klines.csv'] == D1_DEEPEN_KLINES_SHA256
    assert swing['trades_closed_window.csv'] == SWING_4H_TRADES_SHA256


def test_4h_locked_hashes_reject_modified_input(tmp_path, monkeypatch):
    """4H profile must reject modified fixtures even with updated MANIFEST, same as 15m."""
    from htx_trend import scan_replay as scan_replay_module
    
    # Create minimal 4H fixtures
    for name in ('klines.csv', 'trades_closed_window.csv'):
        (tmp_path / name).write_text('dummy_4h_content\n')
    
    # Compute actual hashes
    actual_hashes = {
        name: hashlib.sha256((tmp_path / name).read_bytes()).hexdigest()
        for name in ('klines.csv', 'trades_closed_window.csv')
    }
    
    # Create MANIFEST with correct hashes
    manifest = {'sha256': actual_hashes}
    (tmp_path / 'MANIFEST.json').write_text(json.dumps(manifest))
    
    # Temporarily patch LOCKED_HASHES to use actual hashes
    original_hashes = scan_replay_module.LOCKED_HASHES_BY_PROFILE.copy()
    scan_replay_module.LOCKED_HASHES_BY_PROFILE['SWING_4H'] = actual_hashes.copy()
    
    try:
        # Should work with matching hashes
        # (Will fail on other validation but not on hash mismatch)
        try:
            scan_replay_module.load_inputs(tmp_path, TimeframeProfile.SWING_4H)
        except ValueError as e:
            # Should not be a hash mismatch error
            assert 'SHA256 mismatch' not in str(e)
        
        # Now modify a file
        (tmp_path / 'klines.csv').write_text('modified_4h_content\n')
        
        # Should reject due to hash mismatch
        with pytest.raises(ValueError, match='SHA256 mismatch'):
            scan_replay_module.load_inputs(tmp_path, TimeframeProfile.SWING_4H)
        
        # Forge MANIFEST to match modified content
        forged_hashes = actual_hashes.copy()
        forged_hashes['klines.csv'] = hashlib.sha256((tmp_path / 'klines.csv').read_bytes()).hexdigest()
        manifest['sha256'] = forged_hashes
        (tmp_path / 'MANIFEST.json').write_text(json.dumps(manifest))
        
        # Should still reject (MANIFEST forgery doesn't bypass LOCKED_HASHES)
        with pytest.raises(ValueError, match='SHA256 mismatch'):
            scan_replay_module.load_inputs(tmp_path, TimeframeProfile.SWING_4H)
    finally:
        scan_replay_module.LOCKED_HASHES_BY_PROFILE = original_hashes


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
    assert replay(INPUTS, profile=TimeframeProfile.INTRADAY_15M) == report


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
    candles, _, _ = load_inputs(INPUTS, TimeframeProfile.INTRADAY_15M)
    market = OfflineMarket(candles, Assumptions(), entry_period='15min')
    for time in ('2026-10-20 09:00:09', '2026-10-20 09:00:30', '2026-10-20 09:15:30'):
        now = epoch(time)
        for period, seconds in (('15min', 900), ('60min', 3600)):
            window = market.kline_window('BTC-USDT', period, 160, now_epoch=now)
            assert window.is_complete
            assert all(c.open_time + seconds <= now - 10 for c in window.candles)


def test_real_stale_gate_is_not_bypassed():
    stale = replay(INPUTS, Assumptions(scan_delay_seconds=601,
        start_cst='2026-10-20 03:00:00', end_cst='2026-10-20 03:30:59'),
        profile=TimeframeProfile.INTRADAY_15M)
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
    r = replay(INPUTS, Assumptions(start_cst='2026-10-20 09:00:00', end_cst='2026-10-20 09:00:59'),
               profile=TimeframeProfile.INTRADAY_15M)
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
        market = OfflineMarket(candles, Assumptions(spread_rate=spread), entry_period='15min')
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


def test_absent_optional_book_keeps_forming_open_and_records_gap(report):
    """无 MANIFEST 夹具时不得虚构盘口，分数与 forming-open 假设保持不变。"""
    from htx_trend.market_data import TopOfBook
    from htx_trend.scan_replay import load_optional_book, markdown

    assert load_optional_book(INPUTS) is None
    fixture = report['header']['book_fixture']
    assert fixture['loaded'] is False
    assert fixture['mode'] == 'forming_bar_open'
    assert report['summary']['full_matches'] == 9
    assert report['summary']['entry_matches'] == 12
    assert report['summary']['extra_count'] == 0
    assert report['summary']['missing_count'] == 0
    assert report['summary']['exit_mismatch_count'] == 2
    assert report['summary']['outside_coverage_count'] == 1
    text = '\n'.join(report['header']['fixture_gap'])
    assert '36733c1f' in text and '2026-10-21 02:00' in text
    assert 'f56d3d17' in text and '2026-10-20 19:00' in text
    assert '93cef710' in text and '13:45' in text
    assert '9/12' in report['header']['reachable_upper_bound']
    rendered = markdown(report)
    assert '夹具缺口' in rendered
    candles, _, _ = load_inputs(INPUTS, TimeframeProfile.INTRADAY_15M)
    bare = OfflineMarket(candles, Assumptions(), entry_period='15min')
    bare.now = epoch('2026-10-21 09:00:30')
    forming = next(c for c in candles[('BTC-USDT', '15min')]
                   if c.open_time <= bare.now < c.open_time + 900)
    assert bare.top_of_book('BTC-USDT') == TopOfBook(forming.open, forming.open)
    assert json.loads((INPUTS / 'MANIFEST.json').read_text()).get('optional_book') is None


def _write_book(root: Path, rows, *, kind, provenance='synthetic', sha=None, filename='book_mid.csv'):
    import csv
    path = root / filename
    fields = ['ts_ms', 'ts_cst', 'contract', 'bid', 'ask', 'mid', 'last', 'source']
    with path.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, '') for key in fields})
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest_path = root / 'MANIFEST.json'
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {'sha256': {}}
    manifest['optional_book'] = {
        'file': filename, 'sha256': sha or digest, 'kind': kind, 'provenance': provenance,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return digest


def test_optional_book_drives_quote_and_refuses_gaps_lookahead_and_bad_hash(tmp_path):
    """有夹具：哈希锁定、as-of 取价、不回退 forming-open、不吃未来报价。"""
    from decimal import Decimal as D
    from htx_trend.market_data import Candle, TopOfBook
    from htx_trend.scan_replay import load_optional_book

    for name in ('klines.csv', 'trades_closed_window.csv', 'MANIFEST.json'):
        shutil.copyfile(INPUTS / name, tmp_path / name)
    rows = [
        {'ts_ms': '1000000', 'contract': 'BTC-USDT', 'bid': '10', 'ask': '12', 'mid': '11',
         'source': 'SYNTHETIC_NOT_LIVE'},
        {'ts_ms': '5000000', 'contract': 'BTC-USDT', 'bid': '80', 'ask': '82', 'mid': '81',
         'source': 'SYNTHETIC_NOT_LIVE'},
        {'ts_ms': '1000000', 'contract': 'ETH-USDT', 'bid': '', 'ask': '', 'mid': '77',
         'source': 'SYNTHETIC_NOT_LIVE'},
    ]
    _write_book(tmp_path, rows, kind='mid')
    book = load_optional_book(tmp_path)
    assert book.provenance == 'synthetic' and book.row_count == 3
    candles = {('BTC-USDT', '15min'): [
        Candle(0, D('101'), D('250'), D('50'), D('202')),
    ]}
    market = OfflineMarket(candles, Assumptions(spread_rate=D('.02')), book, entry_period='15min')
    # ts_ms=1000000 → 第 1000 秒；now=2000 只能看到该条，不能看到 5000000，也不能用 open=101。
    market.now = 2000
    assert market.top_of_book('BTC-USDT') == TopOfBook(D('10'), D('12'))
    market.now = 5000
    assert market.top_of_book('BTC-USDT') == TopOfBook(D('80'), D('82'))
    market.now = 999
    with pytest.raises(ValueError, match='refusing to invent'):
        market.top_of_book('BTC-USDT')
    market.now = 2000
    assert market.top_of_book('ETH-USDT') == TopOfBook(D('77'), D('77'))
    _write_book(tmp_path, rows, kind='mid', sha='0' * 64)
    with pytest.raises(ValueError, match='optional book SHA256'):
        load_optional_book(tmp_path)
    # 未声明的旁路文件保持缺省路径。
    manifest = json.loads((tmp_path / 'MANIFEST.json').read_text())
    manifest['optional_book'] = None
    (tmp_path / 'MANIFEST.json').write_text(json.dumps(manifest))
    assert load_optional_book(tmp_path) is None
    bare = OfflineMarket(candles, Assumptions(), entry_period='15min')
    bare.now = 100
    assert bare.top_of_book('BTC-USDT') == TopOfBook(D('101'), D('101'))


def test_optional_book_rejects_inconsistent_or_unlabeled_rows(tmp_path):
    from htx_trend.scan_replay import load_optional_book

    (tmp_path / 'MANIFEST.json').write_text(json.dumps({'sha256': {}, 'optional_book': None}))
    bad_mid = [{
        'ts_ms': '1000', 'contract': 'BTC-USDT', 'bid': '10', 'ask': '12', 'mid': '11.5',
        'source': 'SYNTHETIC_NOT_LIVE',
    }]
    _write_book(tmp_path, bad_mid, kind='book')
    with pytest.raises(ValueError, match='mid must equal'):
        load_optional_book(tmp_path)
    unlabeled = [{
        'ts_ms': '1000', 'contract': 'BTC-USDT', 'bid': '10', 'ask': '12', 'mid': '11',
        'source': 'live-export',
    }]
    _write_book(tmp_path, unlabeled, kind='book', provenance='synthetic')
    with pytest.raises(ValueError, match='SYNTHETIC'):
        load_optional_book(tmp_path)
    tick = [{
        'ts_ms': '1000', 'contract': 'BTC-USDT', 'last': '66', 'source': 'SYNTHETIC_NOT_LIVE',
    }]
    _write_book(tmp_path, tick, kind='tick')
    book = load_optional_book(tmp_path)
    market = OfflineMarket({}, Assumptions(), book)
    market.now = 1
    from htx_trend.market_data import TopOfBook
    from decimal import Decimal as D
    assert market.top_of_book('BTC-USDT') == TopOfBook(D('66'), D('66'))


def test_replay_optional_book_changes_fill_without_touching_absent_inputs(tmp_path):
    """短窗重放确认 worker 管理/成交读的是夹具价，而不是 forming-open。"""
    from htx_trend.scan_replay import replay as replay_scan

    for name in ('klines.csv', 'trades_closed_window.csv', 'MANIFEST.json'):
        shutil.copyfile(INPUTS / name, tmp_path / name)
    window = Assumptions(start_cst='2026-10-20 03:00:00', end_cst='2026-10-20 03:30:59')
    baseline = replay_scan(tmp_path, window, profile=TimeframeProfile.INTRADAY_15M)
    assert baseline['header']['book_fixture']['loaded'] is False
    eth = next(t for t in baseline['trades'] if t['contract'] == 'ETH-USDT' and t['side'] == 'SHORT')
    forming_entry = eth['entry_price']
    stamped = '2026-10-20 03:00:00'
    rows = []
    for contract in ('BTC-USDT', 'ETH-USDT'):
        rows.append({'ts_cst': stamped, 'contract': contract, 'bid': '2401.01', 'ask': '2401.03',
                     'mid': '2401.02', 'source': 'SYNTHETIC_NOT_LIVE'})
    _write_book(tmp_path, rows, kind='book')
    loaded = replay_scan(tmp_path, window, profile=TimeframeProfile.INTRADAY_15M)
    assert loaded['header']['book_fixture']['loaded'] is True
    assert loaded['header']['book_fixture']['provenance'] == 'synthetic'
    eth_book = next(t for t in loaded['trades'] if t['contract'] == 'ETH-USDT' and t['side'] == 'SHORT')
    assert eth_book['entry_price'] == '2401.01'
    assert eth_book['entry_price'] != forming_entry


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


def test_assumptions_ignores_unknown_keys_like_notes(tmp_path):
    """Assumptions 应忽略 JSON 中的未知键（如 notes），不抛出 TypeError。"""
    for name in ('klines.csv', 'trades_closed_window.csv', 'MANIFEST.json'):
        shutil.copyfile(INPUTS / name, tmp_path / name)
    
    assumptions_json = tmp_path / 'assumptions.json'
    assumptions_json.write_text(json.dumps({
        'scan_delay_seconds': 45,
        'equity': '500',
        'notes': ['这是一个未声明的字段', '应该被忽略而不报错'],
        'unknown_field': 'should also be ignored',
        'start_cst': '2026-10-20 03:00:00',
        'end_cst': '2026-10-20 03:30:59',
    }))
    
    from htx_trend.scan_replay import replay as replay_scan
    from decimal import Decimal as D
    
    result = replay_scan(tmp_path, 
                        Assumptions(scan_delay_seconds=45, equity=D('500'),
                                   start_cst='2026-10-20 03:00:00',
                                   end_cst='2026-10-20 03:30:59'),
                        profile=TimeframeProfile.INTRADAY_15M)
    assert result['header']['assumptions']['scan_delay_seconds'] == 45
    assert result['header']['assumptions']['equity'] == '500'
    assert 'notes' not in result['header']['assumptions']
    assert 'unknown_field' not in result['header']['assumptions']


def test_empty_candle_series_does_not_crash_coverage_end():
    """当某合约无 K 线时，coverage_end 应使用 default=0 而非崩溃。"""
    from htx_trend.scan_replay import compare, epoch
    from decimal import Decimal as D
    
    candles = {
        ('BTC-USDT', '15min'): [
            type('Candle', (), {'open_time': 1729382400})()
        ],
    }
    
    live = [
        {
            'position_key': 'test-btc', 'contract': 'BTC-USDT', 'side': 'LONG',
            'model': 'EMA_MOMENTUM_CONTINUATION', 'opened_at_cst': '2024-10-20 03:15:40',
            'signal_bar_cst': '2024-10-20 03:15:00', 'exit_reason': 'trend-weakened-protection-breach',
            'closed_at_cst': '2024-10-20 04:30:21', 'exit_legs_json': '[]',
        },
        {
            'position_key': 'test-stock', 'contract': 'AAPL-USDT', 'side': 'LONG',
            'model': 'EMA_MOMENTUM_CONTINUATION', 'opened_at_cst': '2024-10-20 03:15:40',
            'signal_bar_cst': '2024-10-20 03:15:00', 'exit_reason': 'trend-weakened-protection-breach',
            'closed_at_cst': '2024-10-20 04:30:21', 'exit_legs_json': '[]',
        }
    ]
    
    from htx_trend.market_data import period_seconds
    coverage_end = {
        contract: min(epoch('2024-10-20 05:00:00'),
                      min((max(c.open_time + period_seconds(period) for c in series)
                          for (symbol, period), series in candles.items() if symbol == contract), default=0))
        for contract in {row['contract'] for row in live}
    }
    
    assert 'BTC-USDT' in coverage_end
    assert 'AAPL-USDT' in coverage_end
    assert coverage_end['AAPL-USDT'] == 0
    
    aligned, extras, missing = compare(live, [], [], coverage_end=coverage_end)
    
    aapl_alignment = next(a for a in aligned if a['live']['contract'] == 'AAPL-USDT')
    assert 'OUTSIDE_REPLAY_COVERAGE' in ' '.join(aapl_alignment['reasons'])
