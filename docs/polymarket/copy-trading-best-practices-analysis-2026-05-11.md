# Polymarket 跟单策略最佳实践分析 & 服务偏差修复计划

> 日期：2026-05-11
> 来源：BV1tuRnBuEKn 视频要点 + Polymarket COPYCAT 文章 + monsterblockhk 工具指南
> 对比：CoinStrategy `SmartWalletFollowTaskService` / `SmartWalletSignalRiskService` / `SmartWalletProperties`

---

## 一、核心研究要点（细节注意点）

### 1. 数据陷阱

#### 1.1 PNL 计算偏差
- Polymarket 排行榜 PNL 仅统计**已平仓（realized）**收益，不包含当前持仓的浮动盈亏。
- 排名靠前的钱包可能有大量未平仓亏损仓位被隐藏。
- **关键指标**：应同时关注 realized PNL + unrealized PNL（通过持仓成本与当前价格计算）。

#### 1.2 套利机器人干扰
- 排行榜前列约 3.7% 的用户贡献了 37.44% 的交易量。
- 这些高频套利机器人的策略不可复制（依赖亚毫秒级执行速度 + API 直连）。
- **识别特征**：交易频次极高、单笔利润极低（< $0.01）、买卖对同时出现。

#### 1.3 高胜率 ≠ 高期望值
- 排行榜展示的 win rate 存在生存者偏差：只展示 top-N 已平仓的 positions。
- 样本量过小（< 50 笔）的 100% 胜率几乎必然是偏差导致的。
- **正确评估**：胜率 + PNL + 样本量 ≥ 300 markets 三维交叉验证。

### 2. 钱包筛选维度

| 维度 | 推荐阈值 | 说明 |
|------|----------|------|
| Win Rate + PNL | WR ≥ 55% 且 PNL > 0 | 双重约束，避免高胜率低收益 |
| 样本量 | ≥ 300 markets | 排除统计噪声和生存者偏差 |
| 持仓周期 | 关注中长线（> 48h） | 短线时序不可复制 |
| 利润结构 | 分散化、非单一爆款 | 单一大赢依赖运气而非 edge |

### 3. 高级规避技巧（Iceberging & Merging）

#### 3.1 Iceberging（冰山拆单）
- 高手将大单拆成多笔小单（$5-$50），在不同时间窗口分批执行。
- 目的：避免被跟单工具和链上监控发现，减少市场冲击成本。
- **对跟单方影响**：如果只监控单笔交易，可能误判信号强度。需要在时间窗口内聚合同地址同方向交易。

#### 3.2 Merging（合并退出）
- 同时持有 YES + NO shares，然后通过 merge 转换为 USDC 退出。
- 在链上体现为 "merge" 而非 "sell"，绕开常规的退出监控。
- **对跟单方影响**：仅监控 sell 信号会完全错过 merge 退出，导致持仓滞留。

### 4. 执行层面陷阱

#### 4.1 资金体量错配
- Leader 可能以 $10,000 开仓，follower 以 $50 跟单。
- 市场深度不同层级的滑点差异巨大（大户可能吃掉流动性后，小额跟单反而拿到差价）。
- **正确做法**：按比例（% of bankroll）而非固定金额跟单。

#### 4.2 时间延迟成本
- 从 leader 下单到 follower 检测 → 下单，通常有 15s-3min 延迟。
- 在快速变动市场（如选举结果公布），这个延迟可能导致价格已移动 5-15%。
- **正确做法**：设置 maxEntryPriceDrift，超过阈值自动放弃。

#### 4.3 退出同步盲区
- 跟单系统通常只关注 "入场信号"，忽略 leader 的退出行为。
- Leader 可能通过 merge、limit sell、或直接 transfer shares 退出，而 follower 持仓滞留。
- **正确做法**：持续监控 leader 持仓变化，设置独立的止损/止盈/时间窗口退出机制。

#### 4.4 品类特异性
- 某些钱包在特定品类（如地缘政治、体育）有 edge，但在其他品类（如加密货币）表现很差。
- **正确做法**：按品类过滤信号，或对品类维度建立独立的胜率/PNL 统计。

---

## 二、当前服务对照分析（偏差识别）

### ✅ 已覆盖（做得好的部分）

