package com.metaverse.coin.strategy.job.smartwallet.model;

import java.math.BigDecimal;

public record LeaderTradeOutcomeSummaryView(
        String walletAddress,
        String window,
        long outcomes,
        long closedOutcomes,
        long wins,
        long losses,
        long breakeven,
        long openOutcomes,
        long unresolvedOutcomes,
        long lowConfidenceOutcomes,
        BigDecimal winRate,
        BigDecimal buyCostUsdc,
        BigDecimal estimatedPnlUsdc,
        BigDecimal roiRate,
        String recommendation
) {
}
