"""P2 read-only public collector: rate limit, reconnect, gaps, idempotency."""
import json
import signal
import socket
import urllib.error
from decimal import Decimal
from pathlib import Path

import pytest

from msa_p2_collector.config import load_universe
from msa_p2_collector.estimate import estimate_monthly_gb
from msa_p2_collector.http_client import HttpTransport, PublicHtxClient
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
    assert snap['ts'] == 1_700_000_000_000


def _collector(tmp_path, transport):
    return Collector(
        transport, ResearchStore(tmp_path), symbols=('BTC-USDT',),
        clock=lambda: 1_700_000_000.0, sleep=lambda _seconds: None,
        requests_per_second=1000, max_attempts=2, on_retry=lambda *_args: None,
        intervals={'open_interest': 300, 'liquidation': 60, 'depth': 60, 'trades': 60},
        grace_seconds=5, collect_trades=True,
    )


def _jsonl(root, stream):
    rows = []
    store = ResearchStore(root)
    for path in (root / stream).rglob('*.jsonl'):
        rows.extend(store.read_jsonl(path))
    return rows


def test_liquidation_v3_list_payload_is_stored(tmp_path):
    created_at = 1_700_000_000_000
    collector = _collector(tmp_path, ScriptedTransport([]))
    collector._store('liquidation', 'BTC-USDT', {
        'code': 200,
        'msg': 'ok',
        'data': [{
            'query_id': 11,
            'contract_code': 'BTC-USDT',
            'direction': 'buy',
            'volume': 2,
            'price': 80.5,
            'created_at': created_at,
        }],
    })
    rows = _jsonl(tmp_path, 'liquidation')
    assert len(rows) == 1
    assert rows[0]['query_id'] == 11
    assert rows[0]['direction'] == 'buy'
    assert rows[0]['volume'] == '2'
    assert rows[0]['price'] == '80.5'
    assert rows[0]['created_at'] == created_at


def test_normalize_depth_falls_back_to_payload_ts_then_tick_id():
    bids = [['100', '1']]
    asks = [['101', '2']]
    payload_ts = normalize_depth('ETH-USDT', {
        'status': 'ok',
        'ts': 1_700_000_000_111,
        'tick': {'id': 1_700_000_000, 'bids': bids, 'asks': asks},
    })
    assert payload_ts['ts'] == 1_700_000_000_111

    second_id = normalize_depth('ETH-USDT', {
        'status': 'ok',
        'tick': {'id': 1_700_000_000, 'bids': bids, 'asks': asks},
    })
    assert second_id['ts'] == 1_700_000_000_000

    millisecond_id = normalize_depth('ETH-USDT', {
        'status': 'ok',
        'tick': {'id': 1_700_000_000_222, 'bids': bids, 'asks': asks},
    })
    assert millisecond_id['ts'] == 1_700_000_000_222


def test_real_trade_batches_keep_inner_prints(tmp_path):
    ts = 1_700_000_000_000
    collector = _collector(tmp_path, ScriptedTransport([]))
    collector._store('trades', 'BTC-USDT', {
        'ch': 'market.BTC-USDT.trade.detail',
        'status': 'ok',
        'ts': ts,
        'data': [{
            'id': 131561182318,
            'ts': ts,
            'data': [
                {'id': 131561182318000001, 'ts': ts, 'amount': 2, 'price': 13084.2, 'direction': 'buy'},
                {'id': 131561182318000002, 'ts': ts, 'amount': 1, 'price': 13085, 'direction': 'sell'},
            ],
        }],
    })
    bars = _jsonl(tmp_path, 'trades_1m')
    assert len(bars) == 1
    assert bars[0]['trade_count'] == 2
    assert bars[0]['taker_buy_qty'] == '2'
    assert bars[0]['taker_sell_qty'] == '1'


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


def _payload_for(path, ts=1_700_000_000_000):
    for needle, payload in _pages(ts):
        if needle in path:
            return payload
    raise AssertionError(path)


def _two_coin_universe(directory):
    path = directory / 'pool.yaml'
    path.write_text('contracts:\n  - BTC-USDT\n  - ETH-USDT\n', encoding='utf-8')
    return path


def test_http_transport_reads_public_json_and_retries_429(monkeypatch):
    calls = {'n': 0}

    class Response:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def read(self):
            return b'{"status":"ok","ts":1700000000000,"data":[]}'

    def urlopen(request, timeout=None):
        calls['n'] += 1
        assert request.get_method() == 'GET'
        assert 'https://api.hbdm.com/linear-swap-api/v1/swap_open_interest' in request.full_url
        assert 'Signature' not in request.full_url and 'AccessKeyId' not in request.full_url
        if calls['n'] == 1:
            raise urllib.error.HTTPError(request.full_url, 429, 'rate', hdrs=None, fp=None)
        return Response()

    monkeypatch.setattr('urllib.request.urlopen', urlopen)
    client = HttpTransport()
    with pytest.raises(RetryableTransportError):
        client.get('/linear-swap-api/v1/swap_open_interest', {'contract_code': 'BTC-USDT'})
    body = client.get('/linear-swap-api/v1/swap_open_interest', {'contract_code': 'BTC-USDT'})
    assert body['status'] == 'ok'
    assert calls['n'] == 2


