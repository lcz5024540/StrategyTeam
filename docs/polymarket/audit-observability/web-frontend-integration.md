---
title: "Web BFF 接口前端联调指南 (22 个端点)"
type: design
status: active
tags: [polymarket, audit, frontend]
created: 2026-05-17
updated: 2026-05-17
---

# Web BFF 接口前端联调指南 (22 个端点)

> ⏱️ **创建**: 2026-05-16 第十五批 | 兄弟文档: [`web-bff-interfaces.md`](./web-bff-interfaces.md)
>
> 面向前端工程师 / 量化研究员；目标是不依赖后端，直接 curl / fetch 即可联调。

---

## 一、Base URL & 鉴权

```text
PROD:  http://prod-web:8080/strategy/api/smart-wallet
TEST:  http://49.51.250.174:8080/strategy/api/smart-wallet
LOCAL: http://localhost:8080/strategy/api/smart-wallet
```

鉴权：复用项目既有 auth filter，本批 22 个端点都需要登录态。前端调用时附带 Session Cookie 或 Authorization 头（按现有约定）。

---

## 二、22 个端点速查表

> **说明**：API 基础路径为 `/strategy/api/smart-wallet`（由 `server.servlet.context-path=/strategy/api` + Controller 的 `@RequestMapping("/smart-wallet")` 组成）。本速查表及下文示例中的路径均为该基础路径之后的相对路径 —— 完整 URL = `/strategy/api/smart-wallet` + 表中路径。

| Group | Method+Path | 主要 query |
| --- | --- | --- |
| Trace | `GET /trace/{traceId}` | — |
| Trace | `GET /traces/by-leader/{wallet}` | `lookbackDays=7&limit=100` |
| Shadow | `GET /shadow/records` | `leaderAddress=&decisionStatus=&rejectLayer=&status=&limit=50` |
| Shadow | `GET /shadow/records/{id}` | — |
| Audit | `GET /audit/alerts` | `alertType=&onlyUnresolved=true&sinceCreatedAt=&limit=50` |
| Audit | `GET /audit/settlement-source` | — |
| Audit | `GET /audit/slippage-postfact` | `lookbackDays=7&maxOrders=5000` |
| Audit | `GET /audit/live-replay-diff/latest` | — |
| Audit | `GET /audit/live-replay-diff/history` | `limitDays=30` |
| Audit | `GET /audit/resolution/{marketId}` | `limit=50` |
| Audit | `GET /audit/resolution/needs-review` | `limit=50&sinceObservedAt=` |
| Audit | `GET /audit/resolution/uma-inconsistent` | `limit=50` |
| Audit | `GET /audit/resolution-tridiff` | `lookbackHours=24` |
| Audit | `GET /audit/leader-drift-aggregation` | `lookbackHours=24` |
| Audit | `GET /audit/shadow-estimator-source` ⭐ | `lookbackDays=7` |
| Leader | `GET /leader-rolling/{wallet}` | `windowSize=20` |
| Leader | `GET /leader-drift/{wallet}` | `lookbackDays=30` |
| Market | `GET /market-liquidity/percentile` | `asOfDate=` |
| Market | `GET /market-liquidity/percentile/{category}` | `limitDays=30` |
| Audit | `GET /audit/leader-rolling-aggregation` | 【待补充】query 参数 |
| Audit | `GET /audit/leader-rolling-aggregation/history` | 【待补充】query 参数 |
| Audit | `GET /audit/copy-trading-health` | 【待补充】query 参数 |

---

## 三、典型 dashboard 组合

### 3.1 "今日健康看板"

首屏 4 个 card：

| Card | 数据源 |
| --- | --- |
| 今日 / 昨日 Live↔Replay diff | `GET /audit/live-replay-diff/latest` |
| 结算源审计异常计数 | `GET /audit/settlement-source` → `anomalous` |
| 未恢复审计告警数 | `GET /audit/alerts?onlyUnresolved=true&limit=1` 取 totalCount |
| 三方 diff 今日异常数 | `GET /audit/resolution-tridiff?lookbackHours=24` → `anomalyTotal` |

### 3.2 "Leader 详情页"

按 leader address 路由进入：

```js
const wallet = '0xLeaderAddress';
const rolling = await fetch(`/audit/leader-rolling/${wallet}?windowSize=20`).then(r => r.json());
const drift   = await fetch(`/audit/leader-drift/${wallet}?lookbackDays=30`).then(r => r.json());
const traces  = await fetch(`/traces/by-leader/${wallet}?lookbackDays=7&limit=20`).then(r => r.json());
// 渲染: rolling.winRate, drift.cosineDistance, traces.traces[].traceId 列表
```

