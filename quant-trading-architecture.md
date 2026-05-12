# 量化交易系统 - 技术架构设计文档

## 一、系统全景

本系统采用 **分层插件化架构**，将量化交易系统拆分为 **基础设施层 → 核心引擎层 → 策略层 → 接入层** 四个层级。每层通过明确定义的接口（SPI/API）解耦，策略以"插件"形式热插拔，交易所/数据源以"适配器"形式接入，实现"写一次策略，跑在任何市场"。

---

## 二、分层架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                      Presentation Layer                      │
│         Dashboard / Monitoring / Alert / REST API            │
├──────────────────────────────────────────────────────────────┤
│                       Strategy Layer                         │
│  ┌────────────┐ ┌──────────┐ ┌────────┐ ┌────────────────┐  │
│  │ Polymarket │ │ BTC/ETH  │ │ Weather│ │  Grid Trading  │  │
│  │ CopyTrade  │ │ Predict  │ │Predict │ │   Strategy     │  │
│  └────────────┘ └──────────┘ └────────┘ └────────────────┘  │
│  ┌────────────┐ ┌──────────┐ ┌─────────────────────────┐    │
│  │ CEX Funding│ │ Arbitrage│ │  ... Future Strategies  │    │
│  │ Rate Arb   │ │ Cross-Ex │ │                         │    │
│  └────────────┘ └──────────┘ └─────────────────────────┘    │
├──────────────────────────────────────────────────────────────┤
│                      Core Engine Layer                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │  Order    │ │  Risk    │ │ Position │ │  Event Bus    │  │
│  │  Engine   │ │  Engine  │ │ Manager  │ │  (Disruptor)  │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────┘  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ Data     │ │ Signal   │ │ Account  │ │  Scheduler    │  │
│  │ Engine   │ │ Engine   │ │ Manager  │ │               │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                    Infrastructure Layer                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ Exchange  │ │  Market  │ │ Database │ │  Config &     │  │
│  │ Adapters  │ │  Data    │ │  Layer   │ │  Discovery    │  │
│  │ (Gateway) │ │  Feed    │ │          │ │               │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## 三、各层详细设计

### 3.1 基础设施层 (Infrastructure Layer)

#### 3.1.1 Exchange Gateway — 交易所网关

统一抽象所有交易场所的交易接口，通过 **Adapter Pattern + SPI 机制** 实现热插拔。

```java
// 交易所统一接口 SPI
public interface ExchangeGateway {
    String exchangeId();
    
    // 行情
    Flux<OrderBook> subscribeOrderBook(Symbol symbol);
    Flux<Trade> subscribeTrades(Symbol symbol);
    Flux<Kline> subscribeKline(Symbol symbol, Interval interval);
    
    // 交易
    Mono<OrderResult> placeOrder(OrderRequest request);
    Mono<OrderResult> cancelOrder(String orderId);
    Mono<List<Order>> getOpenOrders(Symbol symbol);
    
    // 账户
    Mono<AccountBalance> getBalance();
    Mono<List<Position>> getPositions();
    
    // 资金费率 (CEX特有, 默认空实现)
    default Flux<FundingRate> subscribeFundingRate(Symbol symbol) {
        return Flux.empty();
    }
}
```

**已规划适配器:**

| 适配器 | 说明 | 协议 |
|--------|------|------|
| `PolymarketGateway` | Polymarket CLOB API | REST + WebSocket |
| `BinanceGateway` | Binance Spot/Futures | REST + WebSocket |
| `OkxGateway` | OKX Unified Account | REST + WebSocket |
| `BybitGateway` | Bybit V5 API | REST + WebSocket |
| `DeribitGateway` | Deribit Options | REST + WebSocket + FIX |

```java
// SPI 自动发现 — META-INF/services/ExchangeGateway
// 或使用 Spring Boot @ConditionalOnProperty 按配置激活
@Component
@ConditionalOnProperty(prefix = "exchange.polymarket", name = "enabled")
public class PolymarketGateway implements ExchangeGateway {
    // Polymarket 特有: CLOB API, CTF Token, Conditional Token Framework
    // 使用 web3j 与链上合约交互
}
```

