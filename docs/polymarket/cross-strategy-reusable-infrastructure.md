# 跨策略可复用基础设施清单

> **目的**：天气策略 / BTC 5min 策略 真正开工前 1 周需要 review 本文档。
> 标注 ✅ 已可直接复用 / 🟡 需小幅适配 / ⚠️ 需抽象 / ❌ 不应复用 (跟单专属)
>
> **2026-05-16 第十四批** 整理 — 基于第 1-13 批落地全貌。
>
> **相关文档**：[`audit-observability-index.md`](./audit-observability-index.md) / [当前主决议](./copy-trading-improvement-decision-2026-05-17.md) + [`tier-generalization.md`](./audit-observability/tier-generalization.md)（Tier A/B/C 精华）

---

## 一、✅ 已可直接复用（零改动）

### 1.1 trace 基础设施
| 组件 | 位置 | 复用方式 |
| --- | --- | --- |
| `TraceIds` | `common-service/.../service/trace/` | 任何策略只需新增前缀（`wth_` / `btc_`）即可生成自己的 trace_id |
| `TraceContext` | 同上 | `try (TraceContext.Scope ignored = TraceContext.scope(traceId))` 即可让日志带 trace |
| logback pattern `%X{traceId}` | `logback-spring.xml` | 全局共享 |

### 1.2 通用告警通道
| 组件 | 位置 | 复用方式 |
| --- | --- | --- |
| `StrategyAuditAlertSink` 接口 | `common-service/.../service/alert/` | 跨策略统一接口，签名 `publish(strategyId, alertType, ...)` |
| `StrategyAlertServiceAuditAlertSink` 实现 | `coin-strategy-job` | 落 `strategy_alert` 表 + 内置去重 |
| `AlertType` 枚举 | `engine/monitor/AlertType.java` | 新策略追加 `AUDIT_WEATHER_*` / `AUDIT_BTC5M_*` 即可 |

### 1.3 Kelly 仓位计算
| 组件 | 位置 | 复用方式 |
| --- | --- | --- |
| `KellyStakeCalculator` | `coin-strategy-job/.../service/` | 纯算法 + 无策略状态，新策略直接调 `compute(...)` |

### 1.4 影子记账（二元市场算法）
| 组件 | 位置 | 复用方式 |
| --- | --- | --- |
| `ShadowFillSimulationService` | `coin-strategy-job/.../service/` | 输入 side+stakeUsdc+snapshot，输出 VWAP 估算；任何 binary market 适用 |
| `smart_wallet_signal_shadow` 表 | DB | 新策略可复用，区分 source 即可（加 `strategy_id` 列再启用） |

---

## 二、🟡 需小幅适配

### 2.1 行为漂移检测
| 组件 | 改动 |
| --- | --- |
| `LeaderBehaviorDriftService` | 7 维特征向量是跟单专用（包含 5-cat one-hot），天气/BTC 需要自定义特征向量。建议抽 `BehaviorVectorizer<E>` 接口（详见 Tier B），各策略实现自己的 vectorizer |
| `LeaderDriftAggregationService` | 调用方依赖具体的 Drift service；改成依赖 `BehaviorVectorizer<E>` 接口后可复用 |
| `behavior_vector_json` 列 | 已是 JSON LONGTEXT，天气/BTC 写自己的特征向量即可，但需要按 strategy_id 区分 |

### 2.2 市场流动性分位
| 组件 | 改动 |
| --- | --- |
| `MarketLiquidityPercentileService` | `category` 字段是 Polymarket 品类 (POLITICS/CRYPTO/SPORTS/WEATHER)，天气策略可直接复用 WEATHER 分桶；BTC 5min 需要新分桶维度（例如 BTC_5M / BTC_15M） |
| `pm_market_liquidity_percentile_daily` 表 | 表结构通用，category 字段值扩充即可 |
| `MarketLiquidityPercentileSnapshotService` | 输入是 `PredictionMarketOpportunity`，需要新策略也把自己的 opportunity 注入到同一个 `opportunityService.list()` 或换为多源聚合 |

### 2.3 结算审计
| 组件 | 改动 |
| --- | --- |
| `MarketResolutionResolver` | UMA Oracle 字段在 Polymarket 普适，天气也走 UMA；BTC 5min 走 Polymarket 自有结算或 Pyth Network，需要新策略提供等价的解析层 |
| `pm_resolution_audit` 表 | 通用，可加 `strategy_id` 列后区分 |

### 2.4 滑点事后统计
| 组件 | 改动 |
| --- | --- |
| `SlippagePostFactReportService` | 当前查 `smart_wallet_order` + `smart_wallet_trade_event`，新策略需要自己的 order/event 表；或抽 `OrderRepository<O>` 接口 |

