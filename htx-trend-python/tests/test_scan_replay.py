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


def test_locked_hash_rejects_modified_input_and_forged_manifest(tmp_path):
    for name in ('klines.csv', 'trades_closed_window.csv', 'MANIFEST.json'):
        shutil.copyfile(INPUTS / name, tmp_path / name)
    target = tmp_path / 'klines.csv'
    target.write_bytes(target.read_bytes() + b'\n')
    with pytest.raises(ValueError, match='SHA256 mismatch'):
        replay(tmp_path)
    manifest = json.loads((tmp_path / 'MANIFEST.json').read_text())
    manifest['sha256']['klines.csv'] = hashlib.sha256(target.read_bytes()).hexdigest()
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
    assert report['summary']['live_count'] == 11
    matches = [a for a in report['alignment'] if a['match']]
    assert matches
    actual = next(a for a in matches if a['live']['opened_at_cst'] == '2026-10-20 03:15:40')
    assert actual['replay']['model'] == 'EMA_MOMENTUM_CONTINUATION'
    assert actual['replay']['exit_reason'] == 'trend-weakened-protection-breach'
    assert actual['signal_bar_open_cst'] == '2026-10-20T03:00:00+08:00'
    assert actual['bar_evidence']['signal_age_seconds'] == 30
    assert report['missing'][0]['bar_evidence']['worker_gate'] == 'HAD_POSITION_AT_TICK_START'
    assert report['missing'][0]['bar_evidence']['positions_at_tick_start'][0]['opened_at_cst'].startswith('2026-10-20T09:00:30')
    for extra in report['extras']:
        assert extra['bar_evidence']['gates']
        assert any(g['allowed'] for g in extra['bar_evidence']['gates'])
    assert all('replay_observation' in d for t in report['trades'] for d in t['exit_decisions'])


def test_reverse_comparison_detects_omission_extra_and_wrong_model(report):
    live = [a['live'] for a in report['alignment']]
    aligned, extras, missing = compare(live, [], report['bars'])
    assert len(missing) == len(live) and not extras and not any(a['match'] for a in aligned)
    aligned, extras, missing = compare([], report['trades'], report['bars'])
    assert not aligned and not missing and len(extras) == len(report['trades'])
    a = next(a for a in report['alignment'] if a['match'])
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
