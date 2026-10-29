"""Offline replay of the unchanged PAPER worker, scan and management call chain.

The scoped patches only inject time/environment and observe pure gate calls.
No production constructor, file ledger, exchange client or database is opened.
This process-local harness is intentionally not thread safe.
"""
from __future__ import annotations

import argparse
import bisect
import csv
import hashlib
import json
import inspect
import re
import tomllib
import os
from dataclasses import asdict, dataclass, replace
from datetime import UTC, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from . import runtime as runtime_module
from . import paper_worker as worker_module
from .execution import ExecutionConfig
from .java_entry_contract import JavaEntryConfig
from .market_data import Candle, ContractSpec, FundingRate, KlineWindow, TopOfBook, period_seconds
from .paper_worker import PaperFileLedger, PaperTrendWorker, PaperWorkerConfig, WorkerPaperExchange
from .runtime import PaperTrendRuntime
from .strategy_decision import HtxStrategyDecisionEngine
from .timeframes import TimeframeProfile

D = Decimal
CST = timezone(timedelta(hours=8))
STRATEGY = 'HTX_TREND_15M_V2'
LOCKED_HASHES = {
    'klines.csv': 'c9317a85fcca1b9fce1a88409f2356dd668cc22688a3d173797f1712a8aef923',
    'trades_closed_window.csv': '845643abcd4e8d9e153d823ae956f23f7c213684b906902dbd62641662337359',
}
BOOK_COLUMNS = ('ts_ms', 'ts_cst', 'contract', 'bid', 'ask', 'mid', 'last', 'source')
BOOK_KINDS = frozenset({'book', 'tick', 'mid'})
BOOK_PROVENANCE = frozenset({'synthetic', 'evidence'})
BOOK_CONTRACTS = frozenset({'BTC-USDT', 'ETH-USDT'})


def epoch(value: str) -> int:
    return int(datetime.fromisoformat(value).replace(tzinfo=CST).timestamp())


def cst(value: int) -> str:
    return datetime.fromtimestamp(value, CST).isoformat()


def plain(value):
    return json.loads(json.dumps(value, default=str, sort_keys=True))


@dataclass(frozen=True)
class Assumptions:
    scan_delay_seconds: int = 30
    spread_rate: Decimal = D('0')
    funding_rate: Decimal = D('0')
    btc_contract_size: Decimal = D('.001')
    eth_contract_size: Decimal = D('.01')
    price_tick: Decimal = D('.01')
    equity: Decimal = D('300')
    leverage: Decimal = D('10')
    margin_rate: Decimal = D('.10')
    max_active_positions: int = 5
    start_cst: str = '2026-10-19 21:35:46'
    end_cst: str = '2026-10-21 10:30:59'

    def validate(self):
        if not 0 <= self.scan_delay_seconds < 900:
            raise ValueError('scan delay must be in [0,900)')
        for name in ('spread_rate', 'funding_rate', 'btc_contract_size', 'eth_contract_size',
                     'price_tick', 'equity', 'leverage', 'margin_rate'):
            if not getattr(self, name).is_finite():
                raise ValueError(f'{name} must be finite')
        if not 0 <= self.spread_rate < 1 or min(self.btc_contract_size, self.eth_contract_size,
                self.price_tick, self.equity, self.leverage, self.margin_rate) <= 0:
            raise ValueError('invalid book/account/contract assumption')
        if self.max_active_positions < 1 or epoch(self.start_cst) >= epoch(self.end_cst):
            raise ValueError('invalid replay range/capacity')


@dataclass(frozen=True)
class BookQuote:
    """一条已锁定的管理时刻报价。bid/ask 直接进入 TopOfBook，不再二次造价。"""
    ts_ms: int
    contract: str
    bid: Decimal
    ask: Decimal


@dataclass(frozen=True)
class OptionalBook:
    file: str
    sha256: str
    kind: str
    provenance: str
    quotes: dict

    @property
    def row_count(self) -> int:
        return sum(len(rows) for rows in self.quotes.values())


class MemoryLedger(PaperFileLedger):
    """Reuse ledger transitions, deliberately bypass file/DB initialization."""
    def __init__(self):
        self.data = {'positions': {}, 'contexts': {}, 'orders': {}, 'fills': [], 'decisions': [], 'bars': {}}

    def checkpoint(self):
        pass

    def close(self):
        pass


