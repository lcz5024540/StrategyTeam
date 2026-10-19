"""Exact per-trade bps ledger. Positive raw funding is paid by longs.

Funding maps coin -> {settlement_timestamp: rate_bps}. Each trade supplies the
complete expected settlement calendar in (entry_ts, exit_ts]; no proxy fills.
Price gross uses simple return on entry notional, signed by side (+1/-1).
"""
from decimal import Decimal, InvalidOperation

RT_COST = Decimal('12')


def decimal(value):
    try:
        result = Decimal(str(value))
    except InvalidOperation as exc:
        raise ValueError('invalid decimal amount') from exc
    if not result.is_finite():
        raise ValueError('amounts must be finite')
    return result


def build_ledger(trades, funding, *, rt_cost=RT_COST):
    cost = decimal(rt_cost)
    if cost < 0:
        raise ValueError('cost must be nonnegative')
    rows, excluded = [], []
    for trade in trades:
        coin, side = trade['coin'], trade['side']
        if side not in (-1, 1) or trade['exit_ts'] <= trade['entry_ts']:
            raise ValueError('invalid side or holding period')
        expected = trade.get('funding_timestamps')
        rates = funding.get(coin)
        if expected is None or rates is None:
            excluded.append(dict(id=trade['id'], coin=coin, reason='MISSING_FUNDING_CALENDAR' if expected is None else 'MISSING_FUNDING_COIN'))
            continue
        if len(set(expected)) != len(expected) or any(not trade['entry_ts'] < t <= trade['exit_ts'] for t in expected):
            raise ValueError('invalid settlement calendar')
        missing = [t for t in expected if rates.get(t) is None]
        if missing:
            excluded.append(dict(id=trade['id'], coin=coin, reason='MISSING_FUNDING_PERIOD', timestamps=missing))
            continue
        try:
            drag = sum((decimal(rates[t]) for t in expected), Decimal(0)) * side
        except ValueError:
            excluded.append(dict(id=trade['id'], coin=coin, reason='INVALID_FUNDING'))
            continue
        if 'gross_bps' in trade:
            gross = decimal(trade['gross_bps'])  # already signed
        else:
            entry, exit_ = decimal(trade['entry_open']), decimal(trade['exit_open'])
            if min(entry, exit_) <= 0:
                raise ValueError('open prices must be positive')
            gross = (exit_ / entry - 1) * 10000 * side
        rows.append(dict(id=trade['id'], coin=coin, gross_bps=gross,
                         rt_cost_bps=cost, funding_cost_bps=drag,
                         net_bps=gross-cost-drag))
    return dict(ledger=rows, excluded=excluded)
