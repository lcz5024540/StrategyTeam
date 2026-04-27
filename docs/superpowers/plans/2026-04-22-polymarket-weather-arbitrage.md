# Polymarket Weather Arbitrage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fully autonomous weather arbitrage module that scans Polymarket weather markets every 15 minutes, computes fair-value probabilities from tomorrow.io forecasts, and places orders when edge > 10% — isolated from the existing follow-trade and hedge logic.

**Architecture:** New `weather/` package inside `coin-strategy-job`, calling existing `PolymarketClobExecutionService` and `ExitSignalProvider` interfaces. Capital tracked independently via `WeatherCapitalPoolService`. Position rows tagged with `source=WEATHER` to isolate from follow-trade positions.

**Tech Stack:** Java 17, Spring Boot, MyBatis Plus, OkHttp 4.12, Jackson, Lombok, JUnit 5

---

## File Map

### New files — coin-strategy-job
```
src/main/java/com/metaverse/coin/strategy/job/weather/
├── WeatherArbitrageJob.java
├── config/
│   └── WeatherArbitrageProperties.java
├── client/
│   └── TomorrowIoApiClient.java
├── model/
│   ├── WeatherForecast.java
│   ├── WeatherMarketSpec.java
│   └── WeatherArbitrageSignal.java
└── service/
    ├── WeatherMarketTitleParser.java
    ├── WeatherFairValueService.java
    ├── WeatherMarketScanService.java
    ├── WeatherArbitrageSignalService.java
    ├── WeatherCapitalPoolService.java
    └── WeatherPositionSyncService.java

src/test/java/com/metaverse/coin/strategy/job/weather/service/
├── WeatherMarketTitleParserTest.java
├── WeatherFairValueServiceTest.java
├── WeatherArbitrageSignalServiceTest.java
└── WeatherCapitalPoolServiceTest.java
```

### New files — coin-strategy-common
```
common-model/src/main/java/.../model/arbitrage/entity/
└── WeatherArbitrageSignalLogEntity.java

common-dao/src/main/java/.../dao/mapper/
└── WeatherArbitrageSignalLogMapper.java
```

### Modified files
- `sql/weather_arbitrage_migration.sql` — create new (DDL)
- `coin-strategy-common/common-model/src/main/java/com/metaverse/coin/strategy/common/model/arbitrage/entity/SmartWalletPositionEntity.java` — add `source` field
- `coin-strategy-job/src/main/resources/application.yml` — add `weather-arbitrage` config block
- `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/CoinStrategyJobApplication.java` — register `WeatherArbitrageProperties` (if using `@EnableConfigurationProperties`)

---

## Task 1: SQL Migration

**Files:**
- Create: `sql/weather_arbitrage_migration.sql`

- [ ] **Step 1: Create migration file**

```sql
-- weather_arbitrage_migration.sql
-- Run once against the target database

-- 1. Add source column to smart_wallet_position
ALTER TABLE `smart_wallet_position`
    ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_TRADE'
        COMMENT 'FOLLOW_TRADE / WEATHER'
        AFTER `status`;

-- 2. Create weather arbitrage signal log table
CREATE TABLE IF NOT EXISTS `weather_arbitrage_signal_log` (
    `id`           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`    VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId',
    `city`         VARCHAR(64)  NOT NULL,
    `market_price` DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `fair_value`   DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `edge`         DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `action`       VARCHAR(16)  NOT NULL COMMENT 'PASS / BUY_YES / BUY_NO',
    `reason`       VARCHAR(512) NOT NULL DEFAULT '',
    `scanned_at`   DATETIME     NOT NULL,
    `created_at`   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_signal_log_city_scanned` (`city`, `scanned_at`),
    INDEX `idx_signal_log_market`       (`market_id`, `scanned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气套利信号扫描日志';
```

- [ ] **Step 2: Commit**

```bash
git add sql/weather_arbitrage_migration.sql
git commit -m "feat(weather): add SQL migration for source column and signal log table"
```

---

## Task 2: Add `source` field to SmartWalletPositionEntity

**Files:**
- Modify: `coin-strategy-common/common-model/src/main/java/com/metaverse/coin/strategy/common/model/arbitrage/entity/SmartWalletPositionEntity.java`

- [ ] **Step 1: Read the current entity** (already done during planning — file is at the path above)

- [ ] **Step 2: Add `source` field**

Add after the `status` field (line 36):

```java
    private String source;  // FOLLOW_TRADE / WEATHER
```

The field block should look like:
```java
    private String status;
    private String source;  // FOLLOW_TRADE / WEATHER
    private Date createdAt;
```

- [ ] **Step 3: Compile to verify**

```bash
cd /Users/lichangzheng.ray/Ray/CoinStrategy
mvn -pl coin-strategy-common/common-model compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-common/common-model/src/main/java/com/metaverse/coin/strategy/common/model/arbitrage/entity/SmartWalletPositionEntity.java
git commit -m "feat(weather): add source field to SmartWalletPositionEntity"
```

---

## Task 3: WeatherArbitrageSignalLogEntity + Mapper

**Files:**
- Create: `coin-strategy-common/common-model/src/main/java/com/metaverse/coin/strategy/common/model/arbitrage/entity/WeatherArbitrageSignalLogEntity.java`
- Create: `coin-strategy-common/common-dao/src/main/java/com/metaverse/coin/strategy/common/dao/mapper/WeatherArbitrageSignalLogMapper.java`

- [ ] **Step 1: Create WeatherArbitrageSignalLogEntity**

```java
package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("weather_arbitrage_signal_log")
public class WeatherArbitrageSignalLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String city;
    private BigDecimal marketPrice;
    private BigDecimal fairValue;
    private BigDecimal edge;
    /** PASS / BUY_YES / BUY_NO */
    private String action;
    private String reason;
    private Date scannedAt;
    private Date createdAt;
}
```

- [ ] **Step 2: Create WeatherArbitrageSignalLogMapper**

```java
package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherArbitrageSignalLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeatherArbitrageSignalLogMapper extends BaseMapper<WeatherArbitrageSignalLogEntity> {
}
```

- [ ] **Step 3: Compile**

```bash
mvn -pl coin-strategy-common/common-model,coin-strategy-common/common-dao compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-common/common-model/src/main/java/com/metaverse/coin/strategy/common/model/arbitrage/entity/WeatherArbitrageSignalLogEntity.java \
        coin-strategy-common/common-dao/src/main/java/com/metaverse/coin/strategy/common/dao/mapper/WeatherArbitrageSignalLogMapper.java
git commit -m "feat(weather): add WeatherArbitrageSignalLogEntity and Mapper"
```

---

## Task 4: WeatherArbitrageProperties

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/config/WeatherArbitrageProperties.java`

- [ ] **Step 1: Create properties class**

```java
package com.metaverse.coin.strategy.job.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "weather-arbitrage")
public class WeatherArbitrageProperties {

    private boolean enabled = false;
    private BigDecimal capitalPoolUsdc = new BigDecimal("500");
    private BigDecimal maxSingleStakeUsdc = new BigDecimal("50");
    private int maxOpenPositions = 5;
    private BigDecimal entryEdgeThreshold = new BigDecimal("0.10");
    private BigDecimal minLiquidityUsdc = new BigDecimal("50");
    /** Min hours until market resolution before we refuse to open new positions */
    private int minHoursToSettlement = 12;
    /** Max hours until market resolution — forecasts beyond this are unreliable */
    private int maxHoursToSettlement = 72;
    private BigDecimal sigmaF = new BigDecimal("3.5");
    private List<String> cities = List.of("London", "New York", "Seoul");
    private String tomorrowIoApiKey = "";
    private long scanFixedDelayMs = 900_000L;   // 15 min
    private long syncFixedDelayMs  = 300_000L;  // 5 min
}
```

