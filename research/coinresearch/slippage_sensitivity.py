"""Offline extra-slippage scenarios; preserve supplied net/fees/funding baseline."""
import argparse
import csv
from decimal import Decimal, localcontext
import hashlib
import io
import json
from pathlib import Path

BPS = (0, 1, 2, 3, 5)


def sensitivity(path, source):
    if source not in ('phase1', 'paper'):
        raise ValueError('source must be phase1 or paper')
    raw = Path(path).read_bytes()
    records = list(csv.DictReader(io.StringIO(raw.decode('utf-8-sig'))))
    if not records:
        raise ValueError('empty trades')
    with localcontext() as ctx:
        ctx.prec = 40
        trades = []
        for r in records:
            def number(key):
                value = Decimal(r[key])
                if not value.is_finite():
                    raise ValueError(f'nonfinite {key}')
                return value
            entry, exit_, qty, size = [number(k) for k in ('entry_price', 'exit_price', 'qty', 'contract_size')]
            if min(entry, exit_, qty, size) <= 0 or r['side'] not in ('LONG', 'SHORT'):
                raise ValueError('invalid price, quantity or side')
            gross = number('gross_usdt' if source == 'phase1' else 'gross')
            fee = number('fee_usdt' if source == 'phase1' else 'fee')
            net = number('net_usdt_baseline' if source == 'phase1' else 'net')
            funding = number('funding_usdt') if source == 'phase1' else Decimal(0)
            slip = number('slippage') if source == 'paper' else Decimal(0)
            expected = (exit_-entry)*qty*size*(1 if r['side'] == 'LONG' else -1)
            if abs(expected-gross) > Decimal('0.000001') or abs(gross-fee-funding-slip-net) > Decimal('0.000001'):
                raise ValueError('gross/net baseline does not reconcile')
            trades.append((entry*qty*size, net))
        scenarios = []
        for bp in BPS:
            nets = [net-2*Decimal(bp)*notional/10000 for notional, net in trades]
            wins = sum(n > 0 for n in nets)
            scenarios.append(dict(extra_one_way_bp=bp, trades=len(nets), wins=wins,
                                  win_rate=str(Decimal(wins)/len(nets)),
                                  net_usdt=str(sum(nets)),
                                  extra_cost_usdt=str(sum(2*Decimal(bp)*n/10000 for n, _ in trades))))
    time_key = 'entry_time_utc' if source == 'phase1' else 'opened_at_cst'
    end_key = 'exit_time_utc' if source == 'phase1' else 'closed_at_cst'
    return dict(source=source, input_sha256=hashlib.sha256(raw).hexdigest(),
                first_entry=min(r[time_key] for r in records), last_exit=max(r[end_key] for r in records),
                scenarios=scenarios)


def write_report(phase1, paper, out_dir):
    result = dict(method='net(bp)=baseline_net-2*bp*entry_price*qty*contract_size/10000; fees and funding unchanged',
                  status='非登记试验、非 PRD 参数来源',
                  phase1_window='记忆窗口 2024-01-25..2025-06-03；CSV 可得段 2024-10-12..2025-06-02',
                  datasets=[sensitivity(phase1, 'phase1'), sensitivity(paper, 'paper')])
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out/'slippage.json').write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2)+'\n')
    md = '# P6 滑点敏感性\n\n非登记试验、非 PRD 参数来源。\n\n'+result['phase1_window']+'。\n\n'+result['method']+'。胜率按净盈亏严格 >0；盈亏=0 不算胜。无随机抽样。\n\n'
    md += '| 数据 | 单边额外 bp | 笔数 | 净盈亏 USDT | 胜率 |\n|---|---:|---:|---:|---:|\n'
    for dataset in result['datasets']:
        for s in dataset['scenarios']:
            md += f"| {dataset['source']} | {s['extra_one_way_bp']} | {s['trades']} | {Decimal(s['net_usdt']):.6f} | {100*Decimal(s['win_rate']):.2f}% |\n"
    for d in result['datasets']:
        md += f"\n{d['source']} input SHA256: `{d['input_sha256']}`；首入场 `{d['first_entry']}`；末平仓 `{d['last_exit']}`。\n"
    (out/'slippage.md').write_text(md)
    return result


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--phase1', required=True)
    p.add_argument('--paper', required=True)
    p.add_argument('--out-dir', required=True)
    a = p.parse_args(argv)
    try:
        write_report(a.phase1, a.paper, a.out_dir)
    except (ValueError, KeyError, OSError) as exc:
        p.error(str(exc))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
