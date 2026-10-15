"""Manage exchange-confirmed positions before scanning new entries.

A decision never decrements inventory: only the transactional order projection does.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal as D
from os import environ
from typing import Any

from .account_facts import _position_volumes, _taker_fee
from .execution_service import _numeric_client_order_id, _order_id, _client_id
from .exits import ExitAction, ExitInput, ProfitProtectionPolicy, decide_v4, decide_legacy
from .htx_v5 import SwapOrderRequest
from .indicators import ema
from .java_entry_contract import JavaEntryConfig, evaluate
from .market_data import period_seconds
from .models import ModelCode, TrendSide
from .mysql_store import TrendOrderIntent, decision_key
from .timeframes import TimeframeProfile

ZERO = D(0)


@dataclass(frozen=True)
class ExitConfig:
    engine_enabled: bool = False
    engine_live: bool = False
    trailing_atr: D = D('2')
    swing_trailing_atr: D = D('3')
    trailing_activation_r: D = D('1')
    fee_rate: D = D('.0004')
    max_funding_cost: D = D('.0012')
    flat_bars: int = 3
    flat_budget: D = D('.25')
    flat_budget_rate: D = D('.003')
    policy: ProfitProtectionPolicy = ProfitProtectionPolicy()

    @classmethod
    def from_env(cls):
        def flag(name): return environ.get(name,'false').lower() == 'true'
        def dec(name, default): return D(environ.get(name,str(default)))
        default=cls()
        return cls(engine_enabled=flag('HTX_TREND_EXIT_ENGINE_ENABLED'),
            engine_live=flag('HTX_TREND_EXIT_ENGINE_LIVE_ENABLED'),
            trailing_atr=dec('HTX_TREND_TRAILING_ATR_MULTIPLIER',default.trailing_atr),
            swing_trailing_atr=dec('HTX_TREND_SWING_TRAILING_ATR_MULTIPLIER',default.swing_trailing_atr),
            trailing_activation_r=dec('HTX_TREND_TRAILING_ACTIVATION_R',default.trailing_activation_r),
            fee_rate=dec('HTX_TREND_ESTIMATED_TAKER_FEE_RATE',default.fee_rate),
            max_funding_cost=dec('HTX_TREND_MAX_HOLD_FUNDING_COST_RATE',default.max_funding_cost),
            flat_bars=int(environ.get('HTX_TREND_FLAT_LOSS_EXIT_CONFIRMATION_BARS','3')),
            policy=ProfitProtectionPolicy(
                dec('HTX_TREND_INTRADAY_PROFIT_ARM_ATR_MULTIPLIER',D('.50')),
                dec('HTX_TREND_INTRADAY_PROFIT_LOCK_ATR_MULTIPLIER',D('.15')),
                dec('HTX_TREND_INTRADAY_PROFIT_PARTIAL_ATR_MULTIPLIER',D('.75')),
                dec('HTX_TREND_INTRADAY_PROFIT_PULLBACK_ATR_MULTIPLIER',D('.40')),
                dec('HTX_TREND_INTRADAY_PROFIT_MIN_NET_FEE_MULTIPLE',D('2')),
                int(environ.get('HTX_TREND_INTRADAY_PROFIT_PROTECTION_BREACH_CONFIRMATION_BARS','2'))))


def decimal(row, key, default=ZERO):
    value=row.get(key); return D(str(value)) if value is not None else default


def timestamp(value):
    if value is None: return None
    parsed=datetime.fromisoformat(value) if isinstance(value,str) else value
    return parsed.replace(tzinfo=UTC) if parsed.tzinfo is None else parsed


def timeframe_side(candles):
    if len(candles)<23: raise ValueError('incomplete exit confirmation window')
    closes=[c.close for c in candles]; fast,slow=ema(closes,8),ema(closes,21)
    if closes[-1]>closes[-2] and fast>slow and closes[-1]>=fast: return TrendSide.LONG
    if closes[-1]<closes[-2] and fast<slow and closes[-1]<=fast: return TrendSide.SHORT
    return TrendSide.FLAT


class LivePositionManager:
    def __init__(self, repository, instance, account, market, client, executor, *,
                 period='15min', timeframe_profile: TimeframeProfile | None = None,
                 java_config=JavaEntryConfig(), config=ExitConfig(), evidence_store=None):
        self.repo,self.instance,self.account=repository,instance,account
        self.market,self.client,self.executor=market,client,executor
        self.period,self.java_config,self.config=period,java_config,config
        self.timeframe_profile = timeframe_profile or TimeframeProfile.for_period(period)
        self.timeframe_profile.validate(period, self.timeframe_profile.contract.trend_period)
        self.evidence_store=evidence_store

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
            self.submit_close(row,quantity,run_id,'initial/model stop invalidated')
            return
        window=self.market.kline_window(code,self.period,160,now_epoch=now_epoch)
        if not window.is_complete: raise ValueError('incomplete management candles')
        signal=evaluate(window.candles,self.java_config)
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
        except Exception: rate=self.config.fee_rate; fee_source='CONFIGURED_FALLBACK'
        state=self.repo.exit_state(row['id'])
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
        funding_reason='adverse funding cost' if (funding if side is TrendSide.LONG else -funding)>self.config.max_funding_cost else None
        input_=ExitInput(ModelCode(row['primary_model']),side,quantity,size,entry,
            decimal(row,'stop_price',decimal(row,'invalidation_price')),decimal(row,'invalidation_price'),
            decimal(row,'target_zone_low' if side is TrendSide.LONG else 'target_zone_high') or None,
            reference,protection,highest,lowest,signal.indicators.atr,signal.side,
            signal.indicators.ema_fast,signal.indicators.ema_slow,opposition,context.opposed(side),
            gross-entry_fee-close_fee,entry_fee,close_fee,armed,state.get('arm_reason') or row.get('trailing_arm_reason'),
            decimal(state,'max_r',decimal(row,'max_favorable_r')),datetime.fromtimestamp(now_epoch,UTC),
            flat_streak=flat,required_flat_streak=self.config.flat_bars,
            flat_loss_budget=max(self.config.flat_budget,entry*size*entry_quantity*self.config.flat_budget_rate),
            policy=self.config.policy,protection_breach_bars=breach,funding_cost_exit_reason=funding_reason,observed_mfe_net_pnl=mfe)
        use_v4=self.config.engine_enabled and input_.primary_model is ModelCode.FIB_PULLBACK_CONTINUATION
        decision=decide_v4(input_) if use_v4 else decide_legacy(input_,trailing_atr_multiplier=(self.config.swing_trailing_atr
            if row.get('timeframe_profile') in {'SWING_4H','TREND_1D'} else self.config.trailing_atr),trailing_activation_r=self.config.trailing_activation_r)
        self._evidence('exit_decision_trace', {'position_id': row['id'], 'strategy_version': self.instance.strategy_name,
            'engine_version': 'fib-exit-engine-v4' if use_v4 else 'legacy', 'input': input_, 'decision': decision,
            'conditions': {'hard_stop': False, 'management_data_complete': True,
                           'higher_timeframe_opposition': opposition, 'funding_reason': funding_reason}}, evidence_refs)
        state.update(highest=highest,lowest=lowest,protection=decision.protection_price,
            armed_at=decision.trailing_armed_at.isoformat() if decision.trailing_armed_at else None,
            arm_reason=decision.trailing_arm_reason,max_r=decision.max_favorable_r,last_bar=last_bar,flat=flat,breach=breach)
        self.repo.save_exit_state(row['id'],state,decision)
        run_id=f'python-exit:{row["id"]}:{now_epoch}'
        # Java's exit-engine live flag blocks every v4 mutation while still
        # recording the decision. It must not fall back to executable legacy.
        blocked='exit engine live disabled' if use_v4 and not self.config.engine_live else None
        self.repo.record_exit_decision(decision_key=decision_key(row['id'],run_id,decision.action.value),position_id=row['id'],run_id=run_id,
            account=self.account,contract_code=code,side=side.value,primary_model=row['primary_model'],engine_version='fib-exit-engine-v4' if use_v4 else 'legacy',
            exit_phase=decision.phase.value,suggested_action=decision.action.value,executed_action='NONE',reference_price=reference,
            protection_price=decision.protection_price,current_net_pnl=input_.current_net_pnl,max_favorable_r=decision.max_favorable_r,
            target_remaining=decision.target_remaining_contracts,evidence={**decision.evidence,'fee_source':fee_source},block_reason=blocked)
        if blocked or decision.action not in {ExitAction.FULL_CLOSE,ExitAction.PARTIAL_CLOSE}: return
        # Stop any residual entry first. Never create a simultaneous opening and closing order.
        if pending:
            for order in pending:
                if order.get('exchange_order_id') is None: raise ValueError('entry has no confirmed exchange id')
                self.executor.cancel(code,str(order['exchange_order_id']))
            return
        volume=quantity if decision.action is ExitAction.FULL_CLOSE else quantity-int(decision.target_remaining_contracts)
        if not 0<volume<=quantity: raise ValueError('invalid reduction quantity')
        self.submit_close(row,volume,run_id,decision.reason)

    def submit_close(self,row,volume,run_id,reason):
        client_id=_numeric_client_order_id(); leg='CLOSE' if volume==int(decimal(row,'remaining_contract_volume')) else 'PARTIAL_CLOSE'
        request=SwapOrderRequest(row['contract_code'],row['side'].lower(),'sell' if row['side']=='LONG' else 'buy',
            'market',client_id,str(volume),reduce_only=True)
        self.repo.record_order_intent(TrendOrderIntent(row['id'],row['position_key'],run_id,row['contract_code'],leg,
            row['side'],request.side.upper(),request.position_side.upper(),True,client_id,volume,self.account,
            row['primary_model'],row.get('factor_snapshot_id'),reason,row.get('timeframe_profile') or 'INTRADAY_15M',
            self.instance.strategy_family,self.instance.strategy_name))
        self.repo._write('UPDATE htx_trend_position SET exit_reason=%s WHERE id=%s',(reason,row['id']))
        try:
            response=self.executor.place(request); order_id=_order_id(response)
            if order_id is None: raise RuntimeError('close response lacks exchange id')
            self.repo.mark_order_accepted(position_id=row['id'],leg=leg,client_order_id=client_id,
                exchange_order_id=order_id,exchange_client_order_id=_client_id(response),message='reduce-only close accepted')
            self.repo.mark_position_status(position_id=row['id'],status='CLOSE_SENT',message=reason)
        except Exception as error:
            self.repo.mark_order_unknown(position_id=row['id'],leg=leg,client_order_id=client_id,message=f'close unknown:{type(error).__name__}')
            self.repo.mark_position_status(position_id=row['id'],status='UNKNOWN',message='close outcome requires reconciliation')
            raise
