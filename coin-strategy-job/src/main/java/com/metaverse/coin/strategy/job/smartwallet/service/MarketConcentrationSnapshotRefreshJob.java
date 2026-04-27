package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketConcentrationSnapshotEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketConcentrationSnapshotRefreshJob {

    private final SmartWalletProperties properties;
    private final SmartWalletMarketMonitoringService marketMonitoringService;
    private final MarketConcentrationService concentrationService;
    private final MarketConcentrationSnapshotService concentrationSnapshotService;

    @Scheduled(fixedDelayString = "${smart-wallet.market-monitoring.concentration-snapshot.refresh-fixed-delay-ms:180000}")
    public void refreshSnapshots() {
        SmartWalletProperties.MarketMonitoring.ConcentrationSnapshot config =
                properties.getMarketMonitoring().getConcentrationSnapshot();
        if (config == null || !config.isEnabled()) {
            return;
        }
        try {
            doRefresh(config);
        } catch (Exception e) {
            log.error("[ConcentrationSnapshotJob] Unexpected refresh failure: {}", e.getMessage(), e);
        }
    }

    private void doRefresh(SmartWalletProperties.MarketMonitoring.ConcentrationSnapshot config) {
        int batchSize = config.getRefreshBatchSize() == null ? 24 : Math.max(1, Math.min(config.getRefreshBatchSize(), 80));
        int candidateLookbackDays = config.getCandidateLookbackDays() == null ? 30 : Math.max(3, Math.min(config.getCandidateLookbackDays(), 90));
        int maxCandidateMarkets = config.getMaxCandidateMarkets() == null ? 60 : Math.max(batchSize, Math.min(config.getMaxCandidateMarkets(), 160));
        long minRefreshIntervalMs = config.getMinMarketRefreshIntervalMs() == null ? 300000L : Math.max(config.getMinMarketRefreshIntervalMs(), 60000L);

        List<String> candidates = marketMonitoringService.listCandidateMarketIds(maxCandidateMarkets, candidateLookbackDays);
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, SmartWalletMarketConcentrationSnapshotEntity> existingSnapshots =
                concentrationSnapshotService.batchGetLatestEntities(candidates, null);

        int refreshed = 0;
        int skippedFresh = 0;
        int unavailable = 0;
        for (String marketId : candidates) {
            if (refreshed >= batchSize) {
                break;
            }
            SmartWalletMarketConcentrationSnapshotEntity existing = existingSnapshots.get(marketId);
            if (!shouldRefresh(existing, minRefreshIntervalMs)) {
                skippedFresh++;
                continue;
            }
            MarketConcentrationService.ConcentrationSnapshot snapshot = concentrationService.analyze(marketId, null);
            if (snapshot == null || !snapshot.isDataAvailable()) {
                unavailable++;
                continue;
            }
            concentrationSnapshotService.upsert(marketId, null, snapshot);
            refreshed++;
        }

        if (refreshed > 0 || unavailable > 0) {
            log.info("[ConcentrationSnapshotJob] candidates={} refreshed={} skippedFresh={} unavailable={} batchSize={} minRefreshIntervalMs={}",
                    candidates.size(), refreshed, skippedFresh, unavailable, batchSize, minRefreshIntervalMs);
        }
    }

    private boolean shouldRefresh(SmartWalletMarketConcentrationSnapshotEntity existing, long minRefreshIntervalMs) {
        return existing == null
                || existing.getSnapshotAt() == null
                || System.currentTimeMillis() - existing.getSnapshotAt().getTime() >= minRefreshIntervalMs;
    }
}