class OfflineMarket:
    def __init__(self, candles, assumptions, book: OptionalBook | None = None):
        self.candles, self.assumptions, self.book = candles, assumptions, book
        self.now = 0

    def kline_window(self, contract, period, size, *, now_epoch):
        # Same 10s completed-bar grace as HtxMarketData; never expose a forming bar.
        rows = tuple(c for c in self.candles[(contract, period)]
                     if c.open_time + period_seconds(period) <= now_epoch - 10)[-size:]
        return KlineWindow(contract, period, rows, size)

    def top_of_book(self, contract):
        # 有夹具就只用锁定报价。无夹具才用形成中 bar 的 open，禁止用 high/low/close。
        if self.book is not None:
            return self._locked_book(contract)
        forming = next((c for c in self.candles[(contract, '15min')]
                        if c.open_time <= self.now < c.open_time + 900), None)
        mid = (forming.open if forming is not None else
               self.kline_window(contract, '15min', 1, now_epoch=self.now).candles[-1].close)
        half = mid * self.assumptions.spread_rate / 2
        return TopOfBook(mid - half, mid + half)

    def _locked_book(self, contract):
        # 不把 spread_rate 再铺到真实 bid/ask 上，否则等于夹具之外另造半边盘口。
        rows = self.book.quotes.get(contract) or ()
        if not rows:
            raise ValueError(f'{contract}: book fixture has no quotes; refusing to invent a book')
        cutoff = self.now * 1000 + 999
        index = bisect.bisect_right(rows, cutoff, key=lambda quote: quote.ts_ms)
        if index == 0:
            raise ValueError(
                f'{contract}: no book quote at or before management time; refusing to invent a book')
        quote = rows[index - 1]
        return TopOfBook(quote.bid, quote.ask)

    def funding_rate(self, contract):
        return FundingRate(contract, self.assumptions.funding_rate)

    def contract_spec(self, contract):
        size = self.assumptions.btc_contract_size if contract == 'BTC-USDT' else self.assumptions.eth_contract_size
        return ContractSpec(contract, size, self.assumptions.price_tick, 1)


def load_inputs(inputs: Path):
    manifest = json.loads((inputs / 'MANIFEST.json').read_text())
    hashes = {name: hashlib.sha256((inputs / name).read_bytes()).hexdigest() for name in LOCKED_HASHES}
    if hashes != LOCKED_HASHES or manifest.get('sha256') != LOCKED_HASHES:
        raise ValueError('input SHA256 mismatch: refusing replay (including rewritten manifest)')
    candles = {}
    with (inputs / 'klines.csv').open(newline='') as stream:
        for row in csv.DictReader(stream):
            key = row['contract_code'], row['period']
            candle = Candle(int(row['bar_time']), *(D(row[k]) for k in ('open', 'high', 'low', 'close')),
                            amount=D(row['amount']) if row['amount'] else None,
                            volume=D(row['volume']) if row['volume'] else None)
            candles.setdefault(key, []).append(candle)
    for key, values in candles.items():
        values.sort(key=lambda c: c.open_time)
        if len({c.open_time for c in values}) != len(values):
            raise ValueError(f'duplicate candle: {key}')
    with (inputs / 'trades_closed_window.csv').open(newline='') as stream:
        live = list(csv.DictReader(stream))
    return candles, live, hashes


def _positive_decimal(text, field):
    raw = (text or '').strip()
    if not raw:
        return None
    value = D(raw)
    if not value.is_finite() or value <= 0:
        raise ValueError(f'{field} must be positive')
    return value


def _row_ts_ms(row):
    ms_text = (row.get('ts_ms') or '').strip()
    cst_text = (row.get('ts_cst') or '').strip()
    if not ms_text and not cst_text:
        raise ValueError('book row needs ts_ms or ts_cst')
    parsed = int(ms_text) if ms_text else None
    if parsed is not None and parsed < 0:
        raise ValueError('ts_ms must be >= 0')
    if cst_text:
        cst_ms = epoch(cst_text) * 1000
        if parsed is None:
            parsed = cst_ms
        elif parsed // 1000 != cst_ms // 1000:
            raise ValueError('ts_ms and ts_cst disagree')
    return parsed


def _locked_prices(kind, bid, ask, mid, last):
    """只接受行内已经写明的价格。缺边时不拿 K 线补。"""
    if kind == 'book':
        if bid is None or ask is None:
            raise ValueError('book kind requires bid and ask')
        if bid > ask:
            raise ValueError('bid must be <= ask')
        if mid is not None and mid != (bid + ask) / 2:
            raise ValueError('mid must equal (bid+ask)/2')
        return bid, ask
    if kind == 'mid':
        if mid is None:
            raise ValueError('mid kind requires mid')
        if bid is None and ask is None:
            return mid, mid
        if bid is None or ask is None or bid > ask or mid != (bid + ask) / 2:
            raise ValueError('mid must equal (bid+ask)/2')
        return bid, ask
    if bid is not None or ask is not None:
        if bid is None or ask is None or bid > ask:
            raise ValueError('tick row must include both bid and ask, or neither')
        if mid is not None and mid != (bid + ask) / 2:
            raise ValueError('mid must equal (bid+ask)/2')
        return bid, ask
    if last is None and mid is None:
        raise ValueError('tick kind requires last or bid/ask')
    if last is not None and mid is not None and last != mid:
        raise ValueError('tick last and mid disagree')
    price = last if last is not None else mid
    return price, price


