package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;

public record StrategyPositionRowView(
        Long id,
        String marketId,
        String question,
        String outcome,
        String status,
        BigDecimal quantity,
        BigDecimal averageEntryPrice,
        BigDecimal costBasisUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal pendingSettlementPnlUsdc,
        String marketEndAtBjt
) {
}
