---
title: "Polymarket 量化系统 — 规约编程计划文档"
type: plan
status: active
tags: [polymarket, plan]
created: 2026-04-12
updated: 2026-04-19
---

# Polymarket 量化系统 — 规约编程计划文档

> 版本：v1.1 | 日期：2026-04-12
> 基于：`polymarket内幕分析.md` × 现有代码 Gap 分析 × 架构审查建议

---

## 进展入口

- 本轮实体识别、候选重评、历史回放、测试修复的落地总结：
  [POLYMARKET_INTELLIGENCE_PROGRESS_SUMMARY.md](POLYMARKET_INTELLIGENCE_PROGRESS_SUMMARY.md)

---

## ⚠️ 架构级问题：共识信号的「地址膨胀」Bug

> 此问题由架构审查发现，**已确认存在于现有代码中**，影响所有依赖共识信号的决策逻辑，必须在 Phase 1 中优先修复。

### 问题根因

`PolymarketConsensusSignalService.buildConsensusForEvent()` 中，共识的核心指标 `distinctWallets` 计算逻辑为：

```java
// 当前代码：按钱包地址去重，本质是地址计数
distinctWallets = count of unique wallet addresses on watchlist
consensusScore  = sum(wallet_scores) / count(wallets)
```

**真实问题场景**：一个 Insider 实体将资金拆分到 3 个钱包（均在 watchlist），在同一市场同向下单：
- 当前系统识别为：`distinctWallets = 3`，触发强共识 ✅
- 真实情况应为：`distinctEntities = 1`，不满足共识门槛 ❌
- 后果：系统在**单一实体伪装的共识信号**上下单，风险被严重低估

### 影响范围

| 位置 | 受影响逻辑 |
|------|-----------|
| `SmartWalletSignalRiskService.evaluate()` | `minDistinctWallets` 门控基于地址数，而非实体数 |
| `SmartWalletSignalRiskService` single-wallet override | 无法判断"单地址"是否真的是独立实体 |
| `SmartWalletScoringService` | 多钱包同实体的历史战绩重复计入 |
| `HHI`（持仓集中度特征）| 基于地址计算的 HHI 严重失真 |
| 后续链上巨鲸接入 | Polymarket 账户和链上地址无法打通 |

---

## 一、现状评估（已实现 vs 缺失）

### 1.1 架构层对照

| 文档提出 | 实现状态 | 备注 |
|---------|---------|------|
| 数据采集层（Gamma/CLOB/Data API/链上） | ✅ 已实现 | PolymarketApiClient + JavaClobClient |
| **Wallet/Entity Intelligence 层** | ❌ **缺失（P0）** | **共识按地址计数，实体膨胀 bug 存在** |
| 特征工程层（AVR/Jump/HHI/Fresh Wallet） | ❌ 缺失 | 无独立特征计算模块 |
| Alpha 模型层（规则/LR/XGBoost） | ❌ 缺失 | 无信号评分体系 |
| 策略执行层（下单/仓位/风控） | ✅ 基础已有 | 执行框架已有，风控门控存在 |
| 监控与复盘 | ⚠️ 部分 | 缺回测框架、告警系统 |

### 1.2 策略层对照

| 文档提出 | 实现状态 | 备注 |
|---------|---------|------|
| 策略一：事件前异常交易（AVR信号） | ❌ 缺失 | 当前仅做 Smart Wallet 跟单 |
| 策略二：价格未收敛套利 | ❌ 缺失 | 无实际价格对比模型 |
| 策略三：信息扩散延迟（Twitter/NLP） | ❌ 缺失 | 无社交数据接入 |
| 策略四：流动性套利（做市/扫单） | ❌ 缺失 | 无做市模块 |
| Smart Wallet 跟单 | ✅ 已实现 | 系统主策略，但受实体膨胀 bug 影响 |

### 1.3 风险控制对照

| 文档提出 | 实现状态 | 备注 |
|---------|---------|------|
| 洗盘识别（自成交过滤） | ❌ 缺失 | 无有效成交量过滤 |
| 流动性阈值过滤 | ❌ 缺失 | 无深度/Volume 最小阈值 |
| 事件时间校准 | ❌ 缺失 | 无 resolution source 对齐 |
| **实体级钱包聚类** | ❌ **缺失（P0）** | **共识信号可被多钱包伪放大** |
| 结算规则解析 | ⚠️ 部分 | 有 settlement sync，无规则争议标记 |
| 混淆因素控制（做市商/激励刷量） | ❌ 缺失 | 无对照组控制 |

---

## 二、模块改进规划

### 🔴 模块 0（前置必做）：Wallet/Entity Intelligence 层

> **这是所有模块的基础设施，必须在 Phase 1 中优先实现，不可跳过**
> 
> 不独立进程，在 `coin-strategy-job` 内新建包 `smartwallet/intelligence`

#### 核心思想

将当前的二元结构（钱包地址 → 跟单任务）升级为三层结构：

```text
当前：TradeEvent ──────────────────────────────────→ FollowTask
升级：TradeEvent → [Intelligence Enrich] → SignalCandidate → [Approved] → FollowTask
                           ↑
                    entity_id + cluster_confidence
```

#### 包结构规约

