"""Append-only cost diagnostics. No order, risk, position or admission writes."""
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal as D
import logging

from ..research.store import digest, primitive

LOG = logging.getLogger(__name__)


def number(value):
    if value is None or isinstance(value, bool):
        raise ValueError('numeric fact missing')
    value = D(str(value))
    if not value.is_finite():
        raise ValueError('nonfinite numeric fact')
    return value


def funding_amount(coverage, position, start, end):
    if (not coverage.get('complete') or not coverage.get('ownership_verified')
            or start is None or end is None
            or coverage.get('start_ms', end + 1) > start
            or coverage.get('end_ms', start - 1) < end
            or str(coverage.get('account_id')) != str(position['account_id'])
            or coverage.get('margin_account') != position['contract_code']):
        return None
    unique = {}
    for row in coverage['rows']:
        ident = str(row['query_id'])
        if ident in unique and unique[ident] != row:
            raise ValueError('conflicting funding identity')
        amount = number(row['amount'])
        if (row.get('asset') != 'USDT' or row.get('margin_account') != position['contract_code']
                or row.get('contract_code', row.get('contract')) != position['contract_code']
                or not start <= row['ts'] <= end or str(row['type']) not in ('30', '31')
                or (str(row['type']) == '30' and amount < 0)
                or (str(row['type']) == '31' and amount > 0)):
            raise ValueError('funding scope/sign mismatch')
        unique[ident] = row
    return sum((number(row['amount']) for row in unique.values()), D(0))


def reconcile(bundle):
    """Recompute cumulative facts; unknown data never become zero or a gate."""
    position = bundle['position']
    result = {'schema': 'regime-cost-observation-v1', 'observation_only': True,
              'affects_entry': False, 'position_id': position['position_id'],
              'account_id': position['account_id'], 'contract': position['contract_code'],
              'mode': bundle['mode'], 'input_hash': digest(bundle), 'warnings': [],
              'status': 'PARTIAL', 'actual': {}, 'planned': {}, 'difference': {}}
    warnings = result['warnings']
    if bundle.get('source_error'):
        warnings.append('SOURCE_UNAVAILABLE:' + bundle['source_error'])
        return result
    plan = bundle.get('plan')
    if not plan:
        warnings.append('PLAN_UNAVAILABLE')
    unique = {}
    for order in bundle['orders']:
        ident = str(order['order_id'])
        if ident in unique and unique[ident] != order:
            raise ValueError('conflicting cumulative order')
        unique[ident] = order
    entry_q, closed_q, entry_value, close_value = (D(0) for _ in range(4))
    fees = {'ENTRY': D(0), 'CLOSE': D(0)}
    exit_slippage = D(0)
    first, last = [], []
    size = number(position['contract_size'])
    if size <= 0 or position['side'] not in ('LONG', 'SHORT'):
        raise ValueError('invalid position units/side')
    sign = D(1) if position['side'] == 'LONG' else D(-1)
    for order in unique.values():
        if (str(order['account_id']) != str(position['account_id'])
                or str(order['position_id']) != str(position['position_id'])
                or order['contract_code'] != position['contract_code']
                or order['side'] != position['side']):
            raise ValueError('order ownership mismatch')
        if order['leg'] not in ('ENTRY', 'CLOSE', 'PARTIAL_CLOSE'):
            raise ValueError('unknown order leg')
        q = number(order['quantity'])
        if q < 0:
            raise ValueError('negative execution quantity')
        if not order.get('terminal'):
            warnings.append('ORDER_PENDING:' + str(order['order_id']))
        if q == 0:
            continue
        px = number(order['vwap'])
        if px <= 0:
            raise ValueError('invalid execution price')
        leg = 'ENTRY' if order['leg'] == 'ENTRY' else 'CLOSE'
        fee = (number(order['fee']) if order.get('fee') is not None
               and order.get('fee_currency') == 'USDT' and order.get('fee_verified') else None)
        fees[leg] = fees[leg] + fee if fees[leg] is not None and fee is not None else None
        if leg == 'ENTRY':
            entry_q += q
            entry_value += q * px
            first.append(order.get('first_fill_ms'))
        else:
            closed_q += q
            close_value += q * px
            last.append(order.get('last_fill_ms'))
            reference = order.get('reference_price')
            exit_slippage = (exit_slippage + sign * (number(reference) - px) * q * size
                             if exit_slippage is not None and reference is not None else None)
    expected = number(position['entry_quantity'])
    remaining = number(position['remaining_quantity'])
    if entry_q != expected or closed_q > entry_q or entry_q - closed_q != remaining:
        warnings.append('QUANTITY_MISMATCH')
        result['status'] = 'INCONSISTENT'
        return primitive(result)
    if entry_q == 0:
        result['status'] = 'NO_FILL'
        return primitive(result)
    for leg, fee in fees.items():
        if fee is None:
            warnings.append(leg + '_FEE_UNKNOWN')
    start = min(first) if first and all(type(t) is int for t in first) else None
    end = (max(last) if last and all(type(t) is int for t in last) else None) if remaining == 0 else bundle['asof_ms']
    funding = funding_amount(bundle.get('funding', {}), position, start, end)
    if funding is None:
        warnings.append('FUNDING_UNKNOWN')
    if exit_slippage is None:
        warnings.append('EXIT_SLIPPAGE_UNKNOWN')
    average = entry_value / entry_q
    gross = sign * (close_value - average * closed_q) * size
    allocated = fees['ENTRY'] * closed_q / entry_q if fees['ENTRY'] is not None else None
    before_funding = gross - allocated - fees['CLOSE'] if allocated is not None and fees['CLOSE'] is not None else None
    net = before_funding + funding if before_funding is not None and funding is not None else None
    result['actual'] = {'entry_contracts': entry_q, 'closed_contracts': closed_q,
                        'remaining_contracts': remaining, 'entry_vwap': average,
                        'close_vwap': close_value / closed_q if closed_q else None,
                        'entry_fee_usdt': fees['ENTRY'], 'close_fee_usdt': fees['CLOSE'],
                        'allocated_entry_fee_usdt': allocated, 'funding_cash_usdt': funding,
                        'realized_gross_usdt': gross, 'realized_net_before_funding_usdt': before_funding,
                        'realized_net_including_funding_usdt': net,
                        'exit_slippage_usdt': exit_slippage}
    if plan:
        if (plan['schema'] != 'regime-cost-plan-v1'
                or number(plan['contract_size']) != size or plan['side'] != position['side']
                or plan['contract'] != position['contract_code']
                or entry_q > number(plan['contracts'])):
            raise ValueError('plan ownership/quantity mismatch')
        planned_entry = number(plan['entry_fee_per_unit']) * entry_q * size
        planned_target = number(plan['target_fee_per_unit']) * closed_q * size
        planned_stop = number(plan['stop_fee_per_unit']) * closed_q * size
        planned_entry_slip = number(plan['entry_slippage_per_unit']) * entry_q * size
        planned_exit_slip = number(plan['exit_slippage_per_unit']) * closed_q * size
        actual_entry_slip = sign * (average - number(plan['entry_reference'])) * entry_q * size
        result['actual']['entry_slippage_usdt'] = actual_entry_slip
        result['planned'] = {'entry_fee_usdt': planned_entry,
                             'target_close_fee_usdt': planned_target, 'stop_close_fee_usdt': planned_stop,
                             'entry_slippage_usdt': planned_entry_slip, 'exit_slippage_usdt': planned_exit_slip,
                             'funding_usdt': None,
                             'target_net_reward_for_filled_quantity_usdt': number(plan['target_net_reward_per_unit']) * entry_q * size}
        result['difference'] = {
            'entry_fee_usdt': fees['ENTRY'] - planned_entry if fees['ENTRY'] is not None else None,
            'close_fee_vs_target_usdt': fees['CLOSE'] - planned_target if fees['CLOSE'] is not None else None,
            'close_fee_vs_stop_usdt': fees['CLOSE'] - planned_stop if fees['CLOSE'] is not None else None,
            'entry_slippage_usdt': actual_entry_slip - planned_entry_slip,
            'exit_slippage_usdt': exit_slippage - planned_exit_slip if exit_slippage is not None else None,
            'funding_usdt': None}
        for key in ('entry_fee_usdt', 'entry_slippage_usdt', 'exit_slippage_usdt'):
            if result['difference'][key] is not None and result['difference'][key] > 0:
                warnings.append('COST_ABOVE_PLAN:' + key)
        if fees['CLOSE'] is not None and fees['CLOSE'] > max(planned_target, planned_stop):
            warnings.append('CLOSE_FEE_ABOVE_BOTH_BRACKET_BUDGETS')
    result['status'] = 'PARTIAL' if any('UNKNOWN' in w or 'UNAVAILABLE' in w or 'PENDING' in w for w in warnings) else 'COMPLETE'
    result['warnings'] = sorted(set(warnings))
    return primitive(result)


