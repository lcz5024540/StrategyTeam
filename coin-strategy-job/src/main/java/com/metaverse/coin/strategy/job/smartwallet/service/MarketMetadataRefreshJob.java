package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketMetadataRefreshJob {

    private final SmartWalletProperties properties;
    private final SmartWalletMarketMonitoringService marketMonitoringService;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletMarketStateService marketStateService;
    private final PolymarketApiClient polymarketApiClient;

    @Scheduled(fixedDelayString = "${smart-wallet.market-monitoring.metadata-refresh.refresh-fixed-delay-ms:300000}")
    public void refreshMetadata() {
        SmartWalletProperties.MarketMonitoring.MetadataRefresh config =
                properties.getMarketMonitoring().getMetadataRefresh();
        if (config == null || !config.isEnabled()) {
            return;
        }
        try {
            doRefresh(config);
        } catch (Exception e) {
            log.error("[MarketMetadataRefreshJob] Unexpected refresh failure: {}", e.getMessage(), e);
        }
    }

    private void doRefresh(SmartWalletProperties.MarketMonitoring.MetadataRefresh config) {
        int batchSize = config.getRefreshBatchSize() == null ? 120 : Math.max(1, Math.min(config.getRefreshBatchSize(), 300));
        int candidateLookbackDays = config.getCandidateLookbackDays() == null ? 30 : Math.max(3, Math.min(config.getCandidateLookbackDays(), 90));
        int maxCandidateMarkets = config.getMaxCandidateMarkets() == null ? 120 : Math.max(batchSize, Math.min(config.getMaxCandidateMarkets(), 300));
        int gammaChunkSize = config.getGammaChunkSize() == null ? 5 : Math.max(1, Math.min(config.getGammaChunkSize(), 100));
        long activeMaxStaleMs = config.getActiveMarketMaxStaleMs() == null ? 900000L : Math.max(config.getActiveMarketMaxStaleMs(), 60000L);
        long closedMaxStaleMs = config.getClosedMarketMaxStaleMs() == null ? 21600000L : Math.max(config.getClosedMarketMaxStaleMs(), activeMaxStaleMs);
        long startedAt = System.currentTimeMillis();

        List<String> incompleteActiveMarketIds = marketInfoService.listIncompleteActiveMarketIds(maxCandidateMarkets);
        Set<String> incompleteActiveMarketIdSet = normalizeMarketIds(incompleteActiveMarketIds);
        List<String> candidates = mergeCandidates(
                incompleteActiveMarketIds,
                marketMonitoringService.listCandidateMarketIds(maxCandidateMarkets, candidateLookbackDays),
                maxCandidateMarkets);
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, SmartWalletMarketStateService.MarketStateSnapshot> persistedStates =
                marketStateService.loadPersistedMarkets(candidates);
        int staleOrMissing = (int) candidates.stream()
                .filter(marketId -> isRefreshDue(marketId, persistedStates.get(marketId), incompleteActiveMarketIdSet, activeMaxStaleMs, closedMaxStaleMs))
                .count();

        List<String> refreshTargets = candidates.stream()
                .sorted(Comparator
                        .comparing((String marketId) -> incompleteActiveMarketIdSet.contains(marketId))
                        .reversed()
                        .thenComparing((String marketId) -> isRefreshDue(marketId, persistedStates.get(marketId), incompleteActiveMarketIdSet, activeMaxStaleMs, closedMaxStaleMs),
                                Comparator.reverseOrder())
                        .thenComparing(marketId -> lastSyncAt(persistedStates.get(marketId)),
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(batchSize)
                .toList();
        if (refreshTargets.isEmpty()) {
            return;
        }
        if (polymarketApiClient.isMetadataEndpointCoolingDown()) {
            log.info("[MarketMetadataRefreshJob] candidateCount={} staleOrMissing={} requested={} refreshed=0 skippedCooldown={} failed=0 elapsedMs={} currentChunkSize={}",
                    candidates.size(), staleOrMissing, refreshTargets.size(), refreshTargets.size(),
                    System.currentTimeMillis() - startedAt, gammaChunkSize);
            return;
        }

        Map<String, SmartWalletMarketStateService.MarketStateSnapshot> refreshed =
                marketStateService.refreshPersistedMarkets(refreshTargets, gammaChunkSize);
        if (!refreshed.isEmpty() || staleOrMissing > 0) {
            log.info("[MarketMetadataRefreshJob] candidateCount={} staleOrMissing={} requested={} refreshed={} skippedCooldown=0 failed={} elapsedMs={} currentChunkSize={}",
                    candidates.size(), staleOrMissing, refreshTargets.size(), refreshed.size(),
                    Math.max(0, refreshTargets.size() - refreshed.size()), System.currentTimeMillis() - startedAt, gammaChunkSize);
        }
    }

    private Set<String> normalizeMarketIds(List<String> marketIds) {
        if (marketIds == null || marketIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        addCandidates(normalized, marketIds, marketIds.size());
        return normalized;
    }

    private List<String> mergeCandidates(List<String> incompleteActiveMarketIds,
                                         List<String> monitoredMarketIds,
                                         int maxCandidateMarkets) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addCandidates(merged, incompleteActiveMarketIds, maxCandidateMarkets);
        addCandidates(merged, monitoredMarketIds, maxCandidateMarkets);
        return merged.stream().toList();
    }

    private void addCandidates(LinkedHashSet<String> merged, List<String> marketIds, int maxCandidateMarkets) {
        if (marketIds == null || marketIds.isEmpty()) {
            return;
        }
        for (String marketId : marketIds) {
            if (marketId == null || marketId.isBlank()) {
                continue;
            }
            merged.add(marketId.trim());
            if (merged.size() >= maxCandidateMarkets) {
                return;
            }
        }
    }

    private boolean isRefreshDue(String marketId,
                                 SmartWalletMarketStateService.MarketStateSnapshot snapshot,
                                 Set<String> forcedRefreshMarketIds,
                                 long activeMaxStaleMs,
                                 long closedMaxStaleMs) {
        if (forcedRefreshMarketIds.contains(marketId)) {
            return true;
        }
        if (snapshot == null || snapshot.lastMetadataSyncAt() == null) {
            return true;
        }
        long maxStaleMs = SmartWalletMarketStateService.LIFECYCLE_ACTIVE.equalsIgnoreCase(snapshot.marketLifecycle())
                ? activeMaxStaleMs
                : closedMaxStaleMs;
        return System.currentTimeMillis() - snapshot.lastMetadataSyncAt().getTime() > maxStaleMs;
    }

    private Date lastSyncAt(SmartWalletMarketStateService.MarketStateSnapshot snapshot) {
        return snapshot == null ? null : snapshot.lastMetadataSyncAt();
    }
}
