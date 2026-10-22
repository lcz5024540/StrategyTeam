"""LIVE split-event projections and offline, insert-only repair of missing types.

Inputs must be an account-scoped, consistent export of orders and ledger rows.
No exchange or database connection is opened by this module.
"""
from dataclasses import dataclass, replace
from decimal import Decimal
import logging

from .mysql_store import LedgerEvent, TradeAccount

ZERO = Decimal('0')
TYPES = ('CLOSE_FILL', 'FEE', 'REALIZED_PNL')


def number(row, key):
    return Decimal(str(row.get(key) or 0))


def matching_events(order, events):
    return [e for e in events
            if str(e.get('position_id')) == str(order['position_id'])
            and str(e.get('client_order_id')) == str(order['client_order_id'])]


@dataclass(frozen=True)
class IncompleteCloseAlert:
    position_id: int
    client_order_id: str
    missing_types: tuple[str, ...]


def detect_incomplete_close_ledger(orders, ledger_events):
    alerts = []
    for order in orders:
        if order['leg'] not in {'CLOSE', 'PARTIAL_CLOSE'}:
            continue
        if not (order['status'] == 'FILLED' or (
            order['status'] in {'CANCELLED', 'PARTIALLY_CANCELLED', 'REJECTED'}
            and number(order, 'filled_contracts') > ZERO)):
            continue
        present = {e['event_type'] for e in matching_events(order, ledger_events)}
        missing = tuple(t for t in TYPES if t not in present)
        if missing:
            alerts.append(IncompleteCloseAlert(order['position_id'], order['client_order_id'], missing))
    return alerts


def emit_incomplete_close_alert(alert):
    logging.getLogger(__name__).warning(
        'LIVE_CLOSE_LEDGER_INCOMPLETE position_id=%s client_order_id=%s missing_types=%s',
        alert.position_id, alert.client_order_id, ','.join(alert.missing_types))


def split_close_events(base, *, fee, pnl, suffix=''):
    """The base describes a delta, or a full snapshot only for missing-type repair."""
    identity = f'{base.position_id}:{base.client_order_id}{suffix}'
    fill = replace(base, event_key=f'trend-close-fill:{identity}', event_type='CLOSE_FILL',
                   fee_usdt=ZERO, gross_pnl_usdt=ZERO, net_pnl_after_fee=ZERO)
    # Economic events do not duplicate fill quantity/turnover.
    economics = replace(fill, filled_contracts=None, amount_usdt=None)
    result = [fill]
    if fee is not None:
        result.append(replace(economics, event_key=f'trend-fee:CLOSE_FILL:{identity}',
                              event_type='FEE', fee_usdt=fee, net_pnl_after_fee=-fee))
    if pnl is not None:
        result.append(replace(economics, event_key=f'trend-realized-pnl:{identity}',
                              event_type='REALIZED_PNL', gross_pnl_usdt=pnl, net_pnl_after_fee=pnl))
    return result


def plan_close_ledger_backfill(position_row, close_order_row, existing_events):
    """Repair missing event types, never reprice existing rows or guess absent facts.

    Legacy combined FILL economics require manual review, not duplicate split money.
    This planner is for terminal orders; run on a quiescent, consistent export.
    """
    p, o = position_row, close_order_row
    if str(p['id']) != str(o['position_id']):
        raise ValueError('order does not belong to position')
    alerts = detect_incomplete_close_ledger([o], existing_events)
    if not alerts:
        return []
    existing = matching_events(o, existing_events)
    if any(e['event_type'] == 'CLOSE_FILL' and any(number(e, k) != ZERO for k in
           ('fee_usdt', 'gross_pnl_usdt', 'net_pnl_after_fee')) for e in existing):
        raise ValueError('legacy combined CLOSE_FILL requires manual review')
    if number(o, 'filled_contracts') <= ZERO or number(o, 'average_price') <= ZERO:
        raise ValueError('backfill requires confirmed quantity and price')
    missing = alerts[0].missing_types
    if 'FEE' in missing and (o.get('fee') is None or str(o.get('fee_currency') or '').upper() != 'USDT'):
        raise ValueError('backfill requires confirmed USDT fee')
    if 'REALIZED_PNL' in missing and o.get('realized_profit') is None:
        raise ValueError('backfill requires confirmed realized_profit')
    if number(p, 'contract_size') <= ZERO:
        raise ValueError('backfill requires contract_size')
    account = TradeAccount(p['account_id'], p.get('account_label') or str(p['account_id']),
                           'PRIMARY', True, False, '')
    base = LedgerEvent('', 'CLOSE_FILL', account, p['strategy_family'], p['strategy_name'],
        p['model_name'], p.get('run_id'), p.get('factor_snapshot_id'), p['contract_code'],
        p['id'], o['id'], o['client_order_id'], p['side'], 'offline LIVE missing-type backfill',
        execution_price_usdt=number(o, 'average_price'), close_price_usdt=number(o, 'average_price'),
        filled_contracts=number(o, 'filled_contracts'), contract_size=number(p, 'contract_size'),
        amount_usdt=number(o, 'filled_contracts') * number(o, 'average_price') * number(p, 'contract_size'))
    keys = {e['event_key'] for e in existing_events}
    return [e for e in split_close_events(base, fee=number(o, 'fee'), pnl=number(o, 'realized_profit'))
            if e.event_type in missing and e.event_key not in keys]


def apply_close_ledger_backfill(repo, events, *, dry_run=True):
    """Use the repository's INSERT IGNORE; stats count attempts, not inserted rows.

    Caller owns transaction scope. Replaying a plan is safe by event_key.
    """
    events = list(events)
    if not dry_run:
        for event in events:
            repo.record_ledger_event(event)
    return {'planned': len(events), 'insert_attempts': 0 if dry_run else len(events), 'dry_run': dry_run}
