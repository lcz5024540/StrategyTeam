package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * 钱包地址行为分型视图。
 *
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView}。
 * Phase 4.2 下沉到 common-service 是为了让 web 端 read service 能返回此类型。</p>
 */
@Builder
public record WalletArchetypeView(
        String walletAddress,
        String archetype,
        String followLane,
        String archetypeReason,
        BigDecimal archetypeConfidence,
        BigDecimal confidence,
        BigDecimal leaderQualityScore,
        BigDecimal followFitScore,
        Long recommendedSyncIntervalMs,
        Integer labelSampleSize,
        Integer trades7d,
        Integer trades30d,
        Integer dedupOrderCount7d,
        Integer dedupOrderCount30d,
        Integer markets30d,
        BigDecimal concentrationMarket30d,
        BigDecimal followableRate30d,
        BigDecimal alpha30s,
        BigDecimal alpha5m,
        BigDecimal alphaRule,
        BigDecimal medianInterTradeMinutes,
        BigDecimal medianHoldMinutes,
        String dataQuality,
        Boolean highFrequencyTradingAddress,
        Integer closedPositionSampleSize,
        BigDecimal orderWinRate,
        BigDecimal averageOrderRoiRate,
        BigDecimal medianOrderRoiRate,
        BigDecimal settlementCompletionRate,
        BigDecimal earlyExitRate,
        BigDecimal profitEarlyExitRate,
        BigDecimal lossEarlyExitRate,
        BigDecimal earlySettlementRate,
        String winSourceType,
        BigDecimal settlementWinRate,
        BigDecimal stabilityScore,
        BigDecimal qualityScore,
        BigDecimal replicabilityScore,
        String recommendedExitLane,
        String summary
) {
}
