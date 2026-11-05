from copy import deepcopy
from decimal import Decimal as D
from types import SimpleNamespace

import pytest

from htx_trend.realtime.cost_observation import reconcile, CostObserver
from htx_trend.realtime.cost_sources import PaperCostReader, LiveCostReader
from htx_trend.research.store import primitive


def bundle(side='LONG', closed=4):
    sign = 1 if side == 'LONG' else -1
    position = dict(position_id='p1', account_id=7, contract_code='ETH-USDT', side=side,
                    contract_size='1', entry_quantity='10', remaining_quantity=str(10-closed))
    plan = dict(schema='regime-cost-plan-v1', plan_id='plan1', contract='ETH-USDT', side=side,
                contract_size='1', contracts=20, entry_reference='100', entry_limit=str(D(100)+D('.5')*sign),
                entry_fee_per_unit='.0402', target_fee_per_unit='.041', stop_fee_per_unit='.039',
                entry_slippage_per_unit='.5', exit_slippage_per_unit='.1',
                target_net_reward_per_unit='1.8', funding_budget=None)
    base = dict(position_id='p1', account_id=7, contract_code='ETH-USDT', side=side,
                fee_currency='USDT', fee_verified=True, terminal=True)
    orders = [dict(base, order_id='e1', leg='ENTRY', quantity='10',
                   vwap=str(D(100)+D('.2')*sign), fee='.5', first_fill_ms=1000, last_fill_ms=1200, reference_price='100'),
              dict(base, order_id='c1', leg='CLOSE', quantity=str(closed),
                   vwap=str(D(100)+D(1)*sign), fee='.2', first_fill_ms=3000, last_fill_ms=3000,
                   reference_price=str(D(100)+D('1.1')*sign))]
    flow = dict(query_id='f1', ts=2000, type='31', asset='USDT', margin_account='ETH-USDT',
                contract_code='ETH-USDT', amount='-.1')
    funding = dict(complete=True, ownership_verified=True, account_id=7, margin_account='ETH-USDT',
                   start_ms=1000, end_ms=4000, rows=[flow])
    return dict(mode='TEST_RECEIPTS', asof_ms=4000, position=position, plan=plan, orders=orders, funding=funding)


@pytest.mark.parametrize('side', ['LONG', 'SHORT'])
def test_partial_fill_scales_budget_and_allocates_entry_fee_once(side):
    b = bundle(side)
    result = reconcile(b)
    assert D(result['planned']['entry_fee_usdt']) == D('.402')
    assert D(result['actual']['entry_slippage_usdt']) == 2
    assert D(result['actual']['exit_slippage_usdt']) == D('.4')
    assert D(result['actual']['allocated_entry_fee_usdt']) == D('.2')
    assert D(result['actual']['realized_gross_usdt']) == D('3.2')
    assert D(result['actual']['realized_net_including_funding_usdt']) == D('2.7')
    assert 'COST_ABOVE_PLAN:entry_fee_usdt' in result['warnings']
    assert result['status'] == 'COMPLETE' and not result['affects_entry']
    assert b == bundle(side)


def test_identical_duplicates_do_not_accumulate_money():
    b = bundle(closed=10)
    original = reconcile(b)
    b['orders'].append(deepcopy(b['orders'][0]))
    b['funding']['rows'].append(deepcopy(b['funding']['rows'][0]))
    result = reconcile(b)
    assert result['actual'] == original['actual']
    assert result['difference'] == original['difference']
    b['orders'][-1]['fee'] = '99'
    with pytest.raises(ValueError, match='conflicting'):
        reconcile(b)


@pytest.mark.parametrize('cause', ['missing', 'incomplete', 'ownership', 'range', 'currency'])
def test_unknown_funding_does_not_become_zero(cause):
    b = bundle()
    if cause == 'missing': b['funding'] = {}
    if cause == 'incomplete': b['funding']['complete'] = False
    if cause == 'ownership': b['funding']['ownership_verified'] = False
    if cause == 'range': b['funding']['end_ms'] = 2500
    if cause == 'currency':
        b['funding']['rows'][0]['asset'] = 'BTC'
        with pytest.raises(ValueError, match='scope'):
            reconcile(b)
        return
    result = reconcile(b)
    assert result['actual']['funding_cash_usdt'] is None
    assert result['actual']['realized_net_including_funding_usdt'] is None
    assert result['status'] == 'PARTIAL' and not result['affects_entry']


def test_proven_empty_funding_is_zero_and_rebates_are_signed():
    b = bundle()
    b['funding']['rows'] = []
    b['orders'][0]['fee'] = '-.5'
    result = reconcile(b)
    assert D(result['actual']['funding_cash_usdt']) == 0
    assert D(result['actual']['realized_net_including_funding_usdt']) == D('3.2')


def test_ledger_zero_without_fee_provenance_remains_unknown():
    b = bundle()
    b['orders'][0].update(fee='0', fee_verified=False)
    b['orders'][1]['reference_price'] = None
    result = reconcile(b)
    assert result['actual']['entry_fee_usdt'] is None
    assert result['difference']['entry_fee_usdt'] is None
    assert result['actual']['exit_slippage_usdt'] is None
    assert 'ENTRY_FEE_UNKNOWN' in result['warnings']


