package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;
import java.util.List;

public record StrategyTaskRowView(
        Long id,
        String displayId,
        String walletAddress,
        String marketId,
        String question,
        String outcome,
        String direction,
        String status,
        BigDecimal recommendedStakeUsdc,
        BigDecimal expectedNetEvUsdc,
        BigDecimal scoreValue,
        String scoreLabel,
        Integer blockerCount,
        List<String> blockerCategories,
        String marketEndAtBjt,
        String scheduledAt,
        String executionMode
) {
}
