---
title: "跟单策略闭环可观测性 — 主题导航索引"
type: index
status: active
tags: [polymarket, audit]
created: 2026-05-17
updated: 2026-05-17
---

# 跟单策略闭环可观测性 — 主题导航索引

> **2026-05-16 第十八批更新**：旧主决议（第 1-16 批历史时间线）已物理清理；
> 当前生效主决议为 [`copy-trading-improvement-decision-2026-05-17.md`](./copy-trading-improvement-decision-2026-05-17.md)。
> 16 批历史决策可通过 `git log` 回溯；本索引覆盖按主题导航当下生效的事实。

---

## 一、按主题分类的章节速查

### 🔧 闭环数据基础（W1 系列）

| 任务 | 主文档节 | 关键产物 |
| --- | --- | --- |
| W1-T1 Live↔Replay 一致性校验 | 三、第四批 #9、第十批 #7、第十二批 16.1 | `LiveReplayDiffReportService` + `live_replay_diff_history` 表 |
| W1-T2 结算源对齐审计 | 三、第五批 #11、第十一批 17.2 | `SettlementSourceAuditService` + `pm_resolution_audit` 表 |
| W1-T3 全链路 trace_id | 三、十三 | `TraceIds` / `TraceContext` (common-service) + 5 表 trace_id 列 |
| W1-T4 被拒信号影子记账 | 三、第二批 #4-#6、第七批 #14 | `SignalShadowRecorder` + `ShadowFillSimulationService` + `smart_wallet_signal_shadow` 表 |

### 🛡️ 风控门控（W2 系列）

| 任务 | 主文档节 | 关键产物 |
| --- | --- | --- |
| W2-T1 流动性 P30 滚动 | 四、第四批 #10、第九批 16.2 | `MarketLiquidityPercentileService` + `pm_market_liquidity_percentile_daily` 表 |
| W2-T1.4 价差 P70 门 | 第十批 #3、第十一批 18.2 | `SignalRiskService` G11 spread P70 段 |
| W2-T2 Leader 滑动窗口胜率门 | 四 | `LeaderRollingPerformanceService` + Gate 2b |
| W2-T3 共识强度作为加分项 | 四 | `consensusGateMode=BOOST_ONLY` 默认 |
| W2-T4 价格冲击三段式 + MAKER 路由 | 四、第三批 #7、第十二批 19.3-19.4、第十三批 20.4 | `MarketPriceImpactEstimator` + `MakerOnlyOrderTimeoutTracker` + SignalRiskService G11 |
| W2-T5 距结算时间门 | 四、第三批 #8 | SignalRiskService G11 三档 |
| W2-T6 Leader 行为漂移门 | 四、第六批 #13、第十批 #6、第十一批 18.4 | `LeaderBehaviorDriftService` + `LeaderDriftAggregationService` + Gate 2c |

### 🔄 通用化（Tier A/B/C）

| 任务 | 主文档节 | 关键产物 |
| --- | --- | --- |
| Tier A 通用化 trace 工具 | 十二、第八批 13.1 | `TraceIds` / `TraceContext` 移到 common-service |
| Tier A AuditAlertSink 通用化 | 十二、第十一批 17.1 | `StrategyAuditAlertSink` 接口在 common-service |
| 方案 B BehaviorProfile 与 Drift centroid 复用 | 十五、第九批 16.1 | `behavior_vector_json` 列 + `LeaderBehaviorDriftService.tryLoadFromBehaviorProfile` |

### 🌐 Web 展示接口（17 个）

详见主文档 19.8 + 20.7 的完整列表。按主题分组：

| 主题 | 端点 |
| --- | --- |
| **trace 反查** | `GET /trace/{traceId}` |
| **shadow 记账** | `GET /shadow/records[/{id}]` |
| **告警** | `GET /audit/alerts` |
| **结算审计** | `GET /audit/settlement-source`、`/audit/resolution/{marketId}`、`/audit/resolution/needs-review`、`/audit/resolution/uma-inconsistent`、`/audit/resolution-tridiff` |
| **滑点 / Live diff** | `GET /audit/slippage-postfact`、`/audit/live-replay-diff/{latest,history}` |
| **Leader 诊断** | `GET /leader-rolling/{wallet}`、`/leader-drift/{wallet}`、`/audit/leader-drift-aggregation`、**`/traces/by-leader/{wallet}`** ⭐ |
| **流动性 / 价差分位** | `GET /market-liquidity/percentile[/{category}]` |

