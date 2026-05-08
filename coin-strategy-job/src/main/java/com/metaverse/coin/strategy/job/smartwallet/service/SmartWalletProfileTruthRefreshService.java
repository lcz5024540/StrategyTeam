package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTruthAuditView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTruthRefreshResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class SmartWalletProfileTruthRefreshService {

    private static final BigDecimal TRUSTED_CONFIDENCE = new BigDecimal("0.5500");
    private static final String DATA_QUALITY_TRUTH = "TRUTH_OUTCOME";
    private static final String DATA_QUALITY_INSUFFICIENT = "INSUFFICIENT_TRUTH_DATA";
    private static final String DATA_QUALITY_UNTRUSTED = "UNTRUSTED_PROFILE_WIN_RATE";

    private final SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletTradeOrderMapper tradeOrderMapper;
    private final SmartWalletOrderAnalysisMapper orderAnalysisMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletProfileStoreService profileStoreService;

    public PolymarketWalletMetricsRequest buildTruthMetrics(String walletAddress,
                                                            String alias,
                                                            DiscoveryMetadata discoveryMetadata) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        List<SmartWalletLeaderTradeOutcomeEntity> trustedClosed = trustedClosedOutcomes(walletAddress);
        if (trustedClosed.isEmpty()) {
            return null;
        }
        TruthStats stats = calculateStats(trustedClosed);
        PolymarketWalletMetricsRequest request = new PolymarketWalletMetricsRequest();
        request.setWalletAddress(walletAddress.trim());
        request.setChain("polygon");
        request.setAlias(alias);
        request.setWinRate(stats.winRate());
        request.setRealizedPnlUsdc(scale(stats.pnl, 4));
        request.setTotalTrades(trustedClosed.size());
        request.setAverageSizeUsdc(scale(stats.averageSize(), 4));
        request.setAverageRoiRate(scale(stats.averageRoi(), 4));
        request.setMaxDrawdownRate(scale(stats.maxDrawdown, 4));
        request.setLastTradeAt(stats.lastTradeAt);
        request.setRecentWinRate7d(stats.recentWinRate7d);
        request.setRecentWinRate30d(stats.recentWinRate30d);
        request.setRecentTradeCount7d(stats.recentTradeCount7d);
        request.setRecentTradeCount30d(stats.recentTradeCount30d);
        if (discoveryMetadata != null) {
            request.setLastDiscoveredAt(discoveryMetadata.lastDiscoveredAt());
            request.setLastDiscoveryQuery(discoveryMetadata.lastDiscoveryQuery());
            request.setLeaderboardHitCount30d(discoveryMetadata.leaderboardHitCount30d());
            request.setDominantSession(discoveryMetadata.dominantSession());
            request.setAsiaTradeRatio30d(discoveryMetadata.asiaTradeRatio30d());
            request.setEuTradeRatio30d(discoveryMetadata.euTradeRatio30d());
            request.setUsTradeRatio30d(discoveryMetadata.usTradeRatio30d());
            request.setActiveHourHistogramJson(discoveryMetadata.activeHourHistogramJson());
        }
        return request;
    }

    public SmartWalletProfileTruthRefreshResult refreshProfile(String walletAddress) {
        SmartWalletProfileEntity profile = loadProfile(walletAddress);
        if (profile == null) {
            return new SmartWalletProfileTruthRefreshResult(walletAddress, false, "PROFILE_NOT_FOUND",
                    0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        PolymarketWalletMetricsRequest metrics = buildTruthMetrics(
                profile.getWalletAddress(),
                profile.getAlias(),
                new DiscoveryMetadata(profile.getLastDiscoveredAt(), profile.getLastDiscoveryQuery(),
                        profile.getLeaderboardHitCount30d(), profile.getDominantSession(),
                        profile.getAsiaTradeRatio30d(), profile.getEuTradeRatio30d(), profile.getUsTradeRatio30d(),
                        profile.getActiveHourHistogramJson())
        );
        int lowConfidence = countByStatus(profile.getWalletAddress(), LeaderTradeOutcomeRebuildService.STATUS_LOW_CONFIDENCE);
        int unresolved = countByStatus(profile.getWalletAddress(), LeaderTradeOutcomeRebuildService.STATUS_UNRESOLVED);
        int missingLoss = missingLossCount(profile.getWalletAddress());
        if (metrics == null || metrics.getTotalTrades() == null || metrics.getTotalTrades() <= 0) {
            profile.setObserveCooldownReason(appendReason(profile.getObserveCooldownReason(), DATA_QUALITY_INSUFFICIENT));
            profile.setUpdatedAt(new Date());
            profileStoreService.applyQualificationState(profile);
            profileMapper.updateById(profile);
            return new SmartWalletProfileTruthRefreshResult(profile.getWalletAddress(), false, DATA_QUALITY_INSUFFICIENT,
                    0, lowConfidence, unresolved, missingLoss, BigDecimal.ZERO, BigDecimal.ZERO, profile.getWalletScore());
        }
        boolean untrustedOldProfile = isUntrustedOldProfile(profile, missingLoss);
        applyMetrics(profile, metrics);
        if (untrustedOldProfile) {
            profile.setObserveCooldownReason(appendReason(profile.getObserveCooldownReason(), DATA_QUALITY_UNTRUSTED));
        } else {
            profile.setObserveCooldownReason(removeReason(profile.getObserveCooldownReason(), DATA_QUALITY_UNTRUSTED));
        }
        profile.setUpdatedAt(new Date());
        profileStoreService.applyQualificationState(profile);
        profileMapper.updateById(profile);
        return new SmartWalletProfileTruthRefreshResult(profile.getWalletAddress(), true, DATA_QUALITY_TRUTH,
                metrics.getTotalTrades(), lowConfidence, unresolved, missingLoss,
                metrics.getWinRate(), metrics.getRealizedPnlUsdc(), profile.getWalletScore());
    }

    public List<SmartWalletProfileTruthRefreshResult> refreshProfiles(Integer limit) {
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        List<SmartWalletProfileEntity> profiles = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, "POLYMARKET")
                .and(q -> q.eq(SmartWalletProfileEntity::getWatchEnabled, true)
                        .or().eq(SmartWalletProfileEntity::getQualified, true)
                        .or().in(SmartWalletProfileEntity::getMonitorTier, List.of("HOT", "WARM")))
                .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                .last("limit " + safeLimit));
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }
        List<SmartWalletProfileTruthRefreshResult> results = new ArrayList<>();
        for (SmartWalletProfileEntity profile : profiles) {
            if (profile != null && StringUtils.hasText(profile.getWalletAddress())) {
                results.add(refreshProfile(profile.getWalletAddress()));
            }
        }
        return results;
    }

    public List<SmartWalletProfileTruthAuditView> audit(String walletAddress) {
        List<SmartWalletProfileEntity> profiles;
        if (StringUtils.hasText(walletAddress)) {
            SmartWalletProfileEntity profile = loadProfile(walletAddress);
            profiles = profile == null ? List.of() : List.of(profile);
        } else {
            profiles = new ArrayList<>();
            final int pageSize = 1000;
            long lastId = 0L;
            while (true) {
                List<SmartWalletProfileEntity> chunk = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, "POLYMARKET")
                        .gt(SmartWalletProfileEntity::getId, lastId)
                        .orderByAsc(SmartWalletProfileEntity::getId)
                        .last("limit " + pageSize));
                if (chunk == null || chunk.isEmpty()) {
                    break;
                }
                profiles.addAll(chunk);
                for (SmartWalletProfileEntity row : chunk) {
                    if (row != null && row.getId() != null && row.getId() > lastId) {
                        lastId = row.getId();
                    }
                }
                if (chunk.size() < pageSize) {
                    break;
                }
            }
            profiles.sort(Comparator.comparing(SmartWalletProfileEntity::getWalletScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        }
        return profiles.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::auditProfile)
                .toList();
    }

    private SmartWalletProfileTruthAuditView auditProfile(SmartWalletProfileEntity profile) {
        List<SmartWalletLeaderTradeOutcomeEntity> trustedClosed = trustedClosedOutcomes(profile.getWalletAddress());
        TruthStats stats = calculateStats(trustedClosed);
        long lowConfidence = countByStatus(profile.getWalletAddress(), LeaderTradeOutcomeRebuildService.STATUS_LOW_CONFIDENCE);
        long unresolved = countByStatus(profile.getWalletAddress(), LeaderTradeOutcomeRebuildService.STATUS_UNRESOLVED);
        long missingLoss = missingLossCount(profile.getWalletAddress());
        String quality = trustedClosed.isEmpty()
                ? DATA_QUALITY_INSUFFICIENT
                : isUntrustedOldProfile(profile, (int) missingLoss) ? DATA_QUALITY_UNTRUSTED : DATA_QUALITY_TRUTH;
        return new SmartWalletProfileTruthAuditView(
                profile.getWalletAddress(),
                profile.getTotalTrades(),
                profile.getWinRate(),
                trustedClosed.size(),
                lowConfidence,
                unresolved,
                missingLoss,
                stats.winRate(),
                scale(stats.pnl, 4),
                quality,
                recommendation(quality, trustedClosed.size(), stats.winRate(), stats.pnl)
        );
    }

    private void applyMetrics(SmartWalletProfileEntity profile, PolymarketWalletMetricsRequest metrics) {
        profile.setWinRate(safe(metrics.getWinRate()));
        profile.setRealizedPnlUsdc(safe(metrics.getRealizedPnlUsdc()));
        profile.setTotalTrades(metrics.getTotalTrades() == null ? 0 : metrics.getTotalTrades());
        profile.setAverageSizeUsdc(safe(metrics.getAverageSizeUsdc()));
        profile.setAverageRoiRate(safe(metrics.getAverageRoiRate()));
        profile.setMaxDrawdownRate(safe(metrics.getMaxDrawdownRate()));
        profile.setRecentWinRate7d(metrics.getRecentWinRate7d());
        profile.setRecentWinRate30d(metrics.getRecentWinRate30d());
        profile.setRecentTradeCount7d(metrics.getRecentTradeCount7d());
        profile.setRecentTradeCount30d(metrics.getRecentTradeCount30d());
        profile.setLastTradeAt(metrics.getLastTradeAt());
        profile.setWalletScore(scoringService.score(
                profile.getWinRate(),
                profile.getRecentWinRate7d(),
                profile.getRecentWinRate30d(),
                profile.getRealizedPnlUsdc(),
                profile.getTotalTrades(),
                profile.getRecentTradeCount7d(),
                profile.getRecentTradeCount30d(),
                profile.getAverageRoiRate(),
                profile.getMaxDrawdownRate()
        ));
    }

    private List<SmartWalletLeaderTradeOutcomeEntity> trustedClosedOutcomes(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        return leaderOutcomeMapper.selectList(new LambdaQueryWrapper<SmartWalletLeaderTradeOutcomeEntity>()
                        .eq(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, walletAddress.trim())
                        .in(SmartWalletLeaderTradeOutcomeEntity::getOutcomeStatus, List.of(
                                LeaderTradeOutcomeRebuildService.STATUS_CLOSED_WIN,
                                LeaderTradeOutcomeRebuildService.STATUS_CLOSED_LOSS,
                                LeaderTradeOutcomeRebuildService.STATUS_CLOSED_BREAKEVEN))
                        .ge(SmartWalletLeaderTradeOutcomeEntity::getSourceConfidence, TRUSTED_CONFIDENCE)
                        .orderByAsc(SmartWalletLeaderTradeOutcomeEntity::getLastTradeAt))
                .stream()
                .filter(row -> row != null)
                .filter(row -> row.getEstimatedRealizedPnlUsdc() != null)
                .toList();
    }

    private TruthStats calculateStats(List<SmartWalletLeaderTradeOutcomeEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return TruthStats.empty();
        }
        long now = System.currentTimeMillis();
        long cutoff7d = now - 7L * 24L * 3600_000L;
        long cutoff30d = now - 30L * 24L * 3600_000L;
        TruthStats stats = new TruthStats();
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        for (SmartWalletLeaderTradeOutcomeEntity row : rows.stream()
                .sorted(Comparator.comparing(SmartWalletLeaderTradeOutcomeEntity::getLastTradeAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()) {
            BigDecimal pnl = safe(row.getEstimatedRealizedPnlUsdc());
            BigDecimal cost = safe(row.getBuyCostUsdc());
            stats.pnl = stats.pnl.add(pnl);
            stats.cost = stats.cost.add(cost);
            stats.roiSum = stats.roiSum.add(safe(row.getEstimatedRoiRate()));
            stats.roiCount++;
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                stats.wins++;
            }
            if (row.getLastTradeAt() != null) {
                stats.lastTradeAt = row.getLastTradeAt();
                long t = row.getLastTradeAt().getTime();
                if (t >= cutoff7d) {
                    stats.recentTradeCount7d++;
                    if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                        stats.recentWins7d++;
                    }
                }
                if (t >= cutoff30d) {
                    stats.recentTradeCount30d++;
                    if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                        stats.recentWins30d++;
                    }
                }
            }
            equity = equity.add(pnl);
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal denominator = peak.max(BigDecimal.ONE);
            BigDecimal drawdown = peak.subtract(equity).max(BigDecimal.ZERO)
                    .divide(denominator, 6, RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE);
            if (drawdown.compareTo(stats.maxDrawdown) > 0) {
                stats.maxDrawdown = drawdown;
            }
        }
        stats.total = rows.size();
        stats.recentWinRate7d = stats.recentTradeCount7d == 0 ? null : rate(stats.recentWins7d, stats.recentTradeCount7d);
        stats.recentWinRate30d = stats.recentTradeCount30d == 0 ? null : rate(stats.recentWins30d, stats.recentTradeCount30d);
        return stats;
    }

    private SmartWalletProfileEntity loadProfile(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        return profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletProfileEntity::getSource, "POLYMARKET")
                .last("limit 1"));
    }

    private int countByStatus(String walletAddress, String status) {
        Long count = leaderOutcomeMapper.selectCount(new LambdaQueryWrapper<SmartWalletLeaderTradeOutcomeEntity>()
                .eq(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, walletAddress)
                .eq(SmartWalletLeaderTradeOutcomeEntity::getOutcomeStatus, status));
        return count == null ? 0 : count.intValue();
    }

    private int missingLossCount(String walletAddress) {
        List<SmartWalletTradeOrderEntity> losses = tradeOrderMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeOrderEntity>()
                .eq(SmartWalletTradeOrderEntity::getWalletAddress, walletAddress)
                .ne(SmartWalletTradeOrderEntity::getStatus, "OPEN")
                .lt(SmartWalletTradeOrderEntity::getTotalPnlUsdc, BigDecimal.ZERO));
        if (losses.isEmpty()) {
            return 0;
        }
        marketInfoService.populateTradeOrders(losses);
        Map<String, SmartWalletOrderAnalysisEntity> oldRowsByKey = orderAnalysisMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                        .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, walletAddress))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> key(row.getMarketId(), row.getOutcome()),
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        int missing = 0;
        for (SmartWalletTradeOrderEntity loss : losses) {
            if (!oldRowsByKey.containsKey(key(loss.getMarketId(), loss.getOutcome()))) {
                missing++;
            }
        }
        return missing;
    }

    private boolean isUntrustedOldProfile(SmartWalletProfileEntity profile, int missingLossCount) {
        return profile != null
                && safe(profile.getWinRate()).compareTo(new BigDecimal("0.9999")) >= 0
                && missingLossCount > 0;
    }

    private String appendReason(String current, String reason) {
        if (!StringUtils.hasText(reason)) {
            return current;
        }
        if (!StringUtils.hasText(current)) {
            return reason;
        }
        return current.contains(reason) ? current : current + "|" + reason;
    }

    private String removeReason(String current, String reason) {
        if (!StringUtils.hasText(current) || !StringUtils.hasText(reason)) {
            return current;
        }
        return String.join("|", java.util.Arrays.stream(current.split("\\|"))
                .filter(item -> !reason.equals(item))
                .filter(StringUtils::hasText)
                .toList());
    }

    private String recommendation(String quality, long sampleCount, BigDecimal winRate, BigDecimal pnl) {
        if (!DATA_QUALITY_TRUTH.equals(quality) || sampleCount < 20) {
            return "OBSERVE_ONLY";
        }
        if (safe(winRate).compareTo(new BigDecimal("0.55")) >= 0 && safe(pnl).compareTo(BigDecimal.ZERO) > 0) {
            return "TRUTH_ELIGIBLE";
        }
        return "DO_NOT_EXPAND";
    }

    private String key(String marketId, String outcome) {
        return (marketId == null ? "" : marketId.trim()) + "|" + (outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT));
    }

    private BigDecimal rate(long wins, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record DiscoveryMetadata(Date lastDiscoveredAt,
                                    String lastDiscoveryQuery,
                                    Integer leaderboardHitCount30d,
                                    String dominantSession,
                                    BigDecimal asiaTradeRatio30d,
                                    BigDecimal euTradeRatio30d,
                                    BigDecimal usTradeRatio30d,
                                    String activeHourHistogramJson) {
    }

    private static class TruthStats {
        private int total;
        private long wins;
        private BigDecimal pnl = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal roiSum = BigDecimal.ZERO;
        private int roiCount;
        private BigDecimal maxDrawdown = BigDecimal.ZERO;
        private Date lastTradeAt;
        private int recentTradeCount7d;
        private int recentTradeCount30d;
        private int recentWins7d;
        private int recentWins30d;
        private BigDecimal recentWinRate7d;
        private BigDecimal recentWinRate30d;

        private static TruthStats empty() {
            return new TruthStats();
        }

        private BigDecimal winRate() {
            if (total <= 0) {
                return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        }

        private BigDecimal averageSize() {
            if (total <= 0) {
                return BigDecimal.ZERO;
            }
            return cost.divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP);
        }

        private BigDecimal averageRoi() {
            if (roiCount <= 0) {
                return BigDecimal.ZERO;
            }
            return roiSum.divide(BigDecimal.valueOf(roiCount), 8, RoundingMode.HALF_UP);
        }
    }
}
