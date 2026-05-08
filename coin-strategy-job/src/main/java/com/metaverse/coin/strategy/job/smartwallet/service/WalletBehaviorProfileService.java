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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Date computedAt = new Date();
        SmartWalletWalletFeatureDailyEntity feature = latestFeature(walletAddress);
        WalletArchetypeView archetypeView = walletArchetypeClassifierService.classify(walletAddress, profile);
        List<SmartWalletOrderAnalysisEntity> orders = orderAnalysisService.analyzeAndPersist(walletAddress);
        OrderAnalysisStats orderStats = analyzeOrderAnalysis(orders);
        AlphaStats alphaStats = analyzeAlpha(walletAddress);

        int trades7d = feature == null || feature.getTrades7d() == null ? 0 : feature.getTrades7d();
        int trades30d = feature == null || feature.getTrades30d() == null ? 0 : feature.getTrades30d();
        BigDecimal medianInterTradeMinutes = firstPositive(orderStats.medianInterTradeMinutes,
                archetypeView == null ? null : archetypeView.medianInterTradeMinutes());
        ArchetypeDecision archetypeDecision = resolveArchetype(archetypeView, profile, feature, orderStats, alphaStats, medianInterTradeMinutes);
        String archetype = archetypeDecision.archetype;
        String followLane = resolveFollowLane(archetype);
        boolean highFrequency = "HF_SCALPER".equalsIgnoreCase(archetype);

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

        // 冷启动中性化：sampleSize 不足时，followableRate / settlementCompletionRate
        // 天然为 0 会把分数封顶在 70%，这里按样本置信度向中性值 0.5 收敛。
        BigDecimal sampleConfidence = clamp(BigDecimal.valueOf(Math.min(orderStats.sampleSize, 20))
                .divide(new BigDecimal("20"), 8, RoundingMode.HALF_UP));
        BigDecimal neutral = new BigDecimal("0.50");
        BigDecimal effectiveFollowable = sampleConfidence.multiply(safe(followableRate))
                .add(BigDecimal.ONE.subtract(sampleConfidence).multiply(neutral));
        BigDecimal effectiveSettlement = sampleConfidence.multiply(safe(orderStats.settlementCompletionRate))
                .add(BigDecimal.ONE.subtract(sampleConfidence).multiply(neutral));

        // 若 openedAt 覆盖率不足，medianInterTradeMinutes 不可信，paceScore 用中性值 0.5。
        BigDecimal openedAtCoverage = safe(orderStats.openedAtCoverageRate);
        BigDecimal effectivePace = openedAtCoverage.compareTo(new BigDecimal("0.50")) >= 0
                ? paceScore(medianInterTradeMinutes)
                : neutral;

        // 新版 replicabilityScore 公式（移除 earlyExitPenalty；提前止盈是合理行为）：
        // 主权重集中到 followFit / 盈利能力；执行友好性降权并冷启动中性化。
        BigDecimal replicabilityScore = clamp(
                followFit.multiply(new BigDecimal("0.40"))
                        .add(effectiveFollowable.multiply(new BigDecimal("0.075")))
                        .add(effectiveSettlement.multiply(new BigDecimal("0.075")))
                        .add(safe(orderWinRate).multiply(new BigDecimal("0.15")))
                        .add(qualityScore.multiply(new BigDecimal("0.15")))
                        .add(stabilityScore.multiply(new BigDecimal("0.10")))
                        .add(effectivePace.multiply(new BigDecimal("0.05")))
        );
        BigDecimal scoreConfidence = sampleConfidence;
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
                .archetypeReason(archetypeDecision.reason)
                .archetypeConfidence(scale(archetypeDecision.confidence, 4))
                .highFrequencyTradingAddress(highFrequency)
                .trades7d(trades7d)
                .trades30d(trades30d)
                .dedupOrderCount7d(orderStats.dedupOrderCount7d)
                .dedupOrderCount30d(orderStats.dedupOrderCount30d)
                .winRate7d(orderStats.closedCount7d == 0 ? null : scale(ratio(orderStats.wins7d, orderStats.closedCount7d), 4))
                .winRate30d(orderStats.closedCount30d == 0 ? null : scale(ratio(orderStats.wins30d, orderStats.closedCount30d), 4))
                .medianInterTradeMinutes(scale(medianInterTradeMinutes, 2))
                .medianHoldMinutes(scale(orderStats.medianHoldMinutes, 2))
                .dataQuality(orderStats.dataQuality)
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
                .scoreConfidence(scale(scoreConfidence, 4))
                .behaviorComposite(scale(behaviorComposite, 4))
                .recommendedExitLane(recommendedExitLane)
                .summary(buildSummary(archetypeDecision, orderStats, recommendedExitLane, profile.getManualRecommendedExitLane(),
                        winSourceType, qualityScore, replicabilityScore))
                .computedAt(computedAt)
                .build();
        persistBehaviorProfile(profile, view);
        return view;
    }

    public WalletBehaviorProfileView getPersistedProfile(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return emptyProfile(walletAddress);
        }
        String normalizedWallet = walletAddress.trim();
        SmartWalletBehaviorProfileEntity entity = behaviorProfileMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                        .eq(SmartWalletBehaviorProfileEntity::getWalletAddress, normalizedWallet)
                        .eq(SmartWalletBehaviorProfileEntity::getSource, SOURCE)
                        .last("limit 1")
        );
        if (entity == null) {
            return emptyProfile(normalizedWallet);
        }
        return toView(entity);
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
                .archetype("NOISE_OR_INSUFFICIENT")
                .followLane("OBSERVE_ONLY")
                .archetypeReason("No deduplicated wallet order evidence yet.")
                .archetypeConfidence(BigDecimal.ZERO)
                .highFrequencyTradingAddress(false)
                .dedupOrderCount7d(0)
                .dedupOrderCount30d(0)
                .medianInterTradeMinutes(BigDecimal.ZERO)
                .medianHoldMinutes(BigDecimal.ZERO)
                .dataQuality("LOW")
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

    private WalletBehaviorProfileView toView(SmartWalletBehaviorProfileEntity entity) {
        if (entity == null) {
            return emptyProfile(null);
        }
        String summary = entity.getSummary();
        String summaryReason = parseSummaryText(summary, "reason=", ", dataQuality=");
        String summaryDataQuality = parseSummaryText(summary, "dataQuality=", ", dedupOrders7d=");
        Integer summaryDedup7d = parseSummaryInt(summary, "dedupOrders7d=", ", dedupOrders30d=");
        Integer summaryDedup30d = parseSummaryInt(summary, "dedupOrders30d=", ", winSourceType=");
        return WalletBehaviorProfileView.builder()
                .walletAddress(entity.getWalletAddress())
                .alias(entity.getAlias())
                .archetype(entity.getArchetype())
                .followLane(resolveFollowLane(entity.getArchetype()))
                .archetypeReason(StringUtils.hasText(summaryReason) ? summaryReason : "Persisted profile; refresh behavior profile to see the latest archetype evidence.")
                .archetypeConfidence(confidenceFromSample(defaultInt(entity.getClosedPositionSampleSize())))
                .highFrequencyTradingAddress(Boolean.TRUE.equals(entity.getHighFrequencyTradingAddress()))
                .trades7d(defaultInt(entity.getTrades7d()))
                .trades30d(defaultInt(entity.getTrades30d()))
                .dedupOrderCount7d(summaryDedup7d == null ? defaultInt(entity.getTrades7d()) : summaryDedup7d)
                .dedupOrderCount30d(summaryDedup30d == null ? defaultInt(entity.getTrades30d()) : summaryDedup30d)
                .medianInterTradeMinutes(scale(entity.getMedianInterTradeMinutes(), 2))
                .medianHoldMinutes(scale(entity.getMedianHoldMinutes(), 2))
                .dataQuality(StringUtils.hasText(summaryDataQuality)
                        ? summaryDataQuality
                        : dataQuality(defaultInt(entity.getClosedPositionSampleSize()), BigDecimal.ONE, BigDecimal.ZERO))
                .closedPositionSampleSize(defaultInt(entity.getClosedPositionSampleSize()))
                .orderWinRate(scale(entity.getOrderWinRate(), 4))
                .averageOrderRoiRate(scale(entity.getAverageOrderRoiRate(), 4))
                .medianOrderRoiRate(scale(entity.getMedianOrderRoiRate(), 4))
                .totalRealizedPnlUsdc(scale(entity.getTotalRealizedPnlUsdc(), 4))
                .settlementCompletionRate(scale(entity.getSettlementCompletionRate(), 4))
                .earlyExitRate(scale(entity.getEarlyExitRate(), 4))
                .profitEarlyExitRate(scale(entity.getProfitEarlyExitRate(), 4))
                .lossEarlyExitRate(scale(entity.getLossEarlyExitRate(), 4))
                .earlySettlementRate(scale(entity.getEarlySettlementRate(), 4))
                .winSourceType(StringUtils.hasText(entity.getWinSourceType()) ? entity.getWinSourceType() : "UNKNOWN")
                .latencyAlphaRate(scale(entity.getLatencyAlphaRate(), 4))
                .swingAlphaRate(scale(entity.getSwingAlphaRate(), 4))
                .settlementWinRate(scale(entity.getSettlementWinRate(), 4))
                .stabilityScore(scale(entity.getStabilityScore(), 4))
                .qualityScore(scale(entity.getQualityScore(), 4))
                .replicabilityScore(scale(entity.getReplicabilityScore(), 4))
                .behaviorComposite(behaviorComposite(entity.getReplicabilityScore(), entity.getQualityScore(), entity.getStabilityScore()))
                .recommendedExitLane(StringUtils.hasText(entity.getRecommendedExitLane()) ? entity.getRecommendedExitLane() : "OBSERVE_ONLY")
                .summary(summary)
                .computedAt(entity.getComputedAt())
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
        int openedAtCount = 0;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        List<BigDecimal> roiRates = new ArrayList<>();
        List<BigDecimal> holdMinutes = new ArrayList<>();
        List<Long> orderTimes = new ArrayList<>();
        Set<String> allDedupKeys = new HashSet<>();
        Set<String> dedupKeys7d = new HashSet<>();
        Set<String> dedupKeys30d = new HashSet<>();
        int wins7d = 0;
        int wins30d = 0;
        int closedCount7d = 0;
        int closedCount30d = 0;
        long now = System.currentTimeMillis();
        long cutoff7d = now - 7L * 24L * 3600_000L;
        long cutoff30d = now - 30L * 24L * 3600_000L;

        for (SmartWalletOrderAnalysisEntity order : orders) {
            if (order == null) {
                continue;
            }
            String dedupKey = uniqueOrderKey(order);
            allDedupKeys.add(dedupKey);
            Long orderTime = orderTimeMillis(order);
            Long openedAtMs = order.getOpenedAt() == null ? null : order.getOpenedAt().getTime();
            if (order.getOpenedAt() != null) {
                openedAtCount++;
            }
            if (openedAtMs != null) {
                // paceScore 使用 openedAt 计算下单间隔；closedAt 是市场结算时间，
                // 同一场市场会在同一秒"集体关仓"，会严重扭曲 medianInterTradeMinutes。
                orderTimes.add(openedAtMs);
            }
            if (orderTime != null) {
                if (orderTime >= cutoff7d) {
                    dedupKeys7d.add(dedupKey);
                    closedCount7d++;
                    if (Boolean.TRUE.equals(order.getIsWin())) {
                        wins7d++;
                    }
                }
                if (orderTime >= cutoff30d) {
                    dedupKeys30d.add(dedupKey);
                    closedCount30d++;
                    if (Boolean.TRUE.equals(order.getIsWin())) {
                        wins30d++;
                    }
                }
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

        BigDecimal knownExitStyleRate = orders.isEmpty() ? BigDecimal.ZERO : ratio(knownExitStyleCount, orders.size());
        BigDecimal openedAtCoverageRate = orders.isEmpty() ? BigDecimal.ZERO : ratio(openedAtCount, orders.size());

        return OrderAnalysisStats.builder()
                .sampleSize(orders.size())
                .dedupOrderCount(allDedupKeys.size())
                .dedupOrderCount7d(dedupKeys7d.size())
                .dedupOrderCount30d(dedupKeys30d.size())
                .wins(wins)
                .wins7d(wins7d)
                .wins30d(wins30d)
                .closedCount7d(closedCount7d)
                .closedCount30d(closedCount30d)
                .averageRoiRate(average(roiRates))
                .medianRoiRate(median(roiRates))
                .totalRealizedPnlUsdc(totalRealizedPnl)
                .knownExitStyleRate(knownExitStyleRate)
                .openedAtCoverageRate(openedAtCoverageRate)
                .settlementCompletionRate(orders.isEmpty() ? BigDecimal.ZERO : ratio(settledCount, orders.size()))
                .earlyExitRate(orders.isEmpty() ? BigDecimal.ZERO : ratio(earlyExitCount, orders.size()))
                .profitEarlyExitRate(profitableKnown == 0 ? BigDecimal.ZERO : ratio(profitableEarly, profitableKnown))
                .lossEarlyExitRate(losingKnown == 0 ? BigDecimal.ZERO : ratio(losingEarly, losingKnown))
                .earlySettlementRate(orders.isEmpty() ? BigDecimal.ZERO : ratio(earlyExitCount, orders.size()))
                .settlementWinRate(settledCount == 0 ? BigDecimal.ZERO : ratio(settledWins, settledCount))
                .medianHoldMinutes(median(holdMinutes))
                .medianInterTradeMinutes(medianInterTradeMinutes(orderTimes))
                .dataQuality(dataQuality(orders.size(), knownExitStyleRate, openedAtCoverageRate))
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

    private ArchetypeDecision resolveArchetype(WalletArchetypeView archetypeView,
                                               SmartWalletProfileEntity profile,
                                               SmartWalletWalletFeatureDailyEntity feature,
                                               OrderAnalysisStats orderStats,
                                               AlphaStats alphaStats,
                                               BigDecimal medianInterTradeMinutes) {
        int sampleSize = orderStats.sampleSize;
        int dedup30d = orderStats.dedupOrderCount30d;
        int trades30d = feature == null || feature.getTrades30d() == null ? 0 : feature.getTrades30d();
        int markets30d = feature == null || feature.getMarkets30d() == null ? 0 : feature.getMarkets30d();
        BigDecimal concentration = safe(feature == null ? null : feature.getConcentrationMarket30d());
        BigDecimal orderWinRate = sampleSize == 0 ? safe(firstNonNull(profile.getRecentWinRate30d(), profile.getWinRate()))
                : ratio(orderStats.wins, sampleSize);
        BigDecimal medianHold = safe(orderStats.medianHoldMinutes);
        BigDecimal gap = safe(medianInterTradeMinutes);
        String quality = orderStats.dataQuality;

        if (sampleSize < 5 || "LOW".equalsIgnoreCase(quality)) {
            return decision("NOISE_OR_INSUFFICIENT", sampleSize < 5
                            ? "Insufficient deduplicated closed-order sample; keep observe-only."
                            : "Order sample has low data quality, so no strong archetype is assigned.",
                    confidenceFromSample(sampleSize).multiply(new BigDecimal("0.50")));
        }

        if (safe(orderStats.settlementCompletionRate).compareTo(new BigDecimal("0.70")) >= 0
                && safe(orderStats.settlementWinRate).compareTo(new BigDecimal("0.58")) >= 0
                && medianHold.compareTo(new BigDecimal("240")) >= 0) {
            return decision("SETTLEMENT_HOLDER",
                    "Most reliable orders reach settlement, with healthy settlement win rate and long holds.",
                    new BigDecimal("0.82"));
        }

        if (dedup30d > 0 && dedup30d < 18
                && (gap.compareTo(new BigDecimal("180")) >= 0 || medianHold.compareTo(new BigDecimal("240")) >= 0)
                && orderWinRate.compareTo(new BigDecimal("0.55")) >= 0) {
            return decision("LOW_FREQ_VALUE",
                    "Deduplicated order count is low, gaps/holds are long, and order win rate is acceptable.",
                    new BigDecimal("0.78"));
        }

        boolean hfVolume = orderStats.dedupOrderCount7d >= 18 || dedup30d >= 40;
        boolean hfGap = gap.compareTo(BigDecimal.ZERO) > 0 && gap.compareTo(new BigDecimal("45")) <= 0;
        boolean hfHold = medianHold.compareTo(BigDecimal.ZERO) > 0 && medianHold.compareTo(new BigDecimal("90")) <= 0;
        boolean hfExit = safe(orderStats.earlyExitRate).compareTo(new BigDecimal("0.50")) >= 0;
        if (sampleSize >= 12 && hfVolume && hfGap && hfHold && hfExit) {
            return decision("HF_SCALPER",
                    "High deduplicated order frequency, short inter-order gaps, short holds, and early exits align.",
                    new BigDecimal("0.86"));
        }

        if (markets30d > 0 && markets30d <= 6
                && concentration.compareTo(new BigDecimal("0.55")) >= 0) {
            return decision("THEMATIC",
                    "Orders are concentrated in a small set of markets/themes without meeting HF evidence.",
                    new BigDecimal("0.72"));
        }

        if ((trades30d >= 8 || dedup30d >= 5)
                && concentration.compareTo(new BigDecimal("0.35")) >= 0
                && medianHold.compareTo(new BigDecimal("60")) >= 0
                && medianHold.compareTo(new BigDecimal("360")) <= 0) {
            return decision("EVENT_DRIVEN",
                    "Activity clusters around a concentrated event set with medium hold times.",
                    new BigDecimal("0.68"));
        }

        if (safe(orderStats.lossEarlyExitRate).compareTo(new BigDecimal("0.55")) >= 0
                && inverseDrawdown(profile == null ? null : profile.getMaxDrawdownRate()).compareTo(new BigDecimal("0.55")) >= 0) {
            return decision("RISK_CONTROL",
                    "Edge appears more tied to loss exits and controlled drawdown than pure hit rate.",
                    new BigDecimal("0.70"));
        }

        if ((safe(alphaStats.swingAlphaRate).compareTo(safe(alphaStats.latencyAlphaRate).add(new BigDecimal("0.03"))) >= 0
                || safe(feature == null ? null : feature.getFollowableRate30d()).compareTo(new BigDecimal("0.55")) >= 0)
                && medianHold.compareTo(new BigDecimal("60")) >= 0) {
            return decision("SWING",
                    "Medium-horizon alpha/followability is stronger than short latency evidence.",
                    new BigDecimal("0.66"));
        }

        String upstream = archetypeView == null ? null : archetypeView.archetype();
        if ("HF".equalsIgnoreCase(upstream)) {
            return decision("NOISE_OR_INSUFFICIENT",
                    "Raw trade classifier suggested HF, but deduplicated order evidence did not satisfy HF gates.",
                    new BigDecimal("0.45"));
        }
        return decision("NOISE_OR_INSUFFICIENT",
                "No archetype has enough deduplicated evidence yet.",
                confidenceFromSample(sampleSize).multiply(new BigDecimal("0.60")));
    }

    private String resolveFollowLane(String archetype) {
        if ("HF_SCALPER".equalsIgnoreCase(archetype)) {
            return "HF_FOLLOW";
        }
        if ("LOW_FREQ_VALUE".equalsIgnoreCase(archetype)
                || "SETTLEMENT_HOLDER".equalsIgnoreCase(archetype)) {
            return "VALUE_FOLLOW";
        }
        if ("EVENT_DRIVEN".equalsIgnoreCase(archetype)
                || "THEMATIC".equalsIgnoreCase(archetype)) {
            return "THEMATIC_FOLLOW";
        }
        if ("SWING".equalsIgnoreCase(archetype)) {
            return "SWING_FOLLOW";
        }
        if ("RISK_CONTROL".equalsIgnoreCase(archetype)) {
            return "CAUTIOUS_FOLLOW";
        }
        return "OBSERVE_ONLY";
    }

    private String recommendExitLane(boolean highFrequency,
                                     BigDecimal medianInterTradeMinutes,
                                     OrderAnalysisStats stats,
                                     String winSourceType) {
        if (stats.sampleSize < 5 || "LOW".equalsIgnoreCase(stats.dataQuality)) {
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
        if ((alphaStats.sampleSize < 4 && orderStats.sampleSize < 5)
                || "LOW".equalsIgnoreCase(orderStats.dataQuality)) {
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

    private String buildSummary(ArchetypeDecision decision,
                                OrderAnalysisStats stats,
                                String recommendedExitLane,
                                String manualRecommendedExitLane,
                                String winSourceType,
                                BigDecimal qualityScore,
                                BigDecimal replicabilityScore) {
        return "Wallet archetype=" + decision.archetype
                + ", reason=" + decision.reason
                + ", dataQuality=" + stats.dataQuality
                + ", dedupOrders7d=" + stats.dedupOrderCount7d
                + ", dedupOrders30d=" + stats.dedupOrderCount30d
                + ", winSourceType=" + winSourceType
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
        // qualityScore 只回答"是否赚钱"：聚焦胜率 + ROI + 横向分位，
        // 抗回撤 / 新鲜度已迁移到 stabilityScore，orderWinRate 已与 recentWinRate 高度相关不再重复。
        BigDecimal recentWinRate = safe(firstNonNull(profile == null ? null : profile.getRecentWinRate30d(),
                profile == null ? null : profile.getWinRate()));
        BigDecimal pnlPercentile = pnlPercentile(firstNonNull(feature == null ? null : feature.getPnl30d(),
                profile == null ? null : profile.getRealizedPnlUsdc()));
        return clamp(
                recentWinRate.multiply(new BigDecimal("0.35"))
                        .add(roiScore(avgOrderRoiRate).multiply(new BigDecimal("0.25")))
                        .add(roiScore(medianOrderRoiRate).multiply(new BigDecimal("0.20")))
                        .add(pnlPercentile.multiply(new BigDecimal("0.20")))
        );
    }

    private BigDecimal stabilityScore(SmartWalletProfileEntity profile,
                                      OrderAnalysisStats orderStats,
                                      BigDecimal recentWinRate,
                                      BigDecimal orderWinRate) {
        // stabilityScore 只回答"是否可持续/可信"：样本充分性 + 抗回撤 + 新鲜度 + 胜率口径一致性。
        // 抗回撤 / 新鲜度从 qualityScore 迁入并提高权重；胜率一致性权重下降避免与 quality 双计。
        BigDecimal sampleConfidence = clamp(BigDecimal.valueOf(Math.min(orderStats.sampleSize, 40))
                .divide(new BigDecimal("40"), 8, RoundingMode.HALF_UP));
        BigDecimal consistency = BigDecimal.ONE.subtract(safe(recentWinRate).subtract(safe(orderWinRate)).abs());
        BigDecimal drawdownInverse = inverseDrawdown(profile == null ? null : profile.getMaxDrawdownRate());
        BigDecimal freshness = freshnessScore(profile == null ? null : profile.getLastTradeAt());
        return clamp(sampleConfidence.multiply(new BigDecimal("0.35"))
                .add(drawdownInverse.multiply(new BigDecimal("0.25")))
                .add(freshness.multiply(new BigDecimal("0.20")))
                .add(clamp(consistency).multiply(new BigDecimal("0.20"))));
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

    private ArchetypeDecision decision(String archetype, String reason, BigDecimal confidence) {
        return new ArchetypeDecision(archetype, reason, clamp(confidence));
    }

    private String uniqueOrderKey(SmartWalletOrderAnalysisEntity order) {
        if (order == null) {
            return "";
        }
        return defaultString(order.getMarketId()) + "|" + defaultString(order.getOutcome()).trim().toUpperCase();
    }

    /**
     * 返回订单的时间锚点，用于 7D/30D 窗口过滤。
     * 优先使用 closedAt（关仓时间），因为 "7D 去重" 的语义是"最近 7 天内关仓的订单"。
     * 如果 closedAt 不存在，则 fallback 到 openedAt。
     */
    private Long orderTimeMillis(SmartWalletOrderAnalysisEntity order) {
        if (order == null) {
            return null;
        }
        if (order.getClosedAt() != null) {
            return order.getClosedAt().getTime();
        }
        if (order.getOpenedAt() != null) {
            return order.getOpenedAt().getTime();
        }
        return null;
    }

    private BigDecimal medianInterTradeMinutes(List<Long> orderTimes) {
        if (orderTimes == null || orderTimes.size() < 2) {
            return BigDecimal.ZERO;
        }
        List<Long> sorted = orderTimes.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList();
        if (sorted.size() < 2) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(BigDecimal.valueOf(Math.max(0L, sorted.get(i) - sorted.get(i - 1)))
                    .divide(new BigDecimal("60000"), 8, RoundingMode.HALF_UP));
        }
        return median(gaps);
    }

    /**
     * 数据质量分级。
     * <p>HIGH: 样本≥20 + 退出风格已知率≥70% + openedAt 覆盖率≥50%
     * <p>MEDIUM: 样本≥3 即可（放松了对 knownExitStyleRate 的硬依赖，
     *   因为 metadata 拉取失败不应连带否定整个地址的统计可信度）
     * <p>LOW: 样本＜3
     */
    private String dataQuality(int sampleSize, BigDecimal knownExitStyleRate, BigDecimal openedAtCoverageRate) {
        if (sampleSize >= 20
                && safe(knownExitStyleRate).compareTo(new BigDecimal("0.70")) >= 0
                && safe(openedAtCoverageRate).compareTo(new BigDecimal("0.50")) >= 0) {
            return "HIGH";
        }
        if (sampleSize >= 3) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private BigDecimal confidenceFromSample(int sampleSize) {
        return clamp(BigDecimal.valueOf(Math.min(Math.max(sampleSize, 0), 40))
                .divide(new BigDecimal("40"), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal firstPositive(BigDecimal primary, BigDecimal secondary) {
        if (primary != null && primary.compareTo(BigDecimal.ZERO) > 0) {
            return primary;
        }
        return secondary;
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

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String parseSummaryText(String summary, String prefix, String suffix) {
        if (!StringUtils.hasText(summary) || !StringUtils.hasText(prefix)) {
            return null;
        }
        int start = summary.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = StringUtils.hasText(suffix) ? summary.indexOf(suffix, start) : -1;
        String value = end < 0 ? summary.substring(start) : summary.substring(start, end);
        value = value == null ? null : value.trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private Integer parseSummaryInt(String summary, String prefix, String suffix) {
        String value = parseSummaryText(summary, prefix, suffix);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        private final int dedupOrderCount;
        private final int dedupOrderCount7d;
        private final int dedupOrderCount30d;
        private final int wins;
        private final int wins7d;
        private final int wins30d;
        private final int closedCount7d;
        private final int closedCount30d;
        private final BigDecimal averageRoiRate;
        private final BigDecimal medianRoiRate;
        private final BigDecimal totalRealizedPnlUsdc;
        private final BigDecimal knownExitStyleRate;
        private final BigDecimal openedAtCoverageRate;
        private final BigDecimal settlementCompletionRate;
        private final BigDecimal earlyExitRate;
        private final BigDecimal profitEarlyExitRate;
        private final BigDecimal lossEarlyExitRate;
        private final BigDecimal earlySettlementRate;
        private final BigDecimal settlementWinRate;
        private final BigDecimal medianHoldMinutes;
        private final BigDecimal medianInterTradeMinutes;
        private final String dataQuality;

        private static OrderAnalysisStats empty() {
            return OrderAnalysisStats.builder()
                    .sampleSize(0)
                    .dedupOrderCount(0)
                    .dedupOrderCount7d(0)
                    .dedupOrderCount30d(0)
                    .wins(0)
                    .wins7d(0)
                    .wins30d(0)
                    .closedCount7d(0)
                    .closedCount30d(0)
                    .averageRoiRate(BigDecimal.ZERO)
                    .medianRoiRate(BigDecimal.ZERO)
                    .totalRealizedPnlUsdc(BigDecimal.ZERO)
                    .knownExitStyleRate(BigDecimal.ZERO)
                    .openedAtCoverageRate(BigDecimal.ZERO)
                    .settlementCompletionRate(BigDecimal.ZERO)
                    .earlyExitRate(BigDecimal.ZERO)
                    .profitEarlyExitRate(BigDecimal.ZERO)
                    .lossEarlyExitRate(BigDecimal.ZERO)
                    .earlySettlementRate(BigDecimal.ZERO)
                    .settlementWinRate(BigDecimal.ZERO)
                    .medianHoldMinutes(BigDecimal.ZERO)
                    .medianInterTradeMinutes(BigDecimal.ZERO)
                    .dataQuality("LOW")
                    .build();
        }
    }

    private static class ArchetypeDecision {
        private final String archetype;
        private final String reason;
        private final BigDecimal confidence;

        private ArchetypeDecision(String archetype, String reason, BigDecimal confidence) {
            this.archetype = archetype;
            this.reason = reason;
            this.confidence = confidence;
        }
    }
}
