---
title: "Web BFF 接口清单（共 18 个）"
type: design
status: active
tags: [polymarket, audit, web]
created: 2026-05-17
updated: 2026-05-17
---

# Web BFF 接口清单（共 18 个）

> ⏱️ **上次更新**: 2026-05-16 第十四批 | 回看主决议第十一节 + 各批 web 章节
>
> **架构原则**：所有 GET 在 `coin-strategy-web` 本地实现（直查 DB），proxy 已废弃。
> 路由前缀：`/strategy/api/smart-wallet`，源码：`SmartWalletAuditController.java`

---

## 一、按用途分组

### 🔍 链路追踪（按 trace 或 leader 反查）

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /trace/{traceId}` | trace_id | 全链路时间线 (trade_event + follow_task + order + trade_order + position + shadow) + 关键耗时 |
| `GET /traces/by-leader/{wallet}?lookbackDays=&limit=` | leader 钱包 | 最近 N 天 trade_event 列表 (含 traceId) |

### 👻 影子记账

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /shadow/records?leaderAddress=&decisionStatus=&rejectLayer=&status=&limit=` | 多条件过滤 | shadow 记录列表 (默认 50, 上限 500) |
| `GET /shadow/records/{id}` | id | 单条 shadow 详情 |

### 🚨 审计告警

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /audit/alerts?alertType=&onlyUnresolved=&sinceCreatedAt=&limit=` | 多条件 | strategy_alert 中 strategy_id=SMART_WALLET_AUDIT 的告警 |

### 📊 审计报表

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /audit/settlement-source` | - | 实时聚合 lifecycle/parseFailure/overdue/claim 异常 |
| `GET /audit/slippage-postfact?lookbackDays=&maxOrders=` | 窗口 | 按 category 分桶 mean/median/P75/P95 |
| `GET /audit/live-replay-diff/latest` | - | 最新一天 diff |
| `GET /audit/live-replay-diff/history?limitDays=` | 天数 | 历史 trend |
| `GET /audit/resolution-tridiff?lookbackHours=` | 窗口 | 4 类不一致 + top-10 实例 |
| `GET /audit/leader-drift-aggregation?lookbackHours=` | 窗口 | 活跃 leader 列表 |

### 🔎 Resolution 审计

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /audit/resolution/{marketId}?limit=` | marketId | 该市场所有审计快照 |
| `GET /audit/resolution/needs-review?limit=&sinceObservedAt=` | 时间 | manualCheckReason 非空 |
| `GET /audit/resolution/uma-inconsistent?limit=` | - | closed=true 但 uma_resolved=false |

### 👤 Leader 诊断

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /leader-rolling/{wallet}?windowSize=` | leader | 滑动窗口胜率 + 最近 closes |
| `GET /leader-drift/{wallet}?lookbackDays=` | leader | 7 维 centroid + cosine 距离 |

### 💧 流动性 / 价差分位

| 端点 | 输入 | 输出 |
| --- | --- | --- |
| `GET /market-liquidity/percentile?asOfDate=` | 日期 | 各 category 当日 P10/P30/P50/P70 + spreadP70 |
| `GET /market-liquidity/percentile/{category}?limitDays=` | category | 历史 trend |

---

## 二、按批次落地（含 ⭐ 标 = 新增）

| 批次 | 端点 |
| --- | --- |
| 第一波（第八批） | trace + shadow×2 + audit/alerts |
| 第二波（第八批） | audit/settlement-source + audit/slippage-postfact + leader-rolling + leader-drift |
| 第三波（第九批） | market-liquidity/percentile + /{category} |
| 第十批 | audit/live-replay-diff/latest + /history |
| 第十一批 | audit/resolution/{marketId}×3 |
| 第十二批 第五波 | audit/resolution-tridiff + audit/leader-drift-aggregation |
| 第十三批 第六波 | ⭐ traces/by-leader/{wallet} |

---

## 三、Service 与 View 文件位置

```text
coin-strategy-web/src/main/java/com/metaverse/coin/strategy/web/
├── controller/smartwallet/
│   └── SmartWalletAuditController.java       # 全部 18 端点 入口
├── service/smartwallet/
│   ├── TraceTimelineReadService.java
│   ├── SmartWalletAuditReadService.java       # shadow + alerts
│   ├── SmartWalletAuditReportReadService.java # settlement-source + slippage
│   ├── SmartWalletLeaderInsightReadService.java # leader-rolling + drift
│   ├── MarketLiquidityPercentileReadService.java
│   ├── LiveReplayDiffHistoryReadService.java
│   ├── ResolutionAuditReadService.java
│   ├── SmartWalletAggregateAuditReadService.java # resolution-tridiff + leader-drift-aggregation
│   └── LeaderTraceListReadService.java        # /traces/by-leader/{wallet}
└── model/smartwallet/
    └── *View.java                             # 对应 view DTO
```

---

## 四、调用示例

```bash
PROD=http://prod-web:8080/strategy/api/smart-wallet

# 链路追踪
curl "$PROD/trace/tev_000000123456"
curl "$PROD/traces/by-leader/0xLeaderAddr?lookbackDays=7&limit=50"

# 影子记账
curl "$PROD/shadow/records?decisionStatus=REJECTED&limit=20"

# 审计报表
curl "$PROD/audit/alerts?onlyUnresolved=true"
curl "$PROD/audit/settlement-source"
curl "$PROD/audit/slippage-postfact?lookbackDays=7"
curl "$PROD/audit/live-replay-diff/latest"
curl "$PROD/audit/live-replay-diff/history?limitDays=14"
curl "$PROD/audit/resolution-tridiff?lookbackHours=24"
curl "$PROD/audit/leader-drift-aggregation?lookbackHours=24"

# Resolution
curl "$PROD/audit/resolution/0xMarketConditionId"
curl "$PROD/audit/resolution/needs-review?limit=20"
curl "$PROD/audit/resolution/uma-inconsistent?limit=20"

# Leader 诊断
curl "$PROD/leader-rolling/0xLeaderAddr?windowSize=20"
curl "$PROD/leader-drift/0xLeaderAddr?lookbackDays=30"

# 流动性
curl "$PROD/market-liquidity/percentile"
curl "$PROD/market-liquidity/percentile/POLITICS?limitDays=14"
```

---

## 五、新增 web 接口的最小步骤

1. 在 `model/smartwallet/` 新增 `XxxView.java`
2. 在 `service/smartwallet/` 新增 `XxxReadService.java`（直查 DB，不依赖 job 模块）
3. 在 `SmartWalletAuditController` 注入 + 加 endpoint
4. 静态校验 brace + 提交
5. 文档：在本清单 + 索引 + 主决议批次节同步

---

## 六、未来 backlog

- 按 trace_id 一次性返回 Mermaid 时序图（前端直接渲染链路）
- 多 leader 对比（同时取 N 个 leader 的 rolling / drift）
- shadow vs live 直接 join 一行返回（当前需要前端组合 2 个端点）

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
