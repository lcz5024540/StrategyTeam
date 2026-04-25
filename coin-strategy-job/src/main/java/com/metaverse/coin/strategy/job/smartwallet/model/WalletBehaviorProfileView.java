package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record WalletBehaviorProfileView(
        String walletAddress,
        String alias,
        String archetype,
        String followLane,
        Boolean highFrequencyTradingAddress,
        Integer trades7d,
        Integer trades30d,
        BigDecimal medianInterTradeMinutes,
        Integer closedPositionSampleSize,
        BigDecimal orderWinRate,
        BigDecimal averageOrderRoiRate,
        BigDecimal medianOrderRoiRate,
        BigDecimal totalRealizedPnlUsdc,
        BigDecimal settlementCompletionRate,
        BigDecimal earlyExitRate,
        BigDecimal profitEarlyExitRate,
        BigDecimal lossEarlyExitRate,
        BigDecimal earlySettlementRate,
        String winSourceType,
        BigDecimal latencyAlphaRate,
        BigDecimal swingAlphaRate,
        BigDecimal settlementWinRate,
        BigDecimal stabilityScore,
        BigDecimal qualityScore,
        BigDecimal replicabilityScore,
        BigDecimal behaviorComposite,
        String recommendedExitLane,
        String summary
) {
}
