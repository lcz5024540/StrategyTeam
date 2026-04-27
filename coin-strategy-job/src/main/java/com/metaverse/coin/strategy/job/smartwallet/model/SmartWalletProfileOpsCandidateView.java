package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record SmartWalletProfileOpsCandidateView(
        String walletAddress,
        String alias,
        Boolean qualified,
        Boolean watchEnabled,
        Boolean manualWatchEnabled,
        String manualRecommendedExitLane,
        Date manualOverrideUpdatedAt,
        String archetype,
        String followLane,
        Boolean highFrequencyTradingAddress,
        BigDecimal orderWinRate,
        BigDecimal averageOrderRoiRate,
        BigDecimal settlementCompletionRate,
        BigDecimal earlyExitRate,
        BigDecimal replicabilityScore,
        String recommendedExitLane,
        String summary
) {
}
