# Polymarket 量化系统 — 规约编程计划文档

> 版本：v1.0 | 日期：2026-04-12
> 基于：`polymarket内幕分析.md` × 现有代码 Gap 分析

---

## 一、现状评估（已实现 vs 缺失）

### 1.1 架构层对照

| 文档提出 | 实现状态 | 备注 |
|---------|---------|------|
| 数据采集层（Gamma/CLOB/Data API/链上） | ✅ 已实现 | PolymarketApiClient + JavaClobClient |
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
| Smart Wallet 跟单 | ✅ 已实现 | 系统主策略 |

### 1.3 风险控制对照

| 文档提出 | 实现状态 | 备注 |
|---------|---------|------|
| 洗盘识别（自成交过滤） | ❌ 缺失 | 无有效成交量过滤 |
| 流动性阈值过滤 | ❌ 缺失 | 无深度/Volume 最小阈值 |
| 事件时间校准 | ❌ 缺失 | 无 resolution source 对齐 |
| 钱包聚类（多账户识别） | ⚠️ 基础 | 有 Clustering 概念但无实现 |
| 结算规则解析 | ⚠️ 部分 | 有 settlement sync，无规则争议标记 |
| 混淆因素控制（做市商/激励刷量） | ❌ 缺失 | 无对照组控制 |

---

## 二、模块改进规划

### 模块 A：特征工程层（Feature Engineering）

**目标**：为所有市场构建实时可计算的 Alpha 特征，支撑后续信号评分

**缺失内容**：
- `AVR`（异常成交量比）
- `Jump`（价格跳变检测）
- `HHI`（持仓集中度）
- `Fresh Wallet Ratio`（新钱包占比）
- `Wallet Clustering`（钱包关联网络）

**设计规约**：

```
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

```
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

```
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
```
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

```
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

```
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

### 模块 F：钱包聚类系统（Wallet Clustering）

**目标**：识别同一实体的多钱包行为，避免误判为多个独立 Insider

**缺失内容**：
- 资金来源分析（入金地址聚类）
- 行为相似度分析（交易时间、市场选择）
- 聚类图谱可视化

**设计规约**：

```
WalletCluster
├── FundingSourceAnalyzer
│   └── groupByDepositAddress()   // 链上追踪入金地址
├── BehaviorSimilarityAnalyzer
│   ├── tradeTimeCorrelation()    // 交易时间同步性
│   └── marketOverlapRatio()      // 市场选择重合度
├── ClusterGraphBuilder
│   └── UnionFind / Graph 算法构建聚类图
└── ClusterEntity                 // 聚类实体（代表一个真实交易者）
    ├── clusterId
    ├── walletAddresses []
    └── aggregatedMetrics         // 合并计算胜率/ROI
```

---

### 模块 G：结算规则解析与争议标记（Resolution Risk）

**目标**：识别规则模糊或存在争议风险的市场，提前规避结算不确定性

**缺失内容**：
- 市场规则 NLP 解析
- UMA Oracle dispute 状态跟踪
- 高争议风险市场标记

**设计规约**：

```
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

```
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

```
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

```
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

### Phase 1（核心基础，4周）

> 目标：构建特征计算 + 信号评分的基础闭环

| 任务 | 模块 | 优先级 | 预计工作量 |
|------|------|--------|-----------|
| AVR / PriceJump 计算器 | 模块A | P0 | 3天 |
| HHI / FreshWallet 计算器 | 模块A | P0 | 3天 |
| 规则引擎 + 信号评分 | 模块B | P0 | 4天 |
| 流动性过滤器 | 模块E | P0 | 2天 |
| market_feature_snapshot 表 | 模块A | P0 | 1天 |
| market_signal_record 表 | 模块B | P0 | 1天 |
| 市场全量扫描定时任务 | 模块C | P1 | 3天 |
| 市场扫描器 API 端点 | 模块C | P1 | 2天 |

**验收标准**：
- [ ] 能对任意 Polymarket 市场计算 AVR/HHI/Fresh 特征
- [ ] 规则引擎能输出 0-10 信号评分并写入数据库
- [ ] 低流动性市场能被自动过滤（Volume/Depth 阈值）
- [ ] 定时任务每 15 分钟扫描全市场并更新信号记录

