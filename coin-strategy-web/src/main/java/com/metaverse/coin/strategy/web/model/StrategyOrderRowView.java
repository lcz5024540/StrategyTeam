package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;

public record StrategyOrderRowView(
        Long id,
        Long followTaskId,
        String executionMode,
        String question,
        String outcome,
        String side,
        String orderType,
        Boolean earlyExit,
        String exitReason,
        String status,
        BigDecimal price,
        BigDecimal size,
        BigDecimal notionalUsdc,
        BigDecimal costBasisUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal estimatedFeeUsdc,
        BigDecimal estimatedSlippageUsdc,
        BigDecimal followProbability,
        BigDecimal calibratedFollowProbability,
        BigDecimal slippageSpikeProbability,
        Integer estimateSampleSize,
        String marketEndAtBjt,
        String placedAt
) {
}
