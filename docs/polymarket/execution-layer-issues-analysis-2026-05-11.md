# Polymarket 跟单执行层三大问题分析 & 修复计划

> 日期：2026-05-11
> 问题来源：新手常见执行陷阱——市价单扫盘 / 只看 spread 不看 depth / 忽略 taker fee

---

## 一、逐项分析：当前服务是否存在该问题

### 问题 1：默认市价单 → 全场扫盘

**结论：✅ 不存在此问题。**

当前服务已使用 GTC 限价单，而非市价单。

**代码证据**：
- `SmartWalletProperties.ExecutionWallet.orderType = "GTC"`（默认 Good-Till-Cancelled）
- `PolymarketOrderRequestFactory.prepareLocalDraft()` 计算 limitPrice：
  ```
  slippage = rawPrice × priceSlippageRate（默认 0.02 = 2%）
  limitPrice = rawPrice + slippage  // BUY 时加滑点
  limitPrice = rawPrice - slippage  // SELL 时减滑点
  ```
- 所有订单都有明确的 `limitPrice`，不会无限制扫盘。
- `snapToTick()` 将价格对齐到 Polymarket 最小精度。

**但仍有优化空间**：当前 2% 滑点容忍是固定的，没有根据市场流动性动态调整。在薄流动性市场，2% 可能仍然导致吃掉多档深度。

---

### 问题 2：看 spread 不看 depth → 0.51/0.52 看着窄，每档只 $50 吃不下

**结论：⚠️ 部分存在此问题。深度数据已采集，但入场时未用于仓位定价。**

**已做的部分**（做得不错）：
- `MarketSnapshotCollectorService` 采集了完整的 orderbook 深度快照：
  - `bidDepth1c` / `askDepth1c`（±1 美分内的深度 USDC 量）
  - `bidDepth3c` / `askDepth3c`（±3 美分）
  - `bidDepth5c` / `askDepth5c`（±5 美分）
  - `spreadBps`（买卖价差基点）
  - `orderImbalance`（买卖不平衡度）
- `EntryTimingRiskService` 检查 `spreadPct`，超过 `maxEntrySpreadPct=0.04`（4%）时拒绝入场。
- `SmartWalletPositionSyncService.resolveOrderbookDeterioration()` 在退出评分中使用 spread 和 depth 变化。

**缺失的关键部分**：
- **入场时仓位不看 depth**：`PolymarketOrderRequestFactory` 计算 size 时仅做 `stakeUsdc / limitPrice`，完全不考虑 orderbook 在目标价位附近有多少可吃深度。
- **没有 "每次只吃当档深度 50%" 的逻辑**：如果跟单仓位 $50，但 best ask 那档只有 $30，系统仍然会挂 $50 的限价单，要么部分成交，要么吃穿到下一档导致实际均价远高于预期。
- **`enableSlippageGate` 默认关闭**：虽然有基于历史数据的滑点概率门控（Gate 11），但这是统计模型预测，不是实时 orderbook depth 检查。

---

### 问题 3：不看 taker fee → Polymarket 3 月底全面启用 taker 手续费

**结论：⚠️ 存在此问题。手续费率静态固定、未按品类区分、未在开单前扣减。**

**当前实现的问题**：

1. **静态费率**：`estimatedFeeRate = 0.0015`（0.15%），硬编码在 `SmartWalletProperties.ExecutionWallet` 中。而 Polymarket 自 2025 年 3 月起，taker fee 按品类从 0% 到 1.8% 不等。实际费率可能是配置值的 12 倍。

2. **费用仅在执行后估算，不在开单前扣减**：
   - `PolymarketOrderRequestFactory` 在计算 `size = stakeUsdc / limitPrice` 时，`stakeUsdc` 没有预先扣除手续费。
   - 实际到手的 shares 会因为 taker fee 而减少，但仓位计算没有考虑这一点。
   - `SmartWalletAccountingService` 在订单成交后记录 `tradingFeeUsdc` / `gasFeeUsdc`，但这是事后核算，不影响下单决策。

3. **没有 maker/taker 策略**：
   - 所有订单都以 `price + slippage` 作为限价，这在价格匹配时大概率成为 taker（吃单方），支付全额 taker fee。
   - 没有 "挂 best ask 附近等 maker 撮合" 的逻辑——如果改为在 best bid 略上方挂单（maker），可以获得 0 fee 甚至 rebate。

