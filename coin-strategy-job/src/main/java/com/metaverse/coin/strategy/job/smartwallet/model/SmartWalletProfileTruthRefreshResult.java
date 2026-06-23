package com.metaverse.coin.strategy.job.smartwallet.model;

import java.math.BigDecimal;

public record SmartWalletProfileTruthRefreshResult(
        String walletAddress,
        boolean refreshed,
        String status,
        int truthSampleCount,
        int lowConfidenceCount,
        int unresolvedCount,
        int missingLossCount,
        BigDecimal winRate,
        BigDecimal realizedPnlUsdc,
        BigDecimal walletScore
) {
}
