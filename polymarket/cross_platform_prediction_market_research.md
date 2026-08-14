---
title: "无 KYC 二元预测市场平台全景扫描 · 跨平台套利对手方评估"
type: research
status: active
tags: [prediction-market, no-kyc, cross-platform, arbitrage]
created: 2026-06-24
updated: 2026-06-24
---

# 无 KYC 二元预测市场平台全景扫描

> **目标**：排除 Kalshi（需 KYC）后，找到可与 Polymarket 构成跨平台二元套利对的去中心化预测市场平台。
> 评估维度：KYC 要求、市场类型、事件重叠度、API 可用性、流动性、结算机制。

---

## 一、平台全景图

### 第一梯队：与 Polymarket 事件重叠度高、流动性尚可

| 平台 | 链 | KYC | 市场类型 | 与 Polymarket 重叠 | 流动性 | API | 套利可行性 |
|------|-----|-----|---------|-------------------|--------|-----|-----------|
| **SX Bet (SX Network)** | SX Chain (EVM L2) | ❌ 无 KYC | 体育 + 加密 + 政治 | ⭐⭐⭐ 高 | 中等 | ✅ REST + WS | ⭐⭐⭐⭐ |
| **Azuro Protocol** | Polygon / Gnosis / Arbitrum / Base | ❌ 无 KYC | 体育 + 事件 | ⭐⭐⭐ 高 | 中等 | ✅ GraphQL (Subgraph) | ⭐⭐⭐ |
| **Overtime Markets (Thales)** | Optimism / Arbitrum / Base | ❌ 无 KYC | 体育 + 加密 | ⭐⭐ 中 | 中等 | ✅ SDK + Subgraph | ⭐⭐⭐ |
| **Drift BET** | Solana | ❌ 无 KYC | 加密事件 + 政治 | ⭐⭐ 中 | 低-中 | ✅ REST + SDK | ⭐⭐ |

### 第二梯队：可用但有限制

| 平台 | 链 | KYC | 市场类型 | 与 Polymarket 重叠 | 流动性 | API | 套利可行性 |
|------|-----|-----|---------|-------------------|--------|-----|-----------|
| **Hedgehog Markets** | Solana | ❌ 无 KYC | 加密 + 体育 + 事件 | ⭐⭐ 中 | 低 | 有限 | ⭐⭐ |
| **PlotX / Omen (Gnosis)** | Gnosis Chain | ❌ 无 KYC | 任意事件 | ⭐ 低 | 极低 | ✅ Subgraph | ⭐ |
| **Manifold Markets** | Web2 (play money) | ❌ 无 KYC | 任意事件 | ⭐⭐⭐ 高 | N/A (虚拟币) | ✅ REST | ❌ 非真实资金 |
| **Metaculus** | Web2 (reputation) | ❌ 无 KYC | 科学/政策/事件 | ⭐⭐ 中 | N/A (无交易) | ✅ REST | ❌ 非交易市场 |

### 已排除

| 平台 | 排除原因 |
|------|---------|
| **Kalshi** | 🔴 需要完整 KYC（SSN、身份证明），CFTC 合规要求 |
| **PredictIt** | 🔴 已关闭（CFTC 撤回 no-action letter） |
| **Betfair** | 🔴 需要 KYC + 地区限制 |
| **Smarkets** | 🔴 需要 KYC |

---

## 二、第一梯队详细分析

### 2.1 SX Bet（最推荐的套利对手方）

#### 概况
- **链**：SX Network（自有 EVM 兼容侧链，基于 Polygon Edge）
- **代币**：SX 代币
- **无 KYC**：仅需钱包连接
- **市场**：体育（NFL、NBA、MLB、足球、MMA）+ 加密事件 + 政治选举
- **订单簿模型**：类似 Polymarket 的 CLOB（不是 AMM）
- **结算**：链上 Oracle（Chainlink + 自有验证节点）

#### 与 Polymarket 重叠事件
- ✅ 美国大选 / 政治事件
- ✅ 加密价格事件（BTC/ETH 价格里程碑）
- ✅ 体育赛事（Polymarket 也在扩展体育市场）
- ✅ 文化事件（奥斯卡、Grammy 等）

#### API 结构
```
REST API: https://api.sx.bet/
WebSocket: wss://api.sx.bet/ws

关键端点：
GET /markets          -- 获取所有活跃市场
GET /markets/{id}     -- 市场详情
GET /orders/active    -- 当前挂单
POST /orders/new      -- 下单（EIP-712 签名）
POST /orders/cancel   -- 取消订单
GET /trades/history   -- 成交历史

签名方式：EIP-712（与 Polymarket 高度一致！）
抵押品：USDC (SX Network)
```

