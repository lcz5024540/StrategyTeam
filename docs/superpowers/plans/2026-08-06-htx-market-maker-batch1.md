# HTX 做市策略 — 第一批（阶段 A+B+C）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭好做市策略的数据/配置地基、纯定价与闸门逻辑、以及持久化方法——全部无外部副作用，可独立编译测试提交，为第二批（策略服务）打底。

**Architecture:** 新组件位于包 `com.metaverse.coin.strategy.htx.strategy`（纯逻辑）与 `...htx.config`（配置），复用现有 `htx_locked_pair` 表（加 `strategy` 列区分做市单）与 `HtxTradeRecorder`（DB 唯一入口，加 MM 专用方法）。本批不触碰调度器、执行器、对账器。

**Tech Stack:** Java 17（构建必须 JDK17）、Spring Boot 2.7.18、MyBatis-Plus 3.5.5、Lombok、JUnit5 + Mockito。

**构建环境（每条 mvn 前必设）：**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
```

**批次范围：** 本文件仅覆盖第一批 = 阶段 A（数据/配置）+ 阶段 B（纯逻辑）+ 阶段 C（持久化）。第二批（MarketMakerService）、第三批（Scheduler+集成）另出计划。

---

## 文件结构（本批）

| 文件 | 动作 | 职责 |
|---|---|---|
| `coin-strategy-htx/src/main/resources/db/schema.sql` | 改 | `htx_locked_pair` 加 `strategy VARCHAR(16)` 列 |
| `.../persistence/entity/HtxLockedPairEntity.java` | 改 | 加 `private String strategy;` |
| `.../persistence/HtxStatus.java` | 改 | 加 MM_* 状态常量 + `STRATEGY_MARKET_MAKER` |
| `.../config/HtxMarketMakerProperties.java` | 新 | `@ConfigurationProperties(prefix="htx.market-maker")` |
| `.../strategy/MarketMakerMath.java` | 新 | 纯静态：买卖价/qty/不亏闸/min-gap |
| `.../strategy/MarketMakerCell.java` | 新 | cell 值对象 |
| `.../persistence/HtxTradeRecorder.java` | 改 | 加 MM 增/查方法 |

---

## 阶段 A — 数据 / 配置地基

### Task A1: htx_locked_pair 加 strategy 列 + 实体字段

**Files:**
- Modify: `coin-strategy-htx/src/main/resources/db/schema.sql:2-23`
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/entity/HtxLockedPairEntity.java:31`

- [ ] **Step 1: 在 schema.sql 的 htx_locked_pair 建表语句里加 strategy 列**

在 `dry_run` 行之后、`message` 行之前插入。改后该表片段应为：

```sql
    net_quote_cost      DECIMAL(36,18) DEFAULT 0,
    dry_run             TINYINT(1)   NOT NULL DEFAULT 0,
    strategy            VARCHAR(16),
    message             VARCHAR(512),
```

说明：H2（测试）与 MySQL8（生产全新建表）均兼容。生产是既有表且 `SPRING_SQL_INIT_MODE=never`，需人工 `ALTER TABLE htx_locked_pair ADD COLUMN strategy VARCHAR(16) NULL;`（列于第三批上线步骤，本批不动生产）。

- [ ] **Step 2: 在实体加 strategy 字段**

`HtxLockedPairEntity.java` 在 `private Boolean dryRun;` 之后加：

```java
    private Boolean dryRun;
    private String strategy;
    private String message;
```

- [ ] **Step 3: 编译验证**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx -am compile -q`
Expected: BUILD SUCCESS（无编译错）

- [ ] **Step 4: 提交**

```bash
git add coin-strategy-htx/src/main/resources/db/schema.sql \
        coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/entity/HtxLockedPairEntity.java
git commit -m "feat(htx-mm): htx_locked_pair 加 strategy 列区分做市单"
```

---

### Task A2: HtxStatus 加 MM 状态常量

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxStatus.java:16`

- [ ] **Step 1: 加常量**

在 `public static final String REJECTED = "REJECTED";` 之后加：

