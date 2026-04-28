package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;

/**
 * Dashboard 首屏指标，只保留跨策略通用 KPI。
 * 策略专属字段应进入专属 module、列表接口或 metadata。
 */
public record StrategyDashboardMetricsView(
        BigDecimal tvlUsdc,
        BigDecimal todayPnlUsdc,
        BigDecimal capitalUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal pendingSettlementPnlUsdc,
        Integer totalOrders,
        Integer openPositions,
        Integer closedPositions,
        BigDecimal winRate,
        Integer winTrades,
        Integer lossTrades,
        BigDecimal maxDrawdownUsdc
) {
}