#### 套利逻辑
```
Polymarket: "Trump wins 2028 election" YES @ $0.42
SX Bet:     "Trump wins 2028 election" YES @ $0.38

套利：买 SX Bet YES @ $0.38 + 买 Polymarket NO @ $0.55
总成本：$0.93 → 无论结果，回收 $1.00 → 毛利 $0.07/对
```

#### 优势
- EIP-712 签名与 Polymarket 完全同类，复用你现有的签名框架
- CLOB 订单簿模型，下单逻辑类似
- 无 KYC，仅需钱包
- 事件重叠度高

#### 风险
- SX Network 是小型侧链，桥接安全性需评估
- 流动性不如 Polymarket（通常 1/10 到 1/5 的深度）
- 结算 Oracle 不同，存在结算分歧可能

---

### 2.2 Azuro Protocol

#### 概况
- **定位**：去中心化博彩/预测基础设施协议（类似 "prediction market 的 Uniswap"）
- **链**：多链部署（Polygon、Gnosis Chain、Arbitrum、Base）
- **无 KYC**：纯链上交互
- **模型**：流动性池模型（LP 提供流动性，用户对赌池下注）
- **事件**：体育为主（足球、NBA、NFL、网球、MMA）+ 部分政治/事件

#### 与 Polymarket 重叠
- ✅ 体育赛事（大量重叠）
- ✅ 部分政治事件
- ⚠️ 天气/加密价格事件较少

#### API 结构
```graphql
# Azuro 使用 The Graph (Subgraph) 做数据查询
# Subgraph endpoint: https://thegraph.com/hosted-service/subgraph/azuro-protocol/azuro-api-polygon-v3

query GetMarkets {
  conditions(where: { resolved: false }) {
    id
    conditionId
    outcomes {
      outcomeId
      currentOdds
    }
    game {
      title
      startsAt
      sport { name }
    }
  }
}

# 下注通过智能合约直接交互
# Core contract: LP.sol → bet(conditionId, outcomeId, amount, minOdds)
```

#### 套利逻辑
```
Polymarket: "Lakers win NBA Finals" YES @ $0.30
Azuro:      "Lakers win NBA Finals" YES (odds 4.0 → implied $0.25)

如果 Polymarket NO @ $0.65, Azuro YES @ $0.25:
总成本 $0.90 < $1.00 → 套利空间 $0.10
```

#### 优势
- 多链部署，可选择 gas 最优的链
- 体育事件覆盖广
- 完全去中心化，无 KYC
- Subgraph 查询方便，事件数据结构化好

#### 风险
- **非 CLOB 模型**：是池子对赌，不是限价单撮合。赔率由 LP 池动态调整，大额下注会显著移动赔率（高滑点）
- 体育事件的结算通常比 Polymarket 快（赛后分钟级），但定义可能有微差
- 流动性分散在多个池子

---

### 2.3 Overtime Markets (by Thales Protocol)

#### 概况
- **定位**：链上体育/事件二元市场，基于 Thales Protocol
- **链**：Optimism、Arbitrum、Base
- **无 KYC**：纯链上
- **模型**：AMM 定价（从 Chainlink/Pinnacle odds feed 获取初始定价）
- **事件**：NFL、NBA、NHL、MLB、足球、MMA、FIFA + 部分加密事件

#### 与 Polymarket 重叠
- ✅ 体育（核心强项）
- ✅ 加密价格事件（"BTC above $X on date Y"）
- ⚠️ 政治/天气较少

#### API 结构
```javascript
// Overtime 提供 SDK (overtime-markets-sdk)
// npm install @thalesmarket/overtime-sdk

import { OvertimeSDK } from '@thalesmarket/overtime-sdk';

const sdk = new OvertimeSDK({
  network: 'optimism', // or 'arbitrum', 'base'
});

// 获取市场
const markets = await sdk.markets.getActiveMarkets();

// 获取报价
const quote = await sdk.markets.getQuote({
  marketAddress: '0x...',
  position: 0, // 0=HOME, 1=AWAY, 2=DRAW
  amount: 100,
});

// 执行买入
const tx = await sdk.markets.buy({
  marketAddress: '0x...',
  position: 0,
  amount: 100,
  slippage: 0.02,
});
```

#### 套利逻辑
加密价格二元事件是跨平台套利的最佳标的：
```
Polymarket: "BTC above $80K on July 31, 2026" YES @ $0.55
Overtime:   "BTC above $80K on July 31, 2026" YES @ $0.48

买 Overtime YES $0.48 + 买 Polymarket NO $0.42 = $0.90
回收 $1.00 → 毛利 $0.10/对
```

