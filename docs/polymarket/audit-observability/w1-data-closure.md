---
title: "W1 — 闭环数据 (trace_id / shadow / live-replay-diff / settlement-source)"
type: audit
status: active
tags: [polymarket, audit]
created: 2026-05-17
updated: 2026-05-17
---

# W1 — 闭环数据 (trace_id / shadow / live-replay-diff / settlement-source)

> ⏱️ **上次更新**: 2026-05-23 第十九批 | 回看主决议第三 / 十 / 十六 / 十七 / 十八 / 十九 / 二十 / 二十一节
>
> **核心问题**：跟单系统的每个信号 / 订单 / 仓位都能被反查吗？没下单的信号若放行会赚还是亏？回测和实盘的差异在哪？

---

## 一、W1-T3 全链路 trace_id

**目标**：从 leader 落单 → 我方信号 → follow_task → CLOB 订单 → 成交 → 仓位 → 结算，全链路共享同一 trace_id。

### 数据模型

- **trace_id 生成**：`tev_%012d`（trade_event.id 派生），16 字符固定长度
- **5 表带 trace_id 列**：`smart_wallet_trade_event` / `smart_wallet_follow_task` / `smart_wallet_order` / `smart_wallet_trade_order` / `smart_wallet_position`（position 用 `origin_trace_id` 语义：首次开仓的 trace）

### 核心组件

- `TraceIds`（common-service） — 生成 / 解析 / 校验
- `TraceContext`（common-service） — MDC try-with-resources
- `TraceQueryService` + Web `GET /smart-wallet/trace/{traceId}` — 反查全链路时间线
- logback pattern `[trace=%X{traceId:-}]` — 所有日志自动带 trace_id

### 验证 SQL

```sql
SELECT COUNT(*) FROM smart_wallet_trade_event WHERE trace_id IS NOT NULL AND created_at >= NOW() - INTERVAL 1 DAY;
```

---

## 二、W1-T4 被拒信号"影子记账"

**目标**：每个被风控拒绝 / 降级的信号都做 shadow 模拟，结算后回填影子 PnL，对比"若放行 vs 真实"的胜率差异。

### 数据流

```sql
SignalRiskService 决策 (REJECTED/PAPER_SHADOW/DOWNGRADED)
    ↓
SignalShadowRecorder.record(event, decision, ...)
    ├─→ ShadowFillSimulationService.simulate(side, stake, snapshot)  # VWAP 逐档算法
    └─→ INSERT INTO smart_wallet_signal_shadow  (status=PENDING_SETTLEMENT)
        ↓
ShadowSettlementBackfillJob (每 10 min)
    ├─→ 找 RESOLVED 市场 → 算 exit_price / pnl
    └─→ UPDATE smart_wallet_signal_shadow SET status=SETTLED
        ↓
ShadowVsLiveWeeklyReportJob (周一 06:00)
    └─→ 按 reject_layer + entry_estimator_source 分桶, 对比 shadow vs live PnL
```

### 第十四批 #1 新增维度

- `entry_estimator_source` 字段（"VWAP(OK)" / "VWAP(DRY_FALLBACK)" / "PROXY" / null）
- 周报新增 `estimatorSourceBreakdown` — VWAP-rejected vs PROXY-rejected 的胜率/平均滑点对比

### 关键 service / table

| 组件 | 位置 |
| --- | --- |
| `SignalShadowRecorder` | `coin-strategy-job/.../service/` |
| `ShadowFillSimulationService` | 同上 (7 用例 brace OK) |
| `ShadowSettlementBackfillService` + Job | 同上 |
| `ShadowVsLiveWeeklyReportService` + Job | 同上 |
| 表 `smart_wallet_signal_shadow` | DDL 见 `smart_wallet_audit_observability_schema.sql` |

---

## 三、W1-T1 Live↔Replay 一致性校验

**目标**：每日跑 T-1 日 replay backtest，与实盘 PnL / 信号数对比；diff > 阈值告警。

### 数据流

```text
LiveReplayDiffJob (每日 02:00 Asia/Shanghai)
    ↓
LiveReplayDiffReportService.runDailyDiff()
    ├─→ SmartWalletReplayBacktestService.replayLiveWindow(date)  # candidate 政策跑窗口
    ├─→ Query smart_wallet_follow_task + position WHERE created_at/openedAt in window
    ├─→ 算 signal_accepted_diff / pnl_diff_usdc / pnl_diff_bps
    ├─→ if exceeded → SmartWalletAuditAlertSink.publish(AUDIT_LIVE_REPLAY_DIFF)
    └─→ upsert INTO live_replay_diff_history (by as_of_date)
```

### Web 查询

- `GET /smart-wallet/audit/live-replay-diff/latest` — 最新一天
- `GET /smart-wallet/audit/live-replay-diff/history?limitDays=30` — 历史 trend

### 阈值配置

