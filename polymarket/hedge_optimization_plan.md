# Polymarket 对冲策略优化计划

> 分析时间：2026-04-13  
> 分析范围：`coin-strategy-job/src/main/java/.../hedge/` 全部代码 + 关联服务  
> 优先级定义：P0 = 致命 Bug / P1 = 核心功能缺失 / P2 = 精度与风险 / P3 = 稳定性与性能 / P4 = 运营与监控

---

## P0 — 致命 Bug（上线前必须修复）

### P0-1：订单实际成交状态未验证 【最高优先级】

**问题根源**  
`HedgeOrderExecutorService.submitOneOrder()` 收到 Polymarket CLOB 的 orderId 后，立即调用 `updateOrderFilled()` 将订单状态设为 `FILLED`。  
但 Polymarket GTC 限价单的成交是**异步的**——下单成功只代表订单已进入撮合队列，不代表立即成交。  
订单可能处于 `LIVE`（挂单中）、`PARTIALLY_FILLED`（部分成交）甚至最终 `CANCELED` 状态。  

`hedge_order` 表已预留 `poll_attempts` / `next_poll_at` 字段，但**没有对应的轮询 Job**。  
对比：跟单策略已有 `SmartWalletOrderPollingService`（每 2s 轮询一次 `LIVE`/`PARTIALLY_FILLED` 订单），对冲策略完全缺失类比实现。

**当前影响**  
- `hedge_position.quantity` 记录的是期望数量，不是实际成交数量
- `hedge_position.realized_pnl_usdc` 结算时基于理论数量而非真实成交
- 若 YES 或 NO 单实际未成交，套利头寸不完整，实际暴露单边风险

**修复方案**  
新建 `HedgeOrderPollingJob`，每 5s 轮询 `hedge_order` 表中 `status = 'PENDING'` 且有 `external_order_id` 的订单：
1. 调用 `clobClientRouter.getOrder(context, externalOrderId)` 查询实际状态
2. 解析 Polymarket 订单状态 → 映射到 `FILLED` / `PARTIAL` / `CANCELLED` / `FAILED`
3. 更新 `filled_quantity`、`cost_usdc`（实际成本 = filledQty × avgPrice）
4. 若同一 `hedge_position_id` 的两边订单均 FILLED → 更新持仓 `quantity` 为实际成交量
5. 若任一边 CANCELLED → 尝试取消另一边 + 将持仓标记 `CANCELLED`

```
hedge_order 状态机（修复后）：
  PENDING → [轮询] → FILLED | PARTIAL | CANCELLED | FAILED
```

---

### P0-2：Dashboard `totalSettled` 始终为 0

**问题代码**（`HedgeController.java:51`）
```java
long totalSettled = positionTracker.getSettlementPendingPositions(0).size();
```
`getSettlementPendingPositions(0)` 内部执行 `last("limit 0")`，MySQL 返回 0 行，导致 totalSettled 永远为 0。

**修复方案**  
在 `HedgePositionTracker` 新增 `countSettled()` 方法：
```java
public long countSettled() {
    return positionMapper.selectCount(new LambdaQueryWrapper<HedgePositionEntity>()
            .eq(HedgePositionEntity::getStatus, "SETTLED"));
}
```
`HedgeController.dashboard()` 改为调用 `positionTracker.countSettled()`。

---

## P1 — 核心功能缺失（策略完整性）

### P1-1：链上 Claim 未自动化

**现状**  
`HedgeSettlementMonitorJob` 检测到市场已 Resolved 后只更新数据库状态，不触发实际的链上资产赎回（`redeemPositions`）。  
注释中写"可通过跟单策略 Claim 模块人工触发"——这意味着套利利润永远锁在链上无法提现。

**与跟单策略对比**  
跟单策略有完整的 `SmartWalletClaimService` + `SmartWalletOnChainClaimGateway`，对冲策略完全缺失。

**修复方案**  
新建 `HedgeClaimService`，复用跟单策略的链上网关：
1. 查询 `hedge_position.status = 'SETTLING'` 的持仓
2. 获取赢方 tokenId（YES 或 NO，由 `final_outcome` 决定）
3. 调用 `SmartWalletOnChainClaimGateway.redeemPositions(tokenId, quantity)`
4. 成功后更新 `claimed_usdc`（实际到账）、`fee_usdc`（实际费用）、`realized_pnl_usdc`
5. 标记持仓 `SETTLED`