#### 3.1.2 Market Data Feed — 行情数据引擎

```java
public interface MarketDataFeed {
    /**
     * 统一行情流 — 所有交易所的数据标准化后汇入同一条流
     */
    Flux<MarketEvent> subscribe(MarketDataRequest request);
    
    /**
     * 历史数据查询 — 用于回测和特征工程
     */
    Mono<List<Kline>> getHistoricalKlines(Symbol symbol, Interval interval, 
                                           Instant from, Instant to);
}
```

**行情标准化模型:**
```java
@Value
public class MarketEvent {
    String exchangeId;
    Symbol symbol;
    MarketEventType type;  // ORDERBOOK, TRADE, KLINE, FUNDING_RATE
    long exchangeTimestamp;
    long localTimestamp;    // 本地纳秒时间戳，用于延迟统计
    Object payload;        // OrderBook / Trade / Kline / FundingRate
}
```

#### 3.1.3 Database Layer — 存储层

| 存储引擎 | 用途 | 选型 |
|----------|------|------|
| 时序数据 | K线, Tick, 资金费率 | QuestDB / TimescaleDB |
| 交易数据 | 订单, 成交, 持仓 | PostgreSQL |
| 缓存 | 实时行情, 信号 | Redis (Lettuce) |
| 配置 | 策略参数, 交易所配置 | YAML + Nacos/Apollo |
| 日志 | 策略日志, 审计 | Elasticsearch |

#### 3.1.4 Config & Discovery — 配置中心

```yaml
# application-strategies.yml
strategies:
  polymarket-copy:
    enabled: true
    class: com.quant.strategy.polymarket.CopyTradeStrategy
    params:
      target-wallets:
        - "0xabc...123"
        - "0xdef...456"
      min-position-size: 100
      max-position-size: 10000
      follow-ratio: 0.5
      
  btc-5min-predict:
    enabled: true
    class: com.quant.strategy.predict.BtcEthPredictStrategy
    params:
      model-endpoint: "http://ml-service:8501/v1/models/btc_5min"
      symbols: ["BTC-USDT-PERP", "ETH-USDT-PERP"]
      interval: 5m
      confidence-threshold: 0.65
      
  funding-rate-arb:
    enabled: false
    class: com.quant.strategy.cex.FundingRateArbStrategy
    params:
      exchanges: ["binance", "okx"]
      min-spread: 0.001
```

---

### 3.2 核心引擎层 (Core Engine Layer)

#### 3.2.1 Event Bus — 事件总线 (系统神经中枢)

采用 **LMAX Disruptor** 实现超低延迟事件分发（单线程百万级/秒）。

```java
public interface EventBus {
    // 发布事件
    void publish(TradingEvent event);
    
    // 订阅事件
    <T extends TradingEvent> Disposable subscribe(
        Class<T> eventType, Consumer<T> handler);
    
    // 策略专属通道 — 隔离不同策略的事件流
    EventChannel createChannel(String strategyId);
}

// 核心事件类型
public sealed interface TradingEvent permits
    MarketDataEvent,    // 行情事件
    SignalEvent,        // 信号事件 (策略产出)
    OrderEvent,         // 订单事件 (下单/撤单/成交)
    RiskEvent,          // 风控事件 (告警/熔断)
    SystemEvent {}      // 系统事件 (启动/停止/健康检查)
```

#### 3.2.2 Signal Engine — 信号引擎

将策略产出的"交易意图"标准化为信号，再由 Order Engine 执行。

```java
@Value
public class TradingSignal {
    String strategyId;
    String signalId;         // UUID
    SignalType type;         // ENTRY / EXIT / ADJUST
    Symbol symbol;
    Direction direction;     // LONG / SHORT
    BigDecimal targetPrice;
    BigDecimal quantity;
    BigDecimal confidence;   // 0.0 ~ 1.0
    SignalMetadata metadata; // 策略自定义元数据
    Instant timestamp;
}
```

#### 3.2.3 Order Engine — 订单引擎

