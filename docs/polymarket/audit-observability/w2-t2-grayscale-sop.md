---
title: "W2-T2 Leader 滑动窗口胜率门 — 灰度观察 SOP"
type: runbook
status: active
tags: [polymarket, audit, deploy]
created: 2026-05-17
updated: 2026-05-17
---

# W2-T2 Leader 滑动窗口胜率门 — 灰度观察 SOP

> ⏱️ **创建**: 2026-05-16 第十七批 ｜ ⏱️ **第十八批升级**: 2026-05-16 已自动化（见下方"零、自动化模式"）
> 配套：[`w2-risk-gates.md`](./w2-risk-gates.md) / [`prod-deployment-checklist-w2-t6.md`](../prod-deployment-checklist-w2-t6.md)
>
> **目的**：W2-T2 在 test profile 第十五批已灰度启用（`enable-leader-rolling-gate=true`，minSamples=10 / minWinRate=0.40），本 SOP 指导运维每日 / 每周的观察动作，并给出阈值调整决策树。

---

## 零、自动化模式（2026-05-16 第十八批起默认）

下文一/二节的"每日人工跑 curl"在第十八批之后**只作为兜底**：日常已交给 Job + 告警:

- **Job**：`LeaderRollingMonitorJob` 每日 09:30（Asia/Shanghai）upsert 一行到
  `leader_rolling_aggregation_daily` 表；多 `lookbackDays` 在 YAML 配置 `7,14` 即两行。
- **告警**：`coolingRatio >= warnCoolingRatio (默认 0.40)` 或 `overallWinRate < warnMinOverallWinRate (默认 0.48)`
  时 publish `AUDIT_LEADER_ROLLING_HEALTH`（WARN 级），状态机：`OK / WARN_HIGH_COOLING / WARN_LOW_WIN_RATE / WARN_BOTH`。
- **Trend 查看**：`GET /strategy/api/smart-wallet/audit/leader-rolling-aggregation/history?historyDays=30&lookbackDays=7`
  返回最近 30 天每日记录 + `latestHealthStatus`，前端可直接出 trend 图，不再手贴表格。
- **关闭开关**：`smart-wallet.audit.leader-rolling-aggregation-enabled=false` 回退到下文人工 SOP（一/二节）。
- **关键配置**（YAML key 前缀 `smart-wallet.audit.`）：
  - `leader-rolling-aggregation-cron` 默认 `0 30 9 * * *`
  - `leader-rolling-aggregation-lookback-days` 默认 `[7]`，上线 7+14 写 `[7,14]`
  - `leader-rolling-aggregation-warn-cooling-ratio` 默认 `0.40`
  - `leader-rolling-aggregation-warn-min-overall-win-rate` 默认 `0.48`
  - `leader-rolling-aggregation-top-n-to-capture` 默认 `5`（写入 `top_cooling_leaders_json`）
  - `leader-rolling-aggregation-min-evaluated-count` 默认 `10`（< 此值不参与 health 判定）

一/二节保留作为 (a) 告警触发后**复核 SOP**；(b) 自动化关闭/失败时**降级 SOP**；(c) Onboard 新人理解机制。

---

## 一、每日观察 checklist（5 min）

每日 09:30（晨会前）跑：

### 1.1 抓数据

```bash
# 当日 COOLING leader 数 / 总活跃 leader 数
curl -s '/strategy/api/smart-wallet/audit/leader-rolling-aggregation?lookbackDays=14' \
  | jq '{activeLeaders, activeCount, coolingCount, coldCount, overallWinRate}'
```

### 1.2 看 3 个指标

| 指标 | 健康范围 | 异常处置 |
| --- | --- | --- |
| `coolingCount / evaluated` | < 15% | 超过 → 看 1.3 决策树 |
| `overallWinRate` | > 0.45 | 低于 → leader 池整体退化, 评估调 leader 准入 |
| `coldCount / activeLeaders` | < 30% | 超过 → 监控周期窗口太短, 加长 lookback |

### 1.3 看 top-15 COOLING leader 列表

```bash
curl -s '/strategy/api/smart-wallet/audit/leader-rolling-aggregation?lookbackDays=14' \
  | jq '.topCoolingLeaders[] | {leaderAddress, sampleCount, winRate, totalPnlUsdc, status}'
```
观察：
- 是否新出现以前没见过的 leader（潜在新失效）
- 同一 leader 连续 3 天上榜 → 加入手工 blocklist 或永久 suspend
- 同时关注 `totalPnlUsdc` — 即使 winRate 不低，但 PnL 显著负 → 可能是高频小赢大亏型

---

## 二、每周观察 checklist（30 min）

每周一 10:00（与 ShadowVsLiveWeeklyReport 同步看）：

### 2.1 跑 7 天聚合

```bash
curl -s '/strategy/api/smart-wallet/audit/leader-rolling-aggregation?lookbackDays=7' > rolling-week.json
curl -s '/strategy/api/smart-wallet/audit/shadow-estimator-source?lookbackDays=7' > vwap-week.json
curl -s '/strategy/api/smart-wallet/audit/live-replay-diff/history?limitDays=7' > replay-week.json
```

### 2.2 三方对照