```java
    public static final String REJECTED = "REJECTED";
    // market-maker（做市）状态
    public static final String MM_RESTING_BUY = "MM_RESTING_BUY";
    public static final String MM_RESTING_SELL = "MM_RESTING_SELL";
    public static final String MM_DONE = "MM_DONE";
    public static final String MM_CANCELLED_BUY = "MM_CANCELLED_BUY";
    /** htx_locked_pair.strategy 取值：做市单。执行 SDK 单为 NULL。 */
    public static final String STRATEGY_MARKET_MAKER = "MARKET_MAKER";
```

- [ ] **Step 2: 编译验证**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxStatus.java
git commit -m "feat(htx-mm): HtxStatus 加 MM_* 状态与 MARKET_MAKER strategy 常量"
```

---

### Task A3: HtxMarketMakerProperties 配置类

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerProperties.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerPropertiesTest.java`

- [ ] **Step 1: 写失败测试（属性绑定 + 默认值）**

```java
package com.metaverse.coin.strategy.htx.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtxMarketMakerPropertiesTest {

    private HtxMarketMakerProperties bind(MockEnvironment env) {
        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        return binder.bind("htx.market-maker", Bindable.of(HtxMarketMakerProperties.class))
                .orElse(new HtxMarketMakerProperties());
    }

    @Test void defaults_are_conservative() {
        HtxMarketMakerProperties p = new HtxMarketMakerProperties();
        assertFalse(p.isEnabled());                                   // 默认关（急停语义）
        assertEquals(List.of("btcusdt", "ethusdt"), p.getSymbols());  // 白名单
        assertEquals(10_000L, p.getPollIntervalMs());
        assertEquals(0, new BigDecimal("0.005").compareTo(p.getSpreadPct()));
        assertEquals(0, new BigDecimal("0.002").compareTo(p.getFeeRatePerSide()));
        assertEquals(0, new BigDecimal("20").compareTo(p.getPerCellQuoteNotional()));
        assertEquals(3, p.getMaxConcurrentCellsPerSymbol());
        assertEquals(0, new BigDecimal("0.003").compareTo(p.getMinGapPct()));
        assertEquals(3_600_000L, p.getBuyTtlMs());
        assertEquals(0L, p.getSellTtlMs());
    }

    @Test void binds_overrides_from_environment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("htx.market-maker.enabled", "true");
        env.setProperty("htx.market-maker.symbols", "btcusdt");
        env.setProperty("htx.market-maker.spread-pct", "0.008");
        env.setProperty("htx.market-maker.max-concurrent-cells-per-symbol", "5");
        HtxMarketMakerProperties p = bind(env);
        assertTrue(p.isEnabled());
        assertEquals(List.of("btcusdt"), p.getSymbols());
        assertEquals(0, new BigDecimal("0.008").compareTo(p.getSpreadPct()));
        assertEquals(5, p.getMaxConcurrentCellsPerSymbol());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=HtxMarketMakerPropertiesTest -q`
Expected: 编译失败（`HtxMarketMakerProperties` 不存在）

- [ ] **Step 3: 写配置类**

```java
package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 做市策略配置，绑定 {@code application.yml} 的 {@code htx.market-maker.*}。
 * 权威值在配置文件（与现有风控约定一致），Java 仅声明兜底默认。
 * 见设计 spec 2026-08-06-htx-market-maker-design.md §5。
 */
@Data
@ConfigurationProperties(prefix = "htx.market-maker")
public class HtxMarketMakerProperties {

    /** 总开关，默认关；急停语义 = 关闭后重启不加载调度器，不开新 cell。 */
    private boolean enabled = false;

    /** 标的白名单，非白名单一律拒开。 */
    private List<String> symbols = List.of("btcusdt", "ethusdt");

    /** 主循环轮询间隔（ms）。 */
    private long pollIntervalMs = 10_000L;

    /** 价差 δ：buyPrice=P0(1−δ)，sellPrice=P0(1+δ)。 */
    private BigDecimal spreadPct = new BigDecimal("0.005");

    /** 保守单边费率 f，用于不亏闸门（宁高勿低），不查账户真实费率。 */
    private BigDecimal feeRatePerSide = new BigDecimal("0.002");

    /** 每 cell 固定 quote 名义（USDT）；qty = notional / buyPrice。 */
    private BigDecimal perCellQuoteNotional = new BigDecimal("20");

    /** 单标的最大并行 open cell 数 K。 */
    private int maxConcurrentCellsPerSymbol = 3;

    /** 新买价与已挂买价的最小相对间距（去重防同价堆单）。 */
    private BigDecimal minGapPct = new BigDecimal("0.003");

    /** 买单未成交超时撤单（ms）。 */
    private long buyTtlMs = 3_600_000L;

    /** 卖单 TTL（ms）；0 = 不设 TTL，一直挂到成交。 */
    private long sellTtlMs = 0L;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=HtxMarketMakerPropertiesTest -q`
