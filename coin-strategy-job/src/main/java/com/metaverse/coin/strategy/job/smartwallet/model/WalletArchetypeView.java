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
        String summary
) {
}
