# Polymarket 对冲套利策略：流程梳理 + 模拟仓 / LIVE 仓 改造方案

> 基于代码：`coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/hedge/`
> 数据库脚本：`sql/hedge_schema.sql`
> 实体：`coin-strategy-common/common-model/src/.../arbitrage/entity/Hedge*Entity.java`
> 编写时间：2026-04-18

---

## 一、当前对冲套利逻辑流程梳理

### 1.1 核心数学

Polymarket 二元市场的结算规则：YES + NO = $1（赢方收回 $1）。
若同时以 `ask_yes + ask_no < 1` 同时买入双边，则：

```
总成本   = Q × (ask_yes + ask_no)
必然回款 = Q × $1            （结算时一边赢得 $1/股，另一边作废）
毛利     = Q × (1 - ask_yes - ask_no)             ← prob_deviation
净利     = Q × (1 - ask_yes - ask_no - 2 × feeRate)
```

`feeRate` 双边各收一次。当前默认 `0.0015`，因此双边总费率 `0.0030`。

### 1.2 调用链路（自上而下）

```
@Scheduled(10s) HedgeScanJob.scan()
        │
        ├── PolymarketMarketDataCacheService.getActiveMarkets()       // 共享缓存读，不重复 REST
        ├── buildPairs(markets)                                       // 二元 YES/NO 标准市场过滤
        ├── ArbitrageDetectorService.scanMarkets(pairs)
        │     └── 对每个市场:
        │           ├── 拉 OrderBook (YES/NO)
        │           ├── bestAsk × 2，aggregate 1% 滑点档前 5 档深度
        │           ├── 校验 minGrossEdgeRate (默认 0.025)
        │           ├── 校验 minNetEdgeRate (默认 0.015，扣 2×fee)
        │           └── 校验 minSideLiquidityUsdc (默认 2000)
        │
        ├── GlobalRiskGuard.getSummary()                              // 跟单+对冲合并敞口
        │
        └── 对每个 HedgeOpportunity:
              HedgeOrderExecutorService.execute(opp, availableCapital)
                ├── HedgeRiskGuard.check()                            // 9 层风控
                │     ├── 净利率 ≥ 阈值
                │     ├── 双边流动性 ≥ 阈值
                │     ├── 市场到期 ≥ 4h
                │     ├── 同 market 不重复建仓
                │     ├── 单笔 ≥ minSinglePositionUsdc(50)
                │     ├── 当前持仓数 < maxOpenPositions(20)
                │     ├── 对冲总敞口 < maxTotalExposureUsdc(30000)
                │     ├── 全局敞口 < globalMaxTotalExposureUsdc(80000)
                │     └── 对冲占比 < maxHedgeShareOfGlobalExposure(0.5)
                │
                ├── HedgeSizeCalculatorService.calculatePositionUsdc  // 半 Kelly
                ├── HedgeSizeCalculatorService.calculateQuantity      // Q = totalUsdc / (askY + askN)
                │
                ├── if !autoExecutionEnabled: 仅 DRY-RUN 日志，return
                │
                ├── marketDataCache.invalidateOrderBook() + 重新 detect  // 防过时价
                ├── walletContextLoader.requireLiveContext()              // 加载执行钱包
                ├── positionTracker.openPosition()                        // 写 hedge_position (status=OPEN)
                │
                ├── CompletableFuture.parallel:
                │     submitOneOrder(YES) + submitOneOrder(NO)
                │     └── PolymarketClobClientRouter.placeOrder() (Python SDK 桥)
                │
                ├── 任一边失败 → tryCancelViaRouter(另一边) + cancelPosition()
                ├── 双边成功 → updateOrderSubmitted() (LIVE 等待轮询)
                └── saveLog(EXECUTED)

@Scheduled(5s) HedgeOrderPollingJob.pollPendingOrders()
        │
        ├── 查 status='LIVE' AND next_poll_at<=now LIMIT 30
        └── 对每条:
              ├── clobClientRouter.getOrder(externalOrderId)
              ├── matched/filled → updateOrderFillConfirmed() → syncActualPositionIfBothFilled()
              ├── canceled/expired → updateOrderCancelledByPolling() + 联动取消另一边 + cancelPosition()
              └── live → advancePollBackoff() (指数退避，最长 60s，40 次封顶)

@Scheduled(30s) HedgeSettlementMonitorJob.checkSettlements()
        │
        ├── 查 status IN (OPEN, SETTLING) LIMIT 30
        └── 对每条:
              ├── apiClient.fetchMarketMetadata(marketId)
              ├── meta.isResolved() → markSettling(positionId, finalOutcome)
              └── 过期 24h 仍未解决 → WARN 日志

HedgeClaimService.triggerClaims()  (手动 REST 触发)
        └── 对 SETTLING 持仓调用链上 redeem，写 claimedUsdc + realizedPnl + status=SETTLED
```

