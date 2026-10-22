"""把 HTX 公开盘口截成 20 档。"""

# 小于该值视为秒（约 1e9）；真实盘口 id 为毫秒时已是 1e12 量级。
_MILLISECOND_TS = 10**12


def _levels(rows, count):
    levels = []
    for row in rows[:count]:
        price, volume = row[0], row[1]
        levels.append([str(price), str(volume)])
    return levels


def _depth_ts(tick, payload):
    tick_ts = tick.get('ts')
    if tick_ts is not None:
        return tick_ts
    payload_ts = payload.get('ts') if isinstance(payload, dict) else None
    if payload_ts is not None:
        return payload_ts
    raw_id = tick.get('id')
    if raw_id is None:
        return None
    value = int(raw_id)
    if value < _MILLISECOND_TS:
        return value * 1000
    return value


def normalize_depth(contract, payload, levels=20):
    tick = payload.get('tick') or payload.get('data') or payload
    bids = tick.get('bids') or []
    asks = tick.get('asks') or []
    return {
        'contract': contract,
        'ts': _depth_ts(tick, payload),
        'bids': _levels(bids, levels),
        'asks': _levels(asks, levels),
        'short_book': len(bids) < levels or len(asks) < levels,
    }
