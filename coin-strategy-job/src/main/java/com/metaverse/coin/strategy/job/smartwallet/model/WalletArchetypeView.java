package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record WalletArchetypeView(
        String walletAddress,
        String archetype,
        String followLane,
        BigDecimal confidence,
        BigDecimal leaderQualityScore,
        BigDecimal followFitScore,
        Long recommendedSyncIntervalMs,
        Integer labelSampleSize,
        Integer trades7d,
        Integer trades30d,
        Integer markets30d,
        BigDecimal concentrationMarket30d,
        BigDecimal followableRate30d,
        BigDecimal alpha30s,
        BigDecimal alpha5m,
        BigDecimal alphaRule,
        BigDecimal medianInterTradeMinutes,
        Boolean highFrequencyTradingAddress,
        Integer closedPositionSampleSize,
        BigDecimal orderWinRate,
        BigDecimal averageOrderRoiRate,
        BigDecimal medianOrderRoiRate,
        BigDecimal settlementCompletionRate,
        BigDecimal earlyExitRate,
        BigDecimal profitEarlyExitRate,
        BigDecimal lossEarlyExitRate,
        String winSourceType,
        BigDecimal settlementWinRate,
        BigDecimal medianHoldMinutes,
        BigDecimal stabilityScore,
        BigDecimal qualityScore,
        BigDecimal replicabilityScore,
        String recommendedExitLane,
        String summary
) {
}
