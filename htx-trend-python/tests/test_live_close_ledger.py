from dataclasses import asdict
from decimal import Decimal as D

import pytest

from htx_trend.live_close_ledger import (
    detect_incomplete_close_ledger, emit_incomplete_close_alert,
    plan_close_ledger_backfill, apply_close_ledger_backfill,
)
from test_order_accounting import store, apply


def fixture_286():
    position = dict(id=286, account_id=7, account_label='primary', strategy_family='TREND',
                    strategy_name='HTX_TREND_V1', model_name='DONCHIAN_BREAKOUT',
                    contract_code='PEPE-USDT', side='SHORT', contract_size='1000000')
    order = dict(id=12, position_id=286, client_order_id='close286', leg='CLOSE', status='FILLED',
                 filled_contracts='27', average_price='0.000003', fee='0.04641408',
                 fee_currency='USDT', realized_profit='-0.5292')
    intent = dict(event_key='trend-close-order:286:close286', event_type='CLOSE_ORDER',
                  position_id=286, client_order_id='close286')
    return position, order, [intent]


def test_detection_and_warning(caplog):
    _, order, events = fixture_286()
    alerts = detect_incomplete_close_ledger([order], events)
    assert len(alerts) == 1
    assert alerts[0].missing_types == ('CLOSE_FILL', 'FEE', 'REALIZED_PNL')
    emit_incomplete_close_alert(alerts[0])
    assert 'LIVE_CLOSE_LEDGER_INCOMPLETE' in caplog.text
    assert 'position_id=286 client_order_id=close286' in caplog.text
    assert not detect_incomplete_close_ledger([dict(order, status='ACCEPTED')], events)
    assert detect_incomplete_close_ledger([dict(order, status='PARTIALLY_CANCELLED')], events)


def test_backfill_dry_run_and_replay_are_insert_only(store):
    db, repo = store
    position, order, events = fixture_286()
    plan = plan_close_ledger_backfill(position, order, events)
    assert len(plan) == 3
    assert sum(e.net_pnl_after_fee for e in plan) == D('-0.57561408')
    assert apply_close_ledger_backfill(repo, plan)['insert_attempts'] == 0
    assert db.raw.execute('SELECT count(*) FROM coin_strategy_ledger_event').fetchone()[0] == 0
    apply_close_ledger_backfill(repo, plan, dry_run=False)
    before = [dict(r) for r in db.raw.execute('SELECT * FROM coin_strategy_ledger_event')]
    apply_close_ledger_backfill(repo, plan, dry_run=False)
    after = [dict(r) for r in db.raw.execute('SELECT * FROM coin_strategy_ledger_event')]
    assert before == after
    assert not detect_incomplete_close_ledger([order], after)
    assert plan_close_ledger_backfill(position, order, after) == []


@pytest.mark.parametrize('missing', ['CLOSE_FILL', 'FEE', 'REALIZED_PNL'])
def test_backfill_only_missing_type(missing):
    p, o, events = fixture_286()
    complete = [asdict(e) for e in plan_close_ledger_backfill(p, o, events)]
    existing = [e for e in complete if e['event_type'] != missing]
    assert [e.event_type for e in plan_close_ledger_backfill(p, o, existing)] == [missing]
    # Fees belonging to the entry / another close never satisfy this order.
    assert detect_incomplete_close_ledger([o], [dict(e, client_order_id='other') for e in complete])


@pytest.mark.parametrize('field,value', [('realized_profit', None), ('fee', None), ('fee_currency', 'BTC')])
def test_backfill_refuses_unknown_economics(field, value):
    p, o, events = fixture_286()
    with pytest.raises(ValueError):
        plan_close_ledger_backfill(p, dict(o, **{field: value}), events)


def test_legacy_combined_economics_are_not_double_booked():
    p, o, events = fixture_286()
    events.append(dict(event_key='legacy', event_type='CLOSE_FILL', position_id=286,
                       client_order_id='close286', fee_usdt='0.04641408'))
    with pytest.raises(ValueError, match='legacy combined'):
        plan_close_ledger_backfill(p, o, events)


def test_existing_filled_order_missing_projection_warns_on_duplicate(store, caplog):
    db, repo = store
    apply(repo, status='FILLED', filled_contracts=D(10))
    db.raw.execute("INSERT INTO htx_trend_order(id,position_id,leg,client_order_id,request_volume,status,filled_contracts,average_price,fee,realized_profit) VALUES(2,1,'CLOSE','456',10,'FILLED',10,110,'.02','1')")
    db.commit()
    apply(repo, leg='CLOSE', client_order_id='456', status='FILLED', filled_contracts=D(10),
          average_price=D(110), fee=D('.02'), realized_profit=D(1))
    assert 'LIVE_CLOSE_LEDGER_INCOMPLETE' in caplog.text