```text
coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/
└── smartwallet/
    └── intelligence/                          ← 新建领域包
        ├── entity/                            ← 实体模型
        │   ├── WalletEntityMap.java           ← 地址 → 实体映射
        │   ├── WalletEntityFeatureSnapshot.java ← 实体级特征快照
        │   └── WalletFollowCandidate.java     ← 候选跟单信号（含实体维度）
        ├── service/
        │   ├── WalletEntityResolutionService.java  ← 核心：地址 → 实体解析
        │   ├── WalletClusteringService.java         ← 聚类算法
        │   ├── EntityFeatureSnapshotService.java    ← 实体特征计算
        │   └── FollowCandidateEvaluationService.java ← 候选信号评估
        └── model/
            ├── EntityResolutionResult.java
            └── ClusterConfidence.java
```

#### 三张核心表规约

```sql
-- 表1：钱包 → 实体映射（核心索引）
CREATE TABLE wallet_entity_map (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    wallet_address  VARCHAR(100) NOT NULL,
    entity_id       VARCHAR(64)  NOT NULL,  -- UUID，同一实体共享
    cluster_method  VARCHAR(30)  NOT NULL,  -- FUNDING_SOURCE / BEHAVIOR_SIM / MANUAL
    cluster_confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0, -- 0-1，置信度
    funding_source_address VARCHAR(100),    -- 共同入金地址（如有）
    behavior_sim_score DECIMAL(5,4),        -- 行为相似度得分
    is_primary_wallet  TINYINT DEFAULT 0,   -- 该实体的主钱包标记
    verified        TINYINT DEFAULT 0,      -- 人工确认
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    UNIQUE KEY uk_wallet (wallet_address),
    INDEX idx_entity (entity_id),
    INDEX idx_funding_source (funding_source_address)
);

-- 表2：实体级特征快照（合并同实体所有钱包数据后的指标）
CREATE TABLE wallet_entity_feature_snapshot (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_id       VARCHAR(64) NOT NULL,
    snapshot_time   DATETIME NOT NULL,
    wallet_count    INT NOT NULL,           -- 该实体下的钱包数
    aggregated_win_rate DECIMAL(5,4),       -- 合并后胜率
    aggregated_roi      DECIMAL(10,4),      -- 合并后 ROI
    aggregated_pnl_usdc DECIMAL(18,2),      -- 合并后 PnL
    aggregated_total_trades INT,            -- 合并后交易次数
    entity_score    DECIMAL(5,4),           -- 实体维度综合得分
    is_multi_wallet TINYINT DEFAULT 0,      -- 是否存在多钱包
    created_at      DATETIME NOT NULL,
    INDEX idx_entity_time (entity_id, snapshot_time)
);

-- 表3：候选跟单信号（intelligence enrich 的输出，follow task 的前置）
CREATE TABLE wallet_follow_candidate (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_event_id BIGINT NOT NULL,        -- 关联 trade event
    wallet_address  VARCHAR(100) NOT NULL,
    entity_id       VARCHAR(64)  NOT NULL,
    cluster_confidence DECIMAL(5,4) NOT NULL,
    entity_wallet_count INT NOT NULL,       -- 该实体总钱包数
    is_entity_first_signal TINYINT DEFAULT 0, -- 该实体在本市场是否首次触发
    entity_consensus_dedup_count INT,       -- 去重后的实体共识数
    enrichment_status VARCHAR(20) NOT NULL, -- ENRICHED / SKIPPED_NO_ENTITY / PENDING
    rejection_reason  VARCHAR(200),
    follow_task_id   BIGINT,                -- 最终生成的 follow task（如有）
    created_at      DATETIME NOT NULL,
    INDEX idx_event (source_event_id),
    INDEX idx_entity (entity_id),
    INDEX idx_wallet (wallet_address)
);
```

#### 关键接口规约

```java
// ---- WalletEntityResolutionService ----
public interface WalletEntityResolutionService {
    /**
     * 解析钱包地址对应的实体
     * 优先查 wallet_entity_map，未命中则触发聚类检测
     */
    EntityResolutionResult resolveEntity(String walletAddress);

    /**
     * 批量解析，用于共识信号实体去重
     */
    Map<String, EntityResolutionResult> resolveEntities(List<String> walletAddresses);

    /**
     * 强制重新聚类（用于后台任务）
     */
    void reCluster(String walletAddress);
}

public record EntityResolutionResult(
    String walletAddress,
    String entityId,               // null 表示未找到关联实体，默认 = walletAddress 自身
    ClusterConfidence confidence,  // HIGH / MEDIUM / LOW / UNRESOLVED
    int entityWalletCount,         // 该实体下的钱包总数
    boolean isMultiWallet,         // 是否存在其他关联钱包
    String primaryWalletAddress    // 实体主钱包
) {}

public enum ClusterConfidence {
    HIGH,       // 共同入金地址 + 行为高度一致，confidence >= 0.85
    MEDIUM,     // 行为相似但入金来源不明，0.60 <= confidence < 0.85
    LOW,        // 仅行为弱相关，0.40 <= confidence < 0.60
    UNRESOLVED  // 无聚类信息，视为独立实体
}

// ---- 共识信号实体去重 ----
public interface EntityAwareConsensusService {
    /**
     * 在构建共识信号时，将地址列表折叠为实体列表
     * 一个实体贡献的多个钱包只计 1 票
     */
    EntityDedupedConsensus deduplicateByEntity(List<String> walletAddresses,
                                               Map<String, BigDecimal> walletScoreMap);
}

public record EntityDedupedConsensus(
    int rawWalletCount,            // 原始地址数（当前系统的值）
    int dedupedEntityCount,        // 去重后实体数（真实共识强度）
    List<String> entityIds,        // 参与实体列表
    BigDecimal entityAvgScore,     // 按实体平均的综合得分
    boolean hasSuspiciousMultiWallet // 是否存在同实体 >1 钱包触发（告警）
) {}
```