def _parse_book_csv(path: Path, *, kind, provenance):
    quotes = {contract: [] for contract in BOOK_CONTRACTS}
    seen = set()
    with path.open(newline='', encoding='utf-8') as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None or set(BOOK_COLUMNS) - set(reader.fieldnames):
            raise ValueError('book csv missing columns ' + ','.join(BOOK_COLUMNS))
        for row in reader:
            contract = (row.get('contract') or '').strip()
            if contract not in BOOK_CONTRACTS:
                raise ValueError(f'unsupported book contract: {contract}')
            if provenance == 'synthetic' and 'SYNTHETIC' not in (row.get('source') or ''):
                raise ValueError('synthetic book row must label source with SYNTHETIC')
            ts_ms = _row_ts_ms(row)
            if (contract, ts_ms) in seen:
                raise ValueError(f'duplicate book quote: {contract} {ts_ms}')
            seen.add((contract, ts_ms))
            bid = _positive_decimal(row.get('bid'), 'bid')
            ask = _positive_decimal(row.get('ask'), 'ask')
            mid = _positive_decimal(row.get('mid'), 'mid')
            last = _positive_decimal(row.get('last'), 'last')
            locked_bid, locked_ask = _locked_prices(kind, bid, ask, mid, last)
            quotes[contract].append(BookQuote(ts_ms, contract, locked_bid, locked_ask))
    if not seen:
        raise ValueError('optional book is empty; refusing to invent a book')
    return {contract: tuple(sorted(rows, key=lambda quote: quote.ts_ms))
            for contract, rows in quotes.items() if rows}


def load_optional_book(inputs: Path):
    """MANIFEST.optional_book 为空则返回 None。声明了就必须哈希命中，不得改读旁路文件。"""
    manifest = json.loads((inputs / 'MANIFEST.json').read_text())
    spec = manifest.get('optional_book')
    if spec is None:
        return None
    if not isinstance(spec, dict):
        raise ValueError('optional_book must be an object or null')
    file_name, kind, provenance = spec.get('file'), spec.get('kind'), spec.get('provenance')
    expected = spec.get('sha256')
    if kind not in BOOK_KINDS or provenance not in BOOK_PROVENANCE:
        raise ValueError('invalid optional book kind or provenance')
    if not isinstance(file_name, str) or not isinstance(expected, str):
        raise ValueError('optional book file/sha256 required')
    relative = Path(file_name)
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('optional book path escapes inputs')
    path = (inputs / relative).resolve()
    if not path.is_relative_to(inputs.resolve()) or not path.is_file():
        raise ValueError('optional book file missing')
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected:
        raise ValueError('optional book SHA256 mismatch: refusing replay')
    quotes = _parse_book_csv(path, kind=kind, provenance=provenance)
    return OptionalBook(file_name, digest, kind, provenance, quotes)


def _fixture_notes(book: OptionalBook | None, full_matches: int, live_count: int):
    if book is None:
        gap = [
            '夹具状态：MANIFEST.optional_book 为空，未加载 book/tick/mid；不虚构盘口。管理时刻继续用形成中 15m bar 的 open 作 mid。',
            'schema：CSV 列 ts_ms,ts_cst,contract,bid,ask,mid,last,source。MANIFEST.optional_book 声明 file、sha256、kind(book|tick|mid)、provenance(synthetic|evidence)。'
            'kind=book 必须 bid 与 ask，mid 若填写则必须等于 (bid+ask)/2；kind=mid 必须 mid，bid/ask 可同时省略（此时 bid=ask=mid）；'
            'kind=tick 可用 last，或 bid 与 ask。取不晚于管理时刻所在秒的最近一条；未来报价不用；该合约尚无报价时拒绝，不回退 forming-open。',
            'BTC 36733c1f / 02:15 缺口窗（CST）：2026-10-21 02:00–09:30。参考腿 09:00:21 qty 1 profit-at-risk、09:15:54 qty 1 trend-weakened。需要入场及管理时刻 bid/ask/mid 或 tick。',
            'ETH f56d3d17 缺口窗（CST）：2026-10-20 19:00–20:00。参考腿 19:30:46 qty 5 trend-weakened-partial、19:45:32 qty 2 trend-weakened。',
            'ETH 93cef710 / 23:00：参考平仓 2026-10-21 13:45:33。现有 K 线重放止于 2026-10-21 10:30:59，15m 最后 open 12:00。无额外 K 线则维持 EXIT_OUTSIDE_KLINE_COVERAGE，不外推、不强平。盘口夹具不能单独补上这段 K 线。',
        ]
        bound = (f'本轮可达上界 {full_matches}/{live_count}：无 book/tick/mid 夹具，'
                 '窗内两笔 EXIT_LEGS_MISMATCH（BTC 36733c1f、ETH f56d3d17）与一笔 '
                 'EXIT_OUTSIDE_KLINE_COVERAGE（ETH 93cef710）不能靠虚构盘口或外推 K 线闭合。')
        status = {'loaded': False, 'mode': 'forming_bar_open', 'file': None, 'sha256': None,
                  'kind': None, 'provenance': None, 'row_count': 0}
        return status, gap, bound
    gap = [
        f'已加载 optional_book {book.file} kind={book.kind} provenance={book.provenance} '
        f'sha256={book.sha256} rows={book.row_count}。管理盘口取自夹具，不用 forming-open。',
        '夹具未覆盖的合约或时刻直接拒绝，不回退虚构盘口。',
    ]
    bound = f'本次重放已加载盘口夹具；完整逐腿 {full_matches}/{live_count}。'
    status = {'loaded': True, 'mode': book.kind, 'file': book.file, 'sha256': book.sha256,
              'kind': book.kind, 'provenance': book.provenance, 'row_count': book.row_count}
    return status, gap, bound


