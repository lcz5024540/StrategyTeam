package com.metaverse.coin.strategy.core.metrics;

public interface StrategyMetricsCalculator<T> {

    StrategyMetricsSnapshot calculate(T source);
}
