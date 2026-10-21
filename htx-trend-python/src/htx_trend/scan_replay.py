"""Offline replay of the unchanged PAPER worker, scan and management call chain.

The scoped patches only inject time/environment and observe pure gate calls.
No production constructor, file ledger, exchange client or database is opened.
This process-local harness is intentionally not thread safe.
"""
from __future__ import annotations

import argparse
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
    'trades_closed_window.csv': '7345678fb736835e6b96b34cbe721a329687e26cb4878c28e44b2283e16a55f3',
}


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


class MemoryLedger(PaperFileLedger):
    """Reuse ledger transitions, deliberately bypass file/DB initialization."""
    def __init__(self):
        self.data = {'positions': {}, 'contexts': {}, 'orders': {}, 'fills': [], 'decisions': [], 'bars': {}}

    def checkpoint(self):
        pass

    def close(self):
        pass


class OfflineMarket:
    def __init__(self, candles, assumptions):
        self.candles, self.assumptions = candles, assumptions
        self.now = 0

    def kline_window(self, contract, period, size, *, now_epoch):
        # Same 10s completed-bar grace as HtxMarketData; never expose a forming bar.
        rows = tuple(c for c in self.candles[(contract, period)]
                     if c.open_time + period_seconds(period) <= now_epoch - 10)[-size:]
        return KlineWindow(contract, period, rows, size)

    def top_of_book(self, contract):
        close = self.kline_window(contract, '15min', 1, now_epoch=self.now).candles[-1].close
        half = close * self.assumptions.spread_rate / 2
        return TopOfBook(close - half, close + half)

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


def compare(live, trades, bars):
    """Match by contract and execution bucket; side/model/exit are separate tests."""
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
            for field, expected in (('side', row['side']), ('model', row['model']), ('exit_reason', row['exit_reason'])):
                if replay[field] != expected:
                    reasons.append(f'{field}: live={expected}; replay={replay[field]}')
            if replay['model'] != row['model'] or replay['side'] != row['side']:
                reasons.append('MODEL_ROUTING: ' + str(bar['route'] if bar else None))
            if replay['exit_reason'] != row['exit_reason']:
                reasons.append('EXIT_PATH: exits.decide_v4 进入获利回撤且延续恶化分支，而非弱势保护价突破分支；完整输入/输出见exit_decisions；真实盘口缺失，不能证明现网根因')
        alignment.append({'live': row, 'signal_bar_open_cst': cst(bucket - 900),
                          'execution_bucket_cst': cst(bucket), 'replay': replay,
                          'entry_match': replay is not None and replay['side'] == row['side'] and replay['model'] == row['model'],
                          'match': replay is not None and not reasons, 'reasons': reasons,
                          'bar_evidence': bar})
    # Closed-trade input is not a complete live scan/order ledger: these are unobserved extras.
    extras = [{**t, 'bar_evidence': index.get((t['contract'], t['execution_bucket']))}
              for i, t in enumerate(trades) if i not in used]
    return alignment, extras, missing