| 最佳实践 | 当前实现 | 位置 |
|----------|----------|------|
| 生存者偏差识别 | `blockUnreliableLeaderboardWinRate` + `unreliableWinRateThreshold=1.0` + `unreliableMaxSampleSize=50` | SignalRiskService Gate 2 |
| 最低样本量 | `minTotalTrades=25`（profile 级）+ `singleWalletOverrideMinTrades=30` | SmartWalletProperties |
| 胜率 + PNL + Score 三维筛选 | `minWinRate=0.62` + `minRealizedPnlUsdc=1000` + `minWalletScore=0.70` | SmartWalletProperties |
| 信号新鲜度门控 | `maxSignalAgeSeconds=3600` + 按 archetype 分层 | SignalRiskService Gate 5 |
| 价格漂移门控 | `maxEntryPriceDriftRate=0.05` + `entryTimingRiskService` | SignalRiskService Gate 8 |
| 止损/止盈 | `enableAutoStopLoss` + `enableAutoTakeProfit` + `TimeAwareStopLossService` + `ScoredExitSignalProvider` | Properties + 独立服务 |
| 时间窗口退出 | `enableAutoTimeWindowExit` + `autoTimeWindowExitHoursBeforeEnd=2` | Properties |
| 资金比例控制 | `bankrollUtilizationRate=0.03` + `maxSingleOrderCapitalRatio=0.01` + Kelly 公式 | Properties + KellyStakeCalculator |
| 持仓集中度门控 | `enableConcentrationGate` + CR_1/CR_3/CR_5/HHI 多维度 | Properties + MarketConcentrationService |
| 共识信号聚合 | `consensusWindowSeconds=180` + 实体去重 + 质量加权 | PolymarketConsensusSignalService |
| 行为画像分类 | WalletArchetypeClassifierService + WalletBehaviorProfileService | 多维分类 |
| 观察冷却机制 | `observeCooldownEnabled` + 近期胜率骤降触发冷却 | Properties + SignalRiskService |
| Leader 持仓验证 | `requireLeaderProfitableActivePosition=true` | SignalRiskService Gate 7 |

### ⚠️ 存在偏差或缺失的部分

#### 偏差 1：样本量阈值过低（当前 25/30，推荐 ≥ 300）

**现状**：`minTotalTrades=25`，`singleWalletOverrideMinTrades=30`。
**最佳实践**：推荐 ≥ 300 markets 的样本量才能可靠评估一个钱包。
**影响**：当前阈值下，25 笔交易的胜率波动极大（95% CI 约 ±20%），容易选入噪声钱包。
**缓解因素**：系统有 `walletScore` 综合评分和 `observeCooldownEnabled` 动态降级机制，部分补偿了低样本风险。但核心入选门槛仍偏低。

#### 偏差 2：未检测套利机器人（Arbitrage Bot）

**现状**：没有专门的套利机器人检测逻辑。虽然 `WalletArchetypeClassifierService` 做了行为分类，但未明确将高频套利 bot 识别并排除。
**最佳实践**：识别特征——交易频次极高（日均 > 50 笔）、单笔利润极低、买卖对同时出现。
**影响**：排行榜导入时可能误将 arb bot 纳入监控池，浪费信号评估资源且产生无法复制的信号。

#### 偏差 3：未监控 Merge 退出方式

**现状**：退出信号只监控 SELL 事件（`ExitSignalProvider` / `TimeAwareStopLossService` / `ScoredExitSignalProvider`）。没有对 leader 的 merge (YES + NO → USDC) 操作做链上监控。
**最佳实践**：merge 是高手常用的隐蔽退出方式，不产生 sell 信号。
**影响**：leader 通过 merge 静默退出后，follower 的仓位持续暴露在风险中，可能触发止损或持仓到过期。

#### 偏差 4：未识别 Iceberging（冰山拆单）

**现状**：共识信号的聚合窗口 `consensusWindowSeconds=180`（3 分钟）仅在**多钱包**维度聚合。对于单个钱包在较长时间窗口内分批执行的冰山单，每笔都被当作独立小额信号，可能因 `minTriggerSizeUsdc=5` 通过但被低估。
**最佳实践**：同地址 + 同方向 + 同 market 在 N 分钟窗口内的交易应聚合计算真实意图仓位。
**影响**：对冰山策略的信号强度误判，可能以极小仓位跟单一笔 $10 的冰山碎片，而实际 leader 意图是 $500 仓位。

#### 偏差 5：未按品类（Category）过滤信号

**现状**：信号评估不区分市场品类（政治、体育、加密货币、娱乐等）。所有品类走同一套风控门控。
**最佳实践**：某些钱包在特定品类有 edge 但在其他品类表现差。推荐按品类维度建立独立的胜率/PNL 统计并做品类过滤。
**影响**：跟单了一个在地缘政治有 edge 的钱包做加密货币市场的信号，可能大概率亏损。

#### 偏差 6：Unrealized PNL 未纳入钱包评估

**现状**：`SmartWalletProfileTruthRefreshService` 刷新 profile 时基于 `closedPositions` 计算 `realizedPnlUsdc`，但未计算当前持仓的浮动盈亏。
**最佳实践**：排行榜 PNL 仅含 realized，需同时考虑 unrealized 才能真实评估钱包质量。
**影响**：一个 realized PNL 很高但当前持有大量亏损仓位的钱包会被高估。