```java
public interface OrderEngine {
    /**
     * 接收信号 → 经过风控校验 → 拆单 → 路由到交易所
     */
    Mono<OrderResult> executeSignal(TradingSignal signal);
    
    /**
     * 智能订单类型
     */
    Mono<OrderResult> executeTWAP(TWAPParams params);
    Mono<OrderResult> executeIceberg(IcebergParams params);
}
```

**订单执行流程:**
```
Signal → RiskCheck → PositionCheck → OrderSplit → ExchangeRoute → Execution → Fill
          ↓              ↓              ↓             ↓              ↓
       Reject?      Adjust Size?    TWAP/Iceberg   Best Price    Notify Strategy
```

#### 3.2.4 Risk Engine — 风控引擎

```java
public interface RiskEngine {
    /**
     * 前置风控 — 下单前校验
     */
    RiskCheckResult preTradeCheck(TradingSignal signal);
    
    /**
     * 实时风控 — 持仓监控
     */
    void monitorPositions();
    
    /**
     * 熔断机制
     */
    void circuitBreak(String strategyId, String reason);
}
```

**风控规则 (可配置):**

```java
public class RiskRules {
    // 单策略维度
    BigDecimal maxPositionPerStrategy;     // 单策略最大持仓
    BigDecimal maxDailyLossPerStrategy;    // 单策略日最大亏损
    int maxOrdersPerMinute;                // 单策略每分钟最大下单数
    
    // 全局维度
    BigDecimal maxTotalExposure;           // 总敞口上限
    BigDecimal maxDrawdown;                // 最大回撤熔断线
    BigDecimal maxCorrelatedExposure;      // 相关性敞口控制
    
    // 交易所维度
    BigDecimal maxExposurePerExchange;     // 单交易所最大敞口
    Duration apiRateLimitWindow;           // API 频率限制
}
```

#### 3.2.5 Position Manager — 持仓管理

```java
public interface PositionManager {
    // 统一持仓视图 — 跨交易所合并
    Mono<PortfolioSnapshot> getPortfolioSnapshot();
    
    // 策略级持仓
    Mono<List<Position>> getPositionsByStrategy(String strategyId);
    
    // PnL 计算
    Mono<PnLReport> calculatePnL(String strategyId, TimeRange range);
    
    // 持仓对账 — 本地 vs 交易所
    Mono<ReconciliationResult> reconcile();
}
```

---

### 3.3 策略层 (Strategy Layer)

#### 3.3.1 策略统一接口 — Strategy SPI

**这是整个系统最关键的扩展点。** 所有策略实现此接口即可接入系统。

```java
public interface TradingStrategy extends Lifecycle {
    
    /** 策略唯一标识 */
    String strategyId();
    
    /** 策略类型 */
    StrategyType strategyType(); // SIGNAL, ARBITRAGE, MARKET_MAKING, COPY_TRADE
    
    /** 策略描述 */
    StrategyDescriptor descriptor();
    
    /** 初始化 — 注入依赖, 加载模型 */
    void initialize(StrategyContext context);
    
    /** 核心: 接收事件, 产出信号 */
    Flux<TradingSignal> onEvent(TradingEvent event);
    
    /** 参数热更新 */
    void onParamsUpdate(Map<String, Object> newParams);
    
    /** 健康检查 */
    HealthStatus healthCheck();
    
    /** 销毁 */
    void destroy();
}

/** 策略上下文 — 策略可用的所有"工具" */
public interface StrategyContext {
    MarketDataFeed marketData();
    OrderEngine orderEngine();
    PositionManager positionManager();
    StrategyStateStore stateStore();     // 策略状态持久化
    MetricsCollector metrics();          // 指标上报
    SchedulerService scheduler();        // 定时任务
    MLModelClient mlClient();            // ML 模型调用
    ExternalDataClient externalData();   // 外部数据 (天气/链上等)
}
```

#### 3.3.2 具体策略实现

**策略 1: Polymarket 跟单**