- [ ] **Step 2: Add config block to application.yml**

Find `coin-strategy-job/src/main/resources/application.yml` and add at the end:

```yaml
weather-arbitrage:
  enabled: false
  capital-pool-usdc: 500
  max-single-stake-usdc: 50
  max-open-positions: 5
  entry-edge-threshold: 0.10
  min-liquidity-usdc: 50
  min-hours-to-settlement: 12
  max-hours-to-settlement: 72
  sigma-f: 3.5
  cities:
    - London
    - New York
    - Seoul
  tomorrow-io-api-key: ${TOMORROW_IO_API_KEY:}
  scan-fixed-delay-ms: 900000
  sync-fixed-delay-ms: 300000
```

- [ ] **Step 3: Register the properties bean**

Find the main application class or a config class that uses `@EnableConfigurationProperties`. Add `WeatherArbitrageProperties.class` to its list.

Example: If `CoinStrategyJobApplication.java` has:
```java
@EnableConfigurationProperties({SmartWalletProperties.class})
```
Change to:
```java
@EnableConfigurationProperties({SmartWalletProperties.class, WeatherArbitrageProperties.class})
```

- [ ] **Step 4: Compile**

```bash
mvn -pl coin-strategy-job compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/config/WeatherArbitrageProperties.java \
        coin-strategy-job/src/main/resources/application.yml
git commit -m "feat(weather): add WeatherArbitrageProperties config class"
```

---

## Task 5: Data Models

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/model/WeatherForecast.java`
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/model/WeatherMarketSpec.java`
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/model/WeatherArbitrageSignal.java`

- [ ] **Step 1: Create WeatherForecast**

```java
package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class WeatherForecast {
    private String city;
    private BigDecimal forecastTempMaxF;
    private BigDecimal forecastTempMinF;
    private BigDecimal forecastTempAvgF;
    private Instant fetchedAt;
}
```

- [ ] **Step 2: Create WeatherMarketSpec**

```java
package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Parsed structure of a Polymarket weather market.
 */
@Data
@Builder
public class WeatherMarketSpec {

    public enum TemperatureType { MAX, MIN, AVG }

    /** Polymarket conditionId */
    private String marketId;
    /** YES token id */
    private String tokenId;
    /** Raw market question */
    private String question;
    private String city;
    private TemperatureType temperatureType;
    /** Temperature threshold in Fahrenheit extracted from the question */
    private BigDecimal thresholdF;
    /** Current YES token mid-price [0,1] */
    private BigDecimal yesPrice;
    /** Current NO token mid-price */
    private BigDecimal noPrice;
    /** Available liquidity in USDC */
    private BigDecimal liquidityUsdc;
    /** Unix epoch seconds of market resolution */
    private long endTimestampSeconds;
}
```

- [ ] **Step 3: Create WeatherArbitrageSignal**

```java
package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class WeatherArbitrageSignal {

    public enum Side { BUY_YES, BUY_NO }

    private String marketId;
    private String tokenId;
    private Side side;
    private String city;
    private String question;
    /** Model-computed fair probability for the token being bought */
    private BigDecimal fairValue;
    /** Current market price of the token being bought */
    private BigDecimal marketPrice;
    /** (fairValue - marketPrice) / marketPrice */
    private BigDecimal edge;
    /** Amount to stake in USDC (computed by WeatherCapitalPoolService) */
    private BigDecimal stakeUsdc;
}
```

- [ ] **Step 4: Compile**

```bash
mvn -pl coin-strategy-job compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/model/
git commit -m "feat(weather): add WeatherForecast, WeatherMarketSpec, WeatherArbitrageSignal models"
```

---

## Task 6: WeatherMarketTitleParser (TDD)

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherMarketTitleParser.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherMarketTitleParserTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WeatherMarketTitleParserTest {

    private final WeatherMarketTitleParser parser = new WeatherMarketTitleParser();

    @Test
    void parse_londonHighExceed_returnsMaxSpec() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-001", "tok-001",
                "Will London high exceed 68°F tomorrow?",
                new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("200"), System.currentTimeMillis() / 1000 + 3600 * 24);

        assertTrue(result.isPresent());
        WeatherMarketSpec spec = result.get();
        assertEquals("London", spec.getCity());
        assertEquals(WeatherMarketSpec.TemperatureType.MAX, spec.getTemperatureType());
        assertEquals(new BigDecimal("68"), spec.getThresholdF());
    }

    @Test
    void parse_nycLowBelow_returnsMinSpec() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-002", "tok-002",
                "NYC low below 45°F on April 23?",
                new BigDecimal("0.07"), new BigDecimal("0.93"),
                new BigDecimal("100"), System.currentTimeMillis() / 1000 + 3600 * 30);

        assertTrue(result.isPresent());
        WeatherMarketSpec spec = result.get();
        assertEquals("New York", spec.getCity());
        assertEquals(WeatherMarketSpec.TemperatureType.MIN, spec.getTemperatureType());
        assertEquals(new BigDecimal("45"), spec.getThresholdF());
    }

    @Test
    void parse_seoulTitle_returnsSeoul() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-003", "tok-003",
                "Will Seoul high exceed 75°F tomorrow?",
                new BigDecimal("0.04"), new BigDecimal("0.96"),
                new BigDecimal("80"), System.currentTimeMillis() / 1000 + 3600 * 20);

        assertTrue(result.isPresent());
        assertEquals("Seoul", result.get().getCity());
    }

    @Test
    void parse_unrecognizedCity_returnsEmpty() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-004", "tok-004",
                "Will Tokyo high exceed 80°F tomorrow?",
                new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100"), System.currentTimeMillis() / 1000 + 3600 * 20);

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_noTemperatureNumber_returnsEmpty() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-005", "tok-005",
                "Will London be hot tomorrow?",
                new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100"), System.currentTimeMillis() / 1000 + 3600 * 20);

        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherMarketTitleParserTest -q 2>&1 | tail -10
```

Expected: FAIL — class not found or compilation error.

- [ ] **Step 3: Implement WeatherMarketTitleParser**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Polymarket market title into a WeatherMarketSpec.
 * Returns empty if the title cannot be understood.
 */
@Service
public class WeatherMarketTitleParser {

    /** keyword → canonical city name (checked case-insensitively against title) */
    private static final Map<String, String> CITY_KEYWORDS = new LinkedHashMap<>();

    static {
        CITY_KEYWORDS.put("london",   "London");
        CITY_KEYWORDS.put("new york", "New York");
        CITY_KEYWORDS.put("nyc",      "New York");
        CITY_KEYWORDS.put("seoul",    "Seoul");
    }

    // Matches patterns like "exceed 68°F", "above 68°F", "below 45°F", "low 45°F", "high 68 degrees"
    private static final Pattern HIGH_PATTERN = Pattern.compile(
            "(?:high|exceed|above|over)[^0-9]*([0-9]+(?:\\.[0-9]+)?)[^a-z]*(?:°f|degrees|°)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOW_PATTERN = Pattern.compile(
            "(?:low|below|under)[^0-9]*([0-9]+(?:\\.[0-9]+)?)[^a-z]*(?:°f|degrees|°)",
            Pattern.CASE_INSENSITIVE);

