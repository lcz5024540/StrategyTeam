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
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketWalletDiscoveryService {

    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketSmartWalletMonitorService monitorService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletProperties properties;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletLeaderboardStateService leaderboardStateService;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final AtomicInteger watchlistSyncCursor = new AtomicInteger(0);
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
        try {
            candidates = fetchLeaderboardCandidates(perQueryLimit, notes);
            leaderboardStateService.refreshSnapshot(candidates.stream()
                    .map(DiscoveryCandidate::toLeaderboardEntry)
                    .toList());
        } catch (Exception e) {
            log.warn("Failed to fetch Polymarket leaderboard", e);
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
                    continue;
                }
                monitorService.ingestWalletMetrics(metrics);
                importedProfiles++;
                analyzedWallets++;
            } catch (Exception e) {
                log.warn("Failed to analyze Polymarket wallet {}", candidate.getWalletAddress());
                notes.add("Analyze failed for " + candidate.getWalletAddress() + ": " + rootMessage(e));
            }
        }

        SmartWalletSelectionSummary selection = selectionService.refreshQualifiedWallets();
        if (syncTradesAfterRefresh) {
            try {
                importedTradeEvents = syncWatchlistTrades();
            } catch (Exception e) {
                log.warn("Failed to sync watchlist trades after discovery");
                notes.add("Trade sync failed after discovery: " + rootMessage(e));
            }
        }

        notes.add("Candidates come from Polymarket official leaderboard and are re-ranked by local wallet score.");
        notes.add("Wallet metrics are calculated from official closed positions and recent trade data.");
        notes.add("Leaderboard candidate pool is unioned across " + resolveLeaderboardQueries().size() + " query slices.");
        return PolymarketDiscoverySummary.builder()
                .candidateWallets(candidates.size())
                .analyzedWallets(analyzedWallets)
                .importedProfiles(importedProfiles)
                .importedTradeEvents(importedTradeEvents)
                .qualifiedCount(selection.getQualifiedCount())
                .watchlistCount(selection.getExecutableCount())
                .notes(notes)
                .build();
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
            log.warn("Failed to analyze Polymarket wallet {}: {}", normalizedWalletAddress, rootMessage(e));
            return false;
        }
        if (metrics == null || metrics.getTotalTrades() == null || metrics.getTotalTrades() <= 0) {
            return false;
        }

        if (!isQualified(metrics)) {
            return false;
        }

        SmartWalletProfileEntity entity = monitorService.ingestWalletMetrics(metrics);
        entity.setQualified(Boolean.TRUE);
        entity.setWatchEnabled(Boolean.TRUE);
        entity.setLastEvaluatedAt(new Date());
        profileStoreService.update(entity);
        return true;
    }

    public int syncWatchlistTrades() {
        return syncWatchlistTrades(profileStoreService.listWatchEnabled(), false);
    }

    public int syncWatchlistTradesBatch() {
        List<SmartWalletProfileEntity> wallets = selectWatchlistTradeBatch();
        if (wallets.isEmpty()) {
            return 0;
        }
        return syncWatchlistTrades(wallets, true);
    }

    private int syncWatchlistTrades(List<SmartWalletProfileEntity> wallets, boolean useCooldown) {
        int importedTradeEvents = 0;
        long now = System.currentTimeMillis();
        for (SmartWalletProfileEntity wallet : wallets) {
            if (wallet == null || !StringUtils.hasText(wallet.getWalletAddress())) {
                continue;
            }
            String walletKey = normalizeWalletKey(wallet.getWalletAddress());
            if (useCooldown && !isWalletSyncDue(walletKey, now)) {
                continue;
            }
            List<PolymarketApiClient.UserTrade> trades;
            try {
                trades = polymarketApiClient.fetchUserTrades(wallet.getWalletAddress());
                if (useCooldown) {
                    walletTradeNextAllowedAt.put(walletKey, now + dynamicTradeSyncIntervalMs(wallet));
                }
            } catch (Exception e) {
                log.warn("Skip syncing wallet {} because Polymarket trades request failed: {}",
                        wallet.getWalletAddress(), rootMessage(e));
                if (useCooldown) {
                    walletTradeNextAllowedAt.put(walletKey, now + tradeErrorCooldownMs());
                }
                continue;
            }
            for (PolymarketApiClient.UserTrade trade : trades) {
                if (trade == null || !StringUtils.hasText(trade.getTradeId())) {
                    continue;
                }
                try {
                    monitorService.ingestTradeEvent(toTradeEventRequest(trade, wallet));
                    importedTradeEvents++;
                } catch (Exception e) {
                    log.warn("Skip trade {} for wallet {}: {}",
                            trade.getTradeId(), wallet.getWalletAddress(), rootMessage(e));
                }
            }
        }
        return importedTradeEvents;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.discovery-fixed-delay-ms:3600000}")
    public void scheduledDiscoverWallets() {
        if (!monitorService.isDiscoveryEnabled()) {
            return;
        }
        try {
            analyzeTopWallets();
        } catch (Exception e) {
            log.warn("Scheduled Polymarket discovery failed: {}", rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.leaderboard-refresh-fixed-delay-ms:300000}")
    public void scheduledRefreshLeaderboardWatchlist() {
        if (!monitorService.isDiscoveryEnabled()) {
            return;
        }
        try {
            refreshLeaderboardWatchlist();
        } catch (Exception e) {
            log.warn("Scheduled Polymarket watchlist refresh failed: {}", rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.event-sync-fixed-delay-ms:30000}")
    public void scheduledSyncWatchlistTrades() {
        if (!monitorService.isEventSyncEnabled()) {
            return;
        }
        try {
            syncWatchlistTradesBatch();
        } catch (Exception e) {
            log.warn("Scheduled Polymarket trade sync failed: {}", rootMessage(e));
        }
    }

    private List<SmartWalletProfileEntity> selectWatchlistTradeBatch() {
        List<SmartWalletProfileEntity> wallets = profileStoreService.listWatchEnabled();
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
            String walletKey = normalizeWalletKey(wallet.getWalletAddress());
            if (!isWalletSyncDue(walletKey, now)) {
                continue;
            }
            result.add(wallet);
        }
        return result;
    }

    private boolean isWalletSyncDue(String walletKey, long now) {
        return now >= walletTradeNextAllowedAt.getOrDefault(walletKey, 0L);
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
        List<PolymarketApiClient.ClosedPosition> positions = polymarketApiClient.fetchClosedPositions(candidate.getWalletAddress());
        if (CollectionUtils.isEmpty(positions)) {
            return null;
        }
        List<PolymarketApiClient.UserTrade> userTrades = polymarketApiClient.fetchUserTrades(
                candidate.getWalletAddress(),
                properties.getPolymarket().getDiscoveryProfileTradeLimit()
        );

        PolymarketApiClient.WalletProfile profile = null;
        try {
            profile = polymarketApiClient.fetchProfile(candidate.getWalletAddress());
        } catch (Exception e) {
            log.debug("Failed to fetch Polymarket profile for {}", candidate.getWalletAddress(), e);
        }

        BigDecimal realizedPnl = BigDecimal.ZERO;
        BigDecimal totalSize = BigDecimal.ZERO;
        BigDecimal roiSum = BigDecimal.ZERO;
        int roiCount = 0;
        int wins = 0;
        int recentWins7d = 0;
        int recentTrades7d = 0;
        int recentWins = 0;
        int recentTrades = 0;
        long lastTradeAt = 0L;
        long recent7dCutoffMs = System.currentTimeMillis() - 7L * 24L * 3600_000L;
        long recentCutoffMs = System.currentTimeMillis() - 30L * 24L * 3600_000L;

        List<PolymarketApiClient.ClosedPosition> orderedPositions = positions.stream()
                .sorted(Comparator.comparing(PolymarketApiClient.ClosedPosition::getClosedAt,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();

        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peakEquity = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (PolymarketApiClient.ClosedPosition position : orderedPositions) {
            BigDecimal pnl = safe(position.getRealizedPnl()).add(safe(position.getCashPnl()));
            BigDecimal size = safe(position.getSizeBought());
            realizedPnl = realizedPnl.add(pnl);
            totalSize = totalSize.add(size);
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                wins++;
            }
            if (position.getClosedAt() != null && position.getClosedAt() >= recentCutoffMs) {
                recentTrades++;
                if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                    recentWins++;
                }
            }
            if (position.getClosedAt() != null && position.getClosedAt() >= recent7dCutoffMs) {
                recentTrades7d++;
                if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                    recentWins7d++;
                }
            }
            if (size.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal roi = pnl.divide(size, 6, RoundingMode.HALF_UP);
                roiSum = roiSum.add(roi.max(BigDecimal.ZERO).min(BigDecimal.ONE));
                roiCount++;
            }

            equity = equity.add(pnl);
            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
            }
            BigDecimal denominator = peakEquity.max(BigDecimal.ONE);
            BigDecimal drawdown = peakEquity.subtract(equity).max(BigDecimal.ZERO)
                    .divide(denominator, 6, RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
            if (position.getClosedAt() != null) {
                lastTradeAt = Math.max(lastTradeAt, position.getClosedAt());
            }
        }

        int totalTrades = orderedPositions.size();
        String alias = profile != null && StringUtils.hasText(profile.getAlias()) ? profile.getAlias() : candidate.getAlias();
        BigDecimal winRate = BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP);
        BigDecimal averageSize = totalTrades == 0 ? BigDecimal.ZERO
                : totalSize.divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP);
        BigDecimal averageRoi = roiCount == 0 ? BigDecimal.ZERO
                : roiSum.divide(BigDecimal.valueOf(roiCount), 4, RoundingMode.HALF_UP);
        BigDecimal recentWinRate7d = recentTrades7d == 0 ? null : BigDecimal.valueOf(recentWins7d)
                .divide(BigDecimal.valueOf(recentTrades7d), 4, RoundingMode.HALF_UP);
        BigDecimal recentWinRate30d = recentTrades == 0 ? null : BigDecimal.valueOf(recentWins)
                .divide(BigDecimal.valueOf(recentTrades), 4, RoundingMode.HALF_UP);
        SessionProfile sessionProfile = buildSessionProfile(userTrades);

        PolymarketWalletMetricsRequest request = new PolymarketWalletMetricsRequest();
        request.setWalletAddress(candidate.getWalletAddress());
        request.setChain("polygon");
        request.setAlias(alias);
        request.setWinRate(winRate);
        request.setRealizedPnlUsdc(realizedPnl.setScale(4, RoundingMode.HALF_UP));
        request.setTotalTrades(totalTrades);
        request.setAverageSizeUsdc(averageSize);
        request.setAverageRoiRate(averageRoi);
        request.setMaxDrawdownRate(maxDrawdown.setScale(4, RoundingMode.HALF_UP));
        request.setLastTradeAt(lastTradeAt > 0 ? new Date(lastTradeAt) : null);
        request.setLastDiscoveredAt(new Date());
        request.setLastDiscoveryQuery(candidate.getLastDiscoveryQuery());
        request.setLeaderboardHitCount30d(candidate.getLeaderboardHitCount());
        request.setDominantSession(sessionProfile.dominantSession);
        request.setAsiaTradeRatio30d(sessionProfile.asiaTradeRatio);
        request.setEuTradeRatio30d(sessionProfile.euTradeRatio);
        request.setUsTradeRatio30d(sessionProfile.usTradeRatio);
        request.setActiveHourHistogramJson(sessionProfile.activeHourHistogramJson);
        request.setRecentWinRate7d(recentWinRate7d);
        request.setRecentWinRate30d(recentWinRate30d);
        request.setRecentTradeCount7d(recentTrades7d);
        request.setRecentTradeCount30d(recentTrades);
        return request;
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
        request.setSizeUsdc(safe(trade.getSize()));
        request.setOpenedAt(trade.getTradedAt() == null ? new Date() : new Date(trade.getTradedAt()));
        return request;
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
}
