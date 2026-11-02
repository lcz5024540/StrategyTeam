"""Manage exchange-confirmed positions before scanning new entries.

A decision never decrements inventory: only the transactional order projection does.
"""
from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal as D
from typing import Any

from .account_facts import _position_volumes, _taker_fee
from .execution_service import _numeric_client_order_id, _order_id, _client_id
from .exits import ExitAction, ExitInput, ProfitProtectionPolicy, decide_v4, decide_legacy
from .continuation import (BASELINE, CONFIRM, LINKED, ContinuationConfig, advance_confirmation,
                           decide_continuation)
from .htx_v5 import SwapOrderRequest
from .indicators import ema
from .java_entry_contract import JavaEntryConfig, evaluate
from .market_data import period_seconds
from .models import ModelCode, TrendSide
from .order_px_fields import order_px_fields
from .mysql_store import TrendOrderIntent, decision_key
from .timeframes import TimeframeProfile

ZERO = D(0)


from .exit_config import ExitConfig
from .holding_policy import resolve_holding_policy


def decimal(row, key, default=ZERO):
    value=row.get(key); return D(str(value)) if value is not None else default


def timestamp(value):
    if value is None: return None
    parsed=datetime.fromisoformat(value) if isinstance(value,str) else value
    return parsed.replace(tzinfo=UTC) if parsed.tzinfo is None else parsed


def _entry_epoch(row: dict[str, Any]) -> int | None:
    """Best-effort entry epoch for bar-counting; returns None when unavailable."""
    # Tests may pass an explicit epoch key; production rows carry datetime fields.
    if 'entry_filled_at_epoch' in row:
        return int(row['entry_filled_at_epoch'])
    if 'created_at_epoch' in row:
        return int(row['created_at_epoch'])
    for key in ('entry_filled_at', 'created_at'):
        value = row.get(key)
        if value is None:
            continue
        try:
            parsed = timestamp(value)
            if parsed is not None:
                return int(parsed.timestamp())
        except Exception:
            continue
    return None


def timeframe_side(candles):
    if len(candles)<23: raise ValueError('incomplete exit confirmation window')
    closes=[c.close for c in candles]; fast,slow=ema(closes,8),ema(closes,21)
    if closes[-1]>closes[-2] and fast>slow and closes[-1]>=fast: return TrendSide.LONG
    if closes[-1]<closes[-2] and fast<slow and closes[-1]<=fast: return TrendSide.SHORT
    return TrendSide.FLAT