4. **止盈/止损阈值未考虑双边手续费**：
   - `autoTakeProfitThresholdRate = 0.35`（35%），`autoStopLossThresholdRate = 0.50`（50%）。
   - 但实际盈利需要扣除入场 + 出场两次 taker fee。如果品类费率 1.8%，双边共 3.6%，对小额短线交易影响显著。
   - Kelly 公式和盈利性评估模型中的 fee 输入也可能偏低。

---

## 二、修复计划

### Fix-A：入场仓位与 Orderbook 深度联动（P0）

**目标**：下单前检查 orderbook 深度，自动缩减超出深度的仓位或拒绝入场。

**方案**：

1. 在 `SmartWalletSignalRiskService.evaluate()` 中，Gate 8（初步仓位检查）之后新增 **Gate 8b: Depth-Aware Sizing**：
   - 从最新 `MarketSnapshotEntity` 获取 `askDepth3c`（best ask ±3 美分内的总深度）
   - 如果 `preliminaryStake > askDepth3c * depthConsumptionMaxRate`（默认 0.50，即只吃 50%），将仓位降到 `askDepth3c * depthConsumptionMaxRate`
   - 如果降后仓位 < `minOrderUsdc`，标记 blocker `[DEPTH_INSUFFICIENT]` 拒绝入场

2. 新增配置项（`SmartWalletProperties.SignalRisk`）：
   ```java
   /** 是否启用深度感知仓位调整 */
   private boolean enableDepthAwareSizing = true;
   /** 允许吃掉的 ask 深度比例上限（±3c 范围），超出部分自动缩减仓位 */
   private BigDecimal depthConsumptionMaxRate = new BigDecimal("0.50");
   /** 深度检查使用的价格区间：1c/3c/5c */
   private String depthCheckRange = "3c";
   /** 最低可接受深度（USDC），低于此值直接拒绝 */
   private BigDecimal minAcceptableDepthUsdc = new BigDecimal("100");
   ```

3. 在 `PolymarketOrderRequestFactory.prepareLocalDraft()` 中也做防御：如果传入了 `maxSizeByDepth` 参数，取 `min(calculatedSize, maxSizeByDepth)`。

**涉及文件**：
- `SmartWalletSignalRiskService.java` — 新增 Gate 8b
- `SmartWalletProperties.SignalRisk` — 新增配置
- `PolymarketOrderRequestFactory.java` — 可选的防御性 size cap
- `SmartWalletFollowTaskService.java` — 将 depth cap 传递给执行层

**预计工作量**：2 天

---

### Fix-B：Taker Fee 动态化 & 开单前扣减（P0）

**目标**：按品类使用正确的 taker fee 率，在仓位计算前扣减手续费，并支持 maker 挂单策略。

**方案**：

#### B1: Fee Rate 动态化

1. 新增 `PolymarketFeeScheduleService`：
   ```java
   @Service
   public class PolymarketFeeScheduleService {
       // 按 market category 返回当前 taker fee rate
       public BigDecimal getTakerFeeRate(String conditionId) { ... }
       // 按 conditionId 查询 Polymarket CLOB API 的实际费率
       public BigDecimal fetchLiveFeeRate(String tokenId) { ... }
   }
   ```
2. 数据来源优先级：
   - 优先：从 Polymarket CLOB API 的 order response 中提取实际 fee rate（已有 `fee` 字段）
   - 次选：按品类映射表（配置驱动）
   - 兜底：`estimatedFeeRate` 配置值

3. 新增配置（`SmartWalletProperties`）：
   ```java
   /** 品类 → taker fee 映射，key = category slug, value = fee rate */
   private Map<String, BigDecimal> categoryFeeRates = new LinkedHashMap<>();
   /** 默认 taker fee rate（当品类未映射时） */
   private BigDecimal defaultTakerFeeRate = new BigDecimal("0.02");
   /** 是否使用动态费率（从 CLOB API 获取） */
   private boolean enableDynamicFeeRate = false;
   ```

#### B2: 开单前 Fee 扣减

