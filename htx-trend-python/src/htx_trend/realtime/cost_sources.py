"""Read-only adapters for cost observations; no exchange mutations or ledger writes."""
from datetime import UTC, datetime
from decimal import Decimal as D

from ..research.store import primitive
from .cost_observation import number


def timestamp(value):
    if value is None:
        return None
    if isinstance(value, str):
        value = datetime.fromisoformat(value)
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return int(value.timestamp() * 1000)


class PaperCostReader:
    def __init__(self, ledger, account_id):
        self.ledger, self.account_id = ledger, account_id

    def __call__(self, now_ms):
        data = self.ledger.data
        bundles = []
        for ident, p in data['positions'].items():
            context = data['contexts'].get(ident, {})
            if p['account_id'] != self.account_id or context.get('model') != 'RT_REGIME_AB':
                continue
            orders = []
            for order_id, order in data['orders'].items():
                entry = (order.get('position_id') is None and
                         order.get('strategy_run_id') == 'rt:' + str(context.get('plan_id')))
                if not entry and order.get('position_id') != ident:
                    continue
                fills = {f['fill_id']: f for f in data['fills'] if f['order_id'] == order_id}
                q = sum((number(f['quantity']) for f in fills.values()), D(0))
                value = sum((number(f['quantity']) * number(f['price']) for f in fills.values()), D(0))
                times = [timestamp(f['filled_at']) for f in fills.values()]
                orders.append(dict(order_id=order_id, position_id=ident, account_id=self.account_id,
                    contract_code=p['contract_code'], side=p['side'], leg='ENTRY' if entry else 'CLOSE',
                    quantity=q, vwap=value / q if q else None,
                    fee=sum((number(f['fee']) for f in fills.values()), D(0)),
                    fee_currency='USDT', fee_verified=True, terminal=order['status'] in ('FILLED', 'PARTIAL', 'CANCELLED', 'REJECTED'),
                    first_fill_ms=min(times) if times else None, last_fill_ms=max(times) if times else None,
                    reference_price=order.get('signal_px')))
            entered = sum((o['quantity'] for o in orders if o['leg'] == 'ENTRY'), D(0))
            bundles.append(primitive(dict(mode='PAPER_SIMULATED',
                asof_ms=timestamp(p.get('closed_at')) or now_ms,
                position=dict(position_id=ident, account_id=self.account_id, contract_code=p['contract_code'],
                    side=p['side'], contract_size=context['contract_size'], entry_quantity=entered,
                    remaining_quantity=p['quantity'] if p['status'] == 'OPEN' else 0),
                plan=context.get('cost_plan'), orders=orders,
                funding={'complete': False, 'reason': 'NO_VERIFIED_SIMULATED_FUNDING_SETTLEMENT'},
                provenance={'fee_source': 'PAPER_LEDGER_NOT_EXCHANGE', 'fills': [f for f in data['fills'] if f['order_id'] in {o['order_id'] for o in orders}]})))
        return bundles


