package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record WalletScreenerView(
        String walletAddress,
        String alias,
        String archetype,
        String followLane,
        String archetypeReason,
        BigDecimal archetypeConfidence,
        String screenerTier,
        Boolean highQualityAddress,
        Boolean highFrequencyTradingAddress,
        String winSourceType,
        BigDecimal qualityScore,
        BigDecimal replicabilityScore,
        BigDecimal stabilityScore,
        BigDecimal recentWinRate7d,
        BigDecimal recentWinRate30d,
        Integer dedupOrderCount7d,
        Integer dedupOrderCount30d,
        BigDecimal medianHoldMinutes,
        String dataQuality,
        BigDecimal orderWinRate,
        BigDecimal averageOrderRoiRate,
        BigDecimal medianOrderRoiRate,
        BigDecimal settlementWinRate,
        BigDecimal followabilityRate,
        BigDecimal concentrationMarket30d,
        BigDecimal freshnessScore,
        String recommendedExitLane,
        Date lastTradeAt,
        String summary,
        Date behaviorComputedAt,
        Boolean behaviorStale,
        Boolean fallbackUsed
) {
}
