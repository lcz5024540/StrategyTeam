package com.metaverse.coin.strategy.common.service.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SmartWallet 钱包画像的<strong>只读</strong>视图 service。
 *
 * <p>从 job 端 {@code SmartWalletProfileStoreService}（1367 行写为主）摘出 read 路径，
 * Phase 4.1 下沉到 common 让 web 端 watchlist 类查询能本地命中。原 job 端 service 不动，
 * 继续承担 upsertMetrics / update / recordHotEventSync 等写路径。</p>
 *
 * <p>语义对齐：本类的 listAll/listExecutableWatchlist/listWarmWatchlist/listColdProfiles/
 * listQualified/listRecentlyDiscovered/findByWalletAddress/countPolymarketProfiles 与
 * job 端同名方法行为一致；tier 计算（HOT/WARM/COLD）使用同一套 properties 阈值
 * 和 manualBlocklist / manualAllowlist runtime 合并规则。</p>
 *
 * <p>不依赖 job 端 walletArchetypeClassifierService / walletBehaviorProfileService —
 * 这些只在 write 路径和 archetype/behavior 类查询里用，本基础 read 路径不需要。</p>
 */
@Service
@RequiredArgsConstructor
public class SmartWalletProfileStoreReadService {

    private static final String SOURCE = "POLYMARKET";
    private static final String MONITOR_TIER_HOT = "HOT";
    private static final String MONITOR_TIER_WARM = "WARM";
    private static final String MONITOR_TIER_COLD = "COLD";
    private static final long DEFAULT_WARM_INACTIVITY_DAYS = 30L;
    private static final long DEFAULT_COLD_INACTIVITY_DAYS = 60L;
    private static final long WARM_RECENT_DISCOVERY_DAYS = 30L;
    private static final BigDecimal WARM_SCORE_BUFFER = new BigDecimal("0.10");
    private static final long LIST_ALL_CACHE_TTL_MS = 15_000L;

    private final SmartWalletProperties properties;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletManualAllowlistService manualAllowlistService;
    private final SmartWalletManualBlocklistService manualBlocklistService;

    private volatile List<SmartWalletProfileEntity> cachedListAll;
    private volatile long cachedListAllExpiresAt;

    // ─────────────────────────────── 公共 read API ───────────────────────────────

