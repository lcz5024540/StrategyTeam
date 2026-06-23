---
title: "天气温度预测策略 V2 — 完整技术方案"
type: design
status: active
tags: [polymarket, strategy, design]
created: 2026-05-12
updated: 2026-05-12
---

# 天气温度预测策略 V2 — 完整技术方案

## 一、现状分析与瓶颈诊断

### 1.1 现有架构

```text
WeatherArbitrageJob (15min @Scheduled)
  → WeatherMarketScanService.scanMarkets()        // 从 Gamma API 拉活跃市场
  → WeatherMarketTitleParser.parse()               // 正则解析 question → city/threshold/type
  → TomorrowIoWeatherForecastProvider.fetchForecast() // 单一数据源
  → WeatherModelCalibrationService.findProfile()   // 按 city+month+leadHourBucket 查 sigma
  → WeatherFairValueService.computeFairValue()     // 正态分布 CDF 计算 fair value
  → WeatherArbitrageSignalService.evaluate()       // edge/EV 阈值过滤
  → WeatherCapitalPoolService.calculateStake()     // edge * 25% * pool
  → WeatherPaperTradeService.openPaperPosition()   // 模拟开仓
```

### 1.2 六大瓶颈

| # | 瓶颈 | 现状 | 影响 |
|---|------|------|------|
| B1 | **单一数据源** | 仅 Tomorrow.io 确定性预报，`dataSourceCount=1` | 无法交叉验证，sigma 估计偏差大，fairValue 不准 |
| B2 | **高斯假设过于简单** | `normalCdf(z)` 单点正态，sigma 固定 fallback=3.5°F | 极端温度概率严重低估，尾部定价失真 |
| B3 | **扫描频率太低** | 15 min 一轮，`minHoursToSettlement=12` | 临近到期（<12h）的高确定性市场完全错过 |
| B4 | **城市覆盖不足** | 仅 London / NYC / Seoul 3 城 | Polymarket 有 10+ 城市的温度市场，大量机会遗漏 |
| B5 | **仓位公式粗糙** | `edge * 25% * pool`，无 Kelly | 高置信度信号仓位不够大，低置信度仓位没有足够缩减 |
| B6 | **无实时观测层** | 仅用预报数据 | <6h 市场可以用实际观测数据获得接近确定性的 fair value |

---

## 二、数据层升级 — 多源集成预报

### 2.1 架构：数据源聚合器

```text
WeatherForecastAggregator (新增)
  ├── OpenMeteoEnsembleForecastProvider (新增，主力)
  │     └── 7 个集成模型 × N 成员 → 原始成员温度分布
  ├── TomorrowIoWeatherForecastProvider (已有，辅助)
  │     └── 确定性预报 + 置信区间
  └── OpenMeteoObservationProvider (新增，实时观测)
        └── 当前温度 + 当日已达到的极值
```

### 2.2 Open-Meteo Ensemble API（主力数据源）

**选择理由**：免费（非商业 <10K 调用/天），提供 7 个全球数值天气模型的**原始集成成员数据**，而非单点预报。

```text
GET https://ensemble-api.open-meteo.com/v1/ensemble
  ?latitude=40.7128&longitude=-74.006
  &daily=temperature_2m_max,temperature_2m_min
  &models=gfs_seamless,ecmwf_ifs025,icon_seamless,gem_global,bom_access_global_ensemble
  &forecast_days=7
  &temperature_unit=fahrenheit
```

**返回**：每个模型的每个集成成员（GFS 31 个，ECMWF 51 个，ICON 40 个等）对每天最高/最低温度的独立预测值。

**核心变更**：

```java
// 新增 model: WeatherEnsembleForecast
@Data @Builder
public class WeatherEnsembleForecast {
    private String city;
    private LocalDate targetDate;
    private List<BigDecimal> maxTempMembersF;  // 所有集成成员的 max temp (°F)
    private List<BigDecimal> minTempMembersF;  // 所有集成成员的 min temp (°F)
    private int totalMembers;                   // 成员总数（如 GFS31 + ECMWF51 = 82）
    private int modelCount;                     // 数据源模型数量
    private Instant fetchedAt;
    private int leadHours;
    private Map<String, Integer> memberCountByModel; // {"GFS":31, "ECMWF":51, ...}
}
```