Expected: Tests run: 2, Failures: 0

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerProperties.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/config/HtxMarketMakerPropertiesTest.java
git commit -m "feat(htx-mm): HtxMarketMakerProperties 配置类 + 绑定测试"
```

---

## 阶段 B — 纯逻辑（无 IO）

### Task B1: MarketMakerMath 定价与闸门

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMath.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMathTest.java`

- [ ] **Step 1: 写失败测试**

不亏边界值推导（f=0.002 → (1−f)²=0.996004）：
- buy=996004、sell=1000000 → 0.996004·1000000=996004.0 == buy → **放行**
- buy=996005、sell=1000000 → 996004.0 < 996005 → **拒**
- δ=0.001、P0=100 → buy=99.90、sell=100.10 → 0.996004·100.10=99.700≈99.70 < 99.90 → **拒**（δ 相对 f 过小）

```java
package com.metaverse.coin.strategy.htx.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketMakerMathTest {

    private static final BigDecimal F = new BigDecimal("0.002");

    @Test void buyPrice_is_p0_times_one_minus_spread_scale2() {
        BigDecimal p0 = new BigDecimal("60000");
        assertEquals(0, new BigDecimal("59700.00")
                .compareTo(MarketMakerMath.buyPrice(p0, new BigDecimal("0.005"))));
    }

    @Test void sellPrice_is_p0_times_one_plus_spread_scale2() {
        BigDecimal p0 = new BigDecimal("60000");
        assertEquals(0, new BigDecimal("60300.00")
                .compareTo(MarketMakerMath.sellPrice(p0, new BigDecimal("0.005"))));
    }

    @Test void qtyFor_is_notional_over_buyprice_scale4_halfdown() {
        // 20 / 59700.00 = 0.000335... → scale4 HALF_DOWN = 0.0003
        assertEquals(0, new BigDecimal("0.0003")
                .compareTo(MarketMakerMath.qtyFor(new BigDecimal("20"), new BigDecimal("59700.00"))));
    }

    @Test void isNoLoss_true_when_exactly_equal() {
        assertTrue(MarketMakerMath.isNoLoss(
                new BigDecimal("996004"), new BigDecimal("1000000"), F));
    }

    @Test void isNoLoss_false_when_buy_one_higher() {
        assertFalse(MarketMakerMath.isNoLoss(
                new BigDecimal("996005"), new BigDecimal("1000000"), F));
    }

    @Test void isNoLoss_false_when_spread_too_small_vs_fee() {
        BigDecimal p0 = new BigDecimal("100");
        BigDecimal buy = MarketMakerMath.buyPrice(p0, new BigDecimal("0.001"));   // 99.90
        BigDecimal sell = MarketMakerMath.sellPrice(p0, new BigDecimal("0.001")); // 100.10
        assertFalse(MarketMakerMath.isNoLoss(buy, sell, F));
    }

    @Test void isNoLoss_true_at_default_spread() {
        BigDecimal p0 = new BigDecimal("100");
        BigDecimal buy = MarketMakerMath.buyPrice(p0, new BigDecimal("0.005"));   // 99.50
        BigDecimal sell = MarketMakerMath.sellPrice(p0, new BigDecimal("0.005")); // 100.50
        assertTrue(MarketMakerMath.isNoLoss(buy, sell, F));
    }

    @Test void farEnough_true_when_no_existing() {
        assertTrue(MarketMakerMath.farEnough(
                new BigDecimal("100"), List.of(), new BigDecimal("0.003")));
    }

    @Test void farEnough_false_when_candidate_too_close() {
        // |100 - 100.2|/100.2 = 0.001996 < 0.003 → 太近
        assertFalse(MarketMakerMath.farEnough(
                new BigDecimal("100"), List.of(new BigDecimal("100.2")), new BigDecimal("0.003")));
    }

    @Test void farEnough_true_when_all_far() {
        assertTrue(MarketMakerMath.farEnough(
                new BigDecimal("100"),
                List.of(new BigDecimal("101"), new BigDecimal("98")),
                new BigDecimal("0.003")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=MarketMakerMathTest -q`
