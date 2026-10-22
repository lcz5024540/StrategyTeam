"""逐笔下单价与三段滑点。只读账本，缺价不回填。"""
from __future__ import annotations

import json
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from .cost import (
    ASSUMED_RT_BPS,
    _adverse_bps,
    _cell,
    _decimal,
    _millis,
    _optional_decimal,
    _time,
    quantile,
)

MIN_N_FOR_INFERENCE = 200
CONCLUSION_INSUFFICIENT = '样本不足不下结论'
CONCLUSION_SINGLE_CLUSTER = '单簇无法估计聚类标准误'
CI_Z = Decimal('1.96')
CI_METHOD = '按contract聚类的CR1标准误，正态近似95%区间，z=1.96；只对S3加手续费'
_SOURCES = {'limit', 'mid', 'last'}

ORDERPX_COLUMNS = (
    'book', 'position_id', 'contract', 'side', 'phase', 'client_order_id',
    'signal_px', 'order_px', 'order_px_source', 'fill_vwap',
    'fill_qty', 'order_qty', 'partial_fill_ratio',
    'signal_ts', 'order_ts', 'first_fill_ts', 'full_fill_ts',
    'latency_signal_to_order_ms', 'latency_order_to_first_fill_ms', 'latency_first_to_full_fill_ms',
    'cancel_count', 'amend_count', 'fee_usdt', 'fee_bps',
    'funding_paid_usdt', 'funding_received_usdt', 'next_bar_open',
    's1_bps', 's2_bps', 's3_bps', 'total_cost_bps', 'total_cost_minus_assumed_bps',
    'missing_flags',
)
SUMMARY_COLUMNS = (
    'book', 'contract', 'n', 'n_total_cost',
    's1_p50', 's1_p75', 's1_p90',
    's2_p50', 's2_p75', 's2_p90',
    's3_p50', 's3_p75', 's3_p90',
    'total_cost_p50', 'total_cost_p75', 'total_cost_p90',
    'assumed_rt_bps', 'total_cost_median_minus_assumed_bps',
    'mean_total_cost_bps', 'total_cost_se_bps', 'total_cost_ci_low_bps', 'total_cost_ci_high_bps',
    'g_clusters', 'ci_method', 'conclusion',
)


def _blank(value):
    return value is None or value == '' or value == 'NULL'


def _named_decimal(order, *keys):
    for key in keys:
        if key not in order or _blank(order.get(key)):
            continue
        return _optional_decimal(order.get(key))
    return None


def _price_for_source(order, source):
    if source == 'limit':
        return _named_decimal(order, 'order_price', 'limit_price')
    if source == 'mid':
        price = _named_decimal(order, 'mid', 'mid_px')
        if price is None:
            return _named_decimal(order, 'order_price')
        return price
    if source == 'last':
        price = _named_decimal(order, 'last', 'last_px')
        if price is None:
            return _named_decimal(order, 'order_price')
        return price
    return None


def resolve_order_px(order):
    """下单价只来自委托价、下单时 mid 或 last。不用信号价，也不用成交价。"""
    raw = order.get('order_px_source')
    order_type = str(order.get('order_type') or '').strip().lower()
    if not _blank(raw):
        source = str(raw).strip().lower()
        if source not in _SOURCES:
            return None, None, ['order_px', 'order_px_source']
    elif order_type == 'limit':
        source = 'limit'
    elif order_type in {'market', 'mkt'}:
        if _named_decimal(order, 'mid', 'mid_px') is not None:
            source = 'mid'
        elif _named_decimal(order, 'last', 'last_px') is not None:
            source = 'last'
        else:
            return None, None, ['order_px', 'order_px_source']
    else:
        price = _named_decimal(order, 'order_price')
        flags = ['order_px_source']
        if price is None:
            flags.insert(0, 'order_px')
        return price, None, flags
    price = _price_for_source(order, source)
    if price is None:
        return None, source, ['order_px']
    return price, source, []


def _iso(value):
    if value is None:
        return None
    return value.isoformat()


def _count(order, key):
    value = order.get(key)
    if _blank(value):
        return None
    return int(value)


def _clock(order, *keys):
    for key in keys:
        if key in order and not _blank(order.get(key)):
            return _time(order.get(key))
    return None


