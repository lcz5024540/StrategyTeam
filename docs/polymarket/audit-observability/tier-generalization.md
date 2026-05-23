---
title: "Tier 通用化 + 跨策略基础设施"
type: design
status: active
tags: [polymarket, audit]
created: 2026-05-17
updated: 2026-05-17
---

# Tier 通用化 + 跨策略基础设施

> ⏱️ **上次更新**: 2026-05-16 第十四批 | 回看主决议第十二 / 十五节
>
> **核心问题**：哪些基础设施已可被天气/BTC 5min 直接复用？哪些需要在新策略上线前抽象？

---

## 一、Tier A 已完成（通用工具 → common-service）

| 组件 | common-service 位置 | 复用方式 |
| --- | --- | --- |
| `TraceIds` | `service/trace/TraceIds.java` | 新策略加自己的 trace 前缀（`wth_` / `btc_`） |
| `TraceContext` | `service/trace/TraceContext.java` | `try (Scope ignored = TraceContext.scope(traceId))` |
| `StrategyAuditAlertSink` 接口 | `service/alert/StrategyAuditAlertSink.java` | 新策略只用 String 签名，不依赖跟单 AlertType |

---

## 二、Tier B 部分完成（需要小幅适配）

| 组件 | 已就绪 | 缺什么 |
| --- | --- | --- |
| `ShadowFillSimulationService` | ✅ 通用二元市场 VWAP 算法 | 上层 recorder 强耦合 SmartWallet, 需抽 `ShadowRecorder<E>` 接口 |
| `KellyStakeCalculator` | ✅ 纯算法 | 已通用，可直接复用 |
| `MarketLiquidityPercentileService` + `pm_market_liquidity_percentile_daily` | ✅ 通用 | category 维度需扩 BTC_5M / WEATHER 等品类 |
| `BehaviorVectorizer<E>` 接口 | ❌ 待抽 | `LeaderBehaviorDriftService` 的 7 维特征对天气/BTC 不适用，需要抽接口 + 各策略实现 |
| `CentroidDriftDetector` | ❌ 待抽 | cosine 距离 + 三态判定通用，但当前埋在 `LeaderBehaviorDriftService` 内部 |

---

## 三、Tier C（重大重构，等新策略真正排进 backlog 前 1-2 周做）

| 组件 | 当前状态 | 抽象方向 |
| --- | --- | --- |
| `SmartWalletSignalRiskService` 11 个 Gate | 700+ 行巨石 | 抽 `SignalRiskGate<S, R>` 接口 + `StrategyRiskEngine<S, R>` 责任链 |
| `SmartWalletReplayBacktestService` | 跟单专属 | 抽 `StrategyReplayEngine<R>` |
| `SmartWalletAccountingService` | 混合 `source=FOLLOW_TRADE/WEATHER` | 抽 `PositionAccountingService<P>` |
| `PolymarketOrderRequestFactory` + `LoggingPolymarketExecutionGateway` | 强耦合 SmartWalletSignal | 抽 `OrderRequestFactory<S>` + `ExecutionGateway<S>` |
| 7 个 Audit Job | 各自 `@Scheduled + AtomicBoolean` 模板 | 抽 `AuditJobTemplate` 基类 |

---

## 四、不应复用（跟单专属）

| 组件 | 原因 |
| --- | --- |
| `WalletEntityResolutionService` | 钱包实体识别只对"跟随他人"有意义 |
| `WalletArchetypeClassifierService` | leader 类型分类（信息交易者 / 做市商） |
| `WalletBehaviorProfileService` | leader 资产质量画像 |
| `FundingSourceClusterer` | 资金来源识别 |
| `EntityAwareConsensusService` | 多 leader 共识 |
| `LeaderRollingPerformanceService` | leader 历史胜率 |
| `LeaderBehaviorDriftService` 本体 | leader 行为漂移（算法可抽，service 强 leader） |
| `LeaderTraceListReadService` | leader-centric web 接口 |
| `SmartWalletFollowTaskService` | 跟单任务管理 |
| `FollowTradeAttributionService` | 跟单归因 |

---

## 五、跨策略上线前 1-2 周 checklist

- [ ] 在 `coin-strategy-common/` 新建 `common-strategy/` 子模块
- [ ] 抽 `SignalRiskGate` 接口 + `StrategyRiskEngine` 责任链
- [ ] 抽 `BehaviorVectorizer<E>` + `CentroidDriftDetector`
- [ ] 抽 `OrderRequestFactory<S>` + `ExecutionGateway<S>`
- [ ] 给 `smart_wallet_signal_shadow` / `live_replay_diff_history` / `pm_resolution_audit` 加 `strategy_id` 列
- [ ] 新策略实现各 interface
- [ ] 复用 audit Job 模板 + 统一告警通道

---

## 六、跨策略风险叠加（重心 C 范畴）

- 多策略并行时的 portfolio Kelly
- 同期相关性约束（跟单 政治市场 vs BTC 5min 可能高度相关）
- 跨策略止损联动（一个策略大幅亏损时，其它策略也要降仓）

详见主决议 6.3 + 6.5 盲点 #6。当前不动，等天气策略真正运行 1 个月后回顾。

---

## 七、相关详细文档

- [`cross-strategy-reusable-infrastructure.md`](../cross-strategy-reusable-infrastructure.md) — 完整可复用 vs 待抽清单
- 主决议第十二 / 十五节 — Tier A/B/C 设计决议过程

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
