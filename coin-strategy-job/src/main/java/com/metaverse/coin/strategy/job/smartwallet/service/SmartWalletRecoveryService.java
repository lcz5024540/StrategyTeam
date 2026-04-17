package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletRecoveryService {

    private final PolymarketMarketMetadataService marketMetadataService;
    private final PolymarketWalletDiscoveryService walletDiscoveryService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletSettlementSyncService settlementSyncService;
    private final SmartWalletAccountingService accountingService;

    private final AtomicBoolean recovering = new AtomicBoolean(false);

    private volatile Date lastRecoveredAt;
    private volatile Map<String, Object> lastSummary = Map.of();

    public Map<String, Object> recoverState(String reason) {
        if (!recovering.compareAndSet(false, true)) {
            return Map.of(
                    "executed", false,
                    "reason", "RECOVERY_ALREADY_RUNNING",
                    "lastRecoveredAt", lastRecoveredAt == null ? null : lastRecoveredAt.toInstant().toString()
            );
        }

        Date startedAt = new Date();
        Map<String, Object> steps = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        try {
            steps.put("metadataCache", marketMetadataService.clearAllCaches());
            executeStep("leaderboardRefresh", steps, notes, walletDiscoveryService::refreshLeaderboardWatchlist);
            executeStep("selectionRefresh", steps, notes, selectionService::refreshQualifiedWallets);
            executeStep("watchlistTradeSync", steps, notes, walletDiscoveryService::syncWatchlistTrades);
            executeStep("candidateReplay", steps, notes,
                    () -> followTaskService.replayRecentIntelligenceCandidates(200, true));
            executeStep("settlementResync", steps, notes,
                    () -> settlementSyncService.runNow(100, 50, true));
            executeStep("backfillMarketEndAt", steps, notes,
                    () -> accountingService.backfillMissingMarketEndAt(200, true));
            executeStep("rebuildRecentReports", steps, notes,
                    () -> accountingService.rebuildRecentReports(7));

            Map<String, Object> summary = Map.of(
                    "executed", true,
                    "reason", StringUtils.hasText(reason) ? reason : "manual",
                    "startedAt", startedAt.toInstant().toString(),
                    "finishedAt", OffsetDateTime.now().toString(),
                    "steps", steps,
                    "notes", notes
            );
            lastRecoveredAt = new Date();
            lastSummary = summary;
            log.warn("Smart-wallet state recovery finished. reason={} steps={}",
                    reason, steps.keySet());
            return summary;
        } finally {
            recovering.set(false);
        }
    }

    public Map<String, Object> status() {
        return Map.of(
                "recovering", recovering.get(),
                "lastRecoveredAt", lastRecoveredAt == null ? null : lastRecoveredAt.toInstant().toString(),
                "lastSummary", lastSummary
        );
    }

    private void executeStep(String stepName,
                             Map<String, Object> steps,
                             List<String> notes,
                             StepSupplier supplier) {
        try {
            steps.put(stepName, supplier.get());
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            steps.put(stepName, Map.of("success", false, "message", message));
            notes.add(stepName + " failed: " + message);
            log.warn("Smart-wallet recovery step {} failed: {}", stepName, message, ex);
        }
    }

    @FunctionalInterface
    private interface StepSupplier {
        Object get();
    }
}
