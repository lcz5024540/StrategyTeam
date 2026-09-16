package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;

public record PaperOrder(String clientOrderId, HedgeLeg leg, boolean reduceOnly, BigDecimal requestedQuantity,
        BigDecimal filledQuantity, BigDecimal averagePrice, Status status, Instant updatedAt, String message) {
    public enum Status { CREATED, FILLED, PARTIALLY_FILLED, REJECTED, TIMED_OUT, CANCELLED }
}