Expected: 编译失败（`MarketMakerMath` 不存在）

- [ ] **Step 3: 写实现**

```java
package com.metaverse.coin.strategy.htx.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 做市纯定价与闸门逻辑（无 IO，可完全单测）。见 spec §2.2 不亏证明。
 * 不亏不变量：{@code (1−f)²·sellPrice ≥ buyPrice}，与成交量无关。
 */
public final class MarketMakerMath {

    private MarketMakerMath() {}

    /** btcusdt/ethusdt quote 报价精度。 */
    public static final int PRICE_SCALE = 2;
    /** 下单数量精度（保守取 btc(6)/eth(4) 交集）。 */
    public static final int QTY_SCALE = 4;

    private static final BigDecimal ONE = BigDecimal.ONE;

    /** buyPrice = P0·(1−δ)，向下含入到 PRICE_SCALE（HALF_UP）。 */
    public static BigDecimal buyPrice(BigDecimal p0, BigDecimal spreadPct) {
        return p0.multiply(ONE.subtract(spreadPct)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** sellPrice = P0·(1+δ)，含入到 PRICE_SCALE（HALF_UP）。 */
    public static BigDecimal sellPrice(BigDecimal p0, BigDecimal spreadPct) {
        return p0.multiply(ONE.add(spreadPct)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** qty = notional / buyPrice，含入到 QTY_SCALE（HALF_DOWN，宁小勿大不超名义）。 */
    public static BigDecimal qtyFor(BigDecimal notional, BigDecimal buyPrice) {
        return notional.divide(buyPrice, QTY_SCALE, RoundingMode.HALF_DOWN);
    }

    /** 不亏闸门：(1−f)²·sellPrice ≥ buyPrice。 */
    public static boolean isNoLoss(BigDecimal buyPrice, BigDecimal sellPrice, BigDecimal feeRatePerSide) {
        BigDecimal oneMinusF = ONE.subtract(feeRatePerSide);
        BigDecimal lhs = oneMinusF.multiply(oneMinusF).multiply(sellPrice);
        return lhs.compareTo(buyPrice) >= 0;
    }

    /**
     * min-gap 去重：候选买价与每个已挂买价的相对距离 |cand−e|/e 都 ≥ minGapPct 才算够远。
     * existingBuys 为空 → 直接够远。
     */
    public static boolean farEnough(BigDecimal candidateBuy, List<BigDecimal> existingBuys,
                                    BigDecimal minGapPct) {
        for (BigDecimal e : existingBuys) {
            BigDecimal rel = candidateBuy.subtract(e).abs()
                    .divide(e, 10, RoundingMode.HALF_UP);
            if (rel.compareTo(minGapPct) < 0) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=MarketMakerMathTest -q`
