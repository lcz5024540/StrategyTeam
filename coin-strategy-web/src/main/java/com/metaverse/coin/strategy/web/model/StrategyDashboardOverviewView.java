package com.metaverse.coin.strategy.web.model;

import java.util.List;

public record StrategyDashboardOverviewView(
        String generatedAtBjt,
        String selectedStrategyCode,
        List<StrategyRegistryView> strategies
) {
}
