# 跟单策略优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Polymarket 跟单系统实现三条并行优化：动态 Kelly 仓位计算、时间感知止损、PnL 归因分析 API。

**Architecture:** 在现有 `SmartWalletSignalRiskService`（入场）和 `SmartWalletPositionSyncService`（持仓管理）基础上注入新组件，三条线互不依赖可并行实施。所有新功能均有独立 `enabled` 开关，回退安全。

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Lombok, JUnit5, Mockito, application.yml 配置驱动

---

## 文件地图

| 操作 | 文件路径 | 职责 |
|------|---------|------|
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/KellyStakeCalculator.java` | Kelly 公式纯工具类，无 Spring 依赖 |
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExitSignalProvider.java` | 退出信号接口（方案 C 接入点） |
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/TimeAwareStopLossService.java` | 时间感知止损 + 趋势加速止损，实现 ExitSignalProvider |
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/model/FollowTradeAttributionView.java` | 归因结果视图 |
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/FollowTradeAttributionService.java` | 跟单 PnL 归因聚合，只读 |
| 新建 | `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/KellyStakeCalculatorTest.java` | Kelly 公式单元测试 |
| 新建 | `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/TimeAwareStopLossServiceTest.java` | 时间感知止损单元测试 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java` | 新增 Kelly + StopLossTier + TrendStopLoss 配置字段 |
| 修改 | `coin-strategy-job/src/main/resources/application.yml` | 新增对应 yml 配置值 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSignalRiskService.java` | 将 recommendedStake 计算替换为 Kelly 逻辑 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java` | 注入 TimeAwareStopLossService，替换固定阈值 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/controller/SmartWalletController.java` | 新增归因 API 端点 |

---

## Task 1: KellyStakeCalculator — 纯工具类

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/KellyStakeCalculator.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/KellyStakeCalculatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
// KellyStakeCalculatorTest.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class KellyStakeCalculatorTest {

    private final KellyStakeCalculator calculator = new KellyStakeCalculator();

    // f* = (p*b - (1-p)) / b
    // p=0.65, entryPrice=0.4 → b=(1-0.4)/0.4=1.5
    // f* = (0.65*1.5 - 0.35) / 1.5 = (0.975-0.35)/1.5 = 0.625/1.5 = 0.4167
    // stake = 1000 * 0.3 * 0.4167 = 125.01 → capped by maxStake=100 → 100.00
    @Test
    void compute_normalCase_returnsKellyStakeCappedByMax() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),   // calibratedFollowProbability
                new BigDecimal("0.40"),   // entryPrice
                new BigDecimal("1000"),   // availableCapital
                new BigDecimal("0.30"),   // kellyFraction
                new BigDecimal("1"),      // minOrderUsdc
                new BigDecimal("100")     // maxStakeUsdc
        );
        assertEquals(new BigDecimal("100.00"), result);
    }

    // p=0.55, entryPrice=0.9 → b=(0.1)/0.9=0.1111
    // f* = (0.55*0.1111 - 0.45) / 0.1111 = (0.0611 - 0.45)/0.1111 = negative → 0
    @Test
    void compute_negativeEv_returnsZero() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.55"),
                new BigDecimal("0.90"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("1"),
                new BigDecimal("100")
        );
        assertEquals(BigDecimal.ZERO.setScale(2), result);
    }

    // p=null → fallback returns null (caller should use old formula)
    @Test
    void compute_nullProbability_returnsNull() {
        BigDecimal result = calculator.compute(
                null,
                new BigDecimal("0.40"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("1"),
                new BigDecimal("100")
        );
        assertNull(result);
    }

    // entryPrice=0.99 (near 1) → odds abnormal → returns minOrderUsdc
    @Test
    void compute_priceNearOne_returnsMinOrder() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),
                new BigDecimal("0.99"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("5"),
                new BigDecimal("100")
        );
        assertEquals(new BigDecimal("5.00"), result);
    }

    // stake below minOrder → returns minOrder
    @Test
    void compute_stakeBeforeCapBelowMin_returnsMinOrder() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.60"),
                new BigDecimal("0.50"),
                new BigDecimal("10"),   // tiny capital → tiny stake
                new BigDecimal("0.30"),
                new BigDecimal("5"),
                new BigDecimal("100")
        );
        // f*=(0.60*1-0.40)/1=0.20; stake=10*0.3*0.20=0.60 < minOrder=5 → 5.00
        assertEquals(new BigDecimal("5.00"), result);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd /Users/lichangzheng.ray/Ray/CoinStrategy
mvn -pl coin-strategy-job test -Dtest=KellyStakeCalculatorTest -q 2>&1 | tail -15
```
期望：编译失败（类不存在）或测试失败。

- [ ] **Step 3: 实现 KellyStakeCalculator**

