package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record WalletBehaviorProfileView(
        String walletAddress,
        String alias,
        String archetype,
        String followLane,
        String archetypeReason,
        BigDecimal archetypeConfidence,
        Boolean highFrequencyTradingAddress,
        Integer trades7d,
        Integer trades30d,
        Integer dedupOrderCount7d,
        Integer dedupOrderCount30d,
        BigDecimal winRate7d,
        BigDecimal winRate30d,
        BigDecimal medianInterTradeMinutes,
        BigDecimal medianHoldMinutes,
        String dataQuality,
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
        BigDecimal scoreConfidence,
        BigDecimal behaviorComposite,
        String recommendedExitLane,
        String summary,
        Date computedAt
) {
}
