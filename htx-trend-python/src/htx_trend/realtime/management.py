"""Post-fill extrema and separate realtime hard-stop / closed-bar evaluation clocks."""
from __future__ import annotations

from dataclasses import asdict, replace, dataclass
from datetime import UTC, datetime
from decimal import Decimal as D
import copy
from ..holding_policy import resolve_holding_policy
from ..holding_adoption import adopted_state

from ..continuation import latest_completed, reentry_gate
from ..execution import GateResult
from ..java_entry_contract import evaluate
from ..market_data import OrderBook, Candle
from ..models import TrendSide
from ..position_manager import timeframe_side, decimal
from ..strategy_context import MarketContext
from ..research.store import primitive
from . import VERSION
from .ownership import is_realtime


def paper_reentry(ledger, account, trigger, candle, book, evidence_store):
    _, previous = latest_completed(ledger, account, trigger.plan.contract, evidence_store)
    return check_reentry(trigger, candle, book, previous)


def check_reentry(trigger, candle, book, previous):
    # Explicit adapter of structure bar only: no fake score/model or trigger-as-bar timestamp.
    @dataclass(frozen=True)
    class Source:
        side: TrendSide
        bar_time: int
    return reentry_gate(Source(trigger.side, trigger.plan.structure_bar), candle, book, previous)