### 1.3 数据模型

| 表 | 作用 | 关键字段 |
|---|---|---|
| `hedge_position` | 一次对冲建仓主记录 | `status` ∈ {OPEN, SETTLING, SETTLED, CANCELLED}，`yes_entry_price`, `no_entry_price`, `prob_deviation`, `quantity`, `total_cost_usdc`, `expected_profit_usdc`, `realized_pnl_usdc`, `final_outcome` |
| `hedge_order` | 双边订单（每仓 2 条） | `status` ∈ {PENDING, LIVE, FILLED, PARTIAL, FAILED, CANCELLED}，`external_order_id`, `side`, `requested_quantity`, `filled_quantity`, `cost_usdc`, `poll_attempts` |
| `hedge_arbitrage_log` | 每次扫描日志（含未执行） | `action` ∈ {SKIP_LOW_EDGE, SKIP_LIQUIDITY, SKIP_RISK, SKIP_DUPLICATE, SKIP_DISABLED, SKIP_STALE, EXECUTED} |

### 1.4 当前为什么"没有运行"——根因诊断

阅读 `application.yml` 后定位三个并列原因：

1. **`polymarket.hedge` 配置块完全缺失**。
   `HedgeProperties.enabled` 默认值是 `false`，三个 `@Scheduled` 任务（`HedgeScanJob` / `HedgeOrderPollingJob` / `HedgeSettlementMonitorJob`）首行即 `if (!properties.isEnabled()) return;`，所以**整套链路从未被触发**。

2. **`autoExecutionEnabled` 默认 `false`**，即使打开 `enabled` 也只走"演练日志"分支：
   ```java
   if (!hedgeProperties.isAutoExecutionEnabled()) {
       log.info("[HedgeExecutor] DRY-RUN ...");
       saveLog(opp, "SKIP_DISABLED", "autoExecution=false（演练模式）", null);
       return false;
   }
   ```
   这种 "演练" 既不写 `hedge_position` 也不写 `hedge_order`，无法用作"模拟仓位回测/PnL 跟踪"。

3. **不存在 PAPER 路径**。`autoExecutionEnabled=true` 直接走 `PolymarketClobClientRouter.placeOrder()` → 真链上下单。不像跟单策略 (`smart-wallet`) 已有 `ExecutionMode { PAPER, LIVE }` 双模分流。

---

## 二、模拟仓 (PAPER) + LIVE 仓 改造方案

### 2.1 设计原则

1. **不破坏既有 LIVE 链路**：所有现有方法保持兼容，模拟仓走旁路独立入口。
2. **数据库内同表，按 `mode` 字段区分**：避免新建一套表带来的 dashboard / 风控双写复杂度。
3. **完整复用风控、机会识别、Kelly 计算**：模拟仓共享同一套 `ArbitrageDetectorService` + `HedgeRiskGuard` + `HedgeSizeCalculatorService`，只替换"下单 + 轮询 + 结算"三处终端动作。
4. **风控敞口可隔离也可统一**：通过开关决定 PAPER 仓位是否纳入 `currentHedgeExposureUsdc()`。默认**不纳入**全局敞口（PAPER 不占用真实 USDC）。

### 2.2 模式对比

| 维度 | PAPER（模拟仓） | LIVE（实盘仓） |
|---|---|---|
| 触发开关 | `polymarket.hedge.execution-mode=PAPER` | `polymarket.hedge.execution-mode=LIVE` 且 `auto-execution-enabled=true` |
| 是否需要钱包 | 否 | 必须 `walletContextLoader.requireLiveContext()` 成功 |
| 下单动作 | 不调用 CLOB；直接以 `bestAsk` 视为成交价创建 `FILLED` 订单 | `clobClientRouter.placeOrder(...)` |
| `hedge_order.status` 起点 | `FILLED` (立即) | `LIVE`（待轮询确认） |
| `hedge_order.external_order_id` | `paper-{positionId}-{side}` | Polymarket 返回 |
| 轮询任务 | 跳过 mode=PAPER 的订单 | 正常处理 |
| 结算 | `HedgeSettlementMonitorJob` 同样监控市场 resolved；但 `markSettled` 直接按理论赢 1.0 USDC/股 计算 PnL，**无链上 claim** | 调用 `HedgeClaimService` 链上 redeem |
| 全局敞口 | 默认不计入（可配） | 计入 |
| 风控 | 同一套 `HedgeRiskGuard` 但跳过"重复建仓"检查时区分 mode | 完整 |