Expected: Tests run: 10, Failures: 0

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMath.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerMathTest.java
git commit -m "feat(htx-mm): MarketMakerMath 定价/不亏闸/min-gap + 边界测试"
```

---

### Task B2: MarketMakerCell 值对象

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerCell.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerCellTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MarketMakerCellTest {

    @Test void fromEntity_maps_all_fields() {
        HtxLockedPairEntity e = new HtxLockedPairEntity();
        e.setId(11L);
        e.setClientOrderId("mm-btcusdt-123");
        e.setSymbol("btcusdt");
        e.setBuyPrice(new BigDecimal("59700.00"));
        e.setSellPrice(new BigDecimal("60300.00"));
        e.setReqQuantity(new BigDecimal("0.0003"));
        e.setStatus(HtxStatus.MM_RESTING_BUY);
        e.setBuyOrderId(555L);
        e.setSellOrderId(null);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);

        MarketMakerCell c = MarketMakerCell.fromEntity(e);
        assertEquals(11L, c.getPairId());
        assertEquals("mm-btcusdt-123", c.getClientId());
        assertEquals("btcusdt", c.getSymbol());
        assertEquals(0, new BigDecimal("59700.00").compareTo(c.getBuyPrice()));
        assertEquals(0, new BigDecimal("60300.00").compareTo(c.getSellPrice()));
        assertEquals(0, new BigDecimal("0.0003").compareTo(c.getQty()));
        assertEquals(HtxStatus.MM_RESTING_BUY, c.getState());
        assertEquals(555L, c.getBuyOrderId());
        assertNull(c.getSellOrderId());
        assertEquals(now, c.getCreatedAt());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=MarketMakerCellTest -q`
Expected: 编译失败（`MarketMakerCell` 不存在）

- [ ] **Step 3: 写实现**

```java
package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔做市 cell 的内存模型。开仓瞬间锁定 buy/sell 价，此后不动（网格 cell）。
 * 从 htx_locked_pair 行重建，用于状态机推进与重启恢复。
 */
@Data
@Builder
public class MarketMakerCell {
    private long pairId;
    private String clientId;
    private String symbol;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private BigDecimal qty;
    private String state;
    private Long buyOrderId;
    private Long sellOrderId;
    private LocalDateTime createdAt;

    public static MarketMakerCell fromEntity(HtxLockedPairEntity e) {
        return MarketMakerCell.builder()
                .pairId(e.getId())
                .clientId(e.getClientOrderId())
                .symbol(e.getSymbol())
                .buyPrice(e.getBuyPrice())
                .sellPrice(e.getSellPrice())
                .qty(e.getReqQuantity())
                .state(e.getStatus())
                .buyOrderId(e.getBuyOrderId())
                .sellOrderId(e.getSellOrderId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=MarketMakerCellTest -q`