在 `HedgeController` 增加 `POST /api/hedge/claim/trigger` 手动触发入口，并支持配置定时自动触发。

---

### P1-2：NegRisk 市场未明确排除

**问题**  
`HedgeScanJob.buildPairs()` 通过 `tokenIds.size() != 2` 过滤非标准市场，但 negRisk 市场的 outcomeTokenIds 也可能恰好为 2（YES/NO）。  
NegRisk 市场的结算逻辑不同：赢方 token 未必等于 \$1（取决于 negRisk 合约的收益率），直接套用 `claimed = quantity` 计算 PnL 会出错。

**修复方案**  
在 `buildPairs()` 增加 negRisk 过滤：
```java
if (market.isNegRisk()) continue; // negRisk 市场结算机制不同，暂不支持
```
同时在 `HedgeRiskGuard` 的检查逻辑中也增加此断言。

---

### P1-3：`scanBatchSize` 配置失效

**问题**  
重构 API 合并后，`HedgeScanJob` 改为读 `marketDataCache.getActiveMarkets()`，该接口返回最多 500 个市场（由缓存配置决定）。  
`HedgeProperties.scanBatchSize`（默认 50）的限制完全失效，实际每次扫描的订单簿调用量是预期的 **10 倍**。

**修复方案**  
在 `HedgeScanJob.buildPairs()` 末尾加截断：
```java
// 限制单次扫描市场数（避免缓存命中后订单簿调用量暴增）
if (pairs.size() > properties.getScanBatchSize()) {
    pairs = pairs.subList(0, properties.getScanBatchSize());
}
```
同时考虑按 `volume24hr` 降序排列（高流动性市场更可能出现套利机会，先扫高价值市场）。

---

## P2 — 精度与风险控制

### P2-1：结算 PnL 精度不足

**现状**  
`HedgeSettlementMonitorJob.checkAndSettle()` 中：
```java
BigDecimal claimed = quantity; // 1 USDC per winning token（理论值）
BigDecimal estimatedFee = claimed × estimatedFeeRate; // 估算值
```
使用的是建仓时的 `quantity`（期望数量）和配置中的固定费率，不是实际成交数量和真实费率。

**正确逻辑**  
1. PnL 应基于 `hedge_order` 中实际 `filled_quantity`（两边取最小值）
2. Fee 应从 `hedge_order.fee_usdc` 字段读取（依赖 P0-1 修复后的订单轮询）
3. `claimed_usdc` = `actual_filled_qty × 1.0`（已结算市场，赢方每股 = $1）

---

### P2-2：执行前订单簿价格时效性不足

**问题**  
订单簿缓存 TTL = 20s，`HedgeScanJob` 每 10s 扫描一次。  
理论上，执行时基于的价格可能已经 20s 过时——在流动性较差或价格波动快的市场中，实际 ask 可能已经超过 best ask + maxSlippageRate，导致订单本应被 Polymarket 拒绝（GTC 单不超过限价则不成交）。

虽然限价单本身有保护作用，但更严重的问题是：**扫描时发现套利机会，但执行时市场条件已不满足**（机会已消失），造成"空跑"建仓——两边都挂单，但因价格变化实际无法同时成交。

**修复方案**  
在 `HedgeOrderExecutorService.execute()` 进入并发下单前，强制刷新订单簿缓存：
```java
// 执行前主动失效缓存，获取最新价格
marketDataCache.invalidateOrderBook(opportunity.getYesTokenId());
marketDataCache.invalidateOrderBook(opportunity.getNoTokenId());
// 重新检测价格是否仍然满足条件
Optional<HedgeOpportunity> freshOpp = detector.detectOpportunity(pair);
if (freshOpp.isEmpty()) {
    saveLog(opportunity, "SKIP_STALE", "执行前价格刷新后机会已消失", null);
    return false;
}
```

---

### P2-3：`GlobalRiskGuard` 多次重复 DB 查询

**问题**  
`HedgeRiskGuard.check()` 调用链：
1. `globalRiskGuard.currentHedgeExposureUsdc()` → DB 查询 1（hedge_position）
2. `globalRiskGuard.checkGlobalExposure(suggestedSize)` 内部：
   - `currentFollowExposureUsdc()` → DB 查询 2（smart_wallet_position）
   - `currentHedgeExposureUsdc()` → DB 查询 3（hedge_position，重复！）

