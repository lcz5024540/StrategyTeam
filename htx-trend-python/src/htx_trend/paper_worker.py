"""Public-market PAPER worker. No credentials, private API or LIVE executor.

One atomically replaced JSON ledger per instance; flock excludes duplicate writers.
Reuse PaperExchange and PaperTrendRuntime without executing database migrations.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal
import fcntl
import json
import logging
import os
from pathlib import Path
import re
import uuid

from .execution import ExecutionConfig, ExecutionFacts
from .java_entry_contract import evaluate
from .ledger import Position
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
    def __init__(self, instance, market, state_root: Path, config: PaperWorkerConfig):
        if instance.execution_mode != 'PAPER' or not re.fullmatch(r'[A-Za-z0-9_-]+', instance.instance_key):
            raise ValueError('PAPER requires an independent safe instance key')
        self.instance, self.market, self.config = instance, market, config
        self.account = 'paper:' + instance.instance_key
        self.profile = profile_for_version(instance.strategy_name) or TimeframeProfile.INTRADAY_15M
        self.entry_config = (JavaEntryConfig.from_env() if instance.strategy_name == LEGACY_STRATEGY_VERSION
                             else profile_entry_config(self.profile))
        HtxStrategyDecisionEngine(strategy_version=instance.strategy_name).require("PAPER")
        self.ledger = PaperFileLedger(state_root / (instance.instance_key + '.json'),
            dict(instance=instance.instance_key, strategy=instance.strategy_name,
                 config=json.loads(json.dumps(asdict(config), default=str))))
        self.exchange = WorkerPaperExchange(self.ledger)
        self.runtime = PaperTrendRuntime(self.ledger, self.exchange, account_id=self.account,
            entry_config=self.entry_config, strategy_version=instance.strategy_name,
            execution_config=replace(ExecutionConfig.from_env(), account_equity_usdt=config.equity,
                max_total_margin_rate=config.margin_rate, max_active_positions=config.max_active_positions,
                max_same_direction_positions=config.max_active_positions,
                hard_risk_limits_enabled=True))
        self.stopped = False

    def stop(self):
        if not self.stopped:
            self.ledger.close()
            self.stopped = True

    def tick(self):
        if self.stopped:
            return WorkerHealth('STOPPED', 'paper worker stopped')
        now = datetime.now(UTC)
        count = 0
        # All state transitions occur serially; rollback the full cycle on failure.
        before = json.dumps(self.ledger.data, default=str)
        try:
            for contract in self.config.contracts:
                periods = self.profile.contract
                entry = self.market.kline_window(contract, periods.entry_period, 160, now_epoch=int(now.timestamp()))
                trend = self.market.kline_window(contract, periods.trend_period, 160, now_epoch=int(now.timestamp()))
                if not entry.is_complete or not trend.is_complete or entry.evidence_status == 'FAILED' or trend.evidence_status == 'FAILED':
                    raise ValueError('PAPER market evidence unavailable')
                bar = entry.candles[-1]
                from .market_data import period_seconds
                if int(now.timestamp()) - bar.open_time > 2 * period_seconds(periods.entry_period):
                    raise ValueError('PAPER stale market data')
                if self.ledger.data['bars'].get(contract) == bar.open_time:
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
                        market_context_opposes=False, at=now)
                positions = self.ledger.active_positions(self.account)
                used = sum((p.entry_price * p.quantity * Decimal(self.ledger.position_context(p.position_id)['contract_size'])
                            / self.config.leverage for p in positions), Decimal('0'))
                pnl = sum((Decimal(str(p['realized_pnl'])) for p in self.ledger.data['positions'].values()), Decimal('0'))
                fees = sum((Decimal(str(f['fee'])) for f in self.ledger.data['fills']), Decimal('0'))
                exit_fees = sum((Decimal(str(f['fee'])) for f in self.ledger.data['fills']
                    if self.ledger.data['orders'][f['order_id']]['position_id'] is not None), Decimal('0'))
                equity = self.config.equity + pnl + exit_fees - fees
                if not had_position:
                    outcome = self.runtime.scan(run_id=run_id, entry_window=entry, trend_window=trend,
                        now_epoch=int(now.timestamp()), at=now, book=book,
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
                self.ledger.data['bars'][contract] = bar.open_time
            self.ledger.checkpoint()
        except BaseException:
            self.ledger.data = json.loads(before)
            raise
        return WorkerHealth('HEALTHY', 'PAPER public market / simulated fills', count,
                            len(self.ledger.active_positions(self.account)))
