"""Atomic cumulative HTX order projection. Exchange facts, never submit responses, own fills."""
from __future__ import annotations

from decimal import Decimal
from hashlib import sha256
from typing import Any

from .mysql_store import LedgerEvent

ZERO = Decimal('0')
PENDING = {'INTENT', 'ACCEPTED', 'PARTIALLY_FILLED', 'UNKNOWN'}
TERMINAL = {'FILLED', 'CANCELLED', 'PARTIALLY_CANCELLED', 'REJECTED'}


def number(row: dict, key: str) -> Decimal:
    return Decimal(str(row.get(key) or 0))


def apply_exchange_order(repo, *, account, instance, position_id: int, leg: str, client_order_id: str,
                         status: str, exchange_order_id: int | None, exchange_client_order_id: int | None,
                         exchange_state: str, filled_contracts: Decimal, average_price: Decimal | None,
                         fee: Decimal | None, fee_currency: str | None, realized_profit: Decimal | None) -> None:
    # _one starts a transaction under PyMySQL autocommit=False. End any prior
    # read snapshot before acquiring the position/order locks in a fixed order.
    repo._connection.rollback()
    repo._in_transaction = True
    try:
        position = repo._one('SELECT * FROM htx_trend_position WHERE id=%s AND account_id=%s '
                             'AND strategy_family=%s AND strategy_name=%s FOR UPDATE',
                             (position_id, account.account_id, instance.strategy_family, instance.strategy_name))
        if position is None:
            raise ValueError('order position does not belong to this writer')
        old = repo._one('SELECT * FROM htx_trend_order WHERE position_id=%s AND leg=%s AND client_order_id=%s FOR UPDATE',
                        (position_id, leg, client_order_id))
        if old is None:
            raise ValueError('cannot reconcile an unpersisted order')
        previous = number(old, 'filled_contracts')
        if filled_contracts < previous or (old['status'] in TERMINAL and status in PENDING):
            raise ValueError('stale exchange order snapshot')
        if filled_contracts < ZERO or filled_contracts > number(old, 'request_volume'):
            raise ValueError('invalid cumulative fill quantity')
        resolved_average = average_price if average_price is not None else (
            number(old, 'average_price') if filled_contracts == previous else None
        )
        if filled_contracts > ZERO and (resolved_average is None or resolved_average <= ZERO):
            raise ValueError('confirmed fill lacks a positive average price')
        resolved_fee = fee if fee is not None else number(old, 'fee')
        resolved_fee_currency = str(fee_currency or old.get('fee_currency') or 'USDT')
        resolved_realized_raw = realized_profit if realized_profit is not None else old.get('realized_profit')
        resolved_realized = Decimal(str(resolved_realized_raw)) if resolved_realized_raw is not None else None
        if resolved_fee_currency.upper() != 'USDT' and resolved_fee != ZERO:
            raise ValueError('cannot book non-USDT fee as USDT')
        delta = filled_contracts - previous
        previous_value = previous * number(old, 'average_price')
        new_value = filled_contracts * (resolved_average or ZERO)
        delta_value = new_value - previous_value
        fee_delta = resolved_fee - number(old, 'fee')
        pnl_delta = None if resolved_realized is None else resolved_realized - number(old, 'realized_profit')
        repo.reconcile_order(position_id=position_id, leg=leg, client_order_id=client_order_id, status=status,
            exchange_order_id=exchange_order_id or old.get('exchange_order_id'),
            exchange_client_order_id=exchange_client_order_id or old.get('exchange_client_order_id'),
            exchange_state=exchange_state, filled_contracts=filled_contracts, average_price=resolved_average,
            fee=resolved_fee, fee_currency=resolved_fee_currency, realized_profit=resolved_realized,
            message='exchange cumulative reconciliation')
        orders = repo._all('SELECT * FROM htx_trend_order WHERE position_id=%s', (position_id,))
        entries = [row for row in orders if row['leg'] == 'ENTRY']
        closes = [row for row in orders if row['leg'] in {'CLOSE', 'PARTIAL_CLOSE'}]
        entered = sum((number(row, 'filled_contracts') for row in entries), ZERO)
        closed = sum((number(row, 'filled_contracts') for row in closes), ZERO)
        if closed > entered:
            raise ValueError('close fills exceed confirmed entry quantity')
        remaining = entered - closed
        entry_fee = sum((number(row, 'fee') for row in entries), ZERO)
        close_fee = sum((number(row, 'fee') for row in closes), ZERO)
        entry_price = sum((number(row, 'filled_contracts') * number(row, 'average_price') for row in entries), ZERO) / entered if entered else None
        close_price = sum((number(row, 'filled_contracts') * number(row, 'average_price') for row in closes), ZERO) / closed if closed else None
        filled_closes = [row for row in closes if number(row, 'filled_contracts') > ZERO]
        gross = (sum((number(row, 'realized_profit') for row in filled_closes), ZERO)
                 if all(row.get('realized_profit') is not None for row in filled_closes) else None)
        pending = [row for row in orders if row['status'] in PENDING]
        if any(row['status'] == 'UNKNOWN' for row in pending):
            position_status = 'UNKNOWN'
        elif any(row['leg'] != 'ENTRY' for row in pending):
            position_status = 'CLOSE_SENT'
        elif pending:
            position_status = 'ENTRY_SENT'
        else:
            position_status = 'ACTIVE' if remaining > ZERO else 'DONE' if entered > ZERO else 'REJECTED'
        repo._write('''UPDATE htx_trend_position SET status=%s,entry_filled_contracts=%s,remaining_contract_volume=%s,
          entry_avg_price=%s,entry_fee=%s,close_filled_contracts=%s,close_avg_price=%s,close_fee=%s,realized_profit=%s,
          stop_price=COALESCE(stop_price,invalidation_price),highest_price=COALESCE(highest_price,%s),
          lowest_price=COALESCE(lowest_price,%s),updated_at=CURRENT_TIMESTAMP(3) WHERE id=%s''',
          (position_status,entered,remaining,entry_price,entry_fee,closed,close_price,close_fee,gross,entry_price,entry_price,position_id))
        if delta or fee_delta or delta_value or (pnl_delta is not None and pnl_delta != ZERO):
            key = sha256(f'{old["id"]}:{filled_contracts}:{resolved_average}:{resolved_fee}:{resolved_realized}'.encode()).hexdigest()
            size = number(position, 'contract_size')
            repo.record_ledger_event(LedgerEvent(key, 'OPEN_FILL' if leg == 'ENTRY' else 'CLOSE_FILL', account,
                instance.strategy_family, instance.strategy_name, position['model_name'], position.get('run_id'),
                position.get('factor_snapshot_id'), position['contract_code'], position_id, old['id'], client_order_id,
                position['side'], 'exchange confirmed cumulative delta',
                execution_price_usdt=delta_value / delta if delta else resolved_average,
                entry_price_usdt=entry_price, close_price_usdt=close_price, filled_contracts=delta,
                contract_size=size, amount_usdt=delta_value * size, fee_usdt=fee_delta,
                gross_pnl_usdt=pnl_delta, net_pnl_after_fee=pnl_delta-fee_delta if pnl_delta is not None else None))
        if entered > ZERO:
            repo.mark_entry_cycle_active(position_id=position_id)
        if position_status == 'DONE':
            repo.mark_entry_cycle_terminal(position_id=position_id, reason=position.get('exit_reason') or 'confirmed full close')
            total_fee = entry_fee + close_fee
            repo._write('''UPDATE htx_trend_position SET final_gross_profit=%s,final_total_fee=%s,
              final_net_profit=%s,final_pnl_source=%s,final_pnl_calculated_at=CURRENT_TIMESTAMP(3) WHERE id=%s''',
              (gross,total_fee,gross-total_fee if gross is not None else None,
               'EXCHANGE_ORDERS' if gross is not None else 'INCOMPLETE',position_id))
        repo._connection.commit()
    except Exception:
        repo._connection.rollback()
        raise
    finally:
        repo._in_transaction = False