```java
// KellyStakeCalculator.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Kelly Criterion stake calculator for binary prediction markets.
 *
 * Formula: f* = (p * b - (1-p)) / b
 *   p = calibrated follow probability
 *   b = (1 - entryPrice) / entryPrice  (payout odds)
 *
 * Returns null when probability is unknown (caller falls back to legacy formula).
 * Returns ZERO when EV is negative (caller should reject signal via EV gate).
 * Returns minOrderUsdc when entry price is near 0 or 1 (abnormal odds).
 */
@Component
public class KellyStakeCalculator {

    private static final BigDecimal PRICE_BOUNDARY = new BigDecimal("0.02");
    private static final int SCALE = 8;

    /**
     * Compute recommended stake using Kelly Criterion.
     *
     * @param calibratedFollowProbability  follow probability (null → return null for fallback)
     * @param entryPrice                   market entry price [0,1]
     * @param availableCapital             available capital in USDC
     * @param kellyFraction                Kelly fraction / discount factor (e.g. 0.3)
     * @param minOrderUsdc                 minimum order size floor
     * @param maxStakeUsdc                 maximum stake ceiling
     * @return recommended stake, or null if probability unknown
     */
    public BigDecimal compute(BigDecimal calibratedFollowProbability,
                              BigDecimal entryPrice,
                              BigDecimal availableCapital,
                              BigDecimal kellyFraction,
                              BigDecimal minOrderUsdc,
                              BigDecimal maxStakeUsdc) {
        if (calibratedFollowProbability == null) {
            return null;
        }

        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : entryPrice;
        BigDecimal capital = availableCapital == null ? BigDecimal.ZERO : availableCapital;
        BigDecimal fraction = kellyFraction == null ? new BigDecimal("0.3") : kellyFraction;
        BigDecimal minOrder = minOrderUsdc == null ? BigDecimal.ONE : minOrderUsdc;
        BigDecimal maxStake = maxStakeUsdc == null ? new BigDecimal("150") : maxStakeUsdc;

        // Abnormal price boundary → clamp to minOrder
        if (price.compareTo(PRICE_BOUNDARY) < 0 || price.compareTo(BigDecimal.ONE.subtract(PRICE_BOUNDARY)) > 0) {
            return minOrder.setScale(2, RoundingMode.HALF_UP);
        }

        // b = (1 - price) / price
        BigDecimal odds = BigDecimal.ONE.subtract(price).divide(price, SCALE, RoundingMode.HALF_UP);

        // f* = (p * b - (1-p)) / b
        BigDecimal p = calibratedFollowProbability;
        BigDecimal numerator = p.multiply(odds).subtract(BigDecimal.ONE.subtract(p));
        if (numerator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal kellyFraction_ = numerator.divide(odds, SCALE, RoundingMode.HALF_UP);

        // stake = capital * fraction * f*
        BigDecimal stake = capital.multiply(fraction).multiply(kellyFraction_).setScale(2, RoundingMode.HALF_UP);

        // Apply floor and ceiling
        if (stake.compareTo(minOrder) < 0) {
            return minOrder.setScale(2, RoundingMode.HALF_UP);
        }
        return stake.min(maxStake).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -pl coin-strategy-job test -Dtest=KellyStakeCalculatorTest -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，所有 5 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/KellyStakeCalculator.java \
        coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/KellyStakeCalculatorTest.java
git commit -m "feat: add KellyStakeCalculator for dynamic position sizing"
```

---

## Task 2: 配置扩展 — SmartWalletProperties + application.yml

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java`
- Modify: `coin-strategy-job/src/main/resources/application.yml`

- [ ] **Step 1: 在 SignalRisk 内部类末尾添加 Kelly 和 StopLossTier 字段**

在 `SmartWalletProperties.SignalRisk` 类末尾（`concentrationStakePenaltyMaxRate` 字段之后，关闭括号之前）添加：

```java
        // ── Kelly 动态仓位 ────────────────────────────────────────────
        /** 是否启用 Kelly 仓位计算（false = 回退到原有 triggerSize * walletScore 公式） */
        private boolean kellyStakeEnabled = false;
        /** Kelly 折扣因子（0.3 = 30% Kelly，保守值） */
        private BigDecimal kellyFraction = new BigDecimal("0.30");

        // ── 时间感知止损 ──────────────────────────────────────────────
        /** 是否启用时间感知动态止损阈值（false = 使用 autoStopLossThresholdRate 固定值） */
        private boolean timeAwareStopLossEnabled = false;
        /** 止损阈值分段列表，按 remainingHours 降序排列，匹配第一个不超过剩余时间的档位 */
        private List<StopLossTier> stopLossTiers = new java.util.ArrayList<>();
        /** 是否启用趋势加速止损（连续 N 期下跌时提前止损） */
        private boolean trendStopLossEnabled = false;
        /** 趋势加速止损连续下跌周期数（每周期 = 一次 checkStopLoss 扫描，约 5 分钟） */
        private int trendStopLossConsecutivePeriods = 3;
```

在 `SmartWalletProperties` 类中（与 `SignalRisk` 同级）添加新内部类：

```java
    @Data
    public static class StopLossTier {
        /** 此阈值适用的最大市场剩余小时数（remainingHours <= this value） */
        private int remainingHours;
        /** 止损触发阈值（正数，0.25 = -25% ROI 触发） */
        private BigDecimal thresholdRate;
    }
```

- [ ] **Step 2: 在 application.yml 中添加对应配置（signal-risk 块末尾）**

在 `signal-risk:` 块末尾（`concentration-stake-penalty-max-rate: 0.50` 之后）添加：

```yaml
    # ── Kelly 动态仓位 ─────────────────────────────────────────
    # [TUNE] 启用后使用 calibratedFollowProbability 和入场价格计算 Kelly 仓位
    kelly-stake-enabled: true
    # [TUNE] Kelly 折扣因子，0.3 = 保守半 Kelly 以下
    kelly-fraction: 0.30
    # ── 时间感知止损 ──────────────────────────────────────────
    # [TUNE] 启用后按市场剩余时间动态收紧止损线
    time-aware-stop-loss-enabled: true
    # [TUNE] 止损分段（remainingHours 降序排列，matchs first tier where remaining <= remainingHours）
    stop-loss-tiers:
      - remaining-hours: 168
        threshold-rate: 0.35
      - remaining-hours: 24
        threshold-rate: 0.25
      - remaining-hours: 6
        threshold-rate: 0.15
      - remaining-hours: 0
        threshold-rate: 0.05
    # [TUNE] 启用趋势加速止损
    trend-stop-loss-enabled: true
    # [TUNE] 连续多少个扫描周期（约 5 分钟/周期）价格单调下跌且 ROI<0 时触发
    trend-stop-loss-consecutive-periods: 3