```java
// 新增 provider: OpenMeteoEnsembleForecastProvider
@Service
public class OpenMeteoEnsembleForecastProvider {

    // 城市 → 经纬度映射（配置化）
    // Open-Meteo 不需要 API key，直接调用
    // 每 15 分钟缓存一次（同一天同一城市不需要重复拉取）

    public Optional<WeatherEnsembleForecast> fetchEnsemble(String city, LocalDate targetDate) {
        CityCoordinate coord = cityCoordinates.get(city);
        // 拼接 URL，请求 GFS + ECMWF + ICON + GEM + BOM 五模型
        // 解析返回的 JSON，提取每个成员的 daily max/min
        // 合并所有模型成员到一个 List<BigDecimal>
    }
}
```

### 2.3 实时观测层（临近到期关键）

对于 `leadHours < 6` 的市场，预报已不是最重要的——**当天实际观测到的气温**才是。

```text
GET https://api.open-meteo.com/v1/forecast
  ?latitude=40.7128&longitude=-74.006
  &hourly=temperature_2m
  &current=temperature_2m
  &temperature_unit=fahrenheit
  &past_hours=24
  &forecast_hours=12
```

```java
// 新增 model
@Data @Builder
public class WeatherRealtimeObservation {
    private String city;
    private BigDecimal currentTempF;
    private BigDecimal todayObservedMaxF;   // 今天到目前为止观测到的最高温
    private BigDecimal todayObservedMinF;   // 今天到目前为止观测到的最低温
    private List<BigDecimal> remainingHourForecastsF; // 今天剩余小时的逐时预报
    private Instant observedAt;
}
```

### 2.4 数据源聚合器

```java
@Service
public class WeatherForecastAggregator {

    private final OpenMeteoEnsembleForecastProvider ensembleProvider;
    private final TomorrowIoWeatherForecastProvider tomorrowIoProvider;
    private final OpenMeteoObservationProvider observationProvider;

    /**
     * 统一入口：根据 leadHours 自动选择最优数据组合
     */
    public WeatherAggregatedData aggregate(String city, LocalDate targetDate) {
        int leadHours = computeLeadHours(city, targetDate);
        WeatherAggregatedData result = new WeatherAggregatedData();
        result.setLeadHours(leadHours);

        // 1. 始终拉取集成预报
        Optional<WeatherEnsembleForecast> ensemble = ensembleProvider.fetchEnsemble(city, targetDate);
        ensemble.ifPresent(result::setEnsemble);

        // 2. 补充 Tomorrow.io 确定性预报
        Optional<WeatherForecast> tomorrowIo = tomorrowIoProvider.fetchForecast(city, targetDate);
        tomorrowIo.ifPresent(result::setDeterministic);

        // 3. 如果 leadHours < 12，拉取实时观测
        if (leadHours < 12) {
            Optional<WeatherRealtimeObservation> obs = observationProvider.fetchCurrent(city);
            obs.ifPresent(result::setObservation);
        }

        result.setDataSourceCount(countSources(result));
        return result;
    }
}
```

---

## 三、Fair Value 模型升级 — 从高斯到集成分布

### 3.1 核心思路

**现状**：`P(maxTemp > threshold) = 1 - Φ((threshold - forecastMax) / sigma)`，一个正态分布假设。

**升级**：直接用集成成员的经验分布计算概率，无需正态假设。

```text
如果 82 个集成成员中，有 65 个预测 maxTemp > 80°F
→ P(maxTemp > 80°F) = 65/82 = 0.793
→ 如果市场 YES 价格 = 0.65，edge = (0.793 - 0.65) / 0.65 = 22%
```

### 3.2 三层概率计算模型

```java
@Service
public class WeatherFairValueServiceV2 {

    /**
     * 根据数据完整度，分三层递进计算 fair value
     */
    public WeatherFairValueResult computeFairValue(WeatherMarketSpec spec,
                                                    WeatherAggregatedData data,
                                                    Optional<WeatherCalibrationProfileEntity> calibration) {

        int leadHours = data.getLeadHours();

        // ── Layer 1: 实时观测层 (leadHours < 6) ──
        // 适用场景：市场今天到期，当天已有实际观测数据
        if (leadHours < 6 && data.getObservation() != null) {
            return computeFromObservation(spec, data);
        }

        // ── Layer 2: 集成分布层 (有集成数据时) ──
        // 适用场景：有 ≥30 个集成成员，直接用经验分布
        if (data.getEnsemble() != null && data.getEnsemble().getTotalMembers() >= 30) {
            return computeFromEnsemble(spec, data, calibration);
        }

        // ── Layer 3: 参数化高斯层 (降级方案) ──
        // 适用场景：集成数据不可用，回退到现有的 sigma 模型
        if (data.getDeterministic() != null) {
            return computeFromGaussian(spec, data.getDeterministic(), calibration);
        }

        return pass("no_data", data.getLeadHours());
    }
}
```