def test_quantity_mismatch_and_legacy_plan_are_diagnostic_only():
    b = bundle()
    b['position']['remaining_quantity'] = '9'
    assert reconcile(b)['status'] == 'INCONSISTENT'
    b = bundle()
    b['plan'] = None
    r = reconcile(b)
    assert 'PLAN_UNAVAILABLE' in r['warnings'] and not r['affects_entry']


class Evidence:
    def __init__(self): self.rows = []
    def put(self, kind, payload):
        self.rows.append((kind, deepcopy(payload)))
        return str(len(self.rows))


def test_observer_deduplicates_reports_restarts_and_processes_after_bad_row(caplog):
    e = Evidence()
    b = bundle()
    bad = deepcopy(b)
    bad['position']['position_id'] = 'bad'
    observer = CostObserver(lambda _: [bad, b], e, interval_ms=1, asynchronous=False)
    observer.tick(10)
    observer.tick(11)
    assert len(e.rows) == 2
    assert observer.latest['bad']['status'] == 'INCONSISTENT'
    assert observer.latest['p1']['actual'] == reconcile(b)['actual']
    b['orders'][0]['fee'] = '.6'
    observer.tick(12)
    assert len(e.rows) == 3
    restarted = CostObserver(lambda _: [b], e, asynchronous=False)
    restarted.tick(13)
    assert restarted.latest['p1']['actual'] == observer.latest['p1']['actual']
    assert 'affects_entry=false' in caplog.text


def test_observer_source_or_evidence_failure_never_raises(caplog):
    def failed(_): raise ConnectionError('offline')
    o = CostObserver(failed, Evidence(), asynchronous=False)
    o.tick(10)
    assert 'REGIME_COST_OBSERVATION_UNAVAILABLE' in caplog.text
    o.close()
    class BrokenEvidence:
        def put(self, *_): raise OSError('storage unavailable')
    o = CostObserver(lambda _: [bundle()], BrokenEvidence(), asynchronous=False)
    o.tick(10)
    assert o.latest == {} and o.seen == {}


def test_async_observer_never_waits_for_reader_or_starts_overlapping_reads():
    from threading import Event
    started, release = Event(), Event()
    calls = []
    def reader(now):
        calls.append(now)
        started.set()
        assert release.wait(5)
        return [bundle()]
    observer = CostObserver(reader, Evidence(), interval_ms=1)
    try:
        observer.tick(100)
        assert started.wait(1)
        pending = observer.future
        observer.tick(101)
        observer.tick(102)
        assert not pending.done() and observer.future is pending and calls == [100]
        release.set()
        pending.result(timeout=1)
        observer.tick(102)
        assert observer.latest['p1']['actual'] == reconcile(bundle())['actual']
    finally:
        release.set()
        observer.close()


def test_paper_reader_includes_closed_positions_and_labels_simulated():
    b = bundle(closed=10)
    data = dict(positions={'p1': dict(account_id=7, contract_code='ETH-USDT', side='LONG', quantity=0,
                     status='CLOSED', closed_at='1970-01-01T00:00:03+00:00')},
                contexts={'p1': dict(model='RT_REGIME_AB', plan_id='plan1', contract_size='1', cost_plan=b['plan'])},
                orders={'e': dict(position_id=None, strategy_run_id='rt:plan1', status='FILLED', signal_px='100'),
                        'c': dict(position_id='p1', status='FILLED', signal_px='101.1')},
                fills=[dict(fill_id='ef', order_id='e', quantity=10, price='100.2', fee='.5', filled_at='1970-01-01T00:00:01+00:00'),
                       dict(fill_id='cf', order_id='c', quantity=10, price='101', fee='.4', filled_at='1970-01-01T00:00:03+00:00')])
    result, = PaperCostReader(SimpleNamespace(data=data), 7)(5000)
    assert result['mode'] == 'PAPER_SIMULATED'
    r = reconcile(result)
    assert D(r['actual']['closed_contracts']) == 10
    assert r['actual']['funding_cash_usdt'] is None


def test_live_reader_uses_durable_scope_and_closes_read_only_connection():
    class Repo:
        def __init__(self): self._connection = self; self.closed = False; self.calls = []
        def close(self): self.closed = True
        def _all(self, sql, args):
            self.calls.append((sql, args))
            return []
    repo = Repo()
    r = LiveCostReader(lambda: repo, lambda _: pytest.fail('client with no positions'), 7, 'instance1')
    assert r(5000) == [] and repo.closed
    sql, args = repo.calls[0]
    assert 'htx_trend_realtime_plan' in sql and 'status IN' not in sql
    assert args[:2] == ('instance1', 7)