def cluster_mean_ci(values, clusters, z=CI_Z):
    """样本均值的聚类稳健标准误（CR1）与正态近似 95% 区间。

    簇少于 2 个时返回 None。公式：
    se = sqrt( (G/(G-1)) * Σ_g (Σ_{i in g} (y_i - ȳ))^2 / N^2 )。
    """
    if len(values) != len(clusters):
        raise ValueError('观测与簇长度不一致')
    if not values:
        raise ValueError('空样本不能计算聚类区间')
    count = len(values)
    mean = sum(values, Decimal(0)) / Decimal(count)
    grouped = defaultdict(lambda: Decimal(0))
    for value, cluster in zip(values, clusters):
        grouped[cluster] += value - mean
    groups = len(grouped)
    if groups < 2:
        return None
    meat = sum((score * score for score in grouped.values()), Decimal(0))
    variance = (Decimal(groups) / Decimal(groups - 1)) * meat / Decimal(count * count)
    error = variance.sqrt()
    half = z * error
    return {
        'n': count,
        'g': groups,
        'mean': mean,
        'se': error,
        'ci_low': mean - half,
        'ci_high': mean + half,
        'z': z,
    }


def _fill_times(fills):
    ordered = sorted(fills, key=lambda fill: (_time(fill['filled_at']), str(fill.get('fill_id'))))
    times = [_time(fill['filled_at']) for fill in ordered]
    quantity = sum((_decimal(fill['quantity'], '成交数量') for fill in ordered), Decimal(0))
    return quantity, times[0], times[-1]


def _funding_amounts(funding_known, funding):
    if not funding_known:
        return None, None, ['funding']
    if not funding:
        return Decimal(0), Decimal(0), []
    return funding['funding_paid_usdt'], funding['funding_received_usdt'], []


def _orderpx_row(order, leg, fills, funding, funding_known):
    flags = []
    order_px, source, source_flags = resolve_order_px(order)
    flags.extend(source_flags)
    signal = leg['signal_price']
    if signal is None:
        flags.append('signal_px')
    next_open = _named_decimal(order, 'next_bar_open')
    if next_open is None:
        flags.append('next_bar_open')
    order_qty = _named_decimal(order, 'order_qty', 'request_volume')
    if order_qty is not None and order_qty <= 0:
        raise ValueError('委托量必须为正')
    fill_qty, first_at, last_at = _fill_times(fills)
    ratio = None
    full_at = None
    if order_qty is None:
        flags.extend(('order_qty', 'partial_fill_ratio', 'full_fill_ts'))
    else:
        ratio = fill_qty / order_qty
        if fill_qty < order_qty:
            flags.append('full_fill_ts')
        else:
            full_at = last_at
    signal_at = _clock(order, 'signal_ts', 'signal_at')
    order_at = _clock(order, 'order_ts', 'order_sent_at')
    if signal_at is None:
        flags.append('signal_ts')
    if order_at is None:
        flags.append('order_ts')
    cancel = _count(order, 'cancel_count')
    amend = _count(order, 'amend_count')
    if cancel is None:
        flags.append('cancel_count')
    if amend is None:
        flags.append('amend_count')
    paid, received, funding_flags = _funding_amounts(funding_known, funding)
    flags.extend(funding_flags)
    side, phase = leg['side'], leg['phase']
    s1 = _adverse_bps(side, phase, signal, order_px)
    s2 = _adverse_bps(side, phase, order_px, leg['fill_vwap'])
    s3 = _adverse_bps(side, phase, next_open, leg['fill_vwap'])
    total = None if s3 is None else s3 + leg['fee_bps']
    return {
        'book': leg['book'],
        'position_id': leg['position_id'],
        'contract': leg['contract'],
        'side': side,
        'phase': phase,
        'client_order_id': leg['client_order_id'],
        'signal_px': signal,
        'order_px': order_px,
        'order_px_source': source,
        'fill_vwap': leg['fill_vwap'],
        'fill_qty': fill_qty,
        'order_qty': order_qty,
        'partial_fill_ratio': ratio,
        'signal_ts': _iso(signal_at),
        'order_ts': _iso(order_at),
        'first_fill_ts': _iso(first_at),
        'full_fill_ts': _iso(full_at),
        'latency_signal_to_order_ms': _millis(signal_at, order_at),
        'latency_order_to_first_fill_ms': _millis(order_at, first_at),
        'latency_first_to_full_fill_ms': _millis(first_at, full_at),
        'cancel_count': cancel,
        'amend_count': amend,
        'fee_usdt': leg['fee_usdt'],
        'fee_bps': leg['fee_bps'],
        'funding_paid_usdt': paid,
        'funding_received_usdt': received,
        'next_bar_open': next_open,
        's1_bps': s1,
        's2_bps': s2,
        's3_bps': s3,
        'total_cost_bps': total,
        'total_cost_minus_assumed_bps': None if total is None else total - ASSUMED_RT_BPS,
        'missing_flags': ['missing_' + name for name in sorted(set(flags))],
    }


