# Polymarket Intelligence 改动总结

> 日期：2026-04-12
> 范围：实体识别、共识去重、候选层补强、延迟重评、历史回放、相关测试与测试基础设施修复

---

## 1. 背景与目标

本轮改动的核心目标，是先把 `smart wallet -> follow task` 这条链路里的“地址即实体”假设修正掉，避免因为钱包地址聚类不准，导致：

- 共识信号被同一实体的多个地址伪放大
- 候选信号在低置信或待聚类状态下被错误放行
- 首次出现的钱包在同一事件里被错误当成独立实体
- 后续接入链上巨鲸 / smart money 监控时，无法复用统一的实体层

这次工作不是简单修一个点，而是把链路升级成：

`TradeEvent -> Intelligence Enrich -> WalletFollowCandidate -> Risk Evaluation -> FollowTask`

并补上“延迟重评”和“历史回放”两条补数路径。

---

## 2. 本次解决的关键问题

### 2.1 首次未命中实体映射时，事件被错误按自身实体处理

原问题：

- `resolveEntity()` 首次未命中 `wallet_entity_map` 后，即使触发了聚类，也会先返回 `selfEntity`
- 这样当前这笔事件会立刻用错误 `entityId` 参与候选生成、共识去重和 follow task 决策

现修复为：

- 先触发聚类
- 再回查映射
- 只有仍然找不到可信实体时，才回退为 `selfEntity`
- 同时显式标记 `resolvedFromMap` / `pendingClustering`

---

### 2.2 共享入金地址被当成高置信同一实体证据

原问题：

- Polygon 上很多地址的首笔入金来自共享桥、交易所热钱包、relayer
- 如果只因为 funding source 相同就高置信合并，会把不相关的钱包错误折叠成同一个实体

现修复为：

- funding source 不再单独触发高置信合并
- 只有“共享入金来源 + 唯一候选 peer + 行为相似度达到阈值”时才合并
- 否则退回行为聚类或 self entity

---

### 2.3 共识去重忽略聚类置信度

原问题：

- `EntityAwareConsensusService` 之前只要有 `entityId` 就直接去重
- 低置信聚类也会立即削弱共识强度，可能把真实多钱包确认错误打成低共识

现修复为：

- 只有满足以下条件，才按实体去重：
  - `resolvedFromMap = true`
  - `clusterMethod != SELF`
  - `confidenceScore >= minClusterConfidenceForDedup`
- 否则退回钱包级 bucket

---

### 2.4 candidate 层只是注释层，不是真正的控制点

原问题：

- `wallet_follow_candidate` 虽然落库了，但很多关键字段是占位值
- `PENDING/LOW_CONFIDENCE_LINK` 没有真正影响 follow task 流程

现修复为：

- candidate 现在会写入真实的：
  - `entityId`
  - `isEntityFirstSignal`
  - `entityConsensusDedupCount`
  - `enrichmentStatus`
  - `rejectionReason`
- 对于 `PENDING` 候选，不再直接进入正常 follow task 流程
- 会先生成一条 `SKIPPED` 任务，占位并记录 intelligence 拦截原因

---

## 3. 主要实现内容

### 3.1 Intelligence 模型与解析结果增强

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/intelligence/model/EntityResolutionResult.java`
- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/intelligence/service/WalletEntityResolutionService.java`

新增/增强点：

- `resolvedFromMap`
- `pendingClustering`
- `selfEntity(wallet, pendingClustering)` 语义化 fallback

作用：

- 后续共识去重、候选状态机、延迟重评都可以明确区分：
  - “可信实体”
  - “待聚类”
  - “低置信链接”
  - “独立钱包 fallback”

---

### 3.2 聚类逻辑收敛为“可信才合并”

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/intelligence/service/WalletClusteringService.java`
- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/intelligence/service/BehaviorSimilarityClusterer.java`

改动要点：

- funding source 不再直接高置信合并
- 行为相似度阈值从配置读取，不再硬编码
- 自身实体落库时保留 funding source 线索，但不伪造高置信结果

---

