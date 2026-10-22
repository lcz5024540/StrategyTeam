"""从只读账本和成交计算执行成本。不写交易库，不改开单。"""
from __future__ import annotations

import csv
import json
from collections import defaultdict
from datetime import datetime
from decimal import Decimal
from pathlib import Path

ASSUMED_RT_BPS = Decimal('12')
BOUNDARY = '仅用于校准回测成本。不改变开单逻辑，不当作过闸证据，不回写已封存目标净效应。'
BPS = Decimal(10000)


def quantile(values, probability):
    """线性分位（Hyndman-Fan type 7）。values 为 Decimal。"""
    ordered = sorted(values)
    if not ordered:
        raise ValueError('空样本不能计算分位')
    if len(ordered) == 1:
        return ordered[0]
    rank = Decimal(len(ordered) - 1) * probability
    lower = int(rank)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = rank - Decimal(lower)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def _decimal(value, label):
    if value is None or value == '' or value == 'NULL':
        raise ValueError(f'缺少{label}')
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError(f'{label}非有限值')
    return result


def _optional_decimal(value):
    if value is None or value == '' or value == 'NULL':
        return None
    return _decimal(value, '数值')


def _time(value):
    if value is None or value == '' or value == 'NULL':
        return None
    parsed = datetime.fromisoformat(str(value).replace(' ', 'T'))
    if parsed.tzinfo is None:
        raise ValueError('时间必须包含时区')
    return parsed


def _phase(leg):
    if leg in ('ENTRY', 'OPEN'):
        return 'OPEN'
    if leg in ('CLOSE', 'PARTIAL_CLOSE'):
        return 'CLOSE'
    raise ValueError(f'未知成交腿 {leg}')


def _adverse_bps(side, phase, reference, fill):
    if reference is None or fill is None:
        return None
    if reference <= 0 or fill <= 0:
        raise ValueError('价格必须为正')
    buying = (side == 'LONG' and phase == 'OPEN') or (side == 'SHORT' and phase == 'CLOSE')
    if buying:
        return (fill - reference) / reference * BPS
    if side not in ('LONG', 'SHORT'):
        raise ValueError('方向必须是 LONG 或 SHORT')
    return (reference - fill) / reference * BPS


def _millis(start, end):
    if start is None or end is None:
        return None
    delta = end - start
    return (Decimal(delta.days) * Decimal(86400000)
            + Decimal(delta.seconds) * Decimal(1000)
            + Decimal(delta.microseconds) / Decimal(1000))


def analyze_bundle(bundle):
    orders = bundle.get('orders') or []
    fills = bundle.get('fills') or []
    seen_orders, order_by_id = set(), {}
    for order in orders:
        client_id = order['client_order_id']
        if client_id in seen_orders:
            raise ValueError(f'重复订单 {client_id}')
        seen_orders.add(client_id)
        order_by_id[client_id] = order
    seen_fills, fills_by_order = set(), defaultdict(list)
    for fill in fills:
        fill_id = fill['fill_id']
        if fill_id in seen_fills:
            raise ValueError('重复成交')
        seen_fills.add(fill_id)
        if fill['client_order_id'] not in order_by_id:
            raise ValueError('成交缺少订单')
        fills_by_order[fill['client_order_id']].append(fill)

    legs = [_leg_row(bundle, order, fills_by_order[order['client_order_id']]) for order in orders]
    funding_rows = _funding_rows(bundle, legs)
    round_trips, excluded = _round_trips(legs)
    from .orderpx import build_orderpx, summarize_orderpx
    orderpx = build_orderpx(bundle, legs, funding_rows)
    return {
        'legs': legs,
        'distribution': _distribution(legs),
        'comparison': _comparison(round_trips, excluded),
        'funding': funding_rows,
        'round_trips': round_trips,
        'orderpx': orderpx,
        'orderpx_summary': summarize_orderpx(orderpx),
        'boundary': BOUNDARY,
        'assumed_rt_bps': ASSUMED_RT_BPS,
    }


