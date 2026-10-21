"""Optional, replayable PAPER mirror. JSON owns execution; no exchange or risk writes."""
from __future__ import annotations

from decimal import Decimal as D
from hashlib import sha256
import json
import os
from pathlib import Path

from .mysql_store import (HtxTrendMySqlRepository, LedgerEvent, MySqlSettings,
                          PositionIntent, TradeAccount, TrendOrderIntent, connect)

STRATEGY = 'HTX_TREND_15M_V2'
ACCOUNT = 'paper:htx-trend-paper-primary'
FEE_RATE = D('.0004')
CLIENT_ORDER_ID_MAX_LEN = 128
LIVE_POSITION_SUMMARY_SQL = """SELECT COUNT(*) AS position_count,
 SUM(final_net_profit) AS net_profit FROM htx_trend_position
 WHERE account_label <> 'paper'"""
PAPER_ACCOUNT_SQL = """INSERT IGNORE INTO htx_trade_account
 (account_label,account_role,enabled,trend_enabled,mirror_enabled,sync_leverage,note)
 VALUES ('paper','PAPER',0,0,0,0,'paper:htx-trend-paper-primary; simulated only')"""


def _validate_client_order_id(client_order_id):
    if not isinstance(client_order_id, str) or not client_order_id:
        raise ValueError('client_order_id must be a non-empty str')
    if len(client_order_id) > CLIENT_ORDER_ID_MAX_LEN:
        raise ValueError(f'client_order_id length {len(client_order_id)} exceeds maximum '
                         f'{CLIENT_ORDER_ID_MAX_LEN}')


def ensure_paper_account(repo):
    repo._write(PAPER_ACCOUNT_SQL, ())
    row = repo._one("SELECT * FROM htx_trade_account WHERE account_label='paper' FOR UPDATE", ())
    if row is None or row['account_role'] not in {'PAPER', 'FOLLOWER'} or any(
            int(row.get(k) or 0) for k in ('enabled', 'trend_enabled', 'mirror_enabled')):
        raise ValueError('paper account must be disabled for live execution')
    return TradeAccount(int(row['id']), 'paper', row['account_role'], False, False, '')


def _key(value):
    return value if value.startswith('paper-') else 'paper-' + value


