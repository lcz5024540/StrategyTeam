package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileStaleDiagnosticView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTierDiagnosticsSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletProfileStoreService {

    private static final String SOURCE = "POLYMARKET";
    // Days thresholds for activity decay (P2)
    private static final long DECAY_START_DAYS   = 30L;
    private static final long DEFAULT_WARM_INACTIVITY_DAYS = 30L;
    private static final long DEFAULT_COLD_INACTIVITY_DAYS = 60L;
    private static final int MIN_ARCHETYPE_EVIDENCE_SAMPLE = 5;
    private static final BigDecimal MIN_EXECUTABLE_FOLLOW_FIT_SCORE = new BigDecimal("0.42");
    private static final BigDecimal WARM_SCORE_BUFFER = new BigDecimal("0.10");
    private static final long WARM_RECENT_DISCOVERY_DAYS = 30L;
    private static final String MONITOR_TIER_HOT = "HOT";
    private static final String MONITOR_TIER_WARM = "WARM";
    private static final String MONITOR_TIER_COLD = "COLD";

    private final SmartWalletProperties properties;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final SmartWalletManualAllowlistService manualAllowlistService;

    public SmartWalletProfileEntity upsertMetrics(PolymarketWalletMetricsRequest request) {
        SmartWalletProfileEntity entity = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, request.getWalletAddress())
                .eq(SmartWalletProfileEntity::getChain, request.getChain())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));

        if (entity == null) {
            entity = new SmartWalletProfileEntity()
                    .setWalletAddress(request.getWalletAddress())
                    .setChain(request.getChain())
                    .setSource(SOURCE)
                    .setCreatedAt(new Date());
            entity.setQualified(Boolean.FALSE);
            entity.setWatchEnabled(Boolean.FALSE);
        }
        Date previousDiscoveredAt = entity.getLastDiscoveredAt();

        entity.setAlias(request.getAlias())
                .setWinRate(safe(request.getWinRate()))
                .setRealizedPnlUsdc(safe(request.getRealizedPnlUsdc()))
                .setTotalTrades(request.getTotalTrades() == null ? 0 : request.getTotalTrades())
                .setAverageSizeUsdc(safe(request.getAverageSizeUsdc()))
                .setAverageRoiRate(safe(request.getAverageRoiRate()))
                .setMaxDrawdownRate(safe(request.getMaxDrawdownRate()))
                .setLastTradeAt(request.getLastTradeAt())
                .setUpdatedAt(new Date());

        if (request.getRecentWinRate7d() != null) {
            entity.setRecentWinRate7d(safe(request.getRecentWinRate7d()));
        }
        if (request.getRecentWinRate30d() != null) {
            entity.setRecentWinRate30d(safe(request.getRecentWinRate30d()));
        }
        if (request.getRecentTradeCount7d() != null) {
            entity.setRecentTradeCount7d(Math.max(request.getRecentTradeCount7d(), 0));
        }
        if (request.getRecentTradeCount30d() != null) {
            entity.setRecentTradeCount30d(Math.max(request.getRecentTradeCount30d(), 0));
        }
        if (request.getLastDiscoveredAt() != null) {
            entity.setLastDiscoveredAt(request.getLastDiscoveredAt());
            entity.setLastDiscoveryQuery(request.getLastDiscoveryQuery());
            entity.setLeaderboardHitCount30d(nextLeaderboardHitCount(previousDiscoveredAt, entity.getLeaderboardHitCount30d(), request));
            entity.setDominantSession(request.getDominantSession());
            entity.setAsiaTradeRatio30d(safe(request.getAsiaTradeRatio30d()));
            entity.setEuTradeRatio30d(safe(request.getEuTradeRatio30d()));
            entity.setUsTradeRatio30d(safe(request.getUsTradeRatio30d()));
            entity.setActiveHourHistogramJson(request.getActiveHourHistogramJson());
        }

        if (entity.getId() == null) {
            profileMapper.insert(entity);
        } else {
            profileMapper.updateById(entity);
        }
        WalletBehaviorProfileView behaviorProfile = refreshBehaviorProfile(entity);
        entity.setWalletScore(scoreWallet(entity, behaviorProfile));
        applyQualificationState(entity, behaviorProfile, true);
        entity.setUpdatedAt(new Date());
        profileMapper.updateById(entity);
        return entity;
    }

    public List<SmartWalletProfileEntity> listAll() {
        return applyMonitorTierState(profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                .orderByDesc(SmartWalletProfileEntity::getRealizedPnlUsdc)));
    }

    public long countPolymarketProfiles() {
        Long count = profileMapper.selectCount(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE));
        return count == null ? 0L : count;
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
        return applyMonitorTierState(profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .ge(SmartWalletProfileEntity::getLastDiscoveredAt, since)
                .isNotNull(SmartWalletProfileEntity::getLastDiscoveredAt)
                .orderByDesc(SmartWalletProfileEntity::getLastDiscoveredAt)
                .last("limit " + Math.max(limit, 1))));
    }

    public void update(SmartWalletProfileEntity entity) {
        applyMonitorTierState(entity);
        entity.setUpdatedAt(new Date());
        profileMapper.updateById(entity);
        walletBehaviorProfileService.evictProfileCache(entity.getWalletAddress());
    }

    public void recordHotEventSync(SmartWalletProfileEntity entity, Date syncedAt) {
        if (entity == null || entity.getId() == null || syncedAt == null) {
            return;
        }
        entity.setLastEventSyncAt(syncedAt);
        entity.setUpdatedAt(new Date());
        SmartWalletProfileEntity update = new SmartWalletProfileEntity()
                .setId(entity.getId())
                .setLastEventSyncAt(entity.getLastEventSyncAt())
                .setUpdatedAt(entity.getUpdatedAt());
        profileMapper.updateById(update);
    }

    public void recordWarmMetricsRefresh(SmartWalletProfileEntity entity, Date refreshedAt) {
        if (entity == null || entity.getId() == null || refreshedAt == null) {
            return;
        }
        entity.setLastMetricsRefreshAt(refreshedAt);
        entity.setUpdatedAt(new Date());
        SmartWalletProfileEntity update = new SmartWalletProfileEntity()
                .setId(entity.getId())
                .setLastMetricsRefreshAt(entity.getLastMetricsRefreshAt())
                .setUpdatedAt(entity.getUpdatedAt());
        profileMapper.updateById(update);
    }

    public void recordObservedTradeActivity(String walletAddress, Date tradedAt) {
        if (!StringUtils.hasText(walletAddress) || tradedAt == null) {
            return;
        }
        SmartWalletProfileEntity entity = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            return;
        }
        Date previousLastTradeAt = entity.getLastTradeAt();
        if (previousLastTradeAt != null && !tradedAt.after(previousLastTradeAt)) {
            return;
        }
        boolean wasCold = isColdMonitorTier(entity) || isForceColdByInactivity(entity, new Date());
        entity.setLastTradeAt(tradedAt);
        applyQualificationState(entity);
        entity.setUpdatedAt(new Date());
        profileMapper.updateById(entity);
        if (wasCold && !isColdMonitorTier(entity)) {
            log.info("WALLET_COLD_RECOVERY walletAddress={} lastTradeAt={} monitorTier={} watchEnabled={}",
                    entity.getWalletAddress(), entity.getLastTradeAt(), entity.getMonitorTier(), entity.getWatchEnabled());
        }
    }

    public List<SmartWalletProfileOverrideView> listManualOverrides(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .and(wrapper -> wrapper
                                .isNotNull(SmartWalletProfileEntity::getManualWatchEnabled)
                                .or()
                                .isNotNull(SmartWalletProfileEntity::getManualRecommendedExitLane)
                                .or()
                                .isNotNull(SmartWalletProfileEntity::getManualOverrideNote))
                        .orderByDesc(SmartWalletProfileEntity::getManualOverrideUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toOverrideView)
                .toList();
    }

    public SmartWalletProfileOverrideSummaryView summarizeManualOverrides() {
        List<SmartWalletProfileEntity> rows = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .and(wrapper -> wrapper
                        .isNotNull(SmartWalletProfileEntity::getManualWatchEnabled)
                        .or()
                        .isNotNull(SmartWalletProfileEntity::getManualRecommendedExitLane)
                        .or()
                        .isNotNull(SmartWalletProfileEntity::getManualOverrideNote)));

        int observeOnlyOverrides = 0;
        int forceExecutableOverrides = 0;
        int exitLaneOverrides = 0;
        int noteOnlyOverrides = 0;
        int updatedLast24h = 0;
        long recentCutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        LinkedHashMap<String, Integer> exitLaneCounts = new LinkedHashMap<>();
        for (SmartWalletProfileEntity row : rows) {
            boolean hasManualWatch = row.getManualWatchEnabled() != null;
            boolean hasExitLane = StringUtils.hasText(row.getManualRecommendedExitLane());
            boolean hasNote = StringUtils.hasText(row.getManualOverrideNote());
            if (Boolean.FALSE.equals(row.getManualWatchEnabled())) {
                observeOnlyOverrides++;
            }
            if (Boolean.TRUE.equals(row.getManualWatchEnabled())) {
                forceExecutableOverrides++;
            }
            if (hasExitLane) {
                exitLaneOverrides++;
                String lane = row.getManualRecommendedExitLane().trim().toUpperCase(Locale.ROOT);
                exitLaneCounts.merge(lane, 1, Integer::sum);
            }
            if (!hasManualWatch && !hasExitLane && hasNote) {
                noteOnlyOverrides++;
            }
            if (row.getManualOverrideUpdatedAt() != null && row.getManualOverrideUpdatedAt().getTime() >= recentCutoff) {
                updatedLast24h++;
            }
        }
        return SmartWalletProfileOverrideSummaryView.builder()
                .totalOverrides(rows.size())
                .observeOnlyOverrides(observeOnlyOverrides)
                .forceExecutableOverrides(forceExecutableOverrides)
                .exitLaneOverrides(exitLaneOverrides)
                .noteOnlyOverrides(noteOnlyOverrides)
                .updatedLast24h(updatedLast24h)
                .exitLaneCounts(exitLaneCounts)
                .build();
    }

    public SmartWalletProfileEntity updateManualOverride(String walletAddress, SmartWalletProfileOverrideRequest request) {
        if (!org.springframework.util.StringUtils.hasText(walletAddress)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        SmartWalletProfileEntity entity = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Smart-wallet profile not found: " + walletAddress);
        }
        Date now = new Date();
        if (request != null) {
            if (Boolean.TRUE.equals(request.getClearManualWatchEnabled())) {
                entity.setManualWatchEnabled(null);
            } else if (request.getManualWatchEnabled() != null) {
                entity.setManualWatchEnabled(request.getManualWatchEnabled());
            }

            if (Boolean.TRUE.equals(request.getClearManualRecommendedExitLane())) {
                entity.setManualRecommendedExitLane(null);
            } else if (org.springframework.util.StringUtils.hasText(request.getManualRecommendedExitLane())) {
                entity.setManualRecommendedExitLane(request.getManualRecommendedExitLane().trim());
            }

            if (request.getNote() != null) {
                entity.setManualOverrideNote(request.getNote().trim().isEmpty() ? null : request.getNote().trim());
            }
        }
        entity.setManualOverrideUpdatedAt(now);
        WalletBehaviorProfileView behaviorProfile = refreshBehaviorProfile(entity);
        applyQualificationState(entity, behaviorProfile, true);
        entity.setUpdatedAt(now);
        profileMapper.updateById(entity);
        walletBehaviorProfileService.evictProfileCache(entity.getWalletAddress());
        return entity;
    }

    public SmartWalletProfileBatchOverrideResultView batchUpdateManualOverrides(SmartWalletProfileBatchOverrideRequest request) {
        if (request == null || request.getWalletAddresses() == null || request.getWalletAddresses().isEmpty()) {
            throw new IllegalArgumentException("walletAddresses is required");
        }
        List<String> uniqueWalletAddresses = request.getWalletAddresses().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (uniqueWalletAddresses.isEmpty()) {
            throw new IllegalArgumentException("walletAddresses is required");
        }

        SmartWalletProfileOverrideRequest singleRequest = request.toSingleOverrideRequest();
        List<String> updatedWallets = new ArrayList<>();
        List<String> missingWallets = new ArrayList<>();
        for (String walletAddress : uniqueWalletAddresses) {
            try {
                updateManualOverride(walletAddress, singleRequest);
                updatedWallets.add(walletAddress);
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith("Smart-wallet profile not found")) {
                    missingWallets.add(walletAddress);
                    continue;
                }
                throw ex;
            }
        }
        return SmartWalletProfileBatchOverrideResultView.builder()
                .requestedCount(request.getWalletAddresses().size())
                .uniqueWalletCount(uniqueWalletAddresses.size())
                .updatedCount(updatedWallets.size())
                .missingCount(missingWallets.size())
                .updatedWalletAddresses(updatedWallets)
                .missingWalletAddresses(missingWallets)
                .build();
    }

    public List<SmartWalletProfileStaleDiagnosticView> listProfileTierDiagnostics(Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
        return listAll().stream()
                .limit(safeLimit)
                .map(this::toStaleDiagnosticView)
                .toList();
    }

    public List<SmartWalletProfileTierDiagnosticsSummaryView> summarizeProfileTierDiagnostics() {
        LinkedHashMap<String, List<SmartWalletProfileStaleDiagnosticView>> byTier = new LinkedHashMap<>();
        for (SmartWalletProfileStaleDiagnosticView row : listAll().stream()
                .map(this::toStaleDiagnosticView)
                .toList()) {
            byTier.computeIfAbsent(firstText(row.monitorTier(), "UNKNOWN"), ignored -> new ArrayList<>()).add(row);
        }
        return byTier.entrySet().stream()
                .map(entry -> summarizeTier(entry.getKey(), entry.getValue()))
                .toList();
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

    private SmartWalletProfileStaleDiagnosticView toStaleDiagnosticView(SmartWalletProfileEntity profile) {
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
                .firstHotBlockReason(firstHotBlockReason(profile, metricsStale, allowlistActive))
                .build();
    }

    private String firstHotBlockReason(SmartWalletProfileEntity wallet, boolean metricsStale, boolean allowlistActive) {
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
            return "STALE_LAST_TRADE_GT_60D";
        }
        if (isWarmByInactivity(wallet, now)) {
            return "STALE_LAST_TRADE_GT_30D";
        }
        if (isObserveCooldownActive(wallet)) {
            return "OBSERVE_COOLDOWN";
        }
        String qualificationBlock = firstQualificationBlockReason(wallet);
        if (qualificationBlock != null) {
            return qualificationBlock;
        }
        ExecutableEvaluation executable = evaluateExecutableCandidate(wallet, walletBehaviorProfileService.getProfile(wallet.getWalletAddress()));
        if (!executable.allowed()) {
            return executable.reason();
        }
        if (Boolean.TRUE.equals(wallet.getWatchEnabled()) && isHotMonitorTier(wallet)) {
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
        if ((wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades()) < defaultInt(properties.getMinTotalTrades())) {
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

    // -------------------------------------------------------------------------
    // P2: Activity-decay job — runs every 6 hours.
    // Inactivity now moves wallets through HOT -> WARM -> COLD instead of
    // repeatedly subtracting from the persisted walletScore every run.
    // -------------------------------------------------------------------------
    @Scheduled(fixedDelay = 6 * 3600_000L)
    public void applyActivityDecay() {
        List<SmartWalletProfileEntity> wallets = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE));
        Date now = new Date();
        for (SmartWalletProfileEntity wallet : wallets) {
            Date lastTrade = wallet.getLastTradeAt();
            if (lastTrade == null) {
                continue;
            }
            long daysSince = TimeUnit.MILLISECONDS.toDays(now.getTime() - lastTrade.getTime());
            if (daysSince <= DECAY_START_DAYS) {
                continue;
            }
            long offlineDays = resolveColdWalletInactivityDays();
            boolean wasWatchEnabled = Boolean.TRUE.equals(wallet.getWatchEnabled());
            String previousTier = wallet.getMonitorTier();
            applyQualificationState(wallet);
            wallet.setUpdatedAt(now);
            profileMapper.updateById(wallet);
            if (daysSince >= offlineDays && wasWatchEnabled) {
                log.info("WALLET_DECAY cold walletAddress={} daysSinceLastTrade={} monitorTier={}",
                        wallet.getWalletAddress(), daysSince, wallet.getMonitorTier());
            } else if (!Objects.equals(previousTier, wallet.getMonitorTier())) {
                log.info("WALLET_DECAY tier walletAddress={} daysSinceLastTrade={} monitorTier={}",
                        wallet.getWalletAddress(), daysSince, wallet.getMonitorTier());
            }
        }
    }

    // -------------------------------------------------------------------------
    // P2: Internal win-rate refresh — runs every 15 minutes by default.
    // Computes real win rate from our own closed positions for each wallet and
    // blends it with the external leaderboard win rate (weight 0.4 internal / 0.6 external)
    // to correct for survivorship bias in the official leaderboard data.
    //
    // Survivorship-bias fallback: when a wallet has winRate=1.0 with a small sample
    // (totalTrades <= LEADERBOARD_POSITION_CAP) and we have no internal closed positions yet,
    // cap the win rate to LEADERBOARD_BIAS_FALLBACK_WIN_RATE immediately rather than waiting
    // for internal data to accumulate. This prevents wallets with inflated leaderboard metrics
    // from qualifying for single-wallet override from the very first signal.
    // -------------------------------------------------------------------------
    private static final int    LEADERBOARD_POSITION_CAP          = 50;
    private static final BigDecimal LEADERBOARD_BIAS_WIN_RATE_TRIGGER = new BigDecimal("1.0");
    private static final BigDecimal LEADERBOARD_BIAS_FALLBACK_WIN_RATE = new BigDecimal("0.65");

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.internal-win-rate-refresh-fixed-delay-ms:900000}")
    public void refreshInternalWinRate() {
        List<SmartWalletProfileEntity> wallets = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE));

        for (SmartWalletProfileEntity wallet : wallets) {
            List<SmartWalletFollowTaskEntity> followedTasks = followTaskMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                            .eq(SmartWalletFollowTaskEntity::getWalletAddress, wallet.getWalletAddress())
                            .eq(SmartWalletFollowTaskEntity::getStatus, "FOLLOWED"));

            int total = 0;
            int wins = 0;
            for (SmartWalletPositionEntity pos : resolveClosedPositionsForWallet(followedTasks)) {
                total++;
                if (safe(pos.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0) {
                    wins++;
                }
            }

            BigDecimal currentWinRate = safe(wallet.getWinRate());
            int leaderboardTrades = wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades();

            if (total < 3) {
                // Not enough internal data for blending yet.
                // Survivorship-bias fallback: wallets with winRate=1.0 and small leaderboard samples
                // almost certainly have inflated metrics — cap them to a conservative default immediately.
                boolean suspiciouslyPerfect = currentWinRate.compareTo(LEADERBOARD_BIAS_WIN_RATE_TRIGGER) >= 0
                        && leaderboardTrades <= LEADERBOARD_POSITION_CAP;
                if (suspiciouslyPerfect && currentWinRate.compareTo(LEADERBOARD_BIAS_FALLBACK_WIN_RATE) > 0) {
                    WalletBehaviorProfileView behaviorProfile = refreshBehaviorProfile(wallet);
                    BigDecimal fallbackScore = scoreWalletWithWinRate(wallet, behaviorProfile, LEADERBOARD_BIAS_FALLBACK_WIN_RATE);
                    wallet.setWalletScore(fallbackScore);
                    wallet.setHotRetentionReason("LEADERBOARD_BIAS_FALLBACK");
                    applyQualificationState(wallet, behaviorProfile, true, "LEADERBOARD_BIAS_FALLBACK");
                    wallet.setUpdatedAt(new Date());
                    profileMapper.updateById(wallet);
                    log.info("WIN_RATE_BIAS_FALLBACK walletAddress={} leaderboardTrades={} effectiveWinRate {} -> {} newScore={}",
                            wallet.getWalletAddress(), leaderboardTrades, currentWinRate,
                            LEADERBOARD_BIAS_FALLBACK_WIN_RATE, fallbackScore);
                }
                continue;
            }

            BigDecimal internalWinRate = BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            // Blend: 40% internal (truth) + 60% external (leaderboard) to smooth noise
            BigDecimal blendedWinRate = internalWinRate.multiply(new BigDecimal("0.40"))
                    .add(currentWinRate.multiply(new BigDecimal("0.60")))
                    .setScale(4, RoundingMode.HALF_UP);

            // Re-score wallet with blended win rate
            wallet.setWinRate(blendedWinRate);
            WalletBehaviorProfileView behaviorProfile = refreshBehaviorProfile(wallet);
            BigDecimal newScore = scoreWallet(wallet, behaviorProfile);
            wallet.setWalletScore(newScore);
            applyQualificationState(wallet, behaviorProfile, true);
            wallet.setUpdatedAt(new Date());
            profileMapper.updateById(wallet);
            log.info("WIN_RATE_REFRESH walletAddress={} internalWinRate={} blended={} newScore={} sampleSize={}",
                    wallet.getWalletAddress(), internalWinRate, blendedWinRate, newScore, total);
        }
    }

    private List<SmartWalletPositionEntity> resolveClosedPositionsForWallet(List<SmartWalletFollowTaskEntity> followedTasks) {
        if (followedTasks == null || followedTasks.isEmpty()) {
            return List.of();
        }
        List<Long> followTaskIds = followedTasks.stream()
                .map(SmartWalletFollowTaskEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (followTaskIds.isEmpty()) {
            return List.of();
        }

        List<SmartWalletOrderEntity> relatedOrders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .in(SmartWalletOrderEntity::getFollowTaskId, followTaskIds)
                        .isNotNull(SmartWalletOrderEntity::getPositionId));
        Set<Long> positionIds = relatedOrders.stream()
                .map(SmartWalletOrderEntity::getPositionId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (positionIds.isEmpty()) {
            return List.of();
        }

        List<SmartWalletPositionEntity> positions = new ArrayList<>(positionMapper.selectBatchIds(positionIds));
        return positions.stream()
                .filter(Objects::nonNull)
                .filter(position -> List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED").contains(
                        position.getStatus() == null ? "" : position.getStatus().toUpperCase()))
                .toList();
    }

    public void applyQualificationState(SmartWalletProfileEntity wallet) {
        applyQualificationState(wallet, null, true);
    }

    public void applyQualificationState(SmartWalletProfileEntity wallet, boolean includeObserveCooldown) {
        applyQualificationState(wallet, null, includeObserveCooldown);
    }

    private void applyQualificationState(SmartWalletProfileEntity wallet,
                                         WalletBehaviorProfileView behaviorProfile,
                                         boolean includeObserveCooldown) {
        applyQualificationState(wallet, behaviorProfile, includeObserveCooldown, null);
    }

    private void applyQualificationState(SmartWalletProfileEntity wallet,
                                         WalletBehaviorProfileView behaviorProfile,
                                         boolean includeObserveCooldown,
                                         String retentionReasonHint) {
        if (wallet == null) {
            return;
        }
        Date now = new Date();
        boolean wasHot = isHotMonitorTier(wallet) || Boolean.TRUE.equals(wallet.getWatchEnabled());
        QualificationEvaluation qualification = evaluateQualification(wallet);
        boolean qualified = qualification.qualified();
        wallet.setQualified(qualified);
        boolean observeBlocked = includeObserveCooldown && applyObserveCooldownState(wallet);
        ExecutableEvaluation executable = (qualified || qualification.retentionEligible())
                ? evaluateExecutableCandidate(wallet, behaviorProfile)
                : ExecutableEvaluation.allow();
        boolean executableCandidate = qualified && !observeBlocked && executable.allowed();
        boolean watchEnabled;
        if (wallet.getManualWatchEnabled() != null) {
            watchEnabled = qualified && wallet.getManualWatchEnabled();
        } else {
            watchEnabled = executableCandidate;
        }
        boolean hardBlocked = qualification.hardFailure()
                || observeBlocked
                || (qualified && !executable.allowed());
        if (!watchEnabled && !hardBlocked
                && shouldRetainHotWallet(wallet, qualification, wasHot, now)) {
            watchEnabled = true;
            startOrKeepHotRetention(wallet, qualification, retentionReasonHint, now);
        } else if (qualified && executableCandidate && !observeBlocked) {
            clearHotRetention(wallet);
        } else if (!isHotRetentionActive(wallet, now)) {
            clearExpiredHotRetention(wallet, now);
        }
        if (isWarmByInactivity(wallet, now)) {
            watchEnabled = false;
        }
        if (isForceColdByInactivity(wallet, now)) {
            watchEnabled = false;
        }
        wallet.setWatchEnabled(watchEnabled);
        applyMonitorTierState(wallet, now);
    }

    private boolean isQualified(SmartWalletProfileEntity wallet) {
        return evaluateQualification(wallet).qualified();
    }

    private QualificationEvaluation evaluateQualification(SmartWalletProfileEntity wallet) {
        BigDecimal winRate = safe(wallet.getWinRate());
        BigDecimal minWinRate = safe(properties.getMinWinRate());
        BigDecimal walletScore = safe(wallet.getWalletScore());
        BigDecimal minWalletScore = safe(properties.getMinWalletScore());
        BigDecimal minScoreWithBuffer = minWalletScore.subtract(resolveScoreHysteresisBuffer()).max(BigDecimal.ZERO);
        BigDecimal minWinRateWithBuffer = minWinRate.subtract(resolveWinRateHysteresisBuffer()).max(BigDecimal.ZERO);

        boolean winOk = winRate.compareTo(minWinRate) >= 0;
        boolean scoreOk = walletScore.compareTo(minWalletScore) >= 0;
        boolean tradesOk = (wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades()) >= defaultInt(properties.getMinTotalTrades());
        boolean pnlOk = safe(wallet.getRealizedPnlUsdc()).compareTo(safe(properties.getMinRealizedPnlUsdc())) >= 0;
        boolean drawdownOk = safe(wallet.getMaxDrawdownRate()).compareTo(safe(properties.getMaxDrawdownRate())) <= 0;
        boolean qualified = winOk && scoreOk && tradesOk && pnlOk && drawdownOk;
        if (qualified) {
            return new QualificationEvaluation(true, false, false, "QUALIFIED");
        }

        boolean nearWin = !winOk && winRate.compareTo(minWinRateWithBuffer) >= 0;
        boolean nearScore = !scoreOk && walletScore.compareTo(minScoreWithBuffer) >= 0;
        boolean hardFailure = !tradesOk
                || !pnlOk
                || !drawdownOk
                || (!winOk && !nearWin)
                || (!scoreOk && !nearScore);
        boolean retentionEligible = !hardFailure && (nearWin || nearScore);
        List<String> reasons = new ArrayList<>();
        if (!winOk) {
            reasons.add(nearWin ? "WIN_RATE_NEAR_THRESHOLD" : "WIN_RATE_HARD_BELOW_THRESHOLD");
        }
        if (!scoreOk) {
            reasons.add(nearScore ? "SCORE_NEAR_THRESHOLD" : "SCORE_HARD_BELOW_THRESHOLD");
        }
        if (!tradesOk) {
            reasons.add("TOTAL_TRADES_BELOW_THRESHOLD");
        }
        if (!pnlOk) {
            reasons.add("PNL_BELOW_THRESHOLD");
        }
        if (!drawdownOk) {
            reasons.add("DRAWDOWN_ABOVE_THRESHOLD");
        }
        return new QualificationEvaluation(false, hardFailure, retentionEligible, String.join("|", reasons));
    }

    private boolean shouldRetainHotWallet(SmartWalletProfileEntity wallet,
                                          QualificationEvaluation qualification,
                                          boolean wasHot,
                                          Date now) {
        if (wallet == null || qualification == null || !qualification.retentionEligible()) {
            return false;
        }
        if (!properties.getPolymarket().isHotRetentionEnabled()) {
            return false;
        }
        if (isHotRetentionExpired(wallet, now)) {
            return false;
        }
        return wasHot || isHotRetentionActive(wallet, now);
    }

    private void startOrKeepHotRetention(SmartWalletProfileEntity wallet,
                                         QualificationEvaluation qualification,
                                         String reasonHint,
                                         Date now) {
        Date effectiveNow = now == null ? new Date() : now;
        if (!isHotRetentionActive(wallet, effectiveNow)) {
            long retentionHours = properties.getPolymarket().getHotRetentionHours() == null
                    ? 24L
                    : Math.max(properties.getPolymarket().getHotRetentionHours(), 1);
            wallet.setHotRetentionUntil(new Date(effectiveNow.getTime() + TimeUnit.HOURS.toMillis(retentionHours)));
        }
        String reason = firstText(reasonHint, qualification.reason());
        wallet.setHotRetentionReason(reason);
    }

    private void clearHotRetention(SmartWalletProfileEntity wallet) {
        wallet.setHotRetentionUntil(null);
        wallet.setHotRetentionReason(null);
    }

    private void clearExpiredHotRetention(SmartWalletProfileEntity wallet, Date now) {
        if (wallet != null && isHotRetentionExpired(wallet, now)) {
            wallet.setHotRetentionReason(firstText(wallet.getHotRetentionReason(), "HOT_RETENTION_EXPIRED"));
        }
    }

    private boolean isHotRetentionActive(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getHotRetentionUntil() == null) {
            return false;
        }
        Date effectiveNow = now == null ? new Date() : now;
        return wallet.getHotRetentionUntil().after(effectiveNow);
    }

    private boolean isHotRetentionExpired(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getHotRetentionUntil() == null) {
            return false;
        }
        Date effectiveNow = now == null ? new Date() : now;
        return !wallet.getHotRetentionUntil().after(effectiveNow);
    }

    private BigDecimal resolveScoreHysteresisBuffer() {
        BigDecimal value = properties.getPolymarket().getScoreHysteresisBuffer();
        return value == null ? new BigDecimal("0.08") : value.max(BigDecimal.ZERO);
    }

    private BigDecimal resolveWinRateHysteresisBuffer() {
        BigDecimal value = properties.getPolymarket().getWinRateHysteresisBuffer();
        return value == null ? new BigDecimal("0.04") : value.max(BigDecimal.ZERO);
    }

    public boolean isObserveCooldownActive(SmartWalletProfileEntity wallet) {
        if (wallet == null || wallet.getObserveCooldownUntil() == null) {
            return false;
        }
        return wallet.getObserveCooldownUntil().after(new Date());
    }

    private boolean isExecutableCandidate(SmartWalletProfileEntity wallet, WalletBehaviorProfileView behaviorProfile) {
        return evaluateExecutableCandidate(wallet, behaviorProfile).allowed();
    }

    private ExecutableEvaluation evaluateExecutableCandidate(SmartWalletProfileEntity wallet, WalletBehaviorProfileView behaviorProfile) {
        if (wallet == null) {
            return ExecutableEvaluation.block("MISSING_WALLET");
        }
        WalletBehaviorProfileView effectiveBehavior = behaviorProfile != null
                ? behaviorProfile
                : walletBehaviorProfileService.getProfile(wallet.getWalletAddress());
        if (effectiveBehavior != null
                && StringUtils.hasText(effectiveBehavior.recommendedExitLane())
                && "FAST_EXIT_OR_SKIP".equalsIgnoreCase(effectiveBehavior.recommendedExitLane())) {
            return ExecutableEvaluation.block("FAST_EXIT_OR_SKIP");
        }
        WalletArchetypeView archetype = walletArchetypeClassifierService.classify(wallet.getWalletAddress(), wallet);
        if (archetype == null) {
            return ExecutableEvaluation.allow();
        }
        if (archetype.labelSampleSize() < MIN_ARCHETYPE_EVIDENCE_SAMPLE) {
            return ExecutableEvaluation.allow();
        }
        if ("OBSERVE_ONLY".equalsIgnoreCase(archetype.followLane())) {
            return ExecutableEvaluation.block("OBSERVE_ONLY_ARCHETYPE");
        }
        if (safe(archetype.followFitScore()).compareTo(MIN_EXECUTABLE_FOLLOW_FIT_SCORE) < 0) {
            return ExecutableEvaluation.block("LOW_FOLLOW_FIT");
        }
        return ExecutableEvaluation.allow();
    }

    private boolean applyObserveCooldownState(SmartWalletProfileEntity wallet) {
        if (wallet == null) {
            return false;
        }
        if (!properties.isObserveCooldownEnabled()) {
            wallet.setObserveCooldownUntil(null);
            wallet.setObserveCooldownReason(null);
            return false;
        }
        Date now = new Date();
        boolean triggered = shouldEnterObserveCooldown(wallet);
        boolean recoverEligible = shouldRecoverObserveCooldown(wallet);
        boolean active = wallet.getObserveCooldownUntil() != null && wallet.getObserveCooldownUntil().after(now);
        if (recoverEligible || (!triggered && !active)) {
            wallet.setObserveCooldownUntil(null);
            wallet.setObserveCooldownReason(null);
            return false;
        }
        if (triggered) {
            if (!active) {
                long cooldownDays = properties.getObserveCooldownDays() == null ? 7L : Math.max(properties.getObserveCooldownDays(), 1);
                wallet.setObserveCooldownUntil(new Date(now.getTime() + TimeUnit.DAYS.toMillis(cooldownDays)));
            }
            wallet.setObserveCooldownReason(buildObserveCooldownReason(wallet));
            return true;
        }
        return active;
    }

    private boolean shouldEnterObserveCooldown(SmartWalletProfileEntity wallet) {
        int recentTrades7d = wallet.getRecentTradeCount7d() == null ? 0 : wallet.getRecentTradeCount7d();
        int minTrades7d = properties.getObserveCooldownMinRecentTrades7d() == null ? 6 : properties.getObserveCooldownMinRecentTrades7d();
        if (recentTrades7d < minTrades7d) {
            return false;
        }
        BigDecimal recentWinRate7d = safe(wallet.getRecentWinRate7d());
        BigDecimal recentWinRate30d = firstNonNull(wallet.getRecentWinRate30d(), wallet.getWinRate());
        BigDecimal historicalWinRate = safe(wallet.getWinRate());
        return recentWinRate7d.compareTo(safe(properties.getObserveCooldownLowRecentWinRate7d())) < 0
                || recentWinRate30d.subtract(recentWinRate7d).compareTo(safe(properties.getObserveCooldownMaxRecent30dGap())) >= 0
                || historicalWinRate.subtract(recentWinRate7d).compareTo(safe(properties.getObserveCooldownMaxHistorical7dGap())) >= 0;
    }

    private boolean shouldRecoverObserveCooldown(SmartWalletProfileEntity wallet) {
        int recentTrades7d = wallet.getRecentTradeCount7d() == null ? 0 : wallet.getRecentTradeCount7d();
        int minTrades7d = properties.getObserveCooldownMinRecentTrades7d() == null ? 6 : properties.getObserveCooldownMinRecentTrades7d();
        if (recentTrades7d < minTrades7d) {
            return false;
        }
        BigDecimal recentWinRate7d = safe(wallet.getRecentWinRate7d());
        BigDecimal recentWinRate30d = firstNonNull(wallet.getRecentWinRate30d(), wallet.getWinRate());
        return recentWinRate7d.compareTo(safe(properties.getObserveCooldownRecoverMinRecentWinRate7d())) >= 0
                && recentWinRate30d.subtract(recentWinRate7d).compareTo(safe(properties.getObserveCooldownRecoverMaxRecent30dGap())) < 0;
    }

    private String buildObserveCooldownReason(SmartWalletProfileEntity wallet) {
        List<String> triggers = new ArrayList<>();
        BigDecimal recentWinRate7d = safe(wallet.getRecentWinRate7d());
        BigDecimal recentWinRate30d = firstNonNull(wallet.getRecentWinRate30d(), wallet.getWinRate());
        BigDecimal historicalWinRate = safe(wallet.getWinRate());
        if (recentWinRate7d.compareTo(safe(properties.getObserveCooldownLowRecentWinRate7d())) < 0) {
            triggers.add("LOW_7D_WIN_RATE");
        }
        if (recentWinRate30d.subtract(recentWinRate7d).compareTo(safe(properties.getObserveCooldownMaxRecent30dGap())) >= 0) {
            triggers.add("RECENT_30D_7D_GAP");
        }
        if (historicalWinRate.subtract(recentWinRate7d).compareTo(safe(properties.getObserveCooldownMaxHistorical7dGap())) >= 0) {
            triggers.add("HISTORICAL_7D_GAP");
        }
        return "[OBSERVE_COOLDOWN] triggers=" + String.join(",", triggers)
                + ", trades7d=" + (wallet.getRecentTradeCount7d() == null ? 0 : wallet.getRecentTradeCount7d())
                + ", winRate=" + historicalWinRate.setScale(4, RoundingMode.HALF_UP)
                + ", recentWinRate30d=" + recentWinRate30d.setScale(4, RoundingMode.HALF_UP)
                + ", recentWinRate7d=" + recentWinRate7d.setScale(4, RoundingMode.HALF_UP);
    }

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

    private void applyMonitorTierState(SmartWalletProfileEntity wallet) {
        applyMonitorTierState(wallet, new Date());
    }

    private void applyMonitorTierState(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null) {
            return;
        }
        Date effectiveNow = now == null ? new Date() : now;
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
        long daysSinceLastTrade = TimeUnit.MILLISECONDS.toDays(Math.max(0L, effectiveNow.getTime() - wallet.getLastTradeAt().getTime()));
        return daysSinceLastTrade > resolveColdWalletInactivityDays();
    }

    private boolean isWarmByInactivity(SmartWalletProfileEntity wallet, Date now) {
        if (wallet == null || wallet.getLastTradeAt() == null) {
            return false;
        }
        Date effectiveNow = now == null ? new Date() : now;
        long daysSinceLastTrade = TimeUnit.MILLISECONDS.toDays(Math.max(0L, effectiveNow.getTime() - wallet.getLastTradeAt().getTime()));
        return daysSinceLastTrade > resolveWarmWalletInactivityDays()
                && daysSinceLastTrade <= resolveColdWalletInactivityDays();
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
                && wallet.getLastDiscoveredAt().getTime() >= effectiveNow.getTime() - TimeUnit.DAYS.toMillis(WARM_RECENT_DISCOVERY_DAYS)) {
            reasons.add("RECENT_DISCOVERY_" + WARM_RECENT_DISCOVERY_DAYS + "D");
        }
        BigDecimal warmThreshold = safe(properties.getMinWalletScore()).subtract(WARM_SCORE_BUFFER).max(BigDecimal.ZERO);
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
        return value == null ? DEFAULT_COLD_INACTIVITY_DAYS : Math.max(value, resolveWarmWalletInactivityDays());
    }

    private long resolveWarmWalletInactivityDays() {
        Integer value = properties.getPolymarket().getWarmWalletInactivityDays();
        return value == null ? DEFAULT_WARM_INACTIVITY_DAYS : Math.max(value, 1);
    }

    private WalletBehaviorProfileView refreshBehaviorProfile(SmartWalletProfileEntity wallet) {
        if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
            return null;
        }
        walletBehaviorProfileService.evictProfileCache(wallet.getWalletAddress());
        try {
            return walletBehaviorProfileService.buildProfile(wallet);
        } catch (Exception e) {
            log.warn("Failed to refresh behavior profile for wallet {}: {}", wallet.getWalletAddress(), e.getMessage());
            return null;
        }
    }

    private BigDecimal scoreWallet(SmartWalletProfileEntity wallet, WalletBehaviorProfileView behaviorProfile) {
        return scoringService.score(
                safe(wallet.getWinRate()),
                wallet.getRecentWinRate7d(),
                wallet.getRecentWinRate30d(),
                safe(wallet.getRealizedPnlUsdc()),
                wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades(),
                wallet.getRecentTradeCount7d(),
                wallet.getRecentTradeCount30d(),
                safe(wallet.getAverageRoiRate()),
                safe(wallet.getMaxDrawdownRate()),
                behaviorProfile == null ? null : scoringService.behaviorComposite(behaviorProfile)
        );
    }

    private BigDecimal scoreWalletWithWinRate(SmartWalletProfileEntity wallet,
                                              WalletBehaviorProfileView behaviorProfile,
                                              BigDecimal effectiveWinRate) {
        return scoringService.score(
                safe(effectiveWinRate),
                wallet.getRecentWinRate7d(),
                wallet.getRecentWinRate30d(),
                safe(wallet.getRealizedPnlUsdc()),
                wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades(),
                wallet.getRecentTradeCount7d(),
                wallet.getRecentTradeCount30d(),
                safe(wallet.getAverageRoiRate()),
                safe(wallet.getMaxDrawdownRate()),
                behaviorProfile == null ? null : scoringService.behaviorComposite(behaviorProfile)
        );
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String normalizeWallet(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.trim().toLowerCase(Locale.ROOT);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal fallback) {
        return primary != null ? primary : fallback;
    }

    private Integer firstNonNull(Integer primary, Integer fallback) {
        return primary != null ? primary : fallback;
    }

    private SmartWalletProfileOverrideView toOverrideView(SmartWalletProfileEntity entity) {
        return SmartWalletProfileOverrideView.builder()
                .walletAddress(entity.getWalletAddress())
                .alias(entity.getAlias())
                .qualified(entity.getQualified())
                .watchEnabled(entity.getWatchEnabled())
                .manualWatchEnabled(entity.getManualWatchEnabled())
                .manualRecommendedExitLane(entity.getManualRecommendedExitLane())
                .manualOverrideNote(entity.getManualOverrideNote())
                .manualOverrideUpdatedAt(entity.getManualOverrideUpdatedAt())
                .build();
    }

    private int nextLeaderboardHitCount(Date previousDiscoveredAt, Integer currentHitCount, PolymarketWalletMetricsRequest request) {
        int increment = request.getLeaderboardHitCount30d() == null ? 1 : Math.max(request.getLeaderboardHitCount30d(), 1);
        if (previousDiscoveredAt == null) {
            return increment;
        }
        long elapsedMs = request.getLastDiscoveredAt().getTime() - previousDiscoveredAt.getTime();
        if (elapsedMs > TimeUnit.DAYS.toMillis(30)) {
            return increment;
        }
        return (currentHitCount == null ? 0 : currentHitCount) + increment;
    }

    private record QualificationEvaluation(boolean qualified,
                                           boolean hardFailure,
                                           boolean retentionEligible,
                                           String reason) {
    }

    private record ExecutableEvaluation(boolean allowed, String reason) {
        private static ExecutableEvaluation allow() {
            return new ExecutableEvaluation(true, "ALLOWED");
        }

        private static ExecutableEvaluation block(String reason) {
            return new ExecutableEvaluation(false, reason);
        }
    }
}
