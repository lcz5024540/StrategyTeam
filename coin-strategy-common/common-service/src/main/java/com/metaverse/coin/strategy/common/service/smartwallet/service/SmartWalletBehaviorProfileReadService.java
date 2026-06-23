package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 4.2：读取 {@link SmartWalletBehaviorProfileEntity} 表，供 web 端 watchlist
 * archetypes / behavior-profiles GET 端点直接用。
 *
 * <p>表数据由 job 端 {@code BehaviorProfileSnapshotJob} 定期 @Scheduled 写入
 * （默认 10 分钟一次）。web 读到的是延迟 N 分钟的快照——业务可接受。</p>
 *
 * <p>scope 过滤复用 {@link SmartWalletProfileStoreReadService} 的 listAll/Executable/Warm
 * 等方法，确保跟 job 端语义一致。</p>
 */
@Service
@RequiredArgsConstructor
public class SmartWalletBehaviorProfileReadService {

    private static final String SOURCE = "POLYMARKET";

    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;
    private final SmartWalletProfileStoreReadService profileStoreReadService;

    /**
     * 按 scope 列出钱包的 archetype 视图（依赖 behavior_profile 表中 archetype/followLane 等字段）。
     *
     * <p>语义差异：跟 job 端 {@code buildWatchlistArchetypes} 现算结果相比，本路径只读表，
     * 部分动态字段（leaderQualityScore、followFitScore、recommendedSyncIntervalMs、alpha30s 等）
     * 表中没有持久化，会以 null 返回。前端需关注的核心字段（archetype、followLane、
     * recommendedExitLane、trades7d、trades30d、orderWinRate、medianInterTradeMinutes 等）全部可用。</p>
     */
    public List<WalletArchetypeView> listArchetypes(String scope, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        List<SmartWalletProfileEntity> scoped = resolveWatchlistScope(scope);
        if (scoped.isEmpty()) {
            return List.of();
        }
        Set<String> addresses = scoped.stream()
                .map(SmartWalletProfileEntity::getWalletAddress)
                .filter(StringUtils::hasText)
                .limit(safeLimit)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, SmartWalletBehaviorProfileEntity> byAddress = loadByAddresses(addresses);
        return scoped.stream()
                .limit(safeLimit)
                .map(profile -> toArchetypeView(profile, byAddress.get(profile.getWalletAddress())))
                .toList();
    }

