package com.metaverse.coin.strategy.core.domain;

import com.metaverse.coin.strategy.core.definition.StrategyExecutionPolicy;

import java.math.BigDecimal;
import java.time.Instant;

public record FollowOrder(
        String followOrderId,
        String subscriptionId,
        String signalId,
        String strategyCode,
        String orderStatus,
        String market,
        String symbolOrMarketId,
        String side,
        BigDecimal price,
        BigDecimal size,
        BigDecimal notional,
        StrategyExecutionPolicy executionMode,
        Instant executedAt
) {
}