#### 优势
- 加密价格事件与 Polymarket 高度重叠且结算源一致（Chainlink）
- L2 上 gas 极低
- SDK 成熟，集成方便

#### 风险
- AMM 模型意味着大单滑点高
- 单市场流动性有限（通常 $10K-$100K TVL per market）
- 需要核实结算参数是否与 Polymarket 完全一致（时间戳精度、价格源）

---

### 2.4 Drift BET (Drift Protocol)

#### 概况
- **定位**：Solana 上最大 DEX 之一 Drift 的预测市场分支
- **链**：Solana
- **无 KYC**：仅需 Solana 钱包
- **模型**：CLOB（与 Drift perps 共享 orderbook 引擎）
- **事件**：加密事件 + 政治 + 文化

#### 与 Polymarket 重叠
- ✅ 加密事件
- ✅ 政治选举
- ⚠️ 体育较少
- ⚠️ 市场数量远少于 Polymarket

#### API 结构
```typescript
// Drift SDK (TypeScript)
import { DriftClient, BET } from '@drift-labs/sdk';

const driftClient = new DriftClient({
  connection,
  wallet,
  env: 'mainnet-beta',
});

// 获取预测市场
const betMarkets = await driftClient.getBetMarkets();

// 下单
await driftClient.placeBetOrder({
  marketIndex: 0,
  direction: 'yes',
  amount: new BN(100 * 1e6), // USDC
  price: 0.55,
});
```

#### 优势
- Solana 的速度优势（400ms 出块）
- CLOB 模型与 Polymarket 相似
- Drift 本身流动性可观

#### 风险
- 预测市场是 Drift 的新业务线，市场数量少
- Solana 与 Polygon 跨链资金调度复杂
- 结算源可能不同

---

## 三、跨平台套利可行性矩阵

### 3.1 综合评分

| 平台 | 无 KYC | 事件重叠 | 流动性 | API 成熟度 | 结算一致性 | 综合评分 |
|------|--------|---------|--------|-----------|-----------|---------|
| **SX Bet** | ✅ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | **🥇 85/100** |
| **Azuro** | ✅ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | **🥈 72/100** |
| **Overtime** | ✅ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | **🥉 70/100** |
| **Drift BET** | ✅ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | 60/100 |
| **Hedgehog** | ✅ | ⭐⭐ | ⭐ | ⭐⭐ | ⭐⭐ | 45/100 |

### 3.2 推荐优先级

**第一选择：SX Bet**
- 理由：CLOB 模型 + EIP-712 签名 + 高事件重叠 + 合理流动性
- 集成成本：低（复用 Polymarket 签名框架）
- 预期套利频率：中等（每天可能 3-10 个可套利事件对）

**第二选择：Overtime Markets**
- 理由：加密价格事件结算源一致（Chainlink）+ SDK 成熟
- 集成成本：中等（需处理 AMM 滑点计算）
- 预期套利频率：低-中（加密价格事件时间窗口有限）

**第三选择：Azuro**
- 理由：体育事件大量重叠 + 多链部署
- 集成成本：中等（GraphQL + 智能合约交互）
- 预期套利频率：高（体育赛事频繁）但单笔利润小

---

## 四、技术实现路径

### 4.1 系统架构扩展

基于现有 CoinStrategy 的 `common-client` 模块，新增：

```
coin-strategy-common/common-client/
├── binance/          (已有)
├── okx/             (已有)
├── gate/            (已有)
├── polymarket/      (已有，CLOB client)
├── sxbet/           (新增 - 第一优先)
│   ├── SxBetApiService.java
│   ├── SxBetMarketDataService.java
│   ├── SxBetOrderService.java
│   └── model/
├── overtime/        (新增 - 第二优先)
│   ├── OvertimeContractService.java
│   ├── OvertimeMarketDataService.java
│   └── model/
└── azuro/           (新增 - 第三优先)
    ├── AzuroSubgraphService.java
    ├── AzuroContractService.java
    └── model/
```

### 4.2 核心模块设计

#### 事件匹配引擎（Market Matching Engine）

跨平台套利的核心难点：**如何确定两个平台上的市场是"同一个事件"？**

```java
public interface IMarketMatchingService {
    
    /**
     * 给定 Polymarket 的一个市场，查找其他平台上对应的市场
     */
    List<CrossPlatformMatch> findMatchingMarkets(String polymarketConditionId);
    
    /**
     * 判定两个市场是否指向同一事件
     * 需要比对：事件描述、结算时间、结算源、结算定义
     */
    MatchConfidence assessMatch(MarketInfo platformA, MarketInfo platformB);
}

public enum MatchConfidence {
    EXACT,      // 完全一致（同一 Oracle、同一定义）
    HIGH,       // 高度一致（相同事件，结算源不同但高度相关）
    MEDIUM,     // 中等（事件相同但定义有微差：时区、四舍五入）
    LOW,        // 低（可能是同一事件但不确定）
    NO_MATCH    // 不匹配
}
```

