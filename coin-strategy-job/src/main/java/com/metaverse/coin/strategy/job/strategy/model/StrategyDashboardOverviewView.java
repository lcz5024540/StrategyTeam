package com.metaverse.coin.strategy.job.strategy.model;

import java.util.List;

public record StrategyDashboardOverviewView(
        String generatedAtBjt,
        String selectedStrategyCode,
        List<StrategyRegistryView> strategies
) {
}