    public List<WalletBehaviorProfileView> listBehaviorProfiles(String scope, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 30 : limit, 200));
        List<SmartWalletProfileEntity> scoped = resolveWatchlistScope(scope);
        if (scoped.isEmpty()) {
            return List.of();
        }
        Set<String> addresses = scoped.stream()
                .map(SmartWalletProfileEntity::getWalletAddress)
                .filter(StringUtils::hasText)
                .limit(safeLimit)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, SmartWalletBehaviorProfileEntity> byAddress = loadByAddresses(addresses);
        return scoped.stream()
                .limit(safeLimit)
                .map(profile -> byAddress.get(profile.getWalletAddress()))
                .filter(java.util.Objects::nonNull)
                .map(this::toBehaviorView)
                .sorted(Comparator.comparing(WalletBehaviorProfileView::replicabilityScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private List<SmartWalletProfileEntity> resolveWatchlistScope(String scope) {
        String normalized = scope == null ? "candidates" : scope.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "all" -> profileStoreReadService.listAll();
            case "warm" -> profileStoreReadService.listWarmWatchlist();
            case "executable" -> profileStoreReadService.listExecutableWatchlist();
            default -> profileStoreReadService.listQualified();
        };
    }

    private Map<String, SmartWalletBehaviorProfileEntity> loadByAddresses(Set<String> addresses) {
        if (addresses.isEmpty()) {
            return Map.of();
        }
        List<SmartWalletBehaviorProfileEntity> rows = behaviorProfileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                        .eq(SmartWalletBehaviorProfileEntity::getSource, SOURCE)
                        .in(SmartWalletBehaviorProfileEntity::getWalletAddress, addresses));
        Map<String, SmartWalletBehaviorProfileEntity> result = new HashMap<>();
        for (SmartWalletBehaviorProfileEntity row : rows) {
            if (row != null && StringUtils.hasText(row.getWalletAddress())) {
                result.put(row.getWalletAddress(), row);
            }
        }
        return result;
    }

    private WalletArchetypeView toArchetypeView(SmartWalletProfileEntity profile,
                                                SmartWalletBehaviorProfileEntity entity) {
        // entity 可能为 null（表中尚无快照），返回最低限度信息
        return WalletArchetypeView.builder()
                .walletAddress(profile.getWalletAddress())
                .archetype(entity == null ? null : entity.getArchetype())
                .followLane(entity == null ? null : entity.getFollowLane())
                .archetypeReason(null) // entity 表无此字段（动态计算字段，仅 buildProfile 现算可得）
                .archetypeConfidence(null)
                .confidence(null)
                .leaderQualityScore(null)
                .followFitScore(null)
                .recommendedSyncIntervalMs(null)
                .labelSampleSize(null)
                .trades7d(entity == null ? null : entity.getTrades7d())
                .trades30d(entity == null ? null : entity.getTrades30d())
                .dedupOrderCount7d(null)
                .dedupOrderCount30d(null)
                .markets30d(null)
                .concentrationMarket30d(null)
                .followableRate30d(null)
                .alpha30s(null)
                .alpha5m(null)
                .alphaRule(null)
                .medianInterTradeMinutes(entity == null ? null : entity.getMedianInterTradeMinutes())
                .medianHoldMinutes(entity == null ? null : entity.getMedianHoldMinutes())
                .dataQuality(null)
                .highFrequencyTradingAddress(entity == null ? null : entity.getHighFrequencyTradingAddress())
                .closedPositionSampleSize(entity == null ? null : entity.getClosedPositionSampleSize())
                .orderWinRate(entity == null ? null : entity.getOrderWinRate())
                .averageOrderRoiRate(entity == null ? null : entity.getAverageOrderRoiRate())
                .medianOrderRoiRate(entity == null ? null : entity.getMedianOrderRoiRate())
                .settlementCompletionRate(entity == null ? null : entity.getSettlementCompletionRate())
                .earlyExitRate(entity == null ? null : entity.getEarlyExitRate())
                .profitEarlyExitRate(entity == null ? null : entity.getProfitEarlyExitRate())
                .lossEarlyExitRate(entity == null ? null : entity.getLossEarlyExitRate())
                .earlySettlementRate(entity == null ? null : entity.getEarlySettlementRate())
                .winSourceType(entity == null ? null : entity.getWinSourceType())
                .settlementWinRate(entity == null ? null : entity.getSettlementWinRate())
                .stabilityScore(entity == null ? null : entity.getStabilityScore())
                .qualityScore(entity == null ? null : entity.getQualityScore())
                .replicabilityScore(entity == null ? null : entity.getReplicabilityScore())
                .recommendedExitLane(entity == null ? null : entity.getRecommendedExitLane())
                .summary(entity == null ? null : entity.getSummary())
                .build();
    }

    private WalletBehaviorProfileView toBehaviorView(SmartWalletBehaviorProfileEntity entity) {
        // entity 不可为 null（前置 filter 已挡）
        return WalletBehaviorProfileView.builder()
                .walletAddress(entity.getWalletAddress())
                .alias(entity.getAlias())
                .archetype(entity.getArchetype())
                .followLane(entity.getFollowLane())
                .archetypeReason(null)         // 表无字段
                .archetypeConfidence(null)
                .highFrequencyTradingAddress(entity.getHighFrequencyTradingAddress())
                .trades7d(entity.getTrades7d())
                .trades30d(entity.getTrades30d())
                .dedupOrderCount7d(null)       // 表无字段
                .dedupOrderCount30d(null)
                .winRate7d(null)               // 表无字段
                .winRate30d(null)
                .medianInterTradeMinutes(entity.getMedianInterTradeMinutes())
                .medianHoldMinutes(entity.getMedianHoldMinutes())
                .dataQuality(null)             // 表无字段
                .closedPositionSampleSize(entity.getClosedPositionSampleSize())
                .orderWinRate(entity.getOrderWinRate())
                .averageOrderRoiRate(entity.getAverageOrderRoiRate())
                .medianOrderRoiRate(entity.getMedianOrderRoiRate())
                .totalRealizedPnlUsdc(entity.getTotalRealizedPnlUsdc())
                .settlementCompletionRate(entity.getSettlementCompletionRate())
                .earlyExitRate(entity.getEarlyExitRate())
                .profitEarlyExitRate(entity.getProfitEarlyExitRate())
                .lossEarlyExitRate(entity.getLossEarlyExitRate())
                .earlySettlementRate(entity.getEarlySettlementRate())
                .winSourceType(entity.getWinSourceType())
                .latencyAlphaRate(entity.getLatencyAlphaRate())
                .swingAlphaRate(entity.getSwingAlphaRate())
                .settlementWinRate(entity.getSettlementWinRate())
                .stabilityScore(entity.getStabilityScore())
                .qualityScore(entity.getQualityScore())
                .replicabilityScore(entity.getReplicabilityScore())
                .scoreConfidence(null)         // 表无字段
                .behaviorComposite(null)
                .recommendedExitLane(entity.getRecommendedExitLane())
                .summary(entity.getSummary())
                .computedAt(entity.getComputedAt())
                .build();
    }

    @SuppressWarnings("unused")
    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