    public List<SmartWalletProfileEntity> listAll() {
        long now = System.currentTimeMillis();
        List<SmartWalletProfileEntity> cached = this.cachedListAll;
        if (cached != null && now < cachedListAllExpiresAt) {
            return cached;
        }
        List<SmartWalletProfileEntity> fresh = applyMonitorTierState(
                profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                        .orderByDesc(SmartWalletProfileEntity::getRealizedPnlUsdc)));
        this.cachedListAll = fresh;
        this.cachedListAllExpiresAt = now + LIST_ALL_CACHE_TTL_MS;
        return fresh;
    }

    public long countPolymarketProfiles() {
        Long count = profileMapper.selectCount(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE));
        return count == null ? 0L : count;
    }

    public SmartWalletProfileEntity findByWalletAddress(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        return profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, normalizeWallet(walletAddress))
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));
    }

    public List<SmartWalletProfileEntity> listWatchEnabled() {
        return listExecutableWatchlist();
    }

    public List<SmartWalletProfileEntity> listExecutableWatchlist() {
        List<SmartWalletProfileEntity> allProfiles = listAll();
        LinkedHashMap<String, SmartWalletProfileEntity> merged = new LinkedHashMap<>();
        for (SmartWalletProfileEntity profile : allProfiles) {
            if (isHotMonitorTier(profile)) {
                merged.put(normalizeWallet(profile.getWalletAddress()), profile);
            }
        }
        mergeManualAllowlistRuntimeProfiles(merged, allProfiles);
        return new ArrayList<>(merged.values());
    }

    public List<SmartWalletProfileEntity> listWarmWatchlist() {
        return listAll().stream()
                .filter(this::isWarmMonitorTier)
                .toList();
    }

    public List<SmartWalletProfileEntity> listColdProfiles() {
        return listAll().stream()
                .filter(this::isColdMonitorTier)
                .toList();
    }

    public List<SmartWalletProfileEntity> listQualified() {
        return listAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getQualified()))
                .toList();
    }

    public List<SmartWalletProfileEntity> listRecentlyDiscovered(Date since, int limit) {
        return applyMonitorTierState(
                profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .ge(SmartWalletProfileEntity::getLastDiscoveredAt, since)
                        .isNotNull(SmartWalletProfileEntity::getLastDiscoveredAt)
                        .orderByDesc(SmartWalletProfileEntity::getLastDiscoveredAt)
                        .last("limit " + Math.max(limit, 1))));
    }

    public boolean isObserveCooldownActive(SmartWalletProfileEntity wallet) {
        if (wallet == null || wallet.getObserveCooldownUntil() == null) {
            return false;
        }
        return wallet.getObserveCooldownUntil().after(new Date());
    }

    // ─────────────────────────────── Monitor tier 判定 ───────────────────────────────

    private List<SmartWalletProfileEntity> applyMonitorTierState(List<SmartWalletProfileEntity> wallets) {
        if (wallets == null || wallets.isEmpty()) {
            return List.of();
        }
        Date now = new Date();
        for (SmartWalletProfileEntity wallet : wallets) {
            applyMonitorTierState(wallet, now);
        }
        return wallets;
    }

    private void applyMonitorTierState(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null) {
            return;
        }
        Date effectiveNow = now == null ? new Date() : now;
        if (manualBlocklistService.isBlocked(wallet.getWalletAddress())) {
            wallet.setMonitorTier(MONITOR_TIER_COLD);
            wallet.setMonitorReason(manualBlocklistService.activeReason(wallet.getWalletAddress()));
            return;
        }
        if (isForceColdByInactivity(wallet, effectiveNow)) {
            wallet.setMonitorTier(MONITOR_TIER_COLD);
            wallet.setMonitorReason("STALE_LAST_TRADE_GT_" + resolveColdWalletInactivityDays() + "D");
            return;
        }
        if (isWarmByInactivity(wallet, effectiveNow)) {
            wallet.setMonitorTier(MONITOR_TIER_WARM);
            wallet.setMonitorReason("STALE_LAST_TRADE_GT_" + resolveWarmWalletInactivityDays() + "D");
            return;
        }
        if (Boolean.TRUE.equals(wallet.getWatchEnabled()) && isHotRetentionActive(wallet, effectiveNow)) {
            wallet.setMonitorTier(MONITOR_TIER_HOT);
            wallet.setMonitorReason("HOT_RETENTION|" + firstText(wallet.getHotRetentionReason(), "NEAR_THRESHOLD"));
            return;
        }
        if (Boolean.TRUE.equals(wallet.getQualified()) && Boolean.TRUE.equals(wallet.getWatchEnabled())) {
            wallet.setMonitorTier(MONITOR_TIER_HOT);
            wallet.setMonitorReason("QUALIFIED_EXECUTABLE");
            return;
        }
        List<String> warmReasons = warmReasons(wallet, effectiveNow);
        if (!warmReasons.isEmpty()) {
            wallet.setMonitorTier(MONITOR_TIER_WARM);
            wallet.setMonitorReason(String.join("|", warmReasons));
            return;
        }
        wallet.setMonitorTier(MONITOR_TIER_COLD);
        wallet.setMonitorReason("NO_ACTIVE_MONITOR_SIGNAL");
    }

    private boolean isForceColdByInactivity(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getLastTradeAt() == null) {
            return false;
        }
        Date effectiveNow = now == null ? new Date() : now;
        long daysSinceLastTrade = TimeUnit.MILLISECONDS.toDays(
                Math.max(0L, effectiveNow.getTime() - wallet.getLastTradeAt().getTime()));
        return daysSinceLastTrade > resolveColdWalletInactivityDays();
    }

    private boolean isWarmByInactivity(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getLastTradeAt() == null) {
            return false;
        }
        Date effectiveNow = now == null ? new Date() : now;
        long daysSinceLastTrade = TimeUnit.MILLISECONDS.toDays(
                Math.max(0L, effectiveNow.getTime() - wallet.getLastTradeAt().getTime()));
        return daysSinceLastTrade > resolveWarmWalletInactivityDays()
                && daysSinceLastTrade <= resolveColdWalletInactivityDays();
    }

    private boolean isHotRetentionActive(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getHotRetentionUntil() == null) {
            return false;
        }
        Date effectiveNow = now == null ? new Date() : now;
        return wallet.getHotRetentionUntil().after(effectiveNow);
    }

    private List<String> warmReasons(SmartWalletProfileEntity wallet, Date now) {
        List<String> reasons = new ArrayList<>();
        if (wallet == null) {
            return reasons;
        }
        Date effectiveNow = now == null ? new Date() : now;
        if (isObserveCooldownActive(wallet)) {
            reasons.add("OBSERVE_COOLDOWN");
        }
        if (wallet.getManualWatchEnabled() != null) {
            reasons.add("MANUAL_OVERRIDE_PRESENT");
        }
        if (wallet.getLastDiscoveredAt() != null
                && wallet.getLastDiscoveredAt().getTime()
                        >= effectiveNow.getTime() - TimeUnit.DAYS.toMillis(WARM_RECENT_DISCOVERY_DAYS)) {
            reasons.add("RECENT_DISCOVERY_" + WARM_RECENT_DISCOVERY_DAYS + "D");
        }
        BigDecimal warmThreshold = safe(properties.getMinWalletScore())
                .subtract(WARM_SCORE_BUFFER)
                .max(BigDecimal.ZERO);
        if (safe(wallet.getWalletScore()).compareTo(warmThreshold) >= 0) {
            reasons.add("SCORE_NEAR_THRESHOLD");
        }
        return reasons;
    }

    private boolean isHotMonitorTier(SmartWalletProfileEntity wallet) {
        return wallet != null && MONITOR_TIER_HOT.equalsIgnoreCase(wallet.getMonitorTier());
    }

    private boolean isWarmMonitorTier(SmartWalletProfileEntity wallet) {
        return wallet != null && MONITOR_TIER_WARM.equalsIgnoreCase(wallet.getMonitorTier());
    }

    private boolean isColdMonitorTier(SmartWalletProfileEntity wallet) {
        return wallet != null && MONITOR_TIER_COLD.equalsIgnoreCase(wallet.getMonitorTier());
    }

    private long resolveColdWalletInactivityDays() {
        Integer value = properties.getPolymarket().getColdWalletInactivityDays();
        return value == null ? DEFAULT_COLD_INACTIVITY_DAYS
                : Math.max(value, resolveWarmWalletInactivityDays());
    }

    private long resolveWarmWalletInactivityDays() {
        Integer value = properties.getPolymarket().getWarmWalletInactivityDays();
        return value == null ? DEFAULT_WARM_INACTIVITY_DAYS : Math.max(value, 1);
    }

    // ─────────────────────────────── Manual allowlist runtime 合并 ───────────────────────────────

    private void mergeManualAllowlistRuntimeProfiles(LinkedHashMap<String, SmartWalletProfileEntity> merged,
                                                    List<SmartWalletProfileEntity> allProfiles) {
        if (merged == null) {
            return;
        }
        LinkedHashMap<String, SmartWalletProfileEntity> profilesByWallet = new LinkedHashMap<>();
        if (allProfiles != null) {
            for (SmartWalletProfileEntity profile : allProfiles) {
                if (profile != null && StringUtils.hasText(profile.getWalletAddress())) {
                    profilesByWallet.put(normalizeWallet(profile.getWalletAddress()), profile);
                }
            }
        }
        for (SmartWalletManualAllowlistEntity allowlist : manualAllowlistService.listActiveEntities()) {
            if (!manualAllowlistService.isActive(allowlist) || !StringUtils.hasText(allowlist.getWalletAddress())) {
                continue;
            }
            String normalizedWallet = normalizeWallet(allowlist.getWalletAddress());
            if (manualBlocklistService.isBlocked(normalizedWallet)) {
                continue;
            }
            SmartWalletProfileEntity existing = profilesByWallet.get(normalizedWallet);
            merged.put(normalizedWallet, toManualAllowlistRuntimeProfile(existing, allowlist));
        }
    }

    private SmartWalletProfileEntity toManualAllowlistRuntimeProfile(SmartWalletProfileEntity existing,
                                                                    SmartWalletManualAllowlistEntity allowlist) {
        SmartWalletProfileEntity runtime = existing == null ? new SmartWalletProfileEntity() : copyProfile(existing);
        Date now = new Date();
        runtime.setWalletAddress(allowlist.getWalletAddress())
                .setSource(SOURCE)
                .setChain(firstText(runtime.getChain(), "polygon"))
                .setAlias(firstText(runtime.getAlias(), "manual-allowlist"))
                .setQualified(existing == null ? Boolean.FALSE : existing.getQualified())
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier(MONITOR_TIER_HOT)
                .setMonitorReason(existing == null
                        ? "MANUAL_ALLOWLIST_DEFAULT_FOLLOW_PROFILE_MISSING"
                        : "MANUAL_ALLOWLIST_DEFAULT_FOLLOW")
                .setUpdatedAt(existing == null ? now : existing.getUpdatedAt())
                .setCreatedAt(existing == null ? now : existing.getCreatedAt());
        return runtime;
    }

    private SmartWalletProfileEntity copyProfile(SmartWalletProfileEntity source) {
        return new SmartWalletProfileEntity()
                .setId(source.getId())
                .setWalletAddress(source.getWalletAddress())
                .setChain(source.getChain())
                .setAlias(source.getAlias())
                .setSource(source.getSource())
                .setWinRate(source.getWinRate())
                .setRealizedPnlUsdc(source.getRealizedPnlUsdc())
                .setTotalTrades(source.getTotalTrades())
                .setAverageSizeUsdc(source.getAverageSizeUsdc())
                .setAverageRoiRate(source.getAverageRoiRate())
                .setMaxDrawdownRate(source.getMaxDrawdownRate())
                .setWalletScore(source.getWalletScore())
                .setQualified(source.getQualified())
                .setWatchEnabled(source.getWatchEnabled())
                .setLastTradeAt(source.getLastTradeAt())
                .setLastEvaluatedAt(source.getLastEvaluatedAt())
                .setLastDiscoveredAt(source.getLastDiscoveredAt())
                .setLastDiscoveryQuery(source.getLastDiscoveryQuery())
                .setLeaderboardHitCount30d(source.getLeaderboardHitCount30d())
                .setDominantSession(source.getDominantSession())
                .setAsiaTradeRatio30d(source.getAsiaTradeRatio30d())
                .setEuTradeRatio30d(source.getEuTradeRatio30d())
                .setUsTradeRatio30d(source.getUsTradeRatio30d())
                .setActiveHourHistogramJson(source.getActiveHourHistogramJson())
                .setRecentWinRate7d(source.getRecentWinRate7d())
                .setRecentWinRate30d(source.getRecentWinRate30d())
                .setRecentTradeCount7d(source.getRecentTradeCount7d())
                .setRecentTradeCount30d(source.getRecentTradeCount30d())
                .setObserveCooldownUntil(source.getObserveCooldownUntil())
                .setObserveCooldownReason(source.getObserveCooldownReason())
                .setHotRetentionUntil(source.getHotRetentionUntil())
                .setHotRetentionReason(source.getHotRetentionReason())
                .setManualWatchEnabled(source.getManualWatchEnabled())
                .setManualRecommendedExitLane(source.getManualRecommendedExitLane())
                .setManualOverrideNote(source.getManualOverrideNote())
                .setManualOverrideUpdatedAt(source.getManualOverrideUpdatedAt())
                .setMonitorTier(source.getMonitorTier())
                .setMonitorReason(source.getMonitorReason())
                .setLastEventSyncAt(source.getLastEventSyncAt())
                .setLastMetricsRefreshAt(source.getLastMetricsRefreshAt())
                .setClosedSyncMaxClosedAt(source.getClosedSyncMaxClosedAt())
                .setClosedSyncTotalCount(source.getClosedSyncTotalCount())
                .setClosedSyncDataQuality(source.getClosedSyncDataQuality())
                .setClosedSyncLastFullAt(source.getClosedSyncLastFullAt())
                .setCreatedAt(source.getCreatedAt())
                .setUpdatedAt(source.getUpdatedAt());
    }

    // ─────────────────────────────── 杂项 helper ───────────────────────────────

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String normalizeWallet(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.trim().toLowerCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