### 3.3 Layer 1：实时观测计算（<6h，核心利润来源）

这是**快进快出、高确定性**信号的核心。当市场问 "NYC 今天最高温是否超过 80°F"，而当前已是下午 2 点、已观测到 82°F 时，这几乎是确定性的 YES。

```java
private WeatherFairValueResult computeFromObservation(WeatherMarketSpec spec,
                                                       WeatherAggregatedData data) {
    WeatherRealtimeObservation obs = data.getObservation();
    BigDecimal threshold = spec.getThresholdF();

    if (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX) {
        // 今天已经观测到的最高温
        BigDecimal observedMax = obs.getTodayObservedMaxF();

        if (observedMax.compareTo(threshold) > 0) {
            // 已经超过阈值 → 几乎确定 YES
            // fair value = 0.97~0.99（留少量余地给数据修正）
            return buildResult(new BigDecimal("0.98"), new BigDecimal("0.99"),
                    data.getLeadHours(), "observation_exceeded");
        }

        // 还没超过，但还有剩余小时
        // 用剩余小时的逐时预报计算：还有多少概率能突破阈值
        BigDecimal gap = threshold.subtract(observedMax); // 还差多少度
        List<BigDecimal> remaining = obs.getRemainingHourForecastsF();
        BigDecimal projectedMax = remaining.stream()
                .reduce(observedMax, BigDecimal::max);

        if (projectedMax.compareTo(threshold) > 0) {
            // 逐时预报显示今天还能突破
            BigDecimal margin = projectedMax.subtract(threshold);
            BigDecimal prob = computeRemainingHoursProbability(gap, remaining, data.getEnsemble());
            return buildResult(prob, new BigDecimal("0.95"), data.getLeadHours(), "observation_projected");
        } else {
            // 逐时预报显示今天大概率突破不了
            BigDecimal prob = computeRemainingHoursProbability(gap, remaining, data.getEnsemble());
            return buildResult(prob, new BigDecimal("0.90"), data.getLeadHours(), "observation_unlikely");
        }
    } else {
        // MIN 温度：市场问"今天最低温是否低于阈值"，最低温通常在凌晨触底
        BigDecimal observedMin = obs.getTodayObservedMinF();

        if (observedMin.compareTo(threshold) < 0) {
            // 已经低于阈值 → 几乎确定 YES
            return buildResult(new BigDecimal("0.98"), new BigDecimal("0.99"),
                    data.getLeadHours(), "observation_exceeded");
        }

        // 还没触底，但还有剩余可能更冷的小时
        BigDecimal gap = observedMin.subtract(threshold); // 还需再降多少度
        List<BigDecimal> remaining = obs.getRemainingHourForecastsF();
        BigDecimal projectedMin = remaining.stream()
                .reduce(observedMin, BigDecimal::min);

        if (projectedMin.compareTo(threshold) < 0) {
            // 逐时预报显示今天还能跌破阈值
            BigDecimal prob = computeRemainingHoursProbability(gap, remaining, data.getEnsemble());
            return buildResult(prob, new BigDecimal("0.95"), data.getLeadHours(), "observation_projected");
        } else {
            // 逐时预报显示今天大概率跌不破
            BigDecimal prob = computeRemainingHoursProbability(gap, remaining, data.getEnsemble());
            return buildResult(prob, new BigDecimal("0.90"), data.getLeadHours(), "observation_unlikely");
        }
    }
}
```

> **实现说明**：`computeFromObservation` 的 MIN（最低温）分支已参照 MAX 分支对称补全 —— 把「已超过阈值」换成「已低于阈值」、`max` 归约换成 `min`、比较方向由 `>` 翻转为 `<`，`gap` 改为「还需再降多少度」，并复用同一 `computeRemainingHoursProbability` 计算剩余小时触底概率，返回 `observation_exceeded / observation_projected / observation_unlikely` 三态与 MAX 对齐。

### 3.4 Layer 2：集成经验分布

