"""把 HTX 公开盘口截成 20 档。"""


def _levels(rows, count):
    levels = []
    for row in rows[:count]:
        price, volume = row[0], row[1]
        levels.append([str(price), str(volume)])
    return levels


def normalize_depth(contract, payload, levels=20):
    tick = payload.get('tick') or payload.get('data') or payload
    bids = tick.get('bids') or []
    asks = tick.get('asks') or []
    return {
        'contract': contract,
        'ts': tick.get('ts'),
        'bids': _levels(bids, levels),
        'asks': _levels(asks, levels),
        'short_book': len(bids) < levels or len(asks) < levels,
    }