class PaperManagement:
    def __init__(self, runtime, broker, frames, market, *, exit_config, entry_config, evidence_store=None, holding_adoption=None, context_provider=None):
        self.runtime,self.broker,self.frames,self.market=runtime,broker,frames,market
        self.ledger,self.account=broker.ledger,broker.account
        self.exit_config,self.entry_config=exit_config,entry_config
        self.evidence_store=evidence_store
        self.holding_adoption=holding_adoption
        self.context_provider=context_provider
        self.ledger_failed=False
        self.last_checkpoint_ms=0
        for position in self.ledger.active_positions(self.account):
            context=self.ledger.position_context(position.position_id)
            if context.get('entry_strategy_version')==VERSION:
                context['extrema_coverage']='GAP_AFTER_RESTART'
                self.ledger.save_position_context(position.position_id,context)

    def event(self, event, now_ms):
        if event.kind!='BBO' or not event.fresh(now_ms):return
        for position in self.ledger.active_positions(self.account):
            if position.contract_code!=event.contract:continue
            context=self.ledger.position_context(position.position_id)
            if context.get('entry_strategy_version')!=VERSION:continue
            first=context.get('first_fill_ms')
            if first is None or event.exchange_ms<first:continue
            price=event.bid if position.side=='LONG' else event.ask
            context.update(highest=str(max(D(context['highest']),price)),lowest=str(min(D(context['lowest']),price)))
            self.ledger.save_position_context(position.position_id,context)

    def gap(self,*,contract=None):
        for position in self.ledger.active_positions(self.account):
            if contract is not None and position.contract_code!=contract:continue
            context=self.ledger.position_context(position.position_id)
            if context.get('entry_strategy_version')!=VERSION:continue
            context['extrema_coverage']='GAP'
            self.ledger.save_position_context(position.position_id,context)
        self.ledger.checkpoint()

    def tick(self, now_ms, *, contract=None):
        failures=[]
        if self.ledger_failed:
            self.ledger.checkpoint()
            self.ledger_failed=False
            return ['AUTHORITATIVE_LEDGER_RECOVERED']
        for position in list(self.ledger.active_positions(self.account)):
            if contract is not None and position.contract_code!=contract:continue
            try:
                context=self.ledger.position_context(position.position_id)
                realtime=context.get('entry_strategy_version')==VERSION
                if not realtime:
                    frame=self.frames.get(position.contract_code)
                    if frame is None:raise ValueError('legacy exit candles unavailable')
                    if frame['entry'].candles[-1].open_time<=self.ledger.data['bars'].get(position.contract_code,-1):continue
                book=self.market.order_book(position.contract_code)
                reference=book.top.bid if position.side=='LONG' else book.top.ask
                self.runtime._exchange.reduction_fee_rate=(D(context.get('holding_policy',{}).get('exit',{}).get('fee_rate','.0004'))
                    if realtime else D('.0004'))
                self.runtime._exchange.contract_size=D(context['contract_size'])
                self.runtime._exchange.book=book.top
                boundary=D(context['initial_stop'])
                hard=reference<=boundary if position.side=='LONG' else reference>=boundary
                if hard and realtime:
                    run=f'rt-hard-stop:{position.position_id}'
                    self.runtime._exchange.reduce_position(position,quantity=position.quantity,exit_price=reference,
                        contract_size=D(context['contract_size']),when=datetime.fromtimestamp(now_ms/1000,UTC),run_id=run)
                    self.ledger.decision(self.account,run,'EXIT_DECISION',{'action':'FULL_CLOSE','reason':'model-or-initial-stop-invalidated',
                        'reference':str(reference),'boundary':str(boundary)},position_id=position.position_id)
                    self.ledger.checkpoint()
                    continue
                frame=self.frames.get(position.contract_code)
                if frame is None:raise ValueError('exit closed candles unavailable')
                window=frame['entry'];bar=window.candles[-1]
                first=context.get('first_fill_ms')
                if not realtime:first=0
                if first is None:raise ValueError('first fill boundary unavailable')
                if (bar.open_time+900)*1000<=first or bar.open_time<=int(context.get('last_bar',-1)):
                    continue
                if not 0 <= now_ms//1000-(bar.open_time+900)<900:raise ValueError('exit candles stale')
                if context.get('holding_policy') is None and self.holding_adoption is not None:
                    policy=self.holding_adoption.policy_for(self.ledger.data['positions'][position.position_id],context,
                        instance_key=self.runtime._evidence_context['instance'],mode='PAPER')
                    context=adopted_state(context,policy)
                    self.ledger.save_position_context(position.position_id,context)
                policy=resolve_holding_policy(context,required=True)
                signal=evaluate(window.candles,policy.entry)
                if not realtime:
                    context['highest']=str(max(D(context['highest']),bar.high))
                    context['lowest']=str(min(D(context['lowest']),bar.low))
                if signal.indicators is None:raise ValueError('exit indicators unavailable')
                # Never merge the whole OHLC range into post-entry extrema.
                flat=(int(context.get('flat_streak',0))+1 if signal.side is TrendSide.FLAT else 0) if realtime else 0
                context['flat_streak']=flat
                self.ledger.save_position_context(position.position_id,context)
                opposition=sum(timeframe_side(w.candles) not in (TrendSide(position.side),TrendSide.FLAT)
                               for period,w in frame['windows'].items() if period in ('60min','4hour')) if realtime else 0
                funding_reason=None
                if realtime:
                    funding=self.market.funding_rate(position.contract_code).rate
                    if (funding if position.side=='LONG' else -funding)>policy.exit.max_adverse_funding_rate:
                        funding_reason='adverse current funding rate'
                self.runtime.manage(run_id=f'rt-manage:{position.position_id}:{bar.open_time}',position_id=position.position_id,
                    reference_price=reference,highest_price=D(context['highest']),lowest_price=D(context['lowest']),
                    atr=signal.indicators.atr,signal_side=signal.side,ema_fast=signal.indicators.ema_fast,ema_slow=signal.indicators.ema_slow,
                    major_timeframe_opposition=opposition,market_context_opposes=(self.context_provider(now_ms).opposed(TrendSide(position.side)) if realtime and self.context_provider else False),at=datetime.fromtimestamp(now_ms/1000,UTC),
                    flat_streak=flat,funding_rate_exit_reason=funding_reason,evidence_windows=(window,frame['trend']),evaluation_candle=bar,
                    market_snapshot={'book':book,'first_fill_ms':first,'extrema_coverage':context.get('extrema_coverage','LEGACY')})
                context=self.ledger.position_context(position.position_id)
                context['last_bar']=bar.open_time
                self.ledger.save_position_context(position.position_id,context)
                if not realtime:self.ledger.data['bars'][position.contract_code]=bar.open_time
                self.ledger.checkpoint()
            except Exception as error:
                failures.append(position.contract_code+':'+type(error).__name__)
                if isinstance(error,OSError):
                    self.ledger_failed=True
                    break
        if now_ms-self.last_checkpoint_ms>=1000:
            self.ledger.checkpoint();self.last_checkpoint_ms=now_ms
        return failures