每次风控检查执行 3 次查询，其中 1 次 hedge 查询完全冗余。

**修复方案**  
`HedgeRiskGuard.check()` 改为一次性调用 `globalRiskGuard.getSummary()`（返回两个策略的敞口数据），后续逻辑直接用 `summary.hedgeExposureUsdc()` 而非重复查询：
```java
GlobalRiskGuard.GlobalExposureSummary summary = globalRiskGuard.getSummary();
BigDecimal currentHedgeExposure = summary.hedgeExposureUsdc();
// ... 既用于 hedge 自身限额检查，也用于全局限额检查
```

---

## P3 — 稳定性与性能

### P3-1：套利扫描日志量过大

**问题**  
`ArbitrageDetectorService.scanMarkets()` 当前不记录日志，但 `HedgeOrderExecutorService.execute()` 对每个机会的每次风控拒绝都调用 `saveLog()`。  
更严重的是：即使是 `probDeviation < minGrossEdgeRate`（在 `detectOpportunity` 内 early-return，根本没有传给 Executor）的市场，也没有日志——日志不完整。  
但按当前逻辑，**500市场 × 6次/分 × 部分有机会** 的日志量仍会迅速膨胀。

**修复方案**  
- `hedge_arbitrage_log` 只写入 **通过初始边际筛选后**被风控阻断或实际执行的机会
- 新增 `SKIP_LOW_EDGE`（概率偏差不足）统计计数器（内存中），而非逐条写 DB
- 为表增加数据保留策略：按 `scanned_at` 自动清理 30 天前的日志（建议 DB event 或应用层定期清理 Job）

---

### P3-2：缓存为空时的熔断保护

**问题**  
若 `PolymarketMarketDataCacheService.refreshActiveMarkets()` 持续失败，`cachedActiveMarkets` 保持为空列表。  
`HedgeScanJob` 调用 `getActiveMarkets()` 时走同步兜底路径（`refreshActiveMarkets()`），在 API 不可用时**每 10s 发起一次失败的 API 调用**，不断重试。

**修复方案**  
在 `PolymarketMarketDataCacheService` 中增加失败计数与冷却：
```java
private volatile int refreshFailCount = 0;
private volatile long nextAllowedRefreshAt = 0;

// 失败 3 次后，冷却 5 分钟再重试
private static final int MAX_FAIL_BEFORE_COOLDOWN = 3;
private static final long COOLDOWN_MS = 5 * 60_000;
```
同时 `HedgeScanJob` 增加对缓存年龄的检查：缓存超过 5 分钟未更新时跳过本次扫描并告警。

---

### P3-3：并发双边下单线程池泄漏

**问题**  
`HedgeOrderExecutorService` 中：
```java
private final ExecutorService orderExecutor = Executors.newFixedThreadPool(2);
```
Spring Bean 销毁时没有 `@PreDestroy` 关闭线程池（对比 `WalletClusteringService` 已有此实现）。

**修复方案**  
增加优雅关闭：
```java
@PreDestroy
public void shutdown() {
    orderExecutor.shutdown();
    try {
        if (!orderExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            orderExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        orderExecutor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```
注意：Spring Boot 2.7 使用 `javax.annotation.PreDestroy`，不是 `jakarta`。

---

## P4 — 运营与监控

### P4-1：缺少关键指标埋点

当前对冲策略完全没有 Micrometer 指标，无法接入 Prometheus / Grafana 监控告警。  
以下指标影响生产运营：

| 指标名称 | 类型 | 说明 |
|---|---|---|
| `hedge.scan.markets_scanned` | Counter | 每次扫描处理的市场数 |
| `hedge.scan.opportunities_found` | Counter | 发现的套利机会数 |
| `hedge.execution.success` | Counter | 实际开仓成功次数 |
| `hedge.execution.failed` | Counter | 开仓失败次数（按原因分 tag） |
| `hedge.settlement.settled` | Counter | 已结算持仓数 |
| `hedge.pnl.realized_usdc` | Gauge | 累计已实现 PnL |
| `hedge.exposure.hedge_usdc` | Gauge | 当前对冲总敞口 |
| `hedge.cache.orderbook_hit_rate` | Gauge | 订单簿缓存命中率 |
| `hedge.order.fill_latency_ms` | Timer | 从下单到确认成交的延迟 |

