package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletDiagnosisView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletScreenerView;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WalletProfileAnalysisService {

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final SmartWalletOrderAnalysisService orderAnalysisService;

    public CursorPageView<WalletScreenerView> screen(List<SmartWalletProfileEntity> profiles,
                                                     Integer limit,
                                                     String cursor,
                                                     String sortBy,
                                                     String sortDirection,
                                                     String screenerTier,
                                                     Boolean highQualityAddress,
                                                     Boolean highFrequencyTradingAddress,
                                                     String winSourceType,
                                                     String recommendedExitLane,
                                                     BigDecimal minQualityScore,
                                                     BigDecimal minReplicabilityScore) {
        if (profiles == null || profiles.isEmpty()) {
            return CursorPageView.empty();
        }
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        int start = parseCursor(cursor);
        Comparator<WalletScreenerView> comparator = screenerComparator(sortBy, sortDirection);
        String normalizedTier = normalize(screenerTier);
        String normalizedWinSourceType = normalize(winSourceType);
        String normalizedExitLane = normalize(recommendedExitLane);

        List<WalletScreenerView> filtered = profiles.stream()
                .map(this::buildScreenerView)
                .filter(view -> normalizedTier == null || normalizedTier.equals(normalize(view.screenerTier())))
                .filter(view -> highQualityAddress == null || highQualityAddress.equals(Boolean.TRUE.equals(view.highQualityAddress())))
                .filter(view -> highFrequencyTradingAddress == null
                        || highFrequencyTradingAddress.equals(Boolean.TRUE.equals(view.highFrequencyTradingAddress())))
                .filter(view -> normalizedWinSourceType == null || normalizedWinSourceType.equals(normalize(view.winSourceType())))
                .filter(view -> normalizedExitLane == null || normalizedExitLane.equals(normalize(view.recommendedExitLane())))
                .filter(view -> minQualityScore == null || safe(view.qualityScore()).compareTo(minQualityScore) >= 0)
                .filter(view -> minReplicabilityScore == null || safe(view.replicabilityScore()).compareTo(minReplicabilityScore) >= 0)
                .sorted(comparator)
                .toList();

        if (start >= filtered.size()) {
            return CursorPageView.empty();
        }
        int end = Math.min(filtered.size(), start + safeLimit);
        List<WalletScreenerView> page = filtered.subList(start, end);
        String nextCursor = end < filtered.size() ? String.valueOf(end) : null;
        return CursorPageView.of(page, nextCursor);
    }

    public WalletDiagnosisView diagnose(String walletAddress, boolean refreshOrderAnalysis) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        SmartWalletProfileEntity profile = profileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                        .eq(SmartWalletProfileEntity::getSource, "POLYMARKET")
                        .last("limit 1"));
        if (profile == null) {
            return null;
        }
        WalletBehaviorProfileView behavior = walletBehaviorProfileService.buildProfile(profile);
        List<SmartWalletOrderAnalysisEntity> orders = orderAnalysisService.listByWalletAddress(walletAddress, refreshOrderAnalysis, null, null);
        WalletScreenerView screenerView = buildScreenerView(profile, behavior, latestFeature(walletAddress));
        List<String> strengths = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        if (Boolean.TRUE.equals(screenerView.highQualityAddress())) {
            strengths.add("Meets the high-quality address threshold on recency, win rate, sample size, and drawdown.");
        } else {
            risks.add("Does not fully meet the high-quality threshold yet.");
        }
        if (safe(behavior.latencyAlphaRate()).compareTo(new BigDecimal("0.58")) >= 0) {
            strengths.add("Short-window alpha stays positive often enough to support latency-sensitive replication.");
        }
        if (safe(behavior.swingAlphaRate()).compareTo(new BigDecimal("0.58")) >= 0) {
            strengths.add("Medium-window alpha remains stable after entry, which supports slower replication.");
        }
        if (safe(behavior.settlementWinRate()).compareTo(new BigDecimal("0.60")) >= 0) {
            strengths.add("Hold-to-settlement trades still win at a healthy rate.");
        }
        if (safe(behavior.stabilityScore()).compareTo(new BigDecimal("0.55")) < 0) {
            risks.add("Win rate and payout consistency are still noisy.");
        }
        if (safe(screenerView.freshnessScore()).compareTo(new BigDecimal("0.60")) < 0) {
            risks.add("Recent activity is fading, so the signal may be stale.");
        }
        if ("FAST_EXIT_OR_SKIP".equalsIgnoreCase(safeString(behavior.recommendedExitLane()))) {
            risks.add("Execution needs to be very fast or skipped entirely because the source exits too quickly.");
        }
        if (orders.stream().noneMatch(order -> "SETTLED_WIN".equalsIgnoreCase(safeString(order.getExitStyle())))) {
            risks.add("The wallet rarely proves edge by waiting until settlement.");
        }

        return WalletDiagnosisView.builder()
                .walletAddress(profile.getWalletAddress())
                .alias(profile.getAlias())
                .archetype(behavior.archetype())
                .followLane(behavior.followLane())
                .screenerTier(screenerView.screenerTier())
                .highQualityAddress(screenerView.highQualityAddress())
                .highFrequencyTradingAddress(behavior.highFrequencyTradingAddress())
                .winSourceType(behavior.winSourceType())
                .qualityScore(scale(behavior.qualityScore(), 4))
                .replicabilityScore(scale(behavior.replicabilityScore(), 4))
                .stabilityScore(scale(behavior.stabilityScore(), 4))
                .settlementWinRate(scale(behavior.settlementWinRate(), 4))
                .latencyAlphaRate(scale(behavior.latencyAlphaRate(), 4))
                .swingAlphaRate(scale(behavior.swingAlphaRate(), 4))
                .medianHoldMinutes(scale(behavior.medianHoldMinutes(), 2))
                .recommendedExitLane(behavior.recommendedExitLane())
                .summary(behavior.summary())
                .strengths(strengths)
                .risks(risks)
                .build();
    }

    public WalletScreenerView buildScreenerView(SmartWalletProfileEntity profile) {
        return buildScreenerView(profile, walletBehaviorProfileService.buildProfile(profile), latestFeature(profile == null ? null : profile.getWalletAddress()));
    }

    private WalletScreenerView buildScreenerView(SmartWalletProfileEntity profile,
                                                 WalletBehaviorProfileView behavior,
                                                 SmartWalletWalletFeatureDailyEntity feature) {
        if (profile == null) {
            return null;
        }
        BigDecimal recentWinRate = safe(firstNonNull(profile.getRecentWinRate30d(), profile.getWinRate()));
        BigDecimal freshnessScore = freshnessScore(profile.getLastTradeAt());
        boolean highQuality = recentWinRate.compareTo(new BigDecimal("0.58")) >= 0
                && defaultInt(behavior == null ? null : behavior.closedPositionSampleSize()) >= 20
                && safe(firstNonNull(feature == null ? null : feature.getPnl30d(), profile.getRealizedPnlUsdc())).compareTo(BigDecimal.ZERO) > 0
                && freshnessScore.compareTo(new BigDecimal("0.85")) >= 0
                && safe(profile.getMaxDrawdownRate()).compareTo(new BigDecimal("0.35")) <= 0;
        String tier = determineTier(profile, behavior, freshnessScore, highQuality);
        return WalletScreenerView.builder()
                .walletAddress(profile.getWalletAddress())
                .alias(profile.getAlias())
                .archetype(behavior == null ? null : behavior.archetype())
                .followLane(behavior == null ? null : behavior.followLane())
                .screenerTier(tier)
                .highQualityAddress(highQuality)
                .highFrequencyTradingAddress(behavior == null ? null : behavior.highFrequencyTradingAddress())
                .winSourceType(behavior == null ? null : behavior.winSourceType())
                .qualityScore(scale(behavior == null ? null : behavior.qualityScore(), 4))
                .replicabilityScore(scale(behavior == null ? null : behavior.replicabilityScore(), 4))
                .stabilityScore(scale(behavior == null ? null : behavior.stabilityScore(), 4))
                .recentWinRate30d(scale(recentWinRate, 4))
                .orderWinRate(scale(behavior == null ? null : behavior.orderWinRate(), 4))
                .averageOrderRoiRate(scale(behavior == null ? null : behavior.averageOrderRoiRate(), 4))
                .medianOrderRoiRate(scale(behavior == null ? null : behavior.medianOrderRoiRate(), 4))
                .settlementWinRate(scale(behavior == null ? null : behavior.settlementWinRate(), 4))
                .followabilityRate(scale(feature == null ? null : feature.getFollowableRate30d(), 4))
                .concentrationMarket30d(scale(feature == null ? null : feature.getConcentrationMarket30d(), 4))
                .freshnessScore(scale(freshnessScore, 4))
                .recommendedExitLane(behavior == null ? null : behavior.recommendedExitLane())
                .lastTradeAt(profile.getLastTradeAt())
                .summary(behavior == null ? null : behavior.summary())
                .build();
    }

    private SmartWalletWalletFeatureDailyEntity latestFeature(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        return walletFeatureDailyMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                .eq(SmartWalletWalletFeatureDailyEntity::getWalletAddress, walletAddress.trim())
                .orderByDesc(SmartWalletWalletFeatureDailyEntity::getFeatureDate)
                .last("limit 1"));
    }

    private String determineTier(SmartWalletProfileEntity profile,
                                 WalletBehaviorProfileView behavior,
                                 BigDecimal freshnessScore,
                                 boolean highQuality) {
        BigDecimal qualityScore = safe(behavior == null ? null : behavior.qualityScore());
        BigDecimal replicabilityScore = safe(behavior == null ? null : behavior.replicabilityScore());
        if (safe(profile == null ? null : profile.getMaxDrawdownRate()).compareTo(new BigDecimal("0.45")) > 0
                || freshnessScore.compareTo(new BigDecimal("0.20")) <= 0
                || qualityScore.compareTo(new BigDecimal("0.30")) < 0) {
            return "BLOCKLIST";
        }
        if (highQuality
                && qualityScore.compareTo(new BigDecimal("0.72")) >= 0
                && replicabilityScore.compareTo(new BigDecimal("0.60")) >= 0
                && !"FAST_EXIT_OR_SKIP".equalsIgnoreCase(safeString(behavior == null ? null : behavior.recommendedExitLane()))) {
            return "EXECUTABLE_A";
        }
        if (qualityScore.compareTo(new BigDecimal("0.60")) >= 0
                && replicabilityScore.compareTo(new BigDecimal("0.45")) >= 0
                && freshnessScore.compareTo(new BigDecimal("0.50")) >= 0) {
            return "EXECUTABLE_B";
        }
        return "OBSERVE";
    }

    private Comparator<WalletScreenerView> screenerComparator(String sortBy, String sortDirection) {
        Comparator<WalletScreenerView> comparator = switch (normalize(sortBy)) {
            case "RECENTWINRATE30D" -> Comparator.comparing(WalletScreenerView::recentWinRate30d, Comparator.nullsLast(Comparator.naturalOrder()));
            case "ORDERWINRATE" -> Comparator.comparing(WalletScreenerView::orderWinRate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "LASTTRADEAT" -> Comparator.comparing(WalletScreenerView::lastTradeAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "QUALITYSCORE" -> Comparator.comparing(WalletScreenerView::qualityScore, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(WalletScreenerView::replicabilityScore, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (!"ASC".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }
        return comparator.thenComparing(WalletScreenerView::walletAddress, Comparator.nullsLast(String::compareTo));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().replace("_", "").toUpperCase(Locale.ROOT) : null;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
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

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal secondary) {
        return primary != null ? primary : secondary;
    }

    private int parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
