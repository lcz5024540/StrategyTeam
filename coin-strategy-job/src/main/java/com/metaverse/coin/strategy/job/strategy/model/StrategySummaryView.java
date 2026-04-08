package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;

public record StrategySummaryView(
        String code,
        String name,
        String category,
        String status,
        String description,
        Integer openPositions,
        BigDecimal compositePnlUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal pendingSettlementPnlUsdc,
        BigDecimal winRate,
        Integer totalOrders
) {
}
