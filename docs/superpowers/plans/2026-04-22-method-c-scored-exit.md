# 归因数据补全 + 方案C 特征驱动退出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 WALLET_SCORE 归因的 UNKNOWN 率过高问题，并实现方案C基础架构：基于时间层级+ROI 动量的特征评分退出信号提供者，通过配置在规则退出和评分退出之间切换。

**Architecture:**
- 归因修复：position → entry order (side=BUY, orderType=ENTRY) → walletAddress → profile.walletScore，绕过不稳定的 marketInfoId → followTask 路径。
- 方案C：`ScoredExitSignalProvider implements ExitSignalProvider`，在时间分层阈值基础上叠加 ROI 动量惩罚（仓位亏损越深，阈值收紧越多）。Spring `@Configuration` 工厂根据 `exit-signal-provider-mode: RULES|SCORING` 决定注入哪个实现。`SmartWalletPositionSyncService` 改为依赖 `ExitSignalProvider` 接口，不再耦合具体类。

**Tech Stack:** Java 17, Spring Boot (`@Configuration` bean factory), MyBatis-Plus, Lombok, JUnit5, application.yml

---

## 文件地图

| 操作 | 文件路径 | 职责 |
|------|---------|------|
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/FollowTradeAttributionService.java` | 修复 groupByWalletScore：用入场订单的 walletAddress 替代 followTask 路径 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExitSignalProvider.java` | 添加 `clearPositionHistory(Long)` 默认 no-op，使接口可安全替换 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java` | 添加 `exitSignalProviderMode` 字段 + `ExitScoring` 内部类 |
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/ExitSignalProviderConfig.java` | Spring @Configuration，按 mode 提供 @Primary ExitSignalProvider bean |
| 新建 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProvider.java` | 方案C实现：时间层级基准 + ROI 动量惩罚 |
| 新建 | `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProviderTest.java` | 单元测试 |
| 修改 | `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java` | 字段改为 `ExitSignalProvider`（接口），移除对 TimeAwareStopLossService 的直接依赖 |
| 修改 | `coin-strategy-job/src/main/resources/application.yml` | 添加 exit-signal-provider-mode 和 ExitScoring 配置 |

---

## Task 1: 修复 FollowTradeAttributionService WALLET_SCORE 归因

**Problem:** 当前实现通过 `position.marketInfoId → followTask.walletAddress` 查找触发钱包，但同一市场可能有多个 followTask（多钱包共识信号），只取第一个导致不准确，且历史数据中 followTask 状态过滤可能遗漏记录。

**Fix:** 改为 `position.id → entry order (side=BUY, orderType=ENTRY) → order.walletAddress`，直接从入场订单读取触发钱包地址，精确且无多义性。

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/FollowTradeAttributionService.java`

- [ ] **Step 1: 修改 groupByWalletScore 方法**

将 `FollowTradeAttributionService.java` 中的 `groupByWalletScore` 方法替换为：

```java
private List<FollowTradeAttributionView> groupByWalletScore(
        List<SmartWalletPositionEntity> positions,
        Map<Long, String> exitReasonByPositionId) {
    // Resolve wallet address from entry orders: position → BUY/ENTRY order → walletAddress
    // This is more accurate than marketInfoId → followTask, which may have multiple tasks per market
    Map<Long, String> walletAddressByPositionId = new LinkedHashMap<>();
    List<Long> positionIds = positions.stream()
            .map(SmartWalletPositionEntity::getId)
            .filter(Objects::nonNull)
            .toList();
    if (!positionIds.isEmpty()) {
        List<SmartWalletOrderEntity> entryOrders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .in(SmartWalletOrderEntity::getPositionId, positionIds)
                        .eq(SmartWalletOrderEntity::getSide, "BUY")
                        .eq(SmartWalletOrderEntity::getOrderType, "ENTRY"));
        entryOrders.forEach(order -> {
            if (order.getPositionId() != null && order.getWalletAddress() != null) {
                walletAddressByPositionId.putIfAbsent(order.getPositionId(), order.getWalletAddress());
            }
        });
    }

    // Fetch profiles
    List<String> walletAddresses = walletAddressByPositionId.values().stream()
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
                String wallet = walletAddressByPositionId.get(p.getId());
                BigDecimal score = wallet == null ? null : walletScoreByAddress.get(wallet);
                return resolveScoreBucket(score);
            }));
    return buildViews(grouped, exitReasonByPositionId);
}
```