```java
private WeatherFairValueResult computeFromEnsemble(WeatherMarketSpec spec,
                                                    WeatherAggregatedData data,
                                                    Optional<WeatherCalibrationProfileEntity> calibration) {
    WeatherEnsembleForecast ensemble = data.getEnsemble();
    BigDecimal threshold = spec.getThresholdF();

    List<BigDecimal> members = (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX)
            ? ensemble.getMaxTempMembersF()
            : ensemble.getMinTempMembersF();

    // Step 1: 应用校准偏移（如果有历史校准数据）
    BigDecimal biasCorrection = BigDecimal.ZERO;
    if (calibration.isPresent() && calibration.get().getSampleSize() >= 20) {
        biasCorrection = calibration.get().getMeanBiasF(); // 新增字段：历史平均偏差
    }
    List<BigDecimal> corrected = members.stream()
            .map(m -> m.add(biasCorrection))
            .toList();

    // Step 2: 计算经验概率
    long exceedCount = corrected.stream()
            .filter(m -> m.compareTo(threshold) > 0)
            .count();
    BigDecimal rawProbability = BigDecimal.valueOf(exceedCount)
            .divide(BigDecimal.valueOf(corrected.size()), 8, RoundingMode.HALF_UP);

    // Step 3: 应用 Laplace 平滑（避免 0% 或 100%）
    // P_smoothed = (exceedCount + 1) / (total + 2)
    BigDecimal smoothedProbability = BigDecimal.valueOf(exceedCount + 1)
            .divide(BigDecimal.valueOf(corrected.size() + 2), 8, RoundingMode.HALF_UP);

    // Step 4: 置信度 = f(成员数, 模型数, leadHours)
    BigDecimal confidence = computeEnsembleConfidence(ensemble, data.getLeadHours());

    return WeatherFairValueResult.builder()
            .actionable(true)
            .fairValue(smoothedProbability)
            .confidence(confidence)
            .leadHours(data.getLeadHours())
            .reason("ensemble_empirical")
            .explain(String.format("members=%d, exceedCount=%d, raw=%.4f, smoothed=%.4f, bias=%.2f",
                    corrected.size(), exceedCount, rawProbability, smoothedProbability, biasCorrection))
            .build();
}

/**
 * 集成置信度公式：
 *   base = min(totalMembers, 100) / 120   (成员越多越好，82 成员 → 0.68)
 *   modelBonus = min(modelCount, 5) * 0.04 (多模型交叉验证，5 模型 → +0.20)
 *   leadPenalty = leadHours > 48 ? -0.10 : leadHours > 72 ? -0.20 : 0
 *   confidence = clamp(base + modelBonus + leadPenalty, 0.30, 0.98)
 */
private BigDecimal computeEnsembleConfidence(WeatherEnsembleForecast ensemble, int leadHours) {
    double base = Math.min(ensemble.getTotalMembers(), 100) / 120.0;
    double modelBonus = Math.min(ensemble.getModelCount(), 5) * 0.04;
    double leadPenalty = leadHours > 72 ? -0.20 : leadHours > 48 ? -0.10 : 0;
    double confidence = Math.max(0.30, Math.min(0.98, base + modelBonus + leadPenalty));
    return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
}
```

---

## 四、信号层升级 — 自适应 Lead-Hour 策略

### 4.1 核心理念：按时间窗口分层策略

| 时间窗口 | leadHours | 扫描频率 | 数据源 | 最低 edge | 置信度 floor | 预期命中率 |
|----------|-----------|---------|--------|----------|------------|-----------|
| **超近期** | 0-6h | 2 min | 观测 + 集成 | 3% | 0.85 | 90%+ |
| **近期** | 6-24h | 5 min | 集成 + 确定性 | 5% | 0.70 | 80%+ |
| **中期** | 24-72h | 15 min | 集成 + 确定性 | 8% | 0.60 | 70%+ |
| **远期** | 72-168h | 30 min | 集成 | 12% | 0.55 | 60%+ |

### 4.2 配置化时间窗口

```java
@Data
public static class LeadHourTier {
    private String name;         // "ULTRA_NEAR", "NEAR", "MEDIUM", "FAR"
    private int minLeadHours;
    private int maxLeadHours;
    private long scanIntervalMs; // 扫描间隔
    private BigDecimal minEdge;
    private BigDecimal minConfidence;
    private BigDecimal kellyFraction;   // Kelly 缩放因子
    private BigDecimal maxStakeRatio;   // 最大单笔占池比例
}

// 默认配置
private List<LeadHourTier> leadHourTiers = List.of(
    new LeadHourTier("ULTRA_NEAR", 0, 6, 120_000, "0.03", "0.85", "0.40", "0.15"),
    new LeadHourTier("NEAR",       6, 24, 300_000, "0.05", "0.70", "0.30", "0.10"),
    new LeadHourTier("MEDIUM",     24, 72, 900_000, "0.08", "0.60", "0.25", "0.08"),
    new LeadHourTier("FAR",        72, 168, 1_800_000, "0.12", "0.55", "0.15", "0.05")
);
```

### 4.3 多频率调度器

替换现有的单一 `@Scheduled(15min)`，改为多档定时器：