#### 跨平台套利检测器

```java
@Service
public class CrossPlatformArbitrageDetector {
    
    /**
     * 核心逻辑：
     * 平台A的 YES 价格 + 平台B的 NO 价格 < $1.00 → 存在套利
     * 或反向：平台A的 NO 价格 + 平台B的 YES 价格 < $1.00
     */
    public Optional<CrossPlatformArbitrageOpportunity> detect(
            MarketQuote polymarketQuote, 
            MarketQuote counterpartyQuote) {
        
        // 方向1：Polymarket YES + Counterparty NO
        BigDecimal costDirection1 = polymarketQuote.getBestAskYes()
            .add(counterpartyQuote.getBestAskNo());
        
        // 方向2：Polymarket NO + Counterparty YES
        BigDecimal costDirection2 = polymarketQuote.getBestAskNo()
            .add(counterpartyQuote.getBestAskYes());
        
        BigDecimal ONE = BigDecimal.ONE;
        BigDecimal minCost = costDirection1.min(costDirection2);
        
        if (minCost.compareTo(ONE) < 0) {
            BigDecimal grossProfit = ONE.subtract(minCost);
            BigDecimal fees = estimateTotalFees(polymarketQuote, counterpartyQuote);
            BigDecimal netProfit = grossProfit.subtract(fees);
            
            if (netProfit.compareTo(MIN_PROFIT_THRESHOLD) > 0) {
                return Optional.of(buildOpportunity(...));
            }
        }
        return Optional.empty();
    }
}
```

#### 执行协调器

```java
@Service
public class CrossPlatformExecutionCoordinator {
    
    /**
     * 跨平台双腿执行策略：
     * 1. 先执行流动性较差的一腿（通常是 counterparty）
     * 2. 确认成交后，立即执行 Polymarket 一腿
     * 3. 如果第二腿失败，评估是否需要平掉第一腿
     */
    public ExecutionResult executeArbitrage(CrossPlatformArbitrageOpportunity opp) {
        // Leg 1: 流动性较差的平台
        OrderResult leg1 = executeLeg(opp.getWeakerLeg());
        
        if (!leg1.isFilled()) {
            return ExecutionResult.aborted("Leg 1 failed to fill");
        }
        
        // Leg 2: Polymarket（流动性通常更好）
        OrderResult leg2 = executeLeg(opp.getStrongerLeg());
        
        if (!leg2.isFilled()) {
            // 紧急处理：尝试平掉 leg1 或接受方向性敞口
            return handleLeg2Failure(leg1, opp);
        }
        
        return ExecutionResult.success(leg1, leg2);
    }
}
```

### 4.3 结算风险管理

```java
/**
 * 跨平台结算分歧风险评估
 * 这是跨平台套利最大的风险：两个平台对同一事件判定不同
 */
@Service
public class SettlementDivergenceRiskService {
    
    public RiskLevel assessDivergenceRisk(CrossPlatformMatch match) {
        
        // 同一 Oracle 源 → 分歧风险极低
        if (match.sharesOracle()) {
            return RiskLevel.MINIMAL;
        }
        
        // 不同 Oracle 但定义完全一致 → 低风险
        if (match.getConfidence() == MatchConfidence.EXACT) {
            return RiskLevel.LOW;
        }
        
        // 定义有微差（时区、四舍五入等）→ 中等风险
        if (match.getConfidence() == MatchConfidence.HIGH) {
            return RiskLevel.MEDIUM;
        }
        
        // 其他情况 → 高风险，不建议套利
        return RiskLevel.HIGH;
    }
}
```

---

## 五、具体套利事件类型分析

### 5.1 最适合跨平台套利的事件类型

| 事件类型 | 推荐平台对 | 结算分歧风险 | 流动性 | 频率 |
|---------|-----------|------------|--------|------|
| **加密价格里程碑** | Polymarket × Overtime | 🟢 极低（都用 Chainlink） | 中等 | 中 |
| **体育赛事结果** | Polymarket × SX Bet / Azuro | 🟢 低（结果明确） | 高 | 高 |
| **美国政治选举** | Polymarket × SX Bet | 🟡 低-中 | 高 | 低（但单笔大） |
| **加密事件（上线/Fork）** | Polymarket × Drift BET | 🟡 中（定义可能不同） | 低 | 低 |
| **天气** | Polymarket × (无明确对手) | N/A | N/A | N/A |

