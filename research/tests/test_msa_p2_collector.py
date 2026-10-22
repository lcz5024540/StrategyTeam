"""P2 read-only public collector: rate limit, reconnect, gaps, idempotency."""
import json
import socket
from decimal import Decimal
from pathlib import Path

import pytest

from msa_p2_collector.config import load_universe
from msa_p2_collector.estimate import estimate_monthly_gb
from msa_p2_collector.http_client import PublicHtxClient
from msa_p2_collector.limiter import RateLimiter
from msa_p2_collector.retry import RetryableTransportError, call_with_retry
from msa_p2_collector.collector import Collector
from msa_p2_collector.normalize import normalize_depth
from msa_p2_collector.storage import ResearchStore
from msa_p2_collector.__main__ import main

REPO = Path(__file__).resolve().parents[2]
PACKAGE = REPO / 'research/msa_p2_collector'


def test_default_universe_is_ring2_43_and_includes_anchors():
    symbols = load_universe(PACKAGE / 'universe.yaml')
    assert len(symbols) == 43
    assert 'BTC-USDT' in symbols
    assert 'ETH-USDT' in symbols
    assert 'WIN-USDT' in symbols
    assert len(symbols) == len(set(symbols))


def test_custom_pool_is_configurable_and_anchors_are_required(tmp_path):
    path = tmp_path / 'pool.yaml'
    path.write_text('contracts:\n  - BTC-USDT\n  - ETH-USDT\n  - SOL-USDT\n', encoding='utf-8')
    assert load_universe(path) == ('BTC-USDT', 'ETH-USDT', 'SOL-USDT')
    path.write_text('contracts:\n  - SOL-USDT\n', encoding='utf-8')
    with pytest.raises(ValueError, match='BTC-USDT'):
        load_universe(path)


def test_rate_limiter_spaces_calls_at_two_per_second():
    clock = {'t': 0.0}
    slept = []

    def now():
        return clock['t']

    def sleep(seconds):
        slept.append(seconds)
        clock['t'] += seconds

    limiter = RateLimiter(2, clock=now, sleep=sleep)
    limiter.acquire()
    limiter.acquire()
    limiter.acquire()
    assert slept == [0.5, 0.5]


def test_retry_reconnects_then_succeeds_and_stops_after_budget():
    calls = {'n': 0}
    events = []

    def operation():
        calls['n'] += 1
        if calls['n'] < 3:
            raise RetryableTransportError('reset')
        return {'ok': True}

    result = call_with_retry(
        operation, max_attempts=4, sleep=lambda _seconds: None,
        on_retry=lambda attempt, _exc: events.append(attempt))
    assert result == {'ok': True}
    assert events == [1, 2]

    def always_down():
        raise RetryableTransportError('down')

    with pytest.raises(RetryableTransportError):
        call_with_retry(always_down, max_attempts=2, sleep=lambda _seconds: None,
                        on_retry=lambda *_args: None)


def _depth_payload(ts, levels=30):
    return {'status': 'ok', 'tick': {
        'ts': ts,
        'bids': [[str(100 - i), '1'] for i in range(levels)],
        'asks': [[str(101 + i), '2'] for i in range(levels)],
    }}


class ScriptedTransport:
    def __init__(self, pages, failures=None):
        self.pages = pages
        self.failures = list(failures or [])
        self.calls = []

    def get(self, path, params):
        self.calls.append((path, dict(params)))
        if self.failures:
            raise self.failures.pop(0)
        for needle, payload in self.pages:
            if needle in path:
                return payload
        raise AssertionError(path)


def _pages(ts):
    trade = {'id': 42, 'ts': ts, 'amount': '3', 'price': '10', 'direction': 'buy'}
    return [
        ('swap_open_interest', {'status': 'ok', 'ts': ts, 'data': [{
            'contract_code': 'BTC-USDT', 'volume': '5', 'amount': '1', 'value': '10'}]}),
        ('swap_liquidation_orders', {'status': 'ok', 'data': {'orders': [{
            'query_id': 9, 'contract_code': 'BTC-USDT', 'direction': 'sell',
            'volume': '1', 'price': '90', 'created_at': ts}]}}),
        ('market/depth', _depth_payload(ts)),
        ('history/trade', {'status': 'ok', 'data': [{'data': [trade]}]}),
    ]


def test_depth_snapshot_keeps_twenty_levels():
    snap = normalize_depth('BTC-USDT', _depth_payload(1_700_000_000_000))
    assert len(snap['bids']) == 20 and len(snap['asks']) == 20
    assert snap['bids'][0] == ['100', '1']
    assert snap['asks'][0] == ['101', '2']


