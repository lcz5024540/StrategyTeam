package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;

public record StrategyDailyReportView(
        String reportDate,
        Integer openPositions,
        Integer closedPositions,
        BigDecimal totalVolumeUsdc,
        BigDecimal feeUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal winRate
) {
}