#### 跟单链路改造规约

**原链路（需修改）**：
```text
SmartWalletFollowTaskService.recordTradeEvent(event)
  → createFollowTaskIfQualified(event)   // 直接评估 → 直接建 task
```

**新链路（按规约实现）**：
```text
SmartWalletFollowTaskService.recordTradeEvent(event)
  → intelligenceEnrichService.enrich(event)      // Step 1: 实体解析 + 聚类置信度
      → WalletEntityResolutionService.resolveEntity(walletAddress)
      → 写入 wallet_follow_candidate（enrichment_status = ENRICHED）
  → followCandidateEvaluationService.evaluate(candidate)  // Step 2: 候选信号评估
      → 实体级重复检测（同实体已有 pending task → SKIP）
      → 更新 PolymarketConsensusSignal 时用 dedupedEntityCount 代替 distinctWallets
  → createFollowTaskIfQualified(candidate)        // Step 3: 原有逻辑（需传入 entityId）
      → SignalRiskDecision 的 distinctWallets 字段替换为 dedupedEntityCount
```

#### 对现有代码的修改点

| 文件 | 修改内容 |
|------|---------|
| `PolymarketConsensusSignalService.java` | `distinctWallets` 替换为 `dedupedEntityCount`，`consensusScore` 基于实体平均 |
| `SmartWalletSignalRiskService.java` | `minDistinctWallets` 门控改为 `minDistinctEntities`；single-wallet override 增加 `clusterConfidence` 检测 |
| `SmartWalletFollowTaskService.java` | 插入 intelligence enrich 中间步骤，`createFollowTaskIfQualified` 改为接收 `WalletFollowCandidate` |
| `SmartWalletScoringService.java` | 新增 `scoreEntity(entityId)` 方法，合并同实体多钱包历史后重新计算胜率/ROI |
| `SmartWalletProperties.java` | `minDistinctWallets` → `minDistinctEntities`；新增 `clusterConfidenceThreshold` |

---

### 模块 A：特征工程层（Feature Engineering）

**目标**：为所有市场构建实时可计算的 Alpha 特征，支撑后续信号评分

**缺失内容**：
- `AVR`（异常成交量比）
- `Jump`（价格跳变检测）
- `HHI`（持仓集中度）
- `Fresh Wallet Ratio`（新钱包占比）
- `Wallet Clustering`（钱包关联网络）

**设计规约**：

```text
FeatureEngineering
├── MarketFeatureCollector        // 汇总每个市场的实时特征
│   ├── AVRCalculator             // AVR = volume(T-24h) / avg_volume(30d)
│   ├── PriceJumpDetector         // Jump = max(|ΔP|) in sliding window
│   ├── DirectionConcentration    // 单边买入比例
│   └── SpreadDepthAnalyzer       // ask-bid spread + 订单簿深度
├── WalletFeatureCollector        // 汇总钱包维度特征
│   ├── HHICalculator             // Herfindahl-Hirschman Index
│   ├── FreshWalletDetector       // 首次交易时间 < N天
│   └── WalletClusterGraph        // 资金来源聚类（同入金地址）
└── FeatureStore                  // 特征存储与版本管理
```

**数据库新增表**：
```sql
-- 市场特征快照（每5分钟）
CREATE TABLE market_feature_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_id VARCHAR(100),
    snapshot_time DATETIME,
    avr DECIMAL(10,4),          -- 异常成交量比
    price_jump DECIMAL(10,6),   -- 最大价格跳变
    buy_side_ratio DECIMAL(5,4),-- 单边买入比例
    hhi DECIMAL(10,6),          -- 持仓集中度
    fresh_wallet_ratio DECIMAL(5,4),
    spread DECIMAL(10,6),
    depth_imbalance DECIMAL(5,4),
    created_at DATETIME
);
```

---

### 模块 B：Alpha 信号评分模型（Signal Scoring）

**目标**：将特征转化为可量化的交易信号评分，支撑自动决策

**缺失内容**：
- 规则引擎（基础评分）
- 信号综合评分（加权组合）
- 模型化评分（Logistic / XGBoost 预留接口）

**设计规约**：

```text
SignalScoring
├── RuleBasedSignalEngine         // 规则驱动的信号检测
│   ├── AbnormalVolumeRule        // AVR > 95分位 → +3分
│   ├── DirectionConcentrationRule// 单边买入 > 90% → +2分
│   ├── FreshWalletRule           // fresh_ratio > 30% → +2分
│   ├── PriceJumpRule             // Jump > 历史95分位 → +1分
│   └── LiquidityRule             // 过滤低深度市场（负过滤）
├── CompositeSignalScorer         // 加权组合 Score = Σ(wi * fi)
│   └── score = w1*AVR + w2*Jump + w3*HHI + w4*Fresh
├── SignalRecord                  // 信号记录模型
│   ├── marketId
│   ├── signalScore (0-10)
│   ├── signalLevel (LOW/MED/HIGH/CRITICAL)
│   ├── triggeredRules []
│   └── suggestedDirection (YES/NO)
└── ModelSignalEngine             // 预留 ML 模型接口（Phase 2）
    ├── LogisticRegressionScorer  // sklearn 模型调用
    └── XGBoostScorer
```

