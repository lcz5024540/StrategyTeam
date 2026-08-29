package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record NormalizedOrderBookSnapshot(Exchange exchange, String canonicalSymbol,
                                          BigDecimal bestBid, BigDecimal bestAsk,
                                          BigDecimal bidNotional, BigDecimal askNotional,
                                          List<Level> bidLevels, List<Level> askLevels,
                                          Instant receivedAt) {
    public record Level(BigDecimal price, BigDecimal notional) {
        public Level {
            if (price == null || notional == null || price.signum() <= 0 || notional.signum() <= 0) {
                throw new IllegalArgumentException("invalid order book level");
            }
        }
    }

    public NormalizedOrderBookSnapshot {
        if (exchange == null || canonicalSymbol == null || bestBid == null || bestAsk == null
                || bidNotional == null || askNotional == null || receivedAt == null
                || bestBid.signum() <= 0 || bestAsk.signum() <= 0 || bestAsk.compareTo(bestBid) < 0) {
            throw new IllegalArgumentException("invalid order book snapshot");
        }
        bidLevels = List.copyOf(bidLevels);
        askLevels = List.copyOf(askLevels);
    }

    /** Compatibility constructor for snapshots recorded before L2 depth capture was introduced. */
    public NormalizedOrderBookSnapshot(Exchange exchange, String canonicalSymbol,
            BigDecimal bestBid, BigDecimal bestAsk, BigDecimal bidNotional,
            BigDecimal askNotional, Instant receivedAt) {
        this(exchange, canonicalSymbol, bestBid, bestAsk, bidNotional, askNotional,
                List.of(), List.of(), receivedAt);
    }
}