同时删除不再需要的 `followTaskMapper` 字段和 `SmartWalletFollowTaskMapper` 依赖（先检查 `buildAttribution` 里是否还有其他用途，若无则删除）：

```java
// 字段列表由
private final SmartWalletPositionMapper positionMapper;
private final SmartWalletOrderMapper orderMapper;
private final SmartWalletFollowTaskMapper followTaskMapper;
private final SmartWalletProfileMapper profileMapper;
private final SmartWalletMarketInfoService marketInfoService;

// 改为（删除 followTaskMapper）
private final SmartWalletPositionMapper positionMapper;
private final SmartWalletOrderMapper orderMapper;
private final SmartWalletProfileMapper profileMapper;
private final SmartWalletMarketInfoService marketInfoService;
```

- [ ] **Step 2: 确认编译通过**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job -am compile -DskipTests -q
echo $?
```

期望：退出码 0，无输出。

- [ ] **Step 3: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/FollowTradeAttributionService.java
git commit -m "fix: use entry order walletAddress for WALLET_SCORE attribution"
```

---

## Task 2: 为 ExitSignalProvider 接口添加 clearPositionHistory 默认方法

**Why:** `SmartWalletPositionSyncService` 在成功退出后调用 `timeAwareStopLossService.clearPositionHistory(positionId)`。改为接口依赖后，此方法必须在接口上声明。`ScoredExitSignalProvider` 也维护价格历史，实现此方法；其他未来实现可选覆盖（接口提供 no-op 默认）。

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExitSignalProvider.java`

- [ ] **Step 1: 在接口中添加 clearPositionHistory 默认方法**

将 `ExitSignalProvider.java` 完整替换为：

```java
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
     * @param positionId        position identifier
     * @param markPrice         current mark price
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

    /**
     * Clear cached price history when a position is closed.
     * Implementations that maintain in-memory history should override this.
     * Default: no-op (safe for stateless implementations).
     */
    default void clearPositionHistory(Long positionId) {
        // no-op by default
    }
}
```

- [ ] **Step 2: 确认编译通过**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job -am compile -DskipTests -q
echo $?
```

期望：退出码 0（TimeAwareStopLossService 已有 clearPositionHistory 方法，编译不受影响）。

- [ ] **Step 3: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ExitSignalProvider.java
git commit -m "feat: add clearPositionHistory default method to ExitSignalProvider interface"
```

---

## Task 3: 配置扩展 + Spring Bean 工厂

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java`
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/ExitSignalProviderConfig.java`
- Modify: `coin-strategy-job/src/main/resources/application.yml`

- [ ] **Step 1: 在 SmartWalletProperties.SignalRisk 中添加新字段**

在 `SmartWalletProperties.SignalRisk` 类的 trend-stop-loss 字段之后（即 `trendStopLossConsecutivePeriods` 后面）添加：

```java
        // ── 方案C：退出信号提供者模式 ────────────────────────────────────
        /**
         * 退出信号提供者模式：
         *   RULES   - 规则退出（时间感知止损 + 趋势止损，即 TimeAwareStopLossService）
         *   SCORING - 特征评分退出（时间层级基准 + ROI 动量调整，即 ScoredExitSignalProvider）
         */
        private String exitSignalProviderMode = "RULES";
        /** 评分退出：ROI 动量权重（每 1% 亏损对基准阈值的缩减量，默认 0.10 = 每亏 10% 收紧 1%） */
        private BigDecimal exitScoringRoiMomentumWeight = new BigDecimal("0.10");
        /** 评分退出：最小止损阈值下限（防止评分后阈值过小导致频繁止损） */
        private BigDecimal exitScoringMinThreshold = new BigDecimal("0.03");
        /** 评分退出：最大止损阈值上限（防止评分后阈值过大导致止损失效） */
        private BigDecimal exitScoringMaxThreshold = new BigDecimal("0.50");
```

- [ ] **Step 2: 新建 ExitSignalProviderConfig.java**

```java
package com.metaverse.coin.strategy.job.smartwallet.config;