#### 偏差 7：资金体量错配检测不够精细

**现状**：有 `maxStakeToTriggerNotionalRatio=0.10`（跟单额 ≤ 触发额的 10%）和 `maxSingleOrderCapitalRatio=0.01`，但是是固定比例，未根据 leader 的总资金规模做动态调整。
**最佳实践**：应按 leader 资金体量对信号做分层——跟大户大信号 vs 跟小户小信号需要不同的仓位策略。
**缓解因素**：`KellyStakeCalculator` + `bankrollUtilizationRate` 在一定程度上实现了基于概率的动态定价。但缺少对 leader 本身资金规模的显式考量。

---

## 三、修复计划

### P0（高优先级 — 影响仓位安全）

#### Fix-1：Merge 退出监控

**目标**：检测 leader 的 merge 操作，及时触发退出信号。

**方案**：
1. 在 `SmartWalletTradeEventEntity` 的事件类型中新增 `MERGE` 类型。
2. 在链上事件监听（position sync 或 event scanner）中识别 merge 交易特征：同一钱包在同一 conditionId 下同时持有 YES + NO shares，然后 shares 减少但无 sell order。
3. 在 `ExitSignalProvider` 中新增规则：当 leader 的某 market 持仓发生 merge 减少时，视为等价 SELL 信号，触发 follower 退出评估。
4. 配置开关：`smart-wallet.signal-risk.enable-merge-exit-detection=true`。

**涉及文件**：
- `SmartWalletPositionSyncService.java` — 增加 merge 检测逻辑
- `ExitSignalProvider.java` / `ScoredExitSignalProvider.java` — 新增 merge 信号源
- `SmartWalletTradeEventEntity.java` — 事件类型扩展
- `SmartWalletProperties.java` — 新增配置项

**预计工作量**：3-5 天

#### Fix-2：Iceberging 聚合检测

**目标**：识别同地址在短时间窗口内对同一 market/outcome 的分批操作，聚合为单一意图信号。

**方案**：
1. 新增 `IcebergAggregationService`，在信号生成前扫描最近 N 分钟内同 wallet + market + outcome + side 的所有事件。
2. 如果在窗口内找到多笔同方向小额交易，聚合为一个 "composite event"，使用聚合后的总金额作为 `triggerSizeUsdc`。
3. 在 `createFollowTaskIfQualified()` 中调用聚合服务，用聚合结果替代单笔事件的 triggerSize。
4. 配置项：`smart-wallet.signal-risk.iceberg-aggregation-window-seconds=600`（10 分钟窗口）、`smart-wallet.signal-risk.iceberg-min-fragment-count=3`。

**涉及文件**：
- 新增 `IcebergAggregationService.java`
- `SmartWalletFollowTaskService.java` — 在 `createFollowTaskIfQualified` 中集成
- `SmartWalletProperties.SignalRisk` — 新增配置

**预计工作量**：2-3 天

### P1（中优先级 — 提升信号质量）

#### Fix-3：套利机器人检测与排除

**目标**：自动识别 arb bot 并标记为不可跟单。

**方案**：
1. 在 `WalletArchetypeClassifierService` 中新增 `ARBITRAGE_BOT` archetype。
2. 识别特征：
   - 日均交易 > 50 笔
   - 平均单笔利润 < $0.50
   - 买卖配对率 > 80%（同 market 短时间内有反向操作）
   - 持仓时间中位数 < 1 小时
3. 标记为 `ARBITRAGE_BOT` 的钱包自动设置 `followLane=OBSERVE_ONLY`。

**涉及文件**：
- `WalletArchetypeClassifierService.java` — 新增分类规则
- `WalletOrderStatsService.java` — 提供 bot 特征统计
- `SmartWalletProperties.SignalRisk` — 新增阈值配置

**预计工作量**：2 天

#### Fix-4：品类维度信号过滤

**目标**：按 market category 建立独立的胜率统计，支持品类维度的信号过滤。

**方案**：
1. 在 `SmartWalletMarketInfoEntity` 中确保 `category` / `tags` 字段已存在（来自 Gamma API 的 groupItemTitle 或 tags）。
2. 在 `WalletOrderStatsService` 中新增按 category 分组的统计逻辑。
3. 在 `SmartWalletProfileEntity` 或新增 `WalletCategoryStatsEntity` 中存储各品类的 winRate / PNL / sampleSize。
4. 在 `SmartWalletSignalRiskService` 中新增可选门控：如果 leader 在该信号品类的胜率低于阈值，降级或拒绝。
5. 配置项：`smart-wallet.signal-risk.enable-category-filter=false`（默认关闭，有数据后开启）。