def test_run_loop_reconnects_records_gap_and_stops(tmp_path):
    clock = {'t': 1_000.0}

    def sleep(seconds):
        clock['t'] += seconds

    transport = ScriptedTransport(
        _pages(1_000_000), failures=[RetryableTransportError('down'), RetryableTransportError('down')])
    store = ResearchStore(tmp_path)
    collector = Collector(
        transport, store, symbols=('BTC-USDT',),
        clock=lambda: clock['t'], sleep=sleep,
        requests_per_second=1000, max_attempts=2, on_retry=lambda *_args: None,
        intervals={'open_interest': 10, 'liquidation': 2, 'depth': 2, 'trades': 2},
        grace_seconds=1, collect_trades=True,
    )

    def should_stop():
        rows = []
        for path in (tmp_path / 'gaps').rglob('*.jsonl'):
            rows.extend(store.read_jsonl(path))
        return any(row['stream'] == 'depth' and row['gap_seconds'] >= 2 for row in rows)

    collector.run_loop(should_stop)
    assert list((tmp_path / 'depth').rglob('*.jsonl'))


def test_cli_live_once_writes_public_files(tmp_path, monkeypatch):
    def boom(*_args, **_kwargs):
        raise AssertionError('network socket opened')

    monkeypatch.setattr(socket, 'socket', boom)

    def get(_self, path, _params):
        return _payload_for(path)

    monkeypatch.setattr(HttpTransport, 'get', get)
    out = tmp_path / 'out'
    code = main([
        '--live', '--once', '--out', str(out),
        '--universe', str(_two_coin_universe(tmp_path)),
    ])
    assert code == 0
    assert list((out / 'depth').rglob('*.jsonl'))
    assert list((out / 'open_interest').rglob('*.jsonl'))
    assert list((out / 'liquidation').rglob('*.jsonl'))
    assert list((out / 'trades_1m').rglob('*.jsonl'))
    contracts = set()
    for path in (out / 'depth').rglob('*.jsonl'):
        for row in ResearchStore.read_jsonl(path):
            contracts.add(row['contract'])
    assert contracts == {'BTC-USDT', 'ETH-USDT'}


def test_cli_live_refuses_universe_without_anchors(tmp_path, capsys):
    path = tmp_path / 'pool.yaml'
    path.write_text('contracts:\n  - SOL-USDT\n', encoding='utf-8')
    code = main([
        '--live', '--once', '--out', str(tmp_path / 'out'), '--universe', str(path),
    ])
    assert code == 1
    assert 'BTC-USDT' in capsys.readouterr().err
    assert not list((tmp_path / 'out').rglob('*.jsonl'))


def test_cli_loop_stops_on_sigint_and_sigterm(tmp_path, monkeypatch):
    handlers = {}
    monkeypatch.setattr(signal, 'signal', lambda signum, handler: handlers.__setitem__(signum, handler))

    def boom(*_args, **_kwargs):
        raise AssertionError('network socket opened')

    monkeypatch.setattr(socket, 'socket', boom)
    seen = {}

    def run_loop(self, should_stop):
        seen['symbols'] = self.symbols
        assert should_stop() is False
        handlers[signal.SIGINT](signal.SIGINT, None)
        assert should_stop() is True
        handlers[signal.SIGTERM](signal.SIGTERM, None)
        assert should_stop() is True

    monkeypatch.setattr(Collector, 'run_loop', run_loop)
    code = main([
        '--live', '--loop', '--out', str(tmp_path / 'out'),
        '--universe', str(_two_coin_universe(tmp_path)),
    ])
    assert code == 0
    assert seen['symbols'] == ('BTC-USDT', 'ETH-USDT')
    assert signal.SIGINT in handlers and signal.SIGTERM in handlers


def test_cli_live_usage_still_exits_2():
    assert main([]) == 2
    assert main(['--live']) == 2
    assert main(['--live', '--out', '/tmp/unused-msa-p2']) == 2
    assert main(['--live', '--once', '--loop', '--out', '/tmp/unused-msa-p2']) == 2


def test_example_unit_is_uninstalled_sample():
    unit = (PACKAGE / 'msa-p2-collector.service').read_text(encoding='utf-8')
    assert 'Type=simple' in unit
    assert '本令不授权安装' in unit
    user = next(line for line in unit.splitlines() if line.startswith('User='))
    assert user != 'User=root'
    work = next(line for line in unit.splitlines() if line.startswith('WorkingDirectory='))
    assert work.endswith('/research') or work.endswith('/research/')
    assert '--live' in unit and '--loop' in unit and '--out' in unit


def test_readme_documents_live_once_and_loop():
    readme = (PACKAGE / 'README.md').read_text(encoding='utf-8')
    assert '--live' in readme
    assert '--once' in readme
    assert '--loop' in readme
    assert '默认不启动' in readme


def test_storage_estimate_is_documented_and_package_is_not_a_trading_process():
    estimate = estimate_monthly_gb(contracts=43, trades_per_second=Decimal('1'))
    readme = (PACKAGE / 'README.md').read_text(encoding='utf-8')
    assert estimate['total_gb'] in readme
    assert '默认不启动' in readme
    source = '\n'.join(path.read_text(encoding='utf-8') for path in PACKAGE.rglob('*.py'))
    for banned in ('htx_trend', 'place_order', 'systemd', 'AccessKeyId', 'api_secret'):
        assert banned not in source
    assert not list(PACKAGE.rglob('*systemd*'))