    public Optional<WeatherMarketSpec> parse(String marketId,
                                             String tokenId,
                                             String question,
                                             BigDecimal yesPrice,
                                             BigDecimal noPrice,
                                             BigDecimal liquidityUsdc,
                                             long endTimestampSeconds) {
        String city = detectCity(question);
        if (city == null) return Optional.empty();

        // Try MAX (high/exceed/above)
        Matcher highMatcher = HIGH_PATTERN.matcher(question);
        if (highMatcher.find()) {
            return Optional.of(WeatherMarketSpec.builder()
                    .marketId(marketId).tokenId(tokenId).question(question)
                    .city(city)
                    .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                    .thresholdF(new BigDecimal(highMatcher.group(1)))
                    .yesPrice(yesPrice).noPrice(noPrice)
                    .liquidityUsdc(liquidityUsdc)
                    .endTimestampSeconds(endTimestampSeconds)
                    .build());
        }

        // Try MIN (low/below/under)
        Matcher lowMatcher = LOW_PATTERN.matcher(question);
        if (lowMatcher.find()) {
            return Optional.of(WeatherMarketSpec.builder()
                    .marketId(marketId).tokenId(tokenId).question(question)
                    .city(city)
                    .temperatureType(WeatherMarketSpec.TemperatureType.MIN)
                    .thresholdF(new BigDecimal(lowMatcher.group(1)))
                    .yesPrice(yesPrice).noPrice(noPrice)
                    .liquidityUsdc(liquidityUsdc)
                    .endTimestampSeconds(endTimestampSeconds)
                    .build());
        }

        return Optional.empty();
    }

