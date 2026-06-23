package com.metaverse.coin.strategy.job.smartwallet.model;

import java.math.BigDecimal;

public record LeaderTradeOutcomeCoverageView(
        String walletAddress,
        long leaderOutcomes,
        long closedOutcomes,
        long followedOutcomes,
        long skippedOutcomes,
        long notMatchedOutcomes,
        BigDecimal leaderWinRate,
        BigDecimal followedWinRate,
        BigDecimal skippedWinRate,
        BigDecimal leaderPnlUsdc,
        BigDecimal followedPnlUsdc,
        BigDecimal skippedPnlUsdc,
        BigDecimal eventFollowRate,
        String recommendation
) {
}
