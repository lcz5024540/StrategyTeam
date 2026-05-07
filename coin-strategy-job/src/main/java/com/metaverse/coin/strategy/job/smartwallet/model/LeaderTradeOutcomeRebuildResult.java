package com.metaverse.coin.strategy.job.smartwallet.model;

public record LeaderTradeOutcomeRebuildResult(
        int requestedWallets,
        int processedWallets,
        int fetchedTrades,
        int rebuiltOutcomes,
        int lowConfidenceOutcomes,
        int failedWallets
) {
}