- `audit.live-replay-pnl-diff-bps=100`（默认）
- `audit.live-replay-signal-miss-pct=0.05`

---

## 四、W1-T2 结算源对齐审计

**目标**：UMA Oracle 争议、Polymarket resolution 延迟、`MarketResolutionResolver` 解析失败全部可观测；找出"卡 settlement 市场"。

### 数据流

```sql
SettlementSourceAuditJob (每小时整点)
    ↓
SettlementSourceAuditService.runScheduled()
    ├─→ 扫描 smart_wallet_market_info + position + claim_retry
    ├─→ 算 lifecycle 分布 / parseFailure / resolutionDelayed / pendingSettlementOverdue / claimFailureMax
    └─→ if anomalous → publish(AUDIT_SETTLEMENT_SOURCE)

MarketResolutionResolver.resolveWithAudit(marketId, request)
    ↓
ResolutionAuditPersistenceService.persist(ResolutionAuditRecord)
    └─→ INSERT INTO pm_resolution_audit (每次解析一条快照)

ResolutionTriDiffJob (每 4h)
    ↓
ResolutionTriDiffService.runScheduled()
    ├─→ 比对 audit 表中 我方 vs Gamma vs UMA 三方判定
    ├─→ 4 类不一致: OURS_VS_UMA / OURS_VS_GAMMA / UMA_VS_GAMMA / HISTORY_CONFLICT
    ├─→ if anomalyTotal >= warnThreshold → publish(AUDIT_RESOLUTION_TRIDIFF)
    └─→ if liveRefreshEnabled → ResolutionLiveRefreshService.refresh(topConflicts)
                                    ↓ 调 Gamma API 重新解析 → 新 audit 记录
```

### Web 查询

- `GET /smart-wallet/audit/settlement-source` — 实时报表
- `GET /smart-wallet/audit/resolution/{marketId}` — 单市场所有审计快照
- `GET /smart-wallet/audit/resolution/needs-review` — 需人工 review
- `GET /smart-wallet/audit/resolution/uma-inconsistent` — UMA 不一致
- `GET /smart-wallet/audit/resolution-tridiff?lookbackHours=24` — 三方 diff 报表

---

## 五、4 个数据闭环的关系图

```text
                  ┌──────────────────────────────────┐
                  │  trace_id  (W1-T3, 链路根)        │
                  │  生成于 trade_event → 透传 5 表    │
                  └────────────┬─────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ↓                      ↓                      ↓
  ┌──────────┐         ┌──────────┐           ┌──────────┐
  │ Shadow   │         │ LiveDiff │           │ Resolution│
  │ (W1-T4)  │         │ (W1-T1)  │           │ Audit     │
  │          │         │          │           │ (W1-T2)   │
  │ 拒绝信号  │         │ replay   │           │ Gamma/UMA │
  │ 模拟 PnL │         │ vs live  │           │ 三方 diff │
  └────┬─────┘         └────┬─────┘           └────┬─────┘
       │ shadow_pnl         │ pnl_diff_bps         │ anomaly counts
       ↓                    ↓                      ↓
  ┌────────────────────────────────────────────────────┐
  │  ShadowVsLive 周报 + AuditAlertSink → strategy_alert │
  │  Live diff history table → web /audit/live-replay-diff│
  │  Resolution tri-diff Job → web /audit/resolution-tridiff│
  └────────────────────────────────────────────────────┘
```

---

## 六、关键 SQL 验证（部署后）

```sql
-- W1-T3 trace_id 覆盖率
SELECT COUNT(*) total, COUNT(trace_id) with_trace
  FROM smart_wallet_follow_task
 WHERE created_at >= NOW() - INTERVAL 1 DAY;

-- W1-T4 shadow 命中率
SELECT decision_status, status, COUNT(*) cnt
  FROM smart_wallet_signal_shadow
 WHERE created_at >= NOW() - INTERVAL 7 DAY
 GROUP BY decision_status, status;

-- 第十四批 #1: VWAP vs PROXY 分桶
SELECT entry_estimator_source, COUNT(*) cnt,
       AVG(ABS(shadow_entry_slippage_bps)) avg_abs_bps,
       SUM(shadow_pnl_usdc) total_pnl
  FROM smart_wallet_signal_shadow
 WHERE status='SETTLED' AND shadow_settled_at >= NOW() - INTERVAL 30 DAY
 GROUP BY entry_estimator_source;

-- W1-T1 最近 7 天 diff
SELECT as_of_date, signal_accepted_diff, pnl_diff_bps, pnl_diff_exceeded
  FROM live_replay_diff_history
 WHERE as_of_date >= CURRENT_DATE - INTERVAL 7 DAY
 ORDER BY as_of_date DESC;

-- W1-T2 三方 diff (近 24h)
SELECT resolution_status, COUNT(*) cnt
  FROM pm_resolution_audit
 WHERE observed_at >= NOW() - INTERVAL 1 DAY
 GROUP BY resolution_status;
```

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