### 5.2 体育赛事：最高频套利场景

体育赛事是跨平台套利最实际的场景：
- 结果明确无歧义（谁赢谁输）
- 频率高（每天数十场比赛）
- Polymarket 正在大力扩展体育市场
- SX Bet 和 Azuro 的核心就是体育

**示例流程**：
```
1. 扫描 Polymarket 体育市场 + SX Bet 体育市场
2. NLP 匹配同一场比赛（"Lakers vs Celtics Game 5" 双方）
3. 比较报价：
   - Polymarket: Lakers YES $0.58, NO $0.44
   - SX Bet: Lakers YES $0.52, NO $0.50
4. 发现：Polymarket NO ($0.44) + SX Bet YES ($0.52) = $0.96 < $1.00
5. 执行双腿下单 → 锁定 $0.04/对 毛利
```

---

## 六、资金管理与跨链策略

### 6.1 多平台资金预置

跨平台套利需要在每个平台预置资金：

```
总资金 $10,000 分配策略：
- Polymarket (Polygon USDC): $5,000 (50%) — 主要腿
- SX Bet (SX Network USDC): $2,500 (25%) — 体育/政治
- Overtime (Optimism USDC): $1,500 (15%) — 加密价格
- Azuro (Polygon USDC): $1,000 (10%) — 体育补充

跨链桥选项：
- Polygon ↔ Optimism: 官方桥 / Stargate / LayerZero
- Polygon ↔ SX Network: SX Bridge
- Polygon ↔ Arbitrum: 官方桥 / Hop Protocol
```

### 6.2 动态再平衡

```java
@Scheduled(fixedDelay = 300000) // 每5分钟
public void rebalanceFundsIfNeeded() {
    Map<Platform, BigDecimal> balances = getAllPlatformBalances();
    Map<Platform, BigDecimal> utilization = calculateUtilization(balances);
    
    // 如果某平台余额 < 预期的 30%，且另一平台余额 > 预期的 150%
    // 触发跨链转账
    for (Platform platform : Platform.values()) {
        if (utilization.get(platform) < 0.3) {
            Platform richest = findMostOverfunded(utilization);
            initiateBridgeTransfer(richest, platform, calculateTransferAmount(...));
        }
    }
}
```

---

## 七、实施建议与优先级

### Phase 1（1-2 周）：SX Bet 集成 + 事件匹配

1. 实现 `SxBetApiService`（EIP-712 签名复用 Polymarket 框架）
2. 拉取 SX Bet 活跃市场列表
3. 手动标注 10-20 个与 Polymarket 重叠的市场对
4. 验证报价差异是否存在套利空间

### Phase 2（2-4 周）：自动化套利检测 + Paper Trading

5. 实现 `CrossPlatformArbitrageDetector`
6. 实现事件匹配引擎（先用规则 + 关键词，后续可升级 NLP）
7. Paper trading 模式运行 1-2 周，统计套利机会频率和理论收益

### Phase 3（4-6 周）：Live Execution

8. 实现 `CrossPlatformExecutionCoordinator`
9. 结算风险管理模块
10. Live execution（小仓位起步）

### Phase 4（可选）：扩展到 Overtime + Azuro

11. 集成 Overtime SDK
12. 集成 Azuro Subgraph
13. 扩展事件匹配引擎覆盖范围

---

## 附录 A：各平台注册/接入流程

### SX Bet
1. 访问 sx.bet，连接 MetaMask
2. 将 USDC 桥接到 SX Network（通过 SX Bridge）
3. 获取 API key（如果有 programmatic access program）
4. 或直接通过智能合约交互

### Overtime
1. 访问 overtimemarkets.xyz，连接钱包
2. 切换到 Optimism/Arbitrum/Base 网络
3. 使用 `@thalesmarket/overtime-sdk` 集成
4. USDC 存入 Overtime 合约即可下注

### Azuro
1. 纯智能合约交互，无需注册
2. 通过 Subgraph 查询市场
3. 直接调用 LP 合约的 `bet()` 方法
4. 需要在对应链上有 USDC

---

## 附录 B：Polymarket 已知限制

注意 Polymarket 本身也有一些限制需要考虑：
- **美国用户限制**：Polymarket 不允许美国用户交易（但通过钱包连接，技术上无法强制）
- **最大订单限制**：部分市场有单笔下单上限
- **手续费**：Taker fee ~2%，需要计入套利成本
- **Gas**：Polygon 上 gas 极低但非零

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md)
