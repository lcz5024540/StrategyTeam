# HTX 持久化 + 可用性 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给独立模块 `coin-strategy-htx` 增加专用库 `htx_trade_strategy` 的写前持久化（下单/撤单/成交/手续费）与可用性硬化（fail-closed 写前、崩溃恢复、对账器自动市价平仓），做到无缺口。

**Architecture:** MyBatis-Plus 直连独立 MySQL 库（不引 common-dao，保持模块隔离）。真实下单前必须先成功落库（write-ahead / fail-closed），买腿手续费从可卖 base 扣减以消除超卖，单腿裸露写死信并由 `@Scheduled` 对账器市价平回中性；启动时重放非终态订单。

**Tech Stack:** Java 17, Spring Boot 2.7.18, MyBatis-Plus 3.5.5, mysql-connector-j 8.3.0, H2(test), JUnit 5, Mockito。

---

## 文件结构

新增（包根 `com.metaverse.coin.strategy.htx`）：
- `persistence/HtxStatus.java` — 状态常量（pair / intent）
- `persistence/entity/{HtxLockedPairEntity,HtxOrderEntity,HtxCancelEntity,HtxExposureEntity}.java` — MyBatis-Plus 实体
- `persistence/mapper/{HtxLockedPairMapper,HtxOrderMapper,HtxCancelMapper,HtxExposureMapper}.java` — `BaseMapper` 接口
- `persistence/HtxPersistenceException.java` — 写库失败异常（fail-closed 信号）
- `persistence/HtxTradeRecorder.java` — 写前持久化服务（本模块 DB 唯一入口）
- `util/HtxSymbolUtil.java` — symbol→base/quote 拆分
- `service/HtxFeeMath.java` — 纯函数：可卖 base 计算
- `recovery/HtxStartupRecovery.java` — `ApplicationReadyEvent` 重放非终态订单
- `recovery/HtxReconciler.java` — `@Scheduled` 对账器：市价平掉裸露
- `src/main/resources/db/schema.sql` — 建表 DDL
- `src/test/resources/application.yml` — H2 测试数据源

修改：
- `coin-strategy-htx/pom.xml` — 加 MyBatis-Plus / mysql / h2 依赖
- `coin-strategy-htx/src/main/resources/application.yml` — datasource + sql.init + mybatis-plus
- `.../CoinStrategyHtxApplication.java` — `@MapperScan`
- `.../config/HtxConfig.java` — executor @Bean 注入 recorder
- `.../service/HtxTradingService.java` — 建 pair 行 + dry-run 记录 + 预检
- `.../service/HtxLockedPairExecutor.java` — 写前落库、费用感知卖量、撤单/裸露记录
- `.../service/HtxLockedPairResult.java` — 加 `PERSIST_WRITE_FAILED`
- `.../service/HtxTradeProperties.java` — 加 reconciler 开关/间隔、preflight 开关

---

### Task 1: 加持久化依赖

**Files:**
- Modify: `coin-strategy-htx/pom.xml:29-44`（在 validation 依赖后插入）

- [ ] **Step 1: 加依赖**

在 `coin-strategy-htx/pom.xml` 的 `spring-boot-starter-validation` 依赖块之后、`spring-boot-starter-test` 之前插入：