class CostObserver:
    """One background read at a time; no callback into trading/admission state."""
    def __init__(self, reader, evidence, *, interval_ms=60000, asynchronous=True):
        self.reader, self.evidence, self.interval_ms = reader, evidence, interval_ms
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix='htx-cost-observer') if asynchronous else None
        self.future = None
        self.last = None
        self.seen = {}
        self.latest = {}

    def _publish(self, bundles):
        for bundle in bundles:
            try:
                report = reconcile(bundle)
            except (ValueError, KeyError, TypeError, ArithmeticError) as error:
                report = {'schema': 'regime-cost-observation-v1', 'observation_only': True,
                          'affects_entry': False, 'position_id': bundle['position']['position_id'],
                          'status': 'INCONSISTENT', 'input_hash': digest(bundle),
                          'warnings': ['INVALID_FACTS:' + type(error).__name__]}
            ident = str(report['position_id'])
            signature = digest(report)
            if self.seen.get(ident) == signature:
                continue
            ref = self.evidence.put('regime_cost_observation', {'input': bundle, 'report': report})
            self.latest[ident] = {**report, 'evidence_ref': ref}
            self.seen[ident] = signature
            if report['warnings']:
                LOG.warning('REGIME_COST_OBSERVATION position=%s warnings=%s affects_entry=false evidence=%s', ident, ','.join(report['warnings']), ref)

    def tick(self, now_ms):
        try:
            if self.future is not None and self.future.done():
                completed, self.future = self.future, None
                self._publish(completed.result())
            if self.last is not None and now_ms - self.last < self.interval_ms:
                return
            self.last = now_ms
            if self.pool is None:
                self._publish(self.reader(now_ms))
            elif self.future is None:
                self.future = self.pool.submit(self.reader, now_ms)
        except Exception as error:
            # Do not propagate into worker.failures or prevent protective exits.
            LOG.warning('REGIME_COST_OBSERVATION_UNAVAILABLE type=%s affects_entry=false', type(error).__name__)

    def close(self):
        if self.pool is not None:
            self.pool.shutdown(wait=False, cancel_futures=True)


def main():
    import argparse
    import json
    from pathlib import Path

    parser = argparse.ArgumentParser(description='Recompute a cost observation from explicit local receipts; never trades.')
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    value = json.loads(args.input.read_text())
    report = reconcile(value)
    output = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + '\n'
    if args.out:
        with args.out.open('x') as stream:
            stream.write(output)
    else:
        print(output, end='')


if __name__ == '__main__':
    main()