import com.metaverse.coin.strategy.job.smartwallet.service.ExitSignalProvider;
import com.metaverse.coin.strategy.job.smartwallet.service.ScoredExitSignalProvider;
import com.metaverse.coin.strategy.job.smartwallet.service.TimeAwareStopLossService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects which ExitSignalProvider implementation to use based on
 * smart-wallet.signal-risk.exit-signal-provider-mode:
 *   RULES   → TimeAwareStopLossService (rule-based, default)
 *   SCORING → ScoredExitSignalProvider (Method C feature scoring)
 */
@Configuration
public class ExitSignalProviderConfig {

    @Bean
    @Primary
    public ExitSignalProvider exitSignalProvider(
            SmartWalletProperties properties,
            TimeAwareStopLossService timeAwareStopLossService,
            ScoredExitSignalProvider scoredExitSignalProvider) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        String mode = (risk == null || risk.getExitSignalProviderMode() == null)
                ? "RULES" : risk.getExitSignalProviderMode().toUpperCase();
        return "SCORING".equals(mode) ? scoredExitSignalProvider : timeAwareStopLossService;
    }
}
```

- [ ] **Step 3: 在 application.yml 的 signal-risk 下添加配置**

在 `trend-stop-loss-consecutive-periods: 3` 之后添加：

```yaml
          exit-signal-provider-mode: RULES   # RULES = 规则止损; SCORING = 方案C特征评分退出
          exit-scoring-roi-momentum-weight: 0.10
          exit-scoring-min-threshold: 0.03
          exit-scoring-max-threshold: 0.50
```

- [ ] **Step 4: 确认编译通过（ScoredExitSignalProvider 尚未创建，先验证配置类编译）**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job -am compile -DskipTests -q 2>&1 | grep -v "^$"
```

期望：编译失败，提示找不到 `ScoredExitSignalProvider`（正常，下一个 Task 创建）。

- [ ] **Step 5: Commit（配置类）**

```bash
git add \
  coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/SmartWalletProperties.java \
  coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/config/ExitSignalProviderConfig.java \
  coin-strategy-job/src/main/resources/application.yml
git commit -m "feat: add exit-signal-provider-mode config and ExitSignalProviderConfig factory"
```

---

## Task 4: ScoredExitSignalProvider — 方案C实现

**Algorithm:**
```
baseThreshold  = resolveTimeTierThreshold(remainingHours)  // same tier logic as TimeAwareStopLossService
currentRoiPct  = (markPrice - avgEntry) / avgEntry         // negative when losing
roiPenalty     = max(0, abs(currentRoiPct) * roiMomentumWeight) when currentRoiPct < 0
finalThreshold = clamp(baseThreshold - roiPenalty, minThreshold, maxThreshold)
```

