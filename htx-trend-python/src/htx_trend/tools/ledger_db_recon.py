"""离线账本对账；只读输入文件，不导入数据库连接或执行交易。"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime
from decimal import Decimal as D
import io
import json
from pathlib import Path

ZERO = D(0)


def number(value):
    if value in (None, '', 'NULL'):
        raise ValueError('缺少金额或数量')
    result = D(str(value))
    if not result.is_finite():
        raise ValueError('金额或数量非有限值')
    return result


def read_rows(path):
    """支持 JSON 数组、CSV、MySQL 制表符或边框表格（单表）。"""
    text = Path(path).read_text(encoding='utf-8-sig')
    if text.lstrip().startswith('['):
        return json.loads(text, parse_float=str)
    if text.lstrip().startswith('+'):
        lines = [line for line in text.splitlines() if line.startswith('|')]
        cells = [[v.strip() for v in line.strip('|').split('|')] for line in lines]
        return [dict(zip(cells[0], row, strict=True)) for row in cells[1:]]
    return list(csv.DictReader(io.StringIO(text), delimiter='\t' if '\t' in text.splitlines()[0] else ','))


def state_fills(state, positions):
    """用订单关联原始成交；不使用 state.realized_pnl 或 DB 盈亏重算。"""
    result = []
    for p in positions:
        key = p['position_key'].removeprefix('paper-')
        if key not in state['positions']:
            continue
        sp = state['positions'][key]
        for fill in state['fills']:
            order = state['orders'][fill['order_id']]
            entry = order.get('position_id') is None
            linked = order.get('position_id') == key or (entry and
                order['strategy_run_id'] == sp['strategy_run_id'] and
                order['account_id'] == sp['account_id'] and
                order['contract_code'] == sp['contract_code'] and order['position_side'] == sp['side'])
            if linked:
                result.append(dict(position_id=str(p['id']), fill_id=fill['fill_id'],
                    leg='ENTRY' if entry else 'CLOSE', quantity=fill['quantity'], price=fill['price'],
                    fee=fill['fee'], filled_at=fill['filled_at'], side=sp['side'],
                    contract_size=state['contexts'][key]['contract_size']))
    return result


def stamp(value):
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        raise ValueError('时间必须包含时区')
    return dt


def fill_metric(fills):
    entries = [f for f in fills if f['leg'] == 'ENTRY']
    closes = [f for f in fills if f['leg'] in {'CLOSE', 'PARTIAL_CLOSE'}]
    if not entries or not closes:
        raise ValueError('缺少开仓或平仓成交')
    if len({f['fill_id'] for f in fills}) != len(fills):
        raise ValueError('重复成交标识')
    if any(number(f['quantity']) <= 0 or number(f['price']) <= 0 or number(f['contract_size']) <= 0 for f in fills):
        raise ValueError('成交数量、价格、合约面值必须为正')
    if len({(f['side'], str(f['contract_size'])) for f in fills}) != 1 or entries[0]['side'] not in {'LONG', 'SHORT'}:
        raise ValueError('成交方向或合约面值不一致')
    entered = sum((number(f['quantity']) for f in entries), ZERO)
    closed = sum((number(f['quantity']) for f in closes), ZERO)
    if closed > entered:
        raise ValueError('平仓成交超过开仓成交')
    # 当前仓位模型为一次开仓后分批平仓；加仓交错需逐时成本法，不能猜测。
    if max(stamp(f['filled_at']) for f in entries) > min(stamp(f['filled_at']) for f in closes):
        raise ValueError('开平仓交错，需补充逐时成本分配')
    entry = sum((number(f['price'])*number(f['quantity']) for f in entries), ZERO)/entered
    gross = sum(((number(f['price'])-entry)*number(f['quantity'])*number(f['contract_size'])*
                 (1 if f['side'] == 'LONG' else -1) for f in closes), ZERO)
    opening = sum((number(f['fee']) for f in entries), ZERO)*closed/entered
    closing = sum((number(f['fee']) for f in closes), ZERO)
    return dict(gross=gross, opening_fee=opening, closing_fee=closing, net=gross-opening-closing,
                closed=closed, entered=entered, closed_at=max(stamp(f['filled_at']) for f in closes).isoformat())


def reconcile(positions, ledger, fills, *, window_start=None, as_of=None, funding_complete=False):
    """金额均为 Decimal；差额方向为 ledger 净减权威净。资金费正数表示成本。"""
    ids = [str(p['id']) for p in positions]
    if len(set(ids)) != len(ids):
        raise ValueError('重复仓位编号；请按账户分别导出')
    orphan_ids = {str(e.get('position_id')) for e in ledger} - set(ids)
    orphan_ids |= {str(f.get('position_id')) for f in fills} - set(ids)
    rows = [dict(position_id=pid, position_key=None, db_final_net=None, ledger_net=None,
                 authoritative_net=None, difference=None, db_difference=None,
                 reasons=['缺少关联仓位；不能丢弃未匹配事件'], status='证据不足')
            for pid in sorted(orphan_ids)]
    cutoff = stamp(as_of) if as_of else None
    for p in positions:
        pid = str(p['id'])
        fs = [f for f in fills if str(f['position_id']) == pid]
        events = [e for e in ledger if str(e.get('position_id')) == pid]
        row = dict(position_id=pid, position_key=p.get('position_key'), db_final_net=None,
                   ledger_net=None, authoritative_net=None, difference=None, db_difference=None,
                   reasons=[], status='证据不足')
        if p.get('final_net_profit') not in (None, 'NULL', ''):
            row['db_final_net'] = number(p['final_net_profit'])
        nets = [number(e['net_pnl_after_fee']) for e in events if e.get('net_pnl_after_fee') not in (None, 'NULL', '')]
        if nets:
            row['ledger_net'] = sum(nets, ZERO)
        try:
            metric = fill_metric(fs)
            if cutoff and stamp(metric['closed_at']) > cutoff:
                continue
            if p['status'] == 'DONE' and metric['closed'] != metric['entered']:
                raise ValueError('DONE 仓位缺少完整平仓成交')
            row.update(metric)
            if window_start and stamp(metric['closed_at']) < stamp(window_start):
                row['reasons'].append('旧窗')
            if not funding_complete:
                raise ValueError('资金费覆盖未确认；使用 --funding-complete 声明导出完整')
            funding = sum((number(e.get('funding_fee_usdt') or 0) for e in events), ZERO)
            row['funding_fee'] = funding
            row['authoritative_net'] = metric['net']-funding
            if row['db_final_net'] is not None:
                row['db_difference'] = row['db_final_net']-row['authoritative_net']
            if row['ledger_net'] is None:
                raise ValueError('缺少账本净额')
            diff = row['ledger_net']-row['authoritative_net']
            row['difference'] = diff
            candidates = [('开仓费', metric['opening_fee']), ('平仓费', metric['closing_fee']),
                          ('开仓费+平仓费', metric['opening_fee']+metric['closing_fee']), ('资金费', funding)]
            matches = [name for name, amount in candidates if amount and abs(diff-amount) <= D('0.00000001')]
            if diff == 0:
                pass
            elif abs(diff) <= D('0.00000001'):
                row['reasons'].append('四舍五入')
            elif len(matches) == 1:
                row['reasons'].extend(matches)
            else:
                row['reasons'].append('未解释差额（需逐事件核查）')
            row['status'] = '已对账'
        except (ValueError, KeyError) as exc:
            if p['status'] == 'ACTIVE' and not any(f['leg'] != 'ENTRY' for f in fs):
                row['status'] = '未平仓，不计入已实现'
            else:
                row['reasons'].append(str(exc))
        rows.append(row)
    done = [r for r in rows if r['status'] == '已对账']
    summary = {'matched_positions': len(done), 'incomplete_positions': sum(r['status'] == '证据不足' for r in rows)}
    for key in ('ledger_net', 'db_final_net', 'authoritative_net', 'difference', 'opening_fee', 'closing_fee', 'gross', 'funding_fee'):
        values = [r[key] for r in done if r.get(key) is not None]
        summary[key] = sum(values, ZERO) if values else None
    attribution = []
    for reason in ('开仓费', '平仓费', '开仓费+平仓费', '旧窗', '资金费', '四舍五入', '未解释差额（需逐事件核查）'):
        selected = [r for r in done if reason in r['reasons']]
        attribution.append(dict(reason=reason, positions=len(selected), amount=sum(
            (r['authoritative_net'] if reason == '旧窗' else r['difference'] for r in selected), ZERO)))
    return dict(summary=summary, attribution=attribution, positions=rows)


def markdown(report):
    lines = ['# 离线盈亏对账', '', '差额 = ledger 净 − fills 权威净；旧窗金额单列，不与口径差额相加。', '',
             '| 仓位 | ledger 净 | DB final_net | fills 权威净 | ledger 差 | DB 差 | 状态与原因 |',
             '|---|---:|---:|---:|---:|---:|---|']
    for r in report['positions']:
        values = [r['position_id'], r['ledger_net'], r['db_final_net'], r['authoritative_net'],
                  r['difference'], r['db_difference'], r['status']+'；'+'；'.join(r['reasons'])]
        lines.append('| '+' | '.join('缺失' if v is None else str(v) for v in values)+' |')
    lines += ['', '## 汇总归因', '', '| 原因 | 仓位数 | 金额 |', '|---|---:|---:|']
    lines += [f"| {r['reason']} | {r['positions']} | {r['amount']} |" for r in report['attribution']]
    lines += ['', '汇总仅包含已对账仓位；证据不足不视为零。', '', '```json', json.dumps(report['summary'], ensure_ascii=False, default=str, indent=2), '```']
    return '\n'.join(lines)+'\n'


def aggregate_reconcile(path):
    """多段 MySQL 汇总只验证恒等式，不推断逐仓原因。"""
    sections, title, lines = {}, None, []
    for line in Path(path).read_text().splitlines()+['===== END =====']:
        if line.startswith('====='):
            if title and lines:
                sections[title] = list(csv.DictReader(io.StringIO('\n'.join(lines)), delimiter='\t'))
            title, lines = line.strip('= '), []
        elif line.strip():
            lines.append(line)
    rows = []
    for ledger in sections['LEDGER paper vs primary totals']:
        pos = next((p for p in sections['POSITION PnL primary (LIVE)']
                    if p['account_label'] == ledger['account_label'] and p['status'] == 'DONE'), None)
        if pos:
            rows.append(dict(account=ledger['account_label'], ledger_net=number(ledger['net_sum']),
                db_final_net=number(pos['sum_final_net']),
                gross_difference=number(ledger['gross_sum'])-number(pos['sum_final_gross']),
                fee_difference=number(ledger['fee_sum'])-number(pos['sum_final_fee']),
                net_difference=number(ledger['net_sum'])-number(pos['sum_final_net']),
                status='证据不足：汇总范围可能不同，需逐仓、逐事件、成交及资金费导出'))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aggregate', help='MySQL 多段汇总导出，仅验证恒等式')
    parser.add_argument('--positions', help='仓位单表导出')
    parser.add_argument('--ledger', help='账本逐事件单表导出')
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--state', help='PAPER state JSON')
    source.add_argument('--fills', help='标准化成交单表导出')
    parser.add_argument('--window-start', help='带时区的窗口起点')
    parser.add_argument('--as-of', help='带时区的平仓截止时间；输入必须对应同一快照')
    parser.add_argument('--funding-complete', action='store_true', help='确认导出包含全部资金费，无行表示零')
    args = parser.parse_args()
    if args.aggregate:
        import sys
        rows = aggregate_reconcile(args.aggregate)
        print(json.dumps(rows, ensure_ascii=False, default=str, indent=2))
        print('| 账户 | ledger 净 | DB 净 | 毛差 | 费差 | 净差 | 状态 |\n|---|---:|---:|---:|---:|---:|---|', file=sys.stderr)
        for row in rows:
            print('| '+' | '.join(str(value) for value in row.values())+' |', file=sys.stderr)
        return
    if not args.positions or not args.ledger or not (args.state or args.fills):
        parser.error('逐仓对账需要 --positions、--ledger 和 --state 或 --fills')
    positions = read_rows(args.positions)
    fills = state_fills(json.loads(Path(args.state).read_text()), positions) if args.state else read_rows(args.fills)
    report = reconcile(positions, read_rows(args.ledger), fills, window_start=args.window_start,
                       as_of=args.as_of, funding_complete=args.funding_complete)
    # stdout 是 JSON，stderr 是 Markdown；输出重定向由调用方决定，输入永不改写。
    import sys
    print(json.dumps(report, ensure_ascii=False, default=str, indent=2))
    print(markdown(report), file=sys.stderr)


if __name__ == '__main__':
    main()