    private String detectCity(String question) {
        String lower = question.toLowerCase();
        for (Map.Entry<String, String> entry : CITY_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherMarketTitleParserTest -q 2>&1 | tail -5
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherMarketTitleParser.java \
        coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherMarketTitleParserTest.java
git commit -m "feat(weather): add WeatherMarketTitleParser with tests"
```

---

## Task 7: WeatherFairValueService (TDD)

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherFairValueService.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherFairValueServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WeatherFairValueServiceTest {

    private final WeatherFairValueService service = new WeatherFairValueService(new BigDecimal("3.5"));

    // forecast max = 70°F, threshold = 68°F → x = (68-70)/3.5 = -0.571
    // P(YES) = 1 - Φ(-0.571) = Φ(0.571) ≈ 0.716
    @Test
    void computeFairValue_maxType_forecastAboveThreshold_highProbability() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastTempMaxF(new BigDecimal("70"))
                .forecastTempMinF(new BigDecimal("55"))
                .forecastTempAvgF(new BigDecimal("62"))
                .fetchedAt(Instant.now())
                .build();

        BigDecimal fairValue = service.computeFairValue(spec, forecast);

        // Φ(0.571) ≈ 0.716 — should be well above 0.5
        assertTrue(fairValue.compareTo(new BigDecimal("0.60")) > 0,
                "Expected fairValue > 0.60 but got " + fairValue);
        assertTrue(fairValue.compareTo(BigDecimal.ONE) < 0,
                "fairValue must be < 1");
    }

    // forecast max = 60°F, threshold = 68°F → x = (68-60)/3.5 = 2.286
    // P(YES) = 1 - Φ(2.286) ≈ 0.011 — very low
    @Test
    void computeFairValue_maxType_forecastBelowThreshold_lowProbability() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastTempMaxF(new BigDecimal("60"))
                .forecastTempMinF(new BigDecimal("50"))
                .forecastTempAvgF(new BigDecimal("55"))
                .fetchedAt(Instant.now())
                .build();

        BigDecimal fairValue = service.computeFairValue(spec, forecast);

        assertTrue(fairValue.compareTo(new BigDecimal("0.10")) < 0,
                "Expected fairValue < 0.10 but got " + fairValue);
    }

    // forecast min = 44°F, threshold = 45°F → x = (45-44)/3.5 = 0.286
    // P(YES) = Φ(0.286) ≈ 0.613
    @Test
    void computeFairValue_minType_forecastBelowThreshold_highProbability() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .temperatureType(WeatherMarketSpec.TemperatureType.MIN)
                .thresholdF(new BigDecimal("45"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("New York")
                .forecastTempMaxF(new BigDecimal("60"))
                .forecastTempMinF(new BigDecimal("44"))
                .forecastTempAvgF(new BigDecimal("52"))
                .fetchedAt(Instant.now())
                .build();

        BigDecimal fairValue = service.computeFairValue(spec, forecast);

        assertTrue(fairValue.compareTo(new BigDecimal("0.50")) > 0,
                "Expected fairValue > 0.50 but got " + fairValue);
    }

    // Edge: forecast exactly equals threshold → P(YES) = 0.5
    @Test
    void computeFairValue_forecastEqualsThreshold_returnsFiftyPercent() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastTempMaxF(new BigDecimal("68"))
                .forecastTempMinF(new BigDecimal("55"))
                .forecastTempAvgF(new BigDecimal("61"))
                .fetchedAt(Instant.now())
                .build();

        BigDecimal fairValue = service.computeFairValue(spec, forecast);

        // Should be close to 0.5
        assertTrue(fairValue.compareTo(new BigDecimal("0.48")) > 0 &&
                   fairValue.compareTo(new BigDecimal("0.52")) < 0,
                "Expected ~0.5 but got " + fairValue);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherFairValueServiceTest -q 2>&1 | tail -10
```

Expected: compile error (class not found).

- [ ] **Step 3: Implement WeatherFairValueService**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Computes fair-value probability for a weather market using a normal distribution
 * assumption over forecast error: error ~ N(0, σ²) where σ = sigmaF (default 3.5°F).
 *
 * <p>Standard normal CDF is approximated using the Zelen & Severo (1964) polynomial
 * (accuracy ≈ 5×10⁻⁸).
 */
@Service
public class WeatherFairValueService {

    private final BigDecimal sigmaF;

    public WeatherFairValueService(BigDecimal sigmaF) {
        this.sigmaF = sigmaF;
    }

    /**
     * Compute P(YES) for the given market spec given the weather forecast.
     *
     * <ul>
     *   <li>MAX market: P(actual max > threshold) = 1 - Φ((threshold - forecastMax) / σ)
     *   <li>MIN market: P(actual min < threshold) = Φ((threshold - forecastMin) / σ)
     * </ul>
     */
    public BigDecimal computeFairValue(WeatherMarketSpec spec, WeatherForecast forecast) {
        BigDecimal forecastTemp = switch (spec.getTemperatureType()) {
            case MAX -> forecast.getForecastTempMaxF();
            case MIN -> forecast.getForecastTempMinF();
            case AVG -> forecast.getForecastTempAvgF();
        };

        // z = (threshold - forecastTemp) / sigma
        BigDecimal z = spec.getThresholdF()
                .subtract(forecastTemp)
                .divide(sigmaF, 8, RoundingMode.HALF_UP);

        return switch (spec.getTemperatureType()) {
            // P(actual > threshold) = 1 - Φ(z)
            case MAX -> BigDecimal.ONE.subtract(normalCdf(z.doubleValue()))
                    .setScale(6, RoundingMode.HALF_UP);
            // P(actual < threshold) = Φ(z)
            case MIN, AVG -> normalCdf(z.doubleValue())
                    .setScale(6, RoundingMode.HALF_UP);
        };
    }

    /**
     * Standard normal CDF using Zelen & Severo (1964) polynomial approximation.
     * Accurate to ~5×10⁻⁸ for all z.
     */
    private static BigDecimal normalCdf(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double d = 0.3989422820 * Math.exp(-z * z / 2.0);
        double poly = t * (0.3193815
                + t * (-0.3565638
                + t * (1.7814779
                + t * (-1.8212560
                + t *  1.3302744))));
        double p = d * poly;
        double cdf = (z >= 0) ? (1.0 - p) : p;
        // Clamp to [0,1] to absorb floating-point edge cases
        cdf = Math.max(0.0, Math.min(1.0, cdf));
        return BigDecimal.valueOf(cdf);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherFairValueServiceTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherFairValueService.java \
        coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherFairValueServiceTest.java
git commit -m "feat(weather): add WeatherFairValueService (normal CDF probability model) with tests"
```

---

## Task 8: TomorrowIoApiClient

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/client/TomorrowIoApiClient.java`

- [ ] **Step 1: Create TomorrowIoApiClient**

```java
package com.metaverse.coin.strategy.job.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TomorrowIoApiClient {

    private static final String BASE_URL = "https://api.tomorrow.io/v4/weather/forecast";

    private final WeatherArbitrageProperties properties;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    /**
     * Fetches next-day forecast for the given city.
     * Returns empty if the API key is missing, the call fails, or the response is malformed.
     */
    public Optional<WeatherForecast> fetchForecast(String city) {
        String apiKey = properties.getTomorrowIoApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[WeatherArbitrage] tomorrow.io API key not configured — skipping city={}", city);
            return Optional.empty();
        }

        String url = BASE_URL
                + "?location=" + city.replace(" ", "%20")
                + "&timesteps=1d"
                + "&fields=temperatureMax,temperatureMin,temperatureApparentAvg"
                + "&units=imperial"
                + "&apikey=" + apiKey;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("[WeatherArbitrage] tomorrow.io returned {} for city={}", response.code(), city);
                return Optional.empty();
            }
            return parseForecast(city, response.body().string());
        } catch (IOException e) {
            log.warn("[WeatherArbitrage] tomorrow.io call failed for city={}: {}", city, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<WeatherForecast> parseForecast(String city, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // tomorrow.io daily forecast: timelines.daily[0].values
            JsonNode daily = root.path("timelines").path("daily");
            if (!daily.isArray() || daily.isEmpty()) {
                log.warn("[WeatherArbitrage] No daily forecast data for city={}", city);
                return Optional.empty();
            }
            // Index 0 = today, index 1 = tomorrow
            JsonNode tomorrowValues = daily.size() > 1
                    ? daily.get(1).path("values")
                    : daily.get(0).path("values");

            BigDecimal maxF = asBigDecimal(tomorrowValues.path("temperatureMax"));
            BigDecimal minF = asBigDecimal(tomorrowValues.path("temperatureMin"));
            BigDecimal avgF = asBigDecimal(tomorrowValues.path("temperatureApparentAvg"));

            if (maxF == null || minF == null) {
                log.warn("[WeatherArbitrage] Missing temperature fields for city={}", city);
                return Optional.empty();
            }

            return Optional.of(WeatherForecast.builder()
                    .city(city)
                    .forecastTempMaxF(maxF)
                    .forecastTempMinF(minF)
                    .forecastTempAvgF(avgF != null ? avgF : maxF.add(minF).divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP))
                    .fetchedAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("[WeatherArbitrage] Failed to parse tomorrow.io response for city={}: {}", city, e.getMessage());
            return Optional.empty();
        }
    }

    private BigDecimal asBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn -pl coin-strategy-job compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/client/TomorrowIoApiClient.java
git commit -m "feat(weather): add TomorrowIoApiClient"
```

---

## Task 9: WeatherMarketScanService

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherMarketScanService.java`

- [ ] **Step 1: Check how PolymarketApiClient.fetchMarkets works**

Read the relevant portion of `PolymarketApiClient.java`. Look for a `fetchMarkets` or similar method that accepts query parameters. You will call it with tag filtering.

```bash
grep -n "fetchMarket\|fetchActive\|public.*List.*Market\|public.*market" \
  coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/smartwallet/service/PolymarketApiClient.java | head -15
```

- [ ] **Step 2: Create WeatherMarketScanService**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches active Polymarket markets and filters for weather-related ones
 * matching the configured cities, price range, liquidity, and settlement window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherMarketScanService {

    private static final BigDecimal YES_PRICE_MAX = new BigDecimal("0.10");
    private static final BigDecimal YES_PRICE_MIN = new BigDecimal("0.001");

    private final PolymarketApiClient polymarketApiClient;
    private final WeatherArbitrageProperties properties;
    private final WeatherMarketTitleParser titleParser;

    /**
     * Returns all parseable weather market specs within the configured
     * price, liquidity, and settlement-window constraints.
     */
    public List<WeatherMarketSpec> scanWeatherMarkets() {
        List<WeatherMarketSpec> result = new ArrayList<>();
        long nowSeconds = Instant.now().getEpochSecond();
        long minEnd = nowSeconds + (long) properties.getMinHoursToSettlement() * 3600;
        long maxEnd = nowSeconds + (long) properties.getMaxHoursToSettlement() * 3600;

        List<PolymarketApiClient.MarketSummary> markets = fetchCandidateMarkets();
        for (PolymarketApiClient.MarketSummary m : markets) {
            if (m.getEndDateTimestamp() < minEnd || m.getEndDateTimestamp() > maxEnd) continue;

            BigDecimal yesPrice = m.getOutcomePrice("Yes");
            BigDecimal noPrice  = m.getOutcomePrice("No");
            if (yesPrice == null || noPrice == null) continue;
            if (yesPrice.compareTo(YES_PRICE_MIN) < 0 || yesPrice.compareTo(YES_PRICE_MAX) > 0) continue;

            BigDecimal liquidity = m.getLiquidity();
            if (liquidity == null || liquidity.compareTo(properties.getMinLiquidityUsdc()) < 0) continue;

            Optional<WeatherMarketSpec> spec = titleParser.parse(
                    m.getConditionId(),
                    m.getYesTokenId(),
                    m.getQuestion(),
                    yesPrice, noPrice,
                    liquidity,
                    m.getEndDateTimestamp());

            spec.ifPresentOrElse(
                    result::add,
                    () -> log.debug("[WeatherArbitrage] Skipped unparseable market: {}", m.getQuestion())
            );
        }
        log.info("[WeatherArbitrage] Scan found {} parseable weather markets", result.size());
        return result;
    }

    private List<PolymarketApiClient.MarketSummary> fetchCandidateMarkets() {
        try {
            // Fetch active markets with weather tag; fall back to keyword search if needed.
            // Adjust the method name/parameters to match what PolymarketApiClient actually exposes.
            return polymarketApiClient.fetchActiveMarkets("weather");
        } catch (Exception e) {
            log.warn("[WeatherArbitrage] Failed to fetch candidate markets: {}", e.getMessage());
            return List.of();
        }
    }
}
```

> **Note:** The method `polymarketApiClient.fetchActiveMarkets("weather")` and the `MarketSummary` model are
> used here based on expected interface. After reading `PolymarketApiClient.java` in Step 1, adjust the
> method call and field accessors to match what actually exists. Add a `fetchActiveMarkets(String tag)`
> method to `PolymarketApiClient` if it doesn't exist yet, following the same OkHttp pattern as `fetchLeaderboard()`.

- [ ] **Step 3: Compile**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -20
```

Fix any compilation errors by adjusting the `PolymarketApiClient` call to match actual method signatures.

- [ ] **Step 4: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherMarketScanService.java
git commit -m "feat(weather): add WeatherMarketScanService"
```

---

## Task 10: WeatherArbitrageSignalService (TDD)

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherArbitrageSignalService.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherArbitrageSignalServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeatherArbitrageSignalServiceTest {

    private WeatherArbitrageProperties properties;
    private WeatherArbitrageSignalService service;

    @BeforeEach
    void setUp() {
        properties = new WeatherArbitrageProperties();
        properties.setEntryEdgeThreshold(new BigDecimal("0.10"));
        WeatherFairValueService fairValueService = new WeatherFairValueService(new BigDecimal("3.5"));
        service = new WeatherArbitrageSignalService(properties, fairValueService);
    }

    // fairValue ≈ 0.716, marketPrice = 0.05 → edge = (0.716 - 0.05) / 0.05 = 13.3 → BUY_YES
    @Test
    void generateSignals_highEdgeBuyYes_returnsSignal() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("mkt-1").tokenId("tok-1")
                .city("London").question("London high > 68°F?")
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))
                .liquidityUsdc(new BigDecimal("200"))
                .endTimestampSeconds(Instant.now().getEpochSecond() + 3600 * 24)
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastTempMaxF(new BigDecimal("70"))
                .forecastTempMinF(new BigDecimal("55"))
                .forecastTempAvgF(new BigDecimal("62"))
                .fetchedAt(Instant.now())
                .build();

        List<WeatherArbitrageSignal> signals = service.generateSignals(
                List.of(spec), Map.of("London", forecast));

        assertEquals(1, signals.size());
        WeatherArbitrageSignal signal = signals.get(0);
        assertEquals(WeatherArbitrageSignal.Side.BUY_YES, signal.getSide());
        assertEquals("mkt-1", signal.getMarketId());
        assertTrue(signal.getEdge().compareTo(new BigDecimal("0.10")) > 0);
    }

    // fairValue ≈ 0.011, marketPrice = 0.05 → edge = (0.011 - 0.05) / 0.05 = -0.78 < -0.10 → BUY_NO (noPrice = 0.95 < 0.10? no)
    // Actually: noPrice = 0.95 — NOT < 0.10, so no BUY_NO signal expected
    @Test
    void generateSignals_noBuyNo_whenNoPriceIsHigh() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("mkt-2").tokenId("tok-2")
                .city("London").question("London high > 80°F?")
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("80"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))  // NO price is high → not a low-price opportunity
                .liquidityUsdc(new BigDecimal("200"))
                .endTimestampSeconds(Instant.now().getEpochSecond() + 3600 * 24)
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastTempMaxF(new BigDecimal("60"))
                .forecastTempMinF(new BigDecimal("50"))
                .forecastTempAvgF(new BigDecimal("55"))
                .fetchedAt(Instant.now())
                .build();

        List<WeatherArbitrageSignal> signals = service.generateSignals(
                List.of(spec), Map.of("London", forecast));

        assertTrue(signals.isEmpty(), "Expected no signal when NO price is not in low-price range");
    }