**修复方案**  
在 `HedgeScanJob`、`HedgeOrderExecutorService`、`HedgeSettlementMonitorJob` 中注入 `MeterRegistry` 并埋点。

---

### P4-2：缺少运营操作接口

当前 `HedgeController` 全部是只读 GET 接口，生产运营中需要以下操作能力：

| 接口 | 方法 | 说明 |
|---|---|---|
| `POST /api/hedge/scan/trigger` | POST | 手动触发一次市场扫描（不等待定时器） |
| `POST /api/hedge/positions/{id}/cancel` | POST | 手动取消指定 OPEN 持仓（同时取消链上订单） |
| `POST /api/hedge/positions/{id}/settle` | POST | 手动触发指定持仓结算检查 |
| `POST /api/hedge/claim/trigger` | POST | 手动触发 Claim（依赖 P1-1） |
| `GET /api/hedge/cache/stats` | GET | 查看缓存状态（命中率、刷新时间） |
| `POST /api/hedge/cache/refresh` | POST | 手动强制刷新市场列表缓存 |

---

### P4-3：`hedge_position.uk_market_id` 唯一键过严

**问题**  
`hedge_position` 表在 `market_id` 上有唯一键，即使之前的头寸已经 `SETTLED` 或 `CANCELLED`，同一市场也无法再次建仓。  
在市场流动性恢复或价格再次出现偏差时，会错过二次套利机会。

**修复方案**  
两种方案任选其一：
- **方案A**：将唯一键改为 `(market_id, status)` 的条件唯一索引（MySQL 不直接支持，可改为应用层检查 + 普通索引）
- **方案B**（推荐）：唯一键保留，但 `HedgeRiskGuard` 的重复开仓检查只检查 `OPEN` 和 `SETTLING` 状态，允许同一 market 在前一笔结算后重新建仓

当前 `HedgeRiskGuard` 已正确只检查 `OPEN/SETTLING`，但 DB 层唯一键会阻止 INSERT。需要：
```sql
ALTER TABLE hedge_position DROP INDEX uk_market_id;
ALTER TABLE hedge_position ADD INDEX idx_market_id (market_id);
```

---

## 优化执行优先级汇总

```
┌────────────────────────────────────────────────────────────────────┐
│ 阶段 1（上线前必做，约 3-5 天）                                       │
│  P0-1  订单成交状态轮询 Job（HedgeOrderPollingJob）                  │
│  P0-2  Dashboard totalSettled 计数 Bug 修复                         │
│  P1-3  scanBatchSize 配置失效修复                                    │
│  P1-2  NegRisk 市场明确排除                                          │
│  P3-3  ExecutorService @PreDestroy 优雅关闭                         │
├────────────────────────────────────────────────────────────────────┤
│ 阶段 2（策略完整性，约 5-7 天）                                       │
│  P1-1  HedgeClaimService 链上自动 Claim                             │
│  P2-1  结算 PnL 改为基于实际成交数量（依赖 P0-1）                     │
│  P2-2  执行前强制刷新订单簿缓存                                       │
│  P4-3  唯一键修复（允许同一市场多次套利）                              │
├────────────────────────────────────────────────────────────────────┤
│ 阶段 3（稳定性与运营，约 3-5 天）                                     │
│  P2-3  GlobalRiskGuard 去重查询                                      │
│  P3-1  套利日志量控制 + 数据保留策略                                  │
│  P3-2  缓存熔断与冷却保护                                            │
│  P4-1  Micrometer 指标埋点（接入 Grafana）                           │
│  P4-2  运营操作接口补全                                              │
└────────────────────────────────────────────────────────────────────┘
```

---

## 附：当前实现已验证正确的部分（无需修改）

- 双边等量建仓 + 半 Kelly 资金管理逻辑（`HedgeSizeCalculatorService`）
- 9 项专属风控检查逻辑（`HedgeRiskGuard`）
- 跨策略数据隔离（`hedge_*` 表与 `smart_wallet_*` 完全分离）
- `GlobalRiskGuard` 作为唯一跨表只读入口的架构
- 并发双边下单 + 任意一边失败回滚另一边的原子性保护
- 市场数据缓存共享（`PolymarketMarketDataCacheService`），API 限频问题已解决
- `HEDGE-{marketId}-YES/NO` signalId 前缀与跟单策略不重叠