class LiveRepo:
    def __init__(self, peers=False):
        self._connection = self
        self.closed = False
        self.peers = peers
        self.row = {**bundle(closed=10)['position'], 'id': 1, 'entry_filled_contracts': 10,
                    'remaining_contract_volume': 0, 'account_id': 7}
        self.orders = [dict(id=i, client_order_id=str(i), exchange_order_id=str(i + 10),
            contract_code='ETH-USDT', order_side='BUY' if i == 1 else 'SELL',
            position_side='LONG', leg='ENTRY' if i == 1 else 'CLOSE') for i in (1, 2)]

    def close(self): self.closed = True
    def exit_state(self, _): return {'cost_plan': bundle(closed=10)['plan']}
    def _all(self, sql, args):
        if 'JOIN htx_trend_realtime_plan' in sql: return [self.row] if args[2] == 0 else []
        if 'FROM htx_trend_order' in sql: return self.orders
        if 'SELECT id FROM htx_trend_position' in sql: return [{'id': 2}] if self.peers else []
        pytest.fail(sql)


class LiveClient:
    def __init__(self, funding=None, fail_funding=False, fail_details=False):
        self.closed = False
        self.funding = funding or []
        self.fail_funding, self.fail_details = fail_funding, fail_details
    def close(self): self.closed = True
    def order(self, code, *, client_order_id):
        i = int(client_order_id)
        return dict(client_order_id=client_order_id, contract_code=code, margin_mode='isolated',
                    side='buy' if i == 1 else 'sell', position_side='long', trade_volume=10,
                    trade_avg_price='100.2' if i == 1 else '101', fee='.5', fee_currency='USDT', status=6)
    def order_details(self, code, order_id, **kwargs):
        if self.fail_details: raise ValueError('missing detail')
        raw = self.order(code, client_order_id=str(int(order_id) - 10))
        return [dict(contract_code=code, order_id=order_id, trade_id=order_id, id=order_id,
                     side=raw['side'], position_side='long', margin_mode='isolated',
                     trade_volume=10, trade_price=raw['trade_avg_price'], trade_fee='.5',
                     fee_currency='USDT', updated_time=1000 if order_id == '11' else 3000)]
    def _signed(self, method, path, *, body):
        assert method == 'POST' and path.endswith('swap_financial_record_exact')
        if self.fail_funding: raise ConnectionError('offline')
        return dict(code=200, data=self.funding if body['from_id'] == 0 else [])


@pytest.mark.parametrize('condition', ['empty', 'cashflow', 'overlap', 'funding_error', 'missing_times'])
def test_live_receipt_reader_closed_positions_funding_and_partial_evidence(condition):
    repo = LiveRepo(peers=condition == 'overlap')
    flow = {**bundle()['funding']['rows'][0], 'query_id': '1'}
    client = LiveClient([flow] if condition == 'cashflow' else [],
                        fail_funding=condition == 'funding_error', fail_details=condition == 'missing_times')
    reader = LiveCostReader(lambda: repo, lambda _: client, 7, 'instance1')
    value, = reader(5000)
    assert repo.closed and client.closed and 'source_error' not in value
    result = reconcile(value)
    assert D(result['actual']['entry_fee_usdt']) == D('.5')
    assert D(result['actual']['realized_net_before_funding_usdt']) == 7
    assert result['actual']['funding_cash_usdt'] == ('0' if condition == 'empty' else None)
    if condition == 'cashflow':
        assert value['funding']['complete'] and not value['funding']['ownership_verified']
        assert value['provenance']['funding_pages']
    assert reader(5001) == [] and reader.cursor == 0
    # A later receipt is retried without accumulating its fee again.
    client.fail_funding = False
    later, = reader(5002)
    assert D(reconcile(later)['actual']['entry_fee_usdt']) == D('.5')


def test_observer_failure_cannot_block_real_paper_entry_or_protective_exit(monkeypatch, caplog):
    from htx_trend.realtime.regime_replay import account_replay
    from test_regime_exit_selection import account_tape, snapshot_for, params

    def failed_tick(*_): raise ConnectionError('observation unavailable')
    value = snapshot_for()
    original = account_replay(account_tape(), params(value), value)
    assert len(original['fills']) == 2 and original['cost_observations']
    report, = original['cost_observations'].values()
    assert report['mode'] == 'PAPER_SIMULATED'
    assert D(report['actual']['closed_contracts']) > 0
    assert report['planned'] and report['actual']['funding_cash_usdt'] is None
    # Fail only worker ticks, not the independent final report projection.
    from htx_trend.realtime.replay import Actor
    actor = Actor(account_tape(), params(value))
    from htx_trend.realtime.regime_bayes import Snapshot
    actor.worker.engine.snapshot = Snapshot(value)
    monkeypatch.setattr(actor.worker.cost_observer, 'tick', failed_tick)
    try:
        for event in account_tape()['observations']: actor.step(event)
        monkeypatch.undo()
        result = actor.result()
        assert result['fills'] == original['fills'] and not result['errors']
        assert result['positions'][0]['status'] == 'CLOSED'
        assert 'affects_entry=false' in caplog.text
    finally:
        actor.close()
