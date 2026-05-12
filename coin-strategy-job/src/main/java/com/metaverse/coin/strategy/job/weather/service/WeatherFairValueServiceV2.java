package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherCalibrationProfileEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Fair Value 计算服务 V2 — 三层递进概率模型 + 多源共识加权。
 *
 * Layer 1 (观测层): leadH < 6，使用实测数据，接近确定性
 * Layer 2 (集成层): 使用 82+ 集成成员的经验分布，无需正态假设
 * Layer 3 (高斯层): 降级方案，回退到参数化正态 CDF
 *
 * 最终 fair value = 加权平均(各层概率) × 共识系数
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherFairValueServiceV2 {

    private final WeatherArbitrageProperties properties;

    /**
     * 核心入口：根据数据可用性自动选择最优计算路径。
     */
    public WeatherFairValueResult computeFairValue(WeatherMarketSpec spec,
                                                    WeatherAggregatedData data,
                                                    Optional<WeatherCalibrationProfileEntity> calibration) {
        if (data.getDataSourceCount() == 0) {
            return pass("no_data_sources", data.getLeadHours());
        }

        int leadHours = data.getLeadHours();
        BigDecimal threshold = spec.getThresholdF();
        WeatherMarketSpec.TemperatureType tempType = spec.getTemperatureType();

        // ── Layer 1: 实时观测层 (leadH < 12 且有观测数据) ──
        // 对于 <6h 市场：观测数据权重极高(0.70)，几乎确定性
        // 对于 6-12h 市场：观测数据作为重要参考(0.45)，与集成共同决策
        // 对于 12-24h 市场：观测数据作为验证补充(0.25)，集成主导
        if (leadHours < 24 && data.getObservation() != null) {
            BigDecimal observationProb = computeFromObservation(spec, data.getObservation());
            if (observationProb != null) {
                BigDecimal confidence;
                BigDecimal obsWeight;
                BigDecimal ensWeight;

                if (leadHours < 6) {
                    // 超近期：观测为主
                    confidence = new BigDecimal("0.95");
                    obsWeight = new BigDecimal("0.70");
                    ensWeight = new BigDecimal("0.30");
                } else if (leadHours < 12) {
                    // 近期：观测+集成共同决策
                    confidence = new BigDecimal("0.88");
                    obsWeight = new BigDecimal("0.45");
                    ensWeight = new BigDecimal("0.55");
                } else {
                    // 12-24h：集成主导，观测辅助验证
                    confidence = new BigDecimal("0.78");
                    obsWeight = new BigDecimal("0.25");
                    ensWeight = new BigDecimal("0.75");
                }

                // 与集成数据加权融合
                if (data.getEnsemble() != null && data.getEnsemble().getTotalMembers() >= 20) {
                    BigDecimal ensembleProb = computeFromEnsemble(spec, data.getEnsemble(), calibration);
                    if (ensembleProb != null) {
                        observationProb = observationProb.multiply(obsWeight)
                                .add(ensembleProb.multiply(ensWeight))
                                .setScale(8, RoundingMode.HALF_UP);
                        // 多源一致提升信心
                        confidence = confidence.add(new BigDecimal("0.02")).min(new BigDecimal("0.98"));
                    }
                }
                return buildResult(observationProb, confidence, leadHours,
                        "observation_layer_" + (leadHours < 6 ? "ultra" : leadHours < 12 ? "near" : "medium"),
                        data.getConsensusScore());
            }
        }

        // ── Layer 2: 集成分布层 (有 ≥30 个集成成员) ──
        if (data.getEnsemble() != null && data.getEnsemble().getTotalMembers() >= 30) {
            BigDecimal ensembleProb = computeFromEnsemble(spec, data.getEnsemble(), calibration);
            if (ensembleProb != null) {
                BigDecimal confidence = computeEnsembleConfidence(data.getEnsemble(), leadHours);

                // 如果有多模型确定性数据做交叉验证
                if (data.getMultiModel() != null && data.getMultiModel().getModelPoints() != null
                        && data.getMultiModel().getModelPoints().size() >= 3) {
                    BigDecimal multiModelProb = computeFromMultiModel(spec, data.getMultiModel());
                    if (multiModelProb != null) {
                        // 集成权重 0.65，多模型权重 0.35
                        ensembleProb = ensembleProb.multiply(new BigDecimal("0.65"))
                                .add(multiModelProb.multiply(new BigDecimal("0.35")))
                                .setScale(8, RoundingMode.HALF_UP);
                        // 多源一致提升信心
                        confidence = confidence.add(new BigDecimal("0.05")).min(new BigDecimal("0.98"));
                    }
                }

                // 共识系数调整
                BigDecimal consensusMultiplier = data.getConsensusScore() != null
                        ? data.getConsensusScore().max(new BigDecimal("0.50"))
                        : new BigDecimal("0.70");
                confidence = confidence.multiply(consensusMultiplier).setScale(4, RoundingMode.HALF_UP);

                return buildResult(ensembleProb, confidence, leadHours,
                        "ensemble_layer", data.getConsensusScore());
            }
        }

        // ── Layer 3: 参数化高斯层 (降级方案) ──
        if (data.getDeterministic() != null) {
            return computeFromGaussian(spec, data.getDeterministic(), calibration, leadHours, data.getConsensusScore());
        }

        return pass("insufficient_data", leadHours);
    }

    // ════════════════════════════════════════════════════════════════════
    // Layer 1: Observation-based
    // ════════════════════════════════════════════════════════════════════

    private BigDecimal computeFromObservation(WeatherMarketSpec spec, WeatherRealtimeObservation obs) {
        BigDecimal threshold = spec.getThresholdF();

        if (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX) {
            if (obs.getTodayObservedMaxF().compareTo(threshold) > 0) {
                // 今天已经超过阈值 → 接近确定 YES
                return new BigDecimal("0.98");
            }
            // 还未超过，用剩余小时预报评估
            if (obs.getRemainingHourForecastsF() != null && !obs.getRemainingHourForecastsF().isEmpty()) {
                BigDecimal projectedMax = obs.getRemainingHourForecastsF().stream()
                        .reduce(obs.getTodayObservedMaxF(), BigDecimal::max);
                BigDecimal gap = threshold.subtract(obs.getTodayObservedMaxF());
                BigDecimal projectedMargin = projectedMax.subtract(threshold);

                if (projectedMargin.compareTo(BigDecimal.ZERO) > 0) {
                    // 预报显示还能突破，margin 越大越确定
                    double prob = 0.70 + Math.min(0.25, projectedMargin.doubleValue() * 0.04);
                    return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
                } else {
                    // 预报显示不太可能突破
                    double prob = Math.max(0.03, 0.25 - gap.doubleValue() * 0.04);
                    return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
                }
            }
            // 无剩余小时数据，但今天还没超过
            BigDecimal gap = threshold.subtract(obs.getTodayObservedMaxF());
            double prob = Math.max(0.05, 0.40 - gap.doubleValue() * 0.05);
            return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
        } else {
            // MIN 类型
            if (obs.getTodayObservedMinF().compareTo(threshold) < 0) {
                return new BigDecimal("0.98");
            }
            if (obs.getRemainingHourForecastsF() != null && !obs.getRemainingHourForecastsF().isEmpty()) {
                BigDecimal projectedMin = obs.getRemainingHourForecastsF().stream()
                        .reduce(obs.getTodayObservedMinF(), BigDecimal::min);
                if (projectedMin.compareTo(threshold) < 0) {
                    return new BigDecimal("0.78");
                }
            }
            BigDecimal gap = obs.getTodayObservedMinF().subtract(threshold);
            double prob = Math.max(0.03, 0.25 - gap.doubleValue() * 0.04);
            return BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Layer 2: Ensemble empirical distribution
    // ════════════════════════════════════════════════════════════════════

    private BigDecimal computeFromEnsemble(WeatherMarketSpec spec,
                                           WeatherEnsembleForecast ensemble,
                                           Optional<WeatherCalibrationProfileEntity> calibration) {
        List<BigDecimal> members = (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX)
                ? ensemble.getMaxTempMembersF()
                : ensemble.getMinTempMembersF();
        if (members == null || members.isEmpty()) return null;

        BigDecimal threshold = spec.getThresholdF();

        // 偏差校正
        BigDecimal biasCorrection = BigDecimal.ZERO;
        if (calibration.isPresent() && calibration.get().getSampleSize() != null
                && calibration.get().getSampleSize() >= 20) {
            // 使用历史校准的 sigma 间接推断 bias（未来可直接存储 meanBias）
            // 暂时不加 bias，纯用集成原始数据
        }

        // 经验概率
        long exceedCount;
        if (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX) {
            exceedCount = members.stream()
                    .map(m -> m.add(biasCorrection))
                    .filter(m -> m.compareTo(threshold) > 0)
                    .count();
        } else {
            exceedCount = members.stream()
                    .map(m -> m.add(biasCorrection))
                    .filter(m -> m.compareTo(threshold) < 0)
                    .count();
        }

        // Laplace 平滑：P = (count + 1) / (total + 2)
        return BigDecimal.valueOf(exceedCount + 1)
                .divide(BigDecimal.valueOf(members.size() + 2), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal computeEnsembleConfidence(WeatherEnsembleForecast ensemble, int leadHours) {
        double base = Math.min(ensemble.getTotalMembers(), 120) / 140.0;
        double modelBonus = Math.min(ensemble.getModelCount(), 5) * 0.04;
        double leadPenalty = leadHours > 72 ? -0.15 : leadHours > 48 ? -0.08 : leadHours > 24 ? -0.03 : 0;
        double confidence = Math.max(0.35, Math.min(0.95, base + modelBonus + leadPenalty));
        return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }

    // ════════════════════════════════════════════════════════════════════
    // Multi-Model cross-validation
    // ════════════════════════════════════════════════════════════════════

    private BigDecimal computeFromMultiModel(WeatherMarketSpec spec, WeatherMultiModelForecast multiModel) {
        List<WeatherMultiModelForecast.ModelPoint> points = multiModel.getModelPoints();
        if (points == null || points.isEmpty()) return null;

        BigDecimal threshold = spec.getThresholdF();
        long exceedCount;
        if (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX) {
            exceedCount = points.stream()
                    .filter(p -> p.getForecastMaxF().compareTo(threshold) > 0)
                    .count();
        } else {
            exceedCount = points.stream()
                    .filter(p -> p.getForecastMinF().compareTo(threshold) < 0)
                    .count();
        }
        return BigDecimal.valueOf(exceedCount + 1)
                .divide(BigDecimal.valueOf(points.size() + 2), 8, RoundingMode.HALF_UP);
    }

    // ════════════════════════════════════════════════════════════════════
    // Layer 3: Gaussian fallback
    // ════════════════════════════════════════════════════════════════════

    private WeatherFairValueResult computeFromGaussian(WeatherMarketSpec spec,
                                                       WeatherForecast forecast,
                                                       Optional<WeatherCalibrationProfileEntity> calibration,
                                                       int leadHours,
                                                       BigDecimal consensusScore) {
        BigDecimal sigma = calibration
                .map(p -> spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX
                        ? p.getSigmaMaxF() : p.getSigmaMinF())
                .filter(s -> s != null && s.compareTo(BigDecimal.ZERO) > 0)
                .orElse(properties.getSigmaF());

        BigDecimal anchorTemp = (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX)
                ? forecast.getForecastTempMaxF()
                : forecast.getForecastTempMinF();

        double z = spec.getThresholdF().subtract(anchorTemp)
                .divide(sigma, 8, RoundingMode.HALF_UP)
                .doubleValue();
        double cdf = 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
        double fairValue = (spec.getTemperatureType() == WeatherMarketSpec.TemperatureType.MAX)
                ? 1.0 - cdf : cdf;

        BigDecimal confidence = new BigDecimal("0.55"); // 高斯 fallback 置信度较低
        if (consensusScore != null) {
            confidence = confidence.multiply(consensusScore.max(new BigDecimal("0.50")))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        return buildResult(
                BigDecimal.valueOf(fairValue).setScale(8, RoundingMode.HALF_UP),
                confidence, leadHours, "gaussian_fallback", consensusScore);
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    private WeatherFairValueResult buildResult(BigDecimal fairValue, BigDecimal confidence,
                                               int leadHours, String reason, BigDecimal consensusScore) {
        return WeatherFairValueResult.builder()
                .actionable(true)
                .fairValue(fairValue)
                .confidence(confidence)
                .leadHours(leadHours)
                .forecastAgeMinutes(0)
                .reason(reason)
                .explain(String.format("layer=%s, fv=%.4f, conf=%.4f, consensus=%s",
                        reason, fairValue, confidence,
                        consensusScore != null ? consensusScore.setScale(3, RoundingMode.HALF_UP) : "N/A"))
                .build();
    }

    private WeatherFairValueResult pass(String reason, int leadHours) {
        return WeatherFairValueResult.builder()
                .actionable(false)
                .fairValue(BigDecimal.ZERO)
                .confidence(BigDecimal.ZERO)
                .leadHours(leadHours)
                .forecastAgeMinutes(0)
                .reason(reason)
                .explain(reason)
                .build();
    }

    private double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        x = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return sign * y;
    }
}