```

- [ ] **Step 3: 确认编译通过**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，无编译错误。

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java \
        coin-strategy-job/src/main/resources/application.yml
git commit -m "feat: add Kelly stake and time-aware stop-loss config fields"
```

---

## Task 3: 将 Kelly 接入 SmartWalletSignalRiskService

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSignalRiskService.java`

- [ ] **Step 1: 注入 KellyStakeCalculator**

在 `SmartWalletSignalRiskService` 的字段列表末尾添加（`@RequiredArgsConstructor` 自动注入）：

```java
    private final KellyStakeCalculator kellyStakeCalculator;
```

- [ ] **Step 2: 替换 recommendedStake 计算逻辑**

找到现有计算块（大约在 `evaluate()` 方法中）：

```java
        BigDecimal walletScore = profile == null
                ? BigDecimal.ZERO
                : leaderboardStateService.adjustWalletScore(profile, event);
        BigDecimal recommendedStake = triggerSize
                .multiply(walletScore)
                .multiply(properties.getBankrollUtilizationRate())
                .min(properties.getMaxStakeUsdc())
                .setScale(2, RoundingMode.HALF_UP);
        recommendedStake = recommendedStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
```

替换为：

```java
        BigDecimal walletScore = profile == null
                ? BigDecimal.ZERO
                : leaderboardStateService.adjustWalletScore(profile, event);

        // ── Kelly 动态仓位（当 kellyStakeEnabled=true 且 calibratedFollowProbability 可用时启用）
        BigDecimal recommendedStake;
        SmartWalletProperties.SignalRisk effectiveRisk = overrideRisk == null ? properties.getSignalRisk() : overrideRisk;
        if (effectiveRisk.isKellyStakeEnabled()) {
            BigDecimal kellyStake = kellyStakeCalculator.compute(
                    profitabilityEstimate.getCalibratedFollowProbability(),
                    price,
                    availableCapital,
                    effectiveRisk.getKellyFraction(),
                    executionWalletContextLoader.load().getMinOrderUsdc(),
                    properties.getMaxStakeUsdc()
            );
            if (kellyStake != null) {
                recommendedStake = kellyStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
            } else {
                // calibratedFollowProbability is null → fall back to legacy formula
                recommendedStake = triggerSize
                        .multiply(walletScore)
                        .multiply(properties.getBankrollUtilizationRate())
                        .min(properties.getMaxStakeUsdc())
                        .setScale(2, RoundingMode.HALF_UP);
                recommendedStake = recommendedStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
            }
        } else {
            recommendedStake = triggerSize
                    .multiply(walletScore)
                    .multiply(properties.getBankrollUtilizationRate())
                    .min(properties.getMaxStakeUsdc())
                    .setScale(2, RoundingMode.HALF_UP);
            recommendedStake = recommendedStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
        }
```

**注意**：此替换块必须位于 `profitabilityEstimate` 计算之后（因为 Kelly 需要 `getCalibratedFollowProbability()`），而 `profitabilityEstimate` 计算需要 `recommendedStake`（用于预估费用）。这造成循环依赖。

**解决方案**：先用旧公式计算一个 `preliminaryStake`，传给 profitabilityEstimate；再用 Kelly 计算最终 `recommendedStake`：

将 `profitabilityEstimate` 计算前的 `recommendedStake` 变量名改为 `preliminaryStake`，并在 `profitabilityEstimate` 计算之后再做 Kelly 替换。具体步骤：

1. 找到现有 `recommendedStake` 计算（在 `profitabilityEstimate` 之前），改变量名为 `preliminaryStake`：

```java
        BigDecimal walletScore = profile == null
                ? BigDecimal.ZERO
                : leaderboardStateService.adjustWalletScore(profile, event);
        BigDecimal preliminaryStake = triggerSize
                .multiply(walletScore)
                .multiply(properties.getBankrollUtilizationRate())
                .min(properties.getMaxStakeUsdc())
                .setScale(2, RoundingMode.HALF_UP);
        preliminaryStake = preliminaryStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
```

2. 将 `tradeProfitabilityScoringService.estimate(...)` 调用中的 `recommendedStake` 参数改为 `preliminaryStake`：

```java
        TradeProfitabilityScoringService.ProfitabilityEstimate profitabilityEstimate =
                tradeProfitabilityScoringService.estimate(profile, event, consensus, preliminaryStake);
```

3. 在 `profitabilityEstimate` 计算之后，添加 Kelly 最终仓位计算：

```java
        // ── Kelly 动态仓位 ─────────────────────────────────────────────────────────
        BigDecimal recommendedStake;
        SmartWalletProperties.SignalRisk effectiveRisk = overrideRisk == null ? properties.getSignalRisk() : overrideRisk;
        if (effectiveRisk.isKellyStakeEnabled()) {
            BigDecimal kellyStake = kellyStakeCalculator.compute(
                    profitabilityEstimate.getCalibratedFollowProbability(),
                    price,
                    availableCapital,
                    effectiveRisk.getKellyFraction(),
                    executionWalletContextLoader.load().getMinOrderUsdc(),
                    properties.getMaxStakeUsdc()
            );
            recommendedStake = (kellyStake != null)
                    ? kellyStake.min(availableCapital.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP)
                    : preliminaryStake;
        } else {
            recommendedStake = preliminaryStake;
        }
