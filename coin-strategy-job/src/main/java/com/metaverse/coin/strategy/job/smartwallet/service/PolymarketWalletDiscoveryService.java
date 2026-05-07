package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.DiscoverySessionCoverageBucketView;
import com.metaverse.coin.strategy.job.smartwallet.model.DiscoverySessionCoverageView;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketWalletDiscoveryService {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";
    private static final String MONITOR_TIER_HOT = "HOT";
    private static final String MONITOR_TIER_WARM = "WARM";
    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketSmartWalletMonitorService monitorService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletProperties properties;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletLeaderboardStateService leaderboardStateService;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final WalletOrderAggregationService walletOrderAggregationService;
    private final LeaderTradeOutcomeRebuildService leaderTradeOutcomeRebuildService;
    private final SmartWalletProfileTruthRefreshService profileTruthRefreshService;
    private final AtomicInteger watchlistSyncCursor = new AtomicInteger(0);
    private final AtomicBoolean firstDiscoveryRun = new AtomicBoolean(true);
    private final AtomicBoolean firstLeaderboardRefreshRun = new AtomicBoolean(true);
    private final Map<String, Long> walletTradeNextAllowedAt = new ConcurrentHashMap<>();

    public PolymarketDiscoverySummary analyzeTopWallets() {
        return analyzeTopWallets(null, true);
    }

    public PolymarketDiscoverySummary refreshLeaderboardWatchlist() {
        return analyzeTopWallets(resolveRefreshTopN(), false);
    }

    public DiscoverySessionCoverageView sessionCoverage() {
        int lookbackDays = resolveSessionCoverageLookbackDays();
        Date since = new Date(System.currentTimeMillis() - lookbackDays * 24L * 3600_000L);
        List<SmartWalletProfileEntity> recentProfiles = profileStoreService.listAll().stream()
                .filter(item -> item.getLastDiscoveredAt() != null && !item.getLastDiscoveredAt().before(since))
                .toList();
        List<SmartWalletProfileEntity> recentWatchlist = profileStoreService.listWatchEnabled().stream()
                .filter(item -> item.getLastDiscoveredAt() != null && !item.getLastDiscoveredAt().before(since))
                .toList();
        return DiscoverySessionCoverageView.builder()
                .generatedAt(new Date())
                .lookbackDays(lookbackDays)
                .profileBuckets(bucketCoverage(recentProfiles))
                .watchlistBuckets(bucketCoverage(recentWatchlist))
                .build();
    }

    private PolymarketDiscoverySummary analyzeTopWallets(Integer perQueryLimit, boolean syncTradesAfterRefresh) {
        List<String> notes = new ArrayList<>();
        List<DiscoveryCandidate> candidates;
        log.info("{} action=发现Polymarket聪明钱包 result=开始 perQueryLimit={} syncTradesAfterRefresh={}",
                LOG_PREFIX, perQueryLimit, syncTradesAfterRefresh);
        try {
            candidates = fetchLeaderboardCandidates(perQueryLimit, notes);
            leaderboardStateService.refreshSnapshot(candidates.stream()
                    .map(DiscoveryCandidate::toLeaderboardEntry)
                    .toList());
            log.info("{} action=拉取Polymarket排行榜候选 result=完成 candidateCount={} querySlices={}",
                    LOG_PREFIX, candidates.size(), resolveLeaderboardQueries().size());
        } catch (Exception e) {
            log.warn("{} action=拉取Polymarket排行榜候选 result=失败 reason={}", LOG_PREFIX, rootMessage(e), e);
            notes.add("Leaderboard fetch failed: " + rootMessage(e));
            return PolymarketDiscoverySummary.builder()
                    .candidateWallets(0)
                    .analyzedWallets(0)
                    .importedProfiles(0)
                    .importedTradeEvents(0)
                    .qualifiedCount(profileStoreService.listQualified().size())
                    .watchlistCount(profileStoreService.listExecutableWatchlist().size())
                    .notes(notes)
                    .build();
        }

        int importedProfiles = 0;
        int analyzedWallets = 0;
        int importedTradeEvents = 0;

        for (DiscoveryCandidate candidate : candidates) {
            try {
                PolymarketWalletMetricsRequest metrics = buildMetrics(candidate);
                if (metrics == null || metrics.getTotalTrades() == null || metrics.getTotalTrades() == 0) {
                    log.info("{} action=分析Polymarket候选钱包 result=跳过 reason=无已结算交易 wallet={}",
                            LOG_PREFIX, candidate.getWalletAddress());
                    continue;
                }
                monitorService.ingestWalletMetrics(metrics);
                importedProfiles++;
                analyzedWallets++;
                log.info("{} action=分析Polymarket候选钱包 result=完成 wallet={} totalTrades={} winRate={} pnlUsdc={}",
                        LOG_PREFIX, candidate.getWalletAddress(), metrics.getTotalTrades(), metrics.getWinRate(),
                        metrics.getRealizedPnlUsdc());
            } catch (Exception e) {
                log.warn("{} action=分析Polymarket候选钱包 result=失败 wallet={} reason={}",
                        LOG_PREFIX, candidate.getWalletAddress(), rootMessage(e));
                notes.add("Analyze failed for " + candidate.getWalletAddress() + ": " + rootMessage(e));
            }
        }

        SmartWalletSelectionSummary selection = syncTradesAfterRefresh
                ? selectionService.refreshQualifiedWallets()
                : currentSelectionSummary();
        if (syncTradesAfterRefresh) {
            try {
                importedTradeEvents = syncWatchlistTrades();
            } catch (Exception e) {
                log.warn("{} action=发现后同步观察名单交易 result=失败 reason={}", LOG_PREFIX, rootMessage(e));
                notes.add("Trade sync failed after discovery: " + rootMessage(e));
            }
        }

        notes.add("Candidates come from Polymarket official leaderboard and are re-ranked by local wallet score.");
        notes.add("Wallet metrics are calculated from official closed positions and recent trade data.");
        notes.add("Leaderboard candidate pool is unioned across " + resolveLeaderboardQueries().size() + " query slices.");
        if (!syncTradesAfterRefresh) {
            notes.add("Incremental leaderboard refresh updates only wallets seen in this cycle; existing HOT wallets are not globally re-qualified here.");
        }
        PolymarketDiscoverySummary summary = PolymarketDiscoverySummary.builder()
                .candidateWallets(candidates.size())
                .analyzedWallets(analyzedWallets)
                .importedProfiles(importedProfiles)
                .importedTradeEvents(importedTradeEvents)
                .qualifiedCount(selection.getQualifiedCount())
                .watchlistCount(selection.getExecutableCount())
                .notes(notes)
                .build();
        log.info("{} action=发现Polymarket聪明钱包 result=完成 candidateWallets={} analyzedWallets={} importedProfiles={} importedTradeEvents={} qualifiedCount={} watchlistCount={}",
                LOG_PREFIX, summary.getCandidateWallets(), summary.getAnalyzedWallets(), summary.getImportedProfiles(),
                summary.getImportedTradeEvents(), summary.getQualifiedCount(), summary.getWatchlistCount());
        return summary;
    }

    private SmartWalletSelectionSummary currentSelectionSummary() {
        int qualifiedCount = safeSize(profileStoreService.listQualified());
        int executableCount = safeSize(profileStoreService.listExecutableWatchlist());
        return SmartWalletSelectionSummary.builder()
                .qualifiedCount(qualifiedCount)
                .executableCount(executableCount)
                .rejectedCount(Math.max(0, safeSize(profileStoreService.listAll()) - qualifiedCount))
                .notes(List.of("Incremental refresh preserved existing wallet admission state."))
                .build();
    }

    private int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    public boolean analyzeWalletAndAdmitWatchlist(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return false;
        }
        String normalizedWalletAddress = walletAddress.trim();
        DiscoveryCandidate candidate = DiscoveryCandidate.builder()
                .walletAddress(normalizedWalletAddress)
                .lastDiscoveryQuery("MANUAL_ADMIT")
                .leaderboardHitCount(1)
                .build();

        PolymarketWalletMetricsRequest metrics;
        try {
            metrics = buildMetrics(candidate);
        } catch (Exception e) {
            log.warn("{} action=手动分析并纳入观察名单 result=失败 wallet={} reason={}",
                    LOG_PREFIX, normalizedWalletAddress, rootMessage(e));
            return false;
        }
        if (metrics == null || metrics.getTotalTrades() == null || metrics.getTotalTrades() <= 0) {
            log.info("{} action=手动分析并纳入观察名单 result=拒绝 wallet={} reason=无已结算交易",
                    LOG_PREFIX, normalizedWalletAddress);
            return false;
        }

        if (!isQualified(metrics)) {
            log.info("{} action=手动分析并纳入观察名单 result=拒绝 wallet={} reason=未达到准入阈值 totalTrades={} winRate={} pnlUsdc={}",
                    LOG_PREFIX, normalizedWalletAddress, metrics.getTotalTrades(), metrics.getWinRate(),
                    metrics.getRealizedPnlUsdc());
            return false;
        }

        SmartWalletProfileEntity entity = monitorService.ingestWalletMetrics(metrics);
        entity.setQualified(Boolean.TRUE);
        entity.setWatchEnabled(Boolean.TRUE);
        entity.setLastEvaluatedAt(new Date());
        profileStoreService.update(entity);
        log.info("{} action=手动分析并纳入观察名单 result=通过 wallet={} profileId={} totalTrades={} winRate={} pnlUsdc={}",
                LOG_PREFIX, normalizedWalletAddress, entity.getId(), metrics.getTotalTrades(), metrics.getWinRate(),
                metrics.getRealizedPnlUsdc());
        return true;
    }

    public int syncWatchlistTrades() {
        return refreshWallets(profileStoreService.listExecutableWatchlist(), MonitoringRefreshMode.HOT_EVENT_SYNC, false);
    }

    public int syncWatchlistTradesBatch() {
        List<SmartWalletProfileEntity> wallets = selectHotWatchlistTradeBatch();
        if (wallets.isEmpty()) {
            return 0;
        }
        return refreshWallets(wallets, MonitoringRefreshMode.HOT_EVENT_SYNC, true);
    }

    public int refreshWarmWalletMetricsBatch() {
        List<SmartWalletProfileEntity> wallets = selectWarmMetricsRefreshBatch();
        if (wallets.isEmpty()) {
            return 0;
        }
        return refreshWallets(wallets, MonitoringRefreshMode.WARM_METRICS_REFRESH, true);
    }

    private int refreshWallets(List<SmartWalletProfileEntity> wallets,
                               MonitoringRefreshMode mode,
                               boolean enforceDueWindow) {
        return switch (mode) {
            case HOT_EVENT_SYNC -> refreshHotWallets(wallets, enforceDueWindow);
            case WARM_METRICS_REFRESH -> refreshWarmWalletMetrics(wallets, enforceDueWindow);
        };
    }

    private int refreshHotWallets(List<SmartWalletProfileEntity> wallets, boolean enforceDueWindow) {
        int importedTradeEvents = 0;
        long now = System.currentTimeMillis();
        for (SmartWalletProfileEntity wallet : wallets) {
            if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
                continue;
            }
            if (!isHotWallet(wallet)) {
                continue;
            }
            if (enforceDueWindow && !isHotWalletSyncDue(wallet, now)) {
                continue;
            }
            importedTradeEvents += refreshHotWallet(wallet, enforceDueWindow, now);
        }
        return importedTradeEvents;
    }

    private int refreshWarmWalletMetrics(List<SmartWalletProfileEntity> wallets, boolean enforceDueWindow) {
        int refreshedProfiles = 0;
        long now = System.currentTimeMillis();
        log.info("{} action=刷新WARM钱包指标批次 result=开始 walletCount={} enforceDueWindow={}",
                LOG_PREFIX, wallets == null ? 0 : wallets.size(), enforceDueWindow);
        for (SmartWalletProfileEntity wallet : wallets) {
            if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
                continue;
            }
            if (!isWarmWallet(wallet)) {
                continue;
            }
            if (enforceDueWindow && !isWarmMetricsRefreshDue(wallet, now)) {
                continue;
            }
            boolean wasWarm = isWarmWallet(wallet);
            SmartWalletProfileEntity updatedWallet = wallet;
            try {
                PolymarketWalletMetricsRequest metrics = buildMetrics(toRefreshCandidate(wallet), false);
                if (metrics != null && metrics.getTotalTrades() != null && metrics.getTotalTrades() > 0) {
                    updatedWallet = monitorService.ingestWalletMetrics(metrics);
                }
                Date refreshedAt = new Date(System.currentTimeMillis());
                if (updatedWallet != null) {
                    updatedWallet.setLastMetricsRefreshAt(refreshedAt);
                }
                profileStoreService.recordWarmMetricsRefresh(updatedWallet, refreshedAt);
                refreshedProfiles++;
                log.info("{} action=刷新WARM钱包指标 result=完成 wallet={} profileId={} monitorTier={} refreshedAt={}",
                        LOG_PREFIX, updatedWallet == null ? wallet.getWalletAddress() : updatedWallet.getWalletAddress(),
                        updatedWallet == null ? wallet.getId() : updatedWallet.getId(),
                        updatedWallet == null ? wallet.getMonitorTier() : updatedWallet.getMonitorTier(), refreshedAt);
                if (wasWarm && isHotWallet(updatedWallet)) {
                    log.info("{} action=WARM钱包晋升后补同步HOT交易 result=开始 wallet={}",
                            LOG_PREFIX, updatedWallet.getWalletAddress());
                    refreshHotWallet(updatedWallet, false, System.currentTimeMillis());
                }
            } catch (Exception e) {
                log.warn("{} action=刷新WARM钱包指标 result=失败 wallet={} reason={}",
                        LOG_PREFIX, wallet.getWalletAddress(), rootMessage(e));
            }
        }
        log.info("{} action=刷新WARM钱包指标批次 result=完成 refreshedProfiles={}",
                LOG_PREFIX, refreshedProfiles);
        return refreshedProfiles;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.discovery-fixed-delay-ms:3600000}")
    public void scheduledDiscoverWallets() {
        if (!monitorService.isDiscoveryEnabled()) {
            log.debug("{} action=定时发现Polymarket聪明钱包 result=跳过 reason=发现开关未开启", LOG_PREFIX);
            return;
        }
        if (shouldSkipInitialAddressPull(firstDiscoveryRun, "定时发现Polymarket聪明钱包")) {
            return;
        }
        try {
            log.info("{} action=定时发现Polymarket聪明钱包 result=开始", LOG_PREFIX);
            PolymarketDiscoverySummary summary = analyzeTopWallets();
            log.info("{} action=定时发现Polymarket聪明钱包 result=完成 candidateWallets={} importedProfiles={} importedTradeEvents={}",
                    LOG_PREFIX, summary.getCandidateWallets(), summary.getImportedProfiles(), summary.getImportedTradeEvents());
        } catch (Exception e) {
            log.warn("{} action=定时发现Polymarket聪明钱包 result=失败 reason={}", LOG_PREFIX, rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.leaderboard-refresh-fixed-delay-ms:300000}")
    public void scheduledRefreshLeaderboardWatchlist() {
        if (!monitorService.isDiscoveryEnabled()) {
            log.debug("{} action=定时刷新Polymarket观察名单 result=跳过 reason=发现开关未开启", LOG_PREFIX);
            return;
        }
        if (shouldSkipInitialAddressPull(firstLeaderboardRefreshRun, "定时刷新Polymarket观察名单")) {
            return;
        }
        try {
            log.info("{} action=定时刷新Polymarket观察名单 result=开始", LOG_PREFIX);
            PolymarketDiscoverySummary summary = refreshLeaderboardWatchlist();
            log.info("{} action=定时刷新Polymarket观察名单 result=完成 candidateWallets={} importedProfiles={} watchlistCount={}",
                    LOG_PREFIX, summary.getCandidateWallets(), summary.getImportedProfiles(), summary.getWatchlistCount());
        } catch (Exception e) {
            log.warn("{} action=定时刷新Polymarket观察名单 result=失败 reason={}", LOG_PREFIX, rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.event-sync-fixed-delay-ms:30000}")
    public void scheduledSyncWatchlistTrades() {
        if (!monitorService.isEventSyncEnabled()) {
            log.debug("{} action=定时同步Polymarket观察名单交易 result=跳过 reason=事件同步开关未开启", LOG_PREFIX);
            return;
        }
        try {
            log.info("{} action=定时同步Polymarket观察名单交易 result=开始", LOG_PREFIX);
            int imported = syncWatchlistTradesBatch();
            log.info("{} action=定时同步Polymarket观察名单交易 result=完成 importedTradeEvents={}",
                    LOG_PREFIX, imported);
        } catch (Exception e) {
            log.warn("{} action=定时同步Polymarket观察名单交易 result=失败 reason={}", LOG_PREFIX, rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.warm-metrics-refresh-fixed-delay-ms:600000}")
    public void scheduledRefreshWarmWalletMetrics() {
        if (!monitorService.isEventSyncEnabled()) {
            log.debug("{} action=定时刷新Polymarket WARM钱包指标 result=跳过 reason=事件同步开关未开启", LOG_PREFIX);
            return;
        }
        try {
            log.info("{} action=定时刷新Polymarket WARM钱包指标 result=开始", LOG_PREFIX);
            int refreshed = refreshWarmWalletMetricsBatch();
            log.info("{} action=定时刷新Polymarket WARM钱包指标 result=完成 refreshedProfiles={}",
                    LOG_PREFIX, refreshed);
        } catch (Exception e) {
            log.warn("{} action=定时刷新Polymarket WARM钱包指标 result=失败 reason={}", LOG_PREFIX, rootMessage(e));
        }
    }

    private boolean shouldSkipInitialAddressPull(AtomicBoolean firstRun, String action) {
        if (!firstRun.compareAndSet(true, false)) {
            return false;
        }
        long existingProfiles = profileStoreService.countPolymarketProfiles();
        if (existingProfiles <= 0) {
            return false;
        }
        log.info("{} action={} result=跳过 reason=启动首轮且数据库已有地址 existingProfiles={} nextRun=等待fixedDelay",
                LOG_PREFIX, action, existingProfiles);
        return true;
    }

    private List<SmartWalletProfileEntity> selectHotWatchlistTradeBatch() {
        List<SmartWalletProfileEntity> wallets = profileStoreService.listExecutableWatchlist();
        if (wallets.isEmpty()) {
            return Collections.emptyList();
        }
        wallets = wallets.stream()
                .sorted(Comparator
                        .comparingLong((SmartWalletProfileEntity wallet) ->
                                walletArchetypeClassifierService.recommendedSyncIntervalMs(wallet.getWalletAddress(), wallet))
                        .thenComparing(SmartWalletProfileEntity::getWalletScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int batchSize = Math.max(1, monitorService.getWatchlistTradeSyncBatchSize());
        int size = wallets.size();
        int start = Math.floorMod(watchlistSyncCursor.getAndAdd(batchSize), size);
        long now = System.currentTimeMillis();
        List<SmartWalletProfileEntity> result = new ArrayList<>();
        for (int offset = 0; offset < size && result.size() < batchSize; offset++) {
            SmartWalletProfileEntity wallet = wallets.get((start + offset) % size);
            if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
                continue;
            }
            if (!isHotWalletSyncDue(wallet, now)) {
                continue;
            }
            result.add(wallet);
        }
        return result;
    }

    private List<SmartWalletProfileEntity> selectWarmMetricsRefreshBatch() {
        long now = System.currentTimeMillis();
        return profileStoreService.listWarmWatchlist().stream()
                .filter(item -> item != null && StringUtils.hasText(item.getWalletAddress()))
                .filter(item -> isWarmMetricsRefreshDue(item, now))
                .sorted(Comparator
                        .comparing(SmartWalletProfileEntity::getLastMetricsRefreshAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SmartWalletProfileEntity::getWalletScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(resolveWarmMetricsRefreshBatchSize())
                .toList();
    }

    private int refreshHotWallet(SmartWalletProfileEntity wallet, boolean enforceDueWindow, long now) {
        String walletKey = normalizeWalletKey(wallet.getWalletAddress());
        List<PolymarketApiClient.UserTrade> trades;
        log.info("{} action=同步HOT钱包交易 result=开始 wallet={} enforceDueWindow={} lastEventSyncAt={}",
                LOG_PREFIX, wallet.getWalletAddress(), enforceDueWindow, wallet.getLastEventSyncAt());
        try {
            trades = fetchIncrementalTradesForHotWallet(wallet);
            if (enforceDueWindow) {
                walletTradeNextAllowedAt.put(walletKey, now + dynamicTradeSyncIntervalMs(wallet));
            }
        } catch (Exception e) {
            log.warn("{} action=同步HOT钱包交易 result=失败 wallet={} reason={}",
                    LOG_PREFIX, wallet.getWalletAddress(), rootMessage(e));
            if (enforceDueWindow) {
                walletTradeNextAllowedAt.put(walletKey, now + tradeErrorCooldownMs());
            }
            return 0;
        }

        int importedTradeEvents = 0;
        long checkpointMillis = 0L;
        for (PolymarketApiClient.UserTrade trade : trades) {
            if (trade == null || !StringUtils.hasText(trade.getTradeId())) {
                continue;
            }
            if (trade.getTradedAt() != null) {
                checkpointMillis = Math.max(checkpointMillis, trade.getTradedAt());
            }
            try {
                monitorService.ingestTradeEvent(toTradeEventRequest(trade, wallet));
                importedTradeEvents++;
                log.info("{} action=导入HOT钱包交易 result=完成 wallet={} tradeId={} marketId={} outcome={} side={} tradedAt={}",
                        LOG_PREFIX, wallet.getWalletAddress(), trade.getTradeId(), trade.getMarketId(),
                        trade.getOutcome(), trade.getSide(), trade.getTradedAt());
            } catch (Exception e) {
                log.warn("{} action=导入HOT钱包交易 result=失败 wallet={} tradeId={} reason={}",
                        LOG_PREFIX, wallet.getWalletAddress(), trade.getTradeId(), rootMessage(e));
            }
        }
        profileStoreService.recordHotEventSync(wallet, resolveHotSyncCheckpoint(checkpointMillis, now));
        log.info("{} action=同步HOT钱包交易 result=完成 wallet={} fetchedTrades={} importedTradeEvents={} checkpointMillis={}",
                LOG_PREFIX, wallet.getWalletAddress(), trades.size(), importedTradeEvents, checkpointMillis);
        return importedTradeEvents;
    }

    private List<PolymarketApiClient.UserTrade> fetchIncrementalTradesForHotWallet(SmartWalletProfileEntity wallet) {
        Date checkpoint = wallet == null ? null : wallet.getLastEventSyncAt();
        if (needsWarmPromotionBackfill(wallet)) {
            return fetchBackfillTradesAfterCheckpoint(wallet, checkpoint);
        }
        List<PolymarketApiClient.UserTrade> recentTrades = polymarketApiClient.fetchUserTrades(wallet.getWalletAddress());
        return filterTradesAfterCheckpoint(recentTrades, checkpoint);
    }

    private List<PolymarketApiClient.UserTrade> fetchBackfillTradesAfterCheckpoint(SmartWalletProfileEntity wallet, Date checkpoint) {
        int pageSize = Math.max(1, properties.getPolymarket().getRecentTradeLimit());
        int offset = 0;
        boolean reachedCheckpoint = false;
        Map<String, PolymarketApiClient.UserTrade> deduped = new LinkedHashMap<>();
        while (!reachedCheckpoint && offset <= PolymarketApiClient.MAX_USER_TRADE_HISTORY_OFFSET) {
            List<PolymarketApiClient.UserTrade> chunk = polymarketApiClient.fetchUserTrades(wallet.getWalletAddress(), pageSize, offset);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            for (PolymarketApiClient.UserTrade trade : chunk) {
                if (trade == null || !StringUtils.hasText(trade.getTradeId())) {
                    continue;
                }
                if (checkpoint != null && trade.getTradedAt() != null && trade.getTradedAt() <= checkpoint.getTime()) {
                    reachedCheckpoint = true;
                    break;
                }
                deduped.putIfAbsent(trade.getTradeId(), trade);
            }
            if (chunk.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return orderTradesChronologically(deduped.values());
    }

    private List<PolymarketApiClient.UserTrade> filterTradesAfterCheckpoint(List<PolymarketApiClient.UserTrade> trades, Date checkpoint) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        List<PolymarketApiClient.UserTrade> filtered = trades.stream()
                .filter(Objects::nonNull)
                .filter(trade -> StringUtils.hasText(trade.getTradeId()))
                .filter(trade -> checkpoint == null || trade.getTradedAt() == null || trade.getTradedAt() > checkpoint.getTime())
                .toList();
        return orderTradesChronologically(filtered);
    }

    private List<PolymarketApiClient.UserTrade> orderTradesChronologically(java.util.Collection<PolymarketApiClient.UserTrade> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        return trades.stream()
                .sorted(Comparator.comparing(PolymarketApiClient.UserTrade::getTradedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean isHotWalletSyncDue(SmartWalletProfileEntity wallet, long now) {
        if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
            return false;
        }
        if (needsWarmPromotionBackfill(wallet)) {
            return true;
        }
        String walletKey = normalizeWalletKey(wallet.getWalletAddress());
        if (now < walletTradeNextAllowedAt.getOrDefault(walletKey, 0L)) {
            return false;
        }
        Date lastEventSyncAt = wallet.getLastEventSyncAt();
        if (lastEventSyncAt == null) {
            return true;
        }
        return now - lastEventSyncAt.getTime() >= dynamicTradeSyncIntervalMs(wallet);
    }

    private boolean isWarmMetricsRefreshDue(SmartWalletProfileEntity wallet, long now) {
        if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
            return false;
        }
        Date lastMetricsRefreshAt = wallet.getLastMetricsRefreshAt();
        if (lastMetricsRefreshAt == null) {
            return true;
        }
        return now - lastMetricsRefreshAt.getTime() >= warmWalletRefreshMinIntervalMs();
    }

    private boolean needsWarmPromotionBackfill(SmartWalletProfileEntity wallet) {
        if (!isHotWallet(wallet) || wallet == null || wallet.getLastMetricsRefreshAt() == null) {
            return false;
        }
        return wallet.getLastEventSyncAt() == null || wallet.getLastMetricsRefreshAt().after(wallet.getLastEventSyncAt());
    }

    private boolean isHotWallet(SmartWalletProfileEntity wallet) {
        return wallet != null && MONITOR_TIER_HOT.equalsIgnoreCase(wallet.getMonitorTier());
    }

    private boolean isWarmWallet(SmartWalletProfileEntity wallet) {
        return wallet != null && MONITOR_TIER_WARM.equalsIgnoreCase(wallet.getMonitorTier());
    }

    private Date resolveHotSyncCheckpoint(long checkpointMillis, long fallbackNow) {
        return new Date(Math.max(checkpointMillis, fallbackNow));
    }

    private long tradeSyncMinIntervalMs() {
        Long value = monitorService.getWalletTradeSyncMinIntervalMs();
        return value == null ? 120000L : Math.max(value, 5000L);
    }

    private long dynamicTradeSyncIntervalMs(SmartWalletProfileEntity wallet) {
        long baseline = tradeSyncMinIntervalMs();
        if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
            return baseline;
        }
        long recommended = walletArchetypeClassifierService.recommendedSyncIntervalMs(wallet.getWalletAddress(), wallet);
        return Math.max(5_000L, Math.min(recommended, Math.max(baseline, recommended)));
    }

    private long tradeErrorCooldownMs() {
        Long value = monitorService.getWalletTradeErrorCooldownMs();
        return value == null ? 300000L : Math.max(value, tradeSyncMinIntervalMs());
    }

    private long warmWalletRefreshMinIntervalMs() {
        Long value = properties.getPolymarket().getWarmWalletRefreshMinIntervalMs();
        return value == null ? 1800000L : Math.max(value, 60000L);
    }

    private int resolveWarmMetricsRefreshBatchSize() {
        Integer value = properties.getPolymarket().getWarmMetricsRefreshBatchSize();
        return value == null ? 10 : Math.max(1, Math.min(value, 200));
    }

    private int resolveAnalyzeTopN() {
        return Math.max(0, monitorService.getAnalyzeTopN());
    }

    private int resolveRefreshTopN() {
        SmartWalletProperties.PolymarketMonitor monitor = properties.getPolymarket();
        Integer refreshTopN = monitor == null ? null : monitor.getLeaderboardRefreshTopN();
        if (refreshTopN == null || refreshTopN <= 0) {
            return Math.max(1, Math.min(resolveAnalyzeTopN(), 10));
        }
        return Math.max(1, refreshTopN);
    }

    private String normalizeWalletKey(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.trim().toLowerCase();
    }

    private PolymarketWalletMetricsRequest buildMetrics(DiscoveryCandidate candidate) {
        return buildMetrics(candidate, true);
    }

    private PolymarketWalletMetricsRequest buildMetrics(DiscoveryCandidate candidate, boolean includeDiscoveryMetadata) {
        List<PolymarketApiClient.UserTrade> userTrades = polymarketApiClient.fetchUserTrades(
                candidate.getWalletAddress(),
                properties.getPolymarket().getDiscoveryProfileTradeLimit()
        );

        PolymarketApiClient.WalletProfile profile = null;
        try {
            profile = polymarketApiClient.fetchProfile(candidate.getWalletAddress());
        } catch (Exception e) {
            log.debug("{} action=拉取Polymarket钱包资料 result=失败 wallet={} reason={}",
                    LOG_PREFIX, candidate.getWalletAddress(), rootMessage(e), e);
        }

        String alias = profile != null && StringUtils.hasText(profile.getAlias()) ? profile.getAlias() : candidate.getAlias();
        SessionProfile sessionProfile = buildSessionProfile(userTrades);

        try {
            leaderTradeOutcomeRebuildService.rebuild(candidate.getWalletAddress(), null);
        } catch (Exception e) {
            log.warn("{} action=重建Polymarket钱包真实闭环 result=失败 wallet={} reason={}",
                    LOG_PREFIX, candidate.getWalletAddress(), rootMessage(e), e);
        }
        SmartWalletProfileTruthRefreshService.DiscoveryMetadata metadata = null;
        if (includeDiscoveryMetadata) {
            metadata = new SmartWalletProfileTruthRefreshService.DiscoveryMetadata(
                    new Date(),
                    candidate.getLastDiscoveryQuery(),
                    candidate.getLeaderboardHitCount(),
                    sessionProfile.dominantSession,
                    sessionProfile.asiaTradeRatio,
                    sessionProfile.euTradeRatio,
                    sessionProfile.usTradeRatio,
                    sessionProfile.activeHourHistogramJson
            );
        }
        PolymarketWalletMetricsRequest truthMetrics = profileTruthRefreshService.buildTruthMetrics(
                candidate.getWalletAddress(),
                alias,
                metadata
        );
        if (truthMetrics == null || truthMetrics.getTotalTrades() == null || truthMetrics.getTotalTrades() <= 0) {
            log.info("{} action=分析Polymarket候选钱包 result=跳过 reason=INSUFFICIENT_TRUTH_DATA wallet={}",
                    LOG_PREFIX, candidate.getWalletAddress());
            return null;
        }
        if (!includeDiscoveryMetadata) {
            truthMetrics.setDominantSession(sessionProfile.dominantSession);
            truthMetrics.setAsiaTradeRatio30d(sessionProfile.asiaTradeRatio);
            truthMetrics.setEuTradeRatio30d(sessionProfile.euTradeRatio);
            truthMetrics.setUsTradeRatio30d(sessionProfile.usTradeRatio);
            truthMetrics.setActiveHourHistogramJson(sessionProfile.activeHourHistogramJson);
        }
        return truthMetrics;
    }

    private DiscoveryCandidate toRefreshCandidate(SmartWalletProfileEntity wallet) {
        return DiscoveryCandidate.builder()
                .walletAddress(wallet.getWalletAddress())
                .alias(wallet.getAlias())
                .leaderboardHitCount(wallet.getLeaderboardHitCount30d() == null ? 0 : wallet.getLeaderboardHitCount30d())
                .lastDiscoveryQuery(wallet.getLastDiscoveryQuery())
                .build();
    }

    private PolymarketTradeEventRequest toTradeEventRequest(PolymarketApiClient.UserTrade trade,
                                                            SmartWalletProfileEntity wallet) {
        PolymarketTradeEventRequest request = new PolymarketTradeEventRequest();
        String canonicalConditionId = marketMetadataService.resolveCanonicalConditionId(
                trade.getMarketId(),
                trade.getQuestion(),
                StringUtils.hasText(trade.getOutcome()) ? List.of(trade.getOutcome()) : List.of()
        );
        request.setWalletAddress(wallet.getWalletAddress());
        request.setChain(wallet.getChain());
        request.setExternalTradeId(trade.getTradeId());
        request.setMarketId(canonicalConditionId);
        request.setQuestion(trade.getQuestion());
        request.setOutcome(trade.getOutcome());
        request.setSide(trade.getSide());
        request.setPrice(safe(trade.getPrice()));
        request.setSizeShares(safe(trade.getSizeShares()));
        request.setSizeUsdc(resolveTradeNotionalUsdc(trade));
        request.setOpenedAt(trade.getTradedAt() == null ? new Date() : new Date(trade.getTradedAt()));
        return request;
    }

    private BigDecimal resolveTradeNotionalUsdc(PolymarketApiClient.UserTrade trade) {
        if (trade == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal explicitNotional = safe(trade.getSizeUsdc());
        if (explicitNotional.compareTo(BigDecimal.ZERO) > 0) {
            return explicitNotional;
        }
        BigDecimal price = safe(trade.getPrice());
        BigDecimal shares = safe(trade.getSizeShares());
        if (price.compareTo(BigDecimal.ZERO) > 0 && shares.compareTo(BigDecimal.ZERO) > 0) {
            return shares.multiply(price).setScale(8, RoundingMode.HALF_UP);
        }
        return safe(trade.getSize());
    }

    private boolean isQualified(PolymarketWalletMetricsRequest metrics) {
        BigDecimal winRate = safe(metrics.getWinRate());
        BigDecimal realizedPnlUsdc = safe(metrics.getRealizedPnlUsdc());
        int totalTrades = metrics.getTotalTrades() == null ? 0 : metrics.getTotalTrades();
        BigDecimal averageRoiRate = safe(metrics.getAverageRoiRate());
        BigDecimal maxDrawdownRate = safe(metrics.getMaxDrawdownRate());
        BigDecimal walletScore = scoringService.score(
                winRate,
                metrics.getRecentWinRate7d(),
                metrics.getRecentWinRate30d(),
                realizedPnlUsdc,
                totalTrades,
                metrics.getRecentTradeCount7d(),
                metrics.getRecentTradeCount30d(),
                averageRoiRate,
                maxDrawdownRate
        );

        return winRate.compareTo(properties.getMinWinRate()) >= 0
                && walletScore.compareTo(properties.getMinWalletScore()) >= 0
                && totalTrades >= properties.getMinTotalTrades()
                && realizedPnlUsdc.compareTo(properties.getMinRealizedPnlUsdc()) >= 0
                && maxDrawdownRate.compareTo(properties.getMaxDrawdownRate()) <= 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }

    private List<DiscoveryCandidate> fetchLeaderboardCandidates(Integer perQueryLimit, List<String> notes) {
        Map<String, DiscoveryCandidate> deduped = new LinkedHashMap<>();
        for (SmartWalletProperties.PolymarketMonitor.LeaderboardQuery query : resolveLeaderboardQueries()) {
            SmartWalletProperties.PolymarketMonitor.LeaderboardQuery effectiveQuery = effectiveQuery(query, perQueryLimit);
            List<PolymarketApiClient.LeaderboardEntry> entries;
            try {
                entries = polymarketApiClient.fetchLeaderboard(effectiveQuery);
            } catch (Exception ex) {
                notes.add("Leaderboard fetch failed for " + queryKey(effectiveQuery) + ": " + rootMessage(ex));
                continue;
            }
            for (PolymarketApiClient.LeaderboardEntry entry : entries) {
                if (entry == null || !StringUtils.hasText(entry.getWalletAddress())) {
                    continue;
                }
                String walletKey = normalizeWalletKey(entry.getWalletAddress());
                DiscoveryCandidate existing = deduped.get(walletKey);
                if (existing == null) {
                    deduped.put(walletKey, DiscoveryCandidate.builder()
                            .walletAddress(entry.getWalletAddress())
                            .alias(entry.getAlias())
                            .pnl(entry.getPnl())
                            .volume(entry.getVolume())
                            .leaderboardHitCount(1)
                            .lastDiscoveryQuery(queryKey(effectiveQuery))
                            .build());
                    continue;
                }
                existing.setLeaderboardHitCount(existing.getLeaderboardHitCount() + 1);
                existing.setLastDiscoveryQuery(queryKey(effectiveQuery));
                if (!StringUtils.hasText(existing.getAlias()) && StringUtils.hasText(entry.getAlias())) {
                    existing.setAlias(entry.getAlias());
                }
                if (entry.getPnl() != null && (existing.getPnl() == null || entry.getPnl().compareTo(existing.getPnl()) > 0)) {
                    existing.setPnl(entry.getPnl());
                }
                if (entry.getVolume() != null && (existing.getVolume() == null || entry.getVolume().compareTo(existing.getVolume()) > 0)) {
                    existing.setVolume(entry.getVolume());
                }
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private SmartWalletProperties.PolymarketMonitor.LeaderboardQuery effectiveQuery(
            SmartWalletProperties.PolymarketMonitor.LeaderboardQuery query,
            Integer perQueryLimit) {
        SmartWalletProperties.PolymarketMonitor.LeaderboardQuery effective = new SmartWalletProperties.PolymarketMonitor.LeaderboardQuery();
        effective.setCategory(query.getCategory());
        effective.setTimePeriod(query.getTimePeriod());
        effective.setOrderBy(query.getOrderBy());
        int queryLimit = query.getLimit() == null ? properties.getPolymarket().getLeaderboardLimit() : query.getLimit();
        effective.setLimit(perQueryLimit == null ? queryLimit : Math.min(queryLimit, Math.max(perQueryLimit, 1)));
        return effective;
    }

    private List<SmartWalletProperties.PolymarketMonitor.LeaderboardQuery> resolveLeaderboardQueries() {
        List<SmartWalletProperties.PolymarketMonitor.LeaderboardQuery> queries = properties.getPolymarket().getLeaderboardQueries();
        if (queries != null && !queries.isEmpty()) {
            return queries;
        }
        SmartWalletProperties.PolymarketMonitor.LeaderboardQuery pnlQuery = new SmartWalletProperties.PolymarketMonitor.LeaderboardQuery();
        pnlQuery.setCategory(properties.getPolymarket().getLeaderboardCategory());
        pnlQuery.setTimePeriod(properties.getPolymarket().getLeaderboardTimePeriod());
        pnlQuery.setOrderBy(properties.getPolymarket().getLeaderboardOrderBy());
        pnlQuery.setLimit(properties.getPolymarket().getLeaderboardLimit());

        SmartWalletProperties.PolymarketMonitor.LeaderboardQuery volumeQuery = new SmartWalletProperties.PolymarketMonitor.LeaderboardQuery();
        volumeQuery.setCategory(properties.getPolymarket().getLeaderboardCategory());
        volumeQuery.setTimePeriod(properties.getPolymarket().getLeaderboardTimePeriod());
        volumeQuery.setOrderBy("VOLUME");
        volumeQuery.setLimit(properties.getPolymarket().getLeaderboardLimit());
        return List.of(pnlQuery, volumeQuery);
    }

    private String queryKey(SmartWalletProperties.PolymarketMonitor.LeaderboardQuery query) {
        return String.join("|",
                safeText(query.getCategory(), "OVERALL"),
                safeText(query.getTimePeriod(), "ALL"),
                safeText(query.getOrderBy(), "PNL"),
                String.valueOf(query.getLimit() == null ? properties.getPolymarket().getLeaderboardLimit() : query.getLimit()));
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : fallback;
    }

    private SessionProfile buildSessionProfile(List<PolymarketApiClient.UserTrade> userTrades) {
        int[] hourlyHistogram = new int[24];
        if (CollectionUtils.isEmpty(userTrades)) {
            return SessionProfile.empty();
        }
        int asiaCount = 0;
        int euCount = 0;
        int usCount = 0;
        int consideredTrades = 0;
        long cutoffMs = System.currentTimeMillis() - resolveSessionCoverageLookbackDays() * 24L * 3600_000L;
        for (PolymarketApiClient.UserTrade trade : userTrades) {
            if (trade == null || trade.getTradedAt() == null || trade.getTradedAt() < cutoffMs) {
                continue;
            }
            int utcHour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(trade.getTradedAt()), ZoneOffset.UTC).getHour();
            hourlyHistogram[utcHour]++;
            consideredTrades++;
            if (utcHour < 8) {
                asiaCount++;
            } else if (utcHour < 16) {
                euCount++;
            } else {
                usCount++;
            }
        }
        if (consideredTrades == 0) {
            return SessionProfile.empty();
        }
        BigDecimal asiaRatio = ratio(asiaCount, consideredTrades);
        BigDecimal euRatio = ratio(euCount, consideredTrades);
        BigDecimal usRatio = ratio(usCount, consideredTrades);
        String dominantSession = resolveDominantSession(asiaRatio, euRatio, usRatio);
        return new SessionProfile(
                dominantSession,
                asiaRatio,
                euRatio,
                usRatio,
                histogramJson(hourlyHistogram)
        );
    }

    private int resolveSessionCoverageLookbackDays() {
        Integer value = properties.getPolymarket().getSessionCoverageLookbackDays();
        return value == null ? 30 : Math.max(value, 1);
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private String resolveDominantSession(BigDecimal asiaRatio, BigDecimal euRatio, BigDecimal usRatio) {
        Map<String, BigDecimal> ratios = new HashMap<>();
        ratios.put("ASIA", safe(asiaRatio));
        ratios.put("EU", safe(euRatio));
        ratios.put("US", safe(usRatio));
        Map.Entry<String, BigDecimal> dominant = ratios.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (dominant == null || dominant.getValue().compareTo(new BigDecimal("0.50")) < 0) {
            return "MIXED";
        }
        return dominant.getKey();
    }

    private String histogramJson(int[] hourlyHistogram) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < hourlyHistogram.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(hourlyHistogram[i]);
        }
        return builder.append(']').toString();
    }

    private List<DiscoverySessionCoverageBucketView> bucketCoverage(List<SmartWalletProfileEntity> profiles) {
        Map<String, List<SmartWalletProfileEntity>> grouped = profiles.stream()
                .collect(Collectors.groupingBy(item -> StringUtils.hasText(item.getDominantSession())
                        ? item.getDominantSession().trim().toUpperCase()
                        : "UNKNOWN", LinkedHashMap::new, Collectors.toList()));
        List<String> orderedSessions = List.of("ASIA", "EU", "US", "MIXED", "UNKNOWN");
        List<DiscoverySessionCoverageBucketView> result = new ArrayList<>();
        for (String session : orderedSessions) {
            List<SmartWalletProfileEntity> bucket = grouped.get(session);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            result.add(DiscoverySessionCoverageBucketView.builder()
                    .dominantSession(session)
                    .walletCount(bucket.size())
                    .avgWinRate(avg(bucket.stream().map(SmartWalletProfileEntity::getWinRate).toList()))
                    .avgRecentWinRate30d(avg(bucket.stream().map(SmartWalletProfileEntity::getRecentWinRate30d).toList()))
                    .avgWalletScore(avg(bucket.stream().map(SmartWalletProfileEntity::getWalletScore).toList()))
                    .avgAsiaTradeRatio30d(avg(bucket.stream().map(SmartWalletProfileEntity::getAsiaTradeRatio30d).toList()))
                    .avgEuTradeRatio30d(avg(bucket.stream().map(SmartWalletProfileEntity::getEuTradeRatio30d).toList()))
                    .avgUsTradeRatio30d(avg(bucket.stream().map(SmartWalletProfileEntity::getUsTradeRatio30d).toList()))
                    .build());
        }
        return result;
    }

    private BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> normalized = values.stream()
                .filter(item -> item != null)
                .toList();
        if (normalized.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : normalized) {
            total = total.add(value);
        }
        return total.divide(BigDecimal.valueOf(normalized.size()), 4, RoundingMode.HALF_UP);
    }

    @lombok.Data
    @lombok.Builder
    private static class DiscoveryCandidate {
        private String walletAddress;
        private String alias;
        private BigDecimal pnl;
        private BigDecimal volume;
        private int leaderboardHitCount;
        private String lastDiscoveryQuery;

        private PolymarketApiClient.LeaderboardEntry toLeaderboardEntry() {
            return PolymarketApiClient.LeaderboardEntry.builder()
                    .walletAddress(walletAddress)
                    .alias(alias)
                    .pnl(pnl)
                    .volume(volume)
                    .build();
        }
    }

    private record SessionProfile(
            String dominantSession,
            BigDecimal asiaTradeRatio,
            BigDecimal euTradeRatio,
            BigDecimal usTradeRatio,
            String activeHourHistogramJson
    ) {
        private static SessionProfile empty() {
            return new SessionProfile(
                    "MIXED",
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    "[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]"
            );
        }
    }

    private enum MonitoringRefreshMode {
        HOT_EVENT_SYNC,
        WARM_METRICS_REFRESH
    }
}
