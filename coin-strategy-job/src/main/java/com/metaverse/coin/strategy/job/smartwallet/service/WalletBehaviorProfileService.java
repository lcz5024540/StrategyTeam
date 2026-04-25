package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletBehaviorProfileService {

    private static final long CACHE_TTL_MS = 10L * 60_000L;
    private static final long PNL_DISTRIBUTION_CACHE_TTL_MS = 5L * 60_000L;
    private static final String SOURCE = "POLYMARKET";

    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    private final SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    private final SmartWalletOrderAnalysisService orderAnalysisService;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final Map<String, CachedBehaviorProfile> behaviorCache = new ConcurrentHashMap<>();
    private volatile CachedPnlDistribution pnlDistributionCache;

    public List<WalletBehaviorProfileView> buildProfiles(List<SmartWalletProfileEntity> profiles, Integer limit) {
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }
        int safeLimit = limit == null ? 30 : Math.max(1, Math.min(limit, 200));
        return profiles.stream()
                .limit(safeLimit)
                .map(this::buildProfile)
                .sorted(Comparator.comparing(WalletBehaviorProfileView::replicabilityScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<WalletBehaviorProfileView> refreshAndPersistProfiles(List<SmartWalletProfileEntity> profiles, Integer limit) {
        return buildProfiles(profiles, limit);
    }

    public WalletBehaviorProfileView buildProfile(SmartWalletProfileEntity profile) {
        if (profile == null || !StringUtils.hasText(profile.getWalletAddress())) {
            return emptyProfile(profile == null ? null : profile.getWalletAddress());
        }

        String walletAddress = profile.getWalletAddress().trim();
        SmartWalletWalletFeatureDailyEntity feature = latestFeature(walletAddress);
        WalletArchetypeView archetypeView = walletArchetypeClassifierService.classify(walletAddress, profile);
        List<SmartWalletOrderAnalysisEntity> orders = orderAnalysisService.analyzeAndPersist(walletAddress);
        OrderAnalysisStats orderStats = analyzeOrderAnalysis(orders);
        AlphaStats alphaStats = analyzeAlpha(walletAddress);

        int trades7d = feature == null || feature.getTrades7d() == null ? 0 : feature.getTrades7d();
        int trades30d = feature == null || feature.getTrades30d() == null ? 0 : feature.getTrades30d();
        BigDecimal medianInterTradeMinutes = archetypeView == null ? null : archetypeView.medianInterTradeMinutes();
        String archetype = resolveArchetype(archetypeView, profile, feature, orderStats);
        String followLane = resolveFollowLane(archetypeView, archetype);
        boolean highFrequency = "HF".equalsIgnoreCase(archetype)
                || trades7d >= 18
                || (medianInterTradeMinutes != null && medianInterTradeMinutes.compareTo(new BigDecimal("45")) <= 0);

        BigDecimal recentWinRate = safe(firstNonNull(profile.getRecentWinRate30d(), profile.getWinRate()));
        BigDecimal orderWinRate = orderStats.sampleSize == 0 ? recentWinRate : ratio(orderStats.wins, orderStats.sampleSize);
        BigDecimal avgOrderRoiRate = orderStats.sampleSize == 0 ? safe(profile.getAverageRoiRate()) : orderStats.averageRoiRate;
        BigDecimal medianOrderRoiRate = orderStats.sampleSize == 0 ? safe(profile.getAverageRoiRate()) : orderStats.medianRoiRate;
        BigDecimal totalRealizedPnlUsdc = orderStats.sampleSize == 0
                ? safe(profile.getRealizedPnlUsdc())
                : orderStats.totalRealizedPnlUsdc;

        BigDecimal followableRate = safe(feature == null ? null : feature.getFollowableRate30d());
        BigDecimal followFit = archetypeView == null ? BigDecimal.ZERO : safe(archetypeView.followFitScore());
        BigDecimal stabilityScore = stabilityScore(profile, orderStats, recentWinRate, orderWinRate);
        BigDecimal qualityScore = qualityScore(profile, feature, orderWinRate, avgOrderRoiRate, medianOrderRoiRate);
        BigDecimal replicabilityScore = clamp(
                followFit.multiply(new BigDecimal("0.35"))
                        .add(followableRate.multiply(new BigDecimal("0.15")))
                        .add(orderStats.settlementCompletionRate.multiply(new BigDecimal("0.15")))
                        .add(orderWinRate.multiply(new BigDecimal("0.10")))
                        .add(stabilityScore.multiply(new BigDecimal("0.10")))
                        .add(qualityScore.multiply(new BigDecimal("0.10")))
                        .add(paceScore(medianInterTradeMinutes).multiply(new BigDecimal("0.05")))
                        .subtract(earlyExitPenalty(orderStats.earlyExitRate, highFrequency, medianInterTradeMinutes))
        );
        BigDecimal behaviorComposite = behaviorComposite(replicabilityScore, qualityScore, stabilityScore);
        String winSourceType = determineWinSourceType(alphaStats, orderStats, profile);

        String recommendedExitLane = recommendExitLane(highFrequency, medianInterTradeMinutes, orderStats, winSourceType);
        if (StringUtils.hasText(profile.getManualRecommendedExitLane())) {
            recommendedExitLane = profile.getManualRecommendedExitLane().trim();
        }

        WalletBehaviorProfileView view = WalletBehaviorProfileView.builder()
                .walletAddress(walletAddress)
                .alias(profile.getAlias())
                .archetype(archetype)
                .followLane(followLane)
                .highFrequencyTradingAddress(highFrequency)
                .trades7d(trades7d)
                .trades30d(trades30d)
                .medianInterTradeMinutes(scale(medianInterTradeMinutes, 2))
                .closedPositionSampleSize(orderStats.sampleSize)
                .orderWinRate(scale(orderWinRate, 4))
                .averageOrderRoiRate(scale(avgOrderRoiRate, 4))
                .medianOrderRoiRate(scale(medianOrderRoiRate, 4))
                .totalRealizedPnlUsdc(scale(totalRealizedPnlUsdc, 4))
                .settlementCompletionRate(scale(orderStats.settlementCompletionRate, 4))
                .earlyExitRate(scale(orderStats.earlyExitRate, 4))
                .profitEarlyExitRate(scale(orderStats.profitEarlyExitRate, 4))
                .lossEarlyExitRate(scale(orderStats.lossEarlyExitRate, 4))
                .earlySettlementRate(scale(orderStats.earlySettlementRate, 4))
                .winSourceType(winSourceType)
                .latencyAlphaRate(scale(alphaStats.latencyAlphaRate, 4))
                .swingAlphaRate(scale(alphaStats.swingAlphaRate, 4))
                .settlementWinRate(scale(orderStats.settlementWinRate, 4))
                .stabilityScore(scale(stabilityScore, 4))
                .qualityScore(scale(qualityScore, 4))
                .replicabilityScore(scale(replicabilityScore, 4))
                .behaviorComposite(scale(behaviorComposite, 4))
                .recommendedExitLane(recommendedExitLane)
                .summary(buildSummary(highFrequency, orderStats, recommendedExitLane, profile.getManualRecommendedExitLane(),
                        winSourceType, qualityScore, replicabilityScore))
                .build();
        persistBehaviorProfile(profile, view);
        return view;
    }

    public String recommendedExitLane(String walletAddress) {
        WalletBehaviorProfileView profile = getProfile(walletAddress);
        return profile == null ? null : profile.recommendedExitLane();
    }

    public WalletBehaviorProfileView getProfile(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        String normalizedWallet = walletAddress.trim().toLowerCase();
        CachedBehaviorProfile cached = behaviorCache.get(normalizedWallet);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt >= now) {
            return cached.profile;
        }
        SmartWalletProfileEntity profile = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));
        if (profile == null) {
            return null;
        }
        WalletBehaviorProfileView built = buildProfile(profile);
        behaviorCache.put(normalizedWallet, new CachedBehaviorProfile(built, now + CACHE_TTL_MS));
        return built;
    }

    public void evictProfileCache(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return;
        }
        behaviorCache.remove(walletAddress.trim().toLowerCase());
    }

    private WalletBehaviorProfileView emptyProfile(String walletAddress) {
        return WalletBehaviorProfileView.builder()
                .walletAddress(walletAddress)
                .highFrequencyTradingAddress(false)
                .closedPositionSampleSize(0)
                .orderWinRate(BigDecimal.ZERO)
                .averageOrderRoiRate(BigDecimal.ZERO)
                .medianOrderRoiRate(BigDecimal.ZERO)
                .totalRealizedPnlUsdc(BigDecimal.ZERO)
                .settlementCompletionRate(BigDecimal.ZERO)
                .earlyExitRate(BigDecimal.ZERO)
                .profitEarlyExitRate(BigDecimal.ZERO)
                .lossEarlyExitRate(BigDecimal.ZERO)
                .earlySettlementRate(BigDecimal.ZERO)
                .winSourceType("UNKNOWN")
                .latencyAlphaRate(BigDecimal.ZERO)
                .swingAlphaRate(BigDecimal.ZERO)
                .settlementWinRate(BigDecimal.ZERO)
                .stabilityScore(BigDecimal.ZERO)
                .qualityScore(BigDecimal.ZERO)
                .replicabilityScore(BigDecimal.ZERO)
                .behaviorComposite(BigDecimal.ZERO)
                .recommendedExitLane("OBSERVE_ONLY")
                .summary("No wallet behavior evidence yet.")
                .build();
    }

    public BigDecimal behaviorComposite(WalletBehaviorProfileView view) {
        if (view == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (view.behaviorComposite() != null) {
            return safe(view.behaviorComposite()).setScale(4, RoundingMode.HALF_UP);
        }
        return behaviorComposite(view.replicabilityScore(), view.qualityScore(), view.stabilityScore());
    }

    public BigDecimal behaviorComposite(BigDecimal replicabilityScore,
                                        BigDecimal qualityScore,
                                        BigDecimal stabilityScore) {
        return clamp(safe(replicabilityScore).multiply(new BigDecimal("0.45"))
                .add(safe(qualityScore).multiply(new BigDecimal("0.35")))
                .add(safe(stabilityScore).multiply(new BigDecimal("0.20"))))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private void persistBehaviorProfile(SmartWalletProfileEntity profile, WalletBehaviorProfileView view) {
        if (profile == null || view == null || !StringUtils.hasText(view.walletAddress())) {
            return;
        }
        Date now = new Date();
        SmartWalletBehaviorProfileEntity entity = behaviorProfileMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                        .eq(SmartWalletBehaviorProfileEntity::getWalletAddress, view.walletAddress())
                        .eq(SmartWalletBehaviorProfileEntity::getSource, SOURCE)
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new SmartWalletBehaviorProfileEntity()
                    .setWalletAddress(view.walletAddress())
                    .setSource(SOURCE)
                    .setCreatedAt(now);
        }
        entity.setAlias(view.alias())
                .setArchetype(view.archetype())
                .setFollowLane(view.followLane())
                .setHighFrequencyTradingAddress(Boolean.TRUE.equals(view.highFrequencyTradingAddress()))
                .setTrades7d(defaultInt(view.trades7d()))
                .setTrades30d(defaultInt(view.trades30d()))
                .setMedianInterTradeMinutes(safe(view.medianInterTradeMinutes()))
                .setClosedPositionSampleSize(defaultInt(view.closedPositionSampleSize()))
                .setOrderWinRate(safe(view.orderWinRate()))
                .setAverageOrderRoiRate(safe(view.averageOrderRoiRate()))
                .setMedianOrderRoiRate(safe(view.medianOrderRoiRate()))
                .setTotalRealizedPnlUsdc(safe(view.totalRealizedPnlUsdc()))
                .setSettlementCompletionRate(safe(view.settlementCompletionRate()))
                .setEarlyExitRate(safe(view.earlyExitRate()))
                .setProfitEarlyExitRate(safe(view.profitEarlyExitRate()))
                .setLossEarlyExitRate(safe(view.lossEarlyExitRate()))
                .setEarlySettlementRate(safe(view.earlySettlementRate()))
                .setWinSourceType(view.winSourceType())
                .setLatencyAlphaRate(safe(view.latencyAlphaRate()))
                .setSwingAlphaRate(safe(view.swingAlphaRate()))
                .setSettlementWinRate(safe(view.settlementWinRate()))
                .setStabilityScore(safe(view.stabilityScore()))
                .setQualityScore(safe(view.qualityScore()))
                .setReplicabilityScore(safe(view.replicabilityScore()))
                .setRecommendedExitLane(view.recommendedExitLane())
                .setSummary(view.summary())
                .setComputedAt(now)
                .setUpdatedAt(now);
        if (entity.getId() == null) {
            behaviorProfileMapper.insert(entity);
        } else {
            behaviorProfileMapper.updateById(entity);
        }
    }

    private SmartWalletWalletFeatureDailyEntity latestFeature(String walletAddress) {
        return walletFeatureDailyMapper.selectOne(new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                .eq(SmartWalletWalletFeatureDailyEntity::getWalletAddress, walletAddress)
                .orderByDesc(SmartWalletWalletFeatureDailyEntity::getFeatureDate)
                .last("limit 1"));
    }

    private OrderAnalysisStats analyzeOrderAnalysis(List<SmartWalletOrderAnalysisEntity> orders) {
        if (orders == null || orders.isEmpty()) {
            return OrderAnalysisStats.empty();
        }

        int wins = 0;
        int knownExitStyleCount = 0;
        int earlyExitCount = 0;
        int settledCount = 0;
        int settledWins = 0;
        int profitableKnown = 0;
        int profitableEarly = 0;
        int losingKnown = 0;
        int losingEarly = 0;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        List<BigDecimal> roiRates = new ArrayList<>();
        List<BigDecimal> holdMinutes = new ArrayList<>();

        for (SmartWalletOrderAnalysisEntity order : orders) {
            if (order == null) {
                continue;
            }
            BigDecimal pnl = safe(order.getRealizedPnlUsdc());
            totalRealizedPnl = totalRealizedPnl.add(pnl);
            if (Boolean.TRUE.equals(order.getIsWin())) {
                wins++;
            }
            if (order.getRoiRate() != null) {
                roiRates.add(order.getRoiRate());
            }
            if (order.getHoldMinutes() != null && order.getHoldMinutes().compareTo(BigDecimal.ZERO) > 0) {
                holdMinutes.add(order.getHoldMinutes());
            }

            String exitStyle = order.getExitStyle() == null ? "UNKNOWN" : order.getExitStyle().trim().toUpperCase();
            if ("UNKNOWN".equals(exitStyle)) {
                continue;
            }
            knownExitStyleCount++;
            boolean earlyExit = exitStyle.startsWith("EARLY_");
            boolean settled = exitStyle.startsWith("SETTLED_");
            if (earlyExit) {
                earlyExitCount++;
            }
            if (settled) {
                settledCount++;
                if ("SETTLED_WIN".equals(exitStyle)) {
                    settledWins++;
                }
            }
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                profitableKnown++;
                if ("EARLY_PROFIT".equals(exitStyle)) {
                    profitableEarly++;
                }
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                losingKnown++;
                if ("EARLY_STOP".equals(exitStyle)) {
                    losingEarly++;
                }
            }
        }

        return OrderAnalysisStats.builder()
                .sampleSize(orders.size())
                .wins(wins)
                .averageRoiRate(average(roiRates))
                .medianRoiRate(median(roiRates))
                .totalRealizedPnlUsdc(totalRealizedPnl)
                .settlementCompletionRate(orders.isEmpty() ? BigDecimal.ZERO : ratio(settledCount, orders.size()))
                .earlyExitRate(orders.isEmpty() ? BigDecimal.ZERO : ratio(earlyExitCount, orders.size()))
                .profitEarlyExitRate(profitableKnown == 0 ? BigDecimal.ZERO : ratio(profitableEarly, profitableKnown))
                .lossEarlyExitRate(losingKnown == 0 ? BigDecimal.ZERO : ratio(losingEarly, losingKnown))
                .earlySettlementRate(orders.isEmpty() ? BigDecimal.ZERO : ratio(earlyExitCount, orders.size()))
                .settlementWinRate(settledCount == 0 ? BigDecimal.ZERO : ratio(settledWins, settledCount))
                .medianHoldMinutes(median(holdMinutes))
                .build();
    }

    private AlphaStats analyzeAlpha(String walletAddress) {
        List<SmartWalletTradeEffectLabelEntity> labels = tradeEffectLabelMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEffectLabelEntity>()
                        .eq(SmartWalletTradeEffectLabelEntity::getWalletAddress, walletAddress)
                        .orderByDesc(SmartWalletTradeEffectLabelEntity::getEventAt)
                        .last("limit 60"));
        if (labels == null || labels.isEmpty()) {
            return AlphaStats.empty();
        }
        List<BigDecimal> latencySignals = new ArrayList<>();
        List<BigDecimal> swingSignals = new ArrayList<>();
        for (SmartWalletTradeEffectLabelEntity label : labels) {
            if (label == null) {
                continue;
            }
            if (label.getIsPositiveEv30s() != null) {
                latencySignals.add(Boolean.TRUE.equals(label.getIsPositiveEv30s()) ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            if (label.getNetPnl30s() != null) {
                latencySignals.add(label.getNetPnl30s().compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            if (label.getIsPositiveEv5m() != null) {
                swingSignals.add(Boolean.TRUE.equals(label.getIsPositiveEv5m()) ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            if (label.getNetPnl5m() != null) {
                swingSignals.add(label.getNetPnl5m().compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            }
        }
        return AlphaStats.builder()
                .latencyAlphaRate(average(latencySignals))
                .swingAlphaRate(average(swingSignals))
                .sampleSize(labels.size())
                .build();
    }

    private String resolveArchetype(WalletArchetypeView archetypeView,
                                    SmartWalletProfileEntity profile,
                                    SmartWalletWalletFeatureDailyEntity feature,
                                    OrderAnalysisStats orderStats) {
        String archetype = archetypeView == null ? "UNKNOWN" : archetypeView.archetype();
        int trades30d = feature == null || feature.getTrades30d() == null ? 0 : feature.getTrades30d();
        if (!"HF".equalsIgnoreCase(archetype)
                && !"THEMATIC".equalsIgnoreCase(archetype)
                && trades30d > 0 && trades30d < 18
                && safe(orderStats.medianHoldMinutes).compareTo(new BigDecimal("240")) >= 0
                && safe(orderStats.settlementCompletionRate).compareTo(new BigDecimal("0.60")) >= 0
                && safe(orderStats.settlementWinRate).compareTo(new BigDecimal("0.58")) >= 0
                && safe(firstNonNull(profile.getRecentWinRate30d(), profile.getWinRate())).compareTo(new BigDecimal("0.58")) >= 0) {
            return "LOW_FREQ_VALUE";
        }
        return archetype;
    }

    private String resolveFollowLane(WalletArchetypeView archetypeView, String archetype) {
        if ("LOW_FREQ_VALUE".equalsIgnoreCase(archetype)) {
            return "VALUE_FOLLOW";
        }
        return archetypeView == null ? "OBSERVE_ONLY" : archetypeView.followLane();
    }

    private String recommendExitLane(boolean highFrequency,
                                     BigDecimal medianInterTradeMinutes,
                                     OrderAnalysisStats stats,
                                     String winSourceType) {
        if (stats.sampleSize < 5) {
            return "OBSERVE_ONLY";
        }
        if (highFrequency && medianInterTradeMinutes != null
                && medianInterTradeMinutes.compareTo(new BigDecimal("15")) <= 0) {
            return "FAST_EXIT_OR_SKIP";
        }
        if (stats.earlyExitRate.compareTo(new BigDecimal("0.65")) >= 0) {
            return "FAST_EXIT_OR_SKIP";
        }
        if ("SETTLEMENT_ALPHA".equalsIgnoreCase(winSourceType)
                && stats.settlementWinRate.compareTo(new BigDecimal("0.60")) >= 0) {
            return "HOLD_TO_SETTLEMENT";
        }
        if (stats.profitEarlyExitRate.compareTo(new BigDecimal("0.55")) >= 0
                && stats.profitEarlyExitRate.compareTo(stats.lossEarlyExitRate.add(new BigDecimal("0.10"))) >= 0) {
            return "EARLY_TAKE_PROFIT";
        }
        if (stats.lossEarlyExitRate.compareTo(new BigDecimal("0.55")) >= 0) {
            return "FAST_STOP_LOSS";
        }
        if (stats.settlementCompletionRate.compareTo(new BigDecimal("0.75")) >= 0) {
            return "HOLD_TO_SETTLEMENT";
        }
        if (stats.earlyExitRate.compareTo(new BigDecimal("0.40")) >= 0) {
            return "HYBRID_EARLY_EXIT";
        }
        return "FOLLOW_ARCHETYPE";
    }

    private String determineWinSourceType(AlphaStats alphaStats,
                                          OrderAnalysisStats orderStats,
                                          SmartWalletProfileEntity profile) {
        if (alphaStats.sampleSize == 0 && orderStats.sampleSize == 0) {
            return "UNKNOWN";
        }
        if (safe(orderStats.settlementCompletionRate).compareTo(new BigDecimal("0.65")) >= 0
                && safe(orderStats.settlementWinRate).compareTo(new BigDecimal("0.60")) >= 0
                && safe(orderStats.settlementWinRate).compareTo(safe(alphaStats.swingAlphaRate)) >= 0) {
            return "SETTLEMENT_ALPHA";
        }
        if (safe(alphaStats.latencyAlphaRate).compareTo(new BigDecimal("0.58")) >= 0
                && safe(alphaStats.latencyAlphaRate).compareTo(safe(alphaStats.swingAlphaRate).add(new BigDecimal("0.05"))) >= 0) {
            return "LATENCY_ALPHA";
        }
        if (safe(alphaStats.swingAlphaRate).compareTo(new BigDecimal("0.58")) >= 0
                && safe(alphaStats.swingAlphaRate).compareTo(safe(alphaStats.latencyAlphaRate).add(new BigDecimal("0.03"))) >= 0) {
            return "SWING_ALPHA";
        }
        if (safe(orderStats.lossEarlyExitRate).compareTo(new BigDecimal("0.45")) >= 0
                && inverseDrawdown(profile == null ? null : profile.getMaxDrawdownRate()).compareTo(new BigDecimal("0.55")) >= 0) {
            return "RISK_CONTROL_ALPHA";
        }
        if (safe(orderStats.settlementWinRate).compareTo(new BigDecimal("0.55")) >= 0) {
            return "SETTLEMENT_ALPHA";
        }
        if (safe(alphaStats.latencyAlphaRate).compareTo(safe(alphaStats.swingAlphaRate)) >= 0
                && safe(alphaStats.latencyAlphaRate).compareTo(new BigDecimal("0.52")) >= 0) {
            return "LATENCY_ALPHA";
        }
        if (safe(alphaStats.swingAlphaRate).compareTo(new BigDecimal("0.52")) >= 0) {
            return "SWING_ALPHA";
        }
        if (safe(orderStats.lossEarlyExitRate).compareTo(new BigDecimal("0.35")) >= 0) {
            return "RISK_CONTROL_ALPHA";
        }
        return "UNKNOWN";
    }

    private String buildSummary(boolean highFrequency,
                                OrderAnalysisStats stats,
                                String recommendedExitLane,
                                String manualRecommendedExitLane,
                                String winSourceType,
                                BigDecimal qualityScore,
                                BigDecimal replicabilityScore) {
        String frequency = highFrequency ? "high-frequency" : "non-high-frequency";
        return "Wallet behaves like a " + frequency
                + " trader, winSourceType=" + winSourceType
                + ", qualityScore=" + scale(qualityScore, 4)
                + ", replicabilityScore=" + scale(replicabilityScore, 4)
                + ", earlyExitRate=" + scale(stats.earlyExitRate, 4)
                + ", settlementWinRate=" + scale(stats.settlementWinRate, 4)
                + ", recommendedExitLane=" + recommendedExitLane
                + (StringUtils.hasText(manualRecommendedExitLane) ? " (manual override)." : ".");
    }

    private BigDecimal qualityScore(SmartWalletProfileEntity profile,
                                    SmartWalletWalletFeatureDailyEntity feature,
                                    BigDecimal orderWinRate,
                                    BigDecimal avgOrderRoiRate,
                                    BigDecimal medianOrderRoiRate) {
        BigDecimal recentWinRate = safe(firstNonNull(profile == null ? null : profile.getRecentWinRate30d(),
                profile == null ? null : profile.getWinRate()));
        BigDecimal pnlPercentile = pnlPercentile(firstNonNull(feature == null ? null : feature.getPnl30d(),
                profile == null ? null : profile.getRealizedPnlUsdc()));
        BigDecimal drawdownInverse = inverseDrawdown(profile == null ? null : profile.getMaxDrawdownRate());
        BigDecimal freshness = freshnessScore(profile == null ? null : profile.getLastTradeAt());
        return clamp(
                recentWinRate.multiply(new BigDecimal("0.30"))
                        .add(safe(orderWinRate).multiply(new BigDecimal("0.20")))
                        .add(roiScore(avgOrderRoiRate).multiply(new BigDecimal("0.15")))
                        .add(roiScore(medianOrderRoiRate).multiply(new BigDecimal("0.10")))
                        .add(pnlPercentile.multiply(new BigDecimal("0.10")))
                        .add(drawdownInverse.multiply(new BigDecimal("0.10")))
                        .add(freshness.multiply(new BigDecimal("0.05")))
        );
    }

    private BigDecimal stabilityScore(SmartWalletProfileEntity profile,
                                      OrderAnalysisStats orderStats,
                                      BigDecimal recentWinRate,
                                      BigDecimal orderWinRate) {
        BigDecimal sampleConfidence = clamp(BigDecimal.valueOf(Math.min(orderStats.sampleSize, 40))
                .divide(new BigDecimal("40"), 8, RoundingMode.HALF_UP));
        BigDecimal consistency = BigDecimal.ONE.subtract(safe(recentWinRate).subtract(safe(orderWinRate)).abs());
        BigDecimal drawdownInverse = inverseDrawdown(profile == null ? null : profile.getMaxDrawdownRate());
        BigDecimal freshness = freshnessScore(profile == null ? null : profile.getLastTradeAt());
        return clamp(sampleConfidence.multiply(new BigDecimal("0.45"))
                .add(clamp(consistency).multiply(new BigDecimal("0.35")))
                .add(drawdownInverse.multiply(new BigDecimal("0.10")))
                .add(freshness.multiply(new BigDecimal("0.10"))));
    }

    private BigDecimal pnlPercentile(BigDecimal currentValue) {
        CachedPnlDistribution cached = pnlDistributionCache;
        long now = System.currentTimeMillis();
        if (cached == null || cached.expiresAt < now) {
            List<BigDecimal> values = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                            .eq(SmartWalletProfileEntity::getSource, SOURCE)
                            .orderByDesc(SmartWalletProfileEntity::getRealizedPnlUsdc))
                    .stream()
                    .map(SmartWalletProfileEntity::getRealizedPnlUsdc)
                    .filter(value -> value != null)
                    .sorted()
                    .toList();
            cached = new CachedPnlDistribution(values, now + PNL_DISTRIBUTION_CACHE_TTL_MS);
            pnlDistributionCache = cached;
        }
        if (cached.values.isEmpty() || currentValue == null) {
            return BigDecimal.ZERO;
        }
        long count = cached.values.stream().filter(value -> value.compareTo(currentValue) <= 0).count();
        return BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(cached.values.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal freshnessScore(Date lastTradeAt) {
        if (lastTradeAt == null) {
            return BigDecimal.ZERO;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - lastTradeAt.getTime());
        long ageDays = ageMs / (24L * 3600_000L);
        if (ageDays <= 7) {
            return BigDecimal.ONE;
        }
        if (ageDays <= 14) {
            return new BigDecimal("0.85");
        }
        if (ageDays <= 30) {
            return new BigDecimal("0.60");
        }
        if (ageDays <= 90) {
            return new BigDecimal("0.25");
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal roiScore(BigDecimal roiRate) {
        if (roiRate == null || roiRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return clamp(roiRate.divide(new BigDecimal("0.25"), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal inverseDrawdown(BigDecimal drawdownRate) {
        BigDecimal normalized = clamp(safe(drawdownRate).divide(new BigDecimal("0.50"), 8, RoundingMode.HALF_UP));
        return BigDecimal.ONE.subtract(normalized).max(BigDecimal.ZERO);
    }

    private BigDecimal paceScore(BigDecimal medianInterTradeMinutes) {
        if (medianInterTradeMinutes == null) {
            return new BigDecimal("0.50");
        }
        if (medianInterTradeMinutes.compareTo(new BigDecimal("10")) <= 0) {
            return new BigDecimal("0.05");
        }
        if (medianInterTradeMinutes.compareTo(new BigDecimal("30")) <= 0) {
            return new BigDecimal("0.30");
        }
        if (medianInterTradeMinutes.compareTo(new BigDecimal("60")) <= 0) {
            return new BigDecimal("0.55");
        }
        if (medianInterTradeMinutes.compareTo(new BigDecimal("180")) <= 0) {
            return new BigDecimal("0.80");
        }
        return new BigDecimal("0.90");
    }

    private BigDecimal earlyExitPenalty(BigDecimal earlyExitRate,
                                        boolean highFrequency,
                                        BigDecimal medianInterTradeMinutes) {
        BigDecimal penalty = BigDecimal.ZERO;
        if (safe(earlyExitRate).compareTo(new BigDecimal("0.60")) > 0) {
            penalty = penalty.add(new BigDecimal("0.10"));
        }
        if (highFrequency && medianInterTradeMinutes != null
                && medianInterTradeMinutes.compareTo(new BigDecimal("15")) <= 0) {
            penalty = penalty.add(new BigDecimal("0.10"));
        }
        return penalty;
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal value : values) {
            if (value == null) {
                continue;
            }
            sum = sum.add(value);
            count++;
        }
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        return sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = values.stream()
                .filter(v -> v != null)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid))
                .divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
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

    private BigDecimal scale(BigDecimal value, int scale) {
        return safe(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal secondary) {
        return primary != null ? primary : secondary;
    }

    private record CachedBehaviorProfile(WalletBehaviorProfileView profile, long expiresAt) {
    }

    private record CachedPnlDistribution(List<BigDecimal> values, long expiresAt) {
    }

    @lombok.Builder
    private static class AlphaStats {
        private final BigDecimal latencyAlphaRate;
        private final BigDecimal swingAlphaRate;
        private final int sampleSize;

        private static AlphaStats empty() {
            return AlphaStats.builder()
                    .latencyAlphaRate(BigDecimal.ZERO)
                    .swingAlphaRate(BigDecimal.ZERO)
                    .sampleSize(0)
                    .build();
        }
    }

    @lombok.Builder
    private static class OrderAnalysisStats {
        private final int sampleSize;
        private final int wins;
        private final BigDecimal averageRoiRate;
        private final BigDecimal medianRoiRate;
        private final BigDecimal totalRealizedPnlUsdc;
        private final BigDecimal settlementCompletionRate;
        private final BigDecimal earlyExitRate;
        private final BigDecimal profitEarlyExitRate;
        private final BigDecimal lossEarlyExitRate;
        private final BigDecimal earlySettlementRate;
        private final BigDecimal settlementWinRate;
        private final BigDecimal medianHoldMinutes;

        private static OrderAnalysisStats empty() {
            return OrderAnalysisStats.builder()
                    .sampleSize(0)
                    .wins(0)
                    .averageRoiRate(BigDecimal.ZERO)
                    .medianRoiRate(BigDecimal.ZERO)
                    .totalRealizedPnlUsdc(BigDecimal.ZERO)
                    .settlementCompletionRate(BigDecimal.ZERO)
                    .earlyExitRate(BigDecimal.ZERO)
                    .profitEarlyExitRate(BigDecimal.ZERO)
                    .lossEarlyExitRate(BigDecimal.ZERO)
                    .earlySettlementRate(BigDecimal.ZERO)
                    .settlementWinRate(BigDecimal.ZERO)
                    .medianHoldMinutes(BigDecimal.ZERO)
                    .build();
        }
    }
}
