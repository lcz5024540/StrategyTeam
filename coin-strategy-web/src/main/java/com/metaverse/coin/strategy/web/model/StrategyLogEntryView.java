package com.metaverse.coin.strategy.web.model;

public record StrategyLogEntryView(
        String timestamp,
        String level,
        String thread,
        String logger,
        String strategy,
        String source,
        String message,
        String action,
        String result,
        String flowStage,
        String eventId,
        String followTaskId,
        String wallet,
        String marketId,
        String outcome,
        String mode,
        String stakeUsdc,
        String entryLane,
        String matchedStrategies,
        String gateHighWaterMark,
        String firstRejectGate,
        String blockerCategories
) {
    public StrategyLogEntryView(String timestamp,
                                String level,
                                String thread,
                                String logger,
                                String strategy,
                                String source,
                                String message) {
        this(timestamp, level, thread, logger, strategy, source, message,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
