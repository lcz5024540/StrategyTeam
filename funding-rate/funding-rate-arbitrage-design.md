# 跨交易所资金费套利系统详细设计方案

## 目录
1. [系统概述](#系统概述)
2. [架构设计](#架构设计)
3. [核心模块设计](#核心模块设计)
4. [接口调用设计](#接口调用设计)
5. [风控逻辑设计](#风控逻辑设计)
6. [数据库设计](#数据库设计)
7. [部署方案](#部署方案)

---

## 系统概述

### 设计目标
构建一个高可用、低延迟、风险可控的跨交易所资金费套利系统,支持:
- 多交易所实时监控(Binance, OKX, Bybit等)
- 智能信号生成与去噪
- 原子化对冲执行
- 多层级风控防护
- 完整的对账与审计

### 技术栈选型
- **后端**: Java 17 + Spring Boot 3.x
- **数据库**: PostgreSQL (事务) + TimescaleDB (时序数据)
- **缓存**: Redis (行情缓存 + 分布式锁)
- **消息队列**: Kafka (异步事件流)
- **监控**: Prometheus + Grafana
- **日志**: ELK Stack (Elasticsearch + Logstash + Kibana)

---

## 架构设计

### 总体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        外部系统层                                │
├─────────────┬─────────────┬─────────────┬──────────────────────┤
│ Binance API │  OKX API    │ Bybit API   │ On-chain Data        │
└──────┬──────┴──────┬──────┴──────┬──────┴──────┬───────────────┘
       │             │             │             │
       └─────────────┴─────────────┴─────────────┘
                         │
         ┌───────────────▼───────────────┐
         │    数据接入层 (Gateway)       │
         │  - REST/WebSocket 适配器      │
         │  - 限频控制 & 重试策略        │
         │  - 数据归一化                 │
         └───────────────┬───────────────┘
                         │
         ┌───────────────▼───────────────┐
         │      数据总线 (Kafka)         │
         │  - market.funding_rate        │
         │  - market.orderbook           │
         │  - market.trades              │
         │  - system.events              │
         └───────────────┬───────────────┘
                         │
        ┌────────────────┴────────────────┐
        │                                 │
┌───────▼───────┐              ┌─────────▼─────────┐
│  数据存储层    │              │   实时处理层       │
│  - PostgreSQL │              │  - 行情聚合        │
│  - TimescaleDB│              │  - 信号计算        │
│  - Redis      │              │  - 去噪/同步       │
└───────┬───────┘              └─────────┬─────────┘
        │                                │
        └────────────────┬───────────────┘
                         │
         ┌───────────────▼───────────────┐
         │       策略引擎层               │
         │  - 资金费差套利策略            │
         │  - 现货期货套利策略            │
         │  - 借贷利差策略                │
         └───────────────┬───────────────┘
                         │
         ┌───────────────▼───────────────┐
         │       风控引擎层               │
         │  - 入场风控检查                │
         │  - 持仓风控监控                │
         │  - 异常检测熔断                │
         └───────────────┬───────────────┘
                         │
         ┌───────────────▼───────────────┐
         │       执行引擎层               │
         │  - 订单拆分与路由              │
         │  - 原子化对冲执行              │
         │  - 成交确认与对账              │
         └───────────────┬───────────────┘
                         │
         ┌───────────────▼───────────────┐
         │     资金管理层                 │
         │  - 库存管理                    │
         │  - 转账编排                    │
         │  - 保证金监控                  │
         └───────────────┬───────────────┘
                         │
         ┌───────────────▼───────────────┐
         │   监控告警层                   │
         │  - 指标采集 (Prometheus)      │
         │  - 可视化 (Grafana)           │
         │  - 告警通知 (钉钉/邮件/SMS)    │
         └───────────────────────────────┘
```

### 分层职责说明

#### 1. 数据接入层 (Gateway Layer)
**职责**: 统一对接各交易所API,提供归一化数据流
**核心组件**:
- `ExchangeGatewayFactory`: 交易所网关工厂
- `BinanceGateway`, `OKXGateway`, `BybitGateway`: 具体交易所适配器
- `RateLimiter`: 限频控制器
- `RetryPolicy`: 重试策略管理器

#### 2. 实时处理层 (Stream Processing)
**职责**: 实时数据清洗、去噪、同步与特征工程
**核心组件**:
- `DataSynchronizer`: 多交易所数据时间对齐
- `SignalProcessor`: 信号预处理(去噪/滤波)
- `FeatureEngine`: 特征工程(资金费差/价差/深度等)
- `AnomalyDetector`: 异常检测

#### 3. 策略引擎层 (Strategy Engine)
**职责**: 策略逻辑实现与信号生成
**核心组件**:
- `FundingRateArbitrageStrategy`: 资金费差套利
- `SpotFuturesArbitrageStrategy`: 现货期货套利
- `BorrowLendArbitrageStrategy`: 借贷利差套利
- `StrategyOrchestrator`: 策略编排器

#### 4. 风控引擎层 (Risk Management)
**职责**: 多层级风控防护
**核心组件**:
- `PreTradeRiskChecker`: 入场前风控
- `PositionMonitor`: 持仓实时监控
- `LiquidationGuard`: 强平防护
- `CircuitBreaker`: 熔断器

#### 5. 执行引擎层 (Execution Engine)
**职责**: 订单智能拆分与原子化对冲执行
**核心组件**:
- `OrderRouter`: 订单路由器
- `SmartOrderSplitter`: 智能订单拆分
- `AtomicHedger`: 原子化对冲执行器
- `ExecutionReporter`: 成交确认与对账

#### 6. 资金管理层 (Fund Management)
**职责**: 跨交易所资金调度与保证金管理
**核心组件**:
- `InventoryManager`: 库存管理器
- `TransferOrchestrator`: 转账编排器
- `MarginMonitor`: 保证金监控器
- `WithdrawalStateMachine`: 提现状态机

---

## 核心模块设计

### 模块1: 数据接入层详细设计

#### 1.1 交易所网关接口定义

```java
/**
 * 交易所网关统一接口
 */
public interface ExchangeGateway {
    
    /**
     * 获取资金费率信息
     * @param symbol 交易对 (如 BTCUSDT)
     * @return 资金费率数据
     */
    FundingRateData getFundingRate(String symbol);
    
    /**
     * 获取历史资金费率
     * @param symbol 交易对
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 历史资金费率列表
     */
    List<FundingRateData> getFundingRateHistory(String symbol, long startTime, long endTime);
    
    /**
     * 获取盘口深度
     * @param symbol 交易对
     * @param limit 深度档位 (如5/10/20)
     * @return 订单簿数据
     */
    OrderBookData getOrderBook(String symbol, int limit);
    
    /**
     * 订阅实时资金费率 (WebSocket)
     * @param symbol 交易对
     * @param callback 回调函数
     */
    void subscribeFundingRate(String symbol, Consumer<FundingRateData> callback);
    
    /**
     * 订阅实时盘口 (WebSocket)
     * @param symbol 交易对
     * @param callback 回调函数
     */
    void subscribeOrderBook(String symbol, Consumer<OrderBookData> callback);
    
    /**
     * 下单
     * @param order 订单请求
     * @return 订单响应
     */
    OrderResponse placeOrder(OrderRequest order);
    
    /**
     * 批量下单
     * @param orders 订单列表
     * @return 订单响应列表
     */
    List<OrderResponse> batchPlaceOrders(List<OrderRequest> orders);
    
    /**
     * 撤单
     * @param orderId 订单ID
     * @param symbol 交易对
     */
    void cancelOrder(String orderId, String symbol);
    
    /**
     * 查询账户余额
     * @return 账户余额信息
     */
    AccountBalance getAccountBalance();
    
    /**
     * 查询持仓
     * @param symbol 交易对
     * @return 持仓信息
     */
    PositionData getPosition(String symbol);
    
    /**
     * 提现
     * @param request 提现请求
     * @return 提现响应
     */
    WithdrawResponse withdraw(WithdrawRequest request);
    
    /**
     * 查询提现状态
     * @param withdrawId 提现ID
     * @return 提现状态
     */
    WithdrawStatus getWithdrawStatus(String withdrawId);
}
```

#### 1.2 限频控制器设计

```java
/**
 * 限频控制器
 * 支持多种限频策略: 令牌桶、漏桶、滑动窗口
 */
public class RateLimiter {
    
    private final String exchange;
    private final String endpoint;
    private final int maxRequests;
    private final Duration window;
    private final Queue<Instant> requestTimes;
    private final Lock lock;
    
    public RateLimiter(String exchange, String endpoint, int maxRequests, Duration window) {
        this.exchange = exchange;
        this.endpoint = endpoint;
        this.maxRequests = maxRequests;
        this.window = window;
        this.requestTimes = new ConcurrentLinkedQueue<>();
        this.lock = new ReentrantLock();
    }
    
    /**
     * 尝试获取令牌
     * @return 是否成功
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            Instant now = Instant.now();
            Instant windowStart = now.minus(window);
            
            // 清理过期请求
            while (!requestTimes.isEmpty() && requestTimes.peek().isBefore(windowStart)) {
                requestTimes.poll();
            }
            
            // 检查是否超限
            if (requestTimes.size() < maxRequests) {
                requestTimes.offer(now);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 阻塞式获取令牌
     * @param timeout 超时时间
     * @return 是否成功
     */
    public boolean acquire(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (tryAcquire()) {
                return true;
            }
            Thread.sleep(100); // 退避等待
        }
        return false;
    }
}
```

#### 1.3 Binance网关实现示例

```java
@Component
@Slf4j
public class BinanceGateway implements ExchangeGateway {
    
    private final RestTemplate restTemplate;
    private final Map<String, RateLimiter> rateLimiters;
    private final String apiKey;
    private final String secretKey;
    
    // API端点限频配置 (参考Binance文档)
    private static final Map<String, RateLimitConfig> RATE_LIMITS = Map.of(
        "/fapi/v1/fundingRate", new RateLimitConfig(500, Duration.ofMinutes(5)),
        "/fapi/v1/depth", new RateLimitConfig(50, Duration.ofSeconds(1)),
        "/fapi/v1/order", new RateLimitConfig(300, Duration.ofMinutes(1)),
        "/sapi/v1/capital/withdraw/apply", new RateLimitConfig(10, Duration.ofMinutes(1))
    );
    
    @Override
    public FundingRateData getFundingRate(String symbol) {
        String endpoint = "/fapi/v1/premiumIndex";
        RateLimiter limiter = getRateLimiter(endpoint);
        
        try {
            // 限频控制
            if (!limiter.acquire(Duration.ofSeconds(5))) {
                throw new RateLimitExceededException("Rate limit exceeded for " + endpoint);
            }
            
            // 构建请求
            String url = "https://fapi.binance.com" + endpoint + "?symbol=" + symbol;
            
            // 发起请求(带重试)
            ResponseEntity<BinanceFundingRate> response = retryTemplate.execute(ctx -> 
                restTemplate.getForEntity(url, BinanceFundingRate.class)
            );
            
            // 转换为统一数据模型
            return convertToFundingRateData(response.getBody());
            
        } catch (Exception e) {
            log.error("Failed to fetch funding rate from Binance for {}", symbol, e);
            throw new ExchangeApiException("Binance API error", e);
        }
    }
    
    @Override
    public List<FundingRateData> getFundingRateHistory(String symbol, long startTime, long endTime) {
        String endpoint = "/fapi/v1/fundingRate";
        RateLimiter limiter = getRateLimiter(endpoint);
        
        try {
            if (!limiter.acquire(Duration.ofSeconds(5))) {
                throw new RateLimitExceededException("Rate limit exceeded");
            }
            
            String url = String.format(
                "https://fapi.binance.com%s?symbol=%s&startTime=%d&endTime=%d&limit=1000",
                endpoint, symbol, startTime, endTime
            );
            
            ResponseEntity<List<BinanceFundingRate>> response = retryTemplate.execute(ctx ->
                restTemplate.exchange(url, HttpMethod.GET, null, 
                    new ParameterizedTypeReference<List<BinanceFundingRate>>() {})
            );
            
            return response.getBody().stream()
                .map(this::convertToFundingRateData)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.error("Failed to fetch funding rate history from Binance", e);
            throw new ExchangeApiException("Binance API error", e);
        }
    }
    
    @Override
    public OrderBookData getOrderBook(String symbol, int limit) {
        String endpoint = "/fapi/v1/depth";
        RateLimiter limiter = getRateLimiter(endpoint);
        
        try {
            if (!limiter.acquire(Duration.ofSeconds(2))) {
                throw new RateLimitExceededException("Rate limit exceeded");
            }
            
            String url = String.format(
                "https://fapi.binance.com%s?symbol=%s&limit=%d",
                endpoint, symbol, limit
            );
            
            ResponseEntity<BinanceOrderBook> response = retryTemplate.execute(ctx ->
                restTemplate.getForEntity(url, BinanceOrderBook.class)
            );
            
            return convertToOrderBookData(response.getBody());
            
        } catch (Exception e) {
            log.error("Failed to fetch order book from Binance", e);
            throw new ExchangeApiException("Binance API error", e);
        }
    }
    
    @Override
    public OrderResponse placeOrder(OrderRequest order) {
        String endpoint = "/fapi/v1/order";
        RateLimiter limiter = getRateLimiter(endpoint);
        
        try {
            if (!limiter.acquire(Duration.ofSeconds(2))) {
                throw new RateLimitExceededException("Rate limit exceeded");
            }
            
            // 构建签名请求
            Map<String, String> params = buildOrderParams(order);
            String signature = generateSignature(params, secretKey);
            params.put("signature", signature);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", apiKey);
            
            String url = "https://fapi.binance.com" + endpoint;
            HttpEntity<Map<String, String>> request = new HttpEntity<>(params, headers);
            
            ResponseEntity<BinanceOrderResponse> response = retryTemplate.execute(ctx ->
                restTemplate.postForEntity(url, request, BinanceOrderResponse.class)
            );
            
            return convertToOrderResponse(response.getBody());
            
        } catch (Exception e) {
            log.error("Failed to place order on Binance", e);
            throw new ExchangeApiException("Binance order failed", e);
        }
    }
    
    private RateLimiter getRateLimiter(String endpoint) {
        return rateLimiters.computeIfAbsent(endpoint, ep -> {
            RateLimitConfig config = RATE_LIMITS.get(ep);
            return new RateLimiter("binance", ep, config.maxRequests, config.window);
        });
    }
    
    // 数据转换方法...
}
```

### 模块2: 信号处理引擎

#### 2.1 数据同步器设计

```java
/**
 * 多交易所数据时间对齐器
 * 将不同交易所的数据对齐到统一的结算窗口
 */
@Component
@Slf4j
public class DataSynchronizer {
    
    private final Map<String, FundingSettlement> settlementSchedules;
    
    /**
     * 对齐到下一个结算窗口
     */
    public SyncedMarketData alignToSettlement(List<MarketSnapshot> snapshots) {
        // 找到最近的公共结算时间
        Instant nextSettlement = findNextCommonSettlement(snapshots);
        
        // 按交易所分组
        Map<String, MarketSnapshot> byExchange = snapshots.stream()
            .collect(Collectors.toMap(MarketSnapshot::getExchange, s -> s));
        
        // 检查数据时效性
        Instant now = Instant.now();
        for (MarketSnapshot snapshot : snapshots) {
            Duration lag = Duration.between(snapshot.getTimestamp(), now);
            if (lag.toMillis() > 5000) { // 超过5秒视为过期
                log.warn("Stale data from {}: lag={}ms", snapshot.getExchange(), lag.toMillis());
            }
        }
        
        return SyncedMarketData.builder()
            .settlementTime(nextSettlement)
            .snapshots(byExchange)
            .syncTimestamp(now)
            .build();
    }
    
    /**
     * 查找下一个公共结算时间
     */
    private Instant findNextCommonSettlement(List<MarketSnapshot> snapshots) {
        // 获取每个交易所的下一次结算时间
        List<Instant> nextSettlements = snapshots.stream()
            .map(s -> {
                FundingSettlement schedule = settlementSchedules.get(s.getExchange());
                return schedule.getNextSettlement(s.getSymbol());
            })
            .collect(Collectors.toList());
        
        // 找到最近的公共时间(所有交易所都会结算的时间)
        return nextSettlements.stream()
            .max(Comparator.naturalOrder())
            .orElseThrow();
    }
}
```

#### 2.2 信号去噪与滤波

```java
/**
 * 信号处理器 - 去噪与滤波
 */
@Component
public class SignalProcessor {
    
    /**
     * Hampel滤波器 - 去除异常值
     */
    public List<Double> hampelFilter(List<Double> data, int windowSize, double nSigma) {
        List<Double> filtered = new ArrayList<>();
        
        for (int i = 0; i < data.size(); i++) {
            int start = Math.max(0, i - windowSize / 2);
            int end = Math.min(data.size(), i + windowSize / 2 + 1);
            List<Double> window = data.subList(start, end);
            
            // 计算中位数和MAD
            double median = calculateMedian(window);
            double mad = calculateMAD(window, median);
            
            double value = data.get(i);
            double zScore = Math.abs((value - median) / (1.4826 * mad));
            
            // 如果超过阈值,用中位数替换
            if (zScore > nSigma) {
                filtered.add(median);
                log.debug("Outlier detected at index {}: value={}, median={}", i, value, median);
            } else {
                filtered.add(value);
            }
        }
        
        return filtered;
    }
    
    /**
     * 指数移动平均(EMA)滤波
     */
    public List<Double> emaFilter(List<Double> data, double alpha) {
        List<Double> ema = new ArrayList<>();
        
        if (data.isEmpty()) return ema;
        
        ema.add(data.get(0)); // 第一个值直接使用
        
        for (int i = 1; i < data.size(); i++) {
            double value = alpha * data.get(i) + (1 - alpha) * ema.get(i - 1);
            ema.add(value);
        }
        
        return ema;
    }
    
    /**
     * Winsorize - 截尾处理
     */
    public List<Double> winsorize(List<Double> data, double lowerPercentile, double upperPercentile) {
        List<Double> sorted = new ArrayList<>(data);
        Collections.sort(sorted);
        
        int lowerIndex = (int) (data.size() * lowerPercentile);
        int upperIndex = (int) (data.size() * upperPercentile);
        
        double lowerBound = sorted.get(lowerIndex);
        double upperBound = sorted.get(upperIndex);
        
        return data.stream()
            .map(v -> Math.max(lowerBound, Math.min(upperBound, v)))
            .collect(Collectors.toList());
    }
    
    /**
     * 卡尔曼滤波 (简化版)
     */
    public List<Double> kalmanFilter(List<Double> data, double processNoise, double measurementNoise) {
        List<Double> filtered = new ArrayList<>();
        
        if (data.isEmpty()) return filtered;
        
        double estimate = data.get(0);
        double errorCovariance = 1.0;
        
        for (double measurement : data) {
            // 预测
            double predictedEstimate = estimate;
            double predictedErrorCovariance = errorCovariance + processNoise;
            
            // 更新
            double kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise);
            estimate = predictedEstimate + kalmanGain * (measurement - predictedEstimate);
            errorCovariance = (1 - kalmanGain) * predictedErrorCovariance;
            
            filtered.add(estimate);
        }
        
        return filtered;
    }
    
    private double calculateMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        return size % 2 == 0 
            ? (sorted.get(size/2 - 1) + sorted.get(size/2)) / 2.0
            : sorted.get(size/2);
    }
    
    private double calculateMAD(List<Double> values, double median) {
        List<Double> deviations = values.stream()
            .map(v -> Math.abs(v - median))
            .collect(Collectors.toList());
        return calculateMedian(deviations);
    }
}
```

#### 2.3 特征工程引擎

```java
/**
 * 特征工程引擎
 */
@Component
public class FeatureEngine {
    
    private final SignalProcessor signalProcessor;
    
    /**
     * 计算资金费差特征
     */
    public FundingDiffFeatures calculateFundingDiffFeatures(
            SyncedMarketData syncedData, 
            String symbolA, 
            String symbolB) {
        
        MarketSnapshot snapshotA = syncedData.getSnapshot("binance");
        MarketSnapshot snapshotB = syncedData.getSnapshot("okx");
        
        // 原始资金费差
        double rawDiff = snapshotA.getFundingRate() - snapshotB.getFundingRate();
        
        // 去噪后的资金费差 (使用历史数据)
        List<Double> historicalDiff = fetchHistoricalDiff(symbolA, symbolB, 100);
        List<Double> filtered = signalProcessor.hampelFilter(historicalDiff, 10, 3.0);
        double smoothedDiff = filtered.get(filtered.size() - 1);
        
        // Z-score标准化
        double mean = calculateMean(historicalDiff);
        double std = calculateStd(historicalDiff, mean);
        double zScore = (rawDiff - mean) / std;
        
        // 持续性指标 (自相关)
        double persistence = calculateAutocorrelation(historicalDiff, 1);
        
        return FundingDiffFeatures.builder()
            .rawDiff(rawDiff)
            .smoothedDiff(smoothedDiff)
            .zScore(zScore)
            .persistence(persistence)
            .mean(mean)
            .std(std)
            .build();
    }
    
    /**
     * 计算价差特征
     */
    public SpreadFeatures calculateSpreadFeatures(SyncedMarketData syncedData) {
        MarketSnapshot snapshotA = syncedData.getSnapshot("binance");
        MarketSnapshot snapshotB = syncedData.getSnapshot("okx");
        
        double priceA = snapshotA.getMarkPrice();
        double priceB = snapshotB.getMarkPrice();
        
        // 对数价差
        double logSpread = Math.log(priceA / priceB);
        
        // 价差ATR
        List<Double> historicalSpread = fetchHistoricalSpread(100);
        double atr = calculateATR(historicalSpread, 14);
        
        // 价差Z-score
        double mean = calculateMean(historicalSpread);
        double std = calculateStd(historicalSpread, mean);
        double zScore = (logSpread - mean) / std;
        
        return SpreadFeatures.builder()
            .logSpread(logSpread)
            .atr(atr)
            .zScore(zScore)
            .build();
    }
    
    /**
     * 计算流动性特征
     */
    public LiquidityFeatures calculateLiquidityFeatures(MarketSnapshot snapshot, double targetQty) {
        OrderBookData orderBook = snapshot.getOrderBook();
        
        // 计算买一卖一点差
        double spread = orderBook.getAskPrice(0) - orderBook.getBidPrice(0);
        double midPrice = (orderBook.getAskPrice(0) + orderBook.getBidPrice(0)) / 2.0;
        double spreadBps = (spread / midPrice) * 10000;
        
        // 计算深度(目标数量对应的VWAP)
        double buyVWAP = calculateVWAP(orderBook.getAsks(), targetQty);
        double sellVWAP = calculateVWAP(orderBook.getBids(), targetQty);
        
        // 计算冲击成本
        double buyImpact = ((buyVWAP - midPrice) / midPrice) * 10000; // bps
        double sellImpact = ((midPrice - sellVWAP) / midPrice) * 10000;
        
        return LiquidityFeatures.builder()
            .spreadBps(spreadBps)
            .buyVWAP(buyVWAP)
            .sellVWAP(sellVWAP)
            .buyImpact(buyImpact)
            .sellImpact(sellImpact)
            .build();
    }
    
    /**
     * 计算VWAP
     */
    private double calculateVWAP(List<PriceLevel> levels, double targetQty) {
        double totalValue = 0.0;
        double totalQty = 0.0;
        
        for (PriceLevel level : levels) {
            double qty = Math.min(level.getQuantity(), targetQty - totalQty);
            totalValue += level.getPrice() * qty;
            totalQty += qty;
            
            if (totalQty >= targetQty) break;
        }
        
        return totalQty > 0 ? totalValue / totalQty : 0.0;
    }
    
    /**
     * 计算ATR
     */
    private double calculateATR(List<Double> prices, int period) {
        List<Double> trueRanges = new ArrayList<>();
        
        for (int i = 1; i < prices.size(); i++) {
            double high = Math.max(prices.get(i), prices.get(i-1));
            double low = Math.min(prices.get(i), prices.get(i-1));
            double tr = high - low;
            trueRanges.add(tr);
        }
        
        // 取最后period个的平均
        int start = Math.max(0, trueRanges.size() - period);
        List<Double> recent = trueRanges.subList(start, trueRanges.size());
        return calculateMean(recent);
    }
    
    // 统计计算辅助方法...
}
```

### 模块3: 策略引擎设计

```java
/**
 * 资金费差套利策略
 */
@Component
@Slf4j
public class FundingRateArbitrageStrategy implements Strategy {
    
    private final FeatureEngine featureEngine;
    private final RiskController riskController;
    private final ExecutionEngine executionEngine;
    private final StrategyConfig config;
    
    @Override
    public void onMarketData(SyncedMarketData marketData) {
        String symbol = "BTCUSDT";
        
        // 1. 计算特征
        FundingDiffFeatures fundingFeatures = featureEngine.calculateFundingDiffFeatures(
            marketData, "binance", "okx");
        
        SpreadFeatures spreadFeatures = featureEngine.calculateSpreadFeatures(marketData);
        
        MarketSnapshot binanceSnapshot = marketData.getSnapshot("binance");
        MarketSnapshot okxSnapshot = marketData.getSnapshot("okx");
        
        LiquidityFeatures binanceLiquidity = featureEngine.calculateLiquidityFeatures(
            binanceSnapshot, config.getTargetQty());
        LiquidityFeatures okxLiquidity = featureEngine.calculateLiquidityFeatures(
            okxSnapshot, config.getTargetQty());
        
        // 2. 计算期望净收益
        double expectedReturn = calculateExpectedReturn(
            fundingFeatures, spreadFeatures, binanceLiquidity, okxLiquidity);
        
        // 3. 决策逻辑
        if (shouldOpenPosition(fundingFeatures, spreadFeatures, expectedReturn)) {
            // 开仓
            openPosition(binanceSnapshot, okxSnapshot, fundingFeatures);
        } else if (shouldClosePosition(fundingFeatures, spreadFeatures)) {
            // 平仓
            closePosition();
        }
    }
    
    /**
     * 计算期望净收益
     */
    private double calculateExpectedReturn(
            FundingDiffFeatures fundingFeatures,
            SpreadFeatures spreadFeatures,
            LiquidityFeatures liquidityA,
            LiquidityFeatures liquidityB) {
        
        // 资金费差现金流
        double fundingCashflow = fundingFeatures.getRawDiff() * config.getNotional();
        
        // 手续费成本
        double feeCost = calculateFeeCost(liquidityA, liquidityB);
        
        // 滑点成本
        double slippageCost = calculateSlippageCost(liquidityA, liquidityB);
        
        // 借贷成本 (如适用)
        double borrowCost = 0.0; // TODO: 从借贷利率计算
        
        // 转账成本 (如需要跨链)
        double transferCost = 0.0; // TODO: 根据转账需求估计
        
        // 价差风险 (预期价差变化的PnL)
        double spreadRisk = estimateSpreadRisk(spreadFeatures);
        
        // 期望净收益
        double expectedReturn = fundingCashflow - feeCost - slippageCost - borrowCost - transferCost + spreadRisk;
        
        log.info("Expected Return Breakdown: funding={}, fee={}, slip={}, borrow={}, transfer={}, spread={}, net={}",
            fundingCashflow, feeCost, slippageCost, borrowCost, transferCost, spreadRisk, expectedReturn);
        
        return expectedReturn;
    }
    
    /**
     * 开仓条件判断
     */
    private boolean shouldOpenPosition(
            FundingDiffFeatures fundingFeatures,
            SpreadFeatures spreadFeatures,
            double expectedReturn) {
        
        // 条件1: 期望收益大于阈值
        if (expectedReturn / config.getNotional() < config.getOpenThreshold()) {
            log.debug("Expected return too low: {}", expectedReturn);
            return false;
        }
        
        // 条件2: 资金费差Z-score足够大
        if (Math.abs(fundingFeatures.getZScore()) < 2.0) {
            log.debug("Funding diff z-score too low: {}", fundingFeatures.getZScore());
            return false;
        }
        
        // 条件3: 价差在合理范围内
        if (Math.abs(spreadFeatures.getZScore()) > 3.0) {
            log.debug("Spread z-score too extreme: {}", spreadFeatures.getZScore());
            return false;
        }
        
        // 条件4: 资金费持续性检查
        if (fundingFeatures.getPersistence() < 0.3) {
            log.debug("Funding rate not persistent enough: {}", fundingFeatures.getPersistence());
            return false;
        }
        
        return true;
    }
    
    /**
     * 平仓条件判断
     */
    private boolean shouldClosePosition(
            FundingDiffFeatures fundingFeatures,
            SpreadFeatures spreadFeatures) {
        
        // 条件1: 资金费差回归
        if (Math.abs(fundingFeatures.getRawDiff()) < config.getCloseThreshold()) {
            log.info("Funding diff converged, closing position");
            return true;
        }
        
        // 条件2: 资金费转负 (如果是做空高费率)
        // TODO: 根据仓位方向判断
        
        // 条件3: 价差止损
        if (Math.abs(spreadFeatures.getZScore()) > 4.0) {
            log.warn("Spread diverged too much, stop loss triggered");
            return true;
        }
        
        return false;
    }
    
    /**
     * 开仓执行
     */
    private void openPosition(
            MarketSnapshot snapshotA,
            MarketSnapshot snapshotB,
            FundingDiffFeatures fundingFeatures) {
        
        // 风控检查
        RiskCheckResult riskCheck = riskController.checkPreTradeRisk(
            "binance", "okx", config.getNotional());
        
        if (!riskCheck.isPassed()) {
            log.warn("Risk check failed: {}", riskCheck.getReason());
            return;
        }
        
        // 确定开仓方向
        String longExchange = fundingFeatures.getRawDiff() > 0 ? "okx" : "binance";
        String shortExchange = fundingFeatures.getRawDiff() > 0 ? "binance" : "okx";
        
        // 构建订单
        OrderRequest longOrder = OrderRequest.builder()
            .exchange(longExchange)
            .symbol("BTCUSDT")
            .side("BUY")
            .type("LIMIT")
            .quantity(config.getTargetQty())
            .price(getBestBidPrice(longExchange))
            .timeInForce("IOC") // 立即成交或取消
            .build();
        
        OrderRequest shortOrder = OrderRequest.builder()
            .exchange(shortExchange)
            .symbol("BTCUSDT")
            .side("SELL")
            .type("LIMIT")
            .quantity(config.getTargetQty())
            .price(getBestAskPrice(shortExchange))
            .timeInForce("IOC")
            .build();
        
        // 原子化对冲执行
        try {
            HedgeResult result = executionEngine.executeAtomicHedge(longOrder, shortOrder);
            
            if (result.isSuccess()) {
                log.info("Position opened successfully: {}", result);
            } else {
                log.error("Failed to open position: {}", result.getError());
                // 回滚已成交的单腿
                executionEngine.rollbackSingleLeg(result);
            }
        } catch (Exception e) {
            log.error("Exception during position opening", e);
        }
    }
    
    // 其他辅助方法...
}
```

---

## 详细流程图见下一部分...