### 2.3 改造文件清单

| # | 类型 | 路径 | 改动 |
|---|---|---|---|
| 1 | 新建 | `coin-strategy-job/.../hedge/config/HedgeExecutionMode.java` | enum {PAPER, LIVE} |
| 2 | 修改 | `coin-strategy-job/.../hedge/config/HedgeProperties.java` | 新增 `executionMode`, `paper.*` 子配置 |
| 3 | 新建 | `coin-strategy-job/.../hedge/service/HedgePaperExecutionService.java` | 模拟下单 + 立即 FILLED |
| 4 | 修改 | `coin-strategy-job/.../hedge/service/HedgeOrderExecutorService.java` | 顶部按 mode 分流；保留 LIVE 既有逻辑 |
| 5 | 修改 | `coin-strategy-job/.../hedge/service/HedgeOrderPollingJob.java` | `WHERE mode='LIVE'` 过滤 |
| 6 | 修改 | `coin-strategy-job/.../hedge/service/HedgeSettlementMonitorJob.java` | 模拟仓走"理论结算"路径 |
| 7 | 修改 | `coin-strategy-job/.../hedge/service/GlobalRiskGuard.java` | `currentHedgeExposureUsdc` 可选过滤 mode |
| 8 | 修改 | `coin-strategy-common/.../HedgePositionEntity.java` | 新增 `mode` 字段 |
| 9 | 修改 | `coin-strategy-common/.../HedgeOrderEntity.java` | 新增 `mode` 字段 |
| 10 | 新建 | `sql/hedge_mode_migration.sql` | `ALTER TABLE` 增加 `mode` 列、索引 |
| 11 | 修改 | `coin-strategy-job/src/main/resources/application.yml` | 增 `polymarket.hedge` 配置块 |
| 12 | 修改 | `coin-strategy-job/.../hedge/controller/HedgeController.java` | dashboard 区分 PAPER/LIVE 统计（可选，先报表分组） |

### 2.4 字段扩展规范

```sql
ALTER TABLE hedge_position
    ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'LIVE' AFTER status,
    ADD INDEX idx_mode_status (mode, status);

ALTER TABLE hedge_order
    ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'LIVE' AFTER status,
    ADD INDEX idx_mode_status (mode, status);

ALTER TABLE hedge_arbitrage_log
    ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'LIVE';
```

> 默认 `LIVE` 保证历史数据语义不变；新代码插入时显式赋值。
> `uk_market_id` 应改为 `(market_id, mode)` 复合唯一键，否则 PAPER 与 LIVE 不能并存。

### 2.5 模拟仓 Order/Position 生成模式

```
HedgePaperExecutionService.execute(opp, availableCapital):
    1. 调用 HedgeRiskGuard.check() —— 与 LIVE 共享
    2. HedgeSizeCalculatorService 计算 totalUsdc / quantity
    3. positionTracker.openPosition(... mode=PAPER)
    4. positionTracker.createOrder(YES, mode=PAPER) → 立即 updateOrderFilled
       cost=Q × yes_ask, externalOrderId="paper-{posId}-YES",
       filledQty=Q, fee=Q × yes_ask × feeRate
    5. positionTracker.createOrder(NO,  mode=PAPER) → 立即 updateOrderFilled
    6. positionTracker.syncActualPositionIfBothFilled(posId)
    7. saveLog(action="EXECUTED", mode=PAPER)
```

模拟结算（`HedgeSettlementMonitorJob` 内分支）：

```
if (position.getMode() == PAPER && resolved):
    BigDecimal claimed = position.getQuantity().multiply(BigDecimal.ONE);
    BigDecimal feeUsdc = position.getYesCostUsdc().add(position.getNoCostUsdc())
                                  .multiply(properties.getEstimatedFeeRate());
    positionTracker.markSettled(positionId, claimed, feeUsdc);
```

### 2.6 风控敞口隔离选项

`HedgeProperties.paper.includeInGlobalExposure`（默认 `false`）：
- `false`：`GlobalRiskGuard.currentHedgeExposureUsdc()` 增加 `WHERE mode='LIVE'` 过滤，PAPER 不占额。
- `true`：兼容老行为，不改 query。

---