| 维度 | rolling-week | vwap-week | replay-week |
| --- | --- | --- | --- |
| 整体胜率 | overallWinRate | buckets[*].winRate | (live-side) |
| 趋势 | top-15 列表迭代 | VWAP vs PROXY 累计 PnL 差 | pnl_diff_bps trend |
| 异常告警 | `coolingCount` 超阈值 | `verdict` 字段 | `pnl_diff_exceeded` 标志 |

### 2.3 周报输出（写入运营月报）

- 本周 COOLING leader 总数 / 平均 winRate / top-3 持续 COOLING leader 列表
- 与上周对比的变化趋势
- 建议：阈值是否调整？是否上 prod？

---

## 三、阈值调整决策树

### 3.1 当前 test profile 阈值

```yaml
enable-leader-rolling-gate: true
leader-rolling-min-samples: 10        # 默认 8 + 2
leader-rolling-min-win-rate: 0.40     # 默认 0.45 - 0.05
leader-rolling-cooling-stake-multiplier: 0.05
```

### 3.2 决策树

```text
本周 COOLING leader 比例 ?
│
├─ < 1% (几乎零触发)
│   └─ 阈值过宽 → 收紧:
│       - leader-rolling-min-win-rate: 0.40 → 0.45 (回归默认)
│       - 或 leader-rolling-min-samples: 10 → 8 (回归默认)
│
├─ 1% - 15% (正常范围) ✅
│   └─ 不动. 持续观察.
│
├─ 15% - 30% (偏多)
│   ├─ 整体 overallWinRate > 0.45?
│   │   ├─ 是 → 部分 leader 是异常退化, 正常拦截; 不动阈值
│   │   └─ 否 → leader 池整体退化, 考虑:
│   │       - 调查 leader 准入逻辑 (SmartWalletSelectionService)
│   │       - 评估是否暂时 enable-leader-drift-gate=true 双重拦截
│   │
│   └─ 阈值可微调放宽一档 (min-win-rate 0.40 → 0.38) 观察一周
│
└─ > 30% (过多)
    └─ 阈值过严 / 数据异常:
        - 立即 enable-leader-rolling-gate=false 回退
        - 排查 follow_task / position 数据完整性 (是否有大量 closedAt 缺失)
        - 排查 LeaderRollingPerformanceService 算法是否正确读到样本
```

### 3.3 上 prod 触发条件

全部满足 → 上 prod（修改 `application-prod.yml`）：

- [ ] test profile 连续 ≥ 14 天处于 "1% - 15% COOLING" 区间
- [ ] 与 W1-T4 ShadowVsLive 周报对比，启用前后 `shadowMinusLivePnlUsdc` 改善 ≥ +5 USDC
- [ ] LiveReplayDiff history 无 pnl_diff_exceeded=true 连续多日
- [ ] 无 W2-T2 相关 AUDIT_* 告警连续触发

上 prod 阈值比 test 再保守一档（最佳实践）：
```yaml
# application-prod.yml
enable-leader-rolling-gate: true
leader-rolling-min-samples: 15        # vs test 10
leader-rolling-min-win-rate: 0.45     # vs test 0.40
leader-rolling-cooling-stake-multiplier: 0.05
```

---

## 四、回退预案

随时回退（一行配置零重启）：
```yaml
smart-wallet:
  signal-risk:
    enable-leader-rolling-gate: false
```

触发回退条件：
- 任一指标超阈值（见 1.2）持续 3 天以上
- 出现意外副作用（例如 web 接口异常 / Job 内存爆炸）
- 灰度数据无法解释（与 Drift 门 / priceImpact 门同时启用，归因困难）

回退后：
1. 在 `docs/polymarket/audit-observability/w2-t2-rollback-YYYY-MM-DD.md` 写回退原因 + 数据快照
2. 评估是否调整算法（LeaderRollingPerformanceService）
3. 重新灰度

---

## 五、与其他风控门的交互

W2-T2 与同时启用的其他门可能叠加：

| 同时启用门 | 叠加效果 | 应对 |
| --- | --- | --- |
| W2-T3 consensus BOOST_ONLY | 共识加成 + leader-rolling 降级共存 → 最终 stake = base × 1.2 × 0.05 = 0.06× | 受 minOrderUsdc 兜底回升 |
| W2-T4 priceImpact 三段式 | 价差大 → MAKER_ONLY + leader 也 COOLING → stake × 0.25 × 0.05 = 0.0125× | 同上 |
| W2-T6 leader-drift | 行为漂移 DRIFTED + 胜率低 COOLING 双重拦截 | 这是期望行为；归因看 reject_layer (G2b vs G2c) |

监控点：
- 看 `smart_wallet_signal_shadow` 里 `reject_layer LIKE 'G2b%'` 与 `LIKE 'G2c%'` 的 overlap
- 如果大部分 G2b 命中 leader 也命中 G2c，说明两门高度相关，可考虑只保留一门

---

## 六、SOP 维护机制

- 每月第一周 review 本文档（结合实际灰度数据 / 调整记录补充章节）
- 阈值调整后立即更新 3.1 节
- 上 prod 后在 [prod-deployment-checklist-w2-t6.md](../prod-deployment-checklist-w2-t6.md) 同步 W2-T2 阈值

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
