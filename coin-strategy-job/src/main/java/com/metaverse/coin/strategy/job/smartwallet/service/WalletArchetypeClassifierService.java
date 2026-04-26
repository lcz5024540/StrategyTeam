package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletArchetypeClassifierService {

    private static final long HF_SYNC_INTERVAL_MS = 30_000L;
    private static final long SWING_SYNC_INTERVAL_MS = 120_000L;
    private static final long THEMATIC_SYNC_INTERVAL_MS = 300_000L;
    private static final long VALUE_SYNC_INTERVAL_MS = 600_000L;
    private static final long NOISE_SYNC_INTERVAL_MS = 900_000L;

    private final SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;

    public WalletArchetypeView classify(String walletAddress) {
        return classify(walletAddress, null);
    }

    public WalletArchetypeView classify(String walletAddress, SmartWalletProfileEntity profile) {
        if (!StringUtils.hasText(walletAddress)) {
            return emptyView(walletAddress);
        }
        String normalizedWallet = walletAddress.trim();
        SmartWalletWalletFeatureDailyEntity feature = walletFeatureDailyMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                        .eq(SmartWalletWalletFeatureDailyEntity::getWalletAddress, normalizedWallet)
                        .orderByDesc(SmartWalletWalletFeatureDailyEntity::getFeatureDate)
                        .last("limit 1"));
        List<SmartWalletTradeEffectLabelEntity> labels = tradeEffectLabelMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                        .eq(SmartWalletTradeEffectLabelEntity::getWalletAddress, normalizedWallet)
                        .orderByDesc(SmartWalletTradeEffectLabelEntity::getEventAt)
                        .last("limit 60"));
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getWalletAddress, normalizedWallet)
                        .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                        .last("limit 60"));
        return classify(normalizedWallet, profile, feature, labels, events);
    }

    public WalletArchetypeView classify(String walletAddress,
                                        SmartWalletProfileEntity profile,
                                        SmartWalletWalletFeatureDailyEntity feature,
                                        List<SmartWalletTradeEffectLabelEntity> labels,
                                        List<SmartWalletTradeEventEntity> events) {
        BigDecimal alpha30s = positiveRatio30s(labels);
        BigDecimal alpha5m = positiveRatio5m(labels);
        BigDecimal alphaRule = positiveRatioRule(labels);
        BigDecimal medianInterTradeMinutes = medianInterTradeMinutes(events);
        int trades7d = feature == null || feature.getTrades7d() == null ? 0 : feature.getTrades7d();
        int trades30d = feature == null || feature.getTrades30d() == null ? 0 : feature.getTrades30d();
        int markets30d = feature == null || feature.getMarkets30d() == null ? 0 : feature.getMarkets30d();
        BigDecimal concentration = safe(feature == null ? null : feature.getConcentrationMarket30d());
        BigDecimal followableRate = safe(feature == null ? null : feature.getFollowableRate30d());
        BigDecimal winRate30d = safe(feature == null ? null : feature.getWinRate30d());
        int labelSampleSize = labels == null ? 0 : labels.size();
        BigDecimal leaderQuality = safe(profile == null ? null : profile.getWalletScore());

        String archetype;
        if (labelSampleSize >= 5 && followableRate.compareTo(new BigDecimal("0.40")) < 0 && alphaRule.compareTo(new BigDecimal("0.45")) < 0) {
            archetype = "NOISE_OR_INSUFFICIENT";
        } else if (trades7d >= 18
                || (medianInterTradeMinutes != null && medianInterTradeMinutes.compareTo(new BigDecimal("45")) <= 0)
                || alpha30s.compareTo(alphaRule.max(alpha5m)) > 0) {
            archetype = "HF_SCALPER";
        } else if (concentration.compareTo(new BigDecimal("0.55")) >= 0
                && markets30d > 0 && markets30d <= 6
                && alphaRule.compareTo(alpha30s.add(new BigDecimal("0.08"))) >= 0
                && alphaRule.compareTo(new BigDecimal("0.58")) >= 0) {
            archetype = "THEMATIC";
        } else if (trades30d > 0 && trades30d < 18
                && medianInterTradeMinutes != null
                && medianInterTradeMinutes.compareTo(new BigDecimal("180")) >= 0
                && winRate30d.compareTo(new BigDecimal("0.58")) >= 0
                && followableRate.compareTo(new BigDecimal("0.45")) >= 0) {
            archetype = "LOW_FREQ_VALUE";
        } else if (alpha5m.compareTo(new BigDecimal("0.55")) >= 0
                || alphaRule.compareTo(new BigDecimal("0.55")) >= 0
                || followableRate.compareTo(new BigDecimal("0.55")) >= 0) {
            archetype = "SWING";
        } else {
            archetype = "NOISE_OR_INSUFFICIENT";
        }

        String followLane = switch (archetype) {
            case "HF_SCALPER" -> "HF_FOLLOW";
            case "THEMATIC" -> "THEMATIC_FOLLOW";
            case "LOW_FREQ_VALUE" -> "VALUE_FOLLOW";
            case "SWING" -> "SWING_FOLLOW";
            default -> "OBSERVE_ONLY";
        };

        BigDecimal followFitScore = leaderQuality
                .multiply(new BigDecimal("0.15"))
                .add(followableRate.multiply(new BigDecimal("0.30")))
                .add(alpha30s.multiply(new BigDecimal("0.15")))
                .add(alpha5m.multiply(new BigDecimal("0.15")))
                .add(alphaRule.multiply(new BigDecimal("0.20")))
                .add(winRate30d.multiply(new BigDecimal("0.05")));
        if ("NOISE_OR_INSUFFICIENT".equals(archetype)) {
            followFitScore = followFitScore.subtract(new BigDecimal("0.25"));
        }
        if ("HF_SCALPER".equals(archetype) && medianInterTradeMinutes != null && medianInterTradeMinutes.compareTo(new BigDecimal("20")) <= 0) {
            followFitScore = followFitScore.add(new BigDecimal("0.05"));
        }
        if ("THEMATIC".equals(archetype) && concentration.compareTo(new BigDecimal("0.60")) >= 0) {
            followFitScore = followFitScore.add(new BigDecimal("0.05"));
        }
        if ("LOW_FREQ_VALUE".equals(archetype) && medianInterTradeMinutes != null
                && medianInterTradeMinutes.compareTo(new BigDecimal("360")) >= 0) {
            followFitScore = followFitScore.add(new BigDecimal("0.05"));
        }
        followFitScore = clamp(followFitScore);

        BigDecimal confidence = clamp(new BigDecimal("0.45")
                .add(BigDecimal.valueOf(Math.min(labelSampleSize, 20)).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP))
                .add(followableRate.multiply(new BigDecimal("0.20"))));

        long recommendedSyncIntervalMs = switch (archetype) {
            case "HF" -> HF_SYNC_INTERVAL_MS;
            case "THEMATIC" -> THEMATIC_SYNC_INTERVAL_MS;
            case "LOW_FREQ_VALUE" -> VALUE_SYNC_INTERVAL_MS;
            case "SWING" -> SWING_SYNC_INTERVAL_MS;
            default -> NOISE_SYNC_INTERVAL_MS;
        };

        return WalletArchetypeView.builder()
                .walletAddress(walletAddress)
                .archetype(archetype)
                .followLane(followLane)
                .archetypeReason(summary(archetype, trades7d, markets30d, medianInterTradeMinutes, alpha30s, alpha5m, alphaRule, followableRate))
                .archetypeConfidence(confidence.setScale(4, RoundingMode.HALF_UP))
                .confidence(confidence.setScale(4, RoundingMode.HALF_UP))
                .leaderQualityScore(leaderQuality.setScale(4, RoundingMode.HALF_UP))
                .followFitScore(followFitScore.setScale(4, RoundingMode.HALF_UP))
                .recommendedSyncIntervalMs(recommendedSyncIntervalMs)
                .labelSampleSize(labelSampleSize)
                .trades7d(trades7d)
                .trades30d(trades30d)
                .dedupOrderCount7d(trades7d)
                .dedupOrderCount30d(trades30d)
                .markets30d(markets30d)
                .concentrationMarket30d(concentration.setScale(4, RoundingMode.HALF_UP))
                .followableRate30d(followableRate.setScale(4, RoundingMode.HALF_UP))
                .alpha30s(alpha30s.setScale(4, RoundingMode.HALF_UP))
                .alpha5m(alpha5m.setScale(4, RoundingMode.HALF_UP))
                .alphaRule(alphaRule.setScale(4, RoundingMode.HALF_UP))
                .medianInterTradeMinutes(medianInterTradeMinutes == null ? null : medianInterTradeMinutes.setScale(2, RoundingMode.HALF_UP))
                .medianHoldMinutes(null)
                .dataQuality(labelSampleSize >= 5 ? "MEDIUM" : "LOW")
                .summary(summary(archetype, trades7d, markets30d, medianInterTradeMinutes, alpha30s, alpha5m, alphaRule, followableRate))
                .build();
    }

    public long recommendedSyncIntervalMs(String walletAddress, SmartWalletProfileEntity profile) {
        return classify(walletAddress, profile).recommendedSyncIntervalMs();
    }

    private WalletArchetypeView emptyView(String walletAddress) {
        return WalletArchetypeView.builder()
                .walletAddress(walletAddress)
                .archetype("NOISE_OR_INSUFFICIENT")
                .followLane("OBSERVE_ONLY")
                .archetypeReason("No wallet archetype evidence yet.")
                .archetypeConfidence(BigDecimal.ZERO)
                .confidence(BigDecimal.ZERO)
                .leaderQualityScore(BigDecimal.ZERO)
                .followFitScore(BigDecimal.ZERO)
                .recommendedSyncIntervalMs(NOISE_SYNC_INTERVAL_MS)
                .labelSampleSize(0)
                .trades7d(0)
                .trades30d(0)
                .dedupOrderCount7d(0)
                .dedupOrderCount30d(0)
                .markets30d(0)
                .concentrationMarket30d(BigDecimal.ZERO)
                .followableRate30d(BigDecimal.ZERO)
                .alpha30s(BigDecimal.ZERO)
                .alpha5m(BigDecimal.ZERO)
                .alphaRule(BigDecimal.ZERO)
                .medianHoldMinutes(BigDecimal.ZERO)
                .dataQuality("LOW")
                .summary("No wallet archetype evidence yet.")
                .build();
    }

    private String summary(String archetype,
                           int trades7d,
                           int markets30d,
                           BigDecimal medianInterTradeMinutes,
                           BigDecimal alpha30s,
                           BigDecimal alpha5m,
                           BigDecimal alphaRule,
                           BigDecimal followableRate) {
        return "HF_SCALPER".equals(archetype)
                ? "High-frequency wallet: fast turnover, short alpha window, should use low-latency follow."
                : "THEMATIC".equals(archetype)
                ? "Thematic wallet: concentrated markets with stronger rule-horizon alpha, suitable for slower follow."
                : "LOW_FREQ_VALUE".equals(archetype)
                ? "Low-frequency value wallet: slower turnover with healthy hit rate and more patient hold profile."
                : "SWING".equals(archetype)
                ? "Swing wallet: medium turnover with more stable 5m/rule alpha."
                : "Noise wallet: local followability is weak under current execution style. trades7d=" + trades7d
                + ", markets30d=" + markets30d
                + ", medianGapMin=" + (medianInterTradeMinutes == null ? "NA" : medianInterTradeMinutes.setScale(2, RoundingMode.HALF_UP))
                + ", alpha30s=" + alpha30s.setScale(4, RoundingMode.HALF_UP)
                + ", alpha5m=" + alpha5m.setScale(4, RoundingMode.HALF_UP)
                + ", alphaRule=" + alphaRule.setScale(4, RoundingMode.HALF_UP)
                + ", followableRate=" + followableRate.setScale(4, RoundingMode.HALF_UP) + ".";
    }

    private BigDecimal positiveRatio30s(List<SmartWalletTradeEffectLabelEntity> labels) {
        return positiveRatio(labels, SmartWalletTradeEffectLabelEntity::getIsPositiveEv30s);
    }

    private BigDecimal positiveRatio5m(List<SmartWalletTradeEffectLabelEntity> labels) {
        return positiveRatio(labels, SmartWalletTradeEffectLabelEntity::getIsPositiveEv5m);
    }

    private BigDecimal positiveRatioRule(List<SmartWalletTradeEffectLabelEntity> labels) {
        return positiveRatio(labels, SmartWalletTradeEffectLabelEntity::getIsPositiveEvRule);
    }

    private BigDecimal positiveRatio(List<SmartWalletTradeEffectLabelEntity> labels,
                                     java.util.function.Function<SmartWalletTradeEffectLabelEntity, Boolean> extractor) {
        if (labels == null || labels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int sample = 0;
        int positive = 0;
        for (SmartWalletTradeEffectLabelEntity label : labels) {
            if (label == null) {
                continue;
            }
            Boolean value = extractor.apply(label);
            if (value == null) {
                continue;
            }
            sample++;
            if (Boolean.TRUE.equals(value)) {
                positive++;
            }
        }
        if (sample == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(positive).divide(BigDecimal.valueOf(sample), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal medianInterTradeMinutes(List<SmartWalletTradeEventEntity> events) {
        if (events == null || events.size() < 2) {
            return null;
        }
        List<Long> sortedOpenedAt = events.stream()
                .filter(event -> event != null && event.getOpenedAt() != null)
                .map(event -> event.getOpenedAt().getTime())
                .sorted()
                .toList();
        if (sortedOpenedAt.size() < 2) {
            return null;
        }
        List<BigDecimal> gaps = new ArrayList<>();
        for (int i = 1; i < sortedOpenedAt.size(); i++) {
            long deltaMs = Math.max(0L, sortedOpenedAt.get(i) - sortedOpenedAt.get(i - 1));
            gaps.add(BigDecimal.valueOf(deltaMs)
                    .divide(new BigDecimal("60000"), 8, RoundingMode.HALF_UP));
        }
        gaps.sort(Comparator.naturalOrder());
        int mid = gaps.size() / 2;
        if (gaps.size() % 2 == 1) {
            return gaps.get(mid);
        }
        return gaps.get(mid - 1).add(gaps.get(mid))
                .divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }
}
