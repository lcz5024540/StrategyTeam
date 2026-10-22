"""一次公开采集循环。进程默认不进入本循环。"""
from __future__ import annotations

from .limiter import RateLimiter
from .normalize import normalize_depth
from .retry import call_with_retry

PATHS = {
    'open_interest': '/linear-swap-api/v1/swap_open_interest',
    'liquidation': '/linear-swap-api/v3/swap_liquidation_orders',
    'depth': '/linear-swap-ex/market/depth',
    'trades': '/linear-swap-ex/market/history/trade',
}
DEFAULT_INTERVALS = {'open_interest': 300, 'liquidation': 60, 'depth': 60, 'trades': 60}


class Collector:
    def __init__(self, transport, store, *, symbols, clock, sleep, requests_per_second,
                 max_attempts, on_retry, intervals, grace_seconds, collect_trades):
        self.transport = transport
        self.store = store
        self.symbols = tuple(symbols)
        self.clock = clock
        self.sleep = sleep
        self.max_attempts = max_attempts
        self.on_retry = on_retry
        self.intervals = intervals
        self.grace_seconds = grace_seconds
        self.collect_trades = collect_trades
        self.limiter = RateLimiter(requests_per_second, clock=clock, sleep=sleep)
        self.checkpoints = {}

    def run_once(self):
        now = self.clock()
        for symbol in self.symbols:
            for stream in ('open_interest', 'liquidation', 'depth', 'trades'):
                if stream == 'trades' and not self.collect_trades:
                    continue
                last = self.checkpoints.get((symbol, stream))
                interval = self.intervals[stream]
                if last is not None and now - last < interval:
                    continue
                if last is not None and now - last > interval + self.grace_seconds:
                    missed = int(now - last - interval)
                    self.store.add_gap({
                        'stream': stream, 'contract': symbol, 'gap_seconds': missed,
                        'previous_at': int(last), 'observed_at': int(now),
                    })
                self._store(stream, symbol, self._get(PATHS[stream], self._params(stream, symbol)))
                self.checkpoints[(symbol, stream)] = now

    def _params(self, stream, symbol):
        if stream == 'open_interest':
            return {'contract_code': symbol}
        if stream == 'liquidation':
            return {'contract': symbol, 'trade_type': 0, 'create_date': 7, 'page_index': 1, 'page_size': 50}
        if stream == 'depth':
            return {'contract_code': symbol, 'type': 'step0'}
        return {'contract_code': symbol, 'size': 200}

    def _get(self, path, params):
        def operation():
            self.limiter.acquire()
            return self.transport.get(path, params)
        return call_with_retry(operation, max_attempts=self.max_attempts, sleep=self.sleep,
                              on_retry=self.on_retry)

    def _store(self, stream, symbol, payload):
        if stream == 'open_interest':
            for row in payload.get('data') or []:
                timestamp = int(row.get('ts') or payload.get('ts'))
                record = {
                    'contract': symbol, 'ts': timestamp,
                    'volume': str(row.get('volume')), 'amount': str(row.get('amount')),
                    'value': str(row.get('value')),
                }
                self.store.add_snapshot(stream, symbol, timestamp, f'{symbol}|{timestamp}', record)
            return
        if stream == 'liquidation':
            orders = (payload.get('data') or {}).get('orders') or []
            for row in orders:
                timestamp = int(row['created_at'])
                record = {
                    'contract': symbol, 'query_id': row['query_id'], 'direction': row.get('direction'),
                    'volume': str(row.get('volume')), 'price': str(row.get('price')), 'created_at': timestamp,
                }
                self.store.add_snapshot(stream, symbol, timestamp, f"{symbol}|{row['query_id']}", record)
            return
        if stream == 'depth':
            snapshot = normalize_depth(symbol, payload)
            timestamp = int(snapshot['ts'])
            self.store.add_snapshot(stream, symbol, timestamp, f'{symbol}|{timestamp}', snapshot)
            return
        for row in _trade_rows(payload):
            self.store.add_trade(symbol, row)


def _trade_rows(payload):
    rows = []
    for item in payload.get('data') or []:
        nested = item.get('data') if isinstance(item, dict) else None
        if isinstance(nested, list):
            rows.extend(nested)
        elif isinstance(item, dict) and 'id' in item:
            rows.append(item)
    return rows


class RecordedTransport:
    """本地短跑用的公开响应副本，不打开网络。"""

    def __init__(self, timestamp_ms=1_700_000_000_000):
        self.timestamp_ms = timestamp_ms

    def get(self, path, params):
        symbol = params.get('contract_code') or params.get('contract')
        ts = self.timestamp_ms
        if 'swap_open_interest' in path:
            return {'status': 'ok', 'ts': ts, 'data': [{
                'contract_code': symbol, 'volume': '5', 'amount': '1', 'value': '10'}]}
        if 'swap_liquidation_orders' in path:
            return {'status': 'ok', 'data': {'orders': [{
                'query_id': 9, 'contract_code': symbol, 'direction': 'sell',
                'volume': '1', 'price': '90', 'created_at': ts}]}}
        if 'market/depth' in path:
            return {'status': 'ok', 'tick': {
                'ts': ts,
                'bids': [[str(100 - i), '1'] for i in range(20)],
                'asks': [[str(101 + i), '2'] for i in range(20)],
            }}
        if 'history/trade' in path:
            return {'status': 'ok', 'data': [{'data': [
                {'id': 42, 'ts': ts, 'amount': '3', 'price': '10', 'direction': 'buy'},
                {'id': 43, 'ts': ts, 'amount': '1', 'price': '11', 'direction': 'sell'},
            ]}]}
        raise ValueError(path)
