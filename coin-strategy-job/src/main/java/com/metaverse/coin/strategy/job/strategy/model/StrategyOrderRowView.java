package com.metaverse.coin.strategy.job.strategy.model;

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
        String marketEndAtBjt,
        String placedAt
) {
}
