package com.metaverse.coin.strategy.core.definition;

public record StrategyDefinition(
        String strategyCode,
        String name,
        String category,
        String description,
        String riskLevel,
        String supportedMarket,
        StrategyExecutionPolicy executionPolicy,
        String feeMode,
        String status
) {
}
