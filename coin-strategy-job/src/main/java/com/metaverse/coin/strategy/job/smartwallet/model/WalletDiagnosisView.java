package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record WalletDiagnosisView(
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
        BigDecimal settlementWinRate,
        BigDecimal latencyAlphaRate,
        BigDecimal swingAlphaRate,
        BigDecimal earlySettlementRate,
        Integer dedupOrderCount7d,
        Integer dedupOrderCount30d,
        BigDecimal medianHoldMinutes,
        String dataQuality,
        String recommendedExitLane,
        String summary,
        List<String> strengths,
        List<String> risks
) {
}