class LiveCostReader:
    """A separate connection/client; round-robin durable plans include closed positions."""
    def __init__(self, repository_factory, client_factory, account_id, instance_key, *, batch_size=10):
        self.repository_factory, self.client_factory = repository_factory, client_factory
        self.account_id, self.instance_key, self.batch_size = account_id, instance_key, batch_size
        self.cursor = 0

    def __call__(self, now_ms):
        repo = self.repository_factory()
        client = None
        try:
            rows = repo._all(
                "SELECT DISTINCT p.* FROM htx_trend_position p JOIN htx_trend_realtime_plan t "
                "ON p.id=CAST(JSON_UNQUOTE(JSON_EXTRACT(t.state_json,'$.position_id')) AS UNSIGNED) "
                "WHERE t.instance_key=%s AND p.account_id=%s AND p.primary_model='RT_REGIME_AB' "
                "AND p.id>%s ORDER BY p.id LIMIT %s",
                (self.instance_key, self.account_id, self.cursor, self.batch_size))
            if not rows:
                self.cursor = 0
                return []
            client = self.client_factory(repo)
            bundles = []
            for row in rows:
                self.cursor = row['id']
                bundle = dict(mode='LIVE_EXCHANGE_RECEIPTS', asof_ms=now_ms,
                    position=dict(position_id=row['id'], account_id=row['account_id'], contract_code=row['contract_code'],
                        side=row['side'], contract_size=row['contract_size'], entry_quantity=row['entry_filled_contracts'],
                        remaining_quantity=row['remaining_contract_volume']),
                    plan=None, orders=[], funding={}, provenance={})
                try:
                    bundle['plan'] = repo.exit_state(row['id']).get('cost_plan')
                    self.read_position(repo, client, row, bundle, now_ms)
                except Exception as error:
                    bundle['source_error'] = type(error).__name__
                bundles.append(primitive(bundle))
            return bundles
        finally:
            if client is not None:
                client.close()
            repo._connection.close()

    def read_position(self, repo, client, row, bundle, now_ms):
        from ..execution_service import _terminal_status
        from .fills import collect, validate

        code = row['contract_code']
        sources = []
        for order in repo._all('SELECT * FROM htx_trend_order WHERE position_id=%s ORDER BY id', (row['id'],)):
            raw = client.order(code, client_order_id=str(order['client_order_id']))
            if (str(raw.get('client_order_id')) != str(order['client_order_id'])
                    or raw.get('contract_code') != code or raw.get('margin_mode') != 'isolated'
                    or raw.get('side', '').upper() != order['order_side'].upper()
                    or raw.get('position_side', '').upper() != order['position_side'].upper()):
                raise ValueError('cost receipt ownership mismatch')
            q = number(raw['trade_volume'])
            times = {}
            details = None
            detail_error = None
            if q > 0:
                try:
                    details = collect(client, code, str(order['exchange_order_id']))
                    times = validate(details, {**order, 'filled_contracts': q,
                        'average_price': raw['trade_avg_price'], 'fee': raw.get('fee'),
                        'fee_currency': raw.get('fee_currency')}, now_ms=now_ms)
                except (ValueError, KeyError, TypeError) as error:
                    detail_error = type(error).__name__
            sources.append({'order': raw, 'details': details, 'detail_error': detail_error})
            bundle['orders'].append(dict(order_id=str(order['client_order_id']), position_id=row['id'],
                account_id=row['account_id'], contract_code=code, side=row['side'], leg=order['leg'],
                quantity=q, vwap=raw.get('trade_avg_price'), fee=raw.get('fee'),
                fee_currency=raw.get('fee_currency'), fee_verified=raw.get('fee') is not None,
                terminal=_terminal_status(raw) in ('FILLED', 'CANCELLED', 'PARTIALLY_CANCELLED', 'REJECTED'),
                first_fill_ms=times.get('first_fill_ms'), last_fill_ms=times.get('last_fill_ms'),
                reference_price=order.get('signal_px')))
        bundle['provenance']['orders'] = sources
        try:
            self.read_funding(repo, client, row, bundle, now_ms)
        except Exception as error:
            # Settlement failure must not discard separately verified executions.
            bundle['funding'] = {'complete': False, 'reason': 'SOURCE_UNAVAILABLE:' + type(error).__name__}

    def read_funding(self, repo, client, row, bundle, now_ms):
        from .trial_facts import funding_history

        code = row['contract_code']
        entered = [o for o in bundle['orders'] if o['leg'] == 'ENTRY' and o['quantity'] > 0]
        closed = [o for o in bundle['orders'] if o['leg'] != 'ENTRY' and o['quantity'] > 0]
        if not entered or any(o['first_fill_ms'] is None for o in entered):
            bundle['funding'] = {'complete': False, 'reason': 'EXACT_ENTRY_TIME_UNAVAILABLE'}
            return
        start = min(o['first_fill_ms'] for o in entered)
        end = now_ms
        if number(row['remaining_contract_volume']) == 0:
            if not closed or any(o['last_fill_ms'] is None for o in closed):
                bundle['funding'] = {'complete': False, 'reason': 'EXACT_CLOSE_TIME_UNAVAILABLE'}
                return
            end = max(o['last_fill_ms'] for o in closed)
            bundle['asof_ms'] = end
        # Broad overlap is intentionally conservative: never allocate contract-level
        # funding to this position while another position may own the same cashflow.
        peers = repo._all('SELECT id FROM htx_trend_position WHERE account_id=%s AND contract_code=%s '
            'AND id<>%s AND entry_filled_contracts>0 AND created_at<=%s AND updated_at>=%s LIMIT 1',
            (row['account_id'], code, row['id'], datetime.fromtimestamp(end / 1000, UTC), datetime.fromtimestamp(start / 1000, UTC)))
        if peers or start >= end:
            bundle['funding'] = {'complete': False, 'reason': 'FUNDING_OWNERSHIP_OR_INTERVAL_UNPROVEN'}
            return
        cursor, flows, pages = start, {}, []
        while cursor < end:
            until = min(end, cursor + 48 * 3600000)
            history = funding_history(client, code, cursor, until)
            pages.extend(history['pages'])
            for flow in history['rows']:
                ident = str(flow['query_id'])
                if ident in flows and flows[ident] != flow:
                    raise ValueError('conflicting funding across windows')
                flows[ident] = flow
            cursor = until
        # Local peers cannot rule out manual/external positions in historical
        # exchange cashflows. Empty coverage proves zero; nonempty contract-level
        # flows remain unallocated, with raw evidence available for reconciliation.
        bundle['funding'] = dict(complete=True, ownership_verified=not flows, account_id=row['account_id'],
            margin_account=code, start_ms=start, end_ms=end, rows=list(flows.values()))
        if flows:
            bundle['funding']['reason'] = 'CONTRACT_CASHFLOWS_NOT_POSITION_ATTRIBUTED'
        bundle['provenance']['funding_pages'] = pages
