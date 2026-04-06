package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;

public record StrategySummaryView(
        String code,
        String name,
        String category,
        String status,
        String description,
        Integer openPositions,
        BigDecimal netPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal winRate,
        Integer totalOrders
) {
}