```java
@Service
public class WeatherMultiTierScheduler {

    // 超近期扫描：2 分钟
    @Scheduled(fixedDelayString = "${weather-arbitrage.ultra-near-scan-ms:120000}")
    public void scanUltraNear() {
        runTierScan("ULTRA_NEAR", 0, 6);
    }

    // 近期扫描：5 分钟
    @Scheduled(fixedDelayString = "${weather-arbitrage.near-scan-ms:300000}")
    public void scanNear() {
        runTierScan("NEAR", 6, 24);
    }

    // 中期扫描：15 分钟（沿用现有频率）
    @Scheduled(fixedDelayString = "${weather-arbitrage.medium-scan-ms:900000}")
    public void scanMedium() {
        runTierScan("MEDIUM", 24, 72);
    }

    // 远期扫描：30 分钟
    @Scheduled(fixedDelayString = "${weather-arbitrage.far-scan-ms:1800000}")
    public void scanFar() {
        runTierScan("FAR", 72, 168);
    }

    private void runTierScan(String tierName, int minLead, int maxLead) {
        // 1. 扫描市场，过滤 leadHours 在 [minLead, maxLead) 范围内的
        // 2. 拉取数据（超近期额外拉观测数据）
        // 3. 计算 fair value
        // 4. 生成信号
        // 5. 执行（paper / live）
    }
}
```

---

## 五、仓位层升级 — 动态 Kelly 仓位管理

### 5.1 分数 Kelly 公式

```text
f* = (p × b - q) / b × kellyFraction

其中：
  p = fair value（我们估计的真实概率）
  q = 1 - p
  b = (1 / marketPrice) - 1（赔率，如买 YES @ 0.65 → 赔率 = 0.538）
  kellyFraction = 按 leadHourTier 配置（0.15 ~ 0.40）
```

```java
@Service
public class WeatherKellyStakeService {

    public BigDecimal calculateStake(WeatherArbitrageSignal signal,
                                     LeadHourTier tier,
                                     BigDecimal availableCapital) {
        BigDecimal p = signal.getFairValue();
        BigDecimal q = BigDecimal.ONE.subtract(p);
        BigDecimal marketPrice = signal.getMarketPrice();

        if (marketPrice.compareTo(BigDecimal.ZERO) <= 0
                || marketPrice.compareTo(BigDecimal.ONE) >= 0) {
            return BigDecimal.ZERO;
        }

        // 赔率 b = (1/price) - 1
        BigDecimal b = BigDecimal.ONE.divide(marketPrice, 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);

        if (b.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // 全 Kelly：f = (p*b - q) / b
        BigDecimal fullKelly = p.multiply(b).subtract(q)
                .divide(b, 8, RoundingMode.HALF_UP);

        if (fullKelly.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO; // 负 EV，不下注
        }

        // 分数 Kelly
        BigDecimal fraction = tier.getKellyFraction();
        BigDecimal kellyStake = fullKelly.multiply(fraction).multiply(availableCapital)
                .setScale(2, RoundingMode.HALF_UP);

        // 上限：单笔不超过池子的 maxStakeRatio
        BigDecimal maxStake = availableCapital.multiply(tier.getMaxStakeRatio())
                .setScale(2, RoundingMode.HALF_UP);

        // 下限：1 USDC（Polymarket 最低交易额）
        BigDecimal minStake = BigDecimal.ONE;

        return kellyStake.min(maxStake).max(minStake);
    }
}
```

### 5.2 置信度加权

在 Kelly 基础上，再乘以 confidence 系数，避免低置信度信号赌太大：

```text
最终仓位 = kellyStake × confidence × edgeDecayFactor

edgeDecayFactor：如果 edge 相对于阈值只是刚刚超过，额外打折
  = min(1.0, (edge - minEdge) / minEdge + 0.5)
```

### 5.3 资金池管理增强

```java
// 现有：固定 500 USDC 池子，简单 allocated 计算
// 升级：分层资金池 + 日级回撤控制

@Data
public class WeatherCapitalPoolConfig {
    private BigDecimal totalPoolUsdc = new BigDecimal("500");
    private BigDecimal maxDailyDrawdownPct = new BigDecimal("0.10");  // 日最大回撤 10%
    private int maxConcurrentPositions = 15;                          // 最大同时持仓（提高）
    private BigDecimal reserveRatio = new BigDecimal("0.20");         // 20% 资金永远不投入

    // 分层分配
    private BigDecimal ultraNearPoolRatio = new BigDecimal("0.35");   // 35% 给超近期
    private BigDecimal nearPoolRatio = new BigDecimal("0.30");        // 30% 给近期
    private BigDecimal mediumPoolRatio = new BigDecimal("0.25");      // 25% 给中期
    private BigDecimal farPoolRatio = new BigDecimal("0.10");         // 10% 给远期
}
```

