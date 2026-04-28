package com.metaverse.coin.strategy.job.strategy.model;

import java.util.List;

public record StrategyLogFlowView(
        String flowId,
        String strategy,
        String source,
        String latestTimestamp,
        String status,
        String currentStage,
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
        String summary,
        List<StrategyFlowLogEvent> entries
) {
}
