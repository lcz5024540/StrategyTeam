"""按日分区的研究文件库。幂等键单独保存，不写交易业务表。"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path


def dumps(record):
    return json.dumps(record, ensure_ascii=False, separators=(',', ':'), sort_keys=True)


def day_from_ms(timestamp_ms):
    return datetime.fromtimestamp(int(timestamp_ms) / 1000, timezone.utc).strftime('%Y-%m-%d')


def day_from_seconds(timestamp):
    return datetime.fromtimestamp(int(timestamp), timezone.utc).strftime('%Y-%m-%d')


class ResearchStore:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._keys = set()
        self._bars = {}
        index = self.root / '_index' / 'keys.txt'
        if index.exists():
            self._keys = {line for line in index.read_text(encoding='utf-8').splitlines() if line}
        trade_root = self.root / 'trades_1m'
        trade_files = sorted(trade_root.rglob('*.jsonl')) if trade_root.exists() else []
        for path in trade_files:
            for row in self.read_jsonl(path):
                self._bars[(row['contract'], int(row['minute']))] = self._bar_from_row(row)

    @staticmethod
    def read_jsonl(path):
        file_path = Path(path)
        if not file_path.exists():
            return []
        return [json.loads(line) for line in file_path.read_text(encoding='utf-8').splitlines() if line]

    def _remember(self, key):
        if key in self._keys:
            return False
        self._keys.add(key)
        index = self.root / '_index' / 'keys.txt'
        index.parent.mkdir(parents=True, exist_ok=True)
        with index.open('a', encoding='utf-8') as handle:
            handle.write(key + '\n')
        return True

    def _append(self, stream, day, contract, record):
        directory = self.root / stream / day
        directory.mkdir(parents=True, exist_ok=True)
        name = f'{contract}.jsonl' if contract else f'{day}.jsonl'
        if stream == 'gaps':
            path = self.root / 'gaps' / f'{day}.jsonl'
            path.parent.mkdir(parents=True, exist_ok=True)
        else:
            path = directory / name
        with path.open('a', encoding='utf-8') as handle:
            handle.write(dumps(record) + '\n')

    def add_snapshot(self, stream, contract, timestamp_ms, key, record):
        if not self._remember(f'{stream}|{key}'):
            return False
        self._append(stream, day_from_ms(timestamp_ms), contract, record)
        return True

    def add_gap(self, record):
        key = f"gap|{record['stream']}|{record['contract']}|{record['previous_at']}|{record['observed_at']}"
        if not self._remember(key):
            return False
        self._append('gaps', day_from_seconds(record['observed_at']), None, record)
        return True

    def add_trade(self, contract, trade):
        trade_id = str(trade['id'])
        if not self._remember(f'trade|{contract}|{trade_id}'):
            return False
        timestamp_ms = int(trade['ts'])
        minute = timestamp_ms // 1000 // 60 * 60
        quantity = Decimal(str(trade['amount']))
        price = Decimal(str(trade['price']))
        if quantity <= 0 or price <= 0:
            raise ValueError('逐笔成交数量和价格必须为正')
        direction = trade['direction']
        bar = self._bars.get((contract, minute))
        if bar is None:
            bar = {
                'contract': contract, 'minute': minute,
                'taker_buy_qty': Decimal(0), 'taker_sell_qty': Decimal(0),
                'taker_buy_notional': Decimal(0), 'taker_sell_notional': Decimal(0),
                'trade_count': 0,
            }
            self._bars[(contract, minute)] = bar
        notional = quantity * price
        if direction == 'buy':
            bar['taker_buy_qty'] += quantity
            bar['taker_buy_notional'] += notional
        elif direction == 'sell':
            bar['taker_sell_qty'] += quantity
            bar['taker_sell_notional'] += notional
        else:
            raise ValueError('逐笔成交方向必须是 buy 或 sell')
        bar['trade_count'] += 1
        self._rewrite_bars(contract, day_from_ms(timestamp_ms))
        return True

    def _rewrite_bars(self, contract, day):
        rows = []
        for (name, minute), bar in sorted(self._bars.items()):
            if name == contract and day_from_seconds(minute) == day:
                rows.append(self._public_bar(bar))
        path = self.root / 'trades_1m' / day / f'{contract}.jsonl'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(''.join(dumps(row) + '\n' for row in rows), encoding='utf-8')

    @staticmethod
    def _public_bar(bar):
        return {
            'contract': bar['contract'],
            'minute': bar['minute'],
            'taker_buy_qty': format(bar['taker_buy_qty'], 'f'),
            'taker_sell_qty': format(bar['taker_sell_qty'], 'f'),
            'taker_buy_notional': format(bar['taker_buy_notional'], 'f'),
            'taker_sell_notional': format(bar['taker_sell_notional'], 'f'),
            'trade_count': bar['trade_count'],
        }

    @staticmethod
    def _bar_from_row(row):
        return {
            'contract': row['contract'],
            'minute': int(row['minute']),
            'taker_buy_qty': Decimal(row['taker_buy_qty']),
            'taker_sell_qty': Decimal(row['taker_sell_qty']),
            'taker_buy_notional': Decimal(row['taker_buy_notional']),
            'taker_sell_notional': Decimal(row['taker_sell_notional']),
            'trade_count': int(row['trade_count']),
        }