**数据库新增表**：
```sql
CREATE TABLE market_signal_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_id VARCHAR(100),
    signal_time DATETIME,
    signal_score DECIMAL(5,2),
    signal_level VARCHAR(20),    -- LOW/MED/HIGH/CRITICAL
    triggered_rules JSON,        -- 触发规则详情
    suggested_direction VARCHAR(10),
    avr_snapshot DECIMAL(10,4),
    hhi_snapshot DECIMAL(10,6),
    fresh_ratio_snapshot DECIMAL(5,4),
    is_followed TINYINT DEFAULT 0,
    created_at DATETIME
);
```

---

### 模块 C：市场扫描器（Market Scanner）

**目标**：自动扫描全市场，按标签筛选，输出异常市场列表

**缺失内容**：
- 全市场周期扫描任务
- 按板块过滤（体育/宏观/天气）
- 异常市场输出 + 前端展示

**设计规约**：

```text
MarketScanner
├── PolymarketMarketScanJob       // 定时任务，每15分钟全量扫描
│   ├── fetchAllActiveMarkets()   // 从 Gamma API 拉取活跃市场
│   ├── filterByCategory()        // 过滤体育/天气/宏观（排除政治）
│   └── computeFeatureAndScore()  // 触发特征计算 + 信号评分
├── MarketAnomalyRanker           // 对异常市场排名
│   ├── rankBySignalScore()
│   └── filterByLiquidityThreshold()
└── ScanResultStore               // 扫描结果持久化
```

**API 新增端点**：
```text
GET /market-scanner/anomalies          - 当前异常市场列表
GET /market-scanner/anomalies/{id}     - 市场异常详情（含特征分解）
GET /market-scanner/history            - 历史扫描结果
POST /market-scanner/trigger           - 手动触发扫描
```

---

### 模块 D：反洗盘过滤器（Wash Trade Filter）

**目标**：识别并过滤虚假交易，提升成交量特征的可靠性

**缺失内容**：
- 自成交识别（同地址/关联地址对敲）
- 高频对倒行为检测
- 有效成交量（Effective Volume）计算

**设计规约**：

```text
WashTradeFilter
├── SelfTradeDetector
│   ├── detectSameAddress()       // 同地址买卖
│   └── detectLinkedAddress()     // 关联地址（同入金源）
├── HighFreqPairDetector
│   └── detectRapidReversal()     // T+5min 内反向交易
├── EffectiveVolumeCalculator
│   └── effectiveVol = rawVol - washVol
└── WashTradeReport               // 可疑交易报告，标记而不删除
```

**数据库新增表**：
```sql
CREATE TABLE wash_trade_flag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market_id VARCHAR(100),
    trade_id VARCHAR(100),
    wallet_a VARCHAR(100),
    wallet_b VARCHAR(100),
    flag_reason VARCHAR(50),     -- SELF_TRADE / RAPID_REVERSAL / LINKED_ADDR
    confidence DECIMAL(5,2),
    created_at DATETIME
);
```

---

### 模块 E：流动性过滤器（Liquidity Gate）

**目标**：避免参与深度不足市场，防止大滑点和假信号

**缺失内容**：
- Volume Threshold 过滤
- Depth Threshold 过滤
- Spread 稳定性检测

**设计规约**：

```text
LiquidityGate
├── MinVolumeFilter               // 24h 成交量 < N USDC → 拒绝
├── MinDepthFilter                // 订单簿单侧深度 < N USDC → 拒绝
├── SpreadStabilityChecker        // spread 方差 > 阈值 → 降级
└── LiquidityGateResult
    ├── PASS
    ├── WARN (低流动性，降仓位)
    └── REJECT (拒绝进入)
```

**集成位置**：在 `SmartWalletSignalRiskService` 和 `MarketScanner` 中均需接入

---

### 模块 F：钱包聚类算法（Wallet Clustering Algorithms）

> **注意**：模块 F 是模块 0（Entity Intelligence 层）的内部实现细节，为其提供聚类能力。
> 模块 0 对外暴露统一接口，模块 F 是内部算法实现，不单独对外暴露服务。

**目标**：实现多种聚类算法，支撑 `WalletEntityResolutionService` 的实体识别

**算法规约**：