def _leg_row(bundle, order, fills):
    if not fills:
        raise ValueError('订单没有成交')
    phase = _phase(order['leg'])
    side = order['side']
    size = _optional_decimal(order.get('contract_size'))
    parsed = []
    for fill in fills:
        fill_size = _optional_decimal(fill.get('contract_size')) or size
        if fill_size is None or fill_size <= 0:
            raise ValueError('合约面值必须为正')
        quantity = _decimal(fill['quantity'], '成交数量')
        price = _decimal(fill['price'], '成交价')
        if quantity <= 0 or price <= 0:
            raise ValueError('成交数量、价格必须为正')
        parsed.append((fill, quantity, price, fill_size, _decimal(fill['fee'], '手续费'), _time(fill['filled_at'])))
    parsed.sort(key=lambda item: item[5])
    notional = sum((price * quantity * fill_size for _, quantity, price, fill_size, _, _ in parsed), Decimal(0))
    base = sum((quantity * fill_size for _, quantity, _, fill_size, _, _ in parsed), Decimal(0))
    if base <= 0 or notional <= 0:
        raise ValueError('成交名义必须为正')
    fill_vwap = notional / base
    fee = sum((item[4] for item in parsed), Decimal(0))
    from .orderpx import resolve_order_px
    signal = _optional_decimal(order.get('signal_price'))
    order_price, _order_px_source, _order_px_flags = resolve_order_px(order)
    missing = []
    if signal is None:
        missing.append('缺信号价')
    if order_price is None:
        missing.append('缺下单价')
    sent = _time(order.get('order_sent_at'))
    if sent is None:
        missing.append('缺下单时间')
    if order.get('cancel_count') is None:
        missing.append('缺撤单次数')
    if order.get('amend_count') is None:
        missing.append('缺改单次数')
    return {
        'book': order.get('book') or bundle.get('book'),
        'position_id': str(order['position_id']),
        'contract': order.get('contract_code') or None,
        'side': side,
        'phase': phase,
        'client_order_id': order['client_order_id'],
        'signal_price': signal,
        'order_price': order_price,
        'fill_vwap': fill_vwap,
        'slippage_vs_signal_bps': _adverse_bps(side, phase, signal, fill_vwap),
        'slippage_vs_order_bps': _adverse_bps(side, phase, order_price, fill_vwap),
        'fee_usdt': fee,
        'fee_bps': fee / notional * BPS,
        'notional_usdt': notional,
        'partial_fill_count': max(len(parsed) - 1, 0),
        'order_to_first_fill_ms': _millis(sent, parsed[0][5]),
        'cancel_count': None if order.get('cancel_count') is None else int(order['cancel_count']),
        'amend_count': None if order.get('amend_count') is None else int(order['amend_count']),
        'fill_count': len(parsed),
        'coverage': '；'.join(missing),
        '_first_fill_at': parsed[0][5],
        '_last_fill_at': parsed[-1][5],
    }


def _funding_rows(bundle, legs):
    by_position = defaultdict(list)
    for leg in legs:
        by_position[leg['position_id']].append(leg)
    seen_keys = set()
    events = defaultdict(list)
    for event in bundle.get('funding') or []:
        key = event.get('event_key')
        if not key:
            raise ValueError('资金费缺少标识')
        if key in seen_keys:
            continue
        seen_keys.add(key)
        events[str(event['position_id'])].append(event)
    rows = []
    for position_id in sorted(by_position):
        position_legs = by_position[position_id]
        opens = [leg for leg in position_legs if leg['phase'] == 'OPEN']
        closes = [leg for leg in position_legs if leg['phase'] == 'CLOSE']
        start = min(leg['_first_fill_at'] for leg in opens) if opens else None
        end = max(leg['_last_fill_at'] for leg in closes) if closes else None
        paid = received = Decimal(0)
        count = 0
        for event in events.get(position_id, []):
            occurred = _time(event['occurred_at'])
            if start is None or occurred is None or occurred <= start:
                continue
            if end is not None and occurred > end:
                continue
            amount = _decimal(event['funding_fee_usdt'], '资金费')
            count += 1
            if amount >= 0:
                paid += amount
            else:
                received += -amount
        sample = position_legs[0]
        rows.append({
            'position_id': position_id,
            'contract': sample['contract'],
            'side': sample['side'],
            'funding_paid_usdt': paid,
            'funding_received_usdt': received,
            'funding_net_usdt': paid - received,
            'event_count': count,
            'position_open': end is None,
            'window_start': None if start is None else start.isoformat(),
            'window_end': None if end is None else end.isoformat(),
        })
    return rows


