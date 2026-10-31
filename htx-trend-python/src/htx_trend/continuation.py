"""Opt-in 15m continuation policy (PAPER or LIVE) and complete-trade reentry facts.

No order placement, network access, or changes to the shared v4 defaults.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime
from decimal import Decimal as D
import os

from .execution import GateResult, PriorTrade
from .exits import ExitAction, ExitPhase, HoldingState, decide, evaluate_holding
from .models import TrendSide

BASELINE = 'baseline-v4'
CONFIRM = 'confirm-v1'
LINKED = 'linked-v1'
ATR_REASON = 'armed-protection-breach-atr-impulse'
CONFIRMED_REASON = 'continuation-protection-confirmed'
PROTECTION_REASONS = frozenset({ATR_REASON, CONFIRMED_REASON,
    'trend-weakened-protection-breach', 'profit-at-risk-with-deteriorating-continuation',
    'confirmed-profit-protection-breach',
    'legacy-atr-trailing-stop'})


@dataclass(frozen=True)
class ContinuationConfig:
    version: str = BASELINE
    cooldown: bool = False

    def __post_init__(self):
        if self.version not in (BASELINE, CONFIRM, LINKED):
            raise ValueError('unknown continuation version')
        if self.cooldown and self.version != BASELINE:
            raise ValueError('cooldown is an independent baseline-only control')

    @classmethod
    def from_env(cls):
        """PAPER worker default: read HTX_PAPER_* only."""
        return cls.from_paper_env()

    @classmethod
    def from_paper_env(cls):
        value = os.getenv('HTX_PAPER_COMPLETED_TRADE_COOLDOWN', 'false').lower()
        if value not in ('true', 'false'):
            raise ValueError('HTX_PAPER_COMPLETED_TRADE_COOLDOWN must be true/false')
        return cls(os.getenv('HTX_PAPER_CONTINUATION_VERSION', BASELINE), value == 'true')

    @classmethod
    def from_live_env(cls):
        """LIVE 15m switch: HTX_LIVE_CONTINUATION_VERSION only (no PAPER env)."""
        return cls(os.getenv('HTX_LIVE_CONTINUATION_VERSION', BASELINE), False)

    def require_15m(self, is_15m, *, live=False):
        scope = '15m LIVE' if live else '15m PAPER'
        if not is_15m and (self.version != BASELINE or self.cooldown):
            raise ValueError(f'continuation/cooldown experiment requires {scope}')


def crossed(side, price, boundary):
    return boundary is not None and boundary > 0 and (price <= boundary if side == TrendSide.LONG else price >= boundary)


def advance_confirmation(input_, candle, context):
    """Use the pre-evaluation protection. Missing/interrupted bars break a streak."""
    previous = context.get('continuation_last_bar')
    if candle is None:
        return 0, None
    if previous is not None and candle.open_time <= int(previous):
        return int(context.get('continuation_breach_count', 0)), previous
    consecutive = previous is not None and candle.open_time == int(previous) + 900
    count = int(context.get('continuation_breach_count', 0)) if consecutive else 0
    # The close must follow arming; a protection first computed in this
    # evaluation is absent from input_ and cannot count retrospectively.
    armed = input_.trailing_armed_at
    eligible = (armed is not None and candle.open_time + 900 >= int(armed.timestamp())
                and evaluate_holding(input_) is HoldingState.TRENDING
                and crossed(input_.side, candle.close, input_.protection_price))
    adverse = (input_.highest_price - input_.reference_price if input_.side is TrendSide.LONG
               else input_.reference_price - input_.lowest_price)
    trigger = (crossed(input_.side, input_.reference_price, input_.protection_price)
               and input_.atr > 0 and adverse >= input_.atr)
    return (min(2, count + 1) if eligible and (count > 0 or trigger) else 0), candle.open_time


def decide_continuation(input_, count):
    # Do not wire the candidate counter into the original generic breach branch.
    result = decide(replace(input_, protection_breach_bars=0))
    if evaluate_holding(input_) is not HoldingState.TRENDING:
        return result
    target = result.reason in (ATR_REASON, 'armed-protection-breach-awaiting-bar-confirmation')
    hold = result.action in (ExitAction.HOLD, ExitAction.ARM_TRAILING)
    if not target and not hold:
        return result  # Hard/model/funding and other original exits retain priority.
    breached = input_.trailing_armed_at is not None and crossed(input_.side, input_.reference_price, input_.protection_price)
    if count >= 2 and breached:
        return replace(result, phase=ExitPhase.REVERSAL_CONFIRMED, action=ExitAction.FULL_CLOSE,
                       target_remaining_contracts=0, reason=CONFIRMED_REASON)
    if target:
        return replace(result, phase=ExitPhase.PROFIT_AT_RISK, action=ExitAction.ARM_TRAILING,
                       target_remaining_contracts=None, reason='continuation-awaiting-closed-bars')
    return result


def reentry_gate(candidate, candle, book, previous):
    """A recent complete protective exit constrains only the same contract/side."""
    if previous is None or previous['side'] != candidate.side.value:
        return None
    reason = previous.get('reason')
    if reason is not None and reason not in PROTECTION_REASONS:
        return None
    try:
        if reason is None:
            raise ValueError('unknown previous exit reason')
        protection = D(str(previous['protection']))
        high, low = D(str(previous['bar_high'])), D(str(previous['bar_low']))
        bar_time = int(previous['bar_time'])
        if not all(v.is_finite() and v > 0 for v in (protection, high, low)) or low > high:
            raise ValueError('invalid exit boundary')
    except (KeyError, TypeError, ValueError, ArithmeticError):
        return GateResult(False, 'REENTRY_EVIDENCE_MISSING', 'previous protective exit lacks verified price/bar facts')
    long = candidate.side is TrendSide.LONG
    boundary = max(protection, high) if long else min(protection, low)
    # Conservative executable-side check: both sides have recovered beyond the boundary.
    quote = book.bid if long else book.ask
    recovered = candle.close > boundary and quote > boundary if long else candle.close < boundary and quote < boundary
    if candle.open_time <= bar_time or candidate.bar_time <= bar_time or not recovered:
        return GateResult(False, 'REENTRY_NEW_PRICE_REQUIRED', f'new closed bar and price recovery required; boundary={boundary}')
    return None


def latest_completed(ledger, account, contract, store=None):
    """Use full exits only; realized_pnl already includes exit fees, not entry fees.

    Operates on the PAPER file-ledger snapshot (also used by offline replay).
    Recovery reads the explicitly linked CAS/window only; never scans the disk.
    """
    rows = [p for p in ledger.data['positions'].values() if p['account_id'] == account
            and p['contract_code'] == contract and p['status'] == 'CLOSED' and p.get('closed_at')]
    if not rows:
        return None, None
    def at(p):
        value = p['closed_at']
        return value if isinstance(value, datetime) else datetime.fromisoformat(value)
    p = max(rows, key=at)
    context = ledger.position_context(p['position_id'])
    # Read actual opening fills, including partial fills, rather than estimate costs.
    opening_ids = {k for k, o in ledger.data['orders'].items() if o.get('strategy_run_id') == p['strategy_run_id']
                   and o.get('account_id') == account and not o.get('position_id')}
    opening_fills = [f for f in ledger.data['fills'] if f['order_id'] in opening_ids]
    fee = sum((D(str(f['fee'])) for f in opening_fills), D(0)) if opening_fills else None
    if fee is None and context.get('entry_fee') is not None:
        fee = D(str(context['entry_fee']))
    prior = PriorTrade(contract, int(at(p).timestamp()), D(str(p['realized_pnl'])) - fee) if fee is not None else None
    saved = context.get('complete_exit')
    if saved:
        return prior, saved
    records = [v for v in ledger.data['decisions'] if v.get('position_id') == p['position_id']
               and v['kind'] == 'EXIT_DECISION' and v['payload'].get('action') == 'FULL_CLOSE']
    complete_record = records[-1] if records else {}
    record = complete_record.get('payload', {})
    previous = dict(position_id=p['position_id'], side=p['side'], reason=record.get('reason'),
                    closed_at=at(p).isoformat(), evidence_status='MISSING')
    if store is not None and record.get('evidence_ref'):
        try:
            obj = store.get(record['evidence_ref'])
            payload = obj['payload']
            if (obj.get('kind') != 'compact_exit_decision' or payload.get('position_id') != p['position_id']
                    or payload.get('run_id') != complete_record.get('run_id')
                    or payload.get('decision', {}).get('action') != 'FULL_CLOSE'
                    or payload.get('decision', {}).get('reason') != record.get('reason')
                    or datetime.fromisoformat(payload['at']) != at(p)):
                raise ValueError('exit evidence does not match latest complete close')
            from .research.compact import load_window
            windows = []
            for ref in obj.get('refs', ()):
                if store.get(ref)['kind'] == 'compact_kline_window':
                    w = load_window(store, ref)
                    if w.period == '15min' and w.contract_code == contract and w.is_complete:
                        windows.append(w)
            if len(windows) != 1:
                raise ValueError('missing unique exit window')
            bar = windows[0].candles[-1]
            if bar.open_time + 900 > int(at(p).timestamp()):
                raise ValueError('exit evidence contains future candle')
            previous.update(protection=payload['decision']['protection_price'], bar_time=bar.open_time,
                            bar_high=str(bar.high), bar_low=str(bar.low), evidence_status='RECOVERED')
        except (OSError, ValueError, KeyError, TypeError):
            pass  # A same-direction candidate will fail closed, with a decision reason.
    # Persist only facts, not a speculative timestamp or guessed ticker/price.
    if previous.get('evidence_status') == 'RECOVERED':
        context['complete_exit'] = previous
        ledger.save_position_context(p['position_id'], context)
    return prior, previous
