package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketMetadataRefreshJob {

    private final SmartWalletProperties properties;
    private final SmartWalletMarketMonitoringService marketMonitoringService;
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

        List<String> candidates = marketMonitoringService.listCandidateMarketIds(maxCandidateMarkets, candidateLookbackDays);
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, SmartWalletMarketStateService.MarketStateSnapshot> persistedStates =
                marketStateService.loadPersistedMarkets(candidates);
        int staleOrMissing = (int) candidates.stream()
                .filter(marketId -> isRefreshDue(persistedStates.get(marketId), activeMaxStaleMs, closedMaxStaleMs))
                .count();

        List<String> refreshTargets = candidates.stream()
                .sorted(Comparator
                        .comparing((String marketId) -> isRefreshDue(persistedStates.get(marketId), activeMaxStaleMs, closedMaxStaleMs))
                        .reversed()
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

    private boolean isRefreshDue(SmartWalletMarketStateService.MarketStateSnapshot snapshot,
                                 long activeMaxStaleMs,
                                 long closedMaxStaleMs) {
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