1. 在 `SmartWalletSignalRiskService` 计算 `preliminaryStake` 后，扣减预估手续费：
   ```java
   BigDecimal takerFeeRate = feeScheduleService.getTakerFeeRate(event.getMarketId());
   BigDecimal effectiveStake = preliminaryStake.divide(
       BigDecimal.ONE.add(takerFeeRate), 2, RoundingMode.DOWN);
   BigDecimal estimatedEntryFee = preliminaryStake.subtract(effectiveStake);
   reasons.add("[FEE_ADJUSTED] Original stake=" + preliminaryStake
       + " adjusted to " + effectiveStake
       + " after taker fee " + takerFeeRate + " (" + estimatedEntryFee + " USDC).");
   ```

2. 在盈利性评估（`TradeProfitabilityScoringService`）中使用真实费率而非固定 0.15%。

#### B3: Maker 挂单策略（可选 P1）

1. 在 `PolymarketOrderRequestFactory` 中新增 maker 模式：
   - 当 `orderStrategy = "MAKER"` 时，limitPrice = bestBid + 1 tick（而非 bestAsk + slippage）
   - maker 订单不会立即成交，需要等撮合，但 fee = 0
   - 适用于流动性好的市场（`askDepth3c > $10K` 且 `spreadBps < 200`）

2. 新增配置：
   ```java
   /** 订单策略：TAKER（默认，立即成交）/ MAKER（挂单等撮合）/ ADAPTIVE（自适应） */
   private String orderStrategy = "TAKER";
   /** ADAPTIVE 模式下，日成交额超过此值的市场使用 MAKER 策略 */
   private BigDecimal makerStrategyMinDailyVolumeUsdc = new BigDecimal("10000");
   ```

**涉及文件**：
- 新增 `PolymarketFeeScheduleService.java`
- `SmartWalletProperties.java` — 新增 fee 和 order strategy 配置
- `SmartWalletSignalRiskService.java` — fee 预扣减
- `TradeProfitabilityScoringService.java` — 使用动态费率
- `PolymarketOrderRequestFactory.java` — maker 模式
- `KellyStakeCalculator.java` — fee 纳入 Kelly 公式

**预计工作量**：B1+B2 = 2 天，B3 = 2 天（可后续单独做）

---

### Fix-C：滑点容忍度动态化（P1）

**目标**：`priceSlippageRate` 从固定 2% 改为根据市场流动性动态调整。

**方案**：

1. 在 `PolymarketOrderRequestFactory` 中，根据 orderbook 深度动态调整 slippage：
   ```
   如果 askDepth3c > $5000：slippage = 1%（流动性充足，可以挂得紧）
   如果 askDepth3c $1000-$5000：slippage = 2%（中等，保持现状）
   如果 askDepth3c < $1000：slippage = 3%（薄流动性，需要更宽容忍）
   如果 askDepth3c < $200：拒绝入场（深度不足）
   ```

2. 新增配置：
   ```java
   private boolean enableDynamicSlippage = false;
   private BigDecimal highLiquiditySlippageRate = new BigDecimal("0.01");
   private BigDecimal mediumLiquiditySlippageRate = new BigDecimal("0.02");
   private BigDecimal lowLiquiditySlippageRate = new BigDecimal("0.03");
   private BigDecimal highLiquidityThresholdUsdc = new BigDecimal("5000");
   private BigDecimal lowLiquidityThresholdUsdc = new BigDecimal("1000");
   ```

**涉及文件**：
- `PolymarketOrderRequestFactory.java` — 动态 slippage 计算
- `SmartWalletProperties.ExecutionWallet` — 新增分层配置
- 可选：`EntryTimingRiskService.java` — 将深度信息传递下去

**预计工作量**：1 天

---

## 三、实施优先级

| 编号 | 修复项 | 优先级 | 工作量 | 当前状态 |
|------|--------|--------|--------|----------|
| Fix-A | Orderbook 深度感知仓位调整 | P0 | 2d | ✅ 已完成（Gate 8c） |
| Fix-B1+B2 | Taker Fee 动态化 & 开单前扣减 | P0 | 2d | ✅ 已完成（Gate 8b + PolymarketFeeScheduleService） |
| Fix-B3 | Maker 挂单策略 | P1 | 2d | ✅ 已完成（TAKER/MAKER/ADAPTIVE 策略路由） |
| Fix-C | 滑点容忍度动态化 | P1 | 1d | ✅ 已完成（3 级流动性分层滑点） |

**推荐实施顺序**：Fix-B1+B2 → Fix-A → Fix-C → Fix-B3

理由：手续费问题影响最直接（每笔交易都在多付 fee），应最先修复。深度感知次之（防止薄流动性市场亏损）。动态滑点和 maker 策略是锦上添花的优化。

