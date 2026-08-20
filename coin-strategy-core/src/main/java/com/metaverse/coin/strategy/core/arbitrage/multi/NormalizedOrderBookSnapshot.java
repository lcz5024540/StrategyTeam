package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;

public record NormalizedOrderBookSnapshot(Exchange exchange, String canonicalSymbol,
                                          BigDecimal bestBid, BigDecimal bestAsk,
                                          BigDecimal bidNotional, BigDecimal askNotional,
                                          Instant receivedAt) {
    public NormalizedOrderBookSnapshot {
        if (exchange == null || canonicalSymbol == null || bestBid == null || bestAsk == null
                || bidNotional == null || askNotional == null || receivedAt == null
                || bestBid.signum() <= 0 || bestAsk.signum() <= 0 || bestAsk.compareTo(bestBid) < 0) {
            throw new IllegalArgumentException("invalid order book snapshot");
        }
    }
}
