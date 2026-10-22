"""按记录字节和采集频率估算每月研究存储。"""
from __future__ import annotations

import json
from decimal import Decimal, ROUND_HALF_EVEN


def _line_bytes(record):
    encoded = json.dumps(record, ensure_ascii=False, separators=(',', ':'), sort_keys=True).encode()
    return len(encoded) + 1


def canonical_record_bytes():
    depth = {
        'contract': 'BTC-USDT', 'ts': 1700000000000, 'short_book': False,
        'bids': [[f'{100 - i}.0', '1.000'] for i in range(20)],
        'asks': [[f'{101 + i}.0', '2.000'] for i in range(20)],
    }
    return {
        'open_interest': _line_bytes({
            'amount': '89.1', 'contract': 'BTC-USDT', 'ts': 1700000000000,
            'value': '1000000', 'volume': '123456.7'}),
        'depth': _line_bytes(depth),
        'trades_1m': _line_bytes({
            'contract': 'BTC-USDT', 'minute': 1700000000, 'taker_buy_notional': '100.1',
            'taker_buy_qty': '12.5', 'taker_sell_notional': '80.2', 'taker_sell_qty': '9.5',
            'trade_count': 40}),
        'liquidation': _line_bytes({
            'contract': 'BTC-USDT', 'created_at': 1700000000000, 'direction': 'sell',
            'price': '100.1', 'query_id': 100000, 'volume': '10'}),
        'trade_id': len('trade|BTC-USDT|100000000000000\n'.encode()),
    }


def estimate_monthly_gb(*, contracts, trades_per_second, days=30):
    """默认不保留逐笔原文，只保留 1 分钟主动买卖量和成交编号索引。

    假设：持仓量 5 分钟、盘口与 1 分钟成交 60 秒、强平每小时 1 条。
    GB 为十进制 10^9 字节。trades_per_second 是单合约平均逐笔速率。
    """
    seconds = days * 86400
    sizes = canonical_record_bytes()
    counts = {
        'open_interest': seconds // 300,
        'depth': seconds // 60,
        'trades_1m': seconds // 60,
        'liquidation': seconds // 3600,
        'trade_id': int(Decimal(trades_per_second) * seconds),
    }
    per_contract = {name: counts[name] * sizes[name] for name in counts}
    total = sum(per_contract.values()) * contracts
    total_gb = (Decimal(total) / Decimal(10 ** 9)).quantize(Decimal('0.001'), rounding=ROUND_HALF_EVEN)
    return {
        'contracts': contracts,
        'days': days,
        'trades_per_second': format(Decimal(trades_per_second), 'f'),
        'bytes': total,
        'total_gb': format(total_gb, 'f'),
        'per_stream_bytes_per_contract': per_contract,
        'record_bytes': sizes,
    }