```java
@Strategy(id = "polymarket-copy", type = COPY_TRADE)
public class PolymarketCopyTradeStrategy implements TradingStrategy {
    
    private List<String> targetWallets;
    private PolymarketChainMonitor chainMonitor;
    
    @Override
    public void initialize(StrategyContext ctx) {
        // 1. 加载目标钱包列表
        // 2. 启动链上事件监控 (ERC1155 Transfer events)
        // 3. 初始化跟单比例计算器
        this.chainMonitor = new PolymarketChainMonitor(targetWallets);
    }
    
    @Override
    public Flux<TradingSignal> onEvent(TradingEvent event) {
        // 监听链上 Transfer 事件
        // → 解析目标钱包的买卖行为
        // → 计算跟单数量 (按比例 + 风控限制)
        // → 产出 TradingSignal
    }
}
```

**策略 2: BTC/ETH 5min 预测**

```java
@Strategy(id = "btc-5min-predict", type = SIGNAL)
public class BtcEthPredictStrategy implements TradingStrategy {
    
    private MLModelClient mlClient;
    private FeatureEngineService featureEngine;
    
    @Override
    public Flux<TradingSignal> onEvent(TradingEvent event) {
        if (event instanceof KlineCloseEvent kline && kline.interval() == FIVE_MIN) {
            return featureEngine.extractFeatures(kline)
                .flatMap(features -> mlClient.predict("btc_5min", features))
                .filter(prediction -> prediction.confidence() > threshold)
                .map(this::toTradingSignal);
        }
        return Flux.empty();
    }
}
```

**策略 3: 天气预测交易**

```java
@Strategy(id = "weather-predict", type = SIGNAL)
public class WeatherPredictStrategy implements TradingStrategy {
    
    private WeatherDataProvider weatherProvider;
    private WeatherMarketMapper marketMapper;
    
    @Override
    public void initialize(StrategyContext ctx) {
        // 接入气象 API (OpenWeather / NOAA)
        // 建立 天气事件 → Polymarket 市场 的映射关系
        this.weatherProvider = ctx.externalData().weather();
        this.marketMapper = new WeatherMarketMapper();
    }
    
    @Override
    public Flux<TradingSignal> onEvent(TradingEvent event) {
        // 定时拉取天气预测数据
        // → 计算天气事件概率
        // → 对比 Polymarket 当前价格
        // → 发现 mispricing → 产出信号
    }
}
```

**策略 4: Polymarket 网格交易**

```java
@Strategy(id = "polymarket-grid", type = MARKET_MAKING)
public class PolymarketGridStrategy implements TradingStrategy {
    
    private GridCalculator gridCalculator;
    
    @Override
    public Flux<TradingSignal> onEvent(TradingEvent event) {
        if (event instanceof OrderBookEvent ob) {
            // 根据当前价格计算网格
            // 检查是否触发网格线
            // 产出买/卖信号
            return gridCalculator.evaluate(ob)
                .filter(GridSignal::isTriggered)
                .map(this::toTradingSignal);
        }
        return Flux.empty();
    }
}
```

**策略 5: CEX 资金费率套利 (未来扩展)**

```java
@Strategy(id = "funding-rate-arb", type = ARBITRAGE)
public class FundingRateArbStrategy implements TradingStrategy {
    
    @Override
    public Flux<TradingSignal> onEvent(TradingEvent event) {
        if (event instanceof FundingRateEvent fr) {
            // 跨交易所资金费率比较
            // → 计算套利空间 (扣除手续费 + 滑点)
            // → 同时产出两个信号: 多头腿 + 空头腿
            return calculateArbOpportunity(fr)
                .flatMapMany(arb -> Flux.just(arb.longLeg(), arb.shortLeg()));
        }
        return Flux.empty();
    }
}
```

---

### 3.4 展示层 (Presentation Layer)

