package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfileStaleDiagnosticView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfileTierDiagnosticsSummaryView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Phase 4.3：HOT/WARM/COLD 分层诊断 read service。
 *
 * <p>派生 {@code firstHotBlockReason} 时用 {@link SmartWalletBehaviorProfileEntity}
 * 表数据替代 job 端 {@code walletBehaviorProfileService.getProfile()} 现算路径。</p>
 *
 * <p>语义差异：</p>
 * <ul>
 *   <li>{@code OBSERVE_ONLY_ARCHETYPE} / {@code FAST_EXIT_OR_SKIP} 判断基于 behavior_profile 表，
 *       延迟取决于 {@code BehaviorProfileSnapshotJob} 的快照间隔（默认 10min）。</li>
 *   <li>{@code LOW_FOLLOW_FIT} 判断的依据 {@code followFitScore} 不在 behavior_profile 表，
 *       本路径不会输出该原因（不会误报，但失去对该原因的覆盖）。</li>
 *   <li>其他原因（MANUAL_*、METRICS_STALE、STALE_LAST_TRADE_GT_*、OBSERVE_COOLDOWN、
 *       *_BELOW_THRESHOLD、NOT_WATCH_ENABLED）与 job 端完全一致。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SmartWalletProfileTierDiagnosticReadService {

    private static final String SOURCE = "POLYMARKET";

    private final SmartWalletProperties properties;
    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;
    private final SmartWalletManualAllowlistService manualAllowlistService;
    private final SmartWalletProfileStoreReadService profileStoreReadService;

    public List<SmartWalletProfileStaleDiagnosticView> listProfileTierDiagnostics(Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        List<SmartWalletProfileEntity> rows = profileStoreReadService.listAll().stream()
                .limit(safeLimit)
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, SmartWalletBehaviorProfileEntity> behaviorByAddr = loadBehavior(rows);
        return rows.stream()
                .map(profile -> toStaleDiagnosticView(profile, behaviorByAddr.get(profile.getWalletAddress())))
                .toList();
    }

    public List<SmartWalletProfileTierDiagnosticsSummaryView> summarizeProfileTierDiagnostics() {
        List<SmartWalletProfileEntity> rows = profileStoreReadService.listAll();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, SmartWalletBehaviorProfileEntity> behaviorByAddr = loadBehavior(rows);
        LinkedHashMap<String, List<SmartWalletProfileStaleDiagnosticView>> byTier = new LinkedHashMap<>();
        for (SmartWalletProfileEntity profile : rows) {
            SmartWalletProfileStaleDiagnosticView view = toStaleDiagnosticView(
                    profile, behaviorByAddr.get(profile.getWalletAddress()));
            byTier.computeIfAbsent(firstText(view.monitorTier(), "UNKNOWN"), ignored -> new ArrayList<>()).add(view);
        }
        return byTier.entrySet().stream()
                .map(entry -> summarizeTier(entry.getKey(), entry.getValue()))
                .toList();
    }

    // ─────────────────────────────── 派生 view ───────────────────────────────

    private SmartWalletProfileStaleDiagnosticView toStaleDiagnosticView(SmartWalletProfileEntity profile,
                                                                        SmartWalletBehaviorProfileEntity behavior) {
        boolean allowlistActive = manualAllowlistService.isActive(profile == null ? null : profile.getWalletAddress());
        boolean metricsStale = isMetricsStale(profile);
        boolean eventSyncStale = isEventSyncStale(profile);
        return SmartWalletProfileStaleDiagnosticView.builder()
                .walletAddress(profile.getWalletAddress())
                .alias(profile.getAlias())
                .qualified(profile.getQualified())
                .watchEnabled(profile.getWatchEnabled())
                .manualWatchEnabled(profile.getManualWatchEnabled())
                .manualAllowlistActive(allowlistActive)
                .monitorTier(profile.getMonitorTier())
                .monitorReason(profile.getMonitorReason())
                .winRate(profile.getWinRate())
                .walletScore(profile.getWalletScore())
                .totalTrades(profile.getTotalTrades())
                .realizedPnlUsdc(profile.getRealizedPnlUsdc())
                .maxDrawdownRate(profile.getMaxDrawdownRate())
                .lastMetricsRefreshAt(profile.getLastMetricsRefreshAt())
                .lastEventSyncAt(profile.getLastEventSyncAt())
                .lastTradeAt(profile.getLastTradeAt())
                .lastDiscoveredAt(profile.getLastDiscoveredAt())
                .metricsStale(metricsStale)
                .eventSyncStale(eventSyncStale)
                .tierDecisionReason(profile.getMonitorReason())
                .firstHotBlockReason(firstHotBlockReason(profile, metricsStale, allowlistActive, behavior))
                .build();
    }

    private SmartWalletProfileTierDiagnosticsSummaryView summarizeTier(String tier,
                                                                       List<SmartWalletProfileStaleDiagnosticView> rows) {
        Date earliestRefresh = null;
        Date latestRefresh = null;
        int missingRefresh = 0;
        int staleMetrics = 0;
        int staleEvents = 0;
        LinkedHashMap<String, Integer> blockCounts = new LinkedHashMap<>();
        for (SmartWalletProfileStaleDiagnosticView row : rows) {
            if (row.lastMetricsRefreshAt() == null) {
                missingRefresh++;
            } else {
                earliestRefresh = earliest(earliestRefresh, row.lastMetricsRefreshAt());
                latestRefresh = latest(latestRefresh, row.lastMetricsRefreshAt());
            }
            if (Boolean.TRUE.equals(row.metricsStale())) {
                staleMetrics++;
            }
            if (Boolean.TRUE.equals(row.eventSyncStale())) {
                staleEvents++;
            }
            blockCounts.merge(firstText(row.firstHotBlockReason(), "NONE"), 1, Integer::sum);
        }
        return SmartWalletProfileTierDiagnosticsSummaryView.builder()
                .monitorTier(tier)
                .walletCount(rows.size())
                .metricsMissingCount(missingRefresh)
                .metricsStaleCount(staleMetrics)
                .eventSyncStaleCount(staleEvents)
                .earliestMetricsRefreshAt(earliestRefresh)
                .latestMetricsRefreshAt(latestRefresh)
                .hotBlockReasonCounts(blockCounts)
                .build();
    }

    private String firstHotBlockReason(SmartWalletProfileEntity wallet,
                                       boolean metricsStale,
                                       boolean allowlistActive,
                                       SmartWalletBehaviorProfileEntity behavior) {
        if (wallet == null) {
            return "MISSING_WALLET";
        }
        if (Boolean.FALSE.equals(wallet.getManualWatchEnabled())) {
            return "MANUAL_WATCH_DISABLED";
        }
        if (allowlistActive) {
            return "MANUAL_ALLOWLIST_DEFAULT_FOLLOW";
        }
        if (metricsStale) {
            return "METRICS_STALE";
        }
        Date now = new Date();
        if (isForceColdByInactivity(wallet, now)) {
            return "STALE_LAST_TRADE_GT_" + resolveColdWalletInactivityDays() + "D";
        }
        if (isWarmByInactivity(wallet, now)) {
            return "STALE_LAST_TRADE_GT_" + resolveWarmWalletInactivityDays() + "D";
        }
        if (profileStoreReadService.isObserveCooldownActive(wallet)) {
            return "OBSERVE_COOLDOWN";
        }
        String qualificationBlock = firstQualificationBlockReason(wallet);
        if (qualificationBlock != null) {
            return qualificationBlock;
        }
        // 派生 archetype/behavior 判断（依赖 behavior_profile 表数据，可能为 null）
        if (behavior != null) {
            if (StringUtils.hasText(behavior.getRecommendedExitLane())
                    && "FAST_EXIT_OR_SKIP".equalsIgnoreCase(behavior.getRecommendedExitLane())) {
                return "FAST_EXIT_OR_SKIP";
            }
            if (StringUtils.hasText(behavior.getFollowLane())
                    && "OBSERVE_ONLY".equalsIgnoreCase(behavior.getFollowLane())) {
                return "OBSERVE_ONLY_ARCHETYPE";
            }
        }
        // 注意：LOW_FOLLOW_FIT 依赖动态计算的 followFitScore，behavior_profile 表无此字段，跳过
        if (Boolean.TRUE.equals(wallet.getWatchEnabled())
                && "HOT".equalsIgnoreCase(wallet.getMonitorTier())) {
            return null;
        }
        return "NOT_WATCH_ENABLED";
    }

    private String firstQualificationBlockReason(SmartWalletProfileEntity wallet) {
        if (safe(wallet.getWinRate()).compareTo(safe(properties.getMinWinRate())) < 0) {
            return "WIN_RATE_BELOW_THRESHOLD";
        }
        if (safe(wallet.getWalletScore()).compareTo(safe(properties.getMinWalletScore())) < 0) {
            return "SCORE_BELOW_THRESHOLD";
        }
        if ((wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades())
                < (properties.getMinTotalTrades() == null ? 0 : properties.getMinTotalTrades())) {
            return "TOTAL_TRADES_BELOW_THRESHOLD";
        }
        if (safe(wallet.getRealizedPnlUsdc()).compareTo(safe(properties.getMinRealizedPnlUsdc())) < 0) {
            return "PNL_BELOW_THRESHOLD";
        }
        if (safe(wallet.getMaxDrawdownRate()).compareTo(safe(properties.getMaxDrawdownRate())) > 0) {
            return "DRAWDOWN_ABOVE_THRESHOLD";
        }
        return null;
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private Map<String, SmartWalletBehaviorProfileEntity> loadBehavior(List<SmartWalletProfileEntity> profiles) {
        Set<String> addresses = profiles.stream()
                .map(SmartWalletProfileEntity::getWalletAddress)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (addresses.isEmpty()) {
            return Map.of();
        }
        List<SmartWalletBehaviorProfileEntity> rows = behaviorProfileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                        .eq(SmartWalletBehaviorProfileEntity::getSource, SOURCE)
                        .in(SmartWalletBehaviorProfileEntity::getWalletAddress, addresses));
        Map<String, SmartWalletBehaviorProfileEntity> map = new HashMap<>();
        for (SmartWalletBehaviorProfileEntity row : rows) {
            if (row != null && StringUtils.hasText(row.getWalletAddress())) {
                map.put(row.getWalletAddress(), row);
            }
        }
        return map;
    }

    private boolean isMetricsStale(SmartWalletProfileEntity wallet) {
        if (wallet == null || wallet.getLastMetricsRefreshAt() == null) {
            return true;
        }
        long staleAfterMs = Math.max(warmWalletRefreshMinIntervalMs() * 2, TimeUnit.HOURS.toMillis(1));
        return System.currentTimeMillis() - wallet.getLastMetricsRefreshAt().getTime() > staleAfterMs;
    }

    private boolean isEventSyncStale(SmartWalletProfileEntity wallet) {
        if (wallet == null || wallet.getLastEventSyncAt() == null) {
            return true;
        }
        long minInterval = properties.getPolymarket().getWalletTradeSyncMinIntervalMs() == null
                ? 30_000L
                : Math.max(properties.getPolymarket().getWalletTradeSyncMinIntervalMs(), 1L);
        long staleAfterMs = Math.max(minInterval * 10, TimeUnit.MINUTES.toMillis(10));
        return System.currentTimeMillis() - wallet.getLastEventSyncAt().getTime() > staleAfterMs;
    }

    private long warmWalletRefreshMinIntervalMs() {
        Long value = properties.getPolymarket().getWarmWalletRefreshMinIntervalMs();
        return value == null ? TimeUnit.MINUTES.toMillis(30) : Math.max(value, 1L);
    }

    private boolean isForceColdByInactivity(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getLastTradeAt() == null) {
            return false;
        }
        long daysSinceLastTrade = TimeUnit.MILLISECONDS.toDays(
                Math.max(0L, now.getTime() - wallet.getLastTradeAt().getTime()));
        return daysSinceLastTrade > resolveColdWalletInactivityDays();
    }

    private boolean isWarmByInactivity(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getLastTradeAt() == null) {
            return false;
        }
        long daysSinceLastTrade = TimeUnit.MILLISECONDS.toDays(
                Math.max(0L, now.getTime() - wallet.getLastTradeAt().getTime()));
        return daysSinceLastTrade > resolveWarmWalletInactivityDays()
                && daysSinceLastTrade <= resolveColdWalletInactivityDays();
    }

    private long resolveColdWalletInactivityDays() {
        Integer value = properties.getPolymarket().getColdWalletInactivityDays();
        return value == null ? 60L : Math.max(value, resolveWarmWalletInactivityDays());
    }

    private long resolveWarmWalletInactivityDays() {
        Integer value = properties.getPolymarket().getWarmWalletInactivityDays();
        return value == null ? 30L : Math.max(value, 1);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private Date earliest(Date current, Date candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate == null || current.before(candidate) ? current : candidate;
    }

    private Date latest(Date current, Date candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate == null || current.after(candidate) ? current : candidate;
    }
}
