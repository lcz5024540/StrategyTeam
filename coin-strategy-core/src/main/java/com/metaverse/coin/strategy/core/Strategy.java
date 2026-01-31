package com.metaverse.coin.strategy.core;

public interface Strategy {
    /**
     * Unique identifier for the strategy instance.
     */
    String getStrategyId();

    /**
     * Start the strategy execution.
     */
    void start();

    /**
     * Stop the strategy execution.
     */
    void stop();
    
    /**
     * Check if the strategy is running.
     */
    boolean isRunning();
}
