package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.LeaderTradeOutcomeRebuildResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTruthRefreshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletLeaderTruthMaintenanceJob {

    private final SmartWalletProperties properties;
    private final LeaderTradeOutcomeRebuildService leaderTradeOutcomeRebuildService;
    private final SmartWalletProfileTruthRefreshService profileTruthRefreshService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.leader-truth-refresh-fixed-delay-ms:900000}")
    public void refreshLeaderTruthAndProfiles() {
        if (!properties.isEnabled() || !properties.getPolymarket().isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[LeaderTruthMaintenanceJob] Skip overlapping run.");
            return;
        }
        long started = System.currentTimeMillis();
        int batchSize = resolveBatchSize();
        try {
            LeaderTradeOutcomeRebuildResult rebuild = leaderTradeOutcomeRebuildService.rebuild(null, batchSize);
            List<SmartWalletProfileTruthRefreshResult> refreshed = profileTruthRefreshService.refreshProfiles(batchSize);
            long insufficient = refreshed.stream()
                    .filter(result -> result != null && !result.refreshed())
                    .count();
            long missingLoss = refreshed.stream()
                    .filter(result -> result != null && result.missingLossCount() > 0)
                    .count();
            log.info("[LeaderTruthMaintenanceJob] rebuiltWallets={} rebuiltOutcomes={} lowConfidenceOutcomes={} failedWallets={} refreshedProfiles={} insufficientProfiles={} missingLossProfiles={} elapsedMs={}",
                    rebuild.processedWallets(),
                    rebuild.rebuiltOutcomes(),
                    rebuild.lowConfidenceOutcomes(),
                    rebuild.failedWallets(),
                    refreshed.size(),
                    insufficient,
                    missingLoss,
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("[LeaderTruthMaintenanceJob] Failed to refresh leader truth/profile metrics: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    private int resolveBatchSize() {
        Integer configured = properties.getPolymarket().getLeaderTruthRefreshBatchSize();
        return configured == null ? 50 : Math.max(1, Math.min(configured, 500));
    }
}