```text
WalletClusteringService（位于 smartwallet/intelligence/service/）
├── FundingSourceClusterer
│   ├── 数据源：Polygon 链上入金记录（via Polygonscan API 或 Alchemy）
│   ├── 算法：同一入金地址 → 同一实体（高置信）
│   └── groupByDepositAddress(List<String> wallets) → Map<String,String> wallet→entityId
├── BehaviorSimilarityClusterer
│   ├── 数据源：smart_wallet_trade_event 历史记录
│   ├── 特征向量：[交易时间分布, 市场选择重合度, 仓位大小相关性]
│   ├── 算法：余弦相似度 + 阈值（similarity > 0.80 → 同实体，MEDIUM 置信）
│   └── computeSimilarity(walletA, walletB) → BigDecimal
├── UnionFindEntityBuilder
│   ├── 输入：来自各 Clusterer 的 (walletA, walletB, confidence) 边集
│   ├── 算法：带权 Union-Find，合并置信度加权
│   └── build(edges) → Map<String, String> wallet→entityId
└── ClusterResultValidator
    └── 人工复核接口：POST /intelligence/entity/merge / /intelligence/entity/split
```

**Phase 1 简化策略**：
- 第一步仅实现 `FundingSourceClusterer`（最高置信，实现最简单）
- `BehaviorSimilarityClusterer` 在 Phase 2 实现
- 未聚类的钱包 `entity_id = wallet_address`（自身即实体，`cluster_confidence = 0`）

---

### 模块 G：结算规则解析与争议标记（Resolution Risk）

**目标**：识别规则模糊或存在争议风险的市场，提前规避结算不确定性

**缺失内容**：
- 市场规则 NLP 解析
- UMA Oracle dispute 状态跟踪
- 高争议风险市场标记

**设计规约**：

```text
ResolutionRiskAnalyzer
├── MarketRuleParser
│   └── extractResolutionConditions()  // NLP 提取结算条件
├── DisputeTracker
│   └── pollUMAOracleStatus()          // 跟踪 UMA dispute 状态
├── RiskClassifier
│   ├── CLEAR   // 规则明确
│   ├── UNCLEAR // 存在歧义（降低仓位）
│   └── RISKY   // 历史有争议（禁止参与）
└── ResolutionRiskReport
```

---

### 模块 H：回测框架（Backtesting）

**目标**：验证信号策略历史表现，支撑参数优化

**缺失内容**：
- 历史数据回放引擎
- 信号回测执行器
- 回测结果分析（胜率/Sharpe/最大回撤）

**设计规约**：

```text
BacktestEngine
├── HistoricalDataLoader
│   └── loadMarketSnapshots(startDate, endDate)
├── SignalReplayEngine
│   └── replaySignalGeneration()      // 用历史特征重跑信号
├── SimulatedExecutor
│   └── simulateTrade(signal, price)  // 模拟撮合
├── BacktestMetricsCalculator
│   ├── winRate()
│   ├── sharpeRatio()
│   ├── maxDrawdown()
│   └── profitFactor()
└── BacktestReport                    // 回测报告输出
```

---

### 模块 I：告警与监控系统（Alerting）

**目标**：生产级实时监控，及时发现异常并通知

**缺失内容**：
- Telegram / Discord Bot 集成
- 信号触发告警
- 系统健康监控告警
- 仓位异常告警

**设计规约**：

```text
AlertingSystem
├── AlertChannel
│   ├── TelegramAlertSender
│   └── DiscordWebhookSender
├── AlertRule
│   ├── SignalTriggeredAlert       // 高分信号触发通知
│   ├── PositionRiskAlert          // 仓位亏损 > X% 通知
│   ├── SystemHealthAlert          // 服务异常/API 超时通知
│   └── SettlementAlert            // 市场结算结果通知
└── AlertThrottler                 // 防告警风暴（同类告警去重）
```

---

### 模块 J：前端市场扫描器页面

**目标**：在现有 Dashboard 基础上新增市场扫描器页面

**缺失内容**：
- 异常市场列表页（含评分/原因分解）
- 市场详情页（特征时序图）
- 信号历史记录页

**设计规约**：

```text
前端新增页面
├── /market-scanner
│   ├── AnomalyMarketTable         // 异常市场列表
│   │   ├── 市场ID / 名称
│   │   ├── 异常评分 (进度条)
│   │   ├── 触发原因标签（成交异常/钱包集中/新钱包）
│   │   └── 操作：查看详情 / 手动跟单
│   └── ScanStatusBar              // 上次扫描时间 + 手动触发按钮
└── /market-scanner/:marketId
    ├── MarketFeatureTimeline       // AVR/HHI/Fresh 时序图
    ├── SignalHistoryList           // 该市场历史信号记录
    └── WalletHeatmap              // 参与钱包分布热力图
```

---

## 三、实施优先级与阶段规划

> **核心原则**：Entity Intelligence 是基础设施，必须先于特征工程和信号评分实现。
> 错误的实体识别会污染所有下游计算，上层建得越多，修正代价越高。

---

### Phase 1：Entity Intelligence 基础设施（3周）

> **目标：修复实体膨胀 bug，建立实体维度的基础能力，不独立进程，内嵌 coin-strategy-job**

#### 第 1 周：数据库 + 包骨架

| 任务 | 文件/位置 | 工作量 |
|------|----------|--------|
| 创建 `wallet_entity_map` 表 | `sql/arbitrage_schema.sql` | 0.5天 |
| 创建 `wallet_entity_feature_snapshot` 表 | 同上 | 0.5天 |
| 创建 `wallet_follow_candidate` 表 | 同上 | 0.5天 |
| 建立 `smartwallet/intelligence` 包骨架 | `coin-strategy-job` | 0.5天 |
| 实现 `WalletEntityMap` / `WalletFollowCandidate` Entity + Mapper | common-model | 1天 |