---

### Phase 2（信号质量提升，3周）

> 目标：提升信号可靠性，减少假信号

| 任务 | 模块 | 优先级 | 预计工作量 |
|------|------|--------|-----------|
| 洗盘自成交检测 | 模块D | P0 | 4天 |
| 高频对倒行为检测 | 模块D | P0 | 3天 |
| 有效成交量替换原始成交量 | 模块D | P0 | 2天 |
| 钱包聚类基础实现（入金地址） | 模块F | P1 | 5天 |
| 结算规则争议标记 | 模块G | P1 | 3天 |
| 告警系统（Telegram/Discord） | 模块I | P1 | 3天 |

**验收标准**：
- [ ] 能识别 >= 80% 的自成交行为
- [ ] 同一实体多钱包能被聚类为一个 ClusterEntity
- [ ] 高争议市场自动标记 RISKY 并拒绝进入
- [ ] 高分信号触发时能在 1 分钟内发送 Telegram 通知

---

### Phase 3（策略扩展，4周）

> 目标：在 Smart Wallet 跟单基础上新增独立策略

| 任务 | 模块 | 优先级 | 预计工作量 |
|------|------|--------|-----------|
| 策略一：事件前异常交易完整实现 | 策略层 | P0 | 5天 |
| 策略二：价格收敛套利（体育/天气） | 策略层 | P1 | 5天 |
| 回测框架基础版 | 模块H | P1 | 7天 |
| 前端市场扫描器页面 | 模块J | P1 | 5天 |

**验收标准**：
- [ ] 策略一能独立产生信号并触发下单（paper模式）
- [ ] 回测框架能跑通 30 天历史数据
- [ ] 前端市场扫描器页面正常展示异常市场列表

---

### Phase 4（ML 模型化，持续）

> 目标：引入机器学习模型提升预测精度

| 任务 | 模块 | 优先级 |
|------|------|--------|
| 特征数据集标注（历史信号 + 结果） | 模块B | P1 |
| Logistic Regression 基线模型 | 模块B | P1 |
| XGBoost / LightGBM 模型 | 模块B | P2 |
| 模型在线推理服务接口 | 模块B | P2 |
| 策略三：社交媒体 NLP（Twitter/Discord） | 策略层 | P2 |

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
-- Phase 1 新增
CREATE TABLE market_feature_snapshot (...);  -- 市场特征快照
CREATE TABLE market_signal_record (...);     -- 信号记录

-- Phase 2 新增  
CREATE TABLE wash_trade_flag (...);          -- 洗盘标记
CREATE TABLE wallet_cluster (...);           -- 钱包聚类
CREATE TABLE resolution_risk (...);          -- 结算风险评级

-- Phase 3 新增
CREATE TABLE backtest_run (...);             -- 回测任务
CREATE TABLE backtest_trade (...);           -- 回测成交记录
CREATE TABLE backtest_metrics (...);         -- 回测指标结果
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

1. **Polymarket API 限流**：特征计算需批量拉取数据，需实现请求队列 + 限流控制，避免封 IP
2. **链上数据延迟**：Polygon RPC 有延迟，特征计算时间戳对齐需特别处理
3. **历史数据获取**：部分特征需要 30 天历史成交量均值，需预填充历史数据
4. **特征泄露风险**：回测时需严格避免使用未来数据（look-ahead bias）
5. **评分权重调优**：初期规则权重需通过历史验证迭代，不可固定不变
6. **洗盘误杀**：过激的洗盘过滤可能误杀真实信号，初期设置较低置信度阈值

---

## 八、后续迭代方向

- **策略三（社交情绪）**：接入 Twitter Streaming API + NLP 情绪分类
- **做市策略**：基于订单簿深度不均实现自动做市
- **多市场组合**：投资组合层面的风险分散
- **用户订阅系统**：将信号对外开放，接入 SaaS 订阅模式（参考 PRODUCT_DESIGN.md）

---

*文档结束 — 下一步：按 Phase 1 任务清单逐项实现，完成后更新本文档的实现状态*