class LiveManagement:
    def __init__(self, broker, manager, market, *, owners=None, context_provider=None):
        self.broker,self.manager,self.market=broker,manager,market
        self.repo=broker.repo
        self.context_provider=context_provider or (lambda _: None)
        self.last_bar={}
        self.old_managers=[]
        self.last_old_ms=0
        self.cached_rows=[];self.inventory_at=None
        self.cached_state={};self.state_saved={}
        for owner in owners or ():
            if owner.strategy_name!=VERSION:
                old=copy.copy(manager);old.instance=owner
                self.old_managers.append(old)

    def inventory(self,now_ms,*,refresh=False):
        if refresh or self.inventory_at is None or now_ms-self.inventory_at>=1000:
            self.cached_rows=self.repo.managed_positions(self.broker.instance)
            self.inventory_at=now_ms
        return self.cached_rows

    def state(self,ident):
        if ident not in self.cached_state:self.cached_state[ident]=self.repo.exit_state(ident)
        return dict(self.cached_state[ident])

    def event(self,event,now_ms):
        if event.kind!='BBO' or not event.fresh(now_ms):return
        for row in self.inventory(now_ms):
            if row['contract_code']!=event.contract:continue
            state=self.state(row['id'])
            first=state.get('first_fill_ms') or state.get('first_fill_bound_ms')
            if first is None or event.exchange_ms<first:continue
            price=event.bid if row['side']=='LONG' else event.ask
            state['highest']=str(max(D(state['highest']),price))
            state['lowest']=str(min(D(state['lowest']),price))
            self._state(row['id'],state,now_ms=now_ms)

    def gap(self,*,contract=None):
        for row in self.repo.managed_positions(self.broker.instance):
            if contract is not None and row['contract_code']!=contract:continue
            state=self.state(row['id']);state['extrema_coverage']='GAP'
            self._state(row['id'],state,force=True)

    def _state(self,ident,state,*,now_ms=0,force=False):
        self.cached_state[ident]=dict(state)
        if force or ident not in self.state_saved or now_ms-self.state_saved[ident]>=1000:
            self.repo.save_management_state(ident,state)
            self.state_saved[ident]=now_ms

    def flush(self):
        for ident,state in self.cached_state.items():self._state(ident,state,force=True)

    def tick(self,now_ms,*,contract=None):
        failures=[]
        if now_ms-self.last_old_ms>=45000:
            for manager in self.old_managers:
                try:context=self.context_provider(now_ms)
                except Exception:context=None
                failures.extend(manager.manage(now_ms//1000,context))
            self.last_old_ms=now_ms
        for row in self.inventory(now_ms):
            if contract is not None and row['contract_code']!=contract:continue
            try:
                state=self.state(row['id'])
                if decimal(row,'remaining_contract_volume')<=0:continue
                if state.get('position_boundary_initialized') is not True:
                    # Only exchange-confirmed fill receipts may populate first_fill_ms.
                    # An observation bound is explicit and cannot certify full extrema.
                    first=state.get('first_fill_ms') or state.get('first_fill_observed_ms') or now_ms
                    price=decimal(row,'entry_avg_price')
                    state.update(first_fill_observed_ms=state.get('first_fill_observed_ms',now_ms),
                        first_fill_bound_ms=first,position_boundary_initialized=True,
                        first_fill_source='EXCHANGE_RECEIPT' if state.get('first_fill_ms') is not None else 'OBSERVED_UPPER_BOUND',
                        extrema_coverage='GAP',highest=str(price),lowest=str(price),
                        last_bar=first//900000*900-900,continuation_last_bar=first//900000*900-900)
                    self._state(row['id'],state,force=True)
                book=self.market.order_book(row['contract_code'])
                price=book.top.bid if row['side']=='LONG' else book.top.ask
                state['highest']=str(max(D(state['highest']),price));state['lowest']=str(min(D(state['lowest']),price))
                self._state(row['id'],state,now_ms=now_ms)
                stop=decimal(row,'stop_price',decimal(row,'invalidation_price'))
                hard=price<=stop if row['side']=='LONG' else price>=stop
                if hard or state.get('pending_realtime_exit'):
                    self._state(row['id'],state,force=True)
                    try:self.manager._manage(row,now_ms//1000,MarketContext.neutral())
                    finally:self.cached_state.pop(row['id'],None)
                    continue
                bar=self.market.kline_window(row['contract_code'],'15min',160,now_epoch=now_ms//1000).candles[-1]
                due=bar.open_time>int(state.get('last_bar',-1)) and (bar.open_time+900)*1000>state.get('first_fill_bound_ms',state.get('first_fill_ms',now_ms))
                if due:
                    self._state(row['id'],state,force=True)
                    try:self.manager._manage(row,now_ms//1000,self.context_provider(now_ms))
                    finally:self.cached_state.pop(row['id'],None)
            except Exception as error:
                failures.append(row['contract_code']+':'+type(error).__name__)
        return failures