---

## 四、P0 修复实施记录（2026-05-11）

### 已完成的变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `PolymarketFeeScheduleService.java` | 新增 | 动态 taker fee 查询服务：3 级优先级（运行时缓存 → 品类映射 → 默认费率），含仓位扣减和手续费估算方法 |
| `SmartWalletProperties.java` | 修改 | 新增 `FeeSchedule` 内部类（品类费率映射、默认费率、预扣减开关）；`SignalRisk` 新增深度感知配置（启用开关、深度消耗上限、检查范围、最低可接受深度） |
| `SmartWalletSignalRiskService.java` | 修改 | Gate 8 后新增 Gate 8b（taker fee 预扣减）和 Gate 8c（orderbook 深度感知仓位调整）；新增 `resolveMarketCategory()` 和 `resolveAskDepth()` 辅助方法 |
| `SmartWalletAccountingService.java` | 修改 | 订单成交后回写真实费率到 `PolymarketFeeScheduleService` 观测缓存，供后续同 market 查询 |

### Gate 8b 逻辑摘要（Taker Fee 预扣减）
- 条件：`feeSchedule.enablePreTradeFeeDeduction = true`（默认开启）
- 从 `PolymarketFeeScheduleService` 获取动态费率
- 将 `preliminaryStake` 按 `rawStake / (1 + feeRate)` 缩减
- 日志标记：`[FEE_ADJUSTED]`

### Gate 8c 逻辑摘要（Orderbook 深度感知仓位调整）
- 条件：`signalRisk.enableDepthAwareSizing = true`（默认开启）
- 从最近 5 分钟内的 market snapshot 获取 askDepth（默认 ±3c 范围）
- 深度 < `minAcceptableDepthUsdc`（$100）→ 拒绝入场 `[G8c_DEPTH_INSUFFICIENT]`
- 仓位 > 深度 × `depthConsumptionMaxRate`（50%）→ 缩减到深度上限 `[DEPTH_DOWNSIZE]`
- 缩减后 < `minOrderUsdc` → 拒绝入场 `[G8c_DEPTH_DOWNSIZE_BELOW_MIN]`
- 快照不可用 → fail-open，由后续滑点门控兜底

---

## 五、P1 修复实施记录（2026-05-11）

### 已完成的变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `SmartWalletProperties.ExecutionWallet` | 修改 | 新增动态滑点配置（enableDynamicSlippage、3 级流动性阈值和滑点率）；新增 Maker 策略配置（orderStrategy、makerStrategyMinDepthUsdc、makerStrategyMaxSpreadBps） |
| `ExecutionWalletContextLoader.java` | 修改 | `ExecutionWalletContext` 新增 9 个字段；`load()` 方法透传新配置 |
| `PolymarketOrderRequestFactory.java` | 修改 | 新增 `MarketSnapshotCollectorService` 依赖；`prepareLocalDraft()` 支持 TAKER/MAKER 策略路由和动态滑点；新增 `resolveSlippageRate()`、`resolveOrderStrategy()`、`fetchAskDepth3c()` 辅助方法；`PreparedPolymarketOrder` 新增 `orderStrategy` 字段 |

### Fix-C 逻辑摘要（动态滑点）
- 条件：`executionWallet.enableDynamicSlippage = true`（默认关闭，需手动开启）
- 从最近 5 分钟 snapshot 获取 askDepth3c
- askDepth3c >= $5000 → slippage = 1%（流动性充足，挂紧）
- askDepth3c $1000~$5000 → slippage = 2%（中等，保持现有默认）
- askDepth3c < $1000 → slippage = 3%（薄流动性，需更宽容忍）
- 快照不可用 → 回退到固定 `priceSlippageRate`（2%）

### Fix-B3 逻辑摘要（Maker 挂单策略）
- 配置 `orderStrategy`：TAKER（默认）/ MAKER / ADAPTIVE
- TAKER 模式：现有逻辑不变，limitPrice = rawPrice + slippage
- MAKER 模式：limitPrice = rawPrice + 1 tick，挂单在 bestBid 附近等撮合，fee = 0
- ADAPTIVE 模式：根据 snapshot 判断市场流动性
  - askDepth3c >= $5000 且 spreadBps <= 200 → 使用 MAKER
  - 否则 → 使用 TAKER
  - 快照不可用或过期 → 保守走 TAKER
