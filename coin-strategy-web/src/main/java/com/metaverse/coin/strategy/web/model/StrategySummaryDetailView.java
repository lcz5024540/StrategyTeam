package com.metaverse.coin.strategy.web.model;

import java.util.List;

public record StrategySummaryDetailView(
        StrategyIdentityView summary,
        StrategyDashboardMetricsView metrics,
        List<StrategyInsightView> insights
) {
}
