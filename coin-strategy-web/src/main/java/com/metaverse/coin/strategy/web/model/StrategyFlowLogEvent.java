package com.metaverse.coin.strategy.web.model;

public record StrategyFlowLogEvent(
        String timestamp,
        String level,
        String thread,
        String logger,
        String strategy,
        String source,
        String rawMessage,
        String flowId,
        String flowStage,
        String action,
        String result,
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
        String blockerCategories,
        String reasonSummary
) {
}
