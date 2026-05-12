package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 多源数据聚合器 — 天气策略 V2 的核心数据层。
 *
 * 5 个独立数据源的联合确认机制：
 *
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ Source 1: Open-Meteo Ensemble (5 模型 × 161 成员原始分布)        │
 * │ Source 2: Open-Meteo Multi-Model (6 国官方气象局确定性预测)      │
 * │ Source 3: Tomorrow.io (商业混合预报)                             │
 * │ Source 4: Open-Meteo 实时观测 (leadH<12 时的当天实测数据)       │
 * │ Source 5: NWS/NOAA (美国城市的官方预报，精度最高)               │
 * └───────────────────────────────────────────────────────────────────┘
 *
 * 共识验证逻辑：
 * - 各源独立估计 P(temp > threshold) → 得到多个概率估计值
 * - 如果各源估计一致（标准差小），共识度高 → 提高 confidence
 * - 如果各源估计分歧大，共识度低 → 降低 confidence 或 PASS
 *
 * 这确保了不会依赖单一数据源的错误预报做出交易决策。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherForecastAggregator {

    private final OpenMeteoEnsembleForecastProvider ensembleProvider;
    private final OpenMeteoMultiModelProvider multiModelProvider;
    private final TomorrowIoWeatherForecastProvider tomorrowIoProvider;
    private final OpenMeteoObservationProvider observationProvider;
    private final WeatherArbitrageProperties properties;

    /**
     * 聚合所有可用数据源，计算共识度。
     *
     * @param city 城市名
     * @param targetDate 预测目标日期
     * @param threshold 市场阈值温度 (°F)，用于计算各源的超越概率共识
     * @param temperatureType MAX 或 MIN
     * @return 聚合数据
     */
    public WeatherAggregatedData aggregate(String city, LocalDate targetDate,
                                           BigDecimal threshold,
                                           WeatherMarketSpec.TemperatureType temperatureType) {
        WeatherAggregatedData result = new WeatherAggregatedData();
        int leadHours = computeLeadHours(city, targetDate);
        result.setLeadHours(leadHours);
        result.setAggregatedAt(Instant.now());

        int sourceCount = 0;
        List<BigDecimal> probabilityEstimates = new ArrayList<>();

        // ── Source 1: Ensemble (最重要的数据源，161 成员原始分布) ──
        try {
            Optional<WeatherEnsembleForecast> ensemble = ensembleProvider.fetchEnsemble(city, targetDate);
            if (ensemble.isPresent()) {
                result.setEnsemble(ensemble.get());
                sourceCount++;
                // 计算该源对 threshold 的概率估计
                BigDecimal prob = computeEnsembleProbability(ensemble.get(), threshold, temperatureType);
                if (prob != null) probabilityEstimates.add(prob);
            }
        } catch (Exception ex) {
            log.debug("[WeatherAggregator] ensemble fetch failed: {}", ex.getMessage());
        }

        // ── Source 2: Multi-Model Deterministic (6 国官方模型独立预测) ──
        try {
            Optional<WeatherMultiModelForecast> multiModel = multiModelProvider.fetchMultiModel(city, targetDate);
            if (multiModel.isPresent()) {
                result.setMultiModel(multiModel.get());
                sourceCount++;
                BigDecimal prob = computeMultiModelProbability(multiModel.get(), threshold, temperatureType);
                if (prob != null) probabilityEstimates.add(prob);
            }
        } catch (Exception ex) {
            log.debug("[WeatherAggregator] multiModel fetch failed: {}", ex.getMessage());
        }

        // ── Source 3: Tomorrow.io (商业数据源交叉验证) ──
        try {
            Optional<WeatherForecast> deterministic = tomorrowIoProvider.fetchForecast(city, targetDate);
            if (deterministic.isPresent()) {
                result.setDeterministic(deterministic.get());
                sourceCount++;
                BigDecimal prob = computeDeterministicProbability(deterministic.get(), threshold, temperatureType);
                if (prob != null) probabilityEstimates.add(prob);
            }
        } catch (Exception ex) {
            log.debug("[WeatherAggregator] tomorrow.io fetch failed: {}", ex.getMessage());
        }

        // ── Source 4: 实时观测 (leadHours < 24 时额外拉取) ──
        if (leadHours < 24) {
            try {
                Optional<WeatherRealtimeObservation> obs = observationProvider.fetchCurrent(city);
                if (obs.isPresent()) {
                    result.setObservation(obs.get());
                    sourceCount++;
                    BigDecimal prob = computeObservationProbability(obs.get(), threshold, temperatureType);
                    if (prob != null) probabilityEstimates.add(prob);
                }
            } catch (Exception ex) {
                log.debug("[WeatherAggregator] observation fetch failed: {}", ex.getMessage());
            }
        }

        result.setDataSourceCount(sourceCount);

        // ── 计算多源共识度 ──
        BigDecimal consensus = computeConsensusScore(probabilityEstimates);
        result.setConsensusScore(consensus);

        log.info("[WeatherAggregator] city={} date={} leadH={} sources={} consensus={} probEstimates={}",
                city, targetDate, leadHours, sourceCount,
                consensus != null ? consensus.setScale(4, RoundingMode.HALF_UP) : "N/A",
                probabilityEstimates);

        return result;
    }

    /**
     * 计算集成成员中超过阈值的比例作为概率估计。
     */
    private BigDecimal computeEnsembleProbability(WeatherEnsembleForecast ensemble,
                                                   BigDecimal threshold,
                                                   WeatherMarketSpec.TemperatureType type) {
        List<BigDecimal> members = (type == WeatherMarketSpec.TemperatureType.MAX)
                ? ensemble.getMaxTempMembersF()
                : ensemble.getMinTempMembersF();
        if (members == null || members.isEmpty()) return null;

        long exceedCount;
        if (type == WeatherMarketSpec.TemperatureType.MAX) {
            exceedCount = members.stream().filter(m -> m.compareTo(threshold) > 0).count();
        } else {
            // MIN: "will low be below X?" → count members below threshold
            exceedCount = members.stream().filter(m -> m.compareTo(threshold) < 0).count();
        }
        // Laplace smoothing
        return BigDecimal.valueOf(exceedCount + 1)
                .divide(BigDecimal.valueOf(members.size() + 2), 8, RoundingMode.HALF_UP);
    }

    /**
     * 6 个官方模型中预测超过阈值的比例。
     */
    private BigDecimal computeMultiModelProbability(WeatherMultiModelForecast multiModel,
                                                    BigDecimal threshold,
                                                    WeatherMarketSpec.TemperatureType type) {
        if (multiModel.getModelPoints() == null || multiModel.getModelPoints().isEmpty()) return null;

        long exceedCount;
        if (type == WeatherMarketSpec.TemperatureType.MAX) {
            exceedCount = multiModel.getModelPoints().stream()
                    .filter(p -> p.getForecastMaxF().compareTo(threshold) > 0)
                    .count();
        } else {
            exceedCount = multiModel.getModelPoints().stream()
                    .filter(p -> p.getForecastMinF().compareTo(threshold) < 0)
                    .count();
        }
        int total = multiModel.getModelPoints().size();
        return BigDecimal.valueOf(exceedCount + 1)
                .divide(BigDecimal.valueOf(total + 2), 8, RoundingMode.HALF_UP);
    }

    /**
     * Tomorrow.io 确定性预报的概率估计（用 sigma=3.5°F 的高斯近似）。
     */
    private BigDecimal computeDeterministicProbability(WeatherForecast forecast,
                                                       BigDecimal threshold,
                                                       WeatherMarketSpec.TemperatureType type) {
        BigDecimal sigma = properties.getSigmaF();
        BigDecimal anchorTemp = (type == WeatherMarketSpec.TemperatureType.MAX)
                ? forecast.getForecastTempMaxF()
                : forecast.getForecastTempMinF();
        if (anchorTemp == null || sigma.compareTo(BigDecimal.ZERO) <= 0) return null;

        double z = threshold.subtract(anchorTemp)
                .divide(sigma, 8, RoundingMode.HALF_UP)
                .doubleValue();
        double cdf = 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
        double prob = (type == WeatherMarketSpec.TemperatureType.MAX)
                ? 1.0 - cdf  // P(max > threshold)
                : cdf;       // P(min < threshold)
        return BigDecimal.valueOf(Math.max(0.01, Math.min(0.99, prob))).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 实时观测的概率估计。
     */
    private BigDecimal computeObservationProbability(WeatherRealtimeObservation obs,
                                                     BigDecimal threshold,
                                                     WeatherMarketSpec.TemperatureType type) {
        if (type == WeatherMarketSpec.TemperatureType.MAX) {
            if (obs.getTodayObservedMaxF().compareTo(threshold) > 0) {
                // 已经超过阈值 → 接近确定
                return new BigDecimal("0.98");
            }
            // 还没超过，看剩余小时预报
            if (obs.getRemainingHourForecastsF() != null && !obs.getRemainingHourForecastsF().isEmpty()) {
                BigDecimal projectedMax = obs.getRemainingHourForecastsF().stream()
                        .reduce(obs.getTodayObservedMaxF(), BigDecimal::max);
                if (projectedMax.compareTo(threshold) > 0) {
                    BigDecimal margin = projectedMax.subtract(threshold);
                    // margin 越大越确定
                    double prob = 0.70 + Math.min(0.25, margin.doubleValue() * 0.05);
                    return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
                } else {
                    BigDecimal gap = threshold.subtract(projectedMax);
                    double prob = Math.max(0.05, 0.30 - gap.doubleValue() * 0.05);
                    return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
                }
            }
            return null;
        } else {
            // MIN: 看是否已经低于阈值
            if (obs.getTodayObservedMinF().compareTo(threshold) < 0) {
                return new BigDecimal("0.98");
            }
            // 看剩余小时是否可能降到阈值以下
            if (obs.getRemainingHourForecastsF() != null && !obs.getRemainingHourForecastsF().isEmpty()) {
                BigDecimal projectedMin = obs.getRemainingHourForecastsF().stream()
                        .reduce(obs.getTodayObservedMinF(), BigDecimal::min);
                if (projectedMin.compareTo(threshold) < 0) {
                    return new BigDecimal("0.80");
                }
            }
            return new BigDecimal("0.15");
        }
    }

    /**
     * 多源共识度计算。
     *
     * 逻辑：各源的概率估计如果高度一致（标准差小），说明所有数据源对结果有相同判断。
     * consensus = max(0, 1 - stdDev / 0.20)
     *
     * - stdDev = 0（完全一致）→ consensus = 1.0
     * - stdDev = 0.10（中等分歧）→ consensus = 0.5
     * - stdDev >= 0.20（严重分歧）→ consensus = 0
     */
    private BigDecimal computeConsensusScore(List<BigDecimal> probabilityEstimates) {
        if (probabilityEstimates == null || probabilityEstimates.size() < 2) {
            return probabilityEstimates != null && probabilityEstimates.size() == 1
                    ? new BigDecimal("0.50") // 单源无法计算共识，给中等分
                    : BigDecimal.ZERO;
        }

        double mean = probabilityEstimates.stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.5);

        double variance = probabilityEstimates.stream()
                .mapToDouble(p -> Math.pow(p.doubleValue() - mean, 2))
                .average()
                .orElse(0);

        double stdDev = Math.sqrt(variance);
        double consensus = Math.max(0, 1.0 - stdDev / 0.20);

        return BigDecimal.valueOf(consensus).setScale(4, RoundingMode.HALF_UP);
    }

    private int computeLeadHours(String city, LocalDate targetDate) {
        WeatherArbitrageProperties.CityRule cityRule = properties.getCities().stream()
                .filter(rule -> city.equalsIgnoreCase(rule.getName()))
                .findFirst()
                .orElse(null);
        ZoneId zone = (cityRule != null && cityRule.getTimeZone() != null)
                ? ZoneId.of(cityRule.getTimeZone())
                : ZoneId.of("UTC");
        Instant targetEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant();
        return (int) Duration.between(Instant.now(), targetEnd).toHours();
    }

    private double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        x = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return sign * y;
    }
}
