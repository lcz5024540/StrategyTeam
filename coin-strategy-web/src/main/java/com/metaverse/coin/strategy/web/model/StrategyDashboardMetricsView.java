package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;

public record StrategyDashboardMetricsView(
        BigDecimal tvlUsdc,
        BigDecimal todayPnlUsdc,
        BigDecimal capitalUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal pendingSettlementPnlUsdc,
        Integer totalOrders,
        Integer filledOrders,
        Integer openPositions,
        Integer pendingPositions,
        Integer closedPositions,
        Integer remainingPositionSlots,
        BigDecimal winRate,
        Integer winTrades,
        Integer lossTrades,
        BigDecimal maxDrawdownUsdc
) {
}
