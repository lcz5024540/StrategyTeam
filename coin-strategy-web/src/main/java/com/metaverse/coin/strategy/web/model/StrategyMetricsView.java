package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;

public record StrategyMetricsView(
        Integer totalOrders,
        Integer filledOrders,
        Integer openPositions,
        Integer closedPositions,
        Integer winningPositions,
        Integer losingPositions,
        BigDecimal winRate,
        BigDecimal tradedVolumeUsdc,
        BigDecimal feeUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal profitFactor,
        BigDecimal maxDrawdownUsdc,
        Integer conflictingMarkets,
        Integer openConflictingMarkets,
        BigDecimal conflictNetPnlUsdc,
        Integer remainingPositionSlots,
        BigDecimal remainingCapitalBudgetUsdc,
        BigDecimal availableCapitalUsdc,
        BigDecimal lockedClaimProceedsUsdc,
        Integer earlyExitOrderCount,
        BigDecimal earlyExitRealizedPnlUsdc,
        Integer takeProfitOrderCount,
        BigDecimal takeProfitRealizedPnlUsdc,
        Integer stopLossOrderCount,
        BigDecimal stopLossRealizedPnlUsdc
) {
}