**涉及文件**：
- `WalletOrderStatsService.java` — 新增按品类统计
- 新增 `WalletCategoryStatsEntity.java` + Mapper
- `SmartWalletSignalRiskService.java` — 新增 Gate
- `SmartWalletProperties.SignalRisk` — 新增配置

**预计工作量**：3-4 天

#### Fix-5：Unrealized PNL 纳入评估

**目标**：在 profile 刷新时同时计算当前持仓的浮动盈亏，作为辅助评估指标。

**方案**：
1. 在 `SmartWalletProfileTruthRefreshService` 刷新时，额外查询钱包当前 open positions。
2. 对每个 open position，通过 `SmartWalletMarketStateService` 获取当前 mid price，计算 unrealized PNL。
3. 将 `unrealizedPnlUsdc` 写入 profile 或单独表。
4. 在筛选时可以用 `realizedPnlUsdc + unrealizedPnlUsdc` 作为 "total PNL" 辅助参考（不替代 realized）。
5. 在 `blockerReasons` 中增加 warning：如果 unrealized PNL 占 realized PNL 的 -50% 以上，标记为 "[UNREALIZED_LOSS_WARNING]"。

**涉及文件**：
- `SmartWalletProfileTruthRefreshService.java` — 新增计算
- `SmartWalletProfileEntity.java` — 新增字段
- `SmartWalletSignalRiskService.java` — 可选 warning

**预计工作量**：2 天

### P2（低优先级 — 长期优化）

#### Fix-6：提高样本量阈值（渐进式）

**目标**：逐步提高 minTotalTrades 要求。

**方案**：
1. 短期：将 `minTotalTrades` 从 25 提升到 50（与 `unreliableMaxSampleSize` 对齐）。
2. 中期：引入分级准入——50-100 笔走 PAPER_CANARY 通道，100-200 笔走小仓 LIVE，200+ 走正常仓位。
3. 长期：当监控池足够大时，将可执行阈值提升到 200+。
4. 注意：不建议直接跳到 300（研究推荐值），因为当前监控池大小有限，过高阈值会导致无可跟钱包。

**涉及文件**：
- `SmartWalletProperties.java` — 调整默认值
- `SmartWalletSignalRiskService.java` — 可能需要分级逻辑

**预计工作量**：1 天（配置调整）+ 观察期

#### Fix-7：Leader 资金体量感知

**目标**：根据 leader 的总资金规模对信号权重和跟单仓位做动态调整。

**方案**：
1. 在 profile 刷新时估算 leader 的 "portfolio size"（open positions 总 cost + recent realized PNL）。
2. 引入 `leaderCapitalTier`：SMALL（< $5k）/ MEDIUM（$5k-$50k）/ LARGE（> $50k）。
3. 在 stake 计算时引入 tier 系数：LARGE leader 的 $500 信号和 SMALL leader 的 $500 信号（all-in）应有不同跟单策略。
4. SMALL leader 全仓信号应降低跟单比例（高风险行为），LARGE leader 的小额信号可能是试探性的，也应降低。

**涉及文件**：
- `SmartWalletProfileEntity.java` — 新增 estimatedPortfolioSize
- `SmartWalletProfileTruthRefreshService.java` — 计算逻辑
- `KellyStakeCalculator.java` — 引入 tier 系数

**预计工作量**：2-3 天

---

## 四、实施优先级总览

| 编号 | 修复项 | 优先级 | 工作量 | 风险等级 |
|------|--------|--------|--------|----------|
| Fix-1 | Merge 退出监控 | P0 | 3-5d | 高 — 直接影响仓位安全 |
| Fix-2 | Iceberging 聚合检测 | P0 | 2-3d | 中 — 影响信号准确性 |
| Fix-3 | 套利机器人检测 | P1 | 2d | 低 — 主要影响信号池纯度 |
| Fix-4 | 品类维度过滤 | P1 | 3-4d | 中 — 提升跟单精度 |
| Fix-5 | Unrealized PNL | P1 | 2d | 低 — 辅助评估指标 |
| Fix-6 | 样本量阈值提升 | P2 | 1d | 低 — 配置变更 |
| Fix-7 | Leader 资金体量感知 | P2 | 2-3d | 低 — 长期优化 |

**推荐实施顺序**：Fix-1 → Fix-2 → Fix-3 → Fix-5 → Fix-4 → Fix-6 → Fix-7

---

## 五、参考资料

1. Polymarket COPYCAT 报告 — Top 10 most copied wallets 分析，iceberging/merging 概念
2. monsterblockhk 工具指南 — 4 工具（Polymarket Analytics、Kreo、PolyGun、PolyHub）+ 3 数据陷阱 + 4 筛选维度 + 3 执行陷阱
3. 当前代码库 — `SmartWalletSignalRiskService`（12+ gates）、`SmartWalletFollowTaskService`（任务生成流水线）、`SmartWalletProperties`（配置体系）