#### 第 2 周：聚类服务 + 实体解析

| 任务 | 文件/位置 | 工作量 |
|------|----------|--------|
| `FundingSourceClusterer`（Polygonscan API 入金地址） | intelligence/service | 2天 |
| `WalletEntityResolutionService`（查表 + fallback to self） | intelligence/service | 1天 |
| `WalletClusteringService`（聚类调度 + UnionFind） | intelligence/service | 1天 |
| 后台定时任务：每小时重跑未解析钱包的聚类 | Job | 1天 |

#### 第 3 周：链路改造 + 共识修复

| 任务 | 文件/位置 | 工作量 |
|------|----------|--------|
| `FollowCandidateEvaluationService`（实体级重复检测） | intelligence/service | 1天 |
| 改造 `SmartWalletFollowTaskService`：插入 enrich 中间步骤 | 现有文件 | 2天 |
| 改造 `PolymarketConsensusSignalService`：`distinctWallets` → `dedupedEntityCount` | 现有文件 | 1天 |
| 改造 `SmartWalletSignalRiskService`：`minDistinctWallets` → `minDistinctEntities` | 现有文件 | 1天 |
| 新增 `EntityAwareConsensusService.deduplicateByEntity()` | intelligence/service | 0.5天 |
| 流水线冒烟测试（模拟同实体 3 钱包触发场景） | test | 0.5天 |

**Phase 1 验收标准**：
- [ ] `wallet_entity_map` 表存在，且 `FundingSourceClusterer` 能自动写入共同入金地址的钱包对
- [ ] `SmartWalletFollowTaskService.recordTradeEvent` 流程中有 `wallet_follow_candidate` 写入
- [ ] `PolymarketConsensusSignalService` 的 `distinctWallets` 字段已替换为 `dedupedEntityCount`
- [ ] **关键验证**：构造测试场景（1个实体 3个钱包同时买入），确认 `dedupedEntityCount = 1` 而非 `3`
- [ ] `SmartWalletSignalRiskService` 的共识门控使用实体数，配置项名称为 `minDistinctEntities`

---

### Phase 2：实体特征增强 + 市场特征基础（4周）

> **目标：提升实体评分质量 + 建立市场维度的特征基础**

#### 实体侧（2周）

| 任务 | 模块 | 工作量 |
|------|------|--------|
| `BehaviorSimilarityClusterer`（交易行为相似度聚类） | 模块F | 3天 |
| `EntityFeatureSnapshotService`（合并同实体多钱包后重算指标） | 模块0 | 2天 |
| `SmartWalletScoringService.scoreEntity(entityId)` 方法 | 现有服务 | 1天 |
| 实体维度的 single-wallet override 增加 `clusterConfidence` 校验 | 模块0 | 1天 |
| API：`GET /intelligence/entity/{entityId}` 实体详情页 | Web | 0.5天 |
| API：`POST /intelligence/entity/merge` / `/split` 人工复核接口 | Web | 0.5天 |

#### 市场侧（2周）

| 任务 | 模块 | 工作量 |
|------|------|--------|
| `AVRCalculator` + `PriceJumpDetector` | 模块A | 3天 |
| `FreshWalletDetector`（基于实体首次交易时间，非地址） | 模块A | 1天 |
| `LiquidityGate`（Volume/Depth 最小阈值） | 模块E | 2天 |
| `market_feature_snapshot` 表 | 模块A | 0.5天 |
| 市场全量扫描定时任务（每15分钟） | 模块C | 1天 |

**Phase 2 验收标准**：
- [ ] 同实体多钱包的合并胜率/ROI 与单独计算结果有可对比的对比报告
- [ ] `FreshWalletDetector` 使用 `entity_id` 的首次交易时间（而非 wallet 地址）
- [ ] 低流动性市场被 `LiquidityGate.REJECT` 并在 `wallet_follow_candidate.rejection_reason` 中记录
- [ ] 市场扫描任务每 15 分钟写入 `market_feature_snapshot`

---

### Phase 3：信号评分 + 反洗盘 + 告警（3周）

> **目标：Alpha 信号闭环 + 信号质量提升 + 生产级监控**

| 任务 | 模块 | 工作量 |
|------|------|--------|
| 规则引擎 + 信号综合评分（基于实体级 HHI/FreshRatio） | 模块B | 4天 |
| `market_signal_record` 表 + 写入逻辑 | 模块B | 1天 |
| 洗盘自成交检测（SelfTradeDetector） | 模块D | 3天 |
| 高频对倒行为检测 | 模块D | 2天 |
| 有效成交量替换原始 AVR 计算 | 模块D | 1天 |
| 结算规则争议标记（RISKY 市场拒绝） | 模块G | 2天 |
| Telegram/Discord 告警（信号 + 仓位 + 系统健康） | 模块I | 2天 |

**Phase 3 验收标准**：
- [ ] 规则引擎对高 AVR + 高实体集中度（HHI）市场输出 HIGH/CRITICAL 信号
- [ ] 洗盘过滤后的有效成交量比原始成交量低（在已知洗盘案例上验证）
- [ ] 高分信号触发后 1 分钟内收到 Telegram 通知
- [ ] RISKY 市场（UMA dispute 历史）自动出现在 `wallet_follow_candidate.rejection_reason`

---

