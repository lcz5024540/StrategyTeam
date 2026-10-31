"""Public-market PAPER worker. No credentials, private API or LIVE executor.

One atomically replaced JSON ledger per instance; flock excludes duplicate writers.
Reuse PaperExchange and PaperTrendRuntime without executing database migrations.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal
import copy
import fcntl
import json
import logging
import os
from pathlib import Path
import re
import uuid

from .execution import ExecutionConfig, ExecutionFacts, GateResult
from .continuation import LINKED, ContinuationConfig, latest_completed
from .java_entry_contract import evaluate
from .ledger import Position
from .maker_execution import MakerExecutionConfig
from .market_data import Candle
from .paper import PaperExchange
from .runtime import PaperTrendRuntime
from .strategy_decision import HtxStrategyDecisionEngine, LEGACY_STRATEGY_VERSION
from .java_entry_contract import JavaEntryConfig
from .strategy_platform import WorkerHealth
from .strategy_profiles import profile_for_version, profile_entry_config
from .timeframes import TimeframeProfile


@dataclass(frozen=True)
class PaperWorkerConfig:
    contracts: tuple[str, ...] = ('BTC-USDT', 'ETH-USDT')
    equity: Decimal = Decimal('300')
    margin_rate: Decimal = Decimal('.10')
    max_active_positions: int = 5
    leverage: Decimal = Decimal('10')

    @classmethod
    def from_env(cls):
        value = cls(tuple(dict.fromkeys(x.strip().upper() for x in os.getenv(
            'HTX_PAPER_CONTRACTS', 'BTC-USDT,ETH-USDT').split(',') if x.strip())),
            Decimal(os.getenv('HTX_PAPER_EQUITY_USDT', '300')),
            Decimal(os.getenv('HTX_PAPER_MAX_MARGIN_RATE', '.10')),
            int(os.getenv('HTX_PAPER_MAX_ACTIVE_POSITIONS', '5')),
            Decimal(os.getenv('HTX_PAPER_LEVERAGE', '10')))
        if (not value.contracts or any(not re.fullmatch(r'[A-Z0-9]+-USDT', c) for c in value.contracts)
                or not value.equity.is_finite() or value.equity != 300
                or not value.margin_rate.is_finite() or not 0 < value.margin_rate <= Decimal('.10')
                or not 1 <= value.max_active_positions <= 5
                or not value.leverage.is_finite() or not 0 < value.leverage <= 10):
            raise ValueError('PAPER requires 300U, margin <=10%, capacity <=5 and leverage <=10')
        return value


class PaperFileLedger:
    """Paper ledger protocol with atomic per-cycle snapshots and exclusive ownership."""
    def __init__(self, path: Path, identity: dict, *, mirror=None):
        from .paper_db_persist import mirror_checkpoint
        self.mirror = mirror if mirror is not None else mirror_checkpoint
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.lock = path.with_suffix('.lock').open('a')
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.data = json.loads(path.read_text()) if path.exists() else {
                'identity': identity, 'positions': {}, 'contexts': {}, 'orders': {},
                'fills': [], 'decisions': [], 'bars': {}}
            if self.data['identity'] != identity:
                raise ValueError('PAPER state identity/config mismatch; use a new instance')
        except BaseException:
            self.lock.close()
            raise

    def checkpoint(self):
        raw = json.dumps(self.data, default=str, sort_keys=True)
        temporary = self.path.with_suffix('.tmp')
        with temporary.open('w') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.path)
        try:
            self.mirror(json.loads(raw))
        except Exception:
            logging.getLogger(__name__).exception("PAPER DB mirror failed; JSON remains authoritative; retry next checkpoint")

    def close(self):
        self.lock.close()

    def record_order(self, order):
        self.data['orders'][order.order_id] = asdict(order)

    def record_fill(self, fill, *, order_status='FILLED'):
        self.data['fills'].append(asdict(fill))
        self.update_order_status(fill.order_id, order_status, fill.filled_at)

    def update_order_status(self, order_id, status, when=None):
        self.data['orders'][order_id].update(status=status, filled_at=when)

    def open_position(self, position):
        self.data['positions'][position.position_id] = asdict(position)

    def position(self, position_id):
        value = dict(self.data['positions'][position_id])
        for key in ('entry_price', 'realized_pnl'):
            value[key] = Decimal(str(value[key]))
        for key in ('opened_at', 'closed_at'):
            if isinstance(value[key], str):
                value[key] = datetime.fromisoformat(value[key])
        return Position(**value)

    def active_positions(self, account_id):
        return [self.position(key) for key, p in self.data['positions'].items()
                if p['account_id'] == account_id and p['status'] == 'OPEN']

    def position_context(self, position_id):
        return dict(self.data['contexts'][position_id])

    def save_position_context(self, position_id, context):
        self.data['contexts'][position_id] = context

    def reduce_position(self, position_id, remaining_quantity, realized_pnl, when):
        value = self.data['positions'][position_id]
        value.update(quantity=remaining_quantity, realized_pnl=realized_pnl)
        if remaining_quantity == 0:
            value.update(status='CLOSED', closed_at=when)

    def mirrored_positions(self, parent_position_id):
        return []

    def decision(self, account_id, run_id, decision_kind, payload, *, position_id=None):
        self.data['decisions'].append(dict(account_id=account_id, run_id=run_id,
            kind=decision_kind, payload=payload, position_id=position_id))


class WorkerPaperExchange(PaperExchange):
    """Contract-denominated fees; unmatched limits expire rather than reserve forever."""
    def __init__(self, ledger):
        super().__init__(ledger)
        self.contract_size = Decimal('1')

    def submit(self, request, candle):
        self._fee_rate = Decimal('.0004') * self.contract_size
        if getattr(self, "book", None) is not None and request.order_type == "MARKET":
            request = replace(request, mid=self.book.mid)
            price = self.book.ask if request.side == "BUY" else self.book.bid
            candle = Candle(candle.open_time, price, price, price, price)
        order = super().submit(request, candle)
        if order.status == "FILLED":
            order = replace(order, limit_price=request.limit_price or candle.close)
        if order.status == 'OPEN':
            self._ledger.update_order_status(order.order_id, 'EXPIRED')
            return replace(order, status='EXPIRED')
        return order


class PaperTrendWorker:
    def __init__(self, instance, market, state_root: Path, config: PaperWorkerConfig, evidence_store=None,
                 continuation_config: ContinuationConfig | None = None):
        if instance.execution_mode != 'PAPER' or not re.fullmatch(r'[A-Za-z0-9_-]+', instance.instance_key):
            raise ValueError('PAPER requires an independent safe instance key')
        self.instance, self.market, self.config = instance, market, config
        self.account = 'paper:' + instance.instance_key
        self.profile = profile_for_version(instance.strategy_name) or TimeframeProfile.INTRADAY_15M
        self.continuation = continuation_config or ContinuationConfig.from_paper_env()
        self.continuation.require_15m(self.profile is TimeframeProfile.INTRADAY_15M and instance.strategy_name == 'HTX_TREND_15M_V2', live=False)
        self.entry_config = (JavaEntryConfig.from_env() if instance.strategy_name == LEGACY_STRATEGY_VERSION
                             else profile_entry_config(self.profile))
        HtxStrategyDecisionEngine(strategy_version=instance.strategy_name).require("PAPER")
        self.ledger = PaperFileLedger(state_root / (instance.instance_key + '.json'),
            dict(instance=instance.instance_key, strategy=instance.strategy_name,
                 config=json.loads(json.dumps(asdict(config), default=str))))
        self.exchange = WorkerPaperExchange(self.ledger)
        self.runtime = PaperTrendRuntime(
            self.ledger, self.exchange, account_id=self.account,
            entry_config=self.entry_config, strategy_version=instance.strategy_name,
            execution_config=replace(ExecutionConfig.from_env(), account_equity_usdt=config.equity,
                max_total_margin_rate=config.margin_rate, max_active_positions=config.max_active_positions,
                max_same_direction_positions=config.max_active_positions,
                hard_risk_limits_enabled=True),
            maker_config=MakerExecutionConfig.from_env(), evidence_store=evidence_store,
            compact=getattr(market, 'compact', None),
            evidence_context={'instance': instance.instance_key, 'timeframe_profile': self.profile.value},
            continuation_config=self.continuation)
        self.runtime._paper_worker_config = asdict(config)
        self.stopped = False
        self._checkpoint_failed = False
        self._failures = {}

    def _failure(self, contract, reason):
        old = self._failures.get(contract)
        if old is None or old['reason'] != reason:
            logging.getLogger(__name__).warning('PAPER degraded %s: %s', contract, reason)
            old = {'reason': reason, 'count': 0, 'first_seen': datetime.now(UTC).isoformat()}
        old.update(count=old['count'] + 1, last_seen=datetime.now(UTC).isoformat())
        self._failures[contract] = old

    def _recovered(self, contract):
        old = self._failures.pop(contract, None)
        if old:
            logging.getLogger(__name__).info('PAPER recovered %s after %s failures', contract, old['count'])

    def _confirm_evidence(self):
        if self.runtime._compact is None or not self.ledger.path.exists():
            return True
        try:
            self.runtime._compact.confirm_ledger(self.instance.instance_key, self.ledger.path)
            self._recovered('LEDGER_CONFIRMATION')
            return True
        except Exception as error:
            self._failure('LEDGER_CONFIRMATION', type(error).__name__)
            return False

    def _record_run_evidence(self, run_id, contract, before, at):
        """Small execution result linked to the durable input decision, never a full ledger copy."""
        if self.runtime._compact is None:
            return True
        decisions = [d for d in self.ledger.data['decisions'] if d['run_id'] == run_id]
        refs = tuple(d['payload']['evidence_ref'] for d in decisions if d['payload'].get('evidence_ref'))
        ids = {d['position_id'] for d in decisions if d.get('position_id')}
        ids.update(k for k, value in self.ledger.data['positions'].items()
                   if before['positions'].get(k) != value or before['contexts'].get(k) != self.ledger.data['contexts'].get(k))
        orders = {k: v for k, v in self.ledger.data['orders'].items() if v.get('strategy_run_id') == run_id}
        complete = all(d['payload'].get('evidence_status') != 'FAILED' for d in decisions)
        try:
            ref = self.runtime._evidence_store.put('compact_execution_result', {
                'instance': self.instance.instance_key, 'strategy': self.instance.strategy_name,
                'timeframe_profile': self.profile.value, 'contract': contract, 'run_id': run_id, 'at': at,
                'simulated': True, 'commit_status': 'PROVISIONAL', 'orders': orders,
                'fills': [f for f in self.ledger.data['fills'] if f['order_id'] in orders],
                'positions': {k: self.ledger.data['positions'][k] for k in ids},
                'contexts': {k: self.ledger.data['contexts'].get(k) for k in ids},
                'input_evidence_status': 'COMPLETE' if complete else 'INCOMPLETE',
                'funding_paid': None, 'funding_paid_status': 'UNKNOWN'}, refs=refs)
            payload = {'evidence_ref': ref, 'evidence_status': 'COMPLETE' if complete else 'INCOMPLETE'}
        except Exception as error:
            payload = {'evidence_status': 'FAILED', 'evidence_error': type(error).__name__}
            complete = False
        self.ledger.decision(self.account, run_id, 'EVIDENCE_RESULT', payload)
        return complete

    def stop(self):
        if not self.stopped:
            self.ledger.close()
            self.stopped = True

    def tick(self):
        if self.stopped:
            return WorkerHealth('STOPPED', 'paper worker stopped')
        if self._checkpoint_failed:
            # Do not produce more simulated executions while the authoritative
            # snapshot is uncommitted. Retrying publication itself is idempotent.
            self.ledger.checkpoint()
            self._checkpoint_failed = False
            self._confirm_evidence()
            return WorkerHealth('DEGRADED', 'pending PAPER ledger persisted; resume next cycle')
        now = datetime.now(UTC)
        count = 0
        confirmation_ok = self._confirm_evidence()
        skipped: list[str] = [] if confirmation_ok else ['LEDGER_CONFIRMATION_FAILED']
        log = logging.getLogger(__name__)
        for contract in self.config.contracts:
            # Per-contract snapshot lets one bad contract fail without undoing
            # progress made for healthy peers in the same tick.
            contract_before = copy.deepcopy(self.ledger.data)
            self.runtime.evidence_error = None
            try:
                periods = self.profile.contract
                entry = self.market.kline_window(contract, periods.entry_period, 160, now_epoch=int(now.timestamp()))
                trend = self.market.kline_window(contract, periods.trend_period, 160, now_epoch=int(now.timestamp()))
                if not entry.is_complete or not trend.is_complete:
                    raise ValueError('PAPER market data incomplete')
                evidence_failed = not confirmation_ok or entry.evidence_status == 'FAILED' or trend.evidence_status == 'FAILED'
                if evidence_failed:
                    skipped.append(f'{contract}:MARKET_EVIDENCE_FAILED')
                    self._failure(contract, 'MARKET_EVIDENCE_FAILED')
                bar = entry.candles[-1]
                from .market_data import period_seconds
                if int(now.timestamp()) - bar.open_time > 2 * period_seconds(periods.entry_period):
                    raise ValueError('PAPER stale market data')
                if self.ledger.data['bars'].get(contract) == bar.open_time:
                    if not evidence_failed:
                        self._recovered(contract)
                    continue
                spec = self.market.contract_spec(contract)
                self.exchange.contract_size = spec.contract_size
                run_id = 'paper-' + str(uuid.uuid4())
                book = self.market.top_of_book(contract)
                self.exchange.book = book
                signal = evaluate(entry.candles, self.entry_config)
                had_position = any(p.contract_code == contract for p in self.ledger.active_positions(self.account))
                for position in self.ledger.active_positions(self.account):
                    if position.contract_code != contract:
                        continue
                    if signal.indicators is None:
                        raise ValueError('PAPER exit indicators unavailable')
                    context = self.ledger.position_context(position.position_id)
                    self.runtime.manage(run_id=run_id, position_id=position.position_id,
                        reference_price=(book.bid if position.side == "LONG" else book.ask), highest_price=max(Decimal(context['highest']), bar.high),
                        lowest_price=min(Decimal(context['lowest']), bar.low), atr=signal.indicators.atr,
                        signal_side=signal.side, ema_fast=signal.indicators.ema_fast,
                        ema_slow=signal.indicators.ema_slow, major_timeframe_opposition=0,
                        market_context_opposes=False, at=now, evidence_windows=(entry, trend), evaluation_candle=bar,
                        market_snapshot={'book': book, 'contract': spec, 'at': now,
                                         'funding_quote_status': 'NOT_REQUESTED_ON_MANAGEMENT_PATH'})
                    if self.runtime.evidence_error:
                        skipped.append(f'{contract}:EXIT_EVIDENCE_FAILED')
                        self._failure(contract, 'EXIT_EVIDENCE_FAILED')
                    # Protect a completed management action from any subsequent
                    # entry/evidence error. The bar marker prevents double reduction.
                    self.ledger.data['bars'][contract] = bar.open_time
                    contract_before = copy.deepcopy(self.ledger.data)
                positions = self.ledger.active_positions(self.account)
                used = sum((p.entry_price * p.quantity * Decimal(self.ledger.position_context(p.position_id)['contract_size'])
                            / self.config.leverage for p in positions), Decimal('0'))
                pnl = sum((Decimal(str(p['realized_pnl'])) for p in self.ledger.data['positions'].values()), Decimal('0'))
                fees = sum((Decimal(str(f['fee'])) for f in self.ledger.data['fills']), Decimal('0'))
                exit_fees = sum((Decimal(str(f['fee'])) for f in self.ledger.data['fills']
                    if self.ledger.data['orders'][f['order_id']]['position_id'] is not None), Decimal('0'))
                equity = self.config.equity + pnl + exit_fees - fees
                if not had_position:
                    if evidence_failed:
                        continue  # no order and no bar advance; retry after recovery
                    policy = getattr(self, 'continuation', ContinuationConfig())
                    prior, previous_exit = (latest_completed(self.ledger, self.account, contract, self.runtime._evidence_store)
                        if policy.cooldown or policy.version == LINKED else (None, None))
                    entry_block = (GateResult(False, 'COOLDOWN_COST_UNKNOWN', 'complete-trade opening cost unknown')
                        if policy.cooldown and previous_exit is not None and prior is None else None)
                    outcome = self.runtime.scan(run_id=run_id, entry_window=entry, trend_window=trend,
                        now_epoch=int(now.timestamp()), at=now, book=book,
                        prior_trade=prior if policy.cooldown else None, previous_exit=previous_exit, entry_block=entry_block,
                        execution_candle=Candle(int(now.timestamp()), book.bid, book.ask, book.bid, book.ask),
                        claimed_structure_keys=frozenset(c["structure_key"] for c in self.ledger.data["contexts"].values() if c.get("structure_key")),
                        contract=spec, funding=self.market.funding_rate(contract),
                        execution_facts=ExecutionFacts(active_position_count=len(positions),
                            same_direction_position_count=len(positions),
                            max_active_positions=self.config.max_active_positions, account_equity_usdt=equity,
                            used_margin_usdt=used, reserved_margin_usdt=Decimal('0'),
                            available_margin_usdt=max(Decimal('0'), equity-used), leverage=self.config.leverage,
                            reserved_risk_usdt=sum((abs(p.entry_price-Decimal(self.ledger.position_context(p.position_id)['initial_stop']))
                                * p.quantity * Decimal(self.ledger.position_context(p.position_id)['contract_size']) for p in positions), Decimal('0'))))
                    count += len(outcome.candidates)
                if not self._record_run_evidence(run_id, contract, contract_before, now):
                    skipped.append(f'{contract}:EVIDENCE_RESULT_FAILED')
                    self._failure(contract, 'EVIDENCE_RESULT_FAILED')
                elif not evidence_failed and not self.runtime.evidence_error:
                    self._recovered(contract)
                self.ledger.data['bars'][contract] = bar.open_time
            except Exception as error:
                self.ledger.data = contract_before
                skipped.append(f"{contract}:{type(error).__name__}")
                self._failure(contract, type(error).__name__ + ':' + str(error))
        try:
            self.ledger.checkpoint()
        except Exception:
            self._checkpoint_failed = True
            raise
        if not self._confirm_evidence():
            skipped.append('LEDGER_CONFIRMATION_FAILED')
        detail = 'PAPER public market / simulated fills'
        if skipped:
            detail += '; skipped=' + ','.join(skipped)
        return WorkerHealth('DEGRADED' if skipped else 'HEALTHY', detail, count,
                            len(self.ledger.active_positions(self.account)))
