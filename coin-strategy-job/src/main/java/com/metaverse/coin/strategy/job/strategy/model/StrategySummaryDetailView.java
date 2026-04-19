package com.metaverse.coin.strategy.job.strategy.model;

import java.util.List;

/**
 * 策略概要详情 —— 仅包含 summary + metrics + insights，不包含列表数据。
 * 用于首屏快速加载，替代原始的 StrategyDetailView 全量返回。
 */
public record StrategySummaryDetailView(
        StrategySummaryView summary,
        StrategyMetricsView metrics,
        List<StrategyInsightView> insights
) {
}