def replay(inputs: Path, assumptions: Assumptions = Assumptions()):
    assumptions.validate()
    candles, live, hashes = load_inputs(inputs)
    ledger = MemoryLedger()
    market = OfflineMarket(candles, assumptions)
    # Construct only inert dependencies. Production __init__ opens a persistent ledger.
    worker = PaperTrendWorker.__new__(PaperTrendWorker)
    worker.config = PaperWorkerConfig(equity=assumptions.equity, margin_rate=assumptions.margin_rate,
        max_active_positions=assumptions.max_active_positions, leverage=assumptions.leverage)
    worker.account, worker.profile, worker.entry_config = 'offline', TimeframeProfile.INTRADAY_15M, JavaEntryConfig()
    worker.ledger, worker.market, worker.stopped = ledger, market, False
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

    class ObservedRuntime(PaperTrendRuntime):
        def scan(self, **kwargs):
            nonlocal scan_contract
            scan_contract = kwargs['entry_window'].contract_code
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
        gate_calls.append({'contract': scan_contract, 'candidate': asdict(candidate), **asdict(result),
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
                        'exit_decisions': []})
                trade = trades[positions[pid]]
                for decision in decisions:
                    if decision['position_id'] == pid and decision['kind'] == 'EXIT_DECISION':
                        trade['exit_decisions'].append({'at_cst': cst(market.now), **decision['payload']})
                        if raw['status'] == 'CLOSED':
                            trade['exit_reason'] = decision['payload']['reason']
                            trade['closed_at_cst'] = cst(market.now)
                trade['status'] = raw['status']
                trade['realized_pnl'] = raw['realized_pnl']
    aligned, extras, missing = compare(live, trades, bars)
    source_root = Path(__file__).parent
    report = {
        'header': {'schema_version': 1, 'package_version': tomllib.loads((source_root.parents[1] / 'pyproject.toml').read_text())['project']['version'], 'reported_live_version': '0.1.6-paper-db-c79afbaa',
            'strategy': STRATEGY, 'input_sha256': hashes,
            'source_sha256': {str(p.relative_to(source_root)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted(source_root.rglob('*.py'))},
            'entry_config': asdict(worker.entry_config), 'execution_config': asdict(execution_config),
            'assumptions': asdict(assumptions),
            'limitations': [
                '假设/缺数据：盘口为最后已收盘15m close±配置spread/2；没有真实盘口深度、滑点或tick路径。',
                '假设/缺数据：funding默认0；contract_size BTC=.001/ETH=.01，tick=.01，合约可交易。',
                '假设/缺数据：初始空仓、无历史结构键/订单/手续费；300U、10倍、保证金上限10%、5仓，后续余额/风险按原worker动态计算。',
                '假设/缺数据：固定收盘后30秒，每15m调用一次；10秒收盘宽限；窗口160根，60m仅完整性检查，scan不使用其趋势。',
                '假设/缺数据：使用当前源码与默认配置；未证明等同现网构建及环境覆盖。源码哈希用于复跑。',
                '假设/缺数据：book深度与turnover缺失；require_execution_facts=False，深度、独立可用保证金、FIB质量、逆市/混合市场等受该开关控制的门未启用；min_average_turnover=0、fee_protection_enabled=False。硬保证金上限与止损风险门仍执行。',
                '原worker语义：prior_trade=None（cooldown无历史输入），市场context中性，退出opposition=0、flat_streak/breach_bars默认0。',
                '原worker语义：同币tick开始有仓则仅管理，即使本tick平仓也不再开；所有历史结构键持续去重。',
                '缺数据：CSV只有窗口内已平仓11笔，未包含全部未平仓/窗口外开仓；多发为相对该参考集未观察到，不能断言现网确未开。',
                'CSV signal_bar_cst按成交桶解释；真实候选bar开盘为该值减15m；同时输出两种时间。',
                '退出使用原runtime.manage/exits.decide，包括部分平仓；数据末尾未平仓不强平。',
                '门输出为原函数实际短路路径；失败之后未到达的门不能记为通过；没有候选或已有仓时不执行候选门。',
                '只读固定CSV、MANIFEST和本包源码；内存ledger，无HTTP/数据库/密钥读取，不初始化生产worker。',
            ]},
        'summary': {'live_count': len(live), 'full_matches': sum(r['match'] for r in aligned),
                    'entry_matches': sum(r['entry_match'] for r in aligned), 'extra_count': len(extras),
                    'missing_count': len(missing), 'replay_trades': len(trades), 'bar_count': len(bars)},
        'alignment': aligned, 'extras': extras, 'missing': missing, 'trades': trades, 'bars': bars,
    }
    return plain(report)


def markdown(report):
    h, s = report['header'], report['summary']
    lines = ['# 完整 runtime.scan 离线重放', '', f"包版本 `{h['package_version']}`；配置 `{h['strategy']}`；现网版本（输入描述）`{h['reported_live_version']}`。", '',
             f"完整对齐 **{s['full_matches']}/{s['live_count']}**；方向/模型入场对齐 **{s['entry_matches']}/{s['live_count']}**；多发 **{s['extra_count']}**；漏发 **{s['missing_count']}**。", '',
             '完整对齐=成交桶、币种、方向、模型、最终出场原因相同；不要求价格/数量/退出时刻相同。多发仅相对于11笔已平仓参考集。', '',
             '## 输入哈希', '']
    lines += [f'- `{k}`: `{v}`' for k, v in h['input_sha256'].items()]
    lines += ['', '## 逐笔对齐', '', '|现网开仓 CST|币种|方向/模型|现网出场|重放出场|结果/归因|', '|---|---|---|---|---|---|']
    for a in report['alignment']:
        live, replayed = a['live'], a['replay']
        reason = '一致' if a['match'] else '; '.join(a['reasons'])
        lines.append(f"|{live['opened_at_cst']}|{live['contract']}|{live['side']}/{live['model']}|{live['exit_reason']}|{replayed['exit_reason'] if replayed else '无开仓'}|{reason.replace('|', '/')}|")
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