### 2.5 Live↔Replay diff
| 组件 | 改动 |
| --- | --- |
| `LiveReplayDiffReportService` | 依赖 `SmartWalletReplayBacktestService` (跟单专属)，新策略需要自己的 ReplayBacktest 实现；建议抽 `StrategyReplayEngine<R>` 接口 |
| `live_replay_diff_history` 表 | 通用，加 `strategy_id` 列后区分 |

---

## 三、⚠️ 需 Tier B/C 抽象（天气/BTC 上线前 1-2 周完成）

### 3.1 SignalRiskService 责任链化
当前 `SmartWalletSignalRiskService` 700+ 行，11 个 Gate 互相耦合。新策略需要 reuse 部分 Gate（如 G11 价格冲击三段式、G2c 行为漂移），不同 Gate 组合。

**建议抽象**：
```java
interface SignalRiskGate<S extends StrategySignal, R extends StrategyRiskDecision> {
    String gateId();              // G1, G2b, G11, ...
    boolean isApplicable(S);
    GateDecision evaluate(S, R);  // ACCEPT / DOWNGRADE / REJECT
}
class StrategyRiskEngine<S, R> {  // 责任链 + 早返
    List<SignalRiskGate<S, R>> gates;
    R evaluate(S signal);
}
```

各策略组合自己的 Gate 集合：
- 跟单：G1 blocklist + G2 archetype + G2b leader-rolling + G2c leader-drift + G6 market-end + G11 priceImpact + G11 spread-p70 + ...
- 天气：G1 blocklist + G2 weather-source + G6 market-end + G11 priceImpact + ...
- BTC 5min：G1 blocklist + G2 btc-source + G6 market-end + G11 priceImpact + G_btc_volatility + ...

### 3.2 Accounting / Position 抽象
`SmartWalletAccountingService` 中混合了 leader-specific 字段（`source=FOLLOW_TRADE/WEATHER`）。建议：
- 把 `smart_wallet_position` 抽出 `PositionEntity` 基类
- 各策略继承或用 `source` 字段区分

### 3.3 OrderRequestFactory + ExecutionGateway
当前 `PolymarketOrderRequestFactory` + `LoggingPolymarketExecutionGateway` 强耦合 SmartWalletSignal。建议抽：
```java
interface OrderRequestFactory<S> {
    PreparedOrder prepare(S signal, ExecutionWalletContext wallet);
}
interface ExecutionGateway<S> {
    StrategyExecutionResult execute(S signal, ExecutionMode mode);
}
```

### 3.4 Audit Job 模板
当前 7 个 Audit Job 各自实现 `@Scheduled` + `AtomicBoolean running` + try/catch 模板代码。抽 `AuditJobTemplate` 基类减少 boilerplate。

---

## 四、❌ 不应复用（跟单专属）

| 组件 | 原因 |
| --- | --- |
| `WalletEntityResolutionService` | 钱包实体识别只对"跟随他人交易"有意义 |
| `WalletArchetypeClassifierService` | leader 类型分类（信息交易者 / 做市商）跟单专属 |
| `WalletBehaviorProfileService` | leader 资产质量画像 |
| `FundingSourceClusterer` | 资金来源识别 |
| `EntityAwareConsensusService` | 多 leader 共识 |
| `LeaderRollingPerformanceService` | leader 历史胜率 |
| `LeaderBehaviorDriftService` | leader 行为漂移（特征向量算法可抽，但 service 本身强 leader） |
| `LeaderTraceListReadService` | leader-centric web 接口 |
| `SmartWalletFollowTaskService` | 跟单任务管理 |
| `FollowTradeAttributionService` | 跟单归因 |

---

## 五、跨策略上线前的 ⚠️ 抽取顺序建议

**T-2 周**：
1. 创建 `coin-strategy-common/common-strategy/` 子模块
2. 抽 `StrategyAuditAlertSink` ✅（已完成）
3. 抽 `TraceIds` / `TraceContext` ✅（已完成）
4. 抽 `SignalRiskGate` 接口 + `StrategyRiskEngine`

**T-1 周**：
5. 抽 `BehaviorVectorizer<E>` 接口；`LeaderBehaviorDriftService` 拆 vectorizer
6. 抽 `OrderRequestFactory<S>` + `ExecutionGateway<S>`
7. 把 `smart_wallet_signal_shadow` / `live_replay_diff_history` / `pm_resolution_audit` 加 `strategy_id` 列

**T 天上线**：
8. 新策略实现各 interface
9. 复用 audit Job 模板 + 通用通道

---

## 六、跨策略风险叠加（重心 C 范畴）

详见主决议文档 6.3 + 6.5 盲点 #6 — 多策略并行时的 portfolio Kelly / 同期相关性约束 / 跨策略止损联动。本文档不展开，留作天气策略真正运行 1 个月后回顾。

---

> **回顾节奏**：每个新策略上线前 1 周、每季度结束时 review 一次本文档；新增可复用组件时追加章节。
