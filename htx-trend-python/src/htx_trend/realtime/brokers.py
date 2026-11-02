"""The existing ledgers/executors remain authoritative. No retry creates a new intent."""
from __future__ import annotations

from dataclasses import asdict, replace
from datetime import UTC, datetime
from decimal import Decimal as D
import copy
import time

from ..execution import ExecutionFacts
from ..ledger import Order, Fill, Position
from ..models import TrendSide
from ..order_accounting import TERMINAL as ORDER_TERMINAL
from ..htx_v5 import SwapOrderRequest
from ..mysql_store import PositionIntent, TrendOrderIntent
from ..execution_service import _order_id, _client_id, _reservation_key
from ..research.store import primitive
from . import VERSION


def utc(ms):
    return datetime.fromtimestamp(ms/1000, UTC)


def initial_context(intent, trigger, contract, holding_parameters):
    return {'model': intent.model, 'entry_strategy_version': VERSION,
            'plan_id': intent.plan_id, 'exit_policy_ref': intent.exit_policy_ref,
            'continuation_version': 'linked-v1', 'entry_plan_version': 'structure-cost-v1',
            'initial_stop': str(intent.price.invalidation), 'invalidation': str(intent.price.invalidation),
            'structure_target': str(intent.price.structure_target), 'contract_size': str(contract.contract_size),
            'raw_structure_invalidation': str(trigger.raw_invalidation),
            'structure_line':str(trigger.plan.line),'structure_atr':str(trigger.plan.atr),
            'entry_net_r':str(intent.price.expected_net_r),'entry_net_risk_per_unit':str(intent.price.net_risk),
            'holding_policy': primitive(holding_parameters), 'trigger_ms': intent.trigger_ms,
            'continuation_last_bar': intent.structure_bar, 'continuation_breach_count': 0,
            'highest': str(trigger.reference), 'lowest': str(trigger.reference), 'entry_fee': '0',
            'protection': None, 'trailing_armed_at': None, 'trailing_arm_reason': None,
            'max_favorable_r': '0', 'original_quantity': intent.size.contracts, 'flat_streak': 0, 'last_bar': intent.structure_bar,
            'first_fill_ms': None, 'extrema_coverage': 'NOT_FILLED'}


