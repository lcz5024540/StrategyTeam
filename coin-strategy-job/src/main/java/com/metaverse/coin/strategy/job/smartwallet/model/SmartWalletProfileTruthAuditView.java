package com.metaverse.coin.strategy.job.smartwallet.model;

import java.math.BigDecimal;

public record SmartWalletProfileTruthAuditView(
        String walletAddress,
        Integer profileTotalTrades,
        BigDecimal profileWinRate,
        long truthSampleCount,
        long lowConfidenceCount,
        long unresolvedCount,
        long missingLossCount,
        BigDecimal truthWinRate,
        BigDecimal truthPnlUsdc,
        String dataQuality,
        String recommendation
) {
}
