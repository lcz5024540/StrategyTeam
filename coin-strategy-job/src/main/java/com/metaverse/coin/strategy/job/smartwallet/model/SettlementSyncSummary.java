package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SettlementSyncSummary {
    private Integer scannedPositions;
    private Integer distinctMarkets;
    private Integer fetchedMarkets;
    private Integer resolvedPositions;
    private Integer unresolvedMarkets;
    private Integer oraclePendingMarkets;
    private Integer skippedFutureMarkets;
    private Integer failedMarkets;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
    @Builder.Default
    private List<SettlementSyncFailure> failures = new ArrayList<>();
    @Builder.Default
    private List<SettlementRetryCandidate> retryCandidates = new ArrayList<>();
    @Builder.Default
    private List<String> processedMarketIds = new ArrayList<>();
}