def compare(live, trades, bars, *, coverage_end=None):
    """One row per position; match entry bucket, then each ordered exit leg."""
    if len({r['position_key'] for r in live}) != len(live):
        raise ValueError('duplicate reference position: store exit legs within one position row')
    index = {(b['contract'], b['execution_bucket']): b for b in bars}
    used = set()
    alignment, missing = [], []
    for row in live:
        bucket = epoch(row['signal_bar_cst'])
        key = row['contract'], bucket
        found = next((i for i, t in enumerate(trades) if i not in used and
                      (t['contract'], t['execution_bucket']) == key), None)
        bar = index.get(key)
        reasons = []
        replay = None if found is None else trades[found]
        if replay is None:
            if bar is None:
                reasons.append('OUTSIDE_REPLAY_COVERAGE')
            elif bar['worker_gate'] != 'SCAN':
                reasons.append(bar['worker_gate'] + ': ' + str(bar.get('positions_at_tick_start', [])))
            elif not bar['candidates']:
                reasons.append('MODEL_ROUTING: ' + str(bar['diagnosis']))
            else:
                reasons.extend(g['stage'] + ': ' + g['reason'] for g in bar['gates'])
            missing.append({'live_position': row['position_key'], 'contract': key[0],
                            'execution_bucket': bucket, 'bar_evidence': bar, 'reasons': reasons})
        else:
            used.add(found)
            for field, expected in (('side', row['side']), ('model', row['model'])):
                if replay[field] != expected:
                    reasons.append(f'{field}: live={expected}; replay={replay[field]}')
            if replay['model'] != row['model'] or replay['side'] != row['side']:
                reasons.append('MODEL_ROUTING: ' + str(bar['route'] if bar else None))
        reference_legs = json.loads(row.get('exit_legs_json') or '[]')
        replay_legs = replay.get('exit_legs', []) if replay else []
        leg_alignment = []
        # One position consumes one entry, regardless of how many reduction orders it has.
        for i in range(max(len(reference_legs), len(replay_legs)) if reference_legs else 0):
            expected = reference_legs[i] if i < len(reference_legs) else None
            actual = replay_legs[i] if i < len(replay_legs) else None
            matched = expected is not None and actual is not None and (
                int(expected['quantity']) == int(actual['quantity']) and
                expected['reason'] == actual['reason'] and
                epoch(expected['at_cst']) // 900 == epoch(actual['at_cst']) // 900)
            leg_alignment.append({'live': expected, 'replay': actual, 'match': matched})
        outside = bool(reference_legs and replay is not None and not replay_legs
                       and coverage_end is not None
                       and max(epoch(leg['at_cst']) for leg in reference_legs)
                       > coverage_end[row['contract']])
        exit_match = bool(reference_legs) and all(x['match'] for x in leg_alignment)
        if not reference_legs:
            reasons.append('EXIT_REFERENCE_INCOMPLETE: only position aggregate available; per-leg orders unavailable')
        elif outside:
            reasons.append('EXIT_OUTSIDE_KLINE_COVERAGE: reference close exceeds replay/K-line coverage; no extrapolation or forced close')
        elif not exit_match:
            reasons.append('EXIT_LEGS_MISMATCH: see exit_leg_alignment for quantity/time/reason differences')
        alignment.append({'live': row, 'signal_bar_open_cst': cst(bucket - 900),
                          'execution_bucket_cst': cst(bucket), 'replay': replay,
                          'entry_match': replay is not None and replay['side'] == row['side'] and replay['model'] == row['model'],
                          'match': replay is not None and not reasons, 'reasons': reasons,
                          'exit_match': exit_match, 'exit_leg_alignment': leg_alignment,
                          'exit_outside_coverage': outside,
                          'bar_evidence': bar})
    # Reverse comparison counts positions, never exit legs.
    extras = [{**t, 'bar_evidence': index.get((t['contract'], t['execution_bucket']))}
              for i, t in enumerate(trades) if i not in used]
    return alignment, extras, missing


def replay(inputs: Path, assumptions: Assumptions = Assumptions()):
    assumptions.validate()
    candles, live, hashes = load_inputs(inputs)
    book = load_optional_book(inputs)
    ledger = MemoryLedger()
    market = OfflineMarket(candles, assumptions, book)
    # Construct only inert dependencies. Production __init__ opens a persistent ledger.
    worker = PaperTrendWorker.__new__(PaperTrendWorker)
    worker.config = PaperWorkerConfig(equity=assumptions.equity, margin_rate=assumptions.margin_rate,
        max_active_positions=assumptions.max_active_positions, leverage=assumptions.leverage)
    worker.account, worker.profile, worker.entry_config = 'offline', TimeframeProfile.INTRADAY_15M, JavaEntryConfig()
    worker.ledger, worker.market, worker.stopped = ledger, market, False
    worker._checkpoint_failed = False
    worker._failures = {}
    worker.exchange = WorkerPaperExchange(ledger)
    execution_config = replace(ExecutionConfig(), account_equity_usdt=worker.config.equity,
        max_total_margin_rate=worker.config.margin_rate, max_active_positions=worker.config.max_active_positions,
        max_same_direction_positions=worker.config.max_active_positions, hard_risk_limits_enabled=True)
    bars, trades, positions = [], [], {}
    gate_calls = []
    original_gate = runtime_module.evaluate_gate
    original_exit = runtime_module.decide
    condition_ids = list(dict.fromkeys(re.findall(r'emit\("([^"\n]+)"', inspect.getsource(original_gate))))
    exit_calls = []
    scan_contract = None
    scan_positions = []

    class ObservedRuntime(PaperTrendRuntime):
        def scan(self, **kwargs):
            nonlocal scan_contract, scan_positions
            scan_contract = kwargs['entry_window'].contract_code
            scan_positions = [dict(contract=p.contract_code, quantity=p.quantity,
                entry_price=p.entry_price, contract_size=ledger.position_context(p.position_id)['contract_size'])
                for p in ledger.active_positions(worker.account)]
            expected_used = sum((p['entry_price'] * p['quantity'] * D(p['contract_size'])
                                 / worker.config.leverage for p in scan_positions), D('0'))
            if kwargs['execution_facts'].used_margin_usdt != expected_used:
                raise ValueError('replay worker margin disagrees with active position ledger')
            return super().scan(**kwargs)

    def observed_exit(value):
        result = original_exit(value)
        exit_calls.append({'input': asdict(value), 'output': asdict(result)})
        return result

    def observed_gate(candidate, **kwargs):
        trace = []
        result = original_gate(candidate, **kwargs, condition_trace=lambda cid, status, actual, threshold, reason:
            trace.append(dict(condition=cid, status=status, actual=actual, threshold=threshold, reason=reason)))
        reached = {item['condition'] for item in trace}
        gate_calls.append({'positions_before_scan': scan_positions, 'contract': scan_contract, 'candidate': asdict(candidate), **asdict(result),
            'conditions': trace, 'not_evaluated_or_disabled': [cid for cid in condition_ids if cid not in reached],
            'execution_facts': asdict(kwargs['execution_facts']),
            'claimed_structure_keys': sorted(kwargs['claimed_structure_keys'])})
        return result

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime.fromtimestamp(market.now, tz or UTC)

    # Ignore ambient HTX knobs. No private settings or credentials are read.
    with patch.dict(os.environ, {}, clear=True), patch.object(worker_module, 'datetime', Clock), \
            patch.object(runtime_module, 'evaluate_gate', observed_gate), \
            patch.object(runtime_module, 'decide', observed_exit):
        worker.runtime = ObservedRuntime(ledger, worker.exchange, account_id=worker.account,
            entry_config=worker.entry_config, execution_config=execution_config, strategy_version=STRATEGY)
        engine = HtxStrategyDecisionEngine(strategy_version=STRATEGY, config=worker.entry_config)
        # Drive the clock, not the rows: gaps must fail worker completeness checks.
        first = ((epoch(assumptions.start_cst) - assumptions.scan_delay_seconds + 899) // 900) * 900
        times = range(first, epoch(assumptions.end_cst) - assumptions.scan_delay_seconds + 1, 900)
        for bucket in times:
            market.now = bucket + assumptions.scan_delay_seconds
            prior_bars = dict(ledger.data['bars'])
            before_fills = len(ledger.data['fills'])
            before_decisions = len(ledger.data['decisions'])
            prior_positions = [{'contract': p.contract_code, 'opened_at_cst': p.opened_at.astimezone(CST).isoformat(),
                                'side': p.side, 'model': ledger.position_context(p.position_id)['model']}
                               for p in ledger.active_positions(worker.account)]
            active_before = {p['contract'] for p in prior_positions}
            gate_calls.clear()
            exit_calls.clear()
            worker.tick()  # Actual production manage -> account facts -> scan -> routing -> order.
            decisions = ledger.data['decisions'][before_decisions:]
            for item, details in zip((d for d in decisions if d['kind'] == 'EXIT_DECISION'), exit_calls, strict=True):
                item['payload']['replay_observation'] = details
            for contract in worker.config.contracts:
                window = market.kline_window(contract, '15min', 160, now_epoch=market.now)
                diagnostic = engine.decide(contract_code=contract, period='15min', candles=window.candles, surface='PAPER')
                # Candidate trace ordered exactly as runtime evaluates its independent models.
                relevant = [g for g in gate_calls if g['contract'] == contract]
                scan = next((d['payload'] for d in decisions if d['kind'] == 'ENTRY_SCAN'
                             and d['payload']['decision_hash'] == diagnostic.decision_hash), None)
                bars.append({'contract': contract, 'execution_bucket': bucket, 'at_cst': cst(market.now),
                    'signal_bar_open_cst': cst(window.candles[-1].open_time), 'signal_age_seconds': market.now-window.candles[-1].open_time-900,
                    'entry_window_hash': window.window_hash,
                    'trend_window_hash': market.kline_window(contract, '60min', 160, now_epoch=market.now).window_hash,
                    'worker_gate': ('ALREADY_SCANNED_BAR' if prior_bars.get(contract) == window.candles[-1].open_time
                                    else 'HAD_POSITION_AT_TICK_START' if contract in active_before else 'SCAN'),
                    'candidate_diagnostics_only': scan is None,
                    'positions_at_tick_start': [p for p in prior_positions if p['contract'] == contract],
                    'diagnosis': asdict(diagnostic.diagnosis), 'signal_reason': diagnostic.signal.reason,
                    'candidates': [asdict(c) for c in diagnostic.candidates], 'gates': relevant,
                    'route': scan, 'cooldown_input': None,
                    'trace_semantics': '原函数短路；未到达的门 NOT_EVALUATED；require_execution_facts=False 的条件未启用'})
            for pid, raw in ledger.data['positions'].items():
                if pid not in positions:
                    context = ledger.position_context(pid)
                    positions[pid] = len(trades)
                    trades.append({'contract': raw['contract_code'], 'execution_bucket': bucket,
                        'opened_at_cst': cst(market.now), 'side': raw['side'], 'model': context['model'],
                        'entry_price': raw['entry_price'], 'initial_quantity': raw['quantity'],
                        'structure_key': context['structure_key'], 'exit_reason': None, 'closed_at_cst': None,
                        'exit_legs': [], 'exit_decisions': []})
                trade = trades[positions[pid]]
                for decision in decisions:
                    if decision['position_id'] == pid and decision['kind'] == 'EXIT_DECISION':
                        trade['exit_decisions'].append({'at_cst': cst(market.now), **decision['payload']})
                        if raw['status'] == 'CLOSED':
                            trade['exit_reason'] = decision['payload']['reason']
                            trade['closed_at_cst'] = cst(market.now)
                for fill in ledger.data['fills'][before_fills:]:
                    order = ledger.data['orders'][fill['order_id']]
                    if order['position_id'] == pid:
                        decision = next(d for d in decisions if d['position_id'] == pid
                                        and d['kind'] == 'EXIT_DECISION')
                        trade['exit_legs'].append(dict(at_cst=cst(market.now),
                            quantity=fill['quantity'], price=fill['price'],
                            reason=decision['payload']['reason']))
                trade['status'] = raw['status']
                trade['realized_pnl'] = raw['realized_pnl']
    coverage_end = {
        contract: min(epoch(assumptions.end_cst),
                      min(max(c.open_time + period_seconds(period) for c in series)
                          for (symbol, period), series in candles.items() if symbol == contract))
        for contract in {row['contract'] for row in live}
    }
    aligned, extras, missing = compare(live, trades, bars, coverage_end=coverage_end)
    full_matches = sum(r['match'] for r in aligned)
    book_fixture, fixture_gap, upper_bound = _fixture_notes(book, full_matches, len(live))
    if book is None:
        boundaries = [
            '默认假设重跑观察：BTC 36733c1f（02:15）：缺真实入场及管理时刻 bid/ask/mid、tick路径与实际评估时间。forming-open入场约82610，现网82633.5，MFE/武装时序级联偏移（重放04:45 ARM，05:00已非newly_armed）。不可凭OHLC唯一恢复；保留A，不回滚open-mid、不注入参考退出。',
            '默认假设重跑观察：ETH f56d3d17：缺真实管理盘口mid/tick路径及实际评估时间。参考19:30减5张trend-weakened-partial、19:45减2张trend-weakened；重放19:30减3张profit-at-risk、19:45减4张trend-weakened。原因与数量路径不可由固定K线唯一确定。',
            'ETH 93cef710（23:00）：缺覆盖13:45退出的15m/60m行情；详见窗外。盘口、funding、contract_spec假设继续保留。',
        ]
        book_limit = '盘口取证不可得 → 假设：scan_delay 时刻用形成中 15m bar 的 open 作 mid（优于上一根 close）；无形成中 bar 时回退上一根 close；spread=0（默认，仍支持配置 spread）；没有真实盘口深度、滑点或tick路径。optional_book 未声明，不加载、不虚构。'
    else:
        boundaries = [f'本次使用夹具 {book.file}（{book.provenance}），管理盘口不再使用 forming-open。']
        book_limit = (f'管理盘口来自哈希锁定夹具 {book.file}（kind={book.kind}, provenance={book.provenance}）。'
                      '不使用 forming-open，也不把 spread_rate 再加到夹具 bid/ask 上。')
    source_root = Path(__file__).parent
    report = {
        'header': {'schema_version': 3, 'package_version': tomllib.loads((source_root.parents[1] / 'pyproject.toml').read_text())['project']['version'], 'reported_live_version': '0.1.6-paper-db-c79afbaa',
            'strategy': STRATEGY, 'input_sha256': hashes,
            'source_sha256': {str(p.relative_to(source_root)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted(source_root.rglob('*.py'))},
            'entry_config': asdict(worker.entry_config), 'execution_config': asdict(execution_config),
            'assumptions': asdict(assumptions),
            'book_fixture': book_fixture,
            'fixture_gap': fixture_gap,
            'reachable_upper_bound': upper_bound,
            'exit_coverage_end_cst': {k: cst(v) for k, v in coverage_end.items()},
            'exit_evidence_boundaries': boundaries,
            'limitations': [
                book_limit,
                '取证不可得 → 假设：funding / contract_spec：funding默认0；contract_size BTC=.001/ETH=.01，tick=.01，合约可交易。',
                '假设/缺数据：初始空仓、无历史结构键/订单/手续费；300U、10倍、保证金上限10%、5仓，后续余额/风险按原worker动态计算。',
                '假设/缺数据：固定收盘后30秒，每15m调用一次；10秒收盘宽限；窗口160根，60m仅完整性检查，scan不使用其趋势。',
                '假设/缺数据：使用当前源码与默认配置；未证明等同现网构建及环境覆盖。源码哈希用于复跑。',
                '假设/缺数据：book深度与turnover缺失；require_execution_facts=False，深度、独立可用保证金、FIB质量、逆市/混合市场等受该开关控制的门未启用；min_average_turnover=0、fee_protection_enabled=False。硬保证金上限与止损风险门仍执行。',
                '原worker语义：prior_trade=None（cooldown无历史输入），市场context中性，退出opposition=0、flat_streak/breach_bars默认0。',
                '原worker语义：同币tick开始有仓则仅管理，即使本tick平仓也不再开；所有历史结构键持续去重。',
                '参考集：窗口内开仓且截至导出已平仓12个position；全部14腿来自预生成state close orders + EXIT_DECISION，BTC归因已交叉验证。ETH 23:00平仓晚于固定K线覆盖，不外推行情。',
                'CSV signal_bar_cst按成交桶解释；真实候选bar开盘为该值减15m；同时输出两种时间。',
                '退出使用原runtime.manage/exits.decide，包括部分平仓；数据末尾未平仓不强平。',
                '门输出为原函数实际短路路径；失败之后未到达的门不能记为通过；没有候选或已有仓时不执行候选门。',
                '只读固定CSV、MANIFEST和本包源码；内存ledger，无HTTP/数据库/密钥读取，不初始化生产worker。',
            ]},
        'summary': {'live_count': len(live), 'full_matches': full_matches,
                    'outside_coverage_count': sum(r['exit_outside_coverage'] for r in aligned),
                    'exit_mismatch_count': sum(not r['exit_match'] and not r['exit_outside_coverage'] for r in aligned),
                    'entry_matches': sum(r['entry_match'] for r in aligned), 'extra_count': len(extras),
                    'missing_count': len(missing), 'replay_trades': len(trades), 'bar_count': len(bars)},
        'alignment': aligned, 'extras': extras, 'missing': missing, 'trades': trades, 'bars': bars,
    }
    return plain(report)


def markdown(report):
    h, s = report['header'], report['summary']
    lines = ['# 完整 runtime.scan 离线重放', '', f"包版本 `{h['package_version']}`；配置 `{h['strategy']}`；现网版本（输入描述）`{h['reported_live_version']}`。", '',
             f"完整对齐 **{s['full_matches']}/{s['live_count']}**；方向/模型入场对齐 **{s['entry_matches']}/{s['live_count']}**；多发 **{s['extra_count']}**；漏发 **{s['missing_count']}**。", '',
             '完整对齐=position入场与每腿数量、15m退出桶、原因均相同；逐腿参考缺失不能记为完整通过。入场按12个position统计。', '',
             '## 输入哈希', '']
    lines += [f'- `{k}`: `{v}`' for k, v in h['input_sha256'].items()]
    if h['book_fixture']['loaded']:
        lines.append(f"- `{h['book_fixture']['file']}`: `{h['book_fixture']['sha256']}`（optional_book）")
    lines += ['', '## 逐笔对齐', '', '|现网开仓 CST|币种|方向/模型|现网出场|重放出场|结果/归因|', '|---|---|---|---|---|---|']
    for a in report['alignment']:
        live, replayed = a['live'], a['replay']
        reason = '一致' if a['match'] else '; '.join(a['reasons'])
        lines.append(f"|{live['opened_at_cst']}|{live['contract']}|{live['side']}/{live['model']}|{live['exit_reason']}|{replayed['exit_reason'] if replayed else '无开仓'}|{reason.replace('|', '/')}|")
    lines += ['', '## 逐腿出场对齐（时间按15m桶，数量按张）', '',
              '|position|腿|现网时间 / 数量 / 原因|重放时间 / 数量 / 原因|一致|',
              '|---|---|---|---|---|']
    for a in report['alignment']:
        for i, leg in enumerate(a['exit_leg_alignment'], 1):
            def describe(value):
                return (' / '.join(str(value[k]) for k in ('at_cst', 'quantity', 'reason'))
                        if value else '缺失')
            lines.append(f"|{a['live']['position_key']}|{i}|{describe(leg['live'])}|{describe(leg['replay'])}|{leg['match']}|")
    lines += ['', '## 窗外（不计完整通过）', '']
    for a in report['alignment']:
        if a['exit_outside_coverage']:
            lines.append(f"- {a['live']['position_key']}：参考平仓 {a['live']['closed_at_cst']}；重放/行情有效覆盖截至 {h['exit_coverage_end_cst'][a['live']['contract']]}。缺13:45时段15m/60m行情；不外推、不强平。")
    lines += ['', '## 未闭合差异与缺字段', '',
              f"窗内出场未对齐 {s['exit_mismatch_count']} 仓；窗外 {s['outside_coverage_count']} 仓。完整对齐 {s['full_matches']}/{s['live_count']}，"
              + ('未达 ≥11/12。' if s['full_matches'] < 11 else '达到 ≥11/12。'),
              '逐腿时间/数量/原因见上表；固定输入下仅补齐参考不能恢复真实盘口。以下为该参考集的证据边界：', '']
    lines += ['- ' + note for note in h['exit_evidence_boundaries']]
    gap_title = '夹具缺口清单' if not h['book_fixture']['loaded'] else '盘口夹具'
    lines += ['', '## ' + gap_title, '']
    lines += ['- ' + line for line in h['fixture_gap']]
    lines += ['', h['reachable_upper_bound']]
    lines += ['', '未改runtime/paper_worker/execution/exits；未回滚A；未出包、未装机。']
    for name, title in (('extras', '多发（参考集中未观察到）'), ('missing', '漏发')):
        lines += ['', '## ' + title, '']
        for row in report[name]:
            evidence = row['bar_evidence']
            gates = '; '.join(g['candidate']['model'] + ':' + g['stage'] + '/' + g['reason'] for g in evidence['gates']) if evidence else '无覆盖'
            lines.append(f"- {row['contract']} {cst(row['execution_bucket'])}：{gates}；worker={evidence['worker_gate'] if evidence else '无覆盖'}")
        if not report[name]:
            lines.append('无。')
    lines += ['', '## 假设和证据边界', ''] + ['- ' + line for line in h['limitations']]
    lines += ['', '```json', json.dumps(h['assumptions'], ensure_ascii=False, indent=2), '```', '',
              '完整配置、全部源码 SHA256、逐bar候选/路由/门阈值与实际值、逐持仓退出决策见同目录 report.json。', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--assumptions', type=Path, help='JSON assumption overrides; strategy thresholds are not configurable here')
    args = parser.parse_args()
    overrides = json.loads(args.assumptions.read_text()) if args.assumptions else {}
    for key, default in asdict(Assumptions()).items():
        if key in overrides and isinstance(default, Decimal):
            overrides[key] = D(str(overrides[key]))
    report = replay(args.inputs, Assumptions(**overrides))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
    (args.out / 'report.md').write_text(markdown(report))
    print(json.dumps(report['summary'], ensure_ascii=False))


if __name__ == '__main__':
    main()