Expected: Tests run: 1, Failures: 0

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerCell.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/strategy/MarketMakerCellTest.java
git commit -m "feat(htx-mm): MarketMakerCell 值对象 + fromEntity"
```

---

## 阶段 C — 持久化方法（HtxTradeRecorder）

### Task C1: Recorder 加 MM 增/查方法

写前 insert 走 fail-closed（抛 `HtxPersistenceException`），状态推进 update 走 best-effort（吞异常告警），与现有约定一致。查询方法用 `QueryWrapper`（照 `findNonTerminalPairs` 模式），无需新增 mapper @Select。

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorder.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorderMmTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HtxTradeRecorderMmTest {

    HtxLockedPairMapper pairMapper;
    HtxOrderMapper orderMapper;
    HtxCancelMapper cancelMapper;
    HtxExposureMapper exposureMapper;
    HtxTradeRecorder recorder;

    @BeforeEach void setup() {
        pairMapper = mock(HtxLockedPairMapper.class);
        orderMapper = mock(HtxOrderMapper.class);
        cancelMapper = mock(HtxCancelMapper.class);
        exposureMapper = mock(HtxExposureMapper.class);
        recorder = new HtxTradeRecorder(pairMapper, orderMapper, cancelMapper, exposureMapper);
    }

    @Test void createMmCell_persists_resting_buy_and_market_maker_strategy() {
        when(pairMapper.insert(any())).thenAnswer(inv -> {
            ((HtxLockedPairEntity) inv.getArgument(0)).setId(100L);
            return 1;
        });
        long id = recorder.createMmCell("btcusdt", new BigDecimal("0.0003"),
                new BigDecimal("59700.00"), new BigDecimal("60300.00"), "mm-btcusdt-1");
        assertEquals(100L, id);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).insert(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(HtxStatus.MM_RESTING_BUY, e.getStatus());
        assertEquals(HtxStatus.STRATEGY_MARKET_MAKER, e.getStrategy());
        assertEquals("mm-btcusdt-1", e.getClientOrderId());
        assertEquals(0, new BigDecimal("0.0003").compareTo(e.getReqQuantity()));
        assertEquals(0, new BigDecimal("59700.00").compareTo(e.getBuyPrice()));
        assertEquals(0, new BigDecimal("60300.00").compareTo(e.getSellPrice()));
        assertFalse(e.getDryRun());   // 做市实盘单
    }

    @Test void createMmCell_fail_closed_on_insert_error() {
        when(pairMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(HtxPersistenceException.class, () ->
                recorder.createMmCell("btcusdt", new BigDecimal("0.0003"),
                        new BigDecimal("59700.00"), new BigDecimal("60300.00"), "mm-btcusdt-1"));
    }

    @Test void markMmBuySent_sets_buy_order_id() {
        recorder.markMmBuySent(100L, 555L);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        assertEquals(100L, cap.getValue().getId());
        assertEquals(555L, cap.getValue().getBuyOrderId());
    }

    @Test void markMmSellResting_sets_sell_order_and_status() {
        recorder.markMmSellResting(100L, 777L, new BigDecimal("0.0003"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(HtxStatus.MM_RESTING_SELL, e.getStatus());
        assertEquals(777L, e.getSellOrderId());
        assertEquals(0, new BigDecimal("0.0003").compareTo(e.getBuyFilledAmount()));
    }

    @Test void markMmDone_sets_done_and_sell_filled() {
        recorder.markMmDone(100L, new BigDecimal("0.0003"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        assertEquals(HtxStatus.MM_DONE, cap.getValue().getStatus());
        assertEquals(0, new BigDecimal("0.0003").compareTo(cap.getValue().getSellFilledAmount()));
    }

    @Test void markMmCancelledBuy_sets_status() {
        recorder.markMmCancelledBuy(100L);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        assertEquals(HtxStatus.MM_CANCELLED_BUY, cap.getValue().getStatus());
    }

    @Test void findOpenMmCells_queries_symbol_strategy_and_resting_statuses() {
        when(pairMapper.selectList(any())).thenReturn(List.of());
        recorder.findOpenMmCells("btcusdt");
        verify(pairMapper).selectList(any(QueryWrapper.class));
    }

    @Test void countOpenMmCells_uses_selectCount() {
        when(pairMapper.selectCount(any())).thenReturn(2L);
        assertEquals(2, recorder.countOpenMmCells("btcusdt"));
    }

    @Test void findAllOpenMmCells_queries_strategy_and_resting_statuses() {
        when(pairMapper.selectList(any())).thenReturn(List.of());
        recorder.findAllOpenMmCells();
        verify(pairMapper).selectList(any(QueryWrapper.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=HtxTradeRecorderMmTest -q`
Expected: 编译失败（MM 方法不存在）

- [ ] **Step 3: 在 HtxTradeRecorder 加 MM 方法**

在 `finalizePair(...)` 之后、`recordCancel(...)` 之前插入以下方法块（放在写前/更新区）。同时确认文件顶部已 `import java.util.List;`（现有已导入）。