def _round_trips(legs):
    by_position = defaultdict(list)
    for leg in legs:
        by_position[leg['position_id']].append(leg)
    trips, excluded = [], []
    for position_id, position_legs in sorted(by_position.items()):
        phases = {leg['phase'] for leg in position_legs}
        complete = all(leg['slippage_vs_order_bps'] is not None and leg['fee_bps'] is not None
                       for leg in position_legs)
        if 'OPEN' in phases and 'CLOSE' in phases and complete:
            cost = sum((leg['slippage_vs_order_bps'] + leg['fee_bps'] for leg in position_legs), Decimal(0))
            sample = position_legs[0]
            trips.append({
                'position_id': position_id,
                'contract': sample['contract'],
                'side': sample['side'],
                'rt_bps': cost,
            })
        else:
            excluded.append(position_legs[0])
    return trips, excluded


def _distribution(legs):
    metrics = ('slippage_vs_order_bps', 'fee_bps', 'order_to_first_fill_ms')
    buckets = defaultdict(list)
    for leg in legs:
        for metric in metrics:
            value = leg[metric]
            if value is not None:
                buckets[(leg['contract'], leg['side'], leg['phase'], metric)].append(value)
    rows = []
    for key in sorted(buckets, key=lambda item: tuple('' if part is None else str(part) for part in item)):
        values = buckets[key]
        contract, side, phase, metric = key
        rows.append({
            'contract': contract, 'side': side, 'phase': phase, 'metric': metric, 'n': len(values),
            'median': quantile(values, Decimal('0.5')),
            'p75': quantile(values, Decimal('0.75')),
            'p90': quantile(values, Decimal('0.9')),
        })
    return rows


def _comparison(trips, excluded):
    scopes = [('ALL', '*', trips, excluded)]
    for side in sorted({trip['side'] for trip in trips} | {row['side'] for row in excluded}):
        scopes.append(('SIDE', side,
                       [trip for trip in trips if trip['side'] == side],
                       [row for row in excluded if row['side'] == side]))
    contracts = {trip['contract'] for trip in trips} | {row['contract'] for row in excluded}
    for contract in sorted(contracts, key=lambda item: '' if item is None else item):
        scopes.append(('CONTRACT', contract or '',
                       [trip for trip in trips if trip['contract'] == contract],
                       [row for row in excluded if row['contract'] == contract]))
    rows = []
    for scope, key, chosen, skipped in scopes:
        values = [trip['rt_bps'] for trip in chosen]
        rows.append({
            'scope': scope,
            'key': key,
            'n': len(values),
            'median_rt_bps': None if not values else quantile(values, Decimal('0.5')),
            'p75_rt_bps': None if not values else quantile(values, Decimal('0.75')),
            'p90_rt_bps': None if not values else quantile(values, Decimal('0.9')),
            'assumed_rt_bps': ASSUMED_RT_BPS,
            'median_minus_assumed_bps': None if not values else quantile(values, Decimal('0.5')) - ASSUMED_RT_BPS,
            'excluded_positions': len(skipped),
        })
    return rows


def load_ledger_pnl_export(directory):
    """读取已导出的 PAPER/LIVE 账本目录，不修改其中任何文件。"""
    root = Path(directory)
    positions = json.loads((root / 'positions.json').read_text(encoding='utf-8'))
    fills = json.loads((root / 'fills.json').read_text(encoding='utf-8'))
    with (root / 'trades_closed_window.csv').open(encoding='utf-8', newline='') as handle:
        trades = list(csv.DictReader(handle))
    contract_by_key = {row['position_key']: row['contract'] for row in trades}
    position_by_id = {str(row['id']): row for row in positions}
    grouped = defaultdict(list)
    for fill in fills:
        grouped[(str(fill['position_id']), fill['leg'])].append(fill)
    orders, normalized = [], []
    for (position_id, leg), group in sorted(grouped.items()):
        position = position_by_id.get(position_id, {})
        key = position.get('position_key') or ''
        if key.startswith('paper-'):
            book = 'PAPER'
        elif key.startswith('live-'):
            book = 'LIVE'
        else:
            book = 'UNKNOWN'
        phase = _phase(leg)
        client_id = f'{position_id}:{leg}'
        orders.append({
            'book': book,
            'position_id': position_id,
            'contract_code': contract_by_key.get(key),
            'side': group[0]['side'],
            'leg': leg,
            'client_order_id': client_id,
            'signal_price': None,
            'order_price': None,
            'order_sent_at': None,
            'cancel_count': None,
            'amend_count': None,
            'contract_size': group[0].get('contract_size'),
        })
        for fill in group:
            normalized.append({
                'position_id': position_id,
                'client_order_id': client_id,
                'fill_id': fill['fill_id'],
                'quantity': fill['quantity'],
                'price': fill['price'],
                'fee': fill['fee'],
                'filled_at': fill['filled_at'],
                'contract_size': fill.get('contract_size'),
            })
        if phase == 'OPEN' and len({item['side'] for item in group}) != 1:
            raise ValueError('同一腿方向不一致')
    return analyze_bundle({'book': 'PAPER', 'orders': orders, 'fills': normalized})