入口源码：`coin-strategy-web/.../controller/smartwallet/SmartWalletAuditController.java`

### ⚙️ Job / SnapshotJob 列表

| Job | 默认 cron / 间隔 | 主文档节 |
| --- | --- | --- |
| `LiveReplayDiffJob` | `0 0 2 * * *` Asia/Shanghai | 第四批 #9 |
| `BehaviorProfileSnapshotJob` | fixedDelay 10 min | 第九批 16.1 |
| `MarketLiquidityPercentileSnapshotJob` | `0 30 2 * * *` Asia/Shanghai | 第九批 16.2 |
| `SettlementSourceAuditJob` | `0 0 * * * *` (每小时) | 第五批 #11 |
| `SlippagePostFactReportJob` | `0 0 7 * * MON` | 第五批 #12 |
| `ShadowSettlementBackfillJob` | fixedDelay 10 min | 第二批 #4 |
| `ShadowVsLiveWeeklyReportJob` | `0 0 6 * * MON` | 第二批 #5 |
| `LeaderDriftAggregationJob` | `0 0 8 * * *` Asia/Shanghai | 第六批 #13 |
| `ResolutionTriDiffJob` | `0 15 */4 * * *` Asia/Shanghai | 第十一批 17.3 |
| `MakerOnlyOrderTimeoutTracker.scan` | fixedDelay 2 s | 第十二批 19.4 |
| `LeaderRollingMonitorJob` ⭐ 第十八批 | `0 30 9 * * *` Asia/Shanghai | 当前主决议 第八节 |

### 📊 配置开关速查（按 enable-* 字段）

主文档第七节 + 后续每批 "自动化审视" 表均列出 `回退路径`。集中清单：

| 配置字段 | 默认值 | 控制内容 |
| --- | --- | --- |
| `signal-risk.enable-consensus-gate` → `consensus-gate-mode` | `BOOST_ONLY` | W2-T3 共识门 模式 |
| `signal-risk.enable-price-impact-gate` | true | W2-T4 三段式门控 |
| `signal-risk.enable-vwap-slippage-estimator` | **false / test=true** | W2-T4 用 VWAP 还是代理算 bps |
| `signal-risk.maker-only-timeout-tracker-enabled` | **false / test=true** | MAKER_ONLY 撤单 |
| `signal-risk.enable-time-to-expiry-gate` | true | W2-T5 距结算时间门 |
| `signal-risk.enable-spread-p70-gate` | **false / prod=true** | W2-T1.4 价差门 |
| `signal-risk.enable-liquidity-rolling-percentile` | **false / prod=true** | W2-T1 流动性门 |
| `signal-risk.enable-leader-rolling-gate` | **false** | W2-T2 滑动胜率门 |
| `signal-risk.enable-leader-drift-gate` | **false / test/prod=true** | W2-T6 行为漂移门 |
| `audit.alert-sink-enabled` | true | 全局审计告警 sink |
| `audit.shadow-backfill-enabled` | true | W1-T4 影子结算回填 |
| `audit.shadow-weekly-report-enabled` | true | W1-T4 周报 |
| `audit.live-replay-diff-enabled` | true | W1-T1 |
| `audit.settlement-source-audit-enabled` | true | W1-T2 |
| `audit.slippage-post-fact-enabled` | true | W2-T4.5 |
| `audit.liquidity-percentile-snapshot-enabled` | true | W2-T1.2 snapshot |
| `audit.leader-drift-aggregation-enabled` | true | LeaderDrift 聚合 |
| `audit.leader-drift-alert-enabled` | true | LeaderDrift 告警 publish |
| `audit.resolution-tridiff-enabled` | true | 第十一批 三方 diff Job |
| `audit.resolution-tridiff-live-refresh-enabled` | **false / test=true** | 第十二批 Gamma/UMA Live refresh |
| `audit.leader-rolling-aggregation-enabled` ⭐ | true | 第十八批 W2-T2 leader-rolling 自动化 |
| `audit.leader-rolling-aggregation-warn-cooling-ratio` ⭐ | 0.40 | 第十八批 health 触发 WARN_HIGH_COOLING |
| `audit.leader-rolling-aggregation-warn-min-overall-win-rate` ⭐ | 0.48 | 第十八批 health 触发 WARN_LOW_WIN_RATE |