```

4. 后续所有对 `recommendedStake` 的引用保持不变（min-order 检查、return 值等）。

- [ ] **Step 3: 确认编译通过**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`。

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletSignalRiskService.java
git commit -m "feat: wire Kelly stake calculator into signal risk evaluation"
```

---

## Task 4: ExitSignalProvider 接口 + TimeAwareStopLossService

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExitSignalProvider.java`
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/TimeAwareStopLossService.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/TimeAwareStopLossServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
// TimeAwareStopLossServiceTest.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeAwareStopLossServiceTest {

    private SmartWalletProperties properties;
    private TimeAwareStopLossService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setTimeAwareStopLossEnabled(true);
        risk.setTrendStopLossEnabled(false);
        risk.setAutoStopLossThresholdRate(new BigDecimal("0.35")); // fallback

        SmartWalletProperties.StopLossTier tier7d = new SmartWalletProperties.StopLossTier();
        tier7d.setRemainingHours(168);
        tier7d.setThresholdRate(new BigDecimal("0.35"));

        SmartWalletProperties.StopLossTier tier24h = new SmartWalletProperties.StopLossTier();
        tier24h.setRemainingHours(24);
        tier24h.setThresholdRate(new BigDecimal("0.25"));

        SmartWalletProperties.StopLossTier tier6h = new SmartWalletProperties.StopLossTier();
        tier6h.setRemainingHours(6);
        tier6h.setThresholdRate(new BigDecimal("0.15"));

        SmartWalletProperties.StopLossTier tier0h = new SmartWalletProperties.StopLossTier();
        tier0h.setRemainingHours(0);
        tier0h.setThresholdRate(new BigDecimal("0.05"));

        risk.setStopLossTiers(List.of(tier7d, tier24h, tier6h, tier0h));
        properties.setSignalRisk(risk);
        service = new TimeAwareStopLossService(properties);
    }

    @Test
    void resolveThreshold_remaining8days_returns35pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(200); // >168h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.35"), threshold);
    }

    @Test
    void resolveThreshold_remaining48h_returns25pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(48); // 24-168h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.25"), threshold);
    }

    @Test
    void resolveThreshold_remaining10h_returns15pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(10); // 6-24h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.15"), threshold);
    }

    @Test
    void resolveThreshold_remaining2h_returns5pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(2); // <6h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.05"), threshold);
    }

    @Test
    void resolveThreshold_noMarketEndAt_returnsFallback() {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity();
        // marketInfo is null → no marketEndAt → fallback to autoStopLossThresholdRate
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.35"), threshold);
    }

    @Test
    void resolveThreshold_disabledFeature_returnsFallback() {
        properties.getSignalRisk().setTimeAwareStopLossEnabled(false);
        SmartWalletPositionEntity position = positionWithRemainingHours(2);
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.35"), threshold); // falls back to autoStopLossThresholdRate
    }

    // ── Trend stop-loss tests ─────────────────────────────────────────────────

    @Test
    void recordPrice_consecutiveDecline_triggersTrendStopLoss() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        service = new TimeAwareStopLossService(properties);

        Long positionId = 42L;
        // ROI is negative throughout (costBasis > currentValue)
        // Record 3 strictly declining prices
        service.recordMarkPrice(positionId, new BigDecimal("0.50"), new BigDecimal("0.60")); // markPrice < avgEntryPrice
        service.recordMarkPrice(positionId, new BigDecimal("0.45"), new BigDecimal("0.60"));
        service.recordMarkPrice(positionId, new BigDecimal("0.40"), new BigDecimal("0.60"));

        assertTrue(service.isTrendStopLossTriggered(positionId, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    @Test
    void recordPrice_notConsecutiveDecline_doesNotTrigger() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        service = new TimeAwareStopLossService(properties);

        Long positionId = 43L;
        service.recordMarkPrice(positionId, new BigDecimal("0.50"), new BigDecimal("0.60"));
        service.recordMarkPrice(positionId, new BigDecimal("0.55"), new BigDecimal("0.60")); // price bounced
        service.recordMarkPrice(positionId, new BigDecimal("0.40"), new BigDecimal("0.60"));

        assertFalse(service.isTrendStopLossTriggered(positionId, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    // helper
    private SmartWalletPositionEntity positionWithRemainingHours(int hoursRemaining) {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity();
        SmartWalletMarketInfoEntity marketInfo = new SmartWalletMarketInfoEntity();
        long endAt = System.currentTimeMillis() + (long) hoursRemaining * 3600_000L;
        marketInfo.setMarketEndAt(endAt);
        position.setMarketInfo(marketInfo);
        return position;
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -pl coin-strategy-job test -Dtest=TimeAwareStopLossServiceTest -q 2>&1 | tail -15
```
期望：编译失败（类不存在）。

- [ ] **Step 3: 实现 ExitSignalProvider 接口**

```java
// ExitSignalProvider.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;

import java.math.BigDecimal;

/**
 * Extension point for position exit signal logic.
 * Rule-based implementations (TimeAwareStopLossService) implement this interface.
 * Method-C ML models will provide alternative implementations that can be wired in
 * without modifying callers.
 */
public interface ExitSignalProvider {

    /**
     * Resolve the effective stop-loss threshold for a given position.
     * A threshold of 0.25 means "exit when ROI <= -25%".
     *
     * @param position position with marketInfo populated
     * @return threshold rate (positive number)
     */
    BigDecimal resolveStopLossThreshold(SmartWalletPositionEntity position);

    /**
     * Record the latest mark price for a position (used for trend analysis).
     *
     * @param positionId      position identifier
     * @param markPrice       current mark price
     * @param averageEntryPrice position's average entry price
     */
    void recordMarkPrice(Long positionId, BigDecimal markPrice, BigDecimal averageEntryPrice);

    /**
     * Returns true if the trend-based stop-loss should trigger for this position.
     *
     * @param positionId        position identifier
     * @param currentMarkPrice  latest mark price
     * @param averageEntryPrice position's average entry price
     */
    boolean isTrendStopLossTriggered(Long positionId, BigDecimal currentMarkPrice, BigDecimal averageEntryPrice);
}
```

- [ ] **Step 4: 实现 TimeAwareStopLossService**

```java
// TimeAwareStopLossService.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule-based exit signal provider that tightens stop-loss thresholds as market
 * end time approaches, and can trigger early exit on consecutive price decline.
 *
 * Price history is held in memory (lost on restart), which is acceptable since
 * the trend signal is advisory and the threshold-based stop-loss is the primary gate.
 */
@Service
public class TimeAwareStopLossService implements ExitSignalProvider {

    private final SmartWalletProperties properties;

    /** positionId → recent mark prices (newest last), bounded by trendStopLossConsecutivePeriods + 1 */
    private final Map<Long, Deque<BigDecimal>> priceHistory = new ConcurrentHashMap<>();

    public TimeAwareStopLossService(SmartWalletProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal resolveStopLossThreshold(SmartWalletPositionEntity position) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        BigDecimal fallback = risk.getAutoStopLossThresholdRate() == null
                ? new BigDecimal("0.35") : risk.getAutoStopLossThresholdRate();

        if (!risk.isTimeAwareStopLossEnabled()) {
            return fallback;
        }

        Long marketEndAt = position == null ? null : position.getMarketEndAt();
        if (marketEndAt == null || marketEndAt <= 0) {
            return fallback;
        }

        List<SmartWalletProperties.StopLossTier> tiers = risk.getStopLossTiers();
        if (tiers == null || tiers.isEmpty()) {
            return fallback;
        }

        long remainingMs = marketEndAt - System.currentTimeMillis();
        if (remainingMs <= 0) {
            // Market already expired — use tightest tier
            return tiers.stream()
                    .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                    .filter(r -> r != null)
                    .min(BigDecimal::compareTo)
                    .orElse(fallback);
        }

        long remainingHours = remainingMs / 3600_000L;

        // Find tightest matching tier: pick smallest thresholdRate among tiers where remainingHours <= tier.remainingHours
        return tiers.stream()
                .filter(tier -> remainingHours <= tier.getRemainingHours())
                .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                .filter(r -> r != null)
                .min(BigDecimal::compareTo)
                .orElse(fallback);
    }

    @Override
    public void recordMarkPrice(Long positionId, BigDecimal markPrice, BigDecimal averageEntryPrice) {
        if (positionId == null || markPrice == null) {
            return;
        }
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled()) {
            return;
        }
        int windowSize = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();

        Deque<BigDecimal> history = priceHistory.computeIfAbsent(positionId, id -> new ArrayDeque<>());
        history.addLast(markPrice);
        // Keep only last windowSize+1 entries
        while (history.size() > windowSize + 1) {
            history.pollFirst();
        }
    }

    @Override
    public boolean isTrendStopLossTriggered(Long positionId, BigDecimal currentMarkPrice, BigDecimal averageEntryPrice) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled() || positionId == null) {
            return false;
        }
        // Only trigger when position is already losing (ROI < 0)
        if (averageEntryPrice == null || currentMarkPrice == null
                || currentMarkPrice.compareTo(averageEntryPrice) >= 0) {
            return false;
        }

        Deque<BigDecimal> history = priceHistory.get(positionId);
        if (history == null) {
            return false;
        }

        int periods = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        if (history.size() < periods) {
            return false;
        }

        // Check last `periods` entries are strictly decreasing
        BigDecimal[] prices = history.toArray(new BigDecimal[0]);
        int start = prices.length - periods;
        for (int i = start; i < prices.length - 1; i++) {
            if (prices[i].compareTo(prices[i + 1]) <= 0) {
                return false; // not strictly decreasing
            }
        }
        return true;
    }

    /** Remove cached price history when a position is closed. Call from exit handling code. */
    public void clearPositionHistory(Long positionId) {
        if (positionId != null) {
            priceHistory.remove(positionId);
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
mvn -pl coin-strategy-job test -Dtest=TimeAwareStopLossServiceTest -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`，所有 7 个测试通过。

- [ ] **Step 6: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExitSignalProvider.java \
        coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/TimeAwareStopLossService.java \
        coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/TimeAwareStopLossServiceTest.java
git commit -m "feat: add ExitSignalProvider interface and TimeAwareStopLossService"
```

---

## Task 5: 将 TimeAwareStopLossService 接入 SmartWalletPositionSyncService

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java`

- [ ] **Step 1: 注入 TimeAwareStopLossService**

在 `SmartWalletPositionSyncService` 字段列表中添加（`@RequiredArgsConstructor` 自动注入）：

```java
    private final TimeAwareStopLossService timeAwareStopLossService;
```

- [ ] **Step 2: 修改 checkStopLoss() 使用动态阈值**

将现有 `checkStopLoss()` 方法替换为：

```java
    @Scheduled(fixedDelay = 300_000)
    public void checkStopLoss() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!properties.isEnabled() || risk == null || !risk.isEnableAutoStopLoss()) {
            return;
        }
        scanPaperPositionsForExitWithDynamicThreshold(AutoExitMode.STOP_LOSS);
    }
```

- [ ] **Step 3: 添加 scanPaperPositionsForExitWithDynamicThreshold 方法**

在 `scanPaperPositionsForExit()` 方法之后添加新方法：

```java
    private void scanPaperPositionsForExitWithDynamicThreshold(AutoExitMode mode) {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE")));
        marketInfoService.populatePositions(openPositions);

        for (SmartWalletPositionEntity position : openPositions) {
            // Record current mark price for trend analysis
            timeAwareStopLossService.recordMarkPrice(
                    position.getId(),
                    position.getLastMarkPrice(),
                    position.getAverageEntryPrice()
            );

            // Resolve dynamic threshold (falls back to fixed threshold when feature is disabled)
            BigDecimal threshold = timeAwareStopLossService.resolveStopLossThreshold(position);

            // Check threshold-based stop-loss
            ExitDecision decision = buildExitDecision(position, threshold, mode);

            // Check trend-based stop-loss (additional trigger)
            boolean trendTriggered = mode == AutoExitMode.STOP_LOSS
                    && timeAwareStopLossService.isTrendStopLossTriggered(
                            position.getId(),
                            position.getLastMarkPrice(),
                            position.getAverageEntryPrice()
                    );

            if (!decision.triggered() && !trendTriggered) {
                continue;
            }

            String exitReason = trendTriggered && !decision.triggered()
                    ? "TREND_STOP_LOSS_AUTO_EXIT"
                    : mode.reason();
            BigDecimal exitPrice = decision.triggered() ? decision.markPrice() : safe(position.getLastMarkPrice());

            try {
                SmartWalletPositionExitRequest exitRequest = new SmartWalletPositionExitRequest();
                exitRequest.setPrice(exitPrice);
                exitRequest.setQuantity(position.getQuantity());
                exitRequest.setReason(exitReason);
                SmartWalletPositionExitResultView exitResult = accountingService.exitPosition(position.getId(), exitRequest);
                timeAwareStopLossService.clearPositionHistory(position.getId());

                log.info("{} triggered positionId={} marketId={} outcome={} mark={} threshold={}% actual={}% pnl={} trendTriggered={} currentStatus={}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        position.getOutcome(),
                        exitPrice,
                        threshold.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        decision.rate().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        safe(exitResult.getRealizedPnlDeltaUsdc()).setScale(2, RoundingMode.HALF_UP),
                        trendTriggered,
                        exitResult.getCurrentStatus());
            } catch (Exception ex) {
                log.warn("{} exit failed for positionId={} marketId={}: {}",
                        mode.logLabel(),
                        position.getId(),
                        position.getMarketId(),
                        ex.getMessage(),
                        ex);
            }
        }
    }
```

- [ ] **Step 4: 确认编译通过**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java
git commit -m "feat: wire TimeAwareStopLossService into position stop-loss scan"
```

---

## Task 6: FollowTradeAttributionView + FollowTradeAttributionService

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/model/FollowTradeAttributionView.java`
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/FollowTradeAttributionService.java`

- [ ] **Step 1: 创建 FollowTradeAttributionView**

```java
// FollowTradeAttributionView.java
package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Map;

@Value
@Builder
public class FollowTradeAttributionView {

    /** 分组维度描述（如 "walletScore:HIGH" 或 "exitReason:TAKE_PROFIT_AUTO_EXIT"） */
    String groupKey;

    /** 本组交易笔数 */
    int tradeCount;

    /** 盈利笔数 */
    int winCount;

    /** 胜率 = winCount / tradeCount */
    BigDecimal winRate;

    /** 总已实现盈亏（USDC） */
    BigDecimal totalRealizedPnlUsdc;

    /** 平均已实现盈亏（USDC） */
    BigDecimal avgRealizedPnlUsdc;

    /** 平均成本（USDC） */
    BigDecimal avgCostBasisUsdc;

    /** 平均持仓时长（小时） */
    BigDecimal avgHoldingHours;

    /** 各退出原因笔数，key=exitReason, value=count */
    Map<String, Integer> exitTypeBreakdown;
}
```

- [ ] **Step 2: 创建 FollowTradeAttributionService**

```java
// FollowTradeAttributionService.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.FollowTradeAttributionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Read-only aggregation service for follow-trade PnL attribution.
 * Does not modify any position or task data.
 *
 * Supported groupBy values:
 *   WALLET_SCORE   — group by wallet score tier (LOW / MEDIUM / HIGH)
 *   EXIT_REASON    — group by SmartWalletOrderEntity.exitReason
 *   MARKET_TIMING  — group by remaining market hours at position open time
 */
@Service
@RequiredArgsConstructor
public class FollowTradeAttributionService {

    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletMarketInfoService marketInfoService;

    /**
     * Build attribution report grouped by the specified dimension.
     *
     * @param groupBy   "WALLET_SCORE" | "EXIT_REASON" | "MARKET_TIMING"
     * @param limit     max closed positions to scan (default 500)
     */
    public List<FollowTradeAttributionView> buildAttribution(String groupBy, Integer limit) {
        int scanLimit = (limit == null || limit <= 0) ? 500 : Math.min(limit, 2000);

        // Load closed positions
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus, List.of("CLOSED", "SETTLED", "CLAIMED"))
                        .orderByDesc(SmartWalletPositionEntity::getClosedAt)
                        .last("limit " + scanLimit));
        marketInfoService.populatePositions(positions);

        // Load exit orders for these positions (side=SELL, orderType=EXIT)
        List<Long> positionIds = positions.stream()
                .map(SmartWalletPositionEntity::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, String> exitReasonByPositionId = new LinkedHashMap<>();
        if (!positionIds.isEmpty()) {
            List<SmartWalletOrderEntity> exitOrders = orderMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletOrderEntity>()
                            .in(SmartWalletOrderEntity::getPositionId, positionIds)
                            .eq(SmartWalletOrderEntity::getSide, "SELL")
                            .eq(SmartWalletOrderEntity::getOrderType, "EXIT"));
            exitOrders.forEach(order -> {
                if (order.getPositionId() != null) {
                    exitReasonByPositionId.putIfAbsent(order.getPositionId(),
                            order.getExitReason() == null ? "UNKNOWN" : order.getExitReason());
                }
            });
        }

        // Group and aggregate
        String dimension = groupBy == null ? "EXIT_REASON" : groupBy.toUpperCase();
        return switch (dimension) {
            case "WALLET_SCORE" -> groupByWalletScore(positions, exitReasonByPositionId);
            case "MARKET_TIMING" -> groupByMarketTiming(positions, exitReasonByPositionId);
            default -> groupByExitReason(positions, exitReasonByPositionId);
        };
    }

    private List<FollowTradeAttributionView> groupByExitReason(
            List<SmartWalletPositionEntity> positions,
            Map<Long, String> exitReasonByPositionId) {
        Map<String, List<SmartWalletPositionEntity>> grouped = positions.stream()
                .collect(Collectors.groupingBy(p -> exitReasonByPositionId.getOrDefault(p.getId(), "UNKNOWN")));
        return buildViews(grouped);
    }

    private List<FollowTradeAttributionView> groupByMarketTiming(
            List<SmartWalletPositionEntity> positions,
            Map<Long, String> exitReasonByPositionId) {
        Map<String, List<SmartWalletPositionEntity>> grouped = positions.stream()
                .collect(Collectors.groupingBy(p -> resolveMarketTimingBucket(p)));
        return buildViews(grouped, exitReasonByPositionId);
    }

    private String resolveMarketTimingBucket(SmartWalletPositionEntity position) {
        Long marketEndAt = position.getMarketEndAt();
        if (marketEndAt == null || position.getOpenedAt() == null) {
            return "UNKNOWN";
        }
        long remainingAtOpenMs = marketEndAt - position.getOpenedAt().getTime();
        long remainingHours = remainingAtOpenMs / 3600_000L;
        if (remainingHours < 24) return "<24h";
        if (remainingHours < 168) return "1-7d";
        return ">7d";
    }

    private List<FollowTradeAttributionView> groupByWalletScore(
            List<SmartWalletPositionEntity> positions,
            Map<Long, String> exitReasonByPositionId) {
        // Find related follow tasks for each position via marketInfoId
        Map<Long, String> walletAddressByMarketInfoId = new LinkedHashMap<>();
        List<Long> marketInfoIds = positions.stream()
                .map(SmartWalletPositionEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!marketInfoIds.isEmpty()) {
            List<SmartWalletFollowTaskEntity> followTasks = followTaskMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                            .in(SmartWalletFollowTaskEntity::getMarketInfoId, marketInfoIds)
                            .eq(SmartWalletFollowTaskEntity::getStatus, "FOLLOWED"));
            followTasks.forEach(ft -> walletAddressByMarketInfoId.putIfAbsent(
                    ft.getMarketInfoId(), ft.getWalletAddress()));
        }

        // Fetch profiles
        List<String> walletAddresses = walletAddressByMarketInfoId.values().stream()
                .filter(Objects::nonNull).distinct().toList();
        Map<String, BigDecimal> walletScoreByAddress = new LinkedHashMap<>();
        if (!walletAddresses.isEmpty()) {
            List<SmartWalletProfileEntity> profiles = profileMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletProfileEntity>()
                            .in(SmartWalletProfileEntity::getWalletAddress, walletAddresses)
                            .eq(SmartWalletProfileEntity::getSource, "POLYMARKET"));
            profiles.forEach(p -> walletScoreByAddress.put(p.getWalletAddress(), p.getWalletScore()));
        }

        Map<String, List<SmartWalletPositionEntity>> grouped = positions.stream()
                .collect(Collectors.groupingBy(p -> {
                    String wallet = walletAddressByMarketInfoId.get(p.getMarketInfoId());
                    BigDecimal score = wallet == null ? null : walletScoreByAddress.get(wallet);
                    return resolveScoreBucket(score);
                }));
        return buildViews(grouped, exitReasonByPositionId);
    }

    private String resolveScoreBucket(BigDecimal walletScore) {
        if (walletScore == null) return "UNKNOWN";
        if (walletScore.compareTo(new BigDecimal("0.80")) >= 0) return "HIGH(≥0.80)";
        if (walletScore.compareTo(new BigDecimal("0.72")) >= 0) return "MEDIUM(0.72-0.80)";
        return "LOW(<0.72)";
    }

    private List<FollowTradeAttributionView> buildViews(Map<String, List<SmartWalletPositionEntity>> grouped) {
        return buildViews(grouped, Map.of());
    }

    private List<FollowTradeAttributionView> buildViews(
            Map<String, List<SmartWalletPositionEntity>> grouped,
            Map<Long, String> exitReasonByPositionId) {
        List<FollowTradeAttributionView> result = new ArrayList<>();
        grouped.forEach((groupKey, posGroup) -> {
            int tradeCount = posGroup.size();
            int winCount = 0;
            BigDecimal totalPnl = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalHoldingHours = BigDecimal.ZERO;
            Map<String, Integer> exitBreakdown = new LinkedHashMap<>();

            for (SmartWalletPositionEntity p : posGroup) {
                BigDecimal pnl = p.getRealizedPnlUsdc() == null ? BigDecimal.ZERO : p.getRealizedPnlUsdc();
                if (pnl.compareTo(BigDecimal.ZERO) > 0) winCount++;
                totalPnl = totalPnl.add(pnl);
                totalCost = totalCost.add(p.getCostBasisUsdc() == null ? BigDecimal.ZERO : p.getCostBasisUsdc());

                if (p.getOpenedAt() != null && p.getClosedAt() != null) {
                    long holdingMs = p.getClosedAt().getTime() - p.getOpenedAt().getTime();
                    totalHoldingHours = totalHoldingHours.add(
                            new BigDecimal(holdingMs).divide(new BigDecimal("3600000"), 2, RoundingMode.HALF_UP));
                }

                String reason = exitReasonByPositionId.getOrDefault(p.getId(), "UNKNOWN");
                exitBreakdown.merge(reason, 1, Integer::sum);
            }

            BigDecimal winRate = tradeCount == 0 ? BigDecimal.ZERO
                    : new BigDecimal(winCount).divide(new BigDecimal(tradeCount), 4, RoundingMode.HALF_UP);
            BigDecimal avgPnl = tradeCount == 0 ? BigDecimal.ZERO
                    : totalPnl.divide(new BigDecimal(tradeCount), 2, RoundingMode.HALF_UP);
            BigDecimal avgCost = tradeCount == 0 ? BigDecimal.ZERO
                    : totalCost.divide(new BigDecimal(tradeCount), 2, RoundingMode.HALF_UP);
            BigDecimal avgHours = tradeCount == 0 ? BigDecimal.ZERO
                    : totalHoldingHours.divide(new BigDecimal(tradeCount), 1, RoundingMode.HALF_UP);

            result.add(FollowTradeAttributionView.builder()
                    .groupKey(groupKey)
                    .tradeCount(tradeCount)
                    .winCount(winCount)
                    .winRate(winRate)
                    .totalRealizedPnlUsdc(totalPnl.setScale(2, RoundingMode.HALF_UP))
                    .avgRealizedPnlUsdc(avgPnl)
                    .avgCostBasisUsdc(avgCost)
                    .avgHoldingHours(avgHours)
                    .exitTypeBreakdown(exitBreakdown)
                    .build());
        });

        result.sort((a, b) -> b.getTotalRealizedPnlUsdc().compareTo(a.getTotalRealizedPnlUsdc()));
        return result;
    }
}
```

- [ ] **Step 3: 确认编译通过**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`。

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/model/FollowTradeAttributionView.java \
        coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/FollowTradeAttributionService.java
git commit -m "feat: add FollowTradeAttributionService and view model for PnL attribution"
```

---

## Task 7: 归因 API 端点

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/controller/SmartWalletController.java`

- [ ] **Step 1: 注入 FollowTradeAttributionService**

在 `SmartWalletController` 字段列表中添加（构造函数注入，参考现有字段模式）：

```java
    private final FollowTradeAttributionService followTradeAttributionService;
```

- [ ] **Step 2: 添加归因 API 端点**

在 `SmartWalletController` 中已有的 `/follow-tasks` 端点附近添加：

```java
    @Operation(summary = "跟单 PnL 归因分析",
               description = "按维度聚合已平仓跟单的盈亏、胜率、持仓时长、退出类型分布。" +
                       "groupBy: EXIT_REASON（按退出原因）| WALLET_SCORE（按钱包评分段）| MARKET_TIMING（按入场时市场剩余时间）")
    @GetMapping("/follow-trades/attribution")
    public List<FollowTradeAttributionView> followTradeAttribution(
            @RequestParam(defaultValue = "EXIT_REASON") String groupBy,
            @RequestParam(defaultValue = "500") Integer limit) {
        try {
            return followTradeAttributionService.buildAttribution(groupBy, limit);
        } catch (Exception e) {
            log.error("Failed to build follow-trade attribution groupBy={} limit={}", groupBy, limit, e);
            throw e;
        }
    }
```

确保 `FollowTradeAttributionView` 的 import 已添加：

```java
import com.metaverse.coin.strategy.job.smartwallet.model.FollowTradeAttributionView;
import com.metaverse.coin.strategy.job.smartwallet.service.FollowTradeAttributionService;
```

- [ ] **Step 3: 确认编译通过**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -10
```
期望：`BUILD SUCCESS`。

- [ ] **Step 4: 手工验证 API**

启动应用后执行（替换端口为实际值 8081）：

```bash
curl -s "http://localhost:8081/strategy/api/smart-wallet/follow-trades/attribution?groupBy=EXIT_REASON&limit=100" | python3 -m json.tool | head -40
```

期望：返回 JSON 数组，每项包含 `groupKey`、`tradeCount`、`winRate`、`totalRealizedPnlUsdc`、`exitTypeBreakdown` 等字段。

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/controller/SmartWalletController.java
git commit -m "feat: add /follow-trades/attribution API endpoint"
```

---

## 自检清单

- [x] §1 归因分析：FollowTradeAttributionService + API endpoint（Task 6-7）
- [x] §2 Kelly 仓位：KellyStakeCalculator + SmartWalletSignalRiskService 接入（Task 1、3）
- [x] §3 时间感知止损：TimeAwareStopLossService + SmartWalletPositionSyncService 接入（Task 4-5）
- [x] ExitSignalProvider 接口（方案 C 接入点）：Task 4
- [x] 所有新功能均有独立 enabled 开关（kellyStakeEnabled、timeAwareStopLossEnabled、trendStopLossEnabled）
- [x] 配置均在 application.yml 中定义（Task 2）
- [x] 趋势止损价格历史用内存存储，不需要 DB 变更（Task 4 注释说明）
- [x] Kelly 与 profitabilityEstimate 循环依赖问题通过 preliminaryStake 解决（Task 3）
