package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;

public record StrategyOrderRowView(
        Long id,
        Long followTaskId,
        String executionMode,
        String question,
        String outcome,
        String side,
        String status,
        BigDecimal price,
        BigDecimal size,
        BigDecimal notionalUsdc,
        BigDecimal expectedNetEvUsdc,
        BigDecimal estimatedFeeUsdc,
        BigDecimal estimatedSlippageUsdc,
        Integer evSampleSize,
        String marketEndAtBjt,
        String placedAt
) {
}