    @Test
    void generateSignals_noForecastForCity_skipsMarket() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("mkt-3").tokenId("tok-3")
                .city("Seoul").question("Seoul high > 75°F?")
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("75"))
                .yesPrice(new BigDecimal("0.05"))
                .noPrice(new BigDecimal("0.95"))
                .liquidityUsdc(new BigDecimal("100"))
                .endTimestampSeconds(Instant.now().getEpochSecond() + 3600 * 24)
                .build();

        List<WeatherArbitrageSignal> signals = service.generateSignals(
                List.of(spec), Map.of()); // no forecasts

        assertTrue(signals.isEmpty());
    }

    // edge = (0.55 - 0.05) / 0.05 = 10.0 is > 0.10 — but let's test below threshold
    @Test
    void generateSignals_edgeBelowThreshold_noSignal() {
        // fairValue ≈ 0.053 when forecast barely above threshold
        // We'll force a small edge by setting yesPrice close to fairValue
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("mkt-4").tokenId("tok-4")
                .city("London").question("London high > 68°F?")
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .yesPrice(new BigDecimal("0.50"))  // price close to fair value
                .noPrice(new BigDecimal("0.50"))
                .liquidityUsdc(new BigDecimal("200"))
                .endTimestampSeconds(Instant.now().getEpochSecond() + 3600 * 24)
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastTempMaxF(new BigDecimal("70")) // fairValue ≈ 0.716
                .forecastTempMinF(new BigDecimal("55"))
                .forecastTempAvgF(new BigDecimal("62"))
                .fetchedAt(Instant.now())
                .build();

        // edge = (0.716 - 0.50) / 0.50 = 0.432 which is > threshold.
        // But yesPrice = 0.50 > YES_PRICE_MAX (0.10), so should be filtered out
        List<WeatherArbitrageSignal> signals = service.generateSignals(
                List.of(spec), Map.of("London", forecast));

        // yesPrice 0.50 is above the 0.10 max, so filtered
        assertTrue(signals.isEmpty(), "Expected no signal when yesPrice > 0.10");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherArbitrageSignalServiceTest -q 2>&1 | tail -10
```

Expected: compile error.

- [ ] **Step 3: Implement WeatherArbitrageSignalService**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherArbitrageSignalService {

    /** YES/NO token price must be below this to qualify as "low-priced" */
    private static final BigDecimal PRICE_MAX = new BigDecimal("0.10");
    private static final BigDecimal PRICE_MIN = new BigDecimal("0.001");

    private final WeatherArbitrageProperties properties;
    private final WeatherFairValueService fairValueService;

    /**
     * Generates trade signals by comparing fair values to current market prices.
     *
     * @param specs     parseable weather market specs from WeatherMarketScanService
     * @param forecasts city → WeatherForecast map from TomorrowIoApiClient
     */
    public List<WeatherArbitrageSignal> generateSignals(List<WeatherMarketSpec> specs,
                                                         Map<String, WeatherForecast> forecasts) {
        List<WeatherArbitrageSignal> signals = new ArrayList<>();
        for (WeatherMarketSpec spec : specs) {
            WeatherForecast forecast = forecasts.get(spec.getCity());
            if (forecast == null) {
                log.debug("[WeatherArbitrage] No forecast for city={}, skipping market={}", spec.getCity(), spec.getMarketId());
                continue;
            }

            BigDecimal fairValue = fairValueService.computeFairValue(spec, forecast);

            // Check BUY_YES: yes token is cheap, fair value says it should be higher
            if (isInLowPriceRange(spec.getYesPrice())) {
                BigDecimal edge = computeEdge(fairValue, spec.getYesPrice());
                if (edge.compareTo(properties.getEntryEdgeThreshold()) > 0) {
                    log.info("[WeatherArbitrage] BUY_YES signal: market={} city={} fairValue={} marketPrice={} edge={}",
                            spec.getMarketId(), spec.getCity(), fairValue, spec.getYesPrice(), edge);
                    signals.add(WeatherArbitrageSignal.builder()
                            .marketId(spec.getMarketId()).tokenId(spec.getTokenId())
                            .side(WeatherArbitrageSignal.Side.BUY_YES)
                            .city(spec.getCity()).question(spec.getQuestion())
                            .fairValue(fairValue).marketPrice(spec.getYesPrice())
                            .edge(edge).stakeUsdc(BigDecimal.ZERO) // set by WeatherCapitalPoolService
                            .build());
                    continue;
                }
            }

            // Check BUY_NO: no token is cheap, fair value of YES is low (so NO is underpriced)
            BigDecimal noFairValue = BigDecimal.ONE.subtract(fairValue);
            if (isInLowPriceRange(spec.getNoPrice())) {
                BigDecimal edge = computeEdge(noFairValue, spec.getNoPrice());
                if (edge.compareTo(properties.getEntryEdgeThreshold()) > 0) {
                    log.info("[WeatherArbitrage] BUY_NO signal: market={} city={} noFairValue={} noMarketPrice={} edge={}",
                            spec.getMarketId(), spec.getCity(), noFairValue, spec.getNoPrice(), edge);
                    signals.add(WeatherArbitrageSignal.builder()
                            .marketId(spec.getMarketId()).tokenId(spec.getTokenId())
                            .side(WeatherArbitrageSignal.Side.BUY_NO)
                            .city(spec.getCity()).question(spec.getQuestion())
                            .fairValue(noFairValue).marketPrice(spec.getNoPrice())
                            .edge(edge).stakeUsdc(BigDecimal.ZERO)
                            .build());
                }
            }
        }
        return signals;
    }

    /** edge = (fairValue - marketPrice) / marketPrice */
    private BigDecimal computeEdge(BigDecimal fairValue, BigDecimal marketPrice) {
        if (marketPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return fairValue.subtract(marketPrice)
                .divide(marketPrice, 6, RoundingMode.HALF_UP);
    }

    private boolean isInLowPriceRange(BigDecimal price) {
        return price != null
                && price.compareTo(PRICE_MIN) >= 0
                && price.compareTo(PRICE_MAX) <= 0;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherArbitrageSignalServiceTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherArbitrageSignalService.java \
        coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherArbitrageSignalServiceTest.java
git commit -m "feat(weather): add WeatherArbitrageSignalService with tests"
```

---

## Task 11: WeatherCapitalPoolService (TDD)

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherCapitalPoolService.java`
- Create: `coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherCapitalPoolServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeatherCapitalPoolServiceTest {

    private WeatherArbitrageProperties properties;
    private SmartWalletPositionMapper positionMapper;
    private WeatherCapitalPoolService service;

    @BeforeEach
    void setUp() {
        properties = new WeatherArbitrageProperties();
        properties.setCapitalPoolUsdc(new BigDecimal("500"));
        properties.setMaxSingleStakeUsdc(new BigDecimal("50"));
        properties.setMaxOpenPositions(5);
        positionMapper = mock(SmartWalletPositionMapper.class);
        // Simulate 0 allocated USDC
        when(positionMapper.selectList(any())).thenReturn(java.util.List.of());
        service = new WeatherCapitalPoolService(properties, positionMapper);
    }

    // edge=0.15, pool=500, kelly fraction=0.25 → stake = min(0.15*500*0.25, 50) = min(18.75, 50) = 18.75
    @Test
    void computeStake_normalEdge_returnsKellyStake() {
        WeatherArbitrageSignal signal = WeatherArbitrageSignal.builder()
                .edge(new BigDecimal("0.15"))
                .stakeUsdc(BigDecimal.ZERO)
                .build();

        BigDecimal stake = service.computeStake(signal, new BigDecimal("0"));

        assertEquals(new BigDecimal("18.75"), stake);
    }

    // edge=2.0, pool=500, kelly fraction=0.25 → stake = min(2.0*500*0.25, 50) = min(250, 50) = 50.00
    @Test
    void computeStake_highEdge_cappedAtMaxSingleStake() {
        WeatherArbitrageSignal signal = WeatherArbitrageSignal.builder()
                .edge(new BigDecimal("2.0"))
                .stakeUsdc(BigDecimal.ZERO)
                .build();

        BigDecimal stake = service.computeStake(signal, new BigDecimal("0"));

        assertEquals(new BigDecimal("50.00"), stake.setScale(2));
    }

    // allocatedUsdc = 490, pool = 500 → only 10 USDC left → stake capped at 10
    @Test
    void computeStake_nearPoolLimit_stakeCappedByAvailableBudget() {
        WeatherArbitrageSignal signal = WeatherArbitrageSignal.builder()
                .edge(new BigDecimal("2.0"))
                .stakeUsdc(BigDecimal.ZERO)
                .build();

        BigDecimal stake = service.computeStake(signal, new BigDecimal("490"));

        assertEquals(new BigDecimal("10.00"), stake.setScale(2));
    }

    // allocatedUsdc = 500, pool = 500 → no budget → stake = 0
    @Test
    void computeStake_poolExhausted_returnsZero() {
        WeatherArbitrageSignal signal = WeatherArbitrageSignal.builder()
                .edge(new BigDecimal("0.5"))
                .stakeUsdc(BigDecimal.ZERO)
                .build();

        BigDecimal stake = service.computeStake(signal, new BigDecimal("500"));

        assertEquals(BigDecimal.ZERO, stake);
    }
}
```

- [ ] **Step 2: Add Mockito to test dependencies (if not already present)**

```bash
grep -n "mockito" coin-strategy-job/pom.xml | head -5
```

If not present, add to `coin-strategy-job/pom.xml` in `<dependencies>`:
```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherCapitalPoolServiceTest -q 2>&1 | tail -10
```

Expected: compile error.

- [ ] **Step 4: Implement WeatherCapitalPoolService**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherCapitalPoolService {

    private static final BigDecimal KELLY_FRACTION = new BigDecimal("0.25");

    private final WeatherArbitrageProperties properties;
    private final SmartWalletPositionMapper positionMapper;

    /**
     * Returns the allocated USDC (sum of costBasisUsdc for all OPEN weather positions).
     */
    public BigDecimal getAllocatedUsdc() {
        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getSource, "WEATHER")
                        .eq(SmartWalletPositionEntity::getStatus, "OPEN"));
        return openPositions.stream()
                .map(p -> p.getCostBasisUsdc() != null ? p.getCostBasisUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Computes stake for a signal given the current allocated amount.
     * stake = min(edge × capitalPool × kellyFraction, maxSingleStake, availableBudget)
     * Returns ZERO if the pool is exhausted.
     *
     * @param signal       the arbitrage signal (edge must be set)
     * @param allocatedUsdc current total allocated (pass result of getAllocatedUsdc())
     */
    public BigDecimal computeStake(WeatherArbitrageSignal signal, BigDecimal allocatedUsdc) {
        BigDecimal available = properties.getCapitalPoolUsdc().subtract(allocatedUsdc);
        if (available.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal kellyStake = signal.getEdge()
                .multiply(properties.getCapitalPoolUsdc())
                .multiply(KELLY_FRACTION)
                .setScale(2, RoundingMode.HALF_UP);

        return kellyStake
                .min(properties.getMaxSingleStakeUsdc())
                .min(available)
                .max(BigDecimal.ZERO);
    }

    /**
     * Convenience: returns true if opening a new position is allowed
     * (pool not exhausted, open position count below limit).
     */
    public boolean canOpenNewPosition(BigDecimal allocatedUsdc) {
        if (allocatedUsdc.compareTo(properties.getCapitalPoolUsdc()) >= 0) return false;
        long openCount = positionMapper.selectCount(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getSource, "WEATHER")
                        .eq(SmartWalletPositionEntity::getStatus, "OPEN"));
        return openCount < properties.getMaxOpenPositions();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn -pl coin-strategy-job test -Dtest=WeatherCapitalPoolServiceTest -q 2>&1 | tail -5
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherCapitalPoolService.java \
        coin-strategy-job/src/test/java/com/metaverse/coin/strategy/job/weather/service/WeatherCapitalPoolServiceTest.java
git commit -m "feat(weather): add WeatherCapitalPoolService with tests"
```

---

## Task 12: WeatherPositionSyncService

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherPositionSyncService.java`

- [ ] **Step 1: Create WeatherPositionSyncService**

```java
package com.metaverse.coin.strategy.job.weather.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.service.ExitSignalProvider;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * Monitors open WEATHER positions and triggers stop-loss exits
 * using the existing ExitSignalProvider (TimeAwareStopLossService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherPositionSyncService {

    private final WeatherArbitrageProperties properties;
    private final SmartWalletPositionMapper positionMapper;
    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketClobExecutionService executionService;
    private final ExitSignalProvider exitSignalProvider;

    @Scheduled(fixedDelayString = "${weather-arbitrage.sync-fixed-delay-ms:300000}")
    public void syncPositions() {
        if (!properties.isEnabled()) return;

        List<SmartWalletPositionEntity> openPositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getSource, "WEATHER")
                        .eq(SmartWalletPositionEntity::getStatus, "OPEN"));

        if (openPositions.isEmpty()) return;
        log.debug("[WeatherArbitrage] Syncing {} open weather positions", openPositions.size());

        for (SmartWalletPositionEntity position : openPositions) {
            try {
                syncPosition(position);
            } catch (Exception e) {
                log.warn("[WeatherArbitrage] Failed to sync position id={}: {}", position.getId(), e.getMessage());
            }
        }
    }

    private void syncPosition(SmartWalletPositionEntity position) {
        if (position.getMarketInfo() == null) {
            // Load market info if not hydrated
            log.debug("[WeatherArbitrage] Position {} has no marketInfo, skipping", position.getId());
            return;
        }

        BigDecimal markPrice = fetchMarkPrice(position);
        if (markPrice == null) return;

        BigDecimal avgEntry = position.getAverageEntryPrice();
        if (avgEntry == null || avgEntry.compareTo(BigDecimal.ZERO) == 0) return;

        // Record price history for trend analysis
        exitSignalProvider.recordMarkPrice(position.getId(), markPrice, avgEntry);

        // Update position with latest mark price and unrealized PnL
        BigDecimal unrealizedPnl = markPrice.subtract(avgEntry)
                .multiply(position.getQuantity())
                .setScale(8, RoundingMode.HALF_UP);
        positionMapper.updateById(position
                .setLastMarkPrice(markPrice)
                .setUnrealizedPnlUsdc(unrealizedPnl));

        // Check stop-loss conditions
        BigDecimal threshold = exitSignalProvider.resolveStopLossThreshold(position);
        BigDecimal roi = markPrice.subtract(avgEntry).divide(avgEntry, 8, RoundingMode.HALF_UP);
        boolean stopLossHit = roi.compareTo(threshold.negate()) <= 0;
        boolean trendStopHit = exitSignalProvider.isTrendStopLossTriggered(
                position.getId(), markPrice, avgEntry);

        if (stopLossHit || trendStopHit) {
            String reason = stopLossHit ? "WEATHER_STOP_LOSS" : "WEATHER_TREND_STOP";
            log.info("[WeatherArbitrage] Exit triggered for position={} reason={} roi={}", position.getId(), reason, roi);
            executionService.executeLiveExit(position, markPrice, reason);
            exitSignalProvider.clearPositionHistory(position.getId());
        }
    }

    private BigDecimal fetchMarkPrice(SmartWalletPositionEntity position) {
        try {
            String tokenId = position.getTokenId();
            if (tokenId == null) return null;
            return polymarketApiClient.fetchMidPrice(tokenId);
        } catch (Exception e) {
            log.debug("[WeatherArbitrage] Could not fetch mark price for position={}: {}",
                    position.getId(), e.getMessage());
            return null;
        }
    }
}
```

> **Note:** `polymarketApiClient.fetchMidPrice(tokenId)` — find the actual method in `PolymarketApiClient`
> that returns current mid-price for a token. Look for `fetchMidPrice`, `getMidPrice`, or `fetchOrderBook`
> and extract the mid. Adjust the call accordingly.

- [ ] **Step 2: Compile**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -20
```