---

## 六、城市覆盖扩展

### 6.1 扩展到 Polymarket 全部温度市场城市

```yaml
weather-arbitrage:
  cities:
    - name: "New York"
      timeZone: "America/New_York"
      latitude: 40.7128
      longitude: -74.006
      aliases: ["new york", "nyc"]
      wundergroundStation: "KNYNEWYO722"   # Weather Underground 验证站（Polymarket 结算源）
    - name: "London"
      timeZone: "Europe/London"
      latitude: 51.5074
      longitude: -0.1278
      aliases: ["london"]
      wundergroundStation: "IGREAT143"
    - name: "Seoul"
      timeZone: "Asia/Seoul"
      latitude: 37.5665
      longitude: 126.978
      aliases: ["seoul"]
      wundergroundStation: "ISEOUL83"
    - name: "Paris"
      timeZone: "Europe/Paris"
      latitude: 48.8566
      longitude: 2.3522
      aliases: ["paris"]
      wundergroundStation: "IPARIS45"
    - name: "Shanghai"
      timeZone: "Asia/Shanghai"
      latitude: 31.2304
      longitude: 121.4737
      aliases: ["shanghai"]
      wundergroundStation: "ISHANG68"
    - name: "Denver"
      timeZone: "America/Denver"
      latitude: 39.7392
      longitude: -104.9903
      aliases: ["denver"]
      wundergroundStation: "KCODENVE445"
    - name: "Seattle"
      timeZone: "America/Los_Angeles"
      latitude: 47.6062
      longitude: -122.3321
      aliases: ["seattle"]
      wundergroundStation: "KWASEATT616"
    - name: "Miami"
      timeZone: "America/New_York"
      latitude: 25.7617
      longitude: -80.1918
      aliases: ["miami"]
      wundergroundStation: "KFLMIAMI294"
    - name: "Tokyo"
      timeZone: "Asia/Tokyo"
      latitude: 35.6762
      longitude: 139.6503
      aliases: ["tokyo"]
      wundergroundStation: "ITOKYO24"
    - name: "Sydney"
      timeZone: "Australia/Sydney"
      latitude: -33.8688
      longitude: 151.2093
      aliases: ["sydney"]
      wundergroundStation: "INSW1247"
```

### 6.2 Weather Underground 验证对齐

Polymarket 天气市场以 **Weather Underground** 气象站数据结算。我们的预测需要校准到和 WU 站点观测一致，否则预测准但结算不一致。

```java
// 校准流程（新增）：
// 1. 每天用 WU 站点的实际观测值更新 WeatherObservation 表
// 2. 对比预报 vs WU 实际值，更新 CalibrationProfile 的 bias 和 sigma
// 3. 优先使用 WU 最近 30 天的误差分布做校准
```

---

## 七、执行层 — 接入现有下单体系

### 7.1 LIVE 模式执行流

天气策略通过 `WeatherStrategyAdapter` 已接入 `PolymarketStrategy` 接口。LIVE 下单需要调用现有的 Polymarket CLOB API：

```java
@Service
public class WeatherLiveTradeService {

    private final PolymarketOrderExecutionService executionService; // 复用现有下单服务

    public void executeLiveSignal(WeatherMarketSpec spec, WeatherArbitrageSignal signal) {
        if (signal.getSide() == WeatherArbitrageSignal.Side.PASS) return;

        String tokenId = (signal.getSide() == WeatherArbitrageSignal.Side.BUY_YES)
                ? spec.getTokenId()
                : resolveNoTokenId(spec);

        BigDecimal limitPrice = signal.getMarketPrice()
                .add(new BigDecimal("0.01")); // 略高于市场价确保成交

        // 使用 GTC (Good Till Cancel) 订单
        executionService.placeLimitOrder(
                tokenId,
                signal.getStakeUsdc(),
                limitPrice,
                "GTC",
                "WEATHER_ARB_" + spec.getCity() + "_" + spec.getTargetDate()
        );
    }
}
```

### 7.2 提前退出（Mid-Market Exit）

对于非超近期市场，如果持仓后预报更新导致 fair value 大幅变动，应提前退出：