```java
// REST API — 策略管理
@RestController
@RequestMapping("/api/v1")
public class StrategyController {
    
    @GetMapping("/strategies")
    List<StrategyStatus> listStrategies();
    
    @PostMapping("/strategies/{id}/start")
    void startStrategy(@PathVariable String id);
    
    @PostMapping("/strategies/{id}/stop")
    void stopStrategy(@PathVariable String id);
    
    @PutMapping("/strategies/{id}/params")
    void updateParams(@PathVariable String id, @RequestBody Map<String, Object> params);
    
    @GetMapping("/portfolio/snapshot")
    PortfolioSnapshot getPortfolio();
    
    @GetMapping("/pnl/{strategyId}")
    PnLReport getPnL(@PathVariable String strategyId, TimeRange range);
}

// WebSocket — 实时推送
@ServerEndpoint("/ws/live")
public class LiveDataWebSocket {
    // 实时推送: 行情、持仓、PnL、风控告警
}
```

---

## 四、Maven 模块划分

```
quant-trading-system/
├── pom.xml                           (Parent BOM)
├── quant-common/                     (通用模型 + 工具)
│   ├── model/                        (Symbol, Order, Position, Signal...)
│   ├── util/                         (BigDecimal 工具, 时间工具)
│   └── exception/                    (统一异常体系)
│
├── quant-infra/                      (基础设施层)
│   ├── quant-exchange-api/           (ExchangeGateway SPI)
│   ├── quant-exchange-polymarket/    (Polymarket 适配器)
│   ├── quant-exchange-binance/       (Binance 适配器)
│   ├── quant-exchange-okx/           (OKX 适配器)
│   ├── quant-data-feed/              (行情引擎)
│   ├── quant-storage/                (存储层: QuestDB + PG + Redis)
│   └── quant-config/                 (配置中心)
│
├── quant-engine/                     (核心引擎层)
│   ├── quant-event-bus/              (Disruptor 事件总线)
│   ├── quant-order-engine/           (订单引擎)
│   ├── quant-risk-engine/            (风控引擎)
│   ├── quant-position-manager/       (持仓管理)
│   ├── quant-signal-engine/          (信号引擎)
│   └── quant-scheduler/              (调度器)
│
├── quant-strategy/                   (策略层)
│   ├── quant-strategy-api/           (TradingStrategy SPI)
│   ├── quant-strategy-polymarket/    (Polymarket 策略集)
│   │   ├── CopyTradeStrategy
│   │   ├── GridStrategy
│   │   └── WeatherPredictStrategy
│   ├── quant-strategy-predict/       (预测类策略)
│   │   └── BtcEthPredictStrategy
│   ├── quant-strategy-arb/           (套利类策略)
│   │   └── FundingRateArbStrategy
│   └── quant-strategy-backtest/      (回测框架)
│
├── quant-ml/                         (ML 模型服务)
│   ├── feature-engine/               (特征工程)
│   ├── model-serving/                (模型推理 gRPC Client)
│   └── model-training/              (训练 Pipeline, Python)
│
├── quant-app/                        (启动模块)
│   └── QuantTradingApplication.java  (Spring Boot Main)
│
└── quant-dashboard/                  (前端 Dashboard)
    └── (React + WebSocket)
```

---

## 五、技术选型

| 领域 | 选型 | 理由 |
|------|------|------|
| 框架 | Spring Boot 3.x + WebFlux | 响应式, 高并发 |
| 事件总线 | LMAX Disruptor | 纳秒级延迟 |
| 响应式 | Project Reactor | 与 WebFlux 天然集成 |
| WebSocket | Reactor Netty | 高性能 WS 客户端 |
| 时序库 | QuestDB | 写入快, SQL兼容 |
| 关系库 | PostgreSQL | 成熟可靠 |
| 缓存 | Redis (Lettuce) | 响应式 Redis 客户端 |
| ML推理 | TensorFlow Serving / gRPC | 模型热更新 |
| 区块链 | Web3j | Polymarket 链上交互 |
| 构建 | Maven (multi-module) | Java 生态标准 |
| 监控 | Prometheus + Grafana | 指标+可视化 |
| 日志 | ELK Stack | 全链路日志 |
| 容器化 | Docker + Docker Compose | 本地开发/部署 |

---

## 六、关键设计模式

### 6.1 策略热插拔

