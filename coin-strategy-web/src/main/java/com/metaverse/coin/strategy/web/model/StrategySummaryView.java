package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;

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
        Integer totalOrders,
        List<String> supportedModes,
        List<String> capabilities,
        String modeBehavior
) {
}