def test_cycle_records_gap_reconnects_and_is_idempotent(tmp_path):
    clock = {'t': 1_700_000_000.0}
    store = ResearchStore(tmp_path)
    transport = ScriptedTransport(
        _pages(1_700_000_000_000), failures=[RetryableTransportError('reset')])
    retries = []
    collector = Collector(
        transport, store, symbols=('BTC-USDT',),
        clock=lambda: clock['t'], sleep=lambda _seconds: None,
        requests_per_second=1000, max_attempts=3,
        on_retry=lambda attempt, _exc: retries.append(attempt),
        intervals={'open_interest': 300, 'liquidation': 60, 'depth': 60, 'trades': 60},
        grace_seconds=5, collect_trades=True,
    )
    collector.run_once()
    assert retries == [1]
    clock['t'] += 180
    transport.pages = _pages(1_700_000_180_000)
    collector.run_once()
    gaps = []
    for path in (tmp_path / 'gaps').rglob('*.jsonl'):
        gaps.extend(store.read_jsonl(path))
    assert any(row['stream'] == 'depth' and row['contract'] == 'BTC-USDT'
               and row['gap_seconds'] == 120 for row in gaps)
    collector.run_once()
    depth_lines = []
    for path in (tmp_path / 'depth').rglob('*.jsonl'):
        depth_lines.extend(store.read_jsonl(path))
    assert len(depth_lines) == 2
    bars = []
    for path in (tmp_path / 'trades_1m').rglob('*.jsonl'):
        bars.extend(store.read_jsonl(path))
    assert len(bars) == 1
    assert bars[0]['taker_buy_qty'] == '3'
    assert bars[0]['taker_sell_qty'] == '0'
    reopened = ResearchStore(tmp_path)
    Collector(
        ScriptedTransport(_pages(1_700_000_180_000)), reopened, symbols=('BTC-USDT',),
        clock=lambda: clock['t'], sleep=lambda _seconds: None,
        requests_per_second=1000, max_attempts=2, on_retry=lambda *_args: None,
        intervals={'open_interest': 300, 'liquidation': 60, 'depth': 60, 'trades': 60},
        grace_seconds=5, collect_trades=True,
    ).run_once()
    bars = []
    for path in (tmp_path / 'trades_1m').rglob('*.jsonl'):
        bars.extend(reopened.read_jsonl(path))
    assert bars[0]['taker_buy_qty'] == '3'
    liquidations = []
    for path in (tmp_path / 'liquidation').rglob('*.jsonl'):
        liquidations.extend(store.read_jsonl(path))
    assert len(liquidations) == 1
    assert liquidations[0]['query_id'] == 9


def test_public_client_does_not_sign_or_attach_keys(monkeypatch):
    seen = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def read(self):
            return b'{"status":"ok","data":[]}'

    def urlopen(request, timeout=None):
        seen['url'] = request.full_url
        seen['headers'] = dict(request.header_items())
        return Response()

    monkeypatch.setattr('urllib.request.urlopen', urlopen)
    client = PublicHtxClient('https://api.hbdm.com')
    client.get('/linear-swap-api/v1/swap_open_interest', {'contract_code': 'BTC-USDT'})
    assert 'api.hbdm.com/linear-swap-api/v1/swap_open_interest' in seen['url']
    assert 'Signature' not in seen['url'] and 'AccessKeyId' not in seen['url']
    joined = ' '.join(f'{key}:{value}' for key, value in seen['headers'])
    assert 'api-key' not in joined.lower() and 'secret' not in joined.lower()


def test_cli_stays_stopped_and_dry_run_does_not_use_the_network(tmp_path, monkeypatch):
    def boom(*_args, **_kwargs):
        raise AssertionError('network socket opened')

    monkeypatch.setattr(socket, 'socket', boom)
    assert main([]) == 2
    assert main(['--dry-run', '--out', str(tmp_path), '--symbols', 'BTC-USDT']) == 0
    assert list((tmp_path / 'depth').rglob('*.jsonl'))
    assert list((tmp_path / 'open_interest').rglob('*.jsonl'))
    assert list((tmp_path / 'liquidation').rglob('*.jsonl'))
    assert list((tmp_path / 'trades_1m').rglob('*.jsonl'))
    manifest = json.loads((tmp_path / 'dry_run_manifest.json').read_text(encoding='utf-8'))
    assert manifest['network'] is False
    assert manifest['symbols'] == ['BTC-USDT']


def test_storage_estimate_is_documented_and_package_is_not_a_trading_process():
    estimate = estimate_monthly_gb(contracts=43, trades_per_second=Decimal('1'))
    readme = (PACKAGE / 'README.md').read_text(encoding='utf-8')
    assert estimate['total_gb'] in readme
    assert '默认不启动' in readme
    source = '\n'.join(path.read_text(encoding='utf-8') for path in PACKAGE.rglob('*.py'))
    for banned in ('htx_trend', 'place_order', 'systemd', 'AccessKeyId', 'api_secret'):
        assert banned not in source
    assert not list(PACKAGE.rglob('*systemd*'))