```java
/**
 * 每次扫描时，同时检查已有持仓是否需要提前退出
 */
public void checkEarlyExit(WeatherPaperPositionEntity position, WeatherFairValueResult currentFairValue) {
    BigDecimal entryFairValue = position.getFairValue();
    BigDecimal currentFV = currentFairValue.getFairValue();
    BigDecimal shift = currentFV.subtract(entryFairValue).abs();

    // 如果 fair value 向不利方向移动超过 15%，提前平仓
    boolean adverseShift = false;
    if ("BUY_YES".equals(position.getSide()) && currentFV.compareTo(entryFairValue) < 0) {
        adverseShift = shift.compareTo(new BigDecimal("0.15")) > 0;
    } else if ("BUY_NO".equals(position.getSide()) && currentFV.compareTo(entryFairValue) > 0) {
        adverseShift = shift.compareTo(new BigDecimal("0.15")) > 0;
    }

    if (adverseShift) {
        // 卖出当前持仓（market sell 或 limit sell @ 当前价 - 0.01）
        closePosition(position, "FAIR_VALUE_ADVERSE_SHIFT");
    }

    // 如果 fair value 已充分反映到市场价，赚到足够利润也可以提前退出
    BigDecimal currentMarketPrice = fetchCurrentPrice(position);
    BigDecimal profitPct = currentMarketPrice.subtract(position.getEntryPrice())
            .divide(position.getEntryPrice(), 4, RoundingMode.HALF_UP);
    if (profitPct.compareTo(new BigDecimal("0.30")) > 0) {
        closePosition(position, "PROFIT_TARGET_REACHED");
    }
}
```

---

## 八、风控层

### 8.1 多维度风控门

```java
public class WeatherRiskGates {

    // G1: 数据新鲜度
    boolean forecastStale = forecastAgeMinutes > maxForecastAgeMinutes;

    // G2: 市场流动性
    boolean lowLiquidity = spec.getLiquidityUsdc().compareTo(minLiquidityUsdc) < 0;

    // G3: 集成成员一致性（新增）
    // 如果成员分歧太大（标准差 > 8°F），说明天气不确定性大，降低仓位或 PASS
    boolean highDisagreement = ensembleStdDev.compareTo(new BigDecimal("8.0")) > 0;

    // G4: 日回撤限制（新增）
    boolean dailyDrawdownBreached = todayPnl.compareTo(maxDailyLoss.negate()) < 0;

    // G5: 同城同日集中度（新增）
    // 同一城市同一天的所有持仓总和不超过池子的 25%
    boolean cityDateConcentration = cityDateExposure.compareTo(pool.multiply("0.25")) > 0;

    // G6: 市场价格异常
    // 如果市场价在 0.02-0.98 之外，skip（结算接近、无交易空间）
    boolean priceAtExtreme = yesPrice.compareTo("0.02") < 0 || yesPrice.compareTo("0.98") > 0;

    // G7: Polymarket API 限频保护
    boolean apiCooldown = lastApiCallAt + cooldownMs > now;
}
```

### 8.2 每日自动报告

```java
@Scheduled(cron = "0 0 8 * * *") // 每天早 8 点
public void dailyReport() {
    // 汇总昨天的信号数 / 开仓数 / 命中数 / PnL
    // 按城市、按 lead-hour-tier 分维度统计
    // 输出到日志 + 数据库
}
```

---

## 九、完整流程图