```java
@Component
public class StrategyRegistry {
    private final ConcurrentHashMap<String, TradingStrategy> strategies = new ConcurrentHashMap<>();
    
    // Spring SPI + 自定义注解扫描
    @PostConstruct
    public void autoDiscover() {
        ServiceLoader.load(TradingStrategy.class)
            .forEach(s -> register(s));
    }
    
    // 运行时注册新策略 (支持 ClassLoader 动态加载 jar)
    public void registerFromJar(Path jarPath) {
        URLClassLoader loader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()});
        ServiceLoader.load(TradingStrategy.class, loader)
            .forEach(this::register);
    }
}
```

### 6.2 回测复用

```java
// 同一套策略代码, 切换数据源即可回测
public class BacktestEngine {
    public BacktestResult run(TradingStrategy strategy, BacktestConfig config) {
        // 1. 构建模拟的 StrategyContext (Mock OrderEngine, 用历史数据 Feed)
        // 2. 回放历史事件
        // 3. 收集信号 + 模拟成交
        // 4. 计算回测指标 (Sharpe, MaxDD, Win Rate...)
    }
}
```

### 6.3 多策略协调

```java
// 策略间通过 Event Bus 通信, 但互不依赖
// Portfolio Optimizer 全局优化资金分配
@Component
public class PortfolioOptimizer {
    // 每个策略声明其 "理想仓位"
    // Optimizer 根据风险预算分配实际仓位
    public AllocationPlan optimize(List<StrategyAllocationRequest> requests) {
        // Kelly Criterion / Risk Parity / Equal Weight
    }
}
```

---

## 七、数据流全景

```
[Chain Monitor]──┐
[WS Binance]─────┤
[WS OKX]─────────┤──→ [MarketDataFeed] ──→ [Event Bus (Disruptor)]
[REST Weather]───┤                              │
[WS Polymarket]──┘                              │
                                                ↓
                                    ┌───────────────────────┐
                                    │    Strategy Router     │
                                    │  (按事件类型分发到策略)  │
                                    └───────────────────────┘
                                                │
                          ┌─────────────────────┼─────────────────────┐
                          ↓                     ↓                     ↓
                   [CopyTrade]           [BTC Predict]          [Grid Trading]
                          │                     │                     │
                          ↓                     ↓                     ↓
                    TradingSignal         TradingSignal         TradingSignal
                          │                     │                     │
                          └─────────────────────┼─────────────────────┘
                                                ↓
                                         [Risk Engine]
                                          ↓ Pass/Reject
                                    [Order Engine]
                                          ↓
                                ┌─────────┼─────────┐
                                ↓         ↓         ↓
                          [Polymarket] [Binance]  [OKX]
```

---

## 八、部署架构

```yaml
# docker-compose.yml (开发环境)
services:
  quant-app:
    build: ./quant-app
    ports: ["8080:8080"]
    depends_on: [questdb, postgres, redis]
    
  ml-serving:
    image: tensorflow/serving
    ports: ["8501:8501"]
    volumes: ["./models:/models"]
    
  questdb:
    image: questdb/questdb
    ports: ["9000:9000", "9009:9009"]
    
  postgres:
    image: postgres:16
    ports: ["5432:5432"]
    
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    
  grafana:
    image: grafana/grafana
    ports: ["3000:3000"]
    
  prometheus:
    image: prom/prometheus
    ports: ["9090:9090"]
```

---

## 九、扩展路线图

| 阶段 | 策略 | 优先级 |
|------|------|--------|
| P0 | Polymarket 跟单 + 网格 | 🔴 |
| P0 | BTC/ETH 5min 预测 | 🔴 |
| P1 | 天气预测交易 | 🟡 |
| P1 | CEX 资金费率套利 | 🟡 |
| P2 | 跨交易所三角套利 | 🟢 |
| P2 | 期权波动率套利 | 🟢 |
| P2 | DEX/CEX 价差套利 | 🟢 |
| P3 | 社交媒体情绪交易 | ⚪ |
| P3 | 链上 MEV 策略 | ⚪ |
