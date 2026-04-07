package com.metaverse.coin.strategy.core.domain;

import com.metaverse.coin.strategy.core.definition.StrategyExecutionPolicy;

import java.math.BigDecimal;
import java.time.Instant;

public record FollowSubscription(
        String subscriptionId,
        String userId,
        String strategyCode,
        String status,
        StrategyExecutionPolicy executionMode,
        BigDecimal budgetLimit,
        Integer positionLimit,
        String riskPreset,
        String feePlan,
        Instant startedAt,
        Instant endedAt
) {
}