def _cell(value):
    if value is None:
        return ''
    if isinstance(value, Decimal):
        text = format(value, 'f')
        if '.' in text:
            text = text.rstrip('0').rstrip('.')
        return text or '0'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    return str(value)


def _write_csv(path, rows, columns):
    with path.open('w', encoding='utf-8', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, extrasaction='ignore', lineterminator='\n')
        writer.writeheader()
        for row in rows:
            writer.writerow({column: _cell(row.get(column)) for column in columns})


def render_markdown(report):
    lines = [
        '# 执行成本账',
        '',
        BOUNDARY,
        '',
        f"回测往返成本假设 {format(report['assumed_rt_bps'], 'f')} bps（与 research Config.rt_cost 默认值一致）。"
        '对比表只含开仓滑点、平仓滑点、开平手续费，不含资金费。资金费按持仓期单独汇总。',
        '正滑点表示成交比参考价更差。缺信号价或下单价时滑点留空，不用成交价或信号价回填。',
        '逐笔账把滑点拆成三段：S1 信号价到下单价，S2 下单价到成交均价，S3 成交均价相对下一根 bar 开盘价。',
        '与 12 bps 假设对比的实际总成本只含 S3 和手续费，不含 S1、S2 和资金费。',
        '汇总按 LIVE/PAPER 与币种分组。可计算成本的成交腿不少于 200 且至少两个币种簇时，'
        '才给出按币种聚类的均值和 95% 区间；否则只保留分位数，并标明样本不足不下结论。',
        '',
        '## 与 12 bps 假设对比',
        '',
        '| 范围 | 分组 | 笔数 | 中位往返 bps | p75 | p90 | 假设 | 中位减假设 | 排除持仓 |',
        '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ]
    for row in report['comparison']:
        lines.append('| ' + ' | '.join(_cell(row[column]) for column in (
            'scope', 'key', 'n', 'median_rt_bps', 'p75_rt_bps', 'p90_rt_bps',
            'assumed_rt_bps', 'median_minus_assumed_bps', 'excluded_positions')) + ' |')
    lines.extend(['', '## 成本分布', ''])
    lines.append('| 合约 | 方向 | 开平 | 指标 | n | 中位 | p75 | p90 |')
    lines.append('| --- | --- | --- | --- | --- | --- | --- | --- |')
    for row in report['distribution']:
        lines.append('| ' + ' | '.join(_cell(row[column]) for column in (
            'contract', 'side', 'phase', 'metric', 'n', 'median', 'p75', 'p90')) + ' |')
    lines.append('')
    return '\n'.join(lines)


def write_report(report, directory):
    out = Path(directory)
    out.mkdir(parents=True, exist_ok=True)
    public_legs = [{key: value for key, value in row.items() if not key.startswith('_')} for row in report['legs']]
    _write_csv(out / 'legs.csv', public_legs, [
        'book', 'position_id', 'contract', 'side', 'phase', 'client_order_id',
        'signal_price', 'order_price', 'fill_vwap', 'slippage_vs_signal_bps', 'slippage_vs_order_bps',
        'fee_usdt', 'fee_bps', 'notional_usdt', 'partial_fill_count', 'order_to_first_fill_ms',
        'cancel_count', 'amend_count', 'fill_count', 'coverage'])
    _write_csv(out / 'distribution.csv', report['distribution'], [
        'contract', 'side', 'phase', 'metric', 'n', 'median', 'p75', 'p90'])
    _write_csv(out / 'comparison.csv', report['comparison'], [
        'scope', 'key', 'n', 'median_rt_bps', 'p75_rt_bps', 'p90_rt_bps',
        'assumed_rt_bps', 'median_minus_assumed_bps', 'excluded_positions'])
    _write_csv(out / 'funding.csv', report['funding'], [
        'position_id', 'contract', 'side', 'funding_paid_usdt', 'funding_received_usdt',
        'funding_net_usdt', 'event_count', 'position_open', 'window_start', 'window_end'])
    (out / 'cost.md').write_text(render_markdown(report), encoding='utf-8')
    from .orderpx import write_orderpx_outputs
    write_orderpx_outputs(report, out)
