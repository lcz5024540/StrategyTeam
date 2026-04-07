package com.metaverse.coin.strategy.core.metrics;

import java.math.BigDecimal;

public record StrategyMetricsSnapshot(
        BigDecimal cumulativePnl,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal maxDrawdown,
        BigDecimal winRate,
        BigDecimal profitFactor,
        BigDecimal sharpeRatio,
        Integer tradeCount,
        Integer openPositionCount
) {
}