### Phase 4：策略扩展 + 回测 + 前端（4周）

> **目标：扩展独立策略，补全回测能力和运营工具**

| 任务 | 模块 | 工作量 |
|------|------|--------|
| 策略一：事件前异常交易（AVR信号独立触发） | 策略层 | 5天 |
| 策略二：价格收敛套利（体育/天气市场） | 策略层 | 5天 |
| 回测框架基础版（历史数据回放 + 指标计算） | 模块H | 7天 |
| 前端：实体图谱页面（实体 → 钱包列表 + 聚类置信） | 模块J | 3天 |
| 前端：市场扫描器页面（AVR/HHI 时序 + 信号历史） | 模块J | 3天 |

**Phase 4 验收标准**：
- [ ] 策略一可在 paper 模式下独立产生信号并创建 follow task
- [ ] 回测框架可跑通 30 天历史数据并输出胜率/Sharpe/最大回撤
- [ ] 前端实体图谱页可看到钱包 → 实体归属 + 聚类置信度

---

### Phase 5：链上接入 + 服务拆分（时机驱动，不定期）

> **仅当链上巨鲸监控真正接入时才执行此阶段**
> 此时 Entity Intelligence 已天然成为多策略复用的基础设施，可以独立成进程

| 任务 | 说明 |
|------|------|
| 链上地址桥接（入金来源追踪 Polygon/ETH） | 从链上交易记录补充 `FundingSourceClusterer` 数据 |
| Polymarket 账户 ↔ 链上地址打通 | 建立跨平台的 entity_id 统一视图 |
| `smartwallet/intelligence` 独立为微服务 | 作为独立的 Entity Intelligence Service 供多策略调用 |
| ML 模型化：Logistic Regression → XGBoost | 基于已积累的实体级标注数据训练 |
| 策略三：社交情绪 NLP | Twitter/Discord 情绪 × 实体交易行为联动 |

---

## 四、接口规约（关键 API 定义）

### 4.1 特征计算接口

```java
public interface MarketFeatureCalculator {
    /**
     * 计算指定市场的当前特征快照
     * @param marketId Polymarket 市场 ID
     * @return MarketFeatureSnapshot 特征快照
     */
    MarketFeatureSnapshot calculate(String marketId);
}

public record MarketFeatureSnapshot(
    String marketId,
    Instant snapshotTime,
    BigDecimal avr,              // 异常成交量比，正常=1.0
    BigDecimal priceJump,        // 最大价格跳变，0-1
    BigDecimal buySideRatio,     // 单边买入比例，0-1
    BigDecimal hhi,              // 持仓集中度，0-1
    BigDecimal freshWalletRatio, // 新钱包占比，0-1
    BigDecimal spread,           // ask-bid spread
    BigDecimal depthImbalance    // 深度不均衡度
) {}
```

### 4.2 信号评分接口

```java
public interface SignalScorer {
    /**
     * 对市场特征进行信号评分
     * @param snapshot 市场特征快照
     * @return SignalRecord 信号记录
     */
    SignalRecord score(MarketFeatureSnapshot snapshot);
}

public record SignalRecord(
    String marketId,
    Instant signalTime,
    BigDecimal score,            // 0-10 综合评分
    SignalLevel level,           // LOW/MED/HIGH/CRITICAL
    List<String> triggeredRules, // 触发的规则名称
    String suggestedDirection,   // YES/NO
    String rejectReason          // 被过滤时的原因
) {}

public enum SignalLevel {
    LOW,      // score 0-3, 观察
    MED,      // score 3-6, 关注
    HIGH,     // score 6-8, 可跟单
    CRITICAL  // score 8-10, 强信号
}
```

### 4.3 流动性门控接口

```java
public interface LiquidityGate {
    LiquidityCheckResult check(String marketId);
}

public record LiquidityCheckResult(
    GateStatus status,           // PASS/WARN/REJECT
    String reason,
    BigDecimal volume24h,
    BigDecimal bestBidDepth,
    BigDecimal bestAskDepth,
    BigDecimal spreadPct
) {}
```

### 4.4 洗盘过滤接口

```java
public interface WashTradeFilter {
    /**
     * 计算指定市场的有效成交量
     */
    EffectiveVolumeResult computeEffectiveVolume(String marketId, Instant from, Instant to);
    
    /**
     * 检测可疑洗盘交易
     */
    List<WashTradeFlag> detectWashTrades(String marketId, Instant from, Instant to);
}
```

---

## 五、数据模型汇总（新增表）