### 3.3 "VWAP vs PROXY 实证对比"

量化研究员关注：

```bash
curl '/audit/shadow-estimator-source?lookbackDays=14'
# 返回:
# {
#   "lookbackDays": 14,
#   "totalSettledShadows": 312,
#   "buckets": [
#     {"source": "VWAP(OK)", "count": 220, "winRate": 0.45, "shadowPnlUsdc": -34.20, "avgEntrySlippageBps": 95},
#     {"source": "PROXY",    "count":  78, "winRate": 0.51, "shadowPnlUsdc": +12.10, "avgEntrySlippageBps": 180},
#     {"source": "UNKNOWN",  "count":  14, ...}
#   ],
#   "verdict": "VWAP rejects worse signals: VWAP shadowPnl=-34.20 vs PROXY=+12.10 (VWAP 拒得更对)"
# }
```

### 3.4 "市场流动性看板"

```bash
# 今日各品类
curl '/audit/market-liquidity/percentile'

# 政治市场 14 天 trend
curl '/audit/market-liquidity/percentile/POLITICS?limitDays=14'
```

### 3.5 "Trace 反查"（线上排查必备）

```bash
# 已知 trace_id
curl '/trace/tev_000000123456'

# 不知道 trace_id 但知道 leader
curl '/traces/by-leader/0xLeader?lookbackDays=3' \
  | jq '.traces[] | select(.processed==false) | .traceId'
# 然后逐一调 /trace/{traceId}
```

---

## 四、Response 字段约定

### 通用规则

- 所有时间字段是 ISO-8601 字符串 (后端用 `java.util.Date`, Jackson 默认序列化)
- `BigDecimal` 序列化为数字 (不带引号)
- 空集合返回 `[]` 而非 `null`
- 阈值命中 boolean 字段以 `*Exceeded` 命名（例如 `pnlDiffExceeded`、`p95Exceeded`）

### 错误响应

- 404: 资源不存在 (例如 `/trace/{traceId}` traceId 拼写错)
- 400: 参数非法 (例如 `lookbackDays=99999`)
- 500: 后端异常 (前端建议显示 toast + retry 按钮)

### 分页约定

- 没有真正分页, 用 `limit` 截断 + `truncated:true` 标志（如 `LeaderTraceListView`、`SlippagePostFactReportView`）
- 前端若看到 `truncated:true`，提示用户"还有更多, 缩小窗口或加过滤"

---

## 五、Mock / 假数据

后端起 test profile 后, 让 `MockDataGenerator` 灌一组测试数据 (sql 见 `sql/polymarket/smart_wallet_manual_blocklist_seed_*.sql` 风格)；前端在 LOCAL 环境用 mock 服务模拟响应即可：

```js
// example: 用 msw 或 mirage 拦截
http.get('/strategy/api/smart-wallet/audit/live-replay-diff/latest', () => {
  return HttpResponse.json({
    asOfDate: '2026-05-15',
    pnlDiffBps: 87.3,
    pnlDiffExceeded: false,
    signalMissPct: 0.02,
    // ...
  });
});
```

---

## 六、Swagger UI

后端启用 `springdoc`, 访问：

```text
PROD:  http://prod-web:8080/strategy/api/swagger-ui.html
LOCAL: http://localhost:8080/strategy/api/swagger-ui.html
```

每个端点带 `@Operation(summary=..., description=...)` 注解，Swagger UI 可直接试调 + 拉取 OpenAPI JSON。

---

## 七、与前端 store 的集成建议

```ts
// 推荐 React Query / SWR
const { data: latestDiff } = useQuery({
  queryKey: ['liveReplayDiff', 'latest'],
  queryFn: () => fetch('/strategy/api/smart-wallet/audit/live-replay-diff/latest').then(r => r.json()),
  refetchInterval: 60_000  // 1 分钟刷新一次, 实际数据每日更新, 这里只是兜底
});
```

| 数据类型 | 推荐 refetchInterval |
| --- | --- |
| 日级数据 (live-replay-diff, shadow report, market-liquidity daily) | 5-10 min |
| 实时计算 (settlement-source, resolution-tridiff, slippage-postfact) | 1-2 min |
| Leader 诊断 (rolling, drift) | on-demand, 不自动刷新 |
| Trace 反查 | on-demand |
| 告警 (audit/alerts) | 30 s（页面停留时） |

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