def build_orderpx(bundle, legs, funding_rows):
    funding_known = 'funding' in bundle and bundle['funding'] is not None
    funding_by_position = {row['position_id']: row for row in funding_rows}
    orders = {order['client_order_id']: order for order in bundle.get('orders') or []}
    fills_by_order = defaultdict(list)
    for fill in bundle.get('fills') or []:
        fills_by_order[fill['client_order_id']].append(fill)
    return [
        _orderpx_row(
            orders[leg['client_order_id']], leg, fills_by_order[leg['client_order_id']],
            funding_by_position.get(leg['position_id']), funding_known)
        for leg in legs
    ]


def _quantile(values, probability):
    if not values:
        return None
    return quantile(values, probability)


def _summary_row(book, contract, rows):
    def series(key):
        return [row[key] for row in rows if row.get(key) is not None]

    costs = []
    clusters = []
    for row in rows:
        if row.get('total_cost_bps') is None:
            continue
        costs.append(row['total_cost_bps'])
        clusters.append(row.get('contract') or '')
    groups = len(set(clusters))
    mean = error = low = high = None
    method = ''
    if len(costs) < MIN_N_FOR_INFERENCE:
        conclusion = CONCLUSION_INSUFFICIENT
    elif groups < 2:
        conclusion = CONCLUSION_SINGLE_CLUSTER
    else:
        interval = cluster_mean_ci(costs, clusters)
        conclusion = ''
        method = CI_METHOD
        mean, error = interval['mean'], interval['se']
        low, high = interval['ci_low'], interval['ci_high']
    median = _quantile(costs, Decimal('0.5'))
    s1, s2, s3 = series('s1_bps'), series('s2_bps'), series('s3_bps')
    return {
        'book': book,
        'contract': contract,
        'n': len(rows),
        'n_total_cost': len(costs),
        's1_p50': _quantile(s1, Decimal('0.5')),
        's1_p75': _quantile(s1, Decimal('0.75')),
        's1_p90': _quantile(s1, Decimal('0.9')),
        's2_p50': _quantile(s2, Decimal('0.5')),
        's2_p75': _quantile(s2, Decimal('0.75')),
        's2_p90': _quantile(s2, Decimal('0.9')),
        's3_p50': _quantile(s3, Decimal('0.5')),
        's3_p75': _quantile(s3, Decimal('0.75')),
        's3_p90': _quantile(s3, Decimal('0.9')),
        'total_cost_p50': median,
        'total_cost_p75': _quantile(costs, Decimal('0.75')),
        'total_cost_p90': _quantile(costs, Decimal('0.9')),
        'assumed_rt_bps': ASSUMED_RT_BPS,
        'total_cost_median_minus_assumed_bps': None if median is None else median - ASSUMED_RT_BPS,
        'mean_total_cost_bps': mean,
        'total_cost_se_bps': error,
        'total_cost_ci_low_bps': low,
        'total_cost_ci_high_bps': high,
        'g_clusters': groups,
        'ci_method': method,
        'conclusion': conclusion,
    }


def summarize_orderpx(rows):
    by_book = defaultdict(list)
    for row in rows:
        by_book[row.get('book') or ''].append(row)
    summary = []
    for book in sorted(by_book):
        chosen = by_book[book]
        summary.append(_summary_row(book, '*', chosen))
        contracts = sorted({row.get('contract') or '' for row in chosen})
        for contract in contracts:
            summary.append(_summary_row(
                book, contract, [row for row in chosen if (row.get('contract') or '') == contract]))
    return summary


def _csv_row(row):
    ready = dict(row)
    flags = ready.get('missing_flags')
    if isinstance(flags, list):
        ready['missing_flags'] = ','.join(flags)
    return ready


def _json_row(row):
    payload = {}
    for column in ORDERPX_COLUMNS:
        value = row.get(column)
        if isinstance(value, Decimal):
            value = _cell(value)
        payload[column] = value
    return payload


def write_orderpx_outputs(report, directory):
    from .cost import _write_csv

    out = Path(directory)
    rows = report.get('orderpx') or []
    _write_csv(out / 'orderpx.csv', [_csv_row(row) for row in rows], ORDERPX_COLUMNS)
    lines = [json.dumps(_json_row(row), ensure_ascii=False) for row in rows]
    text = '\n'.join(lines)
    if text:
        text += '\n'
    (out / 'orderpx.jsonl').write_text(text, encoding='utf-8')
    _write_csv(out / 'orderpx_summary.csv', report.get('orderpx_summary') or [], SUMMARY_COLUMNS)
