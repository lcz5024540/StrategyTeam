"""Render sealed regime research without recomputing or selecting strategy rules."""
import argparse
from datetime import datetime, timezone, timedelta
from decimal import Decimal as D
from pathlib import Path
import json

from htx_trend.realtime.regime_replay import read, file_hash
from htx_trend.research.store import digest


def pct(value):
    return '—' if value is None else f'{D(str(value))*100:+.4f}%'


def when(ms):
    return '—' if ms is None else datetime.fromtimestamp(ms/1000,timezone(timedelta(hours=8))).strftime('%m-%d %H:%M:%S.%f')[:-3]


def render(root, out):
    p=read(root/'protocol.json');report=read(root/'report.json');posterior=read(root/'posterior.json')
    rows=read(root/'records.json.gz')
    if not report['complete'] or report['protocol_hash']!=digest(p):raise ValueError('incomplete or mismatched study')
    lines=['# A/B场景交易与贝叶斯验证：冻结数据结果','',
        '作者：Codex；日期：2026-11-05。代码链路已实现，收益准入未通过。以下是报价路径诊断，不能当作IOC成交或账户盈利。','',
        f"研究区间（北京时间）：{when(p['start_ms'])} 至 {when(p['end_ms'])}；源码封印 `{p['code_seal']}`。",
        f"协议 `{digest(p)}`；处理 {report['processed']:,} 条事件。成本实验 `{p['cost_id']}`；延迟 {p['latency_ms']}ms、每边手续费 {D(p['fee_rate'])*10000}bp 为假设，资金费和额外滑点未知。",
        '没有指定账户本金、风险比例或仓位配置，因此不输出U金额、组合权益或账户回撤。所有收益率使用无杠杆入场名义金额作为分母。','',
        '## 四组、三档的直接结果','',
        '“入场”指延迟后的报价路径起点，不代表实际成交；“完整”要求在连续可用报价中触发冻结止盈/止损。其余状态保留未知。', '',
        '| 组别/退出档位 | 机会 | 报价入场 | 完整 | 盈/亏/零 | 历史胜率 | 完整平均净收益 | 末次标记均值* | 最大已观测回吐 |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for key,s in report['policies'].items():
        lines.append(f"| {key} | {s['opportunities']} | {s['entries']} | {s['closed']} | {s['wins']}/{s['losses']}/{s['zeros']} | {pct(s['historical_win_rate'])} | {pct(s['closed_mean_net_return'])} | {pct(s['last_mark_mean_net_return'])} | {pct(s['max_observed_giveback'])} |")
    lines+=['','*末次标记包括未平/被缺口截断的路径，时间不统一，不是最终收益、组合曲线或回撤。',
        'UNION为既有五模式并集；AB_FIRST只在新bar首次有效报价确认；AB_WINDOW持续等待；AB_GATE是持续等待加概率门。三档名称顺序为止损ATR/目标ATR。','',
        '## 场景—指标—入场—退出—概率—收益','',
        'A：1h EMA20<EMA60、1h MACD柱>0、15m MACD柱<0；阳线且下影≥实体，bid>收盘后做多。',
        'B：1h EMA20>EMA60、两个MACD条件与A相同；阴线且上影≥实体，ask<收盘后做空。两类均要求价差≤10bp及2秒新鲜盘口。','',
        '| 场景/档位 | 机会/完整 | 盈/亏 | 平均净收益 | 后验Beta | 后验胜率均值 [95%区间] | P(均值>0),60min | 完整数/小时块 | 结论 |',
        '|---|---:|---:|---:|---|---|---:|---:|---|']
    for key,g in posterior['groups'].items():
        scenario,rule,mode,cost=json.loads(key)
        if not rule.endswith('/WINDOW'):continue
        s=report['by_scenario'][scenario]['AB_WINDOW/'+mode]
        ci=g['posterior_ci95'];ci_text='—' if ci is None else f"{pct(g['posterior_mean'])} [{pct(ci[0])}, {pct(ci[1])}]"
        lines.append(f"| {scenario}/{mode} | {s['opportunities']}/{s['closed']} | {s['wins']}/{s['losses']} | {pct(s['closed_mean_net_return'])} | {g['posterior']} | {ci_text} | {pct(g['blocks']['60'].get('positive_mean_mass'))} | {g['n']}/{g['blocks']['60']['count']} | {g['status']} |")
    lines+=['','确实进行了贝叶斯推断：完整净收益>0为盈利，Beta(1,1)用唯一成熟胜负更新；50,000次直接抽样计算胜率区间。60分钟同步市场块使用10,000组Dirichlet权重估计正平均净收益支持度；30/120分钟结果见posterior.json。抽样次数不是交易笔数。',
        f"后验截止 `{when(posterior['cutoff_ms'])}`，根种子 {posterior['seed']}，逐组种子及训练机会ID均保存。后验hash `{posterior['hash']}`。",
        '每组至少20笔同合约非重叠完整结果、10小时块才达到样本门。本次后验在回放后计算，不倒灌到本段概率门；初始冻结没有合法旧快照时，概率组按SNAPSHOT_MISSING拒绝。',
        '缺口、未平、未成交及未知费用不填零；零收益计入完整非盈利。统计只描述已完成子集，严重截断和全盈利小样本不构成已验证优势。','',
        '## 概率门过滤损失与机会成本','',
        '| 组别 | 挡掉的完整亏损 | 误删的完整盈利 | 挡掉的未知 |',
        '|---|---:|---:|---:|']
    for key,c in report['filtering'].items():lines.append(f"| {key} | {c['blocked_losses']} | {c['blocked_wins']} | {c['blocked_unknown']} |")
    lines+=['','这是独立机会路径比较；重新调度后新增开仓需实际账户matrix结果，当前未提供配置，不编造该数量。','',
        '## 数据完整性、费用及集中度','',f"事件质量统计：`{json.dumps(report['quality'],ensure_ascii=False)}`。",
        f"FRAME条件匹配：`{json.dumps(report['frame_conditions'],ensure_ascii=False)}`。该分母是有效FRAME评估（含当时可见修订），不是成交数、历史胜率或后验概率。",'',
        '| 原组别 | 删除的主要盈利币种* | 剩余完整数 | 剩余胜率 | 剩余平均净收益 | 原完整平均费用 | 原盈亏比 |',
        '|---|---|---:|---:|---:|---:|---:|']
    for key,v in report['excluding_leading_asset'].items():
        if key.startswith('AB_GATE_'):continue
        s=v['remaining'];base=report['policies'][key]
        lines.append(f"| {key} | {v['removed_asset'] or '—'} | {s['closed']} | {pct(s['historical_win_rate'])} | {pct(s['closed_mean_net_return'])} | {pct(base['closed_mean_fee_return'])} | {base['payoff_ratio'] or '—'} |")
    lines+=['','*按完整机会收益率之和最大选择，含没有正盈利币种的情况，仅用于集中度诊断。报告JSON还包含各币种、各小时、状态和原因分母；资金费未当作真实零值。','',
        '## 逐笔明细（全部基础组）','',
        '| 组/合约/场景 | 触发 | 入场 | 平仓 | 秒数 | 入/出价 | 状态/原因 | 净收益 | 引用 |',
        '|---|---|---|---|---:|---|---|---:|---|']
    for r in rows:
        if r['policy'].startswith('AB_GATE_'):continue
        seconds='—' if r['entry_ms'] is None or r['exit_ms'] is None else f"{(r['exit_ms']-r['entry_ms'])/1000:.3f}"
        value=pct(r['net_return']) if r['status']=='CLOSED' else '未知'
        lines.append(f"| {r['policy']} {r['contract']} {r['scenario']} | {when(r['trigger_ms'])} | {when(r['entry_ms'])} | {when(r['exit_ms'])} | {seconds} | {r['entry_price'] or '—'}/{r.get('exit_price','—')} | {r['status']}/{r['reason'] or '—'} | {value} | `{r['opportunity_id'][:16]}` |")
    lines+=['','完整机会ID、开平价引用、指标值、ATR、冻结止损/目标、费用、MFE/MAE及所有概率门拒绝记录见records.json.gz；FRAME审核见frame-audit.json.gz。','',
        '## 复现与结论边界','',
        f"原始结果目录：`{root}`。",'执行命令见 release/REGIME-BAYESIAN.md。程序freeze→run→fit→本报告；每一步保留源、代码、协议、成本及快照身份。',
        '已研究的前段5分钟采样结果只作假设来源，不能补成本轮实时标签，也不能重标为未见验证。受保护留出未读取。',
        '后续使用新的已封口本地数据做extend，并保留原目录；训练只用当时已成熟的影子标签。独立账户回放使用account --matrix与明确账户配置，不运行交易服务。',
        '本轮不选出实盘胜者、不自动启用或部署。若完整标签、成本或未见验证不足，结论保持NOT_ELIGIBLE。','',
        '### 输出校验值','',*['- `'+name+'`：`'+file_hash(root/name)+'`' for name in ('protocol.json','records.json.gz','report.json','posterior.json','frame-audit.json.gz')]]
    out.write_text('\n'.join(lines)+'\n')


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--root',type=Path,required=True);ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();render(args.root,args.out)