---

## 二、按批次时间顺序回顾（含第十七 / 十八批）

| 批次 | 日期 | 重点 | 主文档节 |
| --- | --- | --- | --- |
| 第一批 | 2026-05-16 | W1-T3 trace_id + W1-T4 影子记账 | 三 |
| 第二批 | 2026-05-16 | 影子结算回填 + 影子周报 + audit 配置族 | 十、自动化追加 |
| 第三批 | 2026-05-16 | W2-T4.3 MAKER_FIRST/MAKER_ONLY 路由 + W2-T5 距结算时间门 | 十、第三批 |
| 第四批 | 2026-05-16 | W1-T1 Live↔Replay diff + W2-T1 流动性 P30 (内存版) | 十、第四批 |
| 第五批 | 2026-05-16 | W1-T2 结算源审计 + W2-T4.5 滑点事后统计 | 十、第五批 |
| 第六批 | 2026-05-16 | W2-T6 Leader 行为漂移门 | 十、第六批 |
| 第七批 | 2026-05-16 | W1-T1.5 + W1-T4.5 audit sink 统一收口 | 十、第七批 |
| 第八批 | 2026-05-16 | Tier A trace 通用化 + 第二波 web (4 端点) + LeaderDrift 聚合 Job | 十三 |
| 第九批 | 2026-05-16 | 方案 B (centroid 复用) + W2-T1.2 daily 表持久化 | 十六 |
| 第十批 | 2026-05-16 | AuditSink 通用化 + pm_resolution_audit + 价差 P70 写入 + W2-T6 灰度 + live_replay_diff history | 十七 |
| 第十一批 | 2026-05-16 | SQL 标准化 + 价差 P70 hot path + Gamma/UMA 三方 diff + W2-T6 prod + 第四波 web (3 端点) | 十八 |
| 第十二批 | 2026-05-16 | 跨策略灰度 + 第五波 web + VWAP estimator + MAKER_ONLY 撤单 + Live refresh | 十九 |
| 第十三批 | 2026-05-16 | test 灰度 (VWAP/MakerOnly/LiveRefresh) + 第六波 web + W2-T6 prod 部署文档 + VWAP hot path 接入 + 本索引 | 二十 |
| 第十四批 | 2026-05-16 | shadow estimator_source 字段 + ShadowVsLive 按 source 分桶 + 主题子文档拆分 | 旧主决议 二十一 |
| 第十五批 | 2026-05-16 | source 桶对比工具 + W2-T2 leader-rolling test 灰度 + Tier B 接口抽取 + frontend integration 文档 | 旧主决议 二十二 |
| 第十六批 | 2026-05-16 | W2-T2 web 端点 + LeaderBehaviorDrift 重构依赖 BehaviorVectorizer | 旧主决议 二十三 |
| 第十七批 | 2026-05-17 | SignalRiskGate Tier B + W2-T2 SOP 文档 + 旧主决议归档 + 新主决议创建 | 新主决议 二 |
| 第十八批 ⭐ | 2026-05-16 | W2-T2 灰度数据观察 自动化 (Job + 日级表 + 健康告警 + history 端点) | 新主决议 八 |

---

## 三、长期 backlog / 隐性盲点

详见主文档第六节 6.1-6.5（重心 B intent / 退出策略增强 / 多 leader 协调 / 智能化升级 / 10 个隐性盲点）。

---

## 四、相关辅助文档

| 文档 | 主题 |
| --- | --- |
| `polymarket策略深度分析报告.md` (仓库根目录) | 上游 GitHub 生态调研报告 |
| `docs/polymarket/copy-trading-improvement-decision-2026-05-17.md` | 当前生效主决议（第十七批起） |
| `docs/polymarket/prod-deployment-checklist-w2-t6.md` | W2-T6 + W2-T1.4 prod 真正上线时的 step-by-step checklist |
| `sql/polymarket/smart_wallet_audit_observability_schema.sql` | 闭环可观测性 6 张表 / 5 列 ALTER 的统一 schema |
| `sql/polymarket/*_migration.sql` | 单文件 migration（含第十八批 `leader_rolling_aggregation_daily_migration.sql`） |

> 索引每月更新一次；新增批次或新 Tier 改造时同步增订。

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../MOC-Polymarket.md) ｜ [知识库首页](../知识库首页.md)