### 3.3 共识信号改成“可信实体去重 + 不可信则按钱包”

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/intelligence/service/EntityAwareConsensusService.java`

改动要点：

- 引入 `SmartWalletProperties.Intelligence.minClusterConfidenceForDedup`
- 可信聚类才按 `entityId` 合并票数
- 不可信聚类保留钱包级投票
- 继续保留可疑多钱包膨胀标记

---

### 3.4 Follow Candidate 层真正接管流程

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java`

新增/重构的关键方法：

- `enrichAndSaveCandidate`
- `resolveEffectiveEntityId`
- `shouldTrustResolvedEntity`
- `determineEnrichmentStatus`
- `determineCandidateReason`
- `isEntityFirstSignal`
- `createSkippedTaskForPendingCandidate`

当前行为：

- `ENRICHED`：正常进入风险评估与任务生成
- `PENDING`：生成一条 `SKIPPED` follow task，并记录 intelligence 原因
- `LOW_CONFIDENCE_LINK`：candidate 保留低置信信息，但 follow 侧回退到钱包级身份

---

### 3.5 延迟重评机制

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java`
- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java`

新增能力：

- 定时扫描 `wallet_follow_candidate` 中的：
  - `PENDING`
  - `LOW_CONFIDENCE_LINK`
- 自动重新跑 intelligence enrich
- 如果原任务是因为 intelligence 被挡住的 `SKIPPED` 任务，则尝试恢复：
  - 更新 candidate
  - 重新风控评估
  - 将 follow task 从 `SKIPPED` 切回 `PENDING` 或维持 `SKIPPED`

新增配置：

- `smart-wallet.intelligence.candidate-reevaluation-fixed-delay-ms`
- `smart-wallet.intelligence.candidate-reevaluation-batch-size`

---

### 3.6 历史回放能力

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletFollowTaskService.java`
- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/controller/SmartWalletController.java`

新增 service：

- `replayRecentIntelligenceCandidates(limit, includeProcessed)`

新增接口：

- `POST /smart-wallet/follow-tasks/intelligence/reevaluate-deferred`
- `POST /smart-wallet/follow-tasks/intelligence/replay-recent`

作用：

- 对于历史上已经入库、但当时没有 candidate/task 的 trade event，可以回放补建
- 这也是未来接入链上巨鲸事件、补跑离线聚类结果、回放 smart money 信号的基础设施

---

### 3.7 WS 市场数据缓存兼容与补强

涉及文件：

- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketMarketDataStreamService.java`

本轮顺手修复：

- `getSnapshot()` 现在会真正应用 `wsCacheTtlMs`
- 加回兼容调用：
  - `getCachedSnapshot`
  - `handleMessage`
- `price_change` 事件不会再把已有 order book 覆盖掉
- 缺失 spread 时，自动根据盘口算出 spread bps

这部分虽然不是 intelligence 主链路，但会直接影响 mock WS 事件注入、快照采集和测试稳定性。

---

## 4. 测试与验证

### 4.1 修复的测试基础设施问题

本轮额外修复了测试基础设施的两类问题：

1. `common-client` 的测试 profile 之前只引了 `junit-jupiter-api`
   - 会导致“有注解但没有完整测试栈”

2. `coin-strategy-job` 的 `spring-boot-starter-test` 与 surefire/JUnit Platform 版本不一致
   - 之前会出现：
   - `java.util.Set org.junit.platform.engine.TestDescriptor.getAncestors()`

已处理方式：

- 根 `pom.xml` 引入 `junit-bom`
- `common-client` 改成使用完整 `junit-jupiter`
- `coin-strategy-job` 的 test profile 显式补齐 `junit-jupiter`
- 排除 `junit-vintage-engine`

---

### 4.2 新增/修正的测试

#### Intelligence / Consensus

- `WalletEntityResolutionServiceTest`
- `EntityAwareConsensusServiceTest`

覆盖点：

- 首次 miss + clustering 后可回查命中实体
- unresolved 时返回 pending/self
- 低置信聚类不参与实体去重
- 高置信聚类按实体去重

#### Follow Task / Candidate

- `SmartWalletFollowTaskServiceTest`

新增覆盖点：

- `reevaluateDeferredCandidates()` 会把被 intelligence 暂拦的任务恢复
- `replayRecentIntelligenceCandidates()` 会补建 candidate/task 并正确统计 summary

#### Controller

- `SmartWalletControllerTest`

新增覆盖点：

- `/follow-tasks/intelligence/reevaluate-deferred`
- `/follow-tasks/intelligence/replay-recent`

#### 其他顺带修正

- `BaselineModelConfigApplicationServiceTest`
- `PolymarketMarketDataStreamServiceTest`
- `MarketSnapshotCollectorServiceTest`
- `SmartWalletPositionSyncServiceTest`
- `WalletFeatureDailyServiceTest`

---

### 4.3 本轮已执行的验证命令

#### 定向业务测试

```bash
mvn -pl coin-strategy-job -am -DwithTests=true \
  -Dtest=WalletEntityResolutionServiceTest,EntityAwareConsensusServiceTest,PolymarketMarketDataStreamServiceTest,MarketSnapshotCollectorServiceTest,SmartWalletPositionSyncServiceTest,WalletFeatureDailyServiceTest,BaselineModelConfigApplicationServiceTest \
  test -Dsurefire.failIfNoSpecifiedTests=false