```text
┌──────────────────────────────────────────────────────────────────────┐
│                    WeatherMultiTierScheduler                         │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌──────────┐                │
│  │ 2min    │ │ 5min    │ │ 15min    │ │ 30min    │                │
│  │ULTRA_NR │ │ NEAR    │ │ MEDIUM   │ │ FAR      │                │
│  └────┬────┘ └────┬────┘ └─────┬────┘ └─────┬────┘                │
│       │           │            │             │                      │
│       └───────────┴────────────┴─────────────┘                      │
│                          │                                           │
│              WeatherMarketScanService                                │
│              过滤: leadHours ∈ [tierMin, tierMax)                    │
│                          │                                           │
│              WeatherForecastAggregator                                │
│         ┌────────────────┼────────────────┐                          │
│    OpenMeteo Ensemble  Tomorrow.io    Realtime Obs                   │
│    (82+ 成员分布)     (确定性补充)   (<12h 实测数据)                 │
│         └────────────────┼────────────────┘                          │
│                          │                                           │
│              WeatherFairValueServiceV2                                │
│         Layer1(观测) / Layer2(集成) / Layer3(高斯)                   │
│                          │                                           │
│              WeatherArbitrageSignalService                            │
│              (按 tier 配置的 edge/confidence 阈值)                   │
│                          │                                           │
│              WeatherKellyStakeService                                │
│              (分数 Kelly × confidence × edgeDecay)                   │
│                          │                                           │
│              WeatherRiskGates                                        │
│              (流动性/一致性/集中度/回撤/限频)                         │
│                          │                                           │
│         ┌────────────────┼────────────────┐                          │
│      PAPER Mode       LIVE Mode       SHADOW Mode                    │
│    (模拟记录)      (实盘下单)        (仅记录日志)                    │
│                          │                                           │
│              WeatherPositionSyncService                               │
│              (到期结算 + 提前退出检测)                                │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 十、实施计划（按优先级排序）

### Phase 1：数据层（预计 2-3 天）

| 任务 | 改动文件 | 工作量 |
|------|---------|--------|
| 新增 `OpenMeteoEnsembleForecastProvider` | 新建 | 中 |
| 新增 `OpenMeteoObservationProvider` | 新建 | 小 |
| 新增 `WeatherForecastAggregator` | 新建 | 中 |
| 新增 `WeatherEnsembleForecast` / `WeatherRealtimeObservation` 模型 | 新建 | 小 |
| 扩展 `WeatherArbitrageProperties.CityRule` 加 latitude/longitude | 修改 | 小 |
| 扩展城市配置到 10 城 | 修改 yaml | 小 |

### Phase 2：模型层（预计 2 天）

| 任务 | 改动文件 | 工作量 |
|------|---------|--------|
| 新增 `WeatherFairValueServiceV2` 三层概率模型 | 新建 | 大 |
| 增强 `WeatherModelCalibrationService` 加 meanBias 字段 | 修改 | 小 |
| 新增 Weather Underground 观测对齐逻辑 | 新建 | 中 |

### Phase 3：信号+仓位层（预计 1-2 天）

| 任务 | 改动文件 | 工作量 |
|------|---------|--------|
| 新增 `LeadHourTier` 配置 + `WeatherMultiTierScheduler` | 新建 | 中 |
| 新增 `WeatherKellyStakeService` | 新建 | 小 |
| 增强 `WeatherCapitalPoolService` 分层资金池 | 修改 | 中 |

### Phase 4：执行+风控层（预计 1-2 天）

| 任务 | 改动文件 | 工作量 |
|------|---------|--------|
| 新增 `WeatherLiveTradeService` 接入现有下单 | 新建 | 中 |
| 新增提前退出逻辑 `checkEarlyExit` | 新建 | 中 |
| 新增 `WeatherRiskGates` 多维风控 | 新建 | 中 |
| 增强 `WeatherArbitrageJob.run()` 调用新流程 | 修改 | 中 |

### Phase 5：验证+上线（预计 2-3 天）

| 任务 | 说明 |
|------|------|
| SHADOW 模式运行 3 天 | 对比新旧模型 fair value 差异，验证数据拉取稳定性 |
| PAPER 模式运行 5-7 天 | 跟踪命中率和理论 PnL，按城市/tier 分析 |
| 分析超近期（<6h）命中率 | 预期 >85%，如果不到需要调校准 |
| 调优参数 | Kelly fraction, edge 阈值, 扫描频率 |
| LIVE 灰度上线 | 小仓位（1-5 USDC）先跑 3 天 |
| LIVE 全量 | 放开到完整 Kelly 仓位 |

---

## 十一、预期收益模型

### 保守估算（PAPER 验证期参考）

| 指标 | 超近期 (0-6h) | 近期 (6-24h) | 中期 (24-72h) | 远期 (72h+) |
|------|-------------|-------------|--------------|------------|
| 日均信号数 | 10-20 | 15-30 | 20-40 | 10-20 |
| 日均开仓数 | 5-10 | 5-10 | 5-10 | 2-5 |
| 预期命中率 | 88-92% | 78-85% | 68-75% | 58-65% |
| 平均 edge | 8-15% | 10-18% | 12-22% | 15-25% |
| 平均仓位 | 8-15 USDC | 5-12 USDC | 3-8 USDC | 2-5 USDC |
| 日均毛利 | 5-12 USDC | 4-10 USDC | 3-8 USDC | 1-4 USDC |

**综合日均预期**：13-34 USDC / 天，年化约 9-24% ROI（500 USDC 本金）

**核心盈利来源**：超近期 + 近期占总利润 60-70%，这也是你要求的"快进快出、提高频次"的重点。

### 关键假设

- Polymarket 温度市场持续维持当前规模（每天 10+ 城市、每城 2-5 个市场）
- Open-Meteo API 保持免费可用
- 每日有效交易窗口约 16 小时（温度主要在白天变化）
- 滑点+手续费约占毛利的 10-15%

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](MOC-Polymarket.md) ｜ [知识库首页](知识库首页.md)