class LivePositionManager:
    def __init__(self, repository, instance, account, market, client, executor, *,
                 period='15min', timeframe_profile: TimeframeProfile | None = None,
                 java_config=JavaEntryConfig(), config=ExitConfig(), evidence_store=None,
                 continuation_config: ContinuationConfig | None = None,
                 require_holding_policy: bool = False, holding_adoption=None):
        self.repo,self.instance,self.account=repository,instance,account
        self.market,self.client,self.executor=market,client,executor
        self.period,self.java_config,self.config=period,java_config,config
        self.timeframe_profile = timeframe_profile or TimeframeProfile.for_period(period)
        self.timeframe_profile.validate(period, self.timeframe_profile.contract.trend_period)
        self.evidence_store=evidence_store
        self._continuation = continuation_config or ContinuationConfig(BASELINE)
        self.require_holding_policy = require_holding_policy
        self.holding_adoption = holding_adoption

    def _evidence(self, kind, payload, refs=()):
        if self.evidence_store is None:
            return None
        try:
            return self.evidence_store.put(kind, payload, refs=tuple(refs))
        except Exception:
            # Evidence outages must never suppress a protective reduction.
            return None

    def manage(self, now_epoch: int, market_context) -> list[str]:
        failures=[]
        for row in self.repo.managed_positions(self.instance):
            if (row.get('timeframe_profile') or TimeframeProfile.INTRADAY_15M.value) != self.timeframe_profile.value:
                continue
            try: self._manage(row,now_epoch,market_context)
            except Exception as error:
                # One broken symbol must not prevent protection on all others.
                failures.append(f'position={row["id"]}:{type(error).__name__}')
        return failures

    def _manage(self, row, now_epoch, context):
        pending=[o for o in self.repo.pending_orders(account_id=self.account.account_id,
            strategy_family=self.instance.strategy_family,strategy_name=self.instance.strategy_name)
            if int(o['position_id'])==int(row['id'])]
        if any(o['leg']!='ENTRY' or o['status']=='UNKNOWN' for o in pending):
            raise ValueError('pending close or unknown order requires reconciliation')
        quantity=int(decimal(row,'remaining_contract_volume'))
        if quantity<=0: return
        side=TrendSide(row['side']); code=row['contract_code']
        same,opposite=_position_volumes(self.client.positions(code),side)
        if same!=quantity or opposite>ZERO:
            self.repo.mark_position_status(position_id=row['id'],status='UNKNOWN',message='exchange/strategy volume mismatch; no external position takeover')
            raise ValueError('exchange/strategy position mismatch')
        book=self.market.order_book(code)
        reference=book.top.bid if side is TrendSide.LONG else book.top.ask
        entry=decimal(row,'entry_avg_price'); size=decimal(row,'contract_size')
        entry_quantity=decimal(row,'entry_filled_contracts')
        stop=decimal(row,'stop_price',decimal(row,'invalidation_price'))
        invalidation=decimal(row,'invalidation_price')
        if entry<=ZERO or size<=ZERO or entry_quantity<=ZERO:
            raise ValueError('incomplete confirmed position')
        if stop<=ZERO or invalidation<=ZERO:
            raise ValueError('position lacks a positive stop boundary')
        # Hard loss boundaries depend only on an executable reference and the
        # confirmed position. Missing model/funding/higher-timeframe data must
        # never suppress a stop.
        boundary=max(stop,invalidation) if side is TrendSide.LONG else min(stop,invalidation)
        hard_stop=(reference<=boundary if side is TrendSide.LONG else reference>=boundary)
        if hard_stop:
            run_id=f'python-hard-stop:{row["id"]}:{now_epoch}'
            self._evidence('exit_decision_trace', {'run_id': run_id, 'position_id': row['id'],
                'condition_id': 'position.hard_stop', 'status': 'PASS', 'reference': reference,
                'boundary': boundary, 'action': 'FULL_CLOSE'})
            if pending:
                for order in pending:
                    if order.get('exchange_order_id') is None:
                        raise ValueError('entry has no confirmed exchange id')
                    self.executor.cancel(code,str(order['exchange_order_id']))
                return
            self.submit_close(row,quantity,run_id,'initial/model stop invalidated',mid=book.top.mid)
            return
        state=self.repo.exit_state(row['id'])
        if state.get('holding_policy') is None and self.holding_adoption is not None:
            state = self.repo.adopt_holding_policy(row['id'], self.holding_adoption,
                                                  instance_key=self.instance.instance_key)
        policy = resolve_holding_policy(state, required=self.require_holding_policy)
        config, java_config = self.config, self.java_config
        if policy is not None:
            policy.require_owner(instance_key=self.instance.instance_key, account_id=self.account.account_id,
                strategy_family=self.instance.strategy_family,
                strategy_name=row.get('strategy_name', self.instance.strategy_name), mode='LIVE',
                profile=self.timeframe_profile)
            config, java_config = policy.exit, policy.entry
        window=self.market.kline_window(code,self.period,160,now_epoch=now_epoch)
        if not window.is_complete: raise ValueError('incomplete management candles')
        signal=evaluate(window.candles,java_config)
        if signal.indicators is None: raise ValueError('missing exit indicators')
        last_bar=window.candles[-1].open_time
        if now_epoch-last_bar>2*period_seconds(self.period): raise ValueError('stale management candles')
        opposition=0
        evidence_refs=list(window.evidence_refs)
        for period in self.timeframe_profile.contract.confirmation_periods:
            higher=self.market.kline_window(code,period,80,now_epoch=now_epoch)
            if not higher.is_complete: raise ValueError('incomplete confirmation candles')
            evidence_refs.extend(higher.evidence_refs)
            direction=timeframe_side(higher.candles)
            opposition += direction not in (side,TrendSide.FLAT)
        try: rate=_taker_fee(self.client.swap_fee(code)); fee_source='HTX_ACCOUNT'
        except Exception: rate=config.fee_rate; fee_source='CONFIGURED_FALLBACK'
        # New entries persist their policy before submission. Untagged legacy
        # positions retain baseline ownership, independent of current env.
        version = state.get('continuation_version', BASELINE)
        if version not in (BASELINE, CONFIRM, LINKED):
            raise ValueError('unknown position continuation version')
        highest=max(decimal(state,'highest',decimal(row,'highest_price',entry)),reference)
        lowest=min(decimal(state,'lowest',decimal(row,'lowest_price',entry)),reference)
        protection=decimal(state,'protection',decimal(row,'profit_protection_price')) or None
        armed=timestamp(state.get('armed_at') or row.get('trailing_armed_at'))
        last=int(state.get('last_bar',0)); flat=int(state.get('flat',0)); breach=int(state.get('breach',0))
        # Repeated scheduler polls cannot count as consecutive completed bars.
        if last_bar!=last:
            flat=flat+1 if signal.side is TrendSide.FLAT else 0
            crossed=protection is not None and (window.candles[-1].close<=protection if side is TrendSide.LONG else window.candles[-1].close>=protection)
            breach=breach+1 if crossed and armed is not None and last_bar>=int(armed.timestamp()) else 0
        entry_fee=decimal(row,'entry_fee')*D(quantity)/entry_quantity
        close_fee=reference*size*quantity*rate
        gross=(reference-entry)*size*quantity*(1 if side is TrendSide.LONG else -1)
        best=highest if side is TrendSide.LONG else lowest
        mfe=(best-entry)*size*quantity*(1 if side is TrendSide.LONG else -1)-entry_fee-best*size*quantity*rate
        funding=self.market.funding_rate(code).rate
        funding_reason='adverse current funding rate' if (funding if side is TrendSide.LONG else -funding)>config.max_adverse_funding_rate else None
        entry_epoch = _entry_epoch(row)
        bars_held = 0
        if entry_epoch is not None and now_epoch > entry_epoch:
            bars_held = max(0, (now_epoch - entry_epoch) // period_seconds(self.period))
        input_=ExitInput(ModelCode(row['primary_model']),side,quantity,size,entry,
            decimal(row,'stop_price',decimal(row,'invalidation_price')),decimal(row,'invalidation_price'),
            decimal(row,'target_zone_low' if side is TrendSide.LONG else 'target_zone_high') or None,
            reference,protection,highest,lowest,signal.indicators.atr,signal.side,
            signal.indicators.ema_fast,signal.indicators.ema_slow,opposition,context.opposed(side),
            gross-entry_fee-close_fee,entry_fee,close_fee,armed,state.get('arm_reason') or row.get('trailing_arm_reason'),
            decimal(state,'max_r',decimal(row,'max_favorable_r')),datetime.fromtimestamp(now_epoch,UTC),
            flat_streak=flat,required_flat_streak=config.flat_bars,
            flat_loss_budget=max(config.flat_budget,entry*size*entry_quantity*config.flat_budget_rate),
            policy=config.policy,protection_breach_bars=breach,funding_rate_exit_reason=funding_reason,observed_mfe_net_pnl=mfe,
            bars_held=bars_held,timeout_bars=config.timeout_bars,min_hold_bars=config.min_hold_bars)
        evaluation_candle = window.candles[-1]
        candidate_policy = version in (CONFIRM, LINKED)
        if candidate_policy:
            if self.instance.strategy_name != 'HTX_TREND_15M_V2':
                raise ValueError('candidate position policy requires 15m')
            count, last_bar = advance_confirmation(input_, evaluation_candle, state)
            cont_decision = decide_continuation(input_, count)
            if (state.get('continuation_last_bar') is not None
                    and evaluation_candle.open_time <= int(state['continuation_last_bar'])):
                from dataclasses import replace
                cont_decision = replace(cont_decision, action=ExitAction.HOLD, target_remaining_contracts=None,
                                        reason='continuation-duplicate-bar')
            state.update(continuation_breach_count=count, continuation_last_bar=last_bar)
            if cont_decision.action is ExitAction.FULL_CLOSE:
                state['complete_exit'] = dict(position_id=row['id'], side=row['side'], reason=cont_decision.reason,
                    closed_at=datetime.fromtimestamp(now_epoch, UTC).isoformat(),
                    protection=str(cont_decision.protection_price) if cont_decision.protection_price else None,
                    bar_time=evaluation_candle.open_time, bar_high=str(evaluation_candle.high),
                    bar_low=str(evaluation_candle.low), evidence_status='RECORDED')
            decision = cont_decision
            use_v4 = False
        else:
            use_v4=config.engine_enabled
            decision=decide_v4(input_) if use_v4 else decide_legacy(input_,trailing_atr_multiplier=(config.swing_trailing_atr
                if row.get('timeframe_profile') in {'SWING_4H','TREND_1D'} else config.trailing_atr),trailing_activation_r=config.trailing_activation_r)
        self._evidence('exit_decision_trace', {'position_id': row['id'], 'strategy_version': self.instance.strategy_name,
            'engine_version': 'exit-engine-v4' if use_v4 else 'legacy', 'input': input_, 'decision': decision,
            'conditions': {'hard_stop': False, 'management_data_complete': True,
                           'higher_timeframe_opposition': opposition, 'funding_reason': funding_reason}}, evidence_refs)
        state.update(highest=highest,lowest=lowest,protection=decision.protection_price,
            armed_at=decision.trailing_armed_at.isoformat() if decision.trailing_armed_at else None,
            arm_reason=decision.trailing_arm_reason,max_r=decision.max_favorable_r,last_bar=last_bar,flat=flat,breach=breach)
        self.repo.save_exit_state(row['id'],state,decision)
        run_id=f'python-exit:{row["id"]}:{now_epoch}'
        # Java's exit-engine live flag blocks every v4 mutation while still
        # recording the decision. It must not fall back to executable legacy.
        blocked='exit engine live disabled' if use_v4 and not config.engine_live else None
        self.repo.record_exit_decision(decision_key=decision_key(row['id'],run_id,decision.action.value),position_id=row['id'],run_id=run_id,
            account=self.account,contract_code=code,side=side.value,primary_model=row['primary_model'],engine_version='exit-engine-v4' if use_v4 else 'legacy',
            exit_phase=decision.phase.value,suggested_action=decision.action.value,executed_action='NONE',reference_price=reference,
            protection_price=decision.protection_price,current_net_pnl=input_.current_net_pnl,max_favorable_r=decision.max_favorable_r,
            target_remaining=decision.target_remaining_contracts,evidence={**decision.evidence,'reason':decision.reason,'fee_source':fee_source},block_reason=blocked)
        if blocked or decision.action not in {ExitAction.FULL_CLOSE,ExitAction.PARTIAL_CLOSE}: return
        # Stop any residual entry first. Never create a simultaneous opening and closing order.
        if pending:
            for order in pending:
                if order.get('exchange_order_id') is None: raise ValueError('entry has no confirmed exchange id')
                self.executor.cancel(code,str(order['exchange_order_id']))
            return
        volume=quantity if decision.action is ExitAction.FULL_CLOSE else quantity-int(decision.target_remaining_contracts)
        if not 0<volume<=quantity: raise ValueError('invalid reduction quantity')
        self.submit_close(row,volume,run_id,decision.reason,mid=book.top.mid)

    def submit_close(self,row,volume,run_id,reason,*,mid=None,last=None):
        client_id=_numeric_client_order_id(); leg='CLOSE' if volume==int(decimal(row,'remaining_contract_volume')) else 'PARTIAL_CLOSE'
        request=SwapOrderRequest(row['contract_code'],row['side'].lower(),'sell' if row['side']=='LONG' else 'buy',
            'market',client_id,str(volume),reduce_only=True)
        self.repo.record_order_intent(TrendOrderIntent(row['id'],row['position_key'],run_id,row['contract_code'],leg,
            row['side'],request.side.upper(),request.position_side.upper(),True,client_id,volume,self.account,
            row['primary_model'],row.get('factor_snapshot_id'),reason,row.get('timeframe_profile') or 'INTRADAY_15M',
            self.instance.strategy_family,self.instance.strategy_name,
            **order_px_fields(request.type, limit=request.price, mid=mid, last=last)))
        self.repo._write('UPDATE htx_trend_position SET exit_reason=%s WHERE id=%s',(reason,row['id']))
        order_ts = datetime.now(UTC)
        try:
            response=self.executor.place(request); order_id=_order_id(response)
            if order_id is None: raise RuntimeError('close response lacks exchange id')
            self.repo.mark_order_accepted(position_id=row['id'],leg=leg,client_order_id=client_id,
                exchange_order_id=order_id,exchange_client_order_id=_client_id(response),message='reduce-only close accepted',order_ts=order_ts)
            self.repo.mark_position_status(position_id=row['id'],status='CLOSE_SENT',message=reason)
        except Exception as error:
            self.repo.mark_order_unknown(position_id=row['id'],leg=leg,client_order_id=client_id,message=f'close unknown:{type(error).__name__}',order_ts=order_ts)
            self.repo.mark_position_status(position_id=row['id'],status='UNKNOWN',message='close outcome requires reconciliation')
            raise