```

结果：

- 通过

#### Controller 测试

```bash
mvn -pl coin-strategy-job -am -DwithTests=true clean test \
  -Dtest=SmartWalletControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：

- 通过

#### FollowTaskService 测试

```bash
mvn -pl coin-strategy-job -am -DwithTests=true clean test \
  -Dtest=SmartWalletFollowTaskServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：

- 通过

---

## 5. 关键接口说明

### 5.1 手动重评延迟候选

```http
POST /smart-wallet/follow-tasks/intelligence/reevaluate-deferred?limit=50
```

返回示例：

```json
{
  "limit": 50,
  "updated": 12
}
```

适用场景：

- 聚类规则上线后，希望把之前 `PENDING` / `LOW_CONFIDENCE_LINK` 的 candidate 再过一遍

---

### 5.2 回放最近历史事件

```http
POST /smart-wallet/follow-tasks/intelligence/replay-recent?limit=100&includeProcessed=false
```

返回示例：

```json
{
  "limit": 100,
  "includeProcessed": false,
  "scanned": 100,
  "changed": 18,
  "skipped": 82
}
```

适用场景：

- 历史 trade event 已存在，但之前没有 candidate/task
- intelligence 逻辑升级后，希望回补旧数据
- 后续接入链上 smart money 监控后，对历史事件做统一回放

---

## 6. 对后续链上巨鲸 / Smart Money 监控的意义

这次改动的一个重要价值，是把“实体层”提前打好了，而不是把 intelligence 逻辑散落在 follow task 或共识里。

后续链上地址监控接入时，可以直接复用：

- `wallet_entity_map`
- `wallet_entity_feature_snapshot`
- `wallet_follow_candidate`
- `replayRecentIntelligenceCandidates`
- `reevaluateDeferredCandidates`

建议后续演进方向：

1. 将 Polymarket 地址与链上钱包地址映射纳入同一 `entity_id`
2. 链上事件先进入 candidate 层，而不是绕过 intelligence 直接生成 task
3. 所有“是否跟单”的决策都统一在 candidate -> risk -> task 这条链路内完成

---

## 7. 当前已知边界

本轮验证是“定向测试通过”，不是“全仓所有测试永久全绿”。

当前明确结论：

- intelligence 主链路已具备可验证的单测与 controller 测试
- 与本次改动直接相关的核心测试已通过

仍建议后续补的内容：

1. 增加 DAO / Mapper 层集成测试
2. 增加 `wallet_follow_candidate` 的实际回放数据校验脚本
3. 为线上补数设计幂等的运维 SOP
4. 对 debug / 外网依赖型测试做隔离，避免影响常规 CI

---

## 8. 建议的后续跟进任务

建议按以下顺序继续推进：

1. 增加 candidate 回放与重评的运维文档
2. 增加 mapper/DAO 集成测试，确认数据库层行为
3. 基于 `entity_id` 构建实体级特征快照
4. 将链上巨鲸 / smart money 监控事件接入 candidate 层
5. 在风控和画像中逐步把“钱包维度指标”替换为“实体维度指标”

---

## 9. 一句话总结

这次改动的本质，不是“多加了两个接口”，而是把 Polymarket 的跟单链路从“按地址做反应”，升级成了“按可信实体做决策，并支持延迟重评与历史回放”的可扩展基础设施。
