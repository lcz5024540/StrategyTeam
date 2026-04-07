package com.metaverse.coin.strategy.job.strategy.model;

import java.util.List;

public record StrategyDetailView(
        StrategySummaryView summary,
        StrategyMetricsView metrics,
        List<StrategyInsightView> insights,
        List<StrategyPositionRowView> positions,
        List<StrategyPositionRowView> nextSettlements,
        List<StrategyOrderRowView> recentOrders,
        List<StrategyTimelinePointView> timeline,
        List<StrategyConflictRowView> conflicts
) {
}