Example: remaining=48h → base=0.25, position ROI=-20%, weight=0.10 → penalty=0.02 → threshold=0.23

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProvider.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProviderTest.java`

- [ ] **Step 1: 写失败测试**

```java
// ScoredExitSignalProviderTest.java
package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoredExitSignalProviderTest {

    private SmartWalletProperties properties;
    private ScoredExitSignalProvider provider;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setTimeAwareStopLossEnabled(true);
        risk.setAutoStopLossThresholdRate(new BigDecimal("0.35")); // fallback
        risk.setExitScoringRoiMomentumWeight(new BigDecimal("0.10"));
        risk.setExitScoringMinThreshold(new BigDecimal("0.03"));
        risk.setExitScoringMaxThreshold(new BigDecimal("0.50"));

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
        provider = new ScoredExitSignalProvider(properties);
    }

    // remaining=48h → base=0.25; position breakeven (ROI=0) → no penalty → 0.25
    @Test
    void threshold_breakevenPosition_returnsBaseTimeThreshold() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(48);
        pos.setLastMarkPrice(new BigDecimal("0.50"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = 0
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.25, penalty=0 → 0.25
        assertEquals(new BigDecimal("0.25"), t);
    }

    // remaining=48h → base=0.25; ROI=-20%, weight=0.10 → penalty=0.02 → threshold=0.23
    @Test
    void threshold_losingPosition_tightensThreshold() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(48);
        pos.setLastMarkPrice(new BigDecimal("0.40"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = -20%
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.25, penalty=0.20*0.10=0.02 → 0.23
        assertEquals(new BigDecimal("0.23"), t);
    }

    // remaining=2h → base=0.05; ROI=-60%, weight=0.10 → penalty=0.06 → floor at minThreshold=0.03
    @Test
    void threshold_deeplyLosing_clampedToMin() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(2);
        pos.setLastMarkPrice(new BigDecimal("0.20"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = -60%
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.05, penalty=0.60*0.10=0.06 → 0.05-0.06=-0.01 → clamped to 0.03
        assertEquals(new BigDecimal("0.03"), t);
    }

    // winning position: ROI > 0 → no penalty → base threshold unchanged
    @Test
    void threshold_winningPosition_noReduction() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(10);
        pos.setLastMarkPrice(new BigDecimal("0.70"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = +40%
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.15, penalty=0 (positive ROI → no penalty) → 0.15
        assertEquals(new BigDecimal("0.15"), t);
    }

    // null markPrice → fall back to base time threshold without penalty
    @Test
    void threshold_nullMarkPrice_returnsBaseTier() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(48);
        pos.setLastMarkPrice(null);
        pos.setAverageEntryPrice(new BigDecimal("0.50"));
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        assertEquals(new BigDecimal("0.25"), t);
    }

    // trend triggered: 3 consecutive declines while ROI < 0
    @Test
    void trendStopLoss_consecutiveDeclines_triggers() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        provider = new ScoredExitSignalProvider(properties);

        Long pid = 99L;
        provider.recordMarkPrice(pid, new BigDecimal("0.50"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.45"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.40"), new BigDecimal("0.60"));

        assertTrue(provider.isTrendStopLossTriggered(pid, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    // clearPositionHistory removes cached price data
    @Test
    void clearPositionHistory_removesPriceData() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        provider = new ScoredExitSignalProvider(properties);

        Long pid = 100L;
        provider.recordMarkPrice(pid, new BigDecimal("0.50"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.45"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.40"), new BigDecimal("0.60"));
        assertTrue(provider.isTrendStopLossTriggered(pid, new BigDecimal("0.40"), new BigDecimal("0.60")));

        provider.clearPositionHistory(pid);
        assertFalse(provider.isTrendStopLossTriggered(pid, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    private SmartWalletPositionEntity positionWithRemainingHours(int hoursRemaining) {
        SmartWalletPositionEntity pos = new SmartWalletPositionEntity();
        SmartWalletMarketInfoEntity marketInfo = new SmartWalletMarketInfoEntity();
        marketInfo.setMarketEndAt(System.currentTimeMillis() + (long) hoursRemaining * 3600_000L);
        pos.setMarketInfo(marketInfo);
        return pos;
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（类不存在）**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job surefire:test -DwithTests=true -Dtest=ScoredExitSignalProviderTest 2>&1 | tail -5
```

期望：`No tests matching pattern "ScoredExitSignalProviderTest" were executed`（未编译）。

- [ ] **Step 3: 实现 ScoredExitSignalProvider**

```java
package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Method-C exit signal provider.
 *
 * Algorithm:
 *   baseThreshold  = resolveTimeTierThreshold(remainingHours)   [same tier logic as TimeAwareStopLossService]
 *   currentRoiPct  = (markPrice - avgEntry) / avgEntry          [negative when losing]
 *   roiPenalty     = max(0, abs(roiPct) * roiMomentumWeight)    [only for losing positions]
 *   finalThreshold = clamp(baseThreshold - roiPenalty, minThreshold, maxThreshold)
 *
 * Example: remaining=48h → base=0.25, ROI=-20%, weight=0.10 → penalty=0.02 → threshold=0.23
 *
 * Trend stop-loss logic is identical to TimeAwareStopLossService (in-memory price history).
 */
@Service
public class ScoredExitSignalProvider implements ExitSignalProvider {

    private static final int SCALE = 8;
    private final SmartWalletProperties properties;
    private final Map<Long, Deque<BigDecimal>> priceHistory = new ConcurrentHashMap<>();

    public ScoredExitSignalProvider(SmartWalletProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal resolveStopLossThreshold(SmartWalletPositionEntity position) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        BigDecimal fallback = risk.getAutoStopLossThresholdRate() == null
                ? new BigDecimal("0.35") : risk.getAutoStopLossThresholdRate();

        BigDecimal base = resolveTimeTierThreshold(position, risk, fallback);

        // Compute ROI penalty
        BigDecimal markPrice = position == null ? null : position.getLastMarkPrice();
        BigDecimal avgEntry = position == null ? null : position.getAverageEntryPrice();
        BigDecimal penalty = BigDecimal.ZERO;

        if (markPrice != null && avgEntry != null && avgEntry.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal roiPct = markPrice.subtract(avgEntry).divide(avgEntry, SCALE, RoundingMode.HALF_UP);
            if (roiPct.compareTo(BigDecimal.ZERO) < 0) {
                // Losing position: tighten threshold proportionally to loss magnitude
                BigDecimal weight = risk.getExitScoringRoiMomentumWeight() == null
                        ? new BigDecimal("0.10") : risk.getExitScoringRoiMomentumWeight();
                penalty = roiPct.abs().multiply(weight).setScale(4, RoundingMode.HALF_UP);
            }
        }

        BigDecimal minT = risk.getExitScoringMinThreshold() == null
                ? new BigDecimal("0.03") : risk.getExitScoringMinThreshold();
        BigDecimal maxT = risk.getExitScoringMaxThreshold() == null
                ? new BigDecimal("0.50") : risk.getExitScoringMaxThreshold();

        BigDecimal result = base.subtract(penalty).setScale(4, RoundingMode.HALF_UP);
        if (result.compareTo(minT) < 0) return minT;
        if (result.compareTo(maxT) > 0) return maxT;
        return result;
    }

    /**
     * Resolve time-tier baseline threshold — same matching logic as TimeAwareStopLossService.
     * Tiers sorted descending by remainingHours; find tier with largest remainingHours <= actualHours.
     */
    private BigDecimal resolveTimeTierThreshold(SmartWalletPositionEntity position,
                                                SmartWalletProperties.SignalRisk risk,
                                                BigDecimal fallback) {
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
            return tiers.stream()
                    .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                    .filter(r -> r != null)
                    .min(BigDecimal::compareTo)
                    .orElse(fallback);
        }
        long remainingHours = remainingMs / 3600_000L;
        return tiers.stream()
                .filter(tier -> tier.getRemainingHours() <= remainingHours)
                .max((a, b) -> Integer.compare(a.getRemainingHours(), b.getRemainingHours()))
                .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                .orElse(fallback);
    }

    @Override
    public void recordMarkPrice(Long positionId, BigDecimal markPrice, BigDecimal averageEntryPrice) {
        if (positionId == null || markPrice == null) return;
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled()) return;
        int windowSize = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        Deque<BigDecimal> history = priceHistory.computeIfAbsent(positionId, id -> new ArrayDeque<>());
        history.addLast(markPrice);
        while (history.size() > windowSize + 1) {
            history.pollFirst();
        }
    }

    @Override
    public boolean isTrendStopLossTriggered(Long positionId, BigDecimal currentMarkPrice, BigDecimal averageEntryPrice) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled() || positionId == null) return false;
        if (averageEntryPrice == null || currentMarkPrice == null
                || currentMarkPrice.compareTo(averageEntryPrice) >= 0) return false;
        Deque<BigDecimal> history = priceHistory.get(positionId);
        if (history == null) return false;
        int periods = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        if (history.size() < periods) return false;
        BigDecimal[] prices = history.toArray(new BigDecimal[0]);
        int start = prices.length - periods;
        for (int i = start; i < prices.length - 1; i++) {
            if (prices[i].compareTo(prices[i + 1]) <= 0) return false;
        }
        return true;
    }

    @Override
    public void clearPositionHistory(Long positionId) {
        if (positionId != null) priceHistory.remove(positionId);
    }
}
```

- [ ] **Step 4: 编译测试类**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
JAVA_HOME=$JAVA_HOME mvn -pl coin-strategy-job -am compile -DskipTests -q

CLASSPATH=$(JAVA_HOME=$JAVA_HOME mvn -pl coin-strategy-job -DwithTests=true dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)

$JAVA_HOME/bin/javac \
  -source 17 -target 17 \
  -cp "coin-strategy-job/target/classes:coin-strategy-job/target/test-classes:coin-strategy-common/common-model/target/classes:$CLASSPATH" \
  -d coin-strategy-job/target/test-classes \
  coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProviderTest.java
echo $?
```

期望：退出码 0，无输出。

- [ ] **Step 5: 运行测试，确认通过**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job surefire:test -DwithTests=true -Dtest=ScoredExitSignalProviderTest 2>&1 | grep -E "Tests run|BUILD"
```

期望：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add \
  coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProvider.java \
  coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/smartwallet/service/ScoredExitSignalProviderTest.java
git commit -m "feat: add ScoredExitSignalProvider (Method C feature-scored exit)"
```

---

## Task 5: SmartWalletPositionSyncService 改为依赖接口

**Why:** 目前字段是 `private final TimeAwareStopLossService timeAwareStopLossService`，无法在不改代码的前提下切换到 `ScoredExitSignalProvider`。改为 `private final ExitSignalProvider exitSignalProvider`，通过 Task 3 的 `@Primary` bean 注入。

**Files:**
- Modify: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java`

- [ ] **Step 1: 替换字段声明和所有调用**

在 `SmartWalletPositionSyncService.java` 中：

1. 将字段 `private final TimeAwareStopLossService timeAwareStopLossService;` 改为：

```java
private final ExitSignalProvider exitSignalProvider;
```

2. 在 `scanPaperPositionsForExitWithDynamicThreshold` 方法中，将所有 `timeAwareStopLossService.` 调用改为 `exitSignalProvider.`（共 4 处）：

```java
// 原: timeAwareStopLossService.recordMarkPrice(...)
exitSignalProvider.recordMarkPrice(
        position.getId(),
        position.getLastMarkPrice(),
        position.getAverageEntryPrice()
);

// 原: BigDecimal threshold = timeAwareStopLossService.resolveStopLossThreshold(position);
BigDecimal threshold = exitSignalProvider.resolveStopLossThreshold(position);

// 原: timeAwareStopLossService.isTrendStopLossTriggered(...)
boolean trendTriggered = mode == AutoExitMode.STOP_LOSS
        && exitSignalProvider.isTrendStopLossTriggered(
                position.getId(),
                position.getLastMarkPrice(),
                position.getAverageEntryPrice()
        );

// 原: timeAwareStopLossService.clearPositionHistory(position.getId());
exitSignalProvider.clearPositionHistory(position.getId());
```

3. 删除 import（若有显式 import）：

```java
// 删除这行（如果存在）：
import com.metaverse.coin.strategy.job.smartwallet.service.TimeAwareStopLossService;
```

- [ ] **Step 2: 确认编译通过**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job -am compile -DskipTests -q
echo $?
```

期望：退出码 0，无输出。

- [ ] **Step 3: 验证所有新旧测试均通过**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -pl coin-strategy-job surefire:test \
  -DwithTests=true \
  -Dtest="KellyStakeCalculatorTest,TimeAwareStopLossServiceTest,ScoredExitSignalProviderTest" \
  2>&1 | grep -E "Tests run|BUILD"
```

期望：3 个测试类，全部通过，`BUILD SUCCESS`。

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/SmartWalletPositionSyncService.java
git commit -m "refactor: SmartWalletPositionSyncService now depends on ExitSignalProvider interface"
```

---

## 自检清单

- [x] **Option 3 修复（Task 1）**：FollowTradeAttributionService WALLET_SCORE 归因改用 entry order.walletAddress，删除 followTaskMapper 依赖
- [x] **接口扩展（Task 2）**：ExitSignalProvider 新增 `clearPositionHistory` 默认方法，现有实现不受影响
- [x] **配置扩展（Task 3）**：exitSignalProviderMode + ExitScoring 权重配置；ExitSignalProviderConfig @Primary bean
- [x] **方案C实现（Task 4）**：ScoredExitSignalProvider 时间层级基准 + ROI 动量惩罚，6 个单元测试覆盖边界
- [x] **接口解耦（Task 5）**：SmartWalletPositionSyncService 依赖 ExitSignalProvider 接口；切换到 SCORING 模式只需改 yml

**关键类型一致性检查：**
- `ScoredExitSignalProvider` 构造器参数：`SmartWalletProperties`（与 ExitSignalProviderConfig 中注入一致）
- `ExitSignalProviderConfig` 注入：`TimeAwareStopLossService`（@Service）+ `ScoredExitSignalProvider`（@Service）+ `@Primary ExitSignalProvider` 三个 bean 共存不冲突
- `clearPositionHistory` 在 `ExitSignalProvider`（default no-op）、`TimeAwareStopLossService`（覆盖）、`ScoredExitSignalProvider`（覆盖）三处均一致

**占位符扫描：** 无 TBD / TODO / 未完成步骤。
