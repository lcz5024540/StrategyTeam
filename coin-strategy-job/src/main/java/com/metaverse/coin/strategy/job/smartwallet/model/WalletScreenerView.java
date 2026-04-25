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
        String screenerTier,
        Boolean highQualityAddress,
        Boolean highFrequencyTradingAddress,
        String winSourceType,
        BigDecimal qualityScore,
        BigDecimal replicabilityScore,
        BigDecimal stabilityScore,
        BigDecimal recentWinRate7d,
        BigDecimal recentWinRate30d,
        BigDecimal orderWinRate,
        BigDecimal averageOrderRoiRate,
        BigDecimal medianOrderRoiRate,
        BigDecimal settlementWinRate,
        BigDecimal followabilityRate,
        BigDecimal concentrationMarket30d,
        BigDecimal freshnessScore,
        String recommendedExitLane,
        Date lastTradeAt,
        String summary
) {
}