class PaperBroker:
    def __init__(self, ledger, engine, account_id, *, latency_ms=250):
        self.ledger, self.engine, self.account = ledger, engine, account_id
        self.latency_ms = latency_ms

    def facts(self, contract, side, book, config, leverage):
        positions = self.ledger.active_positions(self.account)
        contexts = self.ledger.data['contexts']
        realized = sum((D(str(p['realized_pnl'])) for p in self.ledger.data['positions'].values()), D(0))
        fees = sum((D(str(f['fee'])) for f in self.ledger.data['fills']), D(0))
        # PAPER positions.realized_pnl includes exit fee; do not count it twice.
        exit_fee = sum((D(str(f['fee'])) for f in self.ledger.data['fills']
                        if self.ledger.data['orders'][f['order_id']].get('position_id') is not None), D(0))
        equity = config.account_equity_usdt+realized-fees+exit_fee+D(str(self.ledger.data.get('cash_adjustment',0)))
        pending = self.ledger.data.get('realtime_pending', {})
        used = sum((p.entry_price*p.quantity*D(contexts[p.position_id]['contract_size'])/leverage for p in positions),D(0))
        reserved = sum((D(v['notional'])/leverage for v in pending.values()), D(0))
        risk = sum((abs(p.entry_price-D(contexts[p.position_id]['initial_stop']))*p.quantity*D(contexts[p.position_id]['contract_size']) for p in positions),D(0))
        risk += sum((D(v['risk']) for v in pending.values()),D(0))
        return ExecutionFacts(actual_taker_fee_rate=config.fee_rate,
            contract_open_order=any(v['contract']==contract.contract_code for v in pending.values()),
            external_position_side=next((p.side for p in positions if p.contract_code==contract.contract_code),None),
            available_margin_usdt=equity-used-reserved, account_equity_usdt=equity, leverage=leverage,
            active_position_count=len(positions)+len(pending), max_active_positions=config.max_active_positions,
            same_direction_position_count=sum(p.side==side.value for p in positions)+sum(v['side']==side.value for v in pending.values()),
            used_margin_usdt=used,reserved_margin_usdt=reserved,reserved_risk_usdt=risk)

    def submit(self, intent, trigger, contract, *, now_ms, fee_rate, holding_parameters):
        data = self.ledger.data
        ident = intent.client_order_id
        if ident in data['orders']:
            return 'UNKNOWN' # recovery, never a second submit
        before = copy.deepcopy(data)
        before_plans = copy.deepcopy(self.engine.rows)
        try:
            order=Order(ident,self.account,None,intent.contract_code,'BUY' if intent.side.value=='LONG' else 'SELL',
                intent.side.value,'LIMIT',intent.size.contracts,ident,'OPEN',utc(now_ms),
                limit_price=intent.price.worst_acceptable_entry,strategy_run_id='rt:'+intent.plan_id,
                signal_px=trigger.reference,signal_ts=utc(intent.trigger_ms),order_px=intent.price.worst_acceptable_entry,
                order_px_source='LIMIT',order_ts=utc(now_ms))
            self.ledger.record_order(order)
            data.setdefault('realtime_pending',{})[ident] = {
                'plan_id':intent.plan_id,'contract':intent.contract_code,'side':intent.side.value,
                'quantity':intent.size.contracts,'limit':str(intent.price.worst_acceptable_entry),
                'not_before_ms':now_ms+self.latency_ms,'submitted_ms':now_ms,'epoch':trigger.epoch,
                'notional':str(intent.size.notional),'risk':str(intent.size.risk_at_stop),
                'fee_rate':str(fee_rate),'context':initial_context(intent,trigger,contract,holding_parameters),
                'latency_source':'CONFIGURED_ASSUMPTION_NOT_FORMAL_EXECUTION_EVIDENCE'}
            self.engine.terminal(intent.plan_id,'SUBMITTED',client_order_id=ident)
        except Exception:
            self.ledger.data=before
            self.engine.rows=before_plans
            raise
        return 'SUBMITTED'

    def on_book(self, event, *, now_ms):
        if not event.fresh(now_ms):
            for ident,pending in list(self.ledger.data.get('realtime_pending',{}).items()):
                if event.contract==pending['contract'] and event.received_ms>=pending['not_before_ms']:
                    self._finish_no_fill(ident,pending,'STALE_MATCHING_BOOK')
            return []
        result=[]
        for ident, pending in list(self.ledger.data.get('realtime_pending',{}).items()):
            if event.contract!=pending['contract'] or event.received_ms < pending['not_before_ms']:
                continue
            if event.epoch!=pending['epoch']:
                # No invented matching across an unobserved interval.
                self._finish_no_fill(ident,pending,'CAPTURE_GAP_NO_SIMULATED_FILL')
                continue
            long=pending['side']=='LONG'
            price=event.ask if long else event.bid
            available=event.ask_size if long else event.bid_size
            crosses=price<=D(pending['limit']) if long else price>=D(pending['limit'])
            qty=min(pending['quantity'],int(available or 0)) if crosses else 0
            if qty<=0:
                self._finish_no_fill(ident,pending,'IOC_NO_FILL')
                continue
            # Single subsequent book gives conservative L1 matching; no hindsight OHLC fills.
            position_id='rt-paper:'+ident
            when=utc(event.received_ms);context=pending['context'];size=D(context['contract_size'])
            fee=price*qty*size*D(pending['fee_rate'])
            position=Position(position_id,self.account,pending['contract'],pending['side'],qty,price,D(0),'OPEN',when,
                              strategy_run_id='rt:'+pending['plan_id'])
            self.ledger.open_position(position)
            phase='FILLED' if qty==pending['quantity'] else 'PARTIAL'
            self.ledger.record_fill(Fill('rt-fill:'+ident,ident,self.account,qty,price,fee,when),order_status=phase)
            # Entry order.position_id stays None for existing PAPER fee/ledger semantics.
            context.update(first_fill_ms=event.received_ms,first_fill_source='PAPER_NEXT_BOOK',
                highest=str(price),lowest=str(price),entry_fee=str(fee),original_quantity=qty,extrema_coverage='COMPLETE',
                last_bar=event.received_ms//900000*900-900,
                continuation_last_bar=event.received_ms//900000*900-900)
            self.ledger.save_position_context(position_id,context)
            del self.ledger.data['realtime_pending'][ident]
            self.engine.terminal(pending['plan_id'],phase,position_id=position_id,filled_contracts=qty,
                                 canceled_contracts=pending['quantity']-qty)
            result.append(position_id)
        return result

    def _finish_no_fill(self, ident, pending, reason):
        self.ledger.update_order_status(ident,'CANCELLED')
        del self.ledger.data['realtime_pending'][ident]
        self.engine.terminal(pending['plan_id'],'NO_FILL',reason=reason)

    def recover(self):
        # PAPER cannot infer fills while stopped. Persist a censored no-fill, never replay the order.
        for ident,pending in list(self.ledger.data.get('realtime_pending',{}).items()):
            self._finish_no_fill(ident,pending,'RESTART_UNOBSERVED_EXECUTION')


class LiveBroker:
    def __init__(self, entry_executor, engine, *, guard):
        self.entry,self.engine,self.guard=entry_executor,engine,guard
        self.repo=entry_executor._repository
        self.instance,self.account=entry_executor._instance,entry_executor._account
        self.executor=entry_executor._executor

    def facts(self, contract, side, book, config, leverage):
        totals=self.repo.active_risk_reservation_totals(self.account.account_id)
        snapshot=self.entry._facts.snapshot(contract.contract_code,side,book,
            active_position_count=self.repo.active_position_count(account_id=self.account.account_id),
            max_active_positions=config.max_active_positions,reserved_notional_usdt=totals.notional_usdt,
            reserved_risk_usdt=totals.risk_usdt,reserved_margin_usdt=totals.margin_usdt,risk_capacity_usdt=None,
            same_direction_position_count=self.repo.active_direction_position_count(account_id=self.account.account_id,side=side.value))
        if snapshot.fee_source!='HTX_ACCOUNT':
            raise ValueError('realtime LIVE requires actual fee evidence')
        conflict=self.repo.active_contract_position(account_id=self.account.account_id,contract_code=contract.contract_code)
        return replace(snapshot.facts,contract_open_order=snapshot.facts.contract_open_order or conflict is not None)

    def submit(self, intent, trigger, contract, *, now_ms, fee_rate, holding_parameters, facts=None, config=None):
        self.guard()
        if facts is None or config is None:
            raise ValueError('preflight risk facts required')
        ident=intent.client_order_id;position_key='python-rt-'+ident;run='rt:'+intent.plan_id
        reservation=_reservation_key(self.instance,position_key)
        if self.engine.rows[intent.plan_id]['phase'] in ('SUBMITTED','UNKNOWN'):
            return 'UNKNOWN'
        self.engine.terminal(intent.plan_id,'SUBMITTED',client_order_id=ident,position_key=position_key,
                             reservation_key=reservation,contract=contract.contract_code,stage='PREPARED')
        submitted=False;position_id=None;reserved=False
        request=SwapOrderRequest(contract.contract_code,intent.side.value.lower(),
            'buy' if intent.side is TrendSide.LONG else 'sell',
            'limit',ident,str(intent.size.contracts),price=str(intent.price.worst_acceptable_entry),time_in_force='ioc')
        try:
            self.repo.reserve_risk(reservation_key=reservation,instance=self.instance,position_key=position_key,
                notional_usdt=intent.size.notional,risk_usdt=intent.size.risk_at_stop,
                max_total_risk_usdt=facts.account_equity_usdt*config.max_total_open_risk_rate,
                max_active_positions=config.max_active_positions,reason='realtime IOC preflight',
                margin_usdt=intent.size.notional/facts.leverage,used_margin_usdt=facts.used_margin_usdt,
                max_total_margin_usdt=config.account_equity_usdt*config.max_total_margin_rate)
            reserved=True
            self.executor.set_leverage(contract.contract_code,intent.side.value.lower(),int(facts.leverage))
            self.repo._connection.begin();self.repo._in_transaction=True
            units=intent.size.contracts*contract.contract_size
            position_id=self.repo.record_position_intent(PositionIntent(position_key,run,contract.contract_code,intent.side.value,
                contract.contract_size,intent.size.contracts,int(facts.leverage),self.account,intent.model,None,
                'REALTIME_STRUCTURE', 'realtime cost/risk passed',intent.price.invalidation,trigger.target_low,trigger.target_high,
                intent.price.expected_net_r,strategy_name=VERSION,confirmation_models='',policy_version=VERSION,
                entry_hypothesis=intent.plan_id,entry_fee_rate=fee_rate,estimated_entry_fee=intent.price.entry_fee*units,
                estimated_close_fee=intent.price.target_exit_fee*units,expected_net_after_fees=intent.price.expected_net_reward*units))
            self.repo.record_order_intent(TrendOrderIntent(position_id,position_key,run,contract.contract_code,'ENTRY',intent.side.value,
                request.side.upper(),request.position_side.upper(),False,ident,intent.size.contracts,self.account,intent.model,None,
                'realtime price-capped IOC',strategy_name=VERSION,fee_rate_used=fee_rate,signal_px=trigger.reference,
                signal_ts=utc(intent.trigger_ms),order_px=intent.price.worst_acceptable_entry,order_px_source='LIMIT'))
            context=initial_context(intent,trigger,contract,holding_parameters)
            context['entry_execution']={'schema':'realtime-ioc-v1','client_order_id':ident,'submitted_ms':now_ms,
                'type':'limit','time_in_force':'ioc','price':str(intent.price.worst_acceptable_entry)}
            self.repo.initialize_entry_state(position_id,context)
            self.repo._connection.commit();self.repo._in_transaction=False
            self.engine.terminal(intent.plan_id,'SUBMITTED',position_id=position_id,stage='INTENT_DURABLE')
            self.guard()
            # Network/preflight work must not turn a fresh trigger into a stale order.
            if int(time.time()*1000)-trigger.trigger_ms>2000:
                raise ValueError('trigger expired before send')
            submitted=True
            response=self.executor.place(request)
            exchange_id=_order_id(response)
            if exchange_id is None:raise ValueError('accepted response lacks order identity')
            self.repo.mark_order_accepted(position_id=position_id,leg='ENTRY',client_order_id=ident,
                exchange_order_id=exchange_id,exchange_client_order_id=_client_id(response),message='realtime IOC accepted',order_ts=utc(now_ms))
            self.repo.mark_position_status(position_id=position_id,status='ENTRY_SENT',message='realtime IOC sent')
            return 'SUBMITTED'
        except Exception as error:
            if self.repo._in_transaction:
                self.repo._connection.rollback();self.repo._in_transaction=False;position_id=None
            phase='UNKNOWN' if submitted else 'REJECTED'
            if position_id is not None:
                method=self.repo.mark_order_unknown if submitted else self.repo.mark_order_rejected
                method(position_id=position_id,leg='ENTRY',client_order_id=ident,message=type(error).__name__)
                self.repo.mark_position_status(position_id=position_id,status=phase,message='realtime '+phase)
            if reserved and not submitted:self.repo.release_risk(reservation,'realtime request not sent')
            self.engine.terminal(intent.plan_id,phase,reason=type(error).__name__)
            return phase

    def recover(self):
        self.guard()
        results=self.entry.reconcile_pending()
        for ident,row in list(self.engine.rows.items()):
            if row['phase'] not in {'SUBMITTED','UNKNOWN'}:continue
            client=row.get('client_order_id')
            if not client:raise ValueError('submitted plan has no stable order id')
            order=self.repo._one('SELECT * FROM htx_trend_order WHERE client_order_id=%s AND account_id=%s',
                                 (client,self.account.account_id))
            if order is None:
                # Durable PREPARED with no order means send was impossible.
                self.repo.release_risk(row['reservation_key'],'prepared realtime intent never sent')
                self.engine.terminal(ident,'REJECTED',reason='PREPARED_NOT_SENT')
            else:
                # Normal order projector remains authoritative for all partial/unknown results.
                status=str(order.get('status','')).upper()
                filled=D(str(order.get('filled_contracts') or 0))
                if status in ORDER_TERMINAL:
                    phase='FILLED' if status=='FILLED' else 'PARTIAL' if filled>0 else 'NO_FILL'
                    self.engine.terminal(ident,phase,position_id=order['position_id'])
        if any((status=='UNKNOWN' or status.startswith('ERROR:')) for status in results.values()):
            raise ValueError('pending order recovery incomplete')


from ..paper import PaperExchange


class RealtimePaperExchange(PaperExchange):
    """Reduction matching at the supplied executable quote with owned fee semantics."""
    contract_size=D(1)
    reduction_fee_rate=D('.0004')

    @property
    def fee_rate(self):
        return self.reduction_fee_rate

    def submit(self,request,candle):
        if request.position_id is None:
            raise ValueError('realtime opening orders must use the IOC broker')
        self._fee_rate=self.reduction_fee_rate*self.contract_size
        return super().submit(request,candle)