def backfill_paper_state_to_mysql(state_json, repo):
    """Replay a committed PaperFileLedger snapshot using only the four allowed tables.

    Accept a dict, JSON text or path. Caller supplies the repository; this function
    never connects. DONE is the LIVE schema equivalent of JSON CLOSED.
    All writes are atomic, serialized by the paper account row lock.
    """
    if isinstance(state_json, Path):
        state = json.loads(state_json.read_text())
    elif isinstance(state_json, str):
        state = json.loads(state_json) if state_json.lstrip().startswith('{') else json.loads(Path(state_json).read_text())
    else:
        state = state_json
    if state['identity']['instance'] != ACCOUNT.split(':', 1)[1] or state['identity']['strategy'] != STRATEGY:
        raise ValueError('unsupported paper state identity')
    orders, positions = state['orders'], state['positions']
    # Preflight before any writes; preserve authoritative IDs without truncation.
    for order in orders.values():
        _validate_client_order_id(order.get('client_order_id'))
    fills_by_order = {}
    seen = set()
    for fill in state['fills']:
        if fill['fill_id'] in seen:
            raise ValueError('duplicate fill id in authoritative state')
        seen.add(fill['fill_id'])
        if fill['account_id'] != ACCOUNT or orders[fill['order_id']]['account_id'] != ACCOUNT:
            raise ValueError('non-paper fill/account')
        fills_by_order.setdefault(fill['order_id'], []).append(fill)
    # Resolve entry orders by the actual runtime's account/contract/side/run link.
    groups = []
    consumed = set()
    for key, position in positions.items():
        if position['account_id'] != ACCOUNT:
            raise ValueError('non-paper position')
        linked = [(oid, o) for oid, o in orders.items() if o.get('position_id') == key or (
            o.get('position_id') is None and o['account_id'] == ACCOUNT and
            o['contract_code'] == position['contract_code'] and o['position_side'] == position['side'] and
            o.get('strategy_run_id') == position['strategy_run_id'] and oid in fills_by_order)]
        entries = [(oid, o) for oid, o in linked if o.get('position_id') is None]
        if len(entries) != 1:
            raise ValueError('paper entry linkage must be unambiguous')
        linked.sort(key=lambda pair: pair[1].get('position_id') is not None)
        if consumed.intersection(oid for oid, _ in linked):
            raise ValueError('order linked to multiple positions')
        consumed.update(oid for oid, _ in linked)
        groups.append((key, position, linked))
    if set(fills_by_order) - consumed:
        raise ValueError('orphan paper fills')
    repo._connection.rollback()
    repo._in_transaction = True
    added = 0
    try:
        account = ensure_paper_account(repo)
        for key, p, linked in groups:
            context = state['contexts'][key]
            size = D(str(context['contract_size']))
            if not size.is_finite() or size <= 0:
                raise ValueError('invalid contract size')
            entry_fills = fills_by_order[linked[0][0]]
            entered = sum(D(str(f['quantity'])) for f in entry_fills)
            entry = sum(D(str(f['price'])) * D(str(f['quantity'])) for f in entry_fills) / entered
            pk, model, run = _key(key), context['model'], p['strategy_run_id']
            existing = repo._one('SELECT * FROM htx_trend_position WHERE position_key=%s', (pk,))
            if existing and (int(existing['account_id']) != account.account_id or existing['account_label'] != 'paper' or existing['strategy_name'] != STRATEGY):
                raise ValueError('position key belongs to another writer')
            pid = repo.record_position_intent(PositionIntent(pk, run, p['contract_code'], p['side'], size,
                int(entered), 10, account, model, None, 'PAPER', 'simulated fill',
                D(str(context['initial_stop'])) if context.get('initial_stop') else None,
                None, None, None, strategy_name=STRATEGY))
            closed = entry_fee = close_fee = gross = close_value = D(0)
            changed = existing is None
            for oid, order in linked:
                fs = fills_by_order.get(oid, [])
                if not fs:
                    continue
                client = order['client_order_id']
                if not client.startswith('paper-') or order['account_id'] != ACCOUNT:
                    raise ValueError('non-paper order')
                leg = 'ENTRY' if order.get('position_id') is None else 'CLOSE'
                old = repo._one('SELECT * FROM htx_trend_order WHERE client_order_id=%s', (client,))
                if old and (int(old['account_id']) != account.account_id or old['account_label'] != 'paper' or int(old['position_id']) != pid):
                    raise ValueError('order key belongs to another writer')
                order_id = repo.record_order_intent(TrendOrderIntent(pid, pk, order['strategy_run_id'], p['contract_code'],
                    leg, p['side'], order['side'], p['side'], leg != 'ENTRY', client, order['quantity'],
                    account, model, None, 'PAPER simulated', strategy_name=STRATEGY))
                quantity = value = fee = profit = D(0)
                new_fill = False
                for f in fs:
                    q, price = D(str(f['quantity'])), D(str(f['price']))
                    if not q.is_finite() or not price.is_finite() or q <= 0 or price <= 0:
                        raise ValueError('invalid simulated fill')
                    amount = q * price * size
                    cost = amount * FEE_RATE
                    pnl = (price-entry) * q * size * (1 if p['side'] == 'LONG' else -1) if leg != 'ENTRY' else D(0)
                    quantity += q; value += q * price; fee += cost; profit += pnl
                    event_key = 'paper-' + sha256(f'{ACCOUNT}:{f["fill_id"]}'.encode()).hexdigest()
                    if repo._one('SELECT event_key FROM coin_strategy_ledger_event WHERE event_key=%s', (event_key,)):
                        continue
                    repo.record_ledger_event(LedgerEvent(event_key, 'OPEN_FILL' if leg == 'ENTRY' else 'CLOSE_FILL',
                        account, 'TREND', STRATEGY, model, run, None, p['contract_code'], pid, order_id, client,
                        p['side'], 'PAPER_SIMULATED', execution_price_usdt=price, entry_price_usdt=entry,
                        close_price_usdt=price if leg != 'ENTRY' else None, filled_contracts=q, contract_size=size,
                        amount_usdt=amount, fee_usdt=cost, gross_pnl_usdt=pnl if leg != 'ENTRY' else None,
                        # 开仓费随实际平仓数量分摊，手续费列仍仅记录本次成交费用。
                        net_pnl_after_fee=pnl-cost-entry_fee*q/entered if leg != 'ENTRY' else None))
                    added += 1; new_fill = True
                if quantity > D(str(order['quantity'])):
                    raise ValueError('fill quantity exceeds order')
                if new_fill or old is None:
                    repo.reconcile_order(position_id=pid, leg=leg, client_order_id=client,
                        status='FILLED' if quantity == D(str(order['quantity'])) else 'PARTIALLY_FILLED',
                        exchange_order_id=None, exchange_client_order_id=None, exchange_state='PAPER_SIMULATED',
                        filled_contracts=quantity, average_price=value/quantity, fee=fee, fee_currency='USDT',
                        realized_profit=profit if leg != 'ENTRY' else None, message='PAPER_SIMULATED')
                    changed = True
                if leg == 'ENTRY':
                    entry_fee += fee
                else:
                    closed += quantity; close_fee += fee; gross += profit; close_value += value
            remaining = entered - closed
            if remaining < 0 or remaining != D(str(p['quantity'])) or (p['status'] == 'CLOSED') != (remaining == 0):
                raise ValueError('state position/fill quantity mismatch')
            if changed:
                total_fee = entry_fee + close_fee
                repo._write('''UPDATE htx_trend_position SET status=%s,entry_filled_contracts=%s,
                    remaining_contract_volume=%s,entry_avg_price=%s,entry_fee=%s,close_filled_contracts=%s,
                    close_avg_price=%s,close_fee=%s,realized_profit=%s,final_gross_profit=%s,
                    final_total_fee=%s,final_net_profit=%s,final_pnl_source=%s,
                    final_pnl_calculated_at=CURRENT_TIMESTAMP(3) WHERE id=%s''',
                    ('DONE' if remaining == 0 else 'ACTIVE', entered, remaining, entry, entry_fee, closed,
                     close_value/closed if closed else None, close_fee, gross,
                     gross if remaining == 0 else None, total_fee if remaining == 0 else None,
                     gross-total_fee if remaining == 0 else None, 'PAPER_SIMULATED', pid))
        repo._connection.commit()
        return {'fills_added': added, 'positions': len(groups), 'orders': len(fills_by_order)}
    except Exception:
        repo._connection.rollback()
        raise
    finally:
        repo._in_transaction = False


def mirror_checkpoint(state):
    """Dedicated opt-in DB configuration, opened only after JSON checkpoint success."""
    if not os.getenv('HTX_PAPER_DB_HOST'):
        return
    repo = None
    try:
        repo = HtxTrendMySqlRepository(connect(MySqlSettings.from_env('HTX_PAPER_DB_')))
        backfill_paper_state_to_mysql(state, repo)
    finally:
        if repo is not None:
            repo.close()
