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
        BigDecimal medianHoldMinutes,
        String recommendedExitLane,
        String summary,
        List<String> strengths,
        List<String> risks
) {
}