Fix any missing methods in `PolymarketApiClient` as needed.

- [ ] **Step 3: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/service/WeatherPositionSyncService.java
git commit -m "feat(weather): add WeatherPositionSyncService (stop-loss monitor)"
```

---

## Task 13: WeatherArbitrageJob (Main Orchestrator)

**Files:**
- Create: `coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/WeatherArbitrageJob.java`

- [ ] **Step 1: Create WeatherArbitrageJob**

```java
package com.metaverse.coin.strategy.job.weather;

import com.metaverse.coin.strategy.common.dao.mapper.WeatherArbitrageSignalLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherArbitrageSignalLogEntity;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.weather.client.TomorrowIoApiClient;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import com.metaverse.coin.strategy.job.weather.service.WeatherArbitrageSignalService;
import com.metaverse.coin.strategy.job.weather.service.WeatherCapitalPoolService;
import com.metaverse.coin.strategy.job.weather.service.WeatherMarketScanService;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main scheduler for the weather arbitrage strategy.
 * Runs every 15 minutes (configurable). Fully autonomous — no human approval needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherArbitrageJob {

    private final WeatherArbitrageProperties properties;
    private final WeatherMarketScanService marketScanService;
    private final TomorrowIoApiClient tomorrowIoApiClient;
    private final WeatherArbitrageSignalService signalService;
    private final WeatherCapitalPoolService capitalPoolService;
    private final PolymarketClobExecutionService executionService;
    private final SmartWalletPositionMapper positionMapper;
    private final WeatherArbitrageSignalLogMapper signalLogMapper;

    @Scheduled(fixedDelayString = "${weather-arbitrage.scan-fixed-delay-ms:900000}")
    public void run() {
        if (!properties.isEnabled()) {
            log.debug("[WeatherArbitrage] disabled — skipping scan");
            return;
        }
        log.info("[WeatherArbitrage] === Scan cycle start ===");

        // Step 1: Scan Polymarket for weather markets
        List<WeatherMarketSpec> specs = marketScanService.scanWeatherMarkets();
        if (specs.isEmpty()) {
            log.info("[WeatherArbitrage] No matching weather markets found");
            return;
        }

        // Step 2: Fetch forecasts for all needed cities
        Map<String, WeatherForecast> forecasts = fetchForecasts(specs);

        // Step 3: Generate signals
        List<WeatherArbitrageSignal> signals = signalService.generateSignals(specs, forecasts);

        // Step 4: Log all scanned specs (including PASS)
        logScans(specs, forecasts, signals);

        if (signals.isEmpty()) {
            log.info("[WeatherArbitrage] No signals above edge threshold");
            return;
        }

        // Step 5: Execute signals within capital pool limits
        BigDecimal allocated = capitalPoolService.getAllocatedUsdc();
        for (WeatherArbitrageSignal signal : signals) {
            if (!capitalPoolService.canOpenNewPosition(allocated)) {
                log.info("[WeatherArbitrage] Position limit reached, stopping execution");
                break;
            }
            BigDecimal stake = capitalPoolService.computeStake(signal, allocated);
            if (stake.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("[WeatherArbitrage] Insufficient capital for signal market={}", signal.getMarketId());
                continue;
            }
            signal.setStakeUsdc(stake);
            executeSignal(signal);
            allocated = allocated.add(stake);
        }

        log.info("[WeatherArbitrage] === Scan cycle end ===");
    }

    private Map<String, WeatherForecast> fetchForecasts(List<WeatherMarketSpec> specs) {
        Map<String, WeatherForecast> forecasts = new HashMap<>();
        specs.stream()
                .map(WeatherMarketSpec::getCity)
                .distinct()
                .forEach(city -> {
                    Optional<WeatherForecast> forecast = tomorrowIoApiClient.fetchForecast(city);
                    forecast.ifPresent(f -> forecasts.put(city, f));
                });
        return forecasts;
    }

    private void executeSignal(WeatherArbitrageSignal signal) {
        try {
            log.info("[WeatherArbitrage] Executing {} market={} stake={} edge={}",
                    signal.getSide(), signal.getMarketId(), signal.getStakeUsdc(), signal.getEdge());
            // Build a minimal position entity to pass to executeLiveExit if needed,
            // or use the execution service's entry method.
            // The exact call depends on how PolymarketClobExecutionService handles
            // new entries vs exits. Adapt as needed:
            //   executionService.executeLiveEntry(signal.getMarketId(), signal.getTokenId(),
            //       signal.getStakeUsdc(), signal.getMarketPrice(), "WEATHER");
            // Then write the resulting position to the DB with source=WEATHER.
            //
            // NOTE: Check PolymarketClobExecutionService for an entry method.
            // If only exit exists, add an executeEntry method following the same pattern
            // as executeLiveExit, using PolymarketOrderRequestFactory.prepareEntry().
        } catch (Exception e) {
            log.error("[WeatherArbitrage] Failed to execute signal market={}: {}", signal.getMarketId(), e.getMessage());
        }
    }

    private void logScans(List<WeatherMarketSpec> specs,
                          Map<String, WeatherForecast> forecasts,
                          List<WeatherArbitrageSignal> signals) {
        Date now = new Date();
        Map<String, WeatherArbitrageSignal> signalByMarket = new HashMap<>();
        for (WeatherArbitrageSignal s : signals) signalByMarket.put(s.getMarketId(), s);

        for (WeatherMarketSpec spec : specs) {
            WeatherForecast forecast = forecasts.get(spec.getCity());
            WeatherArbitrageSignal signal = signalByMarket.get(spec.getMarketId());

            WeatherArbitrageSignalLogEntity log = new WeatherArbitrageSignalLogEntity()
                    .setMarketId(spec.getMarketId())
                    .setCity(spec.getCity())
                    .setMarketPrice(spec.getYesPrice())
                    .setScannedAt(now);

            if (forecast != null && signal != null) {
                log.setFairValue(signal.getFairValue())
                   .setEdge(signal.getEdge())
                   .setAction(signal.getSide().name())
                   .setReason("edge=" + signal.getEdge());
            } else if (forecast == null) {
                log.setFairValue(BigDecimal.ZERO).setEdge(BigDecimal.ZERO)
                   .setAction("PASS").setReason("no_forecast");
            } else {
                log.setFairValue(BigDecimal.ZERO).setEdge(BigDecimal.ZERO)
                   .setAction("PASS").setReason("edge_below_threshold");
            }
            try {
                signalLogMapper.insert(log);
            } catch (Exception e) {
                WeatherArbitrageJob.log.warn("[WeatherArbitrage] Failed to write signal log: {}", e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn -pl coin-strategy-job compile -q 2>&1 | tail -20
```

The `executeSignal` method has a `// NOTE` comment — after compiling, check `PolymarketClobExecutionService` for an entry method and wire it up. If none exists, add `executeWeatherEntry(String marketId, String tokenId, BigDecimal stakeUsdc, BigDecimal price)` following the same pattern as `executeLiveExit`.

- [ ] **Step 3: Commit**

```bash
git add coin-strategy-job/src/main/java/com/metaverse/coin/strategy/job/weather/WeatherArbitrageJob.java
git commit -m "feat(weather): add WeatherArbitrageJob (main 15-min scan orchestrator)"
```

---

## Task 14: End-to-End Verification

- [ ] **Step 1: Run all weather tests**

```bash
mvn -pl coin-strategy-job test -Dtest="com.metaverse.coin.strategy.job.weather.*" -q 2>&1 | tail -15
```

Expected: all tests pass, no failures.

- [ ] **Step 2: Run full module build**

```bash
mvn -pl coin-strategy-job test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, no regressions in existing tests.

- [ ] **Step 3: Verify `source` field doesn't break existing tests**

Existing tests that construct `SmartWalletPositionEntity` should still pass because `source` defaults to null (the column has a default in SQL). If any test fails due to the new field, add `.setSource("FOLLOW_TRADE")` where needed.

- [ ] **Step 4: Smoke-test with enabled=false**

Start the application and confirm the `WeatherArbitrageJob` log line `"disabled — skipping scan"` appears every 15 minutes without errors.

```bash
# In application.yml, weather-arbitrage.enabled is false by default — no API keys needed.
mvn -pl coin-strategy-job spring-boot:run -q &
sleep 10
grep -i "WeatherArbitrage" logs/*.log | head -5
```

Expected: `[WeatherArbitrage] disabled — skipping scan`

- [ ] **Step 5: Final commit**

```bash
git add -A
git status  # verify only expected files changed
git commit -m "feat(weather): complete weather arbitrage module — all tests green"
```

---

## Self-Review: Spec Coverage

| Spec section | Covered by task |
|-------------|----------------|
| Market scan (0.1¢–10¢, ≥$50 liq, 12–72h window) | Task 9 (WeatherMarketScanService), Task 6 (TitleParser) |
| tomorrow.io forecast fetch | Task 8 (TomorrowIoApiClient) |
| Normal distribution probability model | Task 7 (WeatherFairValueService) |
| Signal generation (edge > 10%) | Task 10 (WeatherArbitrageSignalService) |
| Independent capital pool | Task 4 (config), Task 11 (WeatherCapitalPoolService) |
| Autonomous execution (BUY_YES / BUY_NO) | Task 13 (WeatherArbitrageJob) |
| Dynamic stop-loss via ExitSignalProvider | Task 12 (WeatherPositionSyncService) |
| source=WEATHER isolation | Task 2 (entity field) |
| Signal log table | Task 1 (SQL), Task 3 (entity/mapper), Task 13 (logging) |
| Algorithm evolution path (σ config) | Task 4 (sigmaF in properties), Task 7 (constructor param) |
| Risk guards (pool limit, position count) | Task 11 (WeatherCapitalPoolService.canOpenNewPosition) |