```xml
        <!-- 持久层：独立库 htx_trade_strategy。不引 common-dao 以保持模块隔离。 -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-boot-starter</artifactId>
            <version>3.5.5</version>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.3.0</version>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>2.2.224</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 验证编译**

Run: `mvn -q -pl coin-strategy-htx -am -DskipTests compile`
Expected: BUILD SUCCESS（依赖可解析）

- [ ] **Step 3: 提交**

```bash
git add coin-strategy-htx/pom.xml
git commit -m "build(htx): add mybatis-plus + mysql + h2(test) deps"
```

---

### Task 2: schema.sql（建表 DDL）

**Files:**
- Create: `coin-strategy-htx/src/main/resources/db/schema.sql`

- [ ] **Step 1: 写 DDL**

`CREATE TABLE IF NOT EXISTS`，H2(MySQL mode) 与 MySQL 通用：

```sql
-- htx_trade_strategy 专用库。买/卖 round-trip、写前订单、撤单、单腿裸露死信。
CREATE TABLE IF NOT EXISTS htx_locked_pair (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    client_order_id     VARCHAR(64)  NOT NULL UNIQUE,
    symbol              VARCHAR(32)  NOT NULL,
    req_quantity        DECIMAL(36,18) NOT NULL,
    buy_price           DECIMAL(36,18),
    sell_price          DECIMAL(36,18),
    status              VARCHAR(32)  NOT NULL,
    result_code         VARCHAR(48),
    buy_order_id        BIGINT,
    sell_order_id       BIGINT,
    buy_filled_amount   DECIMAL(36,18) DEFAULT 0,
    sell_filled_amount  DECIMAL(36,18) DEFAULT 0,
    net_base_delta      DECIMAL(36,18) DEFAULT 0,
    total_fee_base      DECIMAL(36,18) DEFAULT 0,
    total_fee_quote     DECIMAL(36,18) DEFAULT 0,
    net_quote_cost      DECIMAL(36,18) DEFAULT 0,
    dry_run             TINYINT(1)   NOT NULL DEFAULT 0,
    message             VARCHAR(512),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS htx_order (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    pair_id             BIGINT       NOT NULL,
    client_order_id     VARCHAR(64),
    htx_order_id        BIGINT,
    side                VARCHAR(8)   NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    price               DECIMAL(36,18),
    req_amount          DECIMAL(36,18) NOT NULL,
    state               VARCHAR(32),
    filled_amount       DECIMAL(36,18) DEFAULT 0,
    filled_cash_amount  DECIMAL(36,18) DEFAULT 0,
    filled_fees         DECIMAL(36,18) DEFAULT 0,
    fee_currency        VARCHAR(16),
    intent_status       VARCHAR(16)  NOT NULL,
    raw_response        TEXT,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX IF NOT EXISTS idx_order_pair    ON htx_order(pair_id);
CREATE INDEX IF NOT EXISTS idx_order_htxid   ON htx_order(htx_order_id);
CREATE INDEX IF NOT EXISTS idx_order_intent  ON htx_order(intent_status);

CREATE TABLE IF NOT EXISTS htx_cancel (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    order_id            BIGINT       NOT NULL,
    htx_order_id        BIGINT,
    reason              VARCHAR(64),
    cancel_ok           TINYINT(1),
    post_cancel_state   VARCHAR(32),
    post_cancel_filled  DECIMAL(36,18) DEFAULT 0,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS htx_exposure (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    pair_id             BIGINT       NOT NULL,
    symbol              VARCHAR(32)  NOT NULL,
    exposed_side        VARCHAR(8)   NOT NULL,
    exposed_amount      DECIMAL(36,18) NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    flatten_order_id    BIGINT,
    flatten_retries     INT          NOT NULL DEFAULT 0,
    detail              VARCHAR(512),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
```

- [ ] **Step 2: 提交**

```bash
git add coin-strategy-htx/src/main/resources/db/schema.sql
git commit -m "feat(htx): schema.sql for htx_trade_strategy (pair/order/cancel/exposure)"
```

---

### Task 3: HtxSymbolUtil（symbol → base/quote）

用于记录手续费币种（买腿费在 base 币，卖腿费在 quote 币）。按已知 quote 后缀拆分。

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/util/HtxSymbolUtil.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/util/HtxSymbolUtilTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.metaverse.coin.strategy.htx.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HtxSymbolUtilTest {
    @Test void splits_usdt_pair() {
        assertEquals("btc", HtxSymbolUtil.base("btcusdt"));
        assertEquals("usdt", HtxSymbolUtil.quote("btcusdt"));
    }
    @Test void splits_btc_quoted_pair() {
        assertEquals("eth", HtxSymbolUtil.base("ethbtc"));
        assertEquals("btc", HtxSymbolUtil.quote("ethbtc"));
    }
    @Test void unknown_quote_returns_symbol_as_base_and_empty_quote() {
        assertEquals("weirdcoin", HtxSymbolUtil.base("weirdcoin"));
        assertEquals("", HtxSymbolUtil.quote("weirdcoin"));
    }
    @Test void null_safe() {
        assertEquals("", HtxSymbolUtil.base(null));
        assertEquals("", HtxSymbolUtil.quote(null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxSymbolUtilTest`
Expected: FAIL（`HtxSymbolUtil` 不存在，编译错误）

- [ ] **Step 3: 实现**

```java
package com.metaverse.coin.strategy.htx.util;

import java.util.List;

/**
 * 拆分 HTX symbol 为 base/quote。HTX symbol 全小写无分隔（btcusdt）。
 * 按已知 quote 后缀由长到短匹配，避免 "btc" 抢先 "busd" 等歧义。
 */
public final class HtxSymbolUtil {
    // 长后缀优先，规避 usdt vs usd、husd vs usd 歧义。
    private static final List<String> QUOTES = List.of(
            "usdt", "usdc", "husd", "usdd", "tusd", "dai", "eth", "btc", "trx", "ht");

    private HtxSymbolUtil() {}

    public static String quote(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "";
        String s = symbol.toLowerCase();
        for (String q : QUOTES) {
            if (s.length() > q.length() && s.endsWith(q)) return q;
        }
        return "";
    }

    public static String base(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "";
        String s = symbol.toLowerCase();
        String q = quote(s);
        return q.isEmpty() ? s : s.substring(0, s.length() - q.length());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxSymbolUtilTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/util/HtxSymbolUtil.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/util/HtxSymbolUtilTest.java
git commit -m "feat(htx): HtxSymbolUtil base/quote split for fee currency"
```

---

### Task 4: HtxFeeMath（可卖 base = 成交 − base 手续费）

修复超卖：买腿手续费扣在 base 币，实际可卖 = filledAmount − filledFees。

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxFeeMath.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxFeeMathTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class HtxFeeMathTest {
    @Test void subtracts_base_fee_from_filled() {
        // 买到 1.0 BTC，手续费 0.002 BTC → 可卖 0.998
        assertEquals(0, new BigDecimal("0.998")
                .compareTo(HtxFeeMath.sellableBase(new BigDecimal("1.0"), new BigDecimal("0.002"))));
    }
    @Test void null_fee_treated_as_zero() {
        assertEquals(0, new BigDecimal("1.0")
                .compareTo(HtxFeeMath.sellableBase(new BigDecimal("1.0"), null)));
    }
    @Test void never_returns_negative() {
        // 手续费异常大于成交（防御）→ 归零，不产生负卖量
        assertEquals(0, BigDecimal.ZERO
                .compareTo(HtxFeeMath.sellableBase(new BigDecimal("0.001"), new BigDecimal("0.005"))));
    }
    @Test void null_filled_is_zero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(HtxFeeMath.sellableBase(null, new BigDecimal("0.1"))));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxFeeMathTest`
Expected: FAIL（`HtxFeeMath` 不存在）

- [ ] **Step 3: 实现**

```java
package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;

/**
 * 手续费/可卖量计算（纯函数）。
 * <p>HTX 买单手续费扣在 base 币（买 BTC → 扣 BTC），因此下卖单前必须从成交 base 中扣掉手续费，
 * 否则按请求量挂卖会超出账户余额（超卖）。若开启 HT/点卡抵扣，filledFees 可能非 base 计价，
 * 本期按订单级聚合做保守扣减（见 spec §2 手续费粒度决策），精度截断由 HTX 侧兜底。
 */
public final class HtxFeeMath {
    private HtxFeeMath() {}

    public static BigDecimal sellableBase(BigDecimal filledBase, BigDecimal baseFee) {
        BigDecimal filled = filledBase == null ? BigDecimal.ZERO : filledBase;
        BigDecimal fee = baseFee == null ? BigDecimal.ZERO : baseFee;
        BigDecimal net = filled.subtract(fee);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxFeeMathTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxFeeMath.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxFeeMathTest.java
git commit -m "feat(htx): HtxFeeMath.sellableBase to prevent oversell (deduct base fee)"
```

---

### Task 5: 状态常量 + 实体 + Mapper

**Files:**
- Create: `persistence/HtxStatus.java`
- Create: `persistence/entity/HtxLockedPairEntity.java`, `HtxOrderEntity.java`, `HtxCancelEntity.java`, `HtxExposureEntity.java`
- Create: `persistence/mapper/HtxLockedPairMapper.java`, `HtxOrderMapper.java`, `HtxCancelMapper.java`, `HtxExposureMapper.java`

(路径前缀均为 `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/`)

- [ ] **Step 1: 状态常量**

```java
package com.metaverse.coin.strategy.htx.persistence;

/** htx_locked_pair.status 与 htx_order.intent_status 的取值。 */
public final class HtxStatus {
    private HtxStatus() {}
    // pair.status
    public static final String NEW = "NEW";
    public static final String BUY_SENT = "BUY_SENT";
    public static final String BUY_FILLED = "BUY_FILLED";
    public static final String SELL_SENT = "SELL_SENT";
    public static final String DONE = "DONE";
    public static final String SELL_PENDING = "SELL_PENDING";
    public static final String EXPOSED = "EXPOSED";
    public static final String BUY_TIMEOUT = "BUY_TIMEOUT";
    public static final String BUY_REJECTED = "BUY_REJECTED";
    public static final String REJECTED = "REJECTED";
    // order.intent_status
    public static final String INTENT_PENDING_SEND = "PENDING_SEND";
    public static final String INTENT_SENT = "SENT";
    public static final String INTENT_SEND_FAILED = "SEND_FAILED";
    public static final String INTENT_TERMINAL = "TERMINAL";
    // exposure.status
    public static final String EXP_OPEN = "OPEN";
    public static final String EXP_FLATTENING = "FLATTENING";
    public static final String EXP_FLATTENED = "FLATTENED";
    public static final String EXP_MANUAL = "MANUAL";
}
```

- [ ] **Step 2: 实体（4 个）**

`entity/HtxLockedPairEntity.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_locked_pair")
public class HtxLockedPairEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientOrderId;
    private String symbol;
    private BigDecimal reqQuantity;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private String status;
    private String resultCode;
    private Long buyOrderId;
    private Long sellOrderId;
    private BigDecimal buyFilledAmount;
    private BigDecimal sellFilledAmount;
    private BigDecimal netBaseDelta;
    private BigDecimal totalFeeBase;
    private BigDecimal totalFeeQuote;
    private BigDecimal netQuoteCost;
    private Boolean dryRun;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`entity/HtxOrderEntity.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_order")
public class HtxOrderEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pairId;
    private String clientOrderId;
    private Long htxOrderId;
    private String side;
    private String type;
    private BigDecimal price;
    private BigDecimal reqAmount;
    private String state;
    private BigDecimal filledAmount;
    private BigDecimal filledCashAmount;
    private BigDecimal filledFees;
    private String feeCurrency;
    private String intentStatus;
    private String rawResponse;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`entity/HtxCancelEntity.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_cancel")
public class HtxCancelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long htxOrderId;
    private String reason;
    private Boolean cancelOk;
    private String postCancelState;
    private BigDecimal postCancelFilled;
    private LocalDateTime createdAt;
}
```

`entity/HtxExposureEntity.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_exposure")
public class HtxExposureEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pairId;
    private String symbol;
    private String exposedSide;
    private BigDecimal exposedAmount;
    private String status;
    private Long flattenOrderId;
    private Integer flattenRetries;
    private String detail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Mapper（4 个，均 `extends BaseMapper`）**

`mapper/HtxLockedPairMapper.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HtxLockedPairMapper extends BaseMapper<HtxLockedPairEntity> {
}
```

`mapper/HtxOrderMapper.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HtxOrderMapper extends BaseMapper<HtxOrderEntity> {
}
```

`mapper/HtxCancelMapper.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxCancelEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HtxCancelMapper extends BaseMapper<HtxCancelEntity> {
}
```

`mapper/HtxExposureMapper.java`：

```java
package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxExposureEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HtxExposureMapper extends BaseMapper<HtxExposureEntity> {
}
```

- [ ] **Step 4: 验证编译**

Run: `mvn -q -pl coin-strategy-htx -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence
git commit -m "feat(htx): MyBatis-Plus entities + mappers + status constants"
```

---

### Task 6: DB 配置 + @MapperScan + H2 映射集成测试

**Files:**
- Modify: `coin-strategy-htx/src/main/resources/application.yml`
- Modify: `.../htx/CoinStrategyHtxApplication.java`
- Create: `coin-strategy-htx/src/test/resources/application.yml`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxMapperIntegrationTest.java`

- [ ] **Step 1: 写失败测试（H2 建库 + 四表 CRUD 冒烟）**

```java
package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxLockedPairMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HtxMapperIntegrationTest {

    @Autowired HtxLockedPairMapper pairMapper;

    @Test void insert_and_read_pair() {
        HtxLockedPairEntity p = new HtxLockedPairEntity();
        p.setClientOrderId("it-" + System.nanoTime());
        p.setSymbol("btcusdt");
        p.setReqQuantity(new BigDecimal("0.01"));
        p.setBuyPrice(new BigDecimal("60000"));
        p.setSellPrice(new BigDecimal("60050"));
        p.setStatus(HtxStatus.NEW);
        p.setDryRun(false);
        assertEquals(1, pairMapper.insert(p));
        assertNotNull(p.getId());
        HtxLockedPairEntity got = pairMapper.selectById(p.getId());
        assertEquals("btcusdt", got.getSymbol());
        assertEquals(HtxStatus.NEW, got.getStatus());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxMapperIntegrationTest`
Expected: FAIL（无 datasource / 无 @MapperScan → 上下文启动失败）

- [ ] **Step 3: 主配置加 datasource + sql.init + mybatis-plus**

在 `coin-strategy-htx/src/main/resources/application.yml` 的 `spring:` 块下（`main:` 同级）追加：

```yaml
  datasource:
    url: "jdbc:mysql://${HTX_DB_HOST:127.0.0.1}:${HTX_DB_PORT:3306}/htx_trade_strategy?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true"
    username: "${HTX_DB_USERNAME:root}"
    password: "${HTX_DB_PASSWORD:}"
    driver-class-name: "com.mysql.cj.jdbc.Driver"
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql
```

并在文件末尾追加 mybatis-plus 配置（顶层，与 `htx:` 同级）：

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **Step 4: 应用类加 @MapperScan**

修改 `CoinStrategyHtxApplication.java`：`@SpringBootApplication` 下加一行注解、补 import。

```java
import org.mybatis.spring.annotation.MapperScan;
```

```java
@SpringBootApplication(scanBasePackages = "com.metaverse.coin.strategy.htx")
@MapperScan("com.metaverse.coin.strategy.htx.persistence.mapper")
@Slf4j
public class CoinStrategyHtxApplication {
```

- [ ] **Step 5: 测试资源用 H2（MySQL 模式）**

Create `coin-strategy-htx/src/test/resources/application.yml`：

```yaml
spring:
  datasource:
    url: "jdbc:h2:mem:htxtest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
# 不设 HTX AK/SK → HtxConfig 条件不满足，不加载真实交易 bean，测试仅验证持久层
htx:
  api:
    access-key: ""
    secret-key: ""
```

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxMapperIntegrationTest`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add coin-strategy-htx/src/main/resources/application.yml \
        coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/CoinStrategyHtxApplication.java \
        coin-strategy-htx/src/test/resources/application.yml \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxMapperIntegrationTest.java
git commit -m "feat(htx): datasource + schema init + @MapperScan; H2 mapper integration test"
```

---

### Task 7: HtxPersistenceException + HtxTradeRecorder（写前持久化，DB 唯一入口）

Recorder 是本模块访问 DB 的唯一入口。**写前方法（createPair / insertOrderIntent）失败必须抛 `HtxPersistenceException`（fail-closed）**；这些方法不加 `@Transactional`，保证 insert 在发 REST 前已 autocommit 落库。更新/记录类方法尽量吞异常仅告警（订单已发，不能回滚真实世界）。

**Files:**
- Create: `persistence/HtxPersistenceException.java`
- Create: `persistence/HtxTradeRecorder.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorderTest.java`

(main 路径前缀 `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/`)

- [ ] **Step 1: 写失败测试（Mockito，验证 fail-closed + 字段映射）**

```java
package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.*;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HtxTradeRecorderTest {

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

    private HtxLockedPairRequest req() {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt").quantity(new BigDecimal("0.01"))
                .buyPrice(new BigDecimal("60000")).sellPrice(new BigDecimal("60050"))
                .clientOrderId("key-1").build();
    }

    @Test void createPair_returns_generated_id() {
        when(pairMapper.insert(any())).thenAnswer(inv -> {
            ((HtxLockedPairEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });
        assertEquals(42L, recorder.createPair(req(), false));
    }

    @Test void createPair_throws_persistence_on_insert_failure() {
        when(pairMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(HtxPersistenceException.class, () -> recorder.createPair(req(), false));
    }

    @Test void insertOrderIntent_throws_persistence_on_failure() {
        when(orderMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(HtxPersistenceException.class, () ->
                recorder.insertOrderIntent(1L, "key-1-BUY", "buy", "buy-limit",
                        new BigDecimal("60000"), new BigDecimal("0.01")));
    }

    @Test void insertOrderIntent_persists_pending_send_and_returns_id() {
        when(orderMapper.insert(any())).thenAnswer(inv -> {
            ((HtxOrderEntity) inv.getArgument(0)).setId(7L);
            return 1;
        });
        long id = recorder.insertOrderIntent(1L, "key-1-BUY", "buy", "buy-limit",
                new BigDecimal("60000"), new BigDecimal("0.01"));
        assertEquals(7L, id);
        ArgumentCaptor<HtxOrderEntity> cap = ArgumentCaptor.forClass(HtxOrderEntity.class);
        verify(orderMapper).insert(cap.capture());
        assertEquals(HtxStatus.INTENT_PENDING_SEND, cap.getValue().getIntentStatus());
        assertEquals("buy", cap.getValue().getSide());
    }

    @Test void updateOrderFill_maps_htxorder_fields_and_fee_currency() {
        HtxOrder o = new HtxOrder();
        o.setState("filled");
        o.setFilledAmount(new BigDecimal("0.01"));
        o.setFilledCashAmount(new BigDecimal("600"));
        o.setFilledFees(new BigDecimal("0.00002"));
        recorder.updateOrderFill(7L, "btcusdt", "buy", o, "{\"raw\":true}");
        ArgumentCaptor<HtxOrderEntity> cap = ArgumentCaptor.forClass(HtxOrderEntity.class);
        verify(orderMapper).updateById(cap.capture());
        HtxOrderEntity e = cap.getValue();
        assertEquals("filled", e.getState());
        assertEquals(0, new BigDecimal("0.00002").compareTo(e.getFilledFees()));
        assertEquals("btc", e.getFeeCurrency());          // 买腿费在 base 币
        assertEquals(HtxStatus.INTENT_TERMINAL, e.getIntentStatus());
    }

    @Test void updateOrderFill_sell_fee_currency_is_quote() {
        HtxOrder o = new HtxOrder();
        o.setState("filled");
        o.setFilledFees(new BigDecimal("0.6"));
        recorder.updateOrderFill(9L, "btcusdt", "sell", o, "{}");
        ArgumentCaptor<HtxOrderEntity> cap = ArgumentCaptor.forClass(HtxOrderEntity.class);
        verify(orderMapper).updateById(cap.capture());
        assertEquals("usdt", cap.getValue().getFeeCurrency());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxTradeRecorderTest`
Expected: FAIL（`HtxPersistenceException` / `HtxTradeRecorder` 不存在）

- [ ] **Step 3: 实现 HtxPersistenceException**

```java
package com.metaverse.coin.strategy.htx.persistence;

/** 写前落库失败信号。触发 fail-closed：调用方必须放弃真实下单。 */
public class HtxPersistenceException extends RuntimeException {
    public HtxPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: 实现 HtxTradeRecorder**

```java
package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.htx.persistence.entity.*;
import com.metaverse.coin.strategy.htx.persistence.mapper.*;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairRequest;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本模块 DB 唯一入口。写前方法失败抛 {@link HtxPersistenceException}（fail-closed）；
 * 更新/记录方法吞异常仅告警（真实订单已发，无法回滚，交由启动恢复/对账器补偿）。
 * 单条 insert/update 走 autocommit（不加 @Transactional），确保发 REST 前已持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxTradeRecorder {

    private final HtxLockedPairMapper pairMapper;
    private final HtxOrderMapper orderMapper;
    private final HtxCancelMapper cancelMapper;
    private final HtxExposureMapper exposureMapper;

    // ---- 写前（fail-closed） ----

    public long createPair(HtxLockedPairRequest req, boolean dryRun) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setClientOrderId(req.getClientOrderId());
            e.setSymbol(req.getSymbol());
            e.setReqQuantity(req.getQuantity());
            e.setBuyPrice(req.getBuyPrice());
            e.setSellPrice(req.getSellPrice());
            e.setStatus(HtxStatus.NEW);
            e.setDryRun(dryRun);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("createPair failed", ex);
        }
    }

    public long insertOrderIntent(long pairId, String clientOrderId, String side,
                                  String type, BigDecimal price, BigDecimal reqAmount) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setPairId(pairId);
            e.setClientOrderId(clientOrderId);
            e.setSide(side);
            e.setType(type);
            e.setPrice(price);
            e.setReqAmount(reqAmount);
            e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            throw new HtxPersistenceException("insertOrderIntent failed", ex);
        }
    }

    // ---- 更新（best-effort，仅告警） ----

    public void markOrderSent(long orderRowId, Long htxOrderId) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setId(orderRowId);
            e.setHtxOrderId(htxOrderId);
            e.setIntentStatus(HtxStatus.INTENT_SENT);
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markOrderSent failed rowId={} htxId={}", orderRowId, htxOrderId, ex);
        }
    }

    public void markOrderSendFailed(long orderRowId, String msg) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setId(orderRowId);
            e.setIntentStatus(HtxStatus.INTENT_SEND_FAILED);
            e.setState(msg);
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(e);
        } catch (Exception ex) {
            log.error("markOrderSendFailed failed rowId={}", orderRowId, ex);
        }
    }

    public void updateOrderFill(long orderRowId, String symbol, String side, HtxOrder o, String rawJson) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setId(orderRowId);
            e.setState(o.getState());
            e.setFilledAmount(o.getFilledAmount());
            e.setFilledCashAmount(o.getFilledCashAmount());
            e.setFilledFees(o.getFilledFees());
            e.setFeeCurrency("buy".equalsIgnoreCase(side)
                    ? HtxSymbolUtil.base(symbol) : HtxSymbolUtil.quote(symbol));
            e.setRawResponse(rawJson);
            if (isTerminal(o.getState())) {
                e.setIntentStatus(HtxStatus.INTENT_TERMINAL);
            }
            e.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updateOrderFill failed rowId={}", orderRowId, ex);
        }
    }

    public void updatePairStatus(long pairId, String status, String resultCode, String message) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setStatus(status);
            e.setResultCode(resultCode);
            e.setMessage(message);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updatePairStatus failed pairId={} status={}", pairId, status, ex);
        }
    }

    public void updatePairBuy(long pairId, Long buyOrderId, BigDecimal buyFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setBuyOrderId(buyOrderId);
            e.setBuyFilledAmount(buyFilled);
            e.setStatus(HtxStatus.BUY_FILLED);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updatePairBuy failed pairId={}", pairId, ex);
        }
    }

    public void updatePairSell(long pairId, Long sellOrderId, BigDecimal sellFilled) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setSellOrderId(sellOrderId);
            e.setSellFilledAmount(sellFilled);
            e.setStatus(HtxStatus.SELL_SENT);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updatePairSell failed pairId={}", pairId, ex);
        }
    }

    public void finalizePair(long pairId, BigDecimal netBaseDelta, BigDecimal totalFeeBase,
                             BigDecimal totalFeeQuote, BigDecimal netQuoteCost,
                             String resultCode, String message) {
        try {
            HtxLockedPairEntity e = new HtxLockedPairEntity();
            e.setId(pairId);
            e.setStatus(HtxStatus.DONE);
            e.setNetBaseDelta(netBaseDelta);
            e.setTotalFeeBase(totalFeeBase);
            e.setTotalFeeQuote(totalFeeQuote);
            e.setNetQuoteCost(netQuoteCost);
            e.setResultCode(resultCode);
            e.setMessage(message);
            e.setUpdatedAt(LocalDateTime.now());
            pairMapper.updateById(e);
        } catch (Exception ex) {
            log.error("finalizePair failed pairId={}", pairId, ex);
        }
    }

    public void recordCancel(long orderRowId, Long htxOrderId, String reason,
                             Boolean ok, String postState, BigDecimal postFilled) {
        try {
            HtxCancelEntity e = new HtxCancelEntity();
            e.setOrderId(orderRowId);
            e.setHtxOrderId(htxOrderId);
            e.setReason(reason);
            e.setCancelOk(ok);
            e.setPostCancelState(postState);
            e.setPostCancelFilled(postFilled);
            cancelMapper.insert(e);
        } catch (Exception ex) {
            log.error("recordCancel failed orderRowId={}", orderRowId, ex);
        }
    }

    public long recordExposure(long pairId, String symbol, String exposedSide,
                               BigDecimal amount, String detail) {
        try {
            HtxExposureEntity e = new HtxExposureEntity();
            e.setPairId(pairId);
            e.setSymbol(symbol);
            e.setExposedSide(exposedSide);
            e.setExposedAmount(amount);
            e.setStatus(HtxStatus.EXP_OPEN);
            e.setDetail(detail);
            e.setUpdatedAt(LocalDateTime.now());
            exposureMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            log.error("recordExposure failed pairId={}", pairId, ex);
            return -1L;
        }
    }

    // ---- 查询（恢复 / 对账器） ----

    public List<HtxLockedPairEntity> findNonTerminalPairs() {
        return pairMapper.selectList(new QueryWrapper<HtxLockedPairEntity>()
                .in("status", HtxStatus.BUY_SENT, HtxStatus.BUY_FILLED,
                        HtxStatus.SELL_SENT, HtxStatus.SELL_PENDING, HtxStatus.EXPOSED));
    }

    public List<HtxOrderEntity> findOrdersByPair(long pairId) {
        return orderMapper.selectList(new QueryWrapper<HtxOrderEntity>().eq("pair_id", pairId));
    }

    public List<HtxExposureEntity> findOpenExposures() {
        return exposureMapper.selectList(new QueryWrapper<HtxExposureEntity>()
                .eq("status", HtxStatus.EXP_OPEN));
    }

    public void markExposureFlattening(long id) {
        updateExposureStatus(id, HtxStatus.EXP_FLATTENING, null, null);
    }

    public void markExposureFlattened(long id, Long flattenOrderId) {
        updateExposureStatus(id, HtxStatus.EXP_FLATTENED, flattenOrderId, null);
    }

    public void markExposureManual(long id, String detail) {
        updateExposureStatus(id, HtxStatus.EXP_MANUAL, null, detail);
    }

    /**
     * 对账器平仓单 write-ahead：先落一行 sell-market 意图订单（best-effort，
     * 失败仅告警不抛——平仓由重试兜底，不因记账失败阻断收敛）。返回订单行 id（失败 -1）。
     */
    public long insertFlattenIntent(long pairId, String symbol, BigDecimal amount) {
        try {
            HtxOrderEntity e = new HtxOrderEntity();
            e.setPairId(pairId);
            e.setClientOrderId("flatten-" + pairId + "-" + System.currentTimeMillis());
            e.setSide("sell");
            e.setType("sell-market");
            e.setReqAmount(amount);
            e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
            orderMapper.insert(e);
            return e.getId();
        } catch (Exception ex) {
            log.error("insertFlattenIntent failed pairId={}", pairId, ex);
            return -1L;
        }
    }

    /** 平仓失败重试计数 +1（best-effort）。 */
    public void incrementFlattenRetry(long id) {
        try {
            HtxExposureEntity cur = exposureMapper.selectById(id);
            int next = (cur == null || cur.getFlattenRetries() == null) ? 1 : cur.getFlattenRetries() + 1;
            HtxExposureEntity e = new HtxExposureEntity();
            e.setId(id);
            e.setFlattenRetries(next);
            e.setUpdatedAt(LocalDateTime.now());
            exposureMapper.updateById(e);
        } catch (Exception ex) {
            log.error("incrementFlattenRetry failed id={}", id, ex);
        }
    }

    private void updateExposureStatus(long id, String status, Long flattenOrderId, String detail) {
        try {
            HtxExposureEntity e = new HtxExposureEntity();
            e.setId(id);
            e.setStatus(status);
            e.setFlattenOrderId(flattenOrderId);
            e.setDetail(detail);
            e.setUpdatedAt(LocalDateTime.now());
            exposureMapper.updateById(e);
        } catch (Exception ex) {
            log.error("updateExposureStatus failed id={} status={}", id, status, ex);
        }
    }

    private static boolean isTerminal(String state) {
        return "filled".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state)
                || "partial-canceled".equalsIgnoreCase(state) || "rejected".equalsIgnoreCase(state);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxTradeRecorderTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxPersistenceException.java \
        coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorder.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/persistence/HtxTradeRecorderTest.java
git commit -m "feat(htx): HtxTradeRecorder write-ahead persistence (fail-closed) + fee capture"
```

---

### Task 8: 结果码 PERSIST_WRITE_FAILED + 配置项

**Files:**
- Modify: `.../service/HtxLockedPairResult.java`（enum Code 内追加）
- Modify: `.../service/HtxTradeProperties.java`（Trade 内追加字段 + 新增 Reconciler 段）
- Modify: `coin-strategy-htx/src/main/resources/application.yml`（htx.trade / htx.reconciler）

- [ ] **Step 1: 加结果码**

在 `HtxLockedPairResult.Code` 枚举里，`REJECT_INVALID_INPUT` 之后追加：

```java
        /** 写前落库失败 → fail-closed，未发任何真实订单。 */
        PERSIST_WRITE_FAILED,
```

- [ ] **Step 2: 加配置字段**

在 `HtxTradeProperties.Trade` 类内 `queryFailureThreshold` 字段后追加：

```java
        /**
         * enabled=true 时，启动即校验 spot-account-id 非空。缺失则视为配置错误。
         * 默认 true（实盘必须有账户 id）。
         */
        private boolean requireSpotAccountIdWhenEnabled = true;
```

在 `HtxTradeProperties` 类内（`depthGuard` 字段后）追加对账器段：

```java
    private Reconciler reconciler = new Reconciler();

    @Data
    public static class Reconciler {
        /** 是否启用定时对账器（裸露自动市价平仓）。默认 true。 */
        private boolean enabled = true;
        /** 对账扫描间隔（ms）。默认 30s。 */
        private long intervalMs = 30_000;
        /** 单条裸露自动平仓的最大重试次数，超限转 MANUAL 并告警。 */
        private int maxFlattenRetries = 3;
    }
```

- [ ] **Step 3: application.yml 补默认值**

在 `application.yml` 的 `htx.trade` 段追加：

```yaml
    require-spot-account-id-when-enabled: true
```

在 `htx` 顶层（`depth-guard` 后）追加：

```yaml
  reconciler:
    enabled: true
    interval-ms: 30000
    max-flatten-retries: 3
```

- [ ] **Step 4: 验证编译**

Run: `mvn -q -pl coin-strategy-htx -am -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxLockedPairResult.java \
        coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxTradeProperties.java \
        coin-strategy-htx/src/main/resources/application.yml
git commit -m "feat(htx): add PERSIST_WRITE_FAILED code + reconciler/preflight props"
```

---

### Task 9: 改造 HtxLockedPairExecutor（写前落库 + 费用感知卖量 + 裸露记录）

核心改动：构造函数注入 `HtxTradeRecorder`；`execute` 增加 `long pairId` 参数；每腿发单前 `insertOrderIntent`（买腿失败向上抛 → fail-closed；卖腿写库失败/发单失败 → 记 exposure，因买腿已成交无法回滚）；卖量改用 `HtxFeeMath.sellableBase(成交, 买腿base手续费)`；`FillOutcome` 携带完整 `HtxOrder` 以采集手续费。

**Files:**
- Modify（整文件替换）: `.../service/HtxLockedPairExecutor.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxLockedPairExecutorTest.java`

- [ ] **Step 1: 写失败测试（Mockito）**

```java
package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HtxLockedPairExecutorTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxTradeProperties props;
    HtxTradeRecorder recorder;
    HtxLockedPairExecutor executor;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        props = new HtxTradeProperties();
        props.getApi().setSpotAccountId("acc-1");
        props.getTrade().setFillTimeoutMs(300);
        props.getTrade().setFillPollIntervalMs(10);
        recorder = mock(HtxTradeRecorder.class);
        // 写前默认成功：买腿 row=100, 卖腿 row=200
        when(recorder.insertOrderIntent(anyLong(), contains("BUY"), eq("buy"), any(), any(), any())).thenReturn(100L);
        when(recorder.insertOrderIntent(anyLong(), contains("SELL"), eq("sell"), any(), any(), any())).thenReturn(200L);
        executor = new HtxLockedPairExecutor(api, props, recorder);
    }

    private HtxLockedPairRequest req(String qty) {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt").quantity(new BigDecimal(qty))
                .buyPrice(new BigDecimal("60000")).sellPrice(new BigDecimal("60050"))
                .clientOrderId("key-1").build();
    }

    private HtxRestResponse<String> okPlace(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok"); r.setData(id); return r;
    }

    private HtxRestResponse<HtxOrder> okOrder(String state, String filled, String fees, String cash) {
        HtxOrder o = new HtxOrder();
        o.setState(state);
        o.setFilledAmount(new BigDecimal(filled));
        o.setFilledFees(new BigDecimal(fees));
        o.setFilledCashAmount(new BigDecimal(cash));
        HtxRestResponse<HtxOrder> r = new HtxRestResponse<>();
        r.setStatus("ok"); r.setData(o); return r;
    }

    @Test void buy_writeAhead_failure_aborts_before_any_REST() {
        when(recorder.insertOrderIntent(anyLong(), contains("BUY"), any(), any(), any(), any()))
                .thenThrow(new HtxPersistenceException("db down", null));
        assertThrows(HtxPersistenceException.class, () -> executor.execute(req("0.01"), 1L));
        verify(spot, never()).placeOrder(any(), any(), any(), any(), any(), any());
    }

    @Test void sell_amount_deducts_buy_base_fee() {
        // 买腿全成 0.01，手续费 0.00002 BTC → 应挂卖 0.00998
        when(spot.placeOrder(any(), any(), eq("buy-limit"), any(), any(), any())).thenReturn(okPlace("111"));
        when(spot.placeOrder(any(), any(), eq("sell-limit"), any(), any(), any())).thenReturn(okPlace("222"));
        when(spot.queryOrder(111L)).thenReturn(okOrder("filled", "0.01", "0.00002", "600"));
        when(spot.queryOrder(222L)).thenReturn(okOrder("filled", "0.00998", "0.6", "599"));

        HtxLockedPairResult res = executor.execute(req("0.01"), 1L);

        assertEquals(HtxLockedPairResult.Code.OK, res.getCode());
        ArgumentCaptor<String> amt = ArgumentCaptor.forClass(String.class);
        verify(spot).placeOrder(any(), any(), eq("sell-limit"), amt.capture(), any(), any());
        assertEquals(0, new BigDecimal("0.00998").compareTo(new BigDecimal(amt.getValue())));
    }

    @Test void sell_reject_after_buy_fill_records_exposure() {
        when(spot.placeOrder(any(), any(), eq("buy-limit"), any(), any(), any())).thenReturn(okPlace("111"));
        HtxRestResponse<String> rej = new HtxRestResponse<>();
        rej.setStatus("error"); rej.setErrMsg("sell-rejected");
        when(spot.placeOrder(any(), any(), eq("sell-limit"), any(), any(), any())).thenReturn(rej);
        when(spot.queryOrder(111L)).thenReturn(okOrder("filled", "0.01", "0.00002", "600"));

        HtxLockedPairResult res = executor.execute(req("0.01"), 1L);

        assertEquals(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL, res.getCode());
        verify(recorder).recordExposure(eq(1L), eq("btcusdt"), eq("base-long"), any(), anyString());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxLockedPairExecutorTest`
Expected: FAIL（executor 尚无 3 参构造 / `execute(req,pairId)` 签名）

- [ ] **Step 3: 整文件替换 `HtxLockedPairExecutor.java`**

```java
package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Locked-pair 执行。所有真实下单前先 write-ahead 落库（见 spec §6）：
 * 买腿写库失败 → 抛 HtxPersistenceException（fail-closed，未发 REST）；
 * 卖腿写库/发单失败发生在买腿已成交之后，无法回滚，改为记 exposure 交由对账器市价平掉。
 * 卖量用 {@link HtxFeeMath#sellableBase} 扣掉买腿 base 手续费，消除超卖。
 */
@Slf4j
@RequiredArgsConstructor
public class HtxLockedPairExecutor {

    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;
    private final HtxTradeRecorder recorder;

    public HtxLockedPairResult execute(HtxLockedPairRequest req, long pairId) {
        String symbol = req.getSymbol();
        String qtyStr = req.getQuantity().toPlainString();
        String buyPriceStr = req.getBuyPrice().toPlainString();
        String sellPriceStr = req.getSellPrice().toPlainString();
        String accountId = props.getApi().getSpotAccountId();
        long timeoutMs = req.getWaitFillTimeoutMs() != null ? req.getWaitFillTimeoutMs() : props.getTrade().getFillTimeoutMs();
        String buyClientId = derive(req.getClientOrderId(), "BUY");
        String sellClientId = derive(req.getClientOrderId(), "SELL");

        // ---- Leg 1: BUY write-ahead（失败抛异常 → TradingService 映射 PERSIST_WRITE_FAILED） ----
        long buyRow = recorder.insertOrderIntent(pairId, buyClientId, "buy", "buy-limit", req.getBuyPrice(), req.getQuantity());

        HtxRestResponse<String> buyResp = htxApiService.getSpotService()
                .placeOrder(accountId, symbol, "buy-limit", qtyStr, buyPriceStr, buyClientId);
        Long buyId = parseOrderId(buyResp);
        if (buyId == null) {
            recorder.markOrderSendFailed(buyRow, buyResp.getErrMsg());
            recorder.updatePairStatus(pairId, HtxStatus.BUY_REJECTED, "BUY_ORDER_REJECTED", buyResp.getErrMsg());
            log.warn("HTX BUY rejected for {}: {}", symbol, buyResp.getErrMsg());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.BUY_ORDER_REJECTED)
                    .message(buyResp.getErrCode() + ":" + buyResp.getErrMsg()).build();
        }
        recorder.markOrderSent(buyRow, buyId);
        recorder.updatePairStatus(pairId, HtxStatus.BUY_SENT, null, null);

        // ---- Wait BUY terminal ----
        FillOutcome buyOutcome = waitForFill(buyId, timeoutMs);
        if (buyOutcome.order != null) {
            recorder.updateOrderFill(buyRow, symbol, "buy", buyOutcome.order, JSON.toJSONString(buyOutcome.order));
        }
        if (buyOutcome.infraFailure) {
            recorder.updatePairStatus(pairId, HtxStatus.BUY_SENT, "QUERY_INFRA_ERROR", "buy-query-infra");
            log.error("HTX BUY {} query infra failure ({}); refusing to cancel", buyId, buyOutcome.consecutiveFailures);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.QUERY_INFRA_ERROR)
                    .buyOrderId(buyId).message("query-infra-fail:" + buyOutcome.consecutiveFailures).build();
        }
        if (buyOutcome.terminalRejected) {
            recorder.updatePairStatus(pairId, HtxStatus.BUY_REJECTED, "BUY_ORDER_REJECTED", "terminal:" + buyOutcome.finalState);
            log.warn("HTX BUY {} terminal without fill: {}", buyId, buyOutcome.finalState);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.BUY_ORDER_REJECTED)
                    .buyOrderId(buyId).message("terminal-state:" + buyOutcome.finalState).build();
        }
        if (!buyOutcome.filled && (buyOutcome.filledAmount == null || buyOutcome.filledAmount.signum() == 0)) {
            log.warn("HTX BUY {} not filled within {}ms; cancelling", buyId, timeoutMs);
            HtxRestResponse<String> cancelResp = htxApiService.getSpotService().cancelOrder(buyId);
            HtxRestResponse<HtxOrder> postCancel = htxApiService.getSpotService().queryOrder(buyId);
            BigDecimal postFilled = BigDecimal.ZERO;
            String postState = null;
            if (postCancel.ok() && postCancel.getData() != null) {
                postState = postCancel.getData().getState();
                postFilled = postCancel.getData().getFilledAmount() == null ? BigDecimal.ZERO : postCancel.getData().getFilledAmount();
            }
            recorder.recordCancel(buyRow, buyId, "buy-timeout", cancelResp.ok(), postState, postFilled);
            if (postFilled.signum() > 0) {
                log.warn("HTX BUY {} cancel race: filled={} — recovering with SELL", buyId, postFilled);
                buyOutcome.filledAmount = postFilled;
                buyOutcome.filled = false;
                if (postCancel.getData() != null) {
                    buyOutcome.order = postCancel.getData();
                    recorder.updateOrderFill(buyRow, symbol, "buy", buyOutcome.order, JSON.toJSONString(buyOutcome.order));
                }
                // fall through to partial recovery
            } else {
                recorder.updatePairStatus(pairId, HtxStatus.BUY_TIMEOUT, "BUY_WAIT_TIMEOUT", "timeout");
                return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.BUY_WAIT_TIMEOUT)
                        .buyOrderId(buyId).buyFilledAmount(BigDecimal.ZERO).message("timeout").build();
            }
        }

        // BUY produced fills.
        BigDecimal filledBase = buyOutcome.filled ? req.getQuantity() : buyOutcome.filledAmount;
        BigDecimal buyFeeBase = buyOutcome.order != null && buyOutcome.order.getFilledFees() != null
                ? buyOutcome.order.getFilledFees() : BigDecimal.ZERO;
        BigDecimal buyCash = buyOutcome.order != null && buyOutcome.order.getFilledCashAmount() != null
                ? buyOutcome.order.getFilledCashAmount() : BigDecimal.ZERO;
        boolean partial = !buyOutcome.filled;
        recorder.updatePairBuy(pairId, buyId, filledBase);
        if (partial) {
            HtxRestResponse<String> c = htxApiService.getSpotService().cancelOrder(buyId);
            recorder.recordCancel(buyRow, buyId, "partial-recover", c.ok(), null, filledBase);
            log.warn("HTX BUY {} partial-filled {}; cancelled remainder", buyId, filledBase);
        }

        // 费用感知卖量：扣掉买腿 base 手续费，消除超卖。
        BigDecimal sellable = HtxFeeMath.sellableBase(filledBase, buyFeeBase);

        // ---- Leg 2: SELL write-ahead（买腿已成交，写库失败→记 exposure，不抛） ----
        long sellRow;
        try {
            sellRow = recorder.insertOrderIntent(pairId, sellClientId, "sell", "sell-limit", req.getSellPrice(), sellable);
        } catch (RuntimeException persistEx) {
            recorder.recordExposure(pairId, symbol, "base-long", sellable, "sell-write-ahead-failed:" + persistEx.getMessage());
            recorder.updatePairStatus(pairId, HtxStatus.EXPOSED, "FAIL_AFTER_BUY_FILL", "sell-persist-failed");
            log.error("HTX SELL write-ahead failed after buy filled={}; exposure recorded. buyId={}", sellable, buyId, persistEx);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId).buyFilledAmount(filledBase).message("sell-persist-failed").build();
        }

        HtxRestResponse<String> sellResp = htxApiService.getSpotService()
                .placeOrder(accountId, symbol, "sell-limit", sellable.toPlainString(), sellPriceStr, sellClientId);
        Long sellId = parseOrderId(sellResp);
        if (sellId == null) {
            recorder.markOrderSendFailed(sellRow, sellResp.getErrMsg());
            recorder.recordExposure(pairId, symbol, "base-long", sellable, "sell-rejected:" + sellResp.getErrMsg());
            recorder.updatePairStatus(pairId, HtxStatus.EXPOSED, "FAIL_AFTER_BUY_FILL", "sell-rejected");
            log.error("HTX SELL rejected after buy filled={} — exposure recorded. buyId={} err={}", sellable, buyId, sellResp.getErrMsg());
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId).buyFilledAmount(filledBase).message("sell-rejected:" + sellResp.getErrMsg()).build();
        }
        recorder.markOrderSent(sellRow, sellId);
        recorder.updatePairSell(pairId, sellId, BigDecimal.ZERO);

        // ---- Wait SELL terminal ----
        FillOutcome sellOutcome = waitForFill(sellId, timeoutMs);
        if (sellOutcome.order != null) {
            recorder.updateOrderFill(sellRow, symbol, "sell", sellOutcome.order, JSON.toJSONString(sellOutcome.order));
        }
        BigDecimal sellFilled = sellOutcome.filledAmount == null ? BigDecimal.ZERO : sellOutcome.filledAmount;
        BigDecimal sellFeeQuote = sellOutcome.order != null && sellOutcome.order.getFilledFees() != null
                ? sellOutcome.order.getFilledFees() : BigDecimal.ZERO;
        BigDecimal sellCash = sellOutcome.order != null && sellOutcome.order.getFilledCashAmount() != null
                ? sellOutcome.order.getFilledCashAmount() : BigDecimal.ZERO;

        if (sellOutcome.infraFailure) {
            recorder.updatePairStatus(pairId, HtxStatus.SELL_SENT, "QUERY_INFRA_ERROR", "sell-query-infra");
            log.error("HTX SELL {} query infra failure; buy filled={}", sellId, filledBase);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.QUERY_INFRA_ERROR)
                    .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase)
                    .message("sell-query-infra-fail:" + sellOutcome.consecutiveFailures).build();
        }
        if (sellOutcome.terminalRejected) {
            recorder.recordExposure(pairId, symbol, "base-long", sellable.subtract(sellFilled), "sell-terminal:" + sellOutcome.finalState);
            recorder.updatePairStatus(pairId, HtxStatus.EXPOSED, "FAIL_AFTER_BUY_FILL", "sell-terminal:" + sellOutcome.finalState);
            log.error("HTX SELL {} rejected after placement — exposure recorded. buyId={}", sellId, buyId);
            return HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL)
                    .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase).sellFilledAmount(sellFilled)
                    .message("sell-terminal:" + sellOutcome.finalState).build();
        }
        if (!sellOutcome.filled) {
            BigDecimal unsold = sellable.subtract(sellFilled);
            if (unsold.signum() > 0) {
                recorder.recordExposure(pairId, symbol, "base-long", unsold, "sell-still-open");
            }
            recorder.updatePairStatus(pairId, HtxStatus.SELL_PENDING,
                    partial ? "BUY_PARTIAL_FILL_RECOVERED" : "SELL_PENDING", partial ? "buy-partial-sell-open" : "sell-open");
            HtxLockedPairResult.Code code = partial
                    ? HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED : HtxLockedPairResult.Code.SELL_PENDING;
            log.warn("HTX SELL {} not fully filled (filled={}); {}", sellId, sellFilled, code);
            return HtxLockedPairResult.builder().code(code)
                    .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase).sellFilledAmount(sellFilled)
                    .message(partial ? "buy-partial-sell-still-open" : "sell-still-open").build();
        }

        // Happy path: both filled → finalize with net accounting.
        BigDecimal netBaseDelta = sellable.subtract(sellFilled);
        BigDecimal netQuoteCost = sellCash.subtract(sellFeeQuote).subtract(buyCash);
        HtxLockedPairResult.Code okCode = partial
                ? HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED : HtxLockedPairResult.Code.OK;
        recorder.finalizePair(pairId, netBaseDelta, buyFeeBase, sellFeeQuote, netQuoteCost, okCode.name(),
                partial ? "buy-partial-sell-full" : "ok");
        log.info("HTX locked-pair {}: symbol={} buyId={} sellId={} sellable={} netBase={} netQuote={} feeBase={} feeQuote={}",
                okCode, symbol, buyId, sellId, sellable, netBaseDelta, netQuoteCost, buyFeeBase, sellFeeQuote);
        return HtxLockedPairResult.builder().code(okCode)
                .buyOrderId(buyId).sellOrderId(sellId).buyFilledAmount(filledBase).sellFilledAmount(sellFilled)
                .message(partial ? "buy-partial-sell-full" : "ok").build();
    }

    private FillOutcome waitForFill(long orderId, long timeoutMs) {
        long start = System.currentTimeMillis();
        FillOutcome out = new FillOutcome();
        int threshold = props.getTrade().getQueryFailureThreshold();
        while (System.currentTimeMillis() - start < timeoutMs) {
            HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrder(orderId);
            if (resp.ok() && resp.getData() != null) {
                out.consecutiveFailures = 0;
                HtxOrder o = resp.getData();
                out.order = o;
                out.finalState = o.getState();
                out.filledAmount = o.getFilledAmount();
                if ("filled".equalsIgnoreCase(out.finalState)) {
                    out.filled = true;
                    return out;
                }
                if ("canceled".equalsIgnoreCase(out.finalState)
                        || "partial-canceled".equalsIgnoreCase(out.finalState)
                        || "rejected".equalsIgnoreCase(out.finalState)) {
                    boolean anyFill = out.filledAmount != null && out.filledAmount.signum() > 0;
                    if (!anyFill) {
                        out.terminalRejected = true;
                    }
                    return out;
                }
            } else {
                out.consecutiveFailures++;
                if (out.consecutiveFailures >= threshold) {
                    out.infraFailure = true;
                    return out;
                }
            }
            sleep(props.getTrade().getFillPollIntervalMs());
        }
        return out;
    }

    private static Long parseOrderId(HtxRestResponse<String> resp) {
        if (!resp.ok() || resp.getData() == null || resp.getData().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(resp.getData().trim());
        } catch (NumberFormatException e) {
            log.warn("HTX returned non-numeric order id: {}", resp.getData());
            return null;
        }
    }

    private static String derive(String base, String suffix) {
        if (base == null || base.isEmpty()) {
            return null;
        }
        String combined = base + "-" + suffix;
        return combined.length() > 64 ? combined.substring(0, 64) : combined;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class FillOutcome {
        boolean filled;
        boolean terminalRejected;
        boolean infraFailure;
        int consecutiveFailures;
        BigDecimal filledAmount;
        String finalState;
        HtxOrder order;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxLockedPairExecutorTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxLockedPairExecutor.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxLockedPairExecutorTest.java
git commit -m "feat(htx): executor write-ahead persistence + fee-aware sell + exposure on single-leg"
```

---

### Task 10: HtxConfig — 注入 recorder，executor 改 3 参构造

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/config/HtxConfig.java`

- [ ] **Step 1: 修改 HtxConfig，把 recorder 注入 executor bean**

当前 `htxLockedPairExecutor(HtxApiService)` 是 2 参构造，改为注入 `HtxTradeRecorder` 后走 3 参构造。`HtxTradeRecorder` 是 `@Component`（Task 7），由 Spring 自动装配。

修改 `htxLockedPairExecutor` @Bean 方法签名与方法体：

```java
    @Bean
    public HtxLockedPairExecutor htxLockedPairExecutor(HtxApiService htxApiService,
                                                       HtxTradeRecorder recorder) {
        return new HtxLockedPairExecutor(htxApiService, props, recorder);
    }
```

在文件顶部补 import：

```java
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
```

- [ ] **Step 2: 编译确认**

Run: `mvn -q -pl coin-strategy-htx -am compile`
Expected: BUILD SUCCESS（executor 3 参构造已在 Task 9 定义，recorder 已在 Task 7 定义）

- [ ] **Step 3: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/config/HtxConfig.java
git commit -m "feat(htx): wire HtxTradeRecorder into locked-pair executor bean"
```

---

### Task 11: HtxTradingService — createPair + fail-closed 映射 + preflight + 传 pairId

**Files:**
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxTradingService.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxTradingServiceTest.java`

- [ ] **Step 1: 写失败测试**

先读现有 `HtxTradingService.java` 全文确认字段/方法签名（`executeLockedPair(HtxLockedPairRequest)` 返回 `HtxLockedPairResult`，持有 `HtxLockedPairExecutor executor`、`HtxTradeProperties props`）。测试覆盖三条路径：

```java
package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HtxTradingServiceTest {

    private HtxLockedPairExecutor executor;
    private HtxTradeRecorder recorder;
    private HtxTradeProperties props;
    private HtxTradingService service;

    private HtxLockedPairRequest req() {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt")
                .quantity(new BigDecimal("0.01"))
                .buyPrice(new BigDecimal("60000"))
                .sellPrice(new BigDecimal("60100"))
                .clientOrderId("key-1")
                .build();
    }

    @BeforeEach
    void setUp() {
        executor = mock(HtxLockedPairExecutor.class);
        recorder = mock(HtxTradeRecorder.class);
        props = new HtxTradeProperties();
        props.getTrade().setEnabled(true);
        props.getApi().setSpotAccountId("123");
        service = new HtxTradingService(executor, recorder, props);
    }

    @Test
    void realPath_persistsPairThenExecutesWithPairId() {
        when(recorder.createPair(any(), eq(false))).thenReturn(42L);
        when(executor.execute(any(), eq(42L)))
                .thenReturn(HtxLockedPairResult.builder()
                        .code(HtxLockedPairResult.Code.OK).build());

        HtxLockedPairResult r = service.executeLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.OK, r.getCode());
        verify(recorder).createPair(any(), eq(false));
        verify(executor).execute(any(), eq(42L));
    }

    @Test
    void dbWriteFailure_failsClosed_noExecute() {
        when(recorder.createPair(any(), eq(false)))
                .thenThrow(new HtxPersistenceException("db down"));

        HtxLockedPairResult r = service.executeLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.PERSIST_WRITE_FAILED, r.getCode());
        verify(executor, never()).execute(any(), anyLong());
    }

    @Test
    void preflight_enabledButNoSpotAccountId_rejects() {
        props.getApi().setSpotAccountId("");
        when(recorder.createPair(any(), eq(false))).thenReturn(1L);

        HtxLockedPairResult r = service.executeLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.REJECTED, r.getCode());
        verify(executor, never()).execute(any(), anyLong());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxTradingServiceTest`
Expected: FAIL（构造器 3 参不存在 / createPair 未调用 / PERSIST_WRITE_FAILED 未映射）

- [ ] **Step 3: 改造 HtxTradingService**

在类中加入 `HtxTradeRecorder recorder` 字段与构造器参数（保留现有 executor/props 注入方式；若原来是 `@RequiredArgsConstructor` 则加 `private final HtxTradeRecorder recorder;` 即可）。改造 `executeLockedPair` 主流程：

```java
    public HtxLockedPairResult executeLockedPair(HtxLockedPairRequest req) {
        boolean dryRun = !props.getTrade().isEnabled();

        // ── 实盘预检：enabled=true 时 spot-account-id 必须非空 ──
        if (!dryRun) {
            String acct = props.getApi().getSpotAccountId();
            if (acct == null || acct.trim().isEmpty()) {
                log.error("HTX preflight failed: trade.enabled=true but api.spot-account-id is empty; refuse to trade");
                return HtxLockedPairResult.builder()
                        .code(HtxLockedPairResult.Code.REJECTED)
                        .message("preflight: spot-account-id empty while enabled")
                        .build();
            }
        }

        // ── dry-run：best-effort 记账，绝不因写库失败中断 ──
        if (dryRun) {
            try {
                recorder.createPair(req, true);
            } catch (RuntimeException e) {
                log.warn("dry-run pair persist failed (ignored): {}", e.getMessage());
            }
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.DRY_RUN_OK)
                    .dryRun(true)
                    .message("dry-run: no real order placed")
                    .build();
        }

        // ── 实盘：write-ahead fail-closed ──
        long pairId;
        try {
            pairId = recorder.createPair(req, false);
        } catch (HtxPersistenceException e) {
            log.error("HTX fail-closed: cannot persist pair intent, refuse to trade: {}", e.getMessage());
            return HtxLockedPairResult.builder()
                    .code(HtxLockedPairResult.Code.PERSIST_WRITE_FAILED)
                    .message("db write-ahead failed: " + e.getMessage())
                    .build();
        }

        HtxLockedPairResult result = executor.execute(req, pairId);
        recorder.updatePairResult(pairId, result);
        return result;
    }
```

补 import：

```java
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
```

> 说明：`recorder.updatePairResult(pairId, result)` 为幂等 best-effort（Task 7 中 update 类方法吞异常记日志），executor 内部已在各腿写 htx_order/htx_exposure，这里只做主单终态回填。若原类无显式构造器（用 `@RequiredArgsConstructor`），测试里的 `new HtxTradingService(executor, recorder, props)` 需字段顺序一致：executor、recorder、props。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxTradingServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/service/HtxTradingService.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/service/HtxTradingServiceTest.java
git commit -m "feat(htx): trading service fail-closed persist + preflight + pairId handoff"
```

---

### Task 12: HtxStartupRecovery — 崩溃后重放非终态 pair/order

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/recovery/HtxStartupRecovery.java`
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/recovery/HtxStartupRecoveryTest.java`

- [ ] **Step 1: 写失败测试**

启动恢复语义：`ApplicationReadyEvent` 触发，扫描 `htx_order` 里 `intent_status = PENDING_SEND` 的行（写了行但没确认真的发出），用 `client_order_id` 调 `queryOrderByClientId` 判定 HTX 侧是否真收单：收单 → 回填 htx_order_id + intent_status=SENT；未收单 → intent_status=SEND_FAILED。仅实盘（`trade.enabled=true`）执行。

```java
package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.config.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxOrderMapper;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HtxStartupRecoveryTest {

    private HtxOrderMapper orderMapper;
    private HtxApiService api;
    private HtxSpotService spot;
    private HtxTradeProperties props;
    private HtxStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        orderMapper = mock(HtxOrderMapper.class);
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        props = new HtxTradeProperties();
        props.getTrade().setEnabled(true);
        recovery = new HtxStartupRecovery(orderMapper, api, props);
    }

    @Test
    void pendingSend_htxHasOrder_backfillsSent() {
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(1L);
        e.setClientOrderId("key-1-BUY");
        e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
        when(orderMapper.selectList(any())).thenReturn(List.of(e));

        HtxOrder o = new HtxOrder();
        o.setOrderId(9001L);
        o.setState("submitted");
        when(spot.queryOrderByClientId("key-1-BUY"))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("ok").data(o).build());

        recovery.recover();

        verify(orderMapper).updateById(argThat(u ->
                Long.valueOf(9001L).equals(u.getHtxOrderId())
                        && HtxStatus.INTENT_SENT.equals(u.getIntentStatus())));
    }

    @Test
    void pendingSend_htxHasNoOrder_marksSendFailed() {
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(2L);
        e.setClientOrderId("key-2-BUY");
        e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
        when(orderMapper.selectList(any())).thenReturn(List.of(e));
        when(spot.queryOrderByClientId("key-2-BUY"))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("error").errMsg("not-found").build());

        recovery.recover();

        verify(orderMapper).updateById(argThat(u ->
                HtxStatus.INTENT_SEND_FAILED.equals(u.getIntentStatus())));
    }

    @Test
    void disabled_doesNothing() {
        props.getTrade().setEnabled(false);
        recovery.recover();
        verifyNoInteractions(orderMapper);
    }
}
```

> 若 `HtxRestResponse` 无 `ok(...)`/`error(...)` 静态工厂，先读该类确认真实构造方式，测试与实现同步改用真实 API（例如 `new HtxRestResponse<>(...)` 或 setter）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxStartupRecoveryTest`
Expected: FAIL（HtxStartupRecovery 不存在）

- [ ] **Step 3: 实现 HtxStartupRecovery**

```java
package com.metaverse.coin.strategy.htx.recovery;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.htx.config.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxOrderMapper;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 崩溃恢复：启动后扫描 write-ahead 未确认发出的订单意图，据 HTX 实况回填或标记失败。
 * 仅实盘（trade.enabled=true）执行。
 */
@Slf4j
@Component
public class HtxStartupRecovery {

    private final HtxOrderMapper orderMapper;
    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;

    public HtxStartupRecovery(HtxOrderMapper orderMapper, HtxApiService htxApiService, HtxTradeProperties props) {
        this.orderMapper = orderMapper;
        this.htxApiService = htxApiService;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        recover();
    }

    public void recover() {
        if (!props.getTrade().isEnabled()) {
            log.info("HTX startup recovery skipped (trade disabled / dry-run)");
            return;
        }
        QueryWrapper<HtxOrderEntity> qw = new QueryWrapper<>();
        qw.eq("intent_status", HtxStatus.INTENT_PENDING_SEND);
        List<HtxOrderEntity> pending = orderMapper.selectList(qw);
        if (pending == null || pending.isEmpty()) {
            log.info("HTX startup recovery: no PENDING_SEND orders");
            return;
        }
        log.warn("HTX startup recovery: {} PENDING_SEND order(s) to reconcile", pending.size());
        for (HtxOrderEntity e : pending) {
            reconcileOne(e);
        }
    }

    private void reconcileOne(HtxOrderEntity e) {
        try {
            HtxRestResponse<HtxOrder> resp = htxApiService.getSpotService().queryOrderByClientId(e.getClientOrderId());
            HtxOrderEntity upd = new HtxOrderEntity();
            upd.setId(e.getId());
            if (resp != null && resp.ok() && resp.getData() != null && resp.getData().getOrderId() != null) {
                upd.setHtxOrderId(resp.getData().getOrderId());
                upd.setState(resp.getData().getState());
                upd.setIntentStatus(HtxStatus.INTENT_SENT);
                log.warn("recovery: order id={} clientId={} was actually SENT (htxId={})",
                        e.getId(), e.getClientOrderId(), resp.getData().getOrderId());
            } else {
                upd.setIntentStatus(HtxStatus.INTENT_SEND_FAILED);
                log.warn("recovery: order id={} clientId={} confirmed SEND_FAILED",
                        e.getId(), e.getClientOrderId());
            }
            orderMapper.updateById(upd);
        } catch (RuntimeException ex) {
            log.error("recovery failed for order id={} clientId={}: {}",
                    e.getId(), e.getClientOrderId(), ex.getMessage());
        }
    }
}
```

> `HtxRestResponse` 真实 API：成功判定用 `resp.ok()`（`"ok".equalsIgnoreCase(status)`），数据取 `resp.getData()`，错误信息 `resp.getErrMsg()`；无静态工厂，测试构造用 `HtxRestResponse.<T>builder().status("ok").data(x).build()`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxStartupRecoveryTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/recovery/HtxStartupRecovery.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/recovery/HtxStartupRecoveryTest.java
git commit -m "feat(htx): startup recovery reconciles PENDING_SEND write-ahead orders"
```

---

### Task 13: HtxReconciler — 定时对账，单腿裸露自动市价平仓

**Files:**
- Create: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/recovery/HtxReconciler.java`
- Modify: `coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/CoinStrategyHtxApplication.java`（加 `@EnableScheduling`）
- Test: `coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/recovery/HtxReconcilerTest.java`

- [ ] **Step 1: 写失败测试**

对账语义：定时拉 `htx_exposure` 中 `status=OPEN` 的裸露行，对 base 多头（`exposed_side="base-long"`）下 **sell-market** 平回中性；平仓单本身也 write-ahead（先 `insertOrderIntent` type=`sell-market`）。成功 → 标 FLATTENED + 回填 flatten_order_id；失败累加重试，超过 `maxFlattenRetries` → 标 MANUAL + 告警。仅实盘执行。

```java
package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.config.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxExposureEntity;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HtxReconcilerTest {

    private HtxTradeRecorder recorder;
    private HtxApiService api;
    private HtxSpotService spot;
    private HtxTradeProperties props;
    private HtxReconciler reconciler;

    private HtxExposureEntity openExposure() {
        HtxExposureEntity ex = new HtxExposureEntity();
        ex.setId(7L);
        ex.setPairId(3L);
        ex.setSymbol("btcusdt");
        ex.setExposedSide("base-long");
        ex.setExposedAmount(new BigDecimal("0.00998"));
        ex.setStatus(HtxStatus.EXP_OPEN);
        return ex;
    }

    @BeforeEach
    void setUp() {
        recorder = mock(HtxTradeRecorder.class);
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        props = new HtxTradeProperties();
        props.getTrade().setEnabled(true);
        props.getApi().setSpotAccountId("123");
        props.getReconciler().setMaxFlattenRetries(3);
        reconciler = new HtxReconciler(recorder, api, props);
    }

    @Test
    void openBaseLong_marketFlattens_marksFlattened() {
        when(recorder.findOpenExposures()).thenReturn(List.of(openExposure()));
        when(recorder.insertFlattenIntent(eq(3L), eq("btcusdt"), any())).thenReturn(55L);
        when(spot.placeOrder(eq("123"), eq("btcusdt"), eq("sell-market"), any(), isNull(), anyString()))
                .thenReturn(HtxRestResponse.<String>builder().status("ok").data("88001").build());

        reconciler.reconcile();

        verify(recorder).markExposureFlattened(eq(7L), eq(88001L));
    }

    @Test
    void flattenRestFails_incrementsRetry_notFlattened() {
        when(recorder.findOpenExposures()).thenReturn(List.of(openExposure()));
        when(recorder.insertFlattenIntent(anyLong(), anyString(), any())).thenReturn(55L);
        when(spot.placeOrder(anyString(), anyString(), eq("sell-market"), any(), isNull(), anyString()))
                .thenReturn(HtxRestResponse.<String>builder().status("error").errMsg("insufficient").build());

        reconciler.reconcile();

        verify(recorder, never()).markExposureFlattened(anyLong(), anyLong());
        verify(recorder).incrementFlattenRetry(eq(7L));
    }

    @Test
    void retriesExhausted_marksManual() {
        HtxExposureEntity ex = openExposure();
        ex.setFlattenRetries(3);
        when(recorder.findOpenExposures()).thenReturn(List.of(ex));

        reconciler.reconcile();

        verify(recorder).markExposureManual(eq(7L), anyString());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void disabled_doesNothing() {
        props.getTrade().setEnabled(false);
        reconciler.reconcile();
        verifyNoInteractions(recorder);
    }
}
```

> 依赖项已在前序任务就绪：`HtxExposureEntity.flattenRetries`（Task 5）、schema `flatten_retries INT NOT NULL DEFAULT 0`（Task 2）、`HtxTradeRecorder` 的 `findOpenExposures()`/`insertFlattenIntent(pairId,symbol,amount)→long`/`markExposureFlattened(id,Long)`/`incrementFlattenRetry(id)`/`markExposureManual(id,detail)`（Task 7）。本任务无需回改前序任务。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxReconcilerTest`
Expected: FAIL（HtxReconciler 不存在）

- [ ] **Step 3: 实现 HtxReconciler**

```java
package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.config.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxExposureEntity;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 定时对账器：检测 htx_exposure 中 OPEN 裸露头寸，市价平回中性。
 * base 多头 → sell-market；平仓单 write-ahead；超过重试上限转 MANUAL + 告警。
 */
@Slf4j
@Component
public class HtxReconciler {

    private final HtxTradeRecorder recorder;
    private final HtxApiService htxApiService;
    private final HtxTradeProperties props;

    public HtxReconciler(HtxTradeRecorder recorder, HtxApiService htxApiService, HtxTradeProperties props) {
        this.recorder = recorder;
        this.htxApiService = htxApiService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${htx.reconciler.interval-ms:30000}", initialDelay = 15000)
    public void scheduled() {
        if (!props.getReconciler().isEnabled()) {
            return;
        }
        reconcile();
    }

    public void reconcile() {
        if (!props.getTrade().isEnabled()) {
            return;
        }
        List<HtxExposureEntity> open = recorder.findOpenExposures();
        if (open == null || open.isEmpty()) {
            return;
        }
        log.warn("HTX reconciler: {} OPEN exposure(s)", open.size());
        for (HtxExposureEntity ex : open) {
            flattenOne(ex);
        }
    }

    private void flattenOne(HtxExposureEntity ex) {
        int retries = ex.getFlattenRetries() == null ? 0 : ex.getFlattenRetries();
        if (retries >= props.getReconciler().getMaxFlattenRetries()) {
            log.error("HTX reconciler ALERT: exposure id={} pair={} symbol={} exhausted {} retries -> MANUAL",
                    ex.getId(), ex.getPairId(), ex.getSymbol(), retries);
            recorder.markExposureManual(ex.getId(), "flatten retries exhausted: " + retries);
            return;
        }
        if (!"base-long".equals(ex.getExposedSide())) {
            log.error("HTX reconciler: unsupported exposed_side={} on id={}; MANUAL",
                    ex.getExposedSide(), ex.getId());
            recorder.markExposureManual(ex.getId(), "unsupported exposed_side: " + ex.getExposedSide());
            return;
        }
        BigDecimal amount = ex.getExposedAmount();
        try {
            recorder.insertFlattenIntent(ex.getPairId(), ex.getSymbol(), amount);
            String clientId = "flatten-" + ex.getId() + "-" + retries;
            HtxRestResponse<String> resp = htxApiService.getSpotService().placeOrder(
                    props.getApi().getSpotAccountId(), ex.getSymbol(),
                    "sell-market", amount, null, clientId);
            Long htxOrderId = parseOrderId(resp);
            if (htxOrderId != null) {
                recorder.markExposureFlattened(ex.getId(), htxOrderId);
                log.warn("HTX reconciler: exposure id={} flattened via sell-market htxId={}",
                        ex.getId(), htxOrderId);
            } else {
                recorder.incrementFlattenRetry(ex.getId());
                log.error("HTX reconciler: flatten REST failed for exposure id={} (retry {}/{})",
                        ex.getId(), retries + 1, props.getReconciler().getMaxFlattenRetries());
            }
        } catch (RuntimeException e) {
            recorder.incrementFlattenRetry(ex.getId());
            log.error("HTX reconciler: flatten exception for exposure id={}: {}", ex.getId(), e.getMessage());
        }
    }

    private Long parseOrderId(HtxRestResponse<String> resp) {
        if (resp == null || !resp.ok() || resp.getData() == null) {
            return null;
        }
        try {
            return Long.parseLong(resp.getData().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 加 @EnableScheduling**

在 `CoinStrategyHtxApplication.java` 类上补 `@EnableScheduling` 注解与 import `org.springframework.scheduling.annotation.EnableScheduling`。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -pl coin-strategy-htx test -Dtest=HtxReconcilerTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/recovery/HtxReconciler.java \
        coin-strategy-htx/src/main/java/com/metaverse/coin/strategy/htx/CoinStrategyHtxApplication.java \
        coin-strategy-htx/src/test/java/com/metaverse/coin/strategy/htx/recovery/HtxReconcilerTest.java
git commit -m "feat(htx): scheduled reconciler market-flattens naked exposure with retry/MANUAL"
```

---

### Task 14: 环境变量与配置文档（htx.env DB 变量）

**Files:**
- Modify: `deploy/linux/env/htx.env`（gitignored，本地追加；不进 git）
- Create: `deploy/linux/env/htx.env.example`（模板，进 git）

- [ ] **Step 1: 在本地 htx.env 追加 DB 变量段**

在 `deploy/linux/env/htx.env` 末尾追加（该文件 gitignored，只在本机生效）：

```bash
# ── 专用库 htx_trade_strategy 连接（本模块独占，勿与 8081 共用）──
export HTX_DB_HOST="127.0.0.1"
export HTX_DB_PORT="3306"
export HTX_DB_USERNAME="root"
export HTX_DB_PASSWORD=""
```

- [ ] **Step 2: 建可进 git 的模板 htx.env.example**

创建 `deploy/linux/env/htx.env.example`（不含真实密钥，进 git 供他人参考）：

```bash
#!/usr/bin/env bash
# htx.env.example — HTX 现货执行服务环境变量模板（复制为 htx.env 后填真值，htx.env 不进 git）
export HTX_ACCESS_KEY=""
export HTX_SECRET_KEY=""
export HTX_SPOT_ACCOUNT_ID=""
export HTX_TRADE_ENABLED="false"

# 专用库 htx_trade_strategy（首次启动 createDatabaseIfNotExist=true 自动建库 + schema.sql 建表）
export HTX_DB_HOST="127.0.0.1"
export HTX_DB_PORT="3306"
export HTX_DB_USERNAME="root"
export HTX_DB_PASSWORD=""
```

- [ ] **Step 3: 确认 htx.env.example 未被 .gitignore 拦（htx.env 才在忽略列表）**

Run: `git check-ignore -v deploy/linux/env/htx.env.example || echo "not ignored, OK to commit"`
Expected: `not ignored, OK to commit`

- [ ] **Step 4: 提交模板**

```bash
git add deploy/linux/env/htx.env.example
git commit -m "docs(htx): env template with dedicated htx_trade_strategy DB vars"
```

---

### Task 15: 全模块编译 + 全量测试收口

**Files:** 无新增，仅验证。

- [ ] **Step 1: 全量编译 htx 模块及其依赖**

Run: `mvn -q -pl coin-strategy-htx -am -DskipTests package`
Expected: BUILD SUCCESS

- [ ] **Step 2: 跑 htx 模块全部测试（含 mapper 集成测试）**

Run: `mvn -q -pl coin-strategy-htx test`
Expected: 全绿。含 HtxSymbolUtilTest / HtxFeeMathTest / HtxMapperIntegrationTest / HtxTradeRecorderTest / HtxLockedPairExecutorTest / HtxTradingServiceTest / HtxStartupRecoveryTest / HtxReconcilerTest。

- [ ] **Step 3: 确认其它模块未受影响（隔离性验证）**

Run: `mvn -q -pl coin-strategy-job,coin-strategy-core,coin-strategy-web -am -DskipTests compile`
Expected: BUILD SUCCESS（htx 的 DB/对账器不外溢，其它模块零改动仍编过）

- [ ] **Step 4: 无新增提交（若前序任务均已提交）**

若有遗留改动：
```bash
git status
```
按需 `git add` + `git commit`。

---

## 验收清单（对齐 spec §3 缺口逐项闭合）

- [ ] **A 持久化**：dry-run 与实盘均落 `htx_locked_pair`；实盘每腿落 `htx_order`；撤单落 `htx_cancel`；裸露落 `htx_exposure`。
- [ ] **B1 超卖**：卖腿量 = `HtxFeeMath.sellableBase(buyFilled, buyFeeBase)`，不再用 `req.getQuantity()`。
- [ ] **B2 手续费采集**：`htx_order.filled_fees` + `fee_currency`（买=base、卖=quote，经 HtxSymbolUtil 派生）落库；主单 `total_fee_base/total_fee_quote/net_quote_cost` 聚合。
- [ ] **C1 崩溃→裸单**：`HtxStartupRecovery` 重放 PENDING_SEND；卖腿失败写 exposure。
- [ ] **C2 对账器**：`HtxReconciler` 定时市价平仓，重试上限转 MANUAL + 告警日志。
- [ ] **C3 fail-closed**：`recorder.createPair` 抛 `HtxPersistenceException` → `PERSIST_WRITE_FAILED`，不发 REST。
- [ ] **C4 实盘预检**：`enabled=true` 且 `spot-account-id` 空 → REJECTED，不下单。
- [ ] **隔离性**：DB 依赖、对账器、专用库仅存在于 coin-strategy-htx；其它模块编译不受影响。
