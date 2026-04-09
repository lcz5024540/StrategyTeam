# Polymarket对冲套利量化系统设计文档

## 目录
- [1. 系统概述](#1-系统概述)
- [2. 业务流程详解](#2-业务流程详解)
- [3. 结算换仓位核心流程](#3-结算换仓位核心流程)
- [4. Java架构设计](#4-java架构设计)
- [5. 技术选型与实现](#5-技术选型与实现)
- [6. 关键代码示例](#6-关键代码示例)

---

## 1. 系统概述

### 1.1 业务目标
构建自动化Polymarket预测市场套利系统，通过对冲策略在Yes/No双边建仓，利用市场定价偏差获取无风险收益。

### 1.2 核心特性
- 实时市场监控与套利机会识别
- 自动化双边下单执行
- 智能仓位管理与风险控制
- 自动结算与资金循环利用

---

## 2. 业务流程详解

### 2.1 完整套利流程

```
┌─────────────────────────────────────────────────────────────┐
│                    市场数据监控                              │
│  - 实时获取Polymarket事件赔率                                │
│  - 计算隐含概率 P_yes = 1/odds_yes                          │
│  - 多市场数据聚合                                            │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                  套利机会识别                                │
│  条件1: P_yes + P_no ≠ 100% (概率套利)                      │
│  条件2: 跨平台价差 > 阈值 (跨平台套利)                       │
│  条件3: 流动性充足 (单边深度 > 最小值)                       │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│               风险评估与仓位计算                             │
│  - 流动性检查: 订单簿深度分析                               │
│  - 滑点预估: 价格冲击模型                                    │
│  - Kelly公式: f* = (bp - q) / b                             │
│  - Delta中性: Q_yes * P_yes = Q_no * P_no                   │
└─────────────────────┬───────────────────────────────────────┘
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
┌──────────────────┐    ┌──────────────────┐
│   Yes端下单      │    │   No端下单       │
│ 价格: P_yes      │    │ 价格: P_no       │
│ 数量: Q_yes      │    │ 数量: Q_no       │
└────────┬─────────┘    └─────────┬────────┘
         │                        │
         └───────────┬────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                持仓管理与监控                                │
│  - 实时追踪对冲比例                                          │
│  - Delta中性检查                                             │
│  - 动态调仓 (可选)                                           │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                结算条件监听                                  │
│  监听字段: Market.resolved = true                            │
│  确认字段: Market.finalOutcome (Yes/No)                      │
│  轮询频率: 每5-10秒检查一次                                  │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
              ┌───────────────┐
              │ resolved=true?│
              └───┬───────┬───┘
                  │ 否    │ 是
                  ▼       ▼
        ┌──────────────┐  ┌──────────────────┐
        │ 继续监控持仓 │  │   领取结算        │
        └──────────────┘  │ claimWinnings()  │
                          └────────┬─────────┘
                                   ▼
                          ┌──────────────────┐
                          │ 仓位释放         │
                          │ 资金可开新单     │
                          └────────┬─────────┘
                                   │
                                   └──────► 循环到市场监控
```

### 2.2 各阶段详细说明

#### 阶段1: 市场数据监控
**数据源**
- WebSocket订阅实时价格更新
- REST API定期拉取订单簿深度
- GraphQL查询市场元数据

**关键指标**
```java
// 隐含概率计算
double impliedProbYes = 1.0 / oddsYes;
double impliedProbNo = 1.0 / oddsNo;
double totalProb = impliedProbYes + impliedProbNo;
```

#### 阶段2: 套利机会识别
**判断逻辑**
```java
// 套利条件
boolean isArbitrageOpportunity = 
    Math.abs(totalProb - 1.0) > THRESHOLD &&  // 概率偏差 > 阈值
    bidDepthYes > MIN_LIQUIDITY &&            // Yes端流动性充足
    bidDepthNo > MIN_LIQUIDITY;               // No端流动性充足
```

#### 阶段3: 风险评估
**Kelly公式应用**
```
f* = (bp - q) / b
其中:
- b = 赔率-1
- p = 获胜概率
- q = 失败概率 (1-p)
- f* = 最优仓位比例
```

**滑点预估模型**
```java
double expectedSlippage = orderSize / (liquidityDepth * DEPTH_FACTOR);
double adjustedPrice = marketPrice * (1 + expectedSlippage);
```

---

## 3. 结算换仓位核心流程

### 3.1 结算时机判断

#### 监听的接口与字段

**GraphQL查询接口**
```graphql
query GetMarketStatus($marketId: ID!) {
  market(id: $marketId) {
    id
    closed          # 布尔值: 市场是否关闭
    resolved        # 布尔值: 市场是否已结算
    finalOutcome    # 字符串: "Yes" | "No" | null
    resolutionTime  # 时间戳: 结算时间
  }
}
```

**REST API端点**
```
GET /markets/{marketId}

Response:
{
  "id": "0x123...",
  "closed": true,
  "resolved": true,
  "finalOutcome": "Yes",
  "resolutionTime": "2024-04-09T10:30:00Z"
}
```

#### 判断条件
```java
public boolean canSettle(Market market) {
    return market.isClosed() &&          // 必须已关闭
           market.isResolved() &&        // 必须已解决
           market.getFinalOutcome() != null;  // 必须有最终结果
}
```

### 3.2 结算执行者

#### 谁来领取结算
**主体**: 持仓用户（系统账户）

**方式**: 主动调用智能合约方法
```solidity
// Polymarket CTF合约
function claimWinnings(
    bytes32 conditionId,
    uint256[] calldata indexSets
) external;
```

**自动化实现**
```java
@Scheduled(fixedDelay = 10000) // 每10秒执行一次
public void checkAndClaim() {
    List<Position> settledPositions = positionTracker
        .getPositions()
        .stream()
        .filter(p -> canSettle(p.getMarket()))
        .collect(Collectors.toList());
    
    for (Position position : settledPositions) {
        claimService.claimWinnings(position);
    }
}
```

### 3.3 结算后仓位释放

#### 资金流转过程
```
结算前:
- 锁定资金: Yes仓位 $500 + No仓位 $500 = $1000
- 可用资金: $0

调用claimWinnings():
- 智能合约验证结算结果
- 赢家仓位按赔率返还资金
- 输家仓位归零

结算后:
- 锁定资金: $0
- 可用资金: $950 (扣除手续费后)
- 盈利: $950 - $1000 = -$50 (本例中亏损，实际套利应盈利)
```

#### 仓位释放检查
```java
public void onSettlementComplete(String marketId, BigDecimal claimedAmount) {
    // 1. 更新持仓状态
    positionTracker.closePosition(marketId);
    
    // 2. 更新可用余额
    BigDecimal newBalance = accountService.getAvailableBalance()
        .add(claimedAmount);
    accountService.updateBalance(newBalance);
    
    // 3. 触发新订单检查
    if (newBalance.compareTo(MIN_ORDER_SIZE) > 0) {
        orderManager.resumeTrading();
    }
}
```

### 3.4 开新单的条件

#### 前置检查
```java
public boolean canOpenNewOrder(BigDecimal requiredCapital) {
    // 1. 资金充足性
    BigDecimal available = accountService.getAvailableBalance();
    if (available.compareTo(requiredCapital) < 0) {
        return false;
    }
    
    // 2. 仓位数量限制
    int openPositions = positionTracker.getOpenPositionsCount();
    if (openPositions >= MAX_CONCURRENT_POSITIONS) {
        return false;
    }
    
    // 3. 风险敞口检查
    BigDecimal totalExposure = positionTracker.getTotalExposure();
    if (totalExposure.compareTo(MAX_RISK_EXPOSURE) > 0) {
        return false;
    }
    
    return true;
}
```

---

## 4. Java架构设计

### 4.1 分层架构总览

```
┌─────────────────────────────────────────────────────────┐
│                    接入层 (Gateway Layer)                │
│  ┌─────────────┐ ┌──────────────┐ ┌────────────────┐   │
│  │ WebSocket   │ │ RestApiClient│ │ GraphQLClient  │   │
│  │ Client      │ │              │ │                │   │
│  │ (Netty)     │ │ (OkHttp)     │ │ (Apollo)       │   │
│  └─────────────┘ └──────────────┘ └────────────────┘   │
└──────────────────────────┬──────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│               数据处理层 (Data Processing Layer)         │
│  ┌──────────────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │ MarketDataService│ │ EventListener│ │ DataRepo    │ │
│  │ - 实时行情订阅   │ │ - 市场状态   │ │ - InfluxDB  │ │
│  │ - 价格聚合       │ │ - 结算事件   │ │ - Redis     │ │
│  │ - Caffeine缓存   │ │ - Disruptor  │ │             │ │
│  └──────────────────┘ └──────────────┘ └─────────────┘ │
└──────────────────────────┬──────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│              策略引擎层 (Strategy Engine Layer)          │
│  ┌──────────────────┐ ┌─────────────┐ ┌──────────────┐ │
│  │ArbitrageDetector │ │RiskManager  │ │Position      │ │
│  │ - 概率差异检测   │ │ - 仓位限制  │ │Calculator    │ │
│  │ - 跨平台价差     │ │ - 流动性    │ │ - 最优仓位   │ │
│  │ - 阈值配置       │ │ - 滑点预估  │ │ - Delta中性  │ │
│  │ - RxJava响应式   │ │ - Kelly公式 │ │ - BigDecimal │ │
│  └──────────────────┘ └─────────────┘ └──────────────┘ │
└──────────────────────────┬──────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│           交易执行层 (Trading Execution Layer)           │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐  │
│  │OrderManager  │ │TradeExecutor │ │BlockchainClient│  │
│  │ - 订单路由   │ │ - 双边下单   │ │ - Web3j        │  │
│  │ - 状态机     │ │ - 并发控制   │ │ - 智能合约调用 │  │
│  └──────────────┘ └──────────────┘ └────────────────┘  │
└──────────────────────────┬──────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│         结算与仓位层 (Settlement & Position Layer)       │
│  ┌──────────────────┐ ┌────────────┐ ┌──────────────┐  │
│  │SettlementMonitor │ │ClaimService│ │PositionTracker│ │
│  │ - resolved轮询   │ │ - claim    │ │ - 实时持仓    │ │
│  │ - Scheduled任务  │ │ - Gas优化  │ │ - 资金可用性  │ │
│  └──────────────────┘ └────────────┘ └──────────────┘  │
└──────────────────────────┬──────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│            基础设施层 (Infrastructure Layer)             │
│  ┌────────┐ ┌──────────┐ ┌─────────┐ ┌──────────────┐  │
│  │ Config │ │ Monitor  │ │ Logging │ │ MessageQueue │  │
│  │ Apollo │ │Prometheus│ │ ELK     │ │ Kafka        │  │
│  └────────┘ └──────────┘ └─────────┘ └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 4.2 核心模块职责

#### 接入层 (Gateway Layer)
| 组件 | 职责 | 技术栈 |
|------|------|--------|
| WebSocketClient | 实时行情推送订阅 | Netty, Reactor-Netty |
| RestApiClient | HTTP请求封装与重试 | OkHttp, Retrofit |
| GraphQLClient | 市场元数据查询 | Apollo Client |

#### 数据处理层 (Data Processing Layer)
| 组件 | 职责 | 技术栈 |
|------|------|--------|
| MarketDataService | 多源数据聚合与标准化 | RxJava, Caffeine |
| EventListener | 异步事件处理 | Disruptor, EventBus |
| DataRepository | 历史数据存储与查询 | InfluxDB, Redis |

#### 策略引擎层 (Strategy Engine Layer)
| 组件 | 职责 | 技术栈 |
|------|------|--------|
| ArbitrageDetector | 套利机会识别算法 | 自定义算法 |
| RiskManager | 多维度风险控制 | 规则引擎 |
| PositionCalculator | 仓位优化计算 | BigDecimal精度运算 |

#### 交易执行层 (Trading Execution Layer)
| 组件 | 职责 | 技术栈 |
|------|------|--------|
| OrderManager | 订单生命周期管理 | 状态机模式 |
| TradeExecutor | 原子化交易执行 | CompletableFuture |
| BlockchainClient | 区块链交互 | Web3j |

#### 结算与仓位层 (Settlement & Position Layer)
| 组件 | 职责 | 技术栈 |
|------|------|--------|
| SettlementMonitor | 定时检查结算状态 | @Scheduled |
| ClaimService | 智能合约领取逻辑 | Web3j, Gas优化 |
| PositionTracker | 实时持仓追踪 | ConcurrentHashMap |

---

## 5. 技术选型与实现

### 5.1 核心技术栈

#### 高性能组件
```xml
<!-- Disruptor: 无锁并发框架 -->
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>3.4.4</version>
</dependency>

<!-- Caffeine: 高性能本地缓存 -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>

<!-- RxJava: 响应式编程 -->
<dependency>
    <groupId>io.reactivex.rxjava3</groupId>
    <artifactId>rxjava</artifactId>
    <version>3.1.8</version>
</dependency>
```

#### 区块链交互
```xml
<!-- Web3j: 以太坊Java客户端 -->
<dependency>
    <groupId>org.web3j</groupId>
    <artifactId>core</artifactId>
    <version>4.10.3</version>
</dependency>
```

#### 数据存储
```xml
<!-- InfluxDB: 时序数据库 -->
<dependency>
    <groupId>com.influxdb</groupId>
    <artifactId>influxdb-client-java</artifactId>
    <version>6.10.0</version>
</dependency>

<!-- Redisson: Redis客户端 -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.24.3</version>
</dependency>
```

### 5.2 设计模式应用

#### 状态机模式 - 订单管理
```java
public enum OrderState {
    CREATED,      // 已创建
    SUBMITTED,    // 已提交
    PARTIAL_FILLED, // 部分成交
    FILLED,       // 完全成交
    CANCELLED,    // 已取消
    FAILED        // 失败
}

public class OrderStateMachine {
    private final Map<OrderState, Set<OrderState>> allowedTransitions;
    
    public OrderStateMachine() {
        allowedTransitions = Map.of(
            OrderState.CREATED, Set.of(OrderState.SUBMITTED, OrderState.CANCELLED),
            OrderState.SUBMITTED, Set.of(OrderState.PARTIAL_FILLED, OrderState.FILLED, 
                                         OrderState.CANCELLED, OrderState.FAILED),
            OrderState.PARTIAL_FILLED, Set.of(OrderState.FILLED, OrderState.CANCELLED)
        );
    }
    
    public boolean canTransition(OrderState from, OrderState to) {
        return allowedTransitions.getOrDefault(from, Set.of()).contains(to);
    }
}
```

#### 策略模式 - 套利策略
```java
public interface ArbitrageStrategy {
    boolean detectOpportunity(MarketData data);
    PositionSize calculateSize(MarketData data, BigDecimal capital);
}

public class ProbabilityArbitrageStrategy implements ArbitrageStrategy {
    @Override
    public boolean detectOpportunity(MarketData data) {
        double totalProb = data.getImpliedProbYes() + data.getImpliedProbNo();
        return Math.abs(totalProb - 1.0) > THRESHOLD;
    }
    
    @Override
    public PositionSize calculateSize(MarketData data, BigDecimal capital) {
        // Kelly公式计算
        double edge = 1.0 - (data.getImpliedProbYes() + data.getImpliedProbNo());
        double kellyFraction = edge / data.getOddsYes();
        return new PositionSize(
            capital.multiply(BigDecimal.valueOf(kellyFraction))
        );
    }
}
```

#### 观察者模式 - 事件驱动
```java
public class MarketEventPublisher {
    private final Disruptor<MarketEvent> disruptor;
    
    public void publishSettlementEvent(String marketId, String outcome) {
        disruptor.publishEvent((event, sequence) -> {
            event.setType(EventType.SETTLEMENT);
            event.setMarketId(marketId);
            event.setOutcome(outcome);
        });
    }
}

@Component
public class SettlementEventHandler implements EventHandler<MarketEvent> {
    @Override
    public void onEvent(MarketEvent event, long sequence, boolean endOfBatch) {
        if (event.getType() == EventType.SETTLEMENT) {
            claimService.processSettlement(event.getMarketId());
        }
    }
}
```

---

## 6. 关键代码示例

### 6.1 结算监控服务

```java
@Service
@Slf4j
public class SettlementMonitorService {
    
    @Autowired
    private GraphQLClient graphQLClient;
    
    @Autowired
    private ClaimService claimService;
    
    @Autowired
    private PositionTracker positionTracker;
    
    // 每10秒检查一次
    @Scheduled(fixedDelay = 10000)
    public void checkSettlements() {
        List<Position> activePositions = positionTracker.getActivePositions();
        
        for (Position position : activePositions) {
            try {
                MarketStatus status = fetchMarketStatus(position.getMarketId());
                
                if (status.isResolved() && status.getFinalOutcome() != null) {
                    log.info("市场已结算: marketId={}, outcome={}", 
                        position.getMarketId(), status.getFinalOutcome());
                    
                    // 触发领取流程
                    claimService.claimWinnings(position);
                }
            } catch (Exception e) {
                log.error("检查结算状态失败: marketId={}", position.getMarketId(), e);
            }
        }
    }
    
    private MarketStatus fetchMarketStatus(String marketId) {
        String query = """
            query GetMarketStatus($marketId: ID!) {
              market(id: $marketId) {
                closed
                resolved
                finalOutcome
                resolutionTime
              }
            }
            """;
        
        Map<String, Object> variables = Map.of("marketId", marketId);
        return graphQLClient.execute(query, variables, MarketStatus.class);
    }
}
```

### 6.2 智能合约领取服务

```java
@Service
@Slf4j
public class ClaimService {
    
    @Autowired
    private Web3j web3j;
    
    @Autowired
    private Credentials credentials;
    
    private static final String CTF_CONTRACT_ADDRESS = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";
    
    public CompletableFuture<TransactionReceipt> claimWinnings(Position position) {
        try {
            // 加载CTF合约
            ConditionalTokens contract = ConditionalTokens.load(
                CTF_CONTRACT_ADDRESS,
                web3j,
                credentials,
                new DefaultGasProvider()
            );
            
            // 准备参数
            byte[] conditionId = Numeric.hexStringToByteArray(position.getConditionId());
            List<BigInteger> indexSets = List.of(
                BigInteger.valueOf(position.getIndexSet())
            );
            
            // 估算Gas
            BigInteger gasLimit = estimateGas(contract, conditionId, indexSets);
            
            // 执行领取
            return contract.redeemPositions(
                COLLATERAL_TOKEN,
                conditionId,
                indexSets
            ).sendAsync().thenApply(receipt -> {
                log.info("领取成功: txHash={}, marketId={}", 
                    receipt.getTransactionHash(), position.getMarketId());
                
                // 更新持仓状态
                positionTracker.markAsSettled(position.getMarketId());
                
                return receipt;
            });
            
        } catch (Exception e) {
            log.error("领取失败: marketId={}", position.getMarketId(), e);
            throw new RuntimeException("Claim failed", e);
        }
    }
    
    private BigInteger estimateGas(ConditionalTokens contract, 
                                    byte[] conditionId, 
                                    List<BigInteger> indexSets) {
        // Gas优化: 批量领取多个市场时使用更高的Gas limit
        return BigInteger.valueOf(300000);
    }
}
```

### 6.3 仓位追踪器

```java
@Component
@Slf4j
public class PositionTracker {
    
    private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();
    
    private final AtomicReference<BigDecimal> availableBalance = 
        new AtomicReference<>(BigDecimal.ZERO);
    
    public void addPosition(Position position) {
        positions.put(position.getMarketId(), position);
        
        // 更新可用余额
        BigDecimal locked = position.getYesAmount().add(position.getNoAmount());
        availableBalance.updateAndGet(balance -> balance.subtract(locked));
        
        log.info("新增持仓: marketId={}, locked={}, available={}", 
            position.getMarketId(), locked, availableBalance.get());
    }
    
    public void markAsSettled(String marketId) {
        Position position = positions.remove(marketId);
        if (position != null) {
            // 释放锁定资金
            BigDecimal released = position.getClaimedAmount();
            availableBalance.updateAndGet(balance -> balance.add(released));
            
            log.info("持仓已结算: marketId={}, released={}, available={}", 
                marketId, released, availableBalance.get());
        }
    }
    
    public List<Position> getActivePositions() {
        return new ArrayList<>(positions.values());
    }
    
    public BigDecimal getAvailableBalance() {
        return availableBalance.get();
    }
    
    public boolean hasAvailableCapital(BigDecimal required) {
        return availableBalance.get().compareTo(required) >= 0;
    }
    
    public int getOpenPositionsCount() {
        return positions.size();
    }
    
    public BigDecimal getTotalExposure() {
        return positions.values().stream()
            .map(p -> p.getYesAmount().add(p.getNoAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

### 6.4 套利检测器

```java
@Service
@Slf4j
public class ArbitrageDetector {
    
    @Value("${arbitrage.threshold:0.02}")
    private double threshold;
    
    @Value("${arbitrage.min-liquidity:1000}")
    private double minLiquidity;
    
    public Optional<ArbitrageOpportunity> detect(MarketData data) {
        // 计算隐含概率
        double probYes = 1.0 / data.getOddsYes();
        double probNo = 1.0 / data.getOddsNo();
        double totalProb = probYes + probNo;
        
        // 检查套利条件
        double probDeviation = Math.abs(totalProb - 1.0);
        if (probDeviation < threshold) {
            return Optional.empty();
        }
        
        // 检查流动性
        if (data.getYesLiquidity() < minLiquidity || 
            data.getNoLiquidity() < minLiquidity) {
            log.debug("流动性不足: yesLiq={}, noLiq={}", 
                data.getYesLiquidity(), data.getNoLiquidity());
            return Optional.empty();
        }
        
        // 计算预期收益
        BigDecimal expectedProfit = calculateExpectedProfit(data);
        
        log.info("发现套利机会: market={}, deviation={}, profit={}", 
            data.getMarketId(), probDeviation, expectedProfit);
        
        return Optional.of(ArbitrageOpportunity.builder()
            .marketId(data.getMarketId())
            .probDeviation(probDeviation)
            .expectedProfit(expectedProfit)
            .oddsYes(data.getOddsYes())
            .oddsNo(data.getOddsNo())
            .build());
    }
    
    private BigDecimal calculateExpectedProfit(MarketData data) {
        // 简化收益计算
        double probYes = 1.0 / data.getOddsYes();
        double probNo = 1.0 / data.getOddsNo();
        
        // 假设投入100单位
        double investment = 100.0;
        double yesInvest = investment * probNo;
        double noInvest = investment * probYes;
        
        // 计算期望回报
        double expectedReturn = Math.min(
            yesInvest * data.getOddsYes(),
            noInvest * data.getOddsNo()
        );
        
        return BigDecimal.valueOf(expectedReturn - investment);
    }
}
```

### 6.5 风险管理器

```java
@Service
@Slf4j
public class RiskManager {
    
    @Value("${risk.max-position-size:10000}")
    private BigDecimal maxPositionSize;
    
    @Value("${risk.max-positions:10}")
    private int maxPositions;
    
    @Value("${risk.max-exposure:50000}")
    private BigDecimal maxExposure;
    
    @Autowired
    private PositionTracker positionTracker;
    
    public RiskCheckResult checkRisk(BigDecimal proposedSize, MarketData data) {
        List<String> violations = new ArrayList<>();
        
        // 1. 单笔仓位限制
        if (proposedSize.compareTo(maxPositionSize) > 0) {
            violations.add("单笔仓位超限: " + proposedSize + " > " + maxPositionSize);
        }
        
        // 2. 持仓数量限制
        if (positionTracker.getOpenPositionsCount() >= maxPositions) {
            violations.add("持仓数量达到上限: " + maxPositions);
        }
        
        // 3. 总风险敞口限制
        BigDecimal currentExposure = positionTracker.getTotalExposure();
        BigDecimal newExposure = currentExposure.add(proposedSize);
        if (newExposure.compareTo(maxExposure) > 0) {
            violations.add("总敞口超限: " + newExposure + " > " + maxExposure);
        }
        
        // 4. 流动性检查
        double estimatedSlippage = calculateSlippage(proposedSize, data);
        if (estimatedSlippage > 0.05) { // 5%滑点阈值
            violations.add("预估滑点过大: " + (estimatedSlippage * 100) + "%");
        }
        
        // 5. 资金充足性
        if (!positionTracker.hasAvailableCapital(proposedSize)) {
            violations.add("可用资金不足");
        }
        
        boolean passed = violations.isEmpty();
        return new RiskCheckResult(passed, violations);
    }
    
    private double calculateSlippage(BigDecimal orderSize, MarketData data) {
        // 简化滑点模型: slippage = orderSize / (liquidity * 0.1)
        double avgLiquidity = (data.getYesLiquidity() + data.getNoLiquidity()) / 2;
        return orderSize.doubleValue() / (avgLiquidity * 0.1);
    }
    
    public BigDecimal calculateOptimalSize(ArbitrageOpportunity opportunity, 
                                           BigDecimal availableCapital) {
        // Kelly公式
        double edge = opportunity.getProbDeviation();
        double avgOdds = (opportunity.getOddsYes() + opportunity.getOddsNo()) / 2;
        double kellyFraction = edge / (avgOdds - 1);
        
        // 使用半Kelly降低风险
        kellyFraction *= 0.5;
        
        // 应用仓位上限
        BigDecimal kellySize = availableCapital.multiply(
            BigDecimal.valueOf(kellyFraction)
        );
        
        return kellySize.min(maxPositionSize);
    }
}
```

---

## 7. 部署与运维

### 7.1 配置管理

```yaml
# application.yml
polymarket:
  api:
    base-url: https://api.polymarket.com
    websocket-url: wss://ws-subscriptions-clob.polymarket.com/ws/market
    graphql-url: https://gamma-api.polymarket.com/query
  
  blockchain:
    rpc-url: https://polygon-rpc.com
    ctf-contract: "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E"
    private-key: ${PRIVATE_KEY}
  
  trading:
    max-position-size: 10000
    max-positions: 10
    max-exposure: 50000
    min-liquidity: 1000
  
  arbitrage:
    threshold: 0.02
    check-interval: 5000  # 5秒
  
  settlement:
    check-interval: 10000  # 10秒
    gas-price-gwei: 50
```

### 7.2 监控指标

```java
@Component
public class MetricsCollector {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public void recordArbitrageDetected(String marketId, double deviation) {
        meterRegistry.counter("arbitrage.detected", 
            "market", marketId).increment();
        
        meterRegistry.gauge("arbitrage.deviation", 
            Tags.of("market", marketId), deviation);
    }
    
    public void recordOrderExecuted(String side, BigDecimal amount) {
        meterRegistry.counter("order.executed", 
            "side", side).increment();
        
        meterRegistry.summary("order.amount", 
            "side", side).record(amount.doubleValue());
    }
    
    public void recordSettlement(String marketId, BigDecimal profit) {
        meterRegistry.counter("settlement.completed",
            "market", marketId).increment();
        
        meterRegistry.summary("settlement.profit").record(profit.doubleValue());
    }
}
```

### 7.3 告警规则

```yaml
# Prometheus告警规则
groups:
  - name: polymarket_alerts
    rules:
      - alert: HighSlippageDetected
        expr: arbitrage_slippage > 0.05
        for: 1m
        annotations:
          summary: "检测到高滑点: {{ $value }}%"
      
      - alert: SettlementFailed
        expr: rate(settlement_failures[5m]) > 0.1
        annotations:
          summary: "结算失败率过高"
      
      - alert: LowBalance
        expr: available_balance < 1000
        annotations:
          summary: "可用余额不足: {{ $value }}"
      
      - alert: PositionStuck
        expr: position_age_seconds > 86400
        annotations:
          summary: "持仓超过24小时未结算"
```

---

## 8. 安全与合规

### 8.1 私钥管理
- 使用硬件钱包或KMS服务存储私钥
- 多签钱包降低单点风险
- 定期轮换API密钥

### 8.2 资金安全
- 设置每日最大损失限额
- 异常交易自动熔断
- 定期资金审计

### 8.3 合规要求
- 遵守各地区法律法规
- KYC/AML检查
- 交易记录留存

---

## 9. 性能优化

### 9.1 延迟优化
- WebSocket长连接减少握手开销
- Caffeine本地缓存减少网络请求
- 批量处理降低API调用频率

### 9.2 并发优化
- Disruptor无锁队列提升吞吐
- CompletableFuture异步执行
- 连接池复用TCP连接

### 9.3 资源优化
- 定时清理过期缓存
- 限制线程池大小
- 数据库连接池配置

---

## 附录

### A. Polymarket API参考

**GraphQL查询示例**
```graphql
# 查询市场详情
query GetMarket($id: ID!) {
  market(id: $id) {
    id
    question
    outcomes
    closed
    resolved
    finalOutcome
    volume
    liquidity
  }
}

# 查询订单簿
query GetOrderBook($marketId: ID!) {
  orderBook(marketId: $marketId) {
    yes {
      price
      size
    }
    no {
      price
      size
    }
  }
}
```

### B. 智能合约接口

**CTF合约关键方法**
```solidity
// 赎回持仓
function redeemPositions(
    IERC20 collateralToken,
    bytes32 parentCollectionId,
    bytes32 conditionId,
    uint256[] calldata indexSets
) external;

// 查询余额
function balanceOf(
    address owner,
    uint256 positionId
) external view returns (uint256);
```

### C. 术语表

| 术语 | 说明 |
|------|------|
| CTF | Conditional Tokens Framework，条件代币框架 |
| Outcome | 市场结果，通常为Yes/No |
| Position | 持仓，代表对某一结果的押注 |
| Condition ID | 条件标识符，唯一标识一个市场条件 |
| Index Set | 索引集合，标识具体的结果组合 |
| Collateral | 抵押品，通常为USDC |
| Kelly Criterion | Kelly公式，计算最优仓位比例的数学方法 |
| Delta Neutral | Delta中性，对冲后总敞口为零 |

---

**文档版本**: v1.0  
**最后更新**: 2024-04-09  
**维护者**: Arbitrage Team