```java
    // ---- 做市（market-maker）专用 ----

    /**
     * 写前建 cell（fail-closed）：status=MM_RESTING_BUY，strategy=MARKET_MAKER，dry_run=0。
     * clientOrderId 幂等键。返回生成主键 id。
     */
    public long createMmCell(String symbol, BigDecimal qty, BigDecimal buyPrice,
                             BigDecimal sellPrice, String clientId) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setClientOrderId(clientId);
            e.setSymbol(symbol);
            e.setReqQuantity(qty);
            e.setBuyPrice(buyPrice);
            e.setSellPrice(sellPrice);
            e.setStatus(HtxStatus.MM_RESTING_BUY);
            e.setStrategy(HtxStatus.STRATEGY_MARKET_MAKER);
            e.setDryRun(false);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("createMmCell failed", ex);
        }
    }

    /** 买单已发，回填 buyOrderId（best-effort）。 */
    public void markMmBuySent(long pairId, Long buyOrderId) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setBuyOrderId(buyOrderId);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmBuySent failed pairId={}", pairId, ex);
        }
    }

    /** 买腿成交、卖单已挂：→ MM_RESTING_SELL，记 sellOrderId 与买腿成交量（best-effort）。 */
    public void markMmSellResting(long pairId, Long sellOrderId, BigDecimal buyFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setSellOrderId(sellOrderId);
            e.setBuyFilledAmount(buyFilled);
            e.setStatus(HtxStatus.MM_RESTING_SELL);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmSellResting failed pairId={}", pairId, ex);
        }
    }

    /** 卖腿成交、round-trip 完成：→ MM_DONE（best-effort）。 */
    public void markMmDone(long pairId, BigDecimal sellFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setSellFilledAmount(sellFilled);
            e.setStatus(HtxStatus.MM_DONE);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmDone failed pairId={}", pairId, ex);
        }
    }

    /** 买单超 TTL 已撤、cell 关闭：→ MM_CANCELLED_BUY（best-effort）。 */
    public void markMmCancelledBuy(long pairId) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setStatus(HtxStatus.MM_CANCELLED_BUY);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markMmCancelledBuy failed pairId={}", pairId, ex);
        }
    }

    /** 某标的的 open cell（RESTING_BUY / RESTING_SELL）。 */
    public List<HtxLockedPairEntity> findOpenMmCells(String symbol) {
        return pairMapper.selectList(new QueryWrapper<HtxLockedPairEntity>()
                .eq("strategy", HtxStatus.STRATEGY_MARKET_MAKER)
                .eq("symbol", symbol)
                .in("status", HtxStatus.MM_RESTING_BUY, HtxStatus.MM_RESTING_SELL));
    }

    /** 全部标的的 open cell（用于重启恢复）。 */
    public List<HtxLockedPairEntity> findAllOpenMmCells() {
        return pairMapper.selectList(new QueryWrapper<HtxLockedPairEntity>()
                .eq("strategy", HtxStatus.STRATEGY_MARKET_MAKER)
                .in("status", HtxStatus.MM_RESTING_BUY, HtxStatus.MM_RESTING_SELL));
    }

    /** 某标的 open cell 数（开仓 K 闸用）。 */
    public int countOpenMmCells(String symbol) {
        Long n = pairMapper.selectCount(new QueryWrapper<HtxLockedPairEntity>()
                .eq("strategy", HtxStatus.STRATEGY_MARKET_MAKER)
                .eq("symbol", symbol)
                .in("status", HtxStatus.MM_RESTING_BUY, HtxStatus.MM_RESTING_SELL));
        return n == null ? 0 : n.intValue();
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -Dtest=HtxTradeRecorderMmTest -q`
Expected: Tests run: 9, Failures: 0

- [ ] **Step 5: 跑全模块测试确认无回归**

Run: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home && mvn -pl coin-strategy-htx test -q`
Expected: BUILD SUCCESS，全部通过（含既有 41 项 + 本批新增）

- [ ] **Step 6: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorder.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorderMmTest.java
git commit -m "feat(htx-mm): HtxTradeRecorder 加做市 cell 增/查方法（fail-closed 写前）"
```

---

## 第一批完成标志

- schema + 实体有 `strategy` 列；`HtxStatus` 有 MM_* 常量；`HtxMarketMakerProperties` 绑定通过。
- `MarketMakerMath`（含不亏边界）、`MarketMakerCell` 纯单测通过。
- `HtxTradeRecorder` MM 方法测试通过，全模块无回归。
- 无任何调度/下单副作用，安全提交。第二批将基于这些地基写 `MarketMakerService`（开仓决策 + 状态机 + 重启恢复）。