```sql
-- Phase 1 新增（Entity Intelligence 基础）
CREATE TABLE wallet_entity_map (...);              -- 地址 → 实体映射（含聚类置信度）
CREATE TABLE wallet_entity_feature_snapshot (...); -- 实体级合并特征快照
CREATE TABLE wallet_follow_candidate (...);        -- 候选跟单信号（enrich 结果）

-- Phase 2 新增
CREATE TABLE market_feature_snapshot (...);        -- 市场特征快照（AVR/Jump/Depth）

-- Phase 3 新增
CREATE TABLE market_signal_record (...);           -- 信号评分记录
CREATE TABLE wash_trade_flag (...);                -- 洗盘标记
CREATE TABLE resolution_risk (...);                -- 结算争议风险评级

-- Phase 4 新增
CREATE TABLE backtest_run (...);                   -- 回测任务
CREATE TABLE backtest_trade (...);                 -- 回测成交记录
CREATE TABLE backtest_metrics (...);               -- 回测指标结果

-- 现有表需新增字段
ALTER TABLE smart_wallet_profile
    ADD COLUMN entity_id VARCHAR(64),              -- 关联实体ID
    ADD COLUMN cluster_confidence DECIMAL(5,4),    -- 聚类置信度
    ADD INDEX idx_entity_id (entity_id);

ALTER TABLE smart_wallet_follow_task
    ADD COLUMN entity_id VARCHAR(64),              -- 触发实体ID
    ADD COLUMN follow_candidate_id BIGINT;         -- 关联候选信号ID

-- ConsensusSignal 视图或表需新增
-- distinctWallets → 保留（历史兼容）
-- dedupedEntityCount → 新增（真实共识强度）
-- hasSuspiciousMultiWallet → 新增（告警标志）
```

---

## 六、测试规约

### 6.1 单元测试要求

每个计算器（AVRCalculator/HHICalculator/等）必须有：
- 正常场景测试（正常市场 → 低分）
- 异常场景测试（异常市场 → 高分）
- 边界测试（空数据/单笔成交/零成交）

### 6.2 集成测试要求

每个定时任务必须有：
- 任务可正常完成的冒烟测试
- 关键指标写入数据库的验证

### 6.3 回测验证要求

信号策略上线前必须通过：
- 至少 30 天历史数据回测
- 胜率 ≥ 55%（or 优于基准）
- 最大回撤 ≤ 30%

---

## 七、注意事项与风险

### 实体层相关（P0 风险）

1. **聚类冷启动问题**：初始 `wallet_entity_map` 为空，所有钱包默认 `entity_id = wallet_address`。需在上线前对 watchlist 存量钱包做一次全量聚类预填充，否则 Phase 1 验收无效
2. **链上数据获取限制**：Polygonscan API 有免费配额限制，`FundingSourceClusterer` 需要实现请求限流 + 本地缓存，避免 API 超限
3. **聚类误判的影响**：错误地将两个独立 Insider 聚类为同一实体，会导致共识信号漏报。初期建议 `clusterConfidenceThreshold = 0.80`（仅高置信聚类生效），低置信映射仍视为独立实体
4. **现有共识门控的平滑升级**：`minDistinctWallets` 改为 `minDistinctEntities` 后，由于实体数 ≤ 地址数，部分原先能触发的信号会因门控变严而不触发。上线初期建议同步降低 `minDistinctEntities` 阈值（如从 3 降为 2），观察信号量变化后再调整

### 市场特征相关

5. **Polymarket API 限流**：全市场扫描需批量拉取数据，需实现请求队列 + 限流控制，避免封 IP
6. **链上数据延迟**：Polygon RPC 有延迟，特征计算时间戳对齐需特别处理
7. **历史数据获取**：AVR 需要 30 天历史成交量均值，需在服务启动时预填充历史数据

### 信号质量相关

8. **特征泄露风险**：回测时需严格避免使用未来数据（look-ahead bias）
9. **评分权重调优**：初期规则权重需通过历史验证迭代，不可固定不变
10. **洗盘误杀**：过激的洗盘过滤可能误杀真实信号，初期设置较低置信度阈值（`washConfidence < 0.70` 不过滤）

---

## 八、后续迭代方向

- **策略三（社交情绪）**：接入 Twitter Streaming API + NLP 情绪分类
- **做市策略**：基于订单簿深度不均实现自动做市
- **多市场组合**：投资组合层面的风险分散
- **用户订阅系统**：将信号对外开放，接入 SaaS 订阅模式（参考 PRODUCT_DESIGN.md）

---

## 九、决策记录（ADR）

### ADR-001：Entity Intelligence 优先于特征工程

**决策**：将 Wallet/Entity Intelligence 层定为 Phase 1 核心任务，特征工程后移到 Phase 2。

**背景**：架构审查发现共识信号的「地址膨胀」bug（一个实体 N 个钱包会被计算为 N 票共识），已在代码 `PolymarketConsensusSignalService` 中确认。

**理由**：
- 该 bug 会导致系统在假共识信号上下单，风险被严重低估
- HHI、FreshWalletRatio 等市场特征也依赖实体维度才有意义，先建实体层可以确保上层特征不失真
- 链上巨鲸接入后该层会自然演化为跨策略基础设施，早建成本低

**代价**：市场扫描器和信号评分延后约 3 周，接受。

---

### ADR-002：Entity Intelligence 不单独拆进程（Phase 1-4）

**决策**：在 `coin-strategy-job` 内新建 `smartwallet/intelligence` 包，不拆独立服务。

**理由**：
- 当前只有 Polymarket 跟单一个消费方，拆进程带来的网络开销和运维复杂度不值得
- 等链上巨鲸监控接入时（Phase 5），届时有多个消费方，拆服务的收益才能覆盖成本
- 包内接口已按 Service 边界设计，未来拆服务只需包装 RPC，无需重写逻辑

**代价**：内嵌在 job 模块中，部署耦合度较高，接受。

---

*文档版本 v1.1 — 下一步：按 Phase 1 任务清单逐项实现。每个阶段完成后更新各 Task 的状态标记。*

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