## 三、模拟仓上线 Run-book（先跑通模拟仓）

### Step 1：执行 SQL 迁移

```bash
mysql -uroot -p coin_strategy < sql/hedge_mode_migration.sql
```

### Step 2：在 `application.yml` 末尾添加 `polymarket.hedge` 配置（详见 4.2）

最关键的两个值：

```yaml
polymarket:
  hedge:
    enabled: true
    execution-mode: PAPER          # ← 仅模拟，不下真单
    auto-execution-enabled: true   # PAPER 模式下也用此开关控制是否生成模拟订单
```

### Step 3：重启 `coin-strategy-job` 应用

```bash
mvn -pl coin-strategy-job spring-boot:run
# 或
mvn package && java -jar coin-strategy-job/target/coin-strategy-job.jar
```

### Step 4：验证

```sql
-- 扫描日志（每 10s 应有新记录）
SELECT scanned_at, market_id, prob_deviation, action, skip_reason
  FROM hedge_arbitrage_log
 ORDER BY scanned_at DESC LIMIT 20;

-- 模拟仓位
SELECT id, mode, market_id, status, yes_entry_price, no_entry_price,
       prob_deviation, quantity, total_cost_usdc, expected_profit_usdc, opened_at
  FROM hedge_position
 WHERE mode='PAPER'
 ORDER BY opened_at DESC;

-- 模拟订单（应当全部 FILLED）
SELECT hedge_position_id, mode, side, status, price, filled_quantity, cost_usdc
  FROM hedge_order
 WHERE mode='PAPER'
 ORDER BY id DESC LIMIT 20;
```

REST 验证：

```bash
curl http://localhost:8081/strategy/api/api/hedge/dashboard
curl http://localhost:8081/strategy/api/api/hedge/positions/open
curl http://localhost:8081/strategy/api/api/hedge/arbitrage-logs?limit=20
```

### Step 5：上线 LIVE（仅在模拟仓稳定运行 ≥ 1 周后）

```yaml
polymarket:
  hedge:
    execution-mode: LIVE
    auto-execution-enabled: true   # ← 真下单
    paper:
      include-in-global-exposure: false
```

并确保 `polymarket.execution-wallet.*` 已配置且地址可用。

---

## 四、附：完整 `polymarket.hedge` 推荐配置

```yaml
polymarket:
  hedge:
    # ── 主开关 ──
    enabled: true
    auto-execution-enabled: true       # PAPER/LIVE 都需要打开
    execution-mode: PAPER              # PAPER | LIVE

    # ── 模拟仓子配置 ──
    paper:
      include-in-global-exposure: false   # PAPER 是否算入全局敞口
      simulate-fill-slippage-rate: 0.0    # 模拟滑点（>0 时模拟成交价 = bestAsk × (1+x)）

    # ── 套利识别 ──
    min-gross-edge-rate: 0.025
    min-net-edge-rate: 0.015
    min-side-liquidity-usdc: 2000
    scan-fixed-delay-ms: 10000
    scan-batch-size: 50

    # ── 仓位管理 ──
    max-single-position-usdc: 3000
    min-single-position-usdc: 50
    max-open-positions: 20
    max-total-exposure-usdc: 30000
    kelly-fraction: 0.5

    # ── 执行参数 ──
    estimated-fee-rate: 0.0015
    max-slippage-rate: 0.005
    order-type: GTC

    # ── 结算监控 ──
    settlement-fixed-delay-ms: 30000
    settlement-batch-size: 30

    # ── 全局风控 ──
    global-max-total-exposure-usdc: 80000
    max-hedge-share-of-global-exposure: 0.5
```

---

## 五、改造的最小可验证里程碑（M0 / M1 / M2）

| 里程碑 | 范围 | 验收 |
|---|---|---|
| M0：纯日志 | 仅添加 application.yml `polymarket.hedge.enabled=true`，autoExecutionEnabled=false | `hedge_arbitrage_log` 持续写入 SKIP_*，扫描链路通 |
| M1：模拟仓全链 | 加 mode 字段 + `HedgePaperExecutionService` + 配置 PAPER | `hedge_position(mode=PAPER)` + 双 FILLED 订单生成；市场 resolved 后 `realized_pnl_usdc` 写入 |
| M2：LIVE 上线 | 切换 `execution-mode=LIVE` + 钱包就绪 | 真单成交，链上 redeem 通过 `/claim/trigger` 触发 |

> 当前仓库尚未实现 M0 — M2 中的任意一步。下一节给出 M1 所需的代码骨架。
