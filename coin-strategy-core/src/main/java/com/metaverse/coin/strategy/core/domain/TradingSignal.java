package com.metaverse.coin.strategy.core.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record TradingSignal(
        String signalId,
        String strategyCode,
        String strategyRunId,
        String signalType,
        String market,
        String symbolOrMarketId,
        String side,
        String action,
        BigDecimal confidence,
        String reason,
        BigDecimal triggerPrice,
        BigDecimal suggestedSize,
        Instant signalAt,
        Instant expireAt
) {
}
